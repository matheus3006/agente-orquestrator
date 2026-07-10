package com.orquestrador.conversation.persona;

import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
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

    public PersonaBatonRouter(AgentRepository agents, PersonaHandoffScanner scanner,
                              PersonaContinuationPoster poster) {
        this.agents = agents;
        this.scanner = scanner;
        this.poster = poster;
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
