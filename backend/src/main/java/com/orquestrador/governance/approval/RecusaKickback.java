package com.orquestrador.governance.approval;

import com.orquestrador.conversation.persona.PersonaContinuationPoster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A recusa VOLTA ao proponente (ADR-0035, D5–D8): o dono recusar uma proposta não pode ser
 * terminal-silencioso — o CEO precisa do PORQUÊ para aprofundar o grill ou consertar o próprio
 * racional. O retorno é um turno na conversa de ORIGEM (transporte reaproveitado do
 * {@link PersonaContinuationPoster}), com o motivo do dono VERBATIM (precedente do caso B:
 * zero paráfrase de LLM), AGREGADO por conversa (D7): N recusas do mesmo lote na mesma thread
 * viram UM turno síntese — a observação por item aparece distinta dentro dele (D8).
 *
 * <p>Fail-soft em toda ponta: proposta sem conversa de origem não tem para onde voltar;
 * proponente que já não existe descarta com log; falha ao postar NUNCA desfaz o gate — o
 * kickback informa, não governa.
 */
@Component
public class RecusaKickback {

    private static final Logger log = LoggerFactory.getLogger(RecusaKickback.class);

    private final PersonaContinuationPoster poster;

    public RecusaKickback(PersonaContinuationPoster poster) {
        this.poster = poster;
    }

    /** A recusa individual (D15): lote de UM — sem {@code reason} do card, o racional é o porquê. */
    public void notificarRecusaIndividual(Approval recusada) {
        String motivo = recusada.getDecisionReason() != null && !recusada.getDecisionReason().isBlank()
                ? recusada.getDecisionReason()
                : recusada.getRacional();
        notificar(recusada.getCompanyId(), List.of(new Recusa(recusada, motivo, null)));
    }

    /** O seam do lote (D7): agrupa por conversa de origem e posta UM turno síntese por grupo. */
    public void notificar(UUID companyId, List<Recusa> recusas) {
        Map<UUID, List<Recusa>> porConversa = new LinkedHashMap<>();
        for (Recusa recusa : recusas) {
            UUID conversationId = recusa.approval().getConversationId();
            if (conversationId == null) {
                continue; // proposta sem thread de origem: não há para onde voltar (fail-soft)
            }
            porConversa.computeIfAbsent(conversationId, id -> new ArrayList<>()).add(recusa);
        }
        porConversa.forEach((conversationId, doGrupo) -> {
            try {
                poster.postRecusaDoGate(companyId, conversationId,
                        doGrupo.get(0).approval().getRequestedByAgentId(), mensagem(doGrupo));
            } catch (RuntimeException falha) {
                log.warn("recusa-gate: kickback na conversa {} falhou — a recusa segue commitada"
                        + " (o kickback informa, não governa)", conversationId, falha);
            }
        });
    }

    /**
     * O turno síntese, determinístico e verbatim: motivo comum UMA vez, cada proposta na sua
     * linha, observação por item distinta quando presente (D8).
     */
    private static String mensagem(List<Recusa> doGrupo) {
        Recusa primeira = doGrupo.get(0);
        String motivoComum = primeira.motivo() == null || primeira.motivo().isBlank()
                ? "(sem motivo registrado)" : primeira.motivo();
        StringBuilder corpo = new StringBuilder("Recusa no gate — o dono recusou ");
        if (doGrupo.size() == 1) {
            corpo.append("a proposta \"").append(resumoDe(primeira.approval())).append("\".");
        } else {
            corpo.append(doGrupo.size()).append(" propostas desta conversa.");
        }
        corpo.append(" Motivo: ").append(motivoComum);
        if (doGrupo.size() == 1) {
            appendObservacao(corpo, primeira.observacao());
        } else {
            for (Recusa recusa : doGrupo) {
                corpo.append("\n- \"").append(resumoDe(recusa.approval())).append("\"");
                appendObservacao(corpo, recusa.observacao());
            }
        }
        corpo.append("\n\nLeve o motivo em conta antes de re-propor: ajuste o plano ou, se a"
                + " lacuna for do dono, aprofunde o grill com ele.");
        return corpo.toString();
    }

    private static void appendObservacao(StringBuilder corpo, String observacao) {
        if (observacao != null && !observacao.isBlank()) {
            corpo.append(" — observação: ").append(observacao);
        }
    }

    private static String resumoDe(Approval approval) {
        return approval.getResumo() != null && !approval.getResumo().isBlank()
                ? approval.getResumo()
                : approval.getActionType().name() + " " + approval.getTargetDetail();
    }

    /** Uma recusa a notificar: motivo comum do lote (ou derivado no individual) + observação por item. */
    public record Recusa(Approval approval, String motivo, String observacao) {
    }
}
