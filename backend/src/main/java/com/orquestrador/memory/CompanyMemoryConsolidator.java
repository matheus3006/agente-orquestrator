package com.orquestrador.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O consolidador das PROJEÇÕES sempre-on (ADR-0019, emendada pela Frente C): PROJEÇÃO
 * DETERMINÍSTICA sobre o Decision-log → nova versão de {@code company_memory} — a CARTA
 * (direções vigentes, grau=DECISAO) e o PLAYBOOK (princípios selados, grau=PRINCIPIO), gêmeas
 * por construção. ZERO LLM no caminho de escrita (defesa contra memory-poisoning: as projeções
 * são derivadas, nunca auto-escritas pelo agente). Idempotente por conteúdo: reprojetar sem
 * Decisão nova não gera versão idêntica (savelog sem ruído). Linhagem respeitada: uma Decisão
 * SUPERSEDIDA (outra a aponta em {@code supersedes}, de QUALQUER grau/tipo) sai da projeção —
 * a projeção é o vigente, o log é a história.
 */
@Service
public class CompanyMemoryConsolidator {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /**
     * O CAP por projeção (C7 — a assinatura anti-inchaço): o sempre-on nunca projeta mais que
     * isto, HARD por construção. Capa-se a PROJEÇÃO, nunca o log (história append-only, C8):
     * o excedente segue no log, endereçável pelo {@code #N}, e vira nudge de estouro.
     */
    static final int CAP_CARTA = 12;
    static final int CAP_PLAYBOOK = 12;

    private final DecisionRepository decisions;
    private final CompanyMemoryRepository memories;
    private final GraduationDetector graduationDetector;

    public CompanyMemoryConsolidator(DecisionRepository decisions, CompanyMemoryRepository memories,
                                     GraduationDetector graduationDetector) {
        this.decisions = decisions;
        this.memories = memories;
        this.graduationDetector = graduationDetector;
    }

    @Transactional
    public void refresh(UUID companyId) {
        // Ordem do ENDEREÇO (C4): o #N nasce em ordem de commit no chokepoint — determinística
        // por construção, sem empate de timestamp.
        List<Decision> log = decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .sorted(Comparator.comparingInt(Decision::getSeq))
                .toList();
        Set<UUID> supersedidas = log.stream()
                .map(Decision::getSupersedesId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        projetaCarta(companyId, log, supersedidas);
        projetaPlaybook(companyId, log, supersedidas);
    }

    private void projetaCarta(UUID companyId, List<Decision> log, Set<UUID> supersedidas) {
        // Grau filtra a superfície (C9): a carta é das DIREÇÕES (grau=DECISAO); princípio de
        // direção mora no playbook — duplicar nas duas inflaria o sempre-on que o cap protege.
        List<Decision> vigentes = log.stream()
                .filter(decision -> decision.getTipo() == DecisionTipo.DIRECAO)
                .filter(decision -> decision.getGrau() == DecisionGrau.DECISAO)
                .filter(decision -> !supersedidas.contains(decision.getId()))
                .toList();
        List<Decision> janela = janela(vigentes, CAP_CARTA);
        List<Decision> excedente = vigentes.subList(0, vigentes.size() - janela.size());
        List<String> nudges = new java.util.ArrayList<>();
        if (!excedente.isEmpty()) {
            nudges.add(nudgeEstouroCarta(excedente));
        }
        for (GraduationDetector.Candidata candidata : graduationDetector.candidatas(log)) {
            nudges.add(nudgeCandidata(candidata));
        }
        salvaSeMudou(companyId, MemoryKind.CARTA, !vigentes.isEmpty(), renderCarta(janela, nudges));
    }

    private void projetaPlaybook(UUID companyId, List<Decision> log, Set<UUID> supersedidas) {
        List<Decision> vigentes = log.stream()
                .filter(decision -> decision.getGrau() == DecisionGrau.PRINCIPIO)
                .filter(decision -> !supersedidas.contains(decision.getId()))
                .toList();
        List<Decision> janela = janela(vigentes, CAP_PLAYBOOK);
        List<Decision> excedente = vigentes.subList(0, vigentes.size() - janela.size());
        List<String> nudges = new java.util.ArrayList<>();
        if (!excedente.isEmpty()) {
            nudges.add(nudgeEstouroPlaybook(excedente));
        }
        salvaSeMudou(companyId, MemoryKind.PLAYBOOK, !vigentes.isEmpty(), renderPlaybook(janela, nudges));
    }

    /** As N mais recentes (maior #N), mantidas em ordem cronológica de leitura. */
    private static List<Decision> janela(List<Decision> vigentes, int cap) {
        return vigentes.size() <= cap ? vigentes
                : vigentes.subList(vigentes.size() - cap, vigentes.size());
    }

    /**
     * Append-only e idempotente por conteúdo: corpo igual ao corrente não vira versão nova. A
     * projeção NÃO NASCE vazia (sem vigente e sem versão anterior → nada), mas uma que já existe
     * re-renderiza até vazia — a auto-cura (D11) vale até para a ÚLTIMA direção supersedida.
     */
    private void salvaSeMudou(UUID companyId, MemoryKind kind, boolean temVigente, String body) {
        CompanyMemory corrente = memories
                .findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, kind)
                .orElse(null);
        if (!temVigente && corrente == null) {
            return;
        }
        if (corrente != null && corrente.getBody().equals(body)) {
            return;
        }
        int nextVersion = corrente == null ? 1 : corrente.getVersion() + 1;
        memories.save(new CompanyMemory(UUID.randomUUID(), companyId, kind, body, nextVersion));
    }

