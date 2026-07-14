package com.orquestrador.skills.mcp;

import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.memory.MemoryQuery;
import com.orquestrador.skills.KnowAllService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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

    /**
     * O TETO D6 da Frente C (F-C3, C10): {@code memory_fetch} traz RACIONAL durável pro contexto sem
     * passar por gate — ele AMPLIA o alcance do agente, então paga o mesmo cinto do corpo de skill.
     * ESPELHA o {@link #CAP_BODIES_POR_RUN} (mesmo número, mesma mecânica) mas é um teto PRÓPRIO, não
     * um contador compartilhado: os dois trilhos capam recursos diferentes, e um dividido deixaria a
     * busca de skill FAMINTA a busca de memória (ou vice-versa) — ninguém decidiu esse acoplamento.
     * {@code memory_search} é metadado (Camada 1 da memória): livre, fora do teto, como o {@code search}.
     */
    static final int CAP_DECISOES_POR_RUN = 3;

    private final KnowAllService knowAll;
    private final MemoryQuery memory;
    private final HeartbeatRunStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public KnowAllMcpBridge(KnowAllService knowAll, MemoryQuery memory, HeartbeatRunStore store) {
        this.knowAll = knowAll;
        this.memory = memory;
        this.store = store;
    }

    /** Abre uma sessão MCP para um Run (executor ou turno do CEO) e devolve o token efêmero do servidor. */
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

    /**
     * {@code knowall_search}: descoberta GLOBAL anotada pela company do run (livre, fora do teto).
     * FORA do perfil do CEO (F-AT2/D6): a persona não navega o catálogo de skills mid-turno.
     */
    public List<KnowAllService.SearchResult> search(String token, String query, int limit) {
        Session session = sessions.get(token);
        if (session == null || !session.serveKnowAll()) {
            return List.of();
        }
        return knowAll.search(session.scope.companyId(), query, limit);
    }

    /**
     * {@code memory_search} (F-C3, C10): os METADADOS do durável selado — LIVRE, fora do teto, como o
     * {@code knowall_search}. Metadado não é corpo: o {@code #N} + área + o que mudou é exatamente o
     * que a projeção sempre-on já ensina de graça. A FRONTEIRA (C10.b) mora na {@link MemoryQuery}.
     */
    public List<MemoryQuery.Metadados> memorySearch(String token, String query, int limit) {
        Session session = sessions.get(token);
        if (session == null) {
            return List.of();
        }
        return memory.search(session.scope.companyId(), query, limit);
    }

    /**
     * {@code memory_fetch} (F-C3, C10): o CORPO de UMA Decisão pelo endereço {@code #N}, com o TETO
     * D6 da frente aplicado AQUI — mesma mecânica do {@link #fetchBody}: dedup ANTES do cap (re-ler o
     * que já veio não estoura), cap DEPOIS (o N+1º {@code #N} DISTINTO é recusado COM MOTIVO, evento
     * {@code MEMORY_FETCH_REFUSED}, NÃO fatal — o agente segue com o que tem). Sucesso →
     * {@code MEMORY_FETCHED} (rastro leve, D5). Fora da fronteira (log cru board-only, C10.b) ou
     * {@code #N} inexistente → NOT_FOUND, que NÃO consome o teto: nada entrou no contexto.
     */
    public MemoryFetchOutcome memoryFetch(String token, int seq) {
        Session session = sessions.get(token);
        if (session == null) {
            return MemoryFetchOutcome.unknownSession("sessão MCP desconhecida ou já encerrada");
        }
        synchronized (session) {
            if (session.fetchedDecisions.contains(seq)) {
                return memory.fetch(session.scope.companyId(), seq)
                        .map(corpo -> MemoryFetchOutcome.ok(corpo, true))
                        .orElse(MemoryFetchOutcome.notFound(foraDoAlcance(seq)));
            }
            if (session.fetchedDecisions.size() >= CAP_DECISOES_POR_RUN) {
                String motivo = "teto de " + CAP_DECISOES_POR_RUN + " corpos de decisão por run atingido"
                        + " (já buscados: " + enderecos(session.fetchedDecisions) + ") — siga com o que já"
                        + " tem; corpo de #" + seq + " recusado";
                store.recordStep(session.scope.runId(), "MEMORY_FETCH_REFUSED", motivo);
                return MemoryFetchOutcome.refusedCap(motivo);
            }
            Optional<MemoryQuery.Corpo> corpo = memory.fetch(session.scope.companyId(), seq);
            if (corpo.isEmpty()) {
                return MemoryFetchOutcome.notFound(foraDoAlcance(seq));
            }
            session.fetchedDecisions.add(seq);
            store.recordStep(session.scope.runId(), "MEMORY_FETCHED", "corpo da decisão #" + seq
                    + " entregue ao agente (" + session.fetchedDecisions.size() + "/" + CAP_DECISOES_POR_RUN + ")");
            return MemoryFetchOutcome.ok(corpo.get(), false);
        }
    }

    /**
     * O motivo da recusa NÃO revela o que está do outro lado da fronteira — nem confirma que o
     * {@code #N} existe (o log cru é do dono; um oráculo de existência já seria vazamento).
     */
    private static String foraDoAlcance(int seq) {
        return "#" + seq + " não está no alcance da memória durável selada (princípios + direções)"
                + " — o log operacional/técnico é material do dono (board-only); confira o endereço"
                + " com memory_search";
    }

    private static String enderecos(Set<Integer> seqs) {
        return seqs.stream().map(seq -> "#" + seq).collect(Collectors.joining(", "));
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
        // O perfil da PONTA (F-C3): o corpo de skill é do EXECUTOR (F-AT2/D6 — "personas FORA"). O
        // script do turno do CEO nem anuncia esta tool; o core recusa mesmo assim, porque o agente
        // pode chamar pelo nome uma tool que ninguém anunciou (o script despacha por nome).
        if (!session.serveKnowAll()) {
            return FetchOutcome.refusedScope("o corpo de skill do KnowAll não está no alcance deste"
                    + " turno — proponha a instalação da skill pelo gate (propose-then-commit)");
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

    /**
     * O estado por-run: o escopo + os DOIS conjuntos de distintos já entregues (dedup + contagem dos
     * caps). Os trilhos são independentes — um teto próprio cada (ver {@link #CAP_DECISOES_POR_RUN}).
     */
    private static final class Session {
        private final McpRunScope scope;
        private final Set<String> fetched = new LinkedHashSet<>();
        private final Set<Integer> fetchedDecisions = new LinkedHashSet<>();

        private Session(McpRunScope scope) {
            this.scope = scope;
        }

        /** O trilho de SKILL (F-AT2) é do EXECUTOR; o de MEMÓRIA (F-C3) é das duas pontas. */
        private boolean serveKnowAll() {
            return scope.profile() == McpToolProfile.EXECUTOR;
        }
    }

    /**
     * O veredito de um {@code fetch_body}: {@code text} carrega o CORPO (OK) ou o MOTIVO (recusa/
     * não-encontrada). Toda recusa é NÃO fatal — o servidor MCP a devolve como conteúdo normal da
     * tool (não como erro de protocolo), o agente lê e segue. {@code deduped} distingue re-entrega.
     */
    public record FetchOutcome(Status status, String text, boolean deduped) {

        /** {@code REFUSED_SCOPE} (F-C3): a tool existe, mas não está no alcance da PONTA desta sessão. */
        public enum Status { OK, REFUSED_CAP, REFUSED_SCOPE, NOT_FOUND, UNKNOWN_SESSION }

        static FetchOutcome ok(String body, boolean deduped) {
            return new FetchOutcome(Status.OK, body, deduped);
        }

        static FetchOutcome refusedCap(String reason) {
            return new FetchOutcome(Status.REFUSED_CAP, reason, false);
        }

        static FetchOutcome refusedScope(String reason) {
            return new FetchOutcome(Status.REFUSED_SCOPE, reason, false);
        }

        static FetchOutcome notFound(String reason) {
            return new FetchOutcome(Status.NOT_FOUND, reason, false);
        }

        static FetchOutcome unknownSession(String reason) {
            return new FetchOutcome(Status.UNKNOWN_SESSION, reason, false);
        }
    }

    /**
     * O veredito de um {@code memory_fetch}: no OK vem o {@code corpo} ESTRUTURADO (racional,
     * evidência, linhagem, cluster — o servidor MCP o relaya como JSON); em toda recusa vem o
     * {@code motivo} e o corpo é {@code null}. Recusa é NÃO fatal (conteúdo normal da tool, nunca
     * erro de protocolo): o agente lê e segue. {@code deduped} distingue a re-entrega do mesmo {@code #N}.
     */
    public record MemoryFetchOutcome(FetchOutcome.Status status, MemoryQuery.Corpo corpo, String motivo,
                                     boolean deduped) {

        static MemoryFetchOutcome ok(MemoryQuery.Corpo corpo, boolean deduped) {
            return new MemoryFetchOutcome(FetchOutcome.Status.OK, corpo, null, deduped);
        }

        static MemoryFetchOutcome refusedCap(String motivo) {
            return new MemoryFetchOutcome(FetchOutcome.Status.REFUSED_CAP, null, motivo, false);
        }

        static MemoryFetchOutcome notFound(String motivo) {
            return new MemoryFetchOutcome(FetchOutcome.Status.NOT_FOUND, null, motivo, false);
        }

        /** Chamada sem {@code seq} (o agente esqueceu o endereço): recusa legível, nunca 500. */
        static MemoryFetchOutcome semEndereco() {
            return notFound("informe o endereço #N da decisão no campo 'seq' (ex.: 7 para #7)"
                    + " — use memory_search para descobri-lo");
        }

        static MemoryFetchOutcome unknownSession(String motivo) {
            return new MemoryFetchOutcome(FetchOutcome.Status.UNKNOWN_SESSION, null, motivo, false);
        }
    }
}
