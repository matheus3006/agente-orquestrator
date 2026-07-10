package com.orquestrador.conversation;

import com.orquestrador.execution.WakeupService;
import com.orquestrador.governance.approval.ApprovalService;
import com.orquestrador.governance.approval.ProposalScanner;
import com.orquestrador.handoff.HandoffNote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * O seam do MOTOR para turnos de conversa (ADR-0018/A.8): o {@code AdapterRunExecutor} pergunta
 * "este Run atende um turno?" ({@link #claimFor}) e, se sim, recebe o turno + o PROMPT montado
 * por-Run (estado vivo + mensagem do dono — ADR-0004 emendado: o {@code command} deixa de ser o
 * {@code runtimeCommand} estático). No fim, {@link #settleAnswered}/{@link #settleFailed} liquidam
 * o turno e DRENAM a fila: se restam turnos PENDING do agente, um novo wakeup é enfileirado —
 * invariante 1 turno = 1 Run, mesmo sob coalescing (dois posts no mesmo run QUEUED).
 */
@Service
public class ConversationTurnCoordinator {

    private final ConversationTurnRepository turns;
    private final ConversationRepository conversations;
    private final LiveStateProjector liveState;
    private final WakeupService wakeups;
    private final ProposalScanner proposalScanner;
    private final ApprovalService approvals;

    public ConversationTurnCoordinator(ConversationTurnRepository turns, ConversationRepository conversations,
                                       LiveStateProjector liveState, WakeupService wakeups,
                                       ProposalScanner proposalScanner, ApprovalService approvals) {
        this.turns = turns;
        this.conversations = conversations;
        this.liveState = liveState;
        this.wakeups = wakeups;
        this.proposalScanner = proposalScanner;
        this.approvals = approvals;
    }

    /**
     * Claim guardado do turno mais antigo PENDING do agente (idempotente para o MESMO runId — o
     * reaper requeia o run com a mesma identidade). Sem turno → empty (o Run segue o caminho
     * clássico de Issue/runtimeCommand). Com turno → o prompt por-Run já composto.
     */
    @Transactional
    public Optional<TurnAssignment> claimFor(UUID companyId, UUID agentId, UUID runId) {
        if (turns.claimOldestPending(agentId, runId) == 0) {
            return Optional.empty();
        }
        ConversationTurn turno = turns.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, TurnStatus.PENDING)
                .orElse(null);
        if (turno == null) {
            return Optional.empty();
        }
        String subject = conversations.findById(turno.getConversationId())
                .map(Conversation::getSubject)
                .orElse("(sem assunto)");
        String prompt = composePrompt(subject, liveState.project(companyId), turno.getOwnerMessage(),
                HandoffNote.relativePath(turno.getConversationId()));
        return Optional.of(new TurnAssignment(turno.getId(), turno.getConversationId(), prompt));
    }

    @Transactional
    public void settleAnswered(UUID turnId, String reply) {
        ConversationTurn turno = turns.findById(turnId).orElseThrow();
        turno.answer(reply);
        turns.save(turno);
        // O canal de PROPOSTA é a SAÍDA do CEO (ADR-0018): os blocos ```proposta da resposta
        // viram Approval PENDING ancorada nesta conversa/run. Parse determinístico; malformado
        // é descartado (segue legível na thread para o dono cobrar).
        for (ProposalScanner.Proposal proposal : proposalScanner.scan(reply)) {
            approvals.propose(turno.getCompanyId(), turno.getAgentId(), turno.getConversationId(),
                    turno.getRunId(), proposal);
        }
        drainQueue(turno);
    }

    @Transactional
    public void settleFailed(UUID turnId) {
        ConversationTurn turno = turns.findById(turnId).orElseThrow();
        turno.fail();
        turns.save(turno);
        drainQueue(turno); // um turno que falhou NÃO pode afogar os seguintes
    }

    /** Restam turnos PENDING do agente? Acorda de novo — o coalescing pode ter juntado N posts num Run só. */
    private void drainQueue(ConversationTurn settled) {
        if (turns.existsByAgentIdAndStatus(settled.getAgentId(), TurnStatus.PENDING)) {
            wakeups.enqueue(settled.getCompanyId(), settled.getAgentId(), null,
                    "conversation-drain:" + settled.getId());
        }
    }

    private static String composePrompt(String subject, String estadoVivo, String ownerMessage,
                                        String handoffNotePath) {
        return "# Conversa com o dono — chat \"" + subject + "\"\n\n"
                + estadoVivo
                + "\n## Mensagem do dono\n" + ownerMessage + "\n\n"
                + "## Instruções do turno\n"
                // G1 (grill 2026-07-09): "delegador puro" (ADR-0018) sempre foi sobre ENTREGÁVEIS;
                // generalizado à conversa, o modelo delegava até uma frase (run 043de730).
                + "- Você é o CEO. MANTENHA UMA CONVERSA com o dono: responda com a SUA voz, em texto"
                + " direto e curto; sua resposta vira a thread do chat.\n"
                + "- Pergunta trivial, de identidade ou de status: responda VOCÊ MESMO, imediatamente"
                + " — zero delegação, zero sub-agentes (nem os internos do seu CLI, ex.: collab/Task).\n"
                + "- Delegue (às áreas ou a sub-agentes) SOMENTE com trabalho real na mesa:"
                + " (A) criação de novo business, depois de grelhar meticulosamente com o dono;"
                + " (B) correção de task que o dono apontou — encaminhe as PALAVRAS DELE, sem"
                + " parafrasear; (C) contratação pedida explicitamente.\n"
                + "- \"Delegador puro\" (ADR-0018) vale para ENTREGÁVEIS: o conteúdo de um entregável"
                + " nunca é seu — mas a conversa é.\n"
                // M5 (ADR-0030) — instrução ESTÁTICA de handoff: não depende do custo (que só existe
                // pós-run), então é dada em TODO turno. Se um turno-continuação for a leitura de "##
                // Mensagem do dono" (a sentinela), estas linhas reforçam retomar pelo mesmo bilhete.
                // G2 (grill 2026-07-09): o imperativo de gravar mora DENTRO da condição — turno
                // trivial NÃO grava bilhete (se o % cruzar sem bilhete, a rede pós-settle o garante).
                // O formato vai INLINE porque nem todo motor materializa a skill `distillation`.
                + "- Regra de ouro do handoff (ADR-0015): SÓ SE a conversa aprofundou E a sua janela"
                + " de contexto está enchendo (~50-60%), destile e grave o bilhete de handoff em `"
                + handoffNotePath + "`; senão NÃO grave nada — nem crie o arquivo.\n"
                + "- Formato do bilhete (markdown curto, referências CONCRETAS — ids e arquivo:linha):"
                + " seções `Onde estou` · `O que já fiz` · `Próximo passo` · `Decisões travadas (com"
                + " racional)` · `Ponteiros (ids + arquivos)`; reescrever substitui o anterior. A"
                + " skill `distillation`, se disponível, detalha o contrato — apoio OPCIONAL, não"
                + " pré-requisito.\n"
                + "- Uma sessão NOVA retoma pelo bilhete em vez de recarregar a conversa inteira.\n";
    }

    /** O turno claimado por este Run + o prompt por-Run que vai ao CLI. */
    public record TurnAssignment(UUID turnId, UUID conversationId, String prompt) {
    }
}
