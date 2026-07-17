package com.orquestrador.conversation.persona;

import tools.jackson.databind.JsonNode;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import com.orquestrador.work.issue.AbsorcaoDeIssueRouter;
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
    private final AbsorcaoDeIssueRouter absorcoes;

    public PersonaBatonRouter(AgentRepository agents, PersonaHandoffScanner scanner,
                              PersonaContinuationPoster poster, CorrecaoDeEntregaRouter correcoes,
                              AbsorcaoDeIssueRouter absorcoes) {
        this.agents = agents;
        this.scanner = scanner;
        this.poster = poster;
        this.correcoes = correcoes;
        this.absorcoes = absorcoes;
    }

    /** Caminho regex (persona não migrada / fallback do malformado): as viradas saem da prosa. */
    public void afterCeoTurnAnswered(ConversationTurn turno, String reply) {
        route(turno, scanner.scan(reply));
    }

    /**
     * F-N3 (ADR-0039, D2): o caminho ESTRUTURADO — as viradas saem dos {@code blocos[]} já validados
     * pelo motor na fonte (schema nativo), sem regex. Mesmo roteamento e mesmas restrições de emissor
     * do regex (só o transporte mudou): o {@code PersonaHandoffScanner} despacha por {@code tipo}.
     */
    public void afterCeoTurnAnsweredStructured(ConversationTurn turno, List<JsonNode> blocos) {
        route(turno, scanner.scanStructured(blocos));
    }

    private void route(ConversationTurn turno, List<PersonaHandoffScanner.Route> routes) {
        CeoPersona origem = agents.findById(turno.getAgentId())
                .flatMap(agent -> CeoPersona.fromDbValue(agent.getPersona()))
                .orElse(null);
        if (origem == null) {
            return; // CEO legado/agente comum: o fluxo de personas não existe para ele
        }
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
                // Absorção (ADR-0037/D3): triagem é estratégia de orquestração — só o PLANEJADOR
                // roteia ```absorcao```; Q/E emitindo é log + descarte, espelho byte a byte da
                // restrição do caso B acima.
                case PersonaHandoffScanner.Absorcao absorcao -> {
                    if (origem != CeoPersona.PLANEJADOR) {
                        log.warn("absorcao: persona '{}' emitiu ```absorcao``` — só o Planejador"
                                + " tria o board (descartado, fail-soft)", origem.dbValue());
                        yield false;
                    }
                    yield absorcoes.rotear(turno, absorcao);
                }
                // Auto-kickback entre personas (rodada 9): só o EXECUTOR devolve — é o único que
                // RECEBE um entregável de outra persona (o Planejador só recebe do Questionador, e
                // um defeito no dossiê ainda é lacuna de intenção-do-usuário, não deste canal, D7).
                // O destino NUNCA é um campo do bloco: o poster o deriva do bastão mais recente da
                // conversa (mesmo princípio de "nunca confiar em identidade do LLM" do `origem`
                // acima) — precisa do HISTÓRICO da conversa (a reincidência pós-prossiga responde
                // à sentinela de prossiga, não a um bastão fresco), por isso mora lá, não aqui.
                case PersonaHandoffScanner.Retrabalho retrabalho -> {
                    if (origem != CeoPersona.EXECUTOR) {
                        log.warn("retrabalho: persona '{}' emitiu ```retrabalho``` — só o Executor"
                                + " devolve entregável a quem entregou (descartado, fail-soft)",
                                origem.dbValue());
                        yield false;
                    }
                    yield poster.postRetrabalho(turno.getCompanyId(), turno.getConversationId(),
                            origem, retrabalho.lacuna());
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
