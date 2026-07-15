package com.orquestrador.memory;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Materializa a DIETA sempre-on da company no workspace como {@code <workDir>/<memoryFile>} (ADR-0018
 * — espelha o {@code SkillMaterializer}). O arquivo vem da {@code WorkspaceConvention} do adapter
 * (ADR-0032): {@code .claude/CLAUDE.md} no Claude Code, {@code AGENTS.md} na raiz para o codex —
 * corpo VERBATIM das projeções nos dois. IDEMPOTENTE: reescreve a cada Run; dieta vazia → REMOVE o
 * arquivo (nunca serve memória velha de outra versão do workspace). Só toca o arquivo da memória —
 * as skills do agente vivem ao lado (no {@code skillsSubdir}) e são de outro materializer.
 *
 * <p><b>F-C3 (C9) — o SPLIT do always-on:</b> a dieta é escolhida pela {@link MemoryAudience}, e as
 * DUAS pontas agora materializam. Até aqui só o turno do CEO chamava este escritor ("em run de Issue
 * o executor não o chama"): o executor rodava sem memória nenhuma, e o D12 ("os agentes APLICAM") era
 * letra morta. Agora o executor recebe os PRINCÍPIOS (o índice enxuto, com os {@code #N} que abrem o
 * {@code memory_fetch}) — e nunca a carta.
 *
 * <p><b>R19-A (fecho do v1, ADR-0011 emendada) — a dieta DIRECIONADA da Issue:</b> quando o run tem
 * {@code issueId}, o materializer também PUXA as Decisões ancoradas NAQUELA issue (o motivo de um
 * REJECT no review + as do caso B) e as materializa como uma seção própria — o executor RECEBE o
 * feedback no re-run sem depender de escolher buscar (PUSH, não pull: feedback de reject não é
 * opcional). É um caminho NOVO e ESTREITO (só a issue do run), que CONVIVE com o trilho JIT selado
 * sem alargá-lo: a Decisão do reject (grau=DECISAO, tipo OPERACIONAL/TECNICA) segue FORA do
 * {@code memory_fetch} (fronteira C10.b do {@link MemoryQuery}, intocada). Turno do CEO
 * ({@code issueId == null}) não ganha seção nenhuma — a ponta do CEO fica inalterada.
 */
@Component
public class CompanyMemoryMaterializer {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final CompanyMemoryRepository memories;
    private final DecisionRepository decisions;

    public CompanyMemoryMaterializer(CompanyMemoryRepository memories, DecisionRepository decisions) {
        this.memories = memories;
        this.decisions = decisions;
    }

    /** Run SEM Issue (turno de conversa do CEO, ou run sem issue): só a dieta durável desta ponta. */
    public void materialize(Path workDir, String memoryFile, UUID companyId, MemoryAudience audience) {
        materialize(workDir, memoryFile, companyId, audience, null);
    }

    public void materialize(Path workDir, String memoryFile, UUID companyId, MemoryAudience audience,
                            UUID issueId) {
        Path alvo = workDir.resolve(memoryFile);
        String dieta = dieta(companyId, audience, issueId);
        try {
            if (dieta.isEmpty()) {
                Files.deleteIfExists(alvo);
                return;
            }
            Files.createDirectories(alvo.getParent());
            Files.writeString(alvo, dieta, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao materializar a memória em " + alvo, exception);
        }
    }

    /**
     * As superfícies CORRENTES da dieta desta ponta, concatenadas na ordem de leitura: as projeções
     * duráveis (cada uma VERBATIM — o corpo é a projeção determinística do consolidator; nada é
     * reescrito aqui) e, por último, a seção DIRECIONADA do feedback da Issue do run (R19-A).
     * Superfície ausente é simplesmente pulada; nenhuma presente ⟹ vazio ⟹ o arquivo some.
     */
    private String dieta(UUID companyId, MemoryAudience audience, UUID issueId) {
        List<String> superficies = new ArrayList<>();
        for (MemoryKind kind : audience.dieta()) {
            memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, kind)
                    .map(CompanyMemory::getBody)
                    .filter(body -> !body.isBlank())
                    .ifPresent(superficies::add);
        }
        if (issueId != null) {
            feedbackDaIssue(companyId, issueId).ifPresent(superficies::add);
        }
        return String.join("\n\n", superficies);
    }

    /**
     * A seção do feedback da Issue (R19-A): as Decisões que o dono ancorou NESTA issue — o motivo do
     * REJECT no review e as do caso B — renderizadas com o endereço {@code #N} e o RACIONAL (o
     * feedback literal). Vazio (sem Decisão ancorada) ⟹ nenhuma seção ⟹ a dieta do executor fica
     * exatamente a durável. O racional entra AQUI de propósito: este caminho ENTREGA o feedback (o
     * agente selado não tem canal inbound), diferente do {@code memory_fetch} onde o log cru é
     * board-only. Ordem de {@code #N} (cronológica por construção, C4).
     */
    private Optional<String> feedbackDaIssue(UUID companyId, UUID issueId) {
        List<Decision> ancoradas = decisions.findByCompanyIdAndIssueIdOrderBySeqAsc(companyId, issueId);
        if (ancoradas.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        out.append("# Feedback do dono sobre esta Issue (revise ANTES de reentregar)\n\n");
        out.append("> As Decisões que o dono ancorou NESTA Issue — inclui o motivo de um REJECT no\n");
        out.append("> review. Endereçadas por #N no Log de Decisões. Este re-run precisa ENDEREÇAR\n");
        out.append("> este feedback antes de submeter de novo.\n\n");
        out.append("## Decisões desta Issue\n");
        for (Decision decisao : ancoradas) {
            out.append(linha(decisao));
        }
        return java.util.Optional.of(out.toString());
    }

    /** O {@code #N} na frente é o ENDEREÇO (C4); o racional é o feedback que o executor precisa aplicar. */
    private static String linha(Decision decisao) {
        return "- #" + decisao.getSeq()
                + " [" + DATA.format(decisao.getDecidedAt()) + "] "
                + "(" + decisao.getArea() + ") "
                + decisao.getOQueMudou()
                + " — racional: " + decisao.getRacional()
                + "\n";
    }
}
