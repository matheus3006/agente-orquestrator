package com.orquestrador.governance.approval;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.governance.approval.ApprovalService.DecideOutcome;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * O LOTE do gate (ADR-0035, D4/D9/D14): decide N approvals reaproveitando o {@link ApprovalService#decide}
 * item a item, SEQUENCIAL. Deliberadamente NÃO transacional: cada decide() abre a própria tx —
 * um item cujo dispatch quebra desfaz SÓ a si mesmo (deny-by-default por item) e o lote segue.
 * Item que não commita (já decidida, inexistente, grelhada pelo probe, alvo malformado) vira
 * linha em {@code puladas} — a contagem sai do loop de graça, sem maquinário de resumo parcial
 * (sistema single-user por design, D9).
 *
 * <p>Sem override de motor no lote (D12): o decide() roda sem {@code EngineOverride} — sempre o
 * motor protocolado na proposta. Recusas commitadas voltam ao proponente num kickback AGREGADO
 * por conversa (D7), depois do loop — nunca por item.
 */
@Service
public class ApprovalBatchService {

    private final ApprovalService approvals;
    private final RecusaKickback recusaKickback;

    public ApprovalBatchService(ApprovalService approvals, RecusaKickback recusaKickback) {
        this.approvals = approvals;
        this.recusaKickback = recusaKickback;
    }

    public BatchOutcome decideBatch(UUID companyId, ApprovalDecision decision, String racional,
                                    String motivo, List<Item> items,
                                    ActorType actorType, UUID actorId) {
        List<Approval> decididas = new ArrayList<>();
        List<Pulada> puladas = new ArrayList<>();
        List<RecusaKickback.Recusa> recusadas = new ArrayList<>();
        for (Item item : items) {
            try {
                DecideOutcome outcome = approvals.decide(companyId, item.approvalId(), decision,
                        racional, decisionReason(motivo, item.observacao()), actorType, actorId);
                switch (outcome) {
                    case DecideOutcome.Grilled grilled ->
                            puladas.add(new Pulada(item.approvalId(), "grill: " + grilled.probe()));
                    case DecideOutcome.Committed committed -> {
                        decididas.add(committed.approval());
                        if (decision == ApprovalDecision.REJECT) {
                            recusadas.add(new RecusaKickback.Recusa(committed.approval(), motivo,
                                    item.observacao()));
                        }
                    }
                }
            } catch (ConflictException | NotFoundException naoCommitou) {
                puladas.add(new Pulada(item.approvalId(), naoCommitou.getMessage()));
            }
        }
        recusaKickback.notificar(companyId, recusadas); // agregado por conversa (D7); vazio = no-op
        return new BatchOutcome(decididas, puladas);
    }

    /** D8 na linha da Approval: observação presente detalha o motivo comum; ausente, cai nele. */
    private static String decisionReason(String motivo, String observacao) {
        boolean temMotivo = motivo != null && !motivo.isBlank();
        boolean temObservacao = observacao != null && !observacao.isBlank();
        if (temMotivo && temObservacao) {
            return motivo + " — observação: " + observacao;
        }
        return temMotivo ? motivo : temObservacao ? observacao : null;
    }

    /** Um item do lote: a approval mirada + a observação opcional do dono sobre ELA (D8). */
    public record Item(UUID approvalId, String observacao) {
    }

    /** Item que não commitou e POR QUÊ — a resposta carrega, nenhuma UI dedicada consome (D9). */
    public record Pulada(UUID approvalId, String motivo) {
    }

    public record BatchOutcome(List<Approval> decididas, List<Pulada> puladas) {
    }
}