    private static String renderCarta(List<Decision> janela, List<String> nudges) {
        StringBuilder out = new StringBuilder();
        out.append("# Carta de interesses da empresa\n\n");
        out.append("> DERIVADA do Decision-log (ADR-0019): projeção determinística das Decisões de\n");
        out.append("> direção aprovadas pelo dono. Não editar à mão — a fonte é o log.\n\n");
        out.append("## Direções vigentes\n");
        for (Decision decisao : janela) {
            out.append(linha(decisao));
        }
        appendNudges(out, nudges);
        return out.toString();
    }

    private static String renderPlaybook(List<Decision> janela, List<String> nudges) {
        StringBuilder out = new StringBuilder();
        out.append("# Playbook da empresa\n\n");
        out.append("> DERIVADO do Decision-log (ADR-0019, Frente C): projeção determinística dos\n");
        out.append("> princípios selados pelo dono (grau=PRINCIPIO). Não editar à mão — a fonte é o log.\n\n");
        out.append("## Princípios vigentes\n");
        for (Decision principio : janela) {
            out.append(linha(principio));
        }
        appendNudges(out, nudges);
        return out.toString();
    }

    /** As nudge-lines do método (C5/C7/C8): determinísticas, zero-LLM — cutucam, nunca agem. */
    private static void appendNudges(StringBuilder out, List<String> nudges) {
        if (nudges.isEmpty()) {
            return;
        }
        out.append("\n## Nudges do método (determinísticas)\n");
        for (String nudge : nudges) {
            out.append(nudge).append("\n");
        }
    }

    private static String nudgeEstouroCarta(List<Decision> excedente) {
        int n = excedente.size();
        return "- Estouro do cap (C7/C8): " + n + (n == 1 ? " direção vigente" : " direções vigentes")
                + " além das " + CAP_CARTA + " mais recentes " + (n == 1 ? "ficou" : "ficaram")
                + " fora da carta (" + enderecos(excedente) + ") — destile num princípio"
                + " (a graduação comprime N→1) ou supersede; nada foi descartado: o log é a"
                + " história e o #N segue endereçável.";
    }

    private static String nudgeEstouroPlaybook(List<Decision> excedente) {
        int n = excedente.size();
        return "- Estouro do cap (C7): " + n + (n == 1 ? " princípio" : " princípios")
                + " além dos " + CAP_PLAYBOOK + " mais recentes " + (n == 1 ? "ficou" : "ficaram")
                + " fora do playbook (" + enderecos(excedente) + ") — supersede princípios"
                + " sobrepostos ou destile; nada foi descartado: o log é a história e o #N"
                + " segue endereçável.";
    }

    private static String nudgeCandidata(GraduationDetector.Candidata candidata) {
        String linhagem = candidata.seqs().stream().map(seq -> "#" + seq)
                .collect(Collectors.joining(", "));
        return "- Candidata a graduação (C5): " + candidata.seqs().size() + " decisões em \""
                + candidata.area() + "\" desde o último princípio da área (" + linhagem
                + ") — proponha a frase e gradue pelo gate (GRADUATE_PRINCIPLE).";
    }

    private static String enderecos(List<Decision> decisoes) {
        return decisoes.stream().map(decisao -> "#" + decisao.getSeq())
                .collect(Collectors.joining(", "));
    }

    private static String linha(Decision decisao) {
        // O #N na frente é o ENDEREÇO (C4): é o que o CEO cita ao supersedar (D11) e a
        // chave que o fetch JIT vai buscar — a projeção ensina o endereço de graça.
        return "- #" + decisao.getSeq()
                + " [" + DATA.format(decisao.getDecidedAt()) + "] "
                + "(" + decisao.getArea() + ") "
                + decisao.getOQueMudou()
                + " — racional: " + decisao.getRacional()
                + "\n";
    }
}
