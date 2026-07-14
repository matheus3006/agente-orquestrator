package com.orquestrador.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Arrays;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * A NAVEGAÇÃO da memória durável (F-C3, C10): a leitura por trás das duas tools read-only que o
 * bridge MCP serve — {@code memory_search} (METADADOS, livre) e {@code memory_fetch} (CORPO, capado
 * no {@code KnowAllMcpBridge}). Irmã da {@code KnowAllService} no papel: a leitura crua mora aqui,
 * o TETO mora no bridge (o script materializado é relay burro e o agente poderia adulterá-lo).
 *
 * <p><b>A FRONTEIRA (C10.b) é o invariante desta classe:</b> o trilho serve o <b>DURÁVEL SELADO</b>
 * — princípios (grau=PRINCIPIO, de QUALQUER tipo: o grau é o eixo do ALCANCE, ortogonal ao do
 * ASSUNTO, C3) + direções (tipo=DIRECAO). O <b>log CRU</b> {@code OPERACIONAL}/{@code TECNICA} com
 * racional segue <b>BOARD-ONLY</b> (o {@code DecisionController} é {@code assertBoardMember} —
 * "racional é material do dono") e é INALCANÇÁVEL por aqui: nem na busca, nem no fetch por {@code #N}.
 * Alargar pro log inteiro é v1.1 com grill próprio (a tabela DERRUBOU do grill da Frente C).
 *
 * <p>Zero-LLM: busca determinística por TERMOS normalizados sobre METADADOS (área + o que mudou),
 * nunca sobre o racional — o corpo só sai pelo fetch, um {@code #N} por vez, sob o teto do D6.
 */
@Service
public class MemoryQuery {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    /** O piso de letras de um termo buscável (abaixo disso é palavra de ligação — casa com tudo). */
    private static final int MIN_TERMO = 4;

    private final DecisionRepository decisions;

    public MemoryQuery(DecisionRepository decisions) {
        this.decisions = decisions;
    }

    /**
     * {@code memory_search}: os METADADOS das Decisões duráveis seladas que casam com a query —
     * livre (fora do teto), porque metadado não é corpo: o que volta aqui já é o que a projeção
     * sempre-on ensina de graça (o {@code #N}, a área, o que mudou). Mais recentes primeiro.
     */
    @Transactional(readOnly = true)
    public List<Metadados> search(UUID companyId, String query, int limit) {
        List<String> termos = termos(query);
        if (termos.isEmpty()) {
            return List.of();
        }
        Linhagem linhagem = linhagemDe(companyId);
        return duravelSelado(companyId).stream()
                .map(decisao -> new Casamento(decisao, termosQueCasam(decisao, termos)))
                .filter(casamento -> casamento.termos() > 0)
                // Quem casa MAIS termos vem antes; empate → o mais recente (maior #N).
                .sorted(Comparator.comparingInt(Casamento::termos).reversed()
                        .thenComparing(Comparator.comparingInt((Casamento c) -> c.decisao().getSeq()).reversed()))
                .limit(Math.max(1, limit))
                .map(casamento -> metadados(casamento.decisao(), linhagem))
                .toList();
    }

    /**
     * {@code memory_fetch}: o CORPO de UMA Decisão pelo endereço {@code #N} — o racional (a resposta
     * literal do <i>"por que decidimos #7?"</i>), a evidência, a linhagem (D11) e o cluster da área.
     * {@code Optional.empty()} quando o {@code #N} não existe NESTA company OU está fora da fronteira
     * (log cru board-only, C10.b) — o bridge traduz os dois em recusa NÃO-fatal, e nenhum dos dois
     * consome o teto: nada entrou no contexto do agente.
     */
    @Transactional(readOnly = true)
    public Optional<Corpo> fetch(UUID companyId, int seq) {
        return decisions.findByCompanyIdAndSeq(companyId, seq)
                .filter(MemoryQuery::selado)
                .map(decisao -> corpo(companyId, decisao));
    }

    private Corpo corpo(UUID companyId, Decision decisao) {
        Linhagem linhagem = linhagemDe(companyId);
        // O CLUSTER: as irmãs SELADAS da mesma área, em metadado (o corpo delas custa outro fetch —
        // a vizinhança orienta a navegação sem gastar o teto).
        List<Metadados> cluster = duravelSelado(companyId).stream()
                .filter(irma -> irma.getArea().equals(decisao.getArea()))
                .filter(irma -> !irma.getId().equals(decisao.getId()))
                .sorted(Comparator.comparingInt(Decision::getSeq).reversed())
                .map(irma -> metadados(irma, linhagem))
                .toList();
        Integer supersedidaPor = linhagem.supersedidaPor(decisao.getId());
        return new Corpo(decisao.getSeq(), DATA.format(decisao.getDecidedAt()), decisao.getArea(),
                decisao.getTipo(), decisao.getGrau(), decisao.getOQueMudou(), decisao.getRacional(),
                decisao.getRacionalTipo(), decisao.getEvidencia(), supersedidaPor == null,
                linhagem.seqDe(decisao.getSupersedesId()), supersedidaPor, cluster);
    }

    /** O tier que o trilho enxerga (C10.b) — a fronteira, num lugar só. */
    private List<Decision> duravelSelado(UUID companyId) {
        return decisions.findByCompanyIdOrderByDecidedAtDesc(companyId).stream()
                .filter(MemoryQuery::selado)
                .toList();
    }

    /** Princípio (qualquer tipo, C3) OU direção — nunca o log cru operacional/técnico. */
    private static boolean selado(Decision decisao) {
        return decisao.getGrau() == DecisionGrau.PRINCIPIO || decisao.getTipo() == DecisionTipo.DIRECAO;
    }

    /**
     * Quantos TERMOS da query aparecem nos METADADOS (área + o que mudou) — nunca no racional (o
     * corpo só sai pelo fetch). Casar por TERMO, e não pela frase inteira, é o que torna a busca
     * usável por quem de fato a chama: um agente pergunta <i>"qual foi a decisão original de
     * cobrança por assento?"</i>, não digita um prefixo exato (achado do /verify da F-C3 — com
     * casamento de frase o codex não achou nada e respondeu pelo que já tinha no sempre-on).
     */
    private static int termosQueCasam(Decision decisao, List<String> termos) {
        List<String> palavras = palavras(decisao.getArea() + " " + decisao.getOQueMudou());
        return (int) termos.stream()
                .filter(termo -> palavras.stream().anyMatch(palavra -> casam(palavra, termo)))
                .count();
    }

    /**
     * Casamento por PALAVRA, com prefixo nos dois sentidos (pega plural/flexão: "assento"↔"assentos").
     * NUNCA por substring solta: "por" casaria dentro de "su<b>por</b>te" e a busca devolveria lixo.
     */
    private static boolean casam(String palavra, String termo) {
        return palavra.startsWith(termo) || termo.startsWith(palavra);
    }

    /**
     * Os termos BUSCÁVEIS de uma query (e as palavras indexáveis de um metadado): normalizados, sem
     * pontuação, com pelo menos {@value #MIN_TERMO} letras — abaixo disso são palavras de ligação
     * ("de", "por", "foi") que casam com quase tudo e arrastariam o log inteiro pro contexto.
     */
    private static List<String> termos(String query) {
        return palavras(query);
    }

    private static List<String> palavras(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normaliza(texto).split("[^a-z0-9]+"))
                .filter(palavra -> palavra.length() >= MIN_TERMO)
                .distinct()
                .toList();
    }

    /** Uma decisão + quantos termos da query ela casou (o critério de ordenação da busca). */
    private record Casamento(Decision decisao, int termos) {
    }

    private Metadados metadados(Decision decisao, Linhagem linhagem) {
        Integer supersedidaPor = linhagem.supersedidaPor(decisao.getId());
        return new Metadados(decisao.getSeq(), DATA.format(decisao.getDecidedAt()), decisao.getArea(),
                decisao.getTipo(), decisao.getGrau(), decisao.getOQueMudou(),
                supersedidaPor == null, supersedidaPor);
    }

    /** O mapa da linhagem D11 desta company: quem SUPERSEDE quem, resolvido em endereços {@code #N}. */
    private Linhagem linhagemDe(UUID companyId) {
        return new Linhagem(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId));
    }

    /** Normalização da casa (espelha {@code DecisionLog.tipoFor}): minúscula + sem acento. */
    private static String normaliza(String texto) {
        return Normalizer.normalize((texto == null ? "" : texto).toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /**
     * O que a busca devolve: METADADO puro (o endereço {@code #N}, quando, onde, o que mudou) +
     * o sinal de VIGÊNCIA — uma Decisão supersedida (D11) continua endereçável e fetchável (o log é
     * a história), mas o agente precisa saber que ela foi substituída, e por qual {@code #N}.
     */
    public record Metadados(int seq, String data, String area, DecisionTipo tipo, DecisionGrau grau,
                            String oQueMudou, boolean vigente, Integer supersedidaPor) {
    }

    /**
     * O que o fetch devolve — e o que o teto do D6 conta: UM racional por slot. A {@code linhagem}
     * ({@code supersede}/{@code supersedidaPor}) e o {@code cluster} (as irmãs da área, em metadado)
     * vêm de carona no MESMO slot: navegar a vizinhança não custa outro corpo.
     */
    public record Corpo(int seq, String data, String area, DecisionTipo tipo, DecisionGrau grau,
                        String oQueMudou, String racional, RacionalTipo racionalTipo, String evidencia,
                        boolean vigente, Integer supersede, Integer supersedidaPor,
                        List<Metadados> cluster) {
    }

    /** O índice de supersede da company (D11), em memória: volume de GATES, não de runs. */
    private static final class Linhagem {

        private final List<Decision> log;

        private Linhagem(List<Decision> log) {
            this.log = log;
        }

        /** O {@code #N} da Decisão que supersede esta, ou {@code null} se ela é a vigente. */
        private Integer supersedidaPor(UUID decisaoId) {
            return log.stream()
                    .filter(candidata -> decisaoId.equals(candidata.getSupersedesId()))
                    .map(Decision::getSeq)
                    .findFirst()
                    .orElse(null);
        }

        /** O {@code #N} de um id de máquina (a outra perna da linhagem: o que ESTA substituiu). */
        private Integer seqDe(UUID decisaoId) {
            if (decisaoId == null) {
                return null;
            }
            return log.stream()
                    .filter(candidata -> decisaoId.equals(candidata.getId()))
                    .map(Decision::getSeq)
                    .findFirst()
                    .orElse(null);
        }
    }
}
