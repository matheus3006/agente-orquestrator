package com.orquestrador.conversation;

import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * O ANTI-SILÊNCIO do canal de proposta (ADR-0036): quando um settle descarta bloco(s)
 * ```proposta``` — shape fora do contrato ou emissor não autorizado (Q/P) — o dono ganha um
 * aviso VISÍVEL na thread, já ANSWERED, sem nenhum Run novo (o espelho exato do corte "Bastão
 * parado" da ADR-0031 §8). A premissa da ADR-0018 ("o texto segue visível na thread para o dono
 * cobrar") caiu na Prova R3-A: a olho nu não se distingue proposta válida de inventada — o dono
 * ficou esperando um gate que nunca encheu.
 *
 * <p>Idempotência (padrão do caso B): o ownerMessage carrega {@code [gatilho <turnoId>]} — o
 * settle reexecutado pelo reaper não duplica o aviso. Voz do aviso: o balcão (Questionador);
 * sem ele, o próprio agente do turno-gatilho (o CEO legado é o seu próprio balcão).
 */
@Component
public class ProposalDescarteNotice {

    private static final Logger log = LoggerFactory.getLogger(ProposalDescarteNotice.class);

    private static final String PREFIXO = "Proposta descartada — nada chegou ao gate";

    private final ConversationTurnRepository turns;
    private final AgentRepository agents;

    public ProposalDescarteNotice(ConversationTurnRepository turns, AgentRepository agents) {
        this.turns = turns;
        this.agents = agents;
    }

    /** A régua M9 e os filtros de fluxo reconhecem o aviso por aqui (não zera, não roteia). */
    public static boolean isAviso(String ownerMessage) {
        return ownerMessage != null && ownerMessage.startsWith(PREFIXO);
    }

    /**
     * Posta o aviso do settle-gatilho — {@code emissorBarrado} escolhe o texto: o cinto de
     * emissor educa o fluxo (Q→P→E); o shape quebrado pede reemissão.
     */
    @Transactional
    public void post(ConversationTurn gatilho, int descartadas, boolean emissorBarrado,
                     CeoPersona emissor) {
        if (jaAvisado(gatilho)) {
            return;
        }
        Agent portaVoz = agents.findFirstByCompanyIdAndRoleAndPersonaOrderByCreatedAtAsc(
                        gatilho.getCompanyId(), CeoPersona.CEO_ROLE, CeoPersona.QUESTIONADOR.dbValue())
                .or(() -> agents.findById(gatilho.getAgentId()))
                .orElse(null);
        if (portaVoz == null) {
            log.warn("proposta-descartada: conversa {} sem porta-voz — só o log resta",
                    gatilho.getConversationId());
            return;
        }
        ConversationTurn aviso = new ConversationTurn(UUID.randomUUID(), gatilho.getConversationId(),
                gatilho.getCompanyId(), portaVoz.getId(),
                PREFIXO + " [gatilho " + gatilho.getId() + "]");
        aviso.answer(texto(descartadas, emissorBarrado, emissor));
        turns.save(aviso);
        // Sem wakeup: já ANSWERED — visível na thread, nenhum Run novo.
    }

    private static String texto(int descartadas, boolean emissorBarrado, CeoPersona emissor) {
        String blocos = descartadas == 1 ? "1 bloco `proposta` foi descartado"
                : descartadas + " blocos `proposta` foram descartados";
        if (emissorBarrado) {
            return blocos + " nesta conversa: o " + emissor.dbValue() + " não protocola o gate — o"
                    + " pedido de contratação segue o fluxo Questionador (grill) → Planejador"
                    + " (estratégia) → Executor (protocolo). Nada chegou ao seu menu de aprovação;"
                    + " o fluxo certo já foi acionado ou você pode cobrá-lo aqui na conversa.";
        }
        return blocos + " nesta conversa: shape fora do contrato do gate. Nada chegou ao seu menu"
                + " de aprovação — se você esperava um pedido lá, cobre a reemissão aqui na conversa.";
    }

    /** Já existe aviso deste gatilho? (idempotência do settle reexecutado, padrão do caso B) */
    private boolean jaAvisado(ConversationTurn gatilho) {
        String marcador = "[gatilho " + gatilho.getId() + "]";
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                        gatilho.getConversationId(), gatilho.getCompanyId()).stream()
                .anyMatch(turno -> isAviso(turno.getOwnerMessage())
                        && turno.getOwnerMessage().endsWith(marcador));
    }
}
