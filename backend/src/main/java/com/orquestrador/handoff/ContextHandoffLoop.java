package com.orquestrador.handoff;

import com.orquestrador.execution.AgentSessionStore;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.runtime.ExecutionTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O LOOP de handoff de contexto do CEO (ADR-0030) — a "fatia futura" que a ADR-0015 mediu mas não
 * construiu: detectar o limiar → GARANTIR o bilhete → retomar num turno FRESCO. Roda PÓS-settle de um
 * turno do CEO que teve sucesso (D1: gatilho pós-settle, não mid-run).
 *
 * <p>Módulo profundo atrás de um seam pequeno — {@link #afterCeoTurn}. Orquestra três decisões, cada
 * uma seu próprio módulo: {@link HandoffThresholdDetector} (cruzou?), a garantia do bilhete (self-
 * distill feliz OU rede resume-cirúrgico, M9) e {@link ContinuationTurnPoster} (continuação + trava).
 * A plumbing do run (adapter/target/workDir/volume) chega pronta no {@link FinishedCeoTurn} — o
 * executor a monta; o loop não a reconstrói.
 */
@Component
public class ContextHandoffLoop {

    private static final Logger log = LoggerFactory.getLogger(ContextHandoffLoop.class);

    private final HandoffThresholdDetector detector;
    private final ContinuationTurnPoster poster;
    private final AgentSessionStore sessions;

    public ContextHandoffLoop(HandoffThresholdDetector detector, ContinuationTurnPoster poster,
                              AgentSessionStore sessions) {
        this.detector = detector;
        this.poster = poster;
        this.sessions = sessions;
    }

    /**
     * Pós-settle (M8): se o run cruzou o limiar da janela E o bilhete existe, enfileira UMA
     * continuação fresca. Nunca acordar antes do bilhete existir (D8). Best-effort: uma falha aqui
     * NUNCA reabre o turno já liquidado nem aborta o run (D9).
     */
    public void afterCeoTurn(FinishedCeoTurn turn) {
        try {
            if (!detector.crossed(turn.adapterType(), turn.model(), turn.effort(), turn.emissions())) {
                return;
            }
            if (!noteExists(turn)) {
                // D8 camada 3 / D4′ (M9): o self-distill falhou — reabre a MESMA sessão (--resume) só
                // para destilar, e RE-checa. Sem bilhete mesmo após a rede ⟹ sem continuação (fail-open).
                surgicalDistill(turn);
                if (!noteExists(turn)) {
                    log.warn("handoff: bilhete {} ausente mesmo após a rede resume-cirúrgico — sem continuação",
                            HandoffNote.relativePath(turn.conversationId()));
                    return;
                }
            }
            poster.postContinuation(turn.companyId(), turn.agentId(), turn.conversationId());
        } catch (RuntimeException exception) {
            log.warn("handoff: loop pós-settle falhou (turno já liquidado — ignorado): {}",
                    exception.getMessage());
        }
    }

    private boolean noteExists(FinishedCeoTurn turn) {
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(turn.workDir(), List.of(), Map.of(),
                turn.adapter().engineContainer().image(), turn.volumeName(), List.of());
        return turn.target()
                .readWorkspaceFile(spec, HandoffNote.relativePath(turn.conversationId()), HandoffNote.MAX_BYTES)
                .found();
    }

    /**
     * A rede (D8 camada 3 / D4′): reabre a MESMA sessão do turno (handle nativo, ADR-0002) num run
     * TERMINAL com prompt mínimo só para GRAVAR o bilhete, e DESCARTA a sessão (não a persiste). É o
     * ÚNICO ponto que liga o {@code --resume} — escopado a destilar-e-encerrar; a janela vai a ~60% e
     * MORRE (zero crescimento contínuo ⟹ não fere a regra de ouro, D4′).
     */
    private void surgicalDistill(FinishedCeoTurn turn) {
        String handle = sessions.currentHandle(turn.agentId(), turn.conversationId()).orElse(null);
        if (handle == null) {
            log.warn("handoff: sem handle de sessão para o resume-cirúrgico (conv {}) — bilhete ausente",
                    turn.conversationId());
            return;
        }
        String notePath = HandoffNote.relativePath(turn.conversationId());
        String prompt = "Você cruzou o limite de contexto e o bilhete de handoff NÃO foi gravado. Use a"
                + " skill `distillation` e GRAVE AGORA o bilhete em `" + notePath + "` — apenas isso;"
                + " depois encerre. Não continue o trabalho.";
        AgentAdapter.Context resume = new AgentAdapter.Context(
                turn.companyId(), turn.agentId(), turn.runId(), null, turn.workDir(),
                prompt, Map.of("ORQ_PROMPT", prompt), turn.target(), turn.volumeName(),
                handle, turn.model(), turn.effort());
        try {
            turn.adapter().execute(resume, (type, detail) -> {
                // rastro descartado: a sessão do resume é terminal e NÃO é persistida (D4′).
            });
        } catch (RuntimeException exception) {
            log.warn("handoff: resume-cirúrgico falhou (conv {}): {}", turn.conversationId(),
                    exception.getMessage());
        }
    }

    /** Tudo sobre o turno do CEO recém-liquidado — o insumo do loop (montado pelo executor). */
    public record FinishedCeoTurn(
            UUID companyId, UUID agentId, UUID conversationId, UUID runId,
            String adapterType, String model, String effort,
            List<AgentAdapter.CostEmission> emissions,
            AgentAdapter adapter, ExecutionTarget target, Path workDir, String volumeName) {
    }
}
