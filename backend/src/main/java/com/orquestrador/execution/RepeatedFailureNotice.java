package com.orquestrador.execution;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import com.orquestrador.work.issue.IssueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O aviso ao dono quando o circuit-breaker abre (ADR-0018 teto (c), R18-B): escalacao DIRETA ao
 * dono (a cascata executor->arquiteto->lider->CEO->dono da ADR foi emendada para FORA), pelo
 * padrao M6 ja provado — um {@link ConversationTurn} ja ANSWERED na conversa ancorada a issue,
 * ZERO Run novo (espelho de {@code ProposalDescarteNotice} / {@code postaProtocolo} da absorcao).
 * Voz: o Questionador (balcao); sem ele, o proprio agente travado.
 *
 * <p>Idempotencia (padrao M6): a chave {@code [gatilho <runId-do-FAILED>]}. Enquanto o topo do par
 * nao muda, o aviso e UM so — varios wakeups automaticos barrados nao empilham avisos. Se o dono
 * insiste (wakeup manual) e o run falha de novo, o novo run-gatilho gera um novo aviso.
 */
@Component
public class RepeatedFailureNotice {

    private static final Logger log = LoggerFactory.getLogger(RepeatedFailureNotice.class);

    private static final String PREFIXO = "Execução travada — o circuit-breaker abriu";

    private final ConversationTurnRepository turns;
    private final AgentRepository agents;
    private final IssueService issues;

    public RepeatedFailureNotice(ConversationTurnRepository turns, AgentRepository agents,
                                 IssueService issues) {
        this.turns = turns;
        this.agents = agents;
        this.issues = issues;
    }

    /** Os filtros de fluxo de personas reconhecem o aviso por aqui (turno interno, nao "dono de volta"). */
    public static boolean isAviso(String ownerMessage) {
        return ownerMessage != null && ownerMessage.startsWith(PREFIXO);
    }

    /**
     * Posta o aviso do par travado uma UNICA vez por run-gatilho. A protecao (o breaker barrar) e
     * independente deste aviso: sem conversa ancorada OU sem porta-voz, so o log resta (fail-soft,
     * como o M6) — o re-enfileiramento segue barrado de qualquer forma.
     */
    @Transactional
    public void avisaSeInedito(UUID companyId, UUID agentId, UUID issueId,
                               RepeatedFailureBreaker.Tripped tripped) {
        UUID conversationId = issues.conversationIdOf(companyId, issueId);
        if (conversationId == null) {
            log.warn("circuit-breaker aberto no par agente={} issue={} sem conversa ancorada — so o log resta",
                    agentId, issueId);
            return;
        }
        if (jaAvisado(conversationId, companyId, tripped.gatilhoRunId())) {
            return;
        }
        Agent portaVoz = agents.findFirstByCompanyIdAndRoleAndPersonaOrderByCreatedAtAsc(
                        companyId, CeoPersona.CEO_ROLE, CeoPersona.QUESTIONADOR.dbValue())
                .or(() -> agents.findById(agentId))
                .orElse(null);
        if (portaVoz == null) {
            log.warn("circuit-breaker: conversa {} sem porta-voz — so o log resta", conversationId);
            return;
        }
        ConversationTurn aviso = new ConversationTurn(UUID.randomUUID(), conversationId, companyId,
                portaVoz.getId(), PREFIXO + " [gatilho " + tripped.gatilhoRunId() + "]");
        aviso.answer(texto(agentId, issueId, tripped));
        turns.save(aviso);
        // Sem wakeup: ja ANSWERED — visivel na thread, nenhum Run novo.
    }

    private static String texto(UUID agentId, UUID issueId, RepeatedFailureBreaker.Tripped tripped) {
        String erro = tripped.ultimoErro() == null || tripped.ultimoErro().isBlank()
                ? "sem detalhe de erro" : tripped.ultimoErro();
        return "O agente " + agentId + " falhou " + tripped.falhasConsecutivas()
                + " vezes seguidas na issue " + issueId + " — parei de reenfileirar SO este par"
                + " (o agente segue em outras issues; a issue segue para outros agentes). Ultimo erro: "
                + erro + ". Para retomar, acorde o agente manualmente nesta issue (wakeup direto) —"
                + " e o gesto que destrava.";
    }

    private boolean jaAvisado(UUID conversationId, UUID companyId, UUID gatilhoRunId) {
        String marcador = "[gatilho " + gatilhoRunId + "]";
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conversationId, companyId)
                .stream()
                .anyMatch(turno -> isAviso(turno.getOwnerMessage())
                        && turno.getOwnerMessage().endsWith(marcador));
    }
}
