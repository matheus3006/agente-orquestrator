package com.orquestrador.skills.mcp;

import com.orquestrador.memory.MemoryQuery;
import com.orquestrador.skills.KnowAllService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * O callback LOOPBACK do servidor MCP-stdio (F-AT2, grill agente-como-tool D2; ampliado na F-C3/C10):
 * o subprocesso {@code node} materializado no run POSTa aqui com o TOKEN efêmero do run no header
 * {@code X-Orq-Mcp-Token}; o {@link KnowAllMcpBridge} resolve o token → escopo do run e serve a
 * busca/leitura com a IDENTIDADE DO PRODUTO. Não é a superfície rejeitada pela 7c (D2): sem
 * credencial no {@code env} do agente, sem endpoint ator-AGENT — o token não é identidade do agente,
 * é um handle por-run de leitura read-only (search livre + fetch capado no bridge). Rota
 * {@code /internal/**} (interna, não a API pública {@code /api/companies}). Endurecer o bind a
 * loopback + a fronteira do container ficam para a (ii)-docker (v1.1, D7).
 *
 * <p>DUAS famílias de rota, UM canal (C10 — "zero canal novo"): {@code /knowall/*} (corpo de skill,
 * F-AT2, só na ponta do EXECUTOR) e {@code /memory/*} (a memória durável selada, F-C3, nas duas
 * pontas). Mesmo token, mesma sessão, mesmo guard — o que muda é o {@link McpToolProfile} do escopo.
 *
 * <p>Token ausente/desconhecido → 401 (sessão morta). Toda RECUSA de negócio (teto/fora-do-escopo/
 * não-encontrada) é 200 com {@code {status,…,motivo}} — não-fatal: o servidor MCP a relaya como
 * conteúdo normal da tool e o agente segue com o que tem (D4 anti-silêncio).
 */
@RestController
@RequestMapping("/internal/mcp")
public class KnowAllMcpBridgeController {

    static final String TOKEN_HEADER = "X-Orq-Mcp-Token";

    private final KnowAllMcpBridge bridge;

    public KnowAllMcpBridgeController(KnowAllMcpBridge bridge) {
        this.bridge = bridge;
    }

    @PostMapping("/knowall/search")
    public ResponseEntity<List<KnowAllService.SearchResult>> search(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody SearchRequest request) {
        if (!bridge.recognizes(token)) {
            return ResponseEntity.status(401).build();
        }
        int limit = request != null && request.limit() != null ? request.limit() : 20;
        String query = request != null ? request.query() : null;
        return ResponseEntity.ok(bridge.search(token, query, limit));
    }

    @PostMapping("/knowall/fetch-body")
    public ResponseEntity<KnowAllMcpBridge.FetchOutcome> fetchBody(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody FetchBodyRequest request) {
        if (!bridge.recognizes(token)) {
            return ResponseEntity.status(401).build();
        }
        String skillKey = request != null ? request.skillKey() : null;
        return ResponseEntity.ok(bridge.fetchBody(token, skillKey));
    }

    /** {@code memory_search} (F-C3): metadados do durável selado — livre, fora do teto. */
    @PostMapping("/memory/search")
    public ResponseEntity<List<MemoryQuery.Metadados>> memorySearch(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody SearchRequest request) {
        if (!bridge.recognizes(token)) {
            return ResponseEntity.status(401).build();
        }
        int limit = request != null && request.limit() != null ? request.limit() : 20;
        String query = request != null ? request.query() : null;
        return ResponseEntity.ok(bridge.memorySearch(token, query, limit));
    }

    /** {@code memory_fetch} (F-C3): o corpo de UMA Decisão pelo #N — sob o teto do D6 (no bridge). */
    @PostMapping("/memory/fetch")
    public ResponseEntity<KnowAllMcpBridge.MemoryFetchOutcome> memoryFetch(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody MemoryFetchRequest request) {
        if (!bridge.recognizes(token)) {
            return ResponseEntity.status(401).build();
        }
        if (request == null || request.seq() == null) {
            return ResponseEntity.ok(KnowAllMcpBridge.MemoryFetchOutcome.semEndereco());
        }
        return ResponseEntity.ok(bridge.memoryFetch(token, request.seq()));
    }

    public record SearchRequest(String query, Integer limit) {
    }

    public record FetchBodyRequest(String skillKey) {
    }

    public record MemoryFetchRequest(Integer seq) {
    }
}
