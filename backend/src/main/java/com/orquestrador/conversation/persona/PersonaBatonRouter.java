package com.orquestrador.conversation.persona;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import com.orquestrador.work.issue.CorrecaoDeEntregaRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * O ROTEADOR do bastão (ADR-0031, M5): módulo profundo atrás de um seam de um método — a persona
 * emite um bloco tipado na resposta; QUEM reivindica o próximo turno fica escondido aqui.
 * Roda no settle do turno (o mesmo ponto do {@code ProposalScanner} → gate T3, que segue intocado):
 * {@code ```handoff} → continuação da persona-alvo; {@code ```needs-grilling} → kickback do
 * Questionador (com a trava M9 no poster). Turno de agente SEM persona (CEO legado) não roteia.
 * UMA virada por settle: a primeira roteável vence; extras são ignoradas com log (fail-soft).
 */
@Component
public class PersonaBatonRouter {

    private static final Logger log = LoggerFactory.getLogger(PersonaBatonRouter.class);

    private final AgentRepository agents;
    private final PersonaHandoffScanner scanner;
    private final PersonaContinuationPoster poster;
    private final CorrecaoDeEntregaRouter correcoes;

    public PersonaBatonRouter(AgentRepository agents, PersonaHandoffScanner scanner,
                              PersonaContinuationPoster poster, CorrecaoDeEntregaRouter correcoes) {
        this.agents = agents;
        this.scanner = scanner;
        this.poster = poster;
        this.correcoes = correcoes;
    }

    public void afterCeoTurnAnswered(ConversationTurn turno, String reply) {
        CeoPersona origem = agents.findById(turno.getAgentId())
                .flatMap(agent -> CeoPersona.fromDbValue(agent.getPersona()))
                .orElse(null);
        if (origem == null) {
            return; // CEO legado/agente comum: o fluxo de personas não existe para ele
        }
        List<PersonaHandoffScanner.Route> routes = scanner.scan(reply);
        for (int i = 0; i < routes.size(); i++) {
            boolean posted = switch (routes.get(i)) {
                case PersonaHandoffScanner.Handoff handoff ->
                        poster.postBaton(turno.getCompanyId(), turno.getConversationId(), origem, handoff);
                case PersonaHandoffScanner.NeedsGrilling kickback ->
                        poster.postKickback(turno.getCompanyId(), turno.getConversationId(), origem,
                                kickback.lacuna());
                // Caso B (ADR-0034/D1): roteado DIRETO — sem cascata P→E, sem gate (o pedido do
                // dono É a autorização). Restrição de emissor: só o BALCÃO roteia correção; P/E
                // emitindo é log + descarte, espelho do "uma virada por settle".
                case PersonaHandoffScanner.Correcao correcao -> {
                    if (origem != CeoPersona.QUESTIONADOR) {
                        log.warn("correcao: persona '{}' emitiu ```correcao``` — só o Questionador"
                                + " roteia o caso B (descartado, fail-soft)", origem.dbValue());
                        yield false;
                    }
                    yield correcoes.rotear(turno, correcao.issue());
                }
            };
            if (posted) {
                if (i < routes.size() - 1) {
                    log.warn("persona: {} bloco(s) extra(s) na mesma resposta ignorados (uma virada"
                            + " por settle)", routes.size() - 1 - i);
                }
                return;
            }
        }
    }
}
