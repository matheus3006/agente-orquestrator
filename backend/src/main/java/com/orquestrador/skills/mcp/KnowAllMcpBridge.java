package com.orquestrador.skills.mcp;

import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.skills.KnowAllService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O CORE por trás do servidor MCP do KnowAll (F-AT2, grill agente-como-tool D2/D4/D5): media as
 * chamadas {@code knowall_search}/{@code knowall_fetch_body} que o EXECUTOR faz mid-run, com a
 * IDENTIDADE DO PRODUTO (não do agente) — o servidor MCP-stdio (subprocesso do CLI) só carrega um
 * TOKEN efêmero por-run; o escopo real ({@link McpRunScope}) mora AQUI, resolvido a partir do token.
 * Sem credencial no {@code env} do agente, sem endpoint ator-AGENT (D2).
 *
 * <p><b>O teto do #1 (D4) é enforcement de PRIMEIRA CLASSE aqui — não no script materializado</b>
 * (que o agente poderia adulterar): {@code fetch_body} tem cap-por-run de corpos DISTINTOS
 * ({@value #CAP_BODIES_POR_RUN}) + dedup por skill (mesmo corpo 2× = 1 leitura, não consome o teto).
 * Estourou → recusa COM MOTIVO (anti-silêncio), NÃO fatal: volta como resposta da tool e o agente
 * segue com o que tem. {@code search} é descoberta de metadados (Camada 1) — livre, não entra no teto.
 *
 * <p>O número {@value #CAP_BODIES_POR_RUN}: mais apertado que o teto de PEDIDOS da F-AT1 (5, higiene
 * de fila com clique do dono) porque aqui o corpo cai DIRETO no contexto do run SEM gate — é o cinto
 * literal do #1 (re-poluição). Mesmo espírito do 6/3 do codex: um número pequeno, embutido, provado.
 */
@Component
public class KnowAllMcpBridge {

    static final int CAP_BODIES_POR_RUN = 3;

    private final KnowAllService knowAll;
    private final HeartbeatRunStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public KnowAllMcpBridge(KnowAllService knowAll, HeartbeatRunStore store) {
        this.knowAll = knowAll;
        this.store = store;
    }

    /** Abre uma sessão MCP para um Run de executor e devolve o token efêmero que o servidor apresenta. */
    public String open(McpRunScope scope) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Session(scope));
        return token;
    }

    /** Fecha a sessão (chamado no finally do run) — o token vira inválido; nova chamada é recusada. */
    public void close(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Este token corresponde a uma sessão VIVA? (o controller loopback recusa 401 quando não.) */
    public boolean recognizes(String token) {
        return token != null && sessions.containsKey(token);
    }

    /** {@code knowall_search}: descoberta GLOBAL anotada pela company do run (livre, fora do teto). */
    public List<KnowAllService.SearchResult> search(String token, String query, int limit) {
        Session session = sessions.get(token);
        if (session == null) {
            return List.of();
        }
        return knowAll.search(session.scope.companyId(), query, limit);
    }

    /**
     * {@code knowall_fetch_body}: o corpo de UMA skill fora do set, com o teto do #1 (D4) aplicado
     * AQUI. Dedup ANTES do cap (re-buscar o que já veio não estoura); cap DEPOIS (o N+1º distinto é
     * recusado com motivo, evento {@code SKILL_BODY_FETCH_REFUSED}). Sucesso → {@code SKILL_BODY_FETCHED}
     * (D5, rastro leve). Chave inexistente → NOT_FOUND (não consome o teto — nada entrou no contexto).
     */
    public FetchOutcome fetchBody(String token, String skillKey) {
        Session session = sessions.get(token);
        if (session == null) {
            return FetchOutcome.unknownSession("sessão MCP do KnowAll desconhecida ou já encerrada");
        }
        synchronized (session) {
            // Dedup: o mesmo corpo já entregue neste run é re-servido SEM consumir o teto (já está no
            // contexto do agente; re-buscar não aumenta a distinta contagem — "2× = 1 leitura", D4).
            if (session.fetched.contains(skillKey)) {
                return knowAll.fetchBody(skillKey)
                        .map(body -> FetchOutcome.ok(body, true))
                        .orElse(FetchOutcome.notFound("skill '" + skillKey + "' não está mais no catálogo"));
            }
            // Cap: o N+1º corpo DISTINTO é recusado com motivo (não fatal) — o agente segue com o que tem.
            if (session.fetched.size() >= CAP_BODIES_POR_RUN) {
                String reason = "teto de " + CAP_BODIES_POR_RUN + " corpos de skill por run atingido (já"
                        + " buscados: " + String.join(", ", session.fetched) + ") — siga com o que já tem;"
                        + " corpo de '" + skillKey + "' recusado";
                store.recordStep(session.scope.runId(), "SKILL_BODY_FETCH_REFUSED", reason);
                return FetchOutcome.refusedCap(reason);
            }
            Optional<String> body = knowAll.fetchBody(skillKey);
            if (body.isEmpty()) {
                return FetchOutcome.notFound("skill '" + skillKey + "' não encontrada no catálogo do KnowAll");
            }
            session.fetched.add(skillKey);
            store.recordStep(session.scope.runId(), "SKILL_BODY_FETCHED", "corpo da skill '" + skillKey
                    + "' entregue ao executor mid-run (" + session.fetched.size() + "/" + CAP_BODIES_POR_RUN + ")");
            return FetchOutcome.ok(body.get(), false);
        }
    }

    /** O estado por-run: o escopo + o conjunto de corpos DISTINTOS já entregues (dedup + contagem do cap). */
    private static final class Session {
        private final McpRunScope scope;
        private final Set<String> fetched = new LinkedHashSet<>();

        private Session(McpRunScope scope) {
            this.scope = scope;
        }
    }

    /**
     * O veredito de um {@code fetch_body}: {@code text} carrega o CORPO (OK) ou o MOTIVO (recusa/
     * não-encontrada). Toda recusa é NÃO fatal — o servidor MCP a devolve como conteúdo normal da
     * tool (não como erro de protocolo), o agente lê e segue. {@code deduped} distingue re-entrega.
     */
    public record FetchOutcome(Status status, String text, boolean deduped) {

        public enum Status { OK, REFUSED_CAP, NOT_FOUND, UNKNOWN_SESSION }

        static FetchOutcome ok(String body, boolean deduped) {
            return new FetchOutcome(Status.OK, body, deduped);
        }

        static FetchOutcome refusedCap(String reason) {
            return new FetchOutcome(Status.REFUSED_CAP, reason, false);
        }

        static FetchOutcome notFound(String reason) {
            return new FetchOutcome(Status.NOT_FOUND, reason, false);
        }

        static FetchOutcome unknownSession(String reason) {
            return new FetchOutcome(Status.UNKNOWN_SESSION, reason, false);
        }
    }
}
