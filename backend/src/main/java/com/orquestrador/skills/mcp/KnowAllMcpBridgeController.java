package com.orquestrador.skills.mcp;

import com.orquestrador.skills.KnowAllService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * O callback LOOPBACK do servidor MCP-stdio do KnowAll (F-AT2, grill agente-como-tool D2): o
 * subprocesso {@code node} materializado no run POSTa aqui com o TOKEN efêmero do run no header
 * {@code X-Orq-Mcp-Token}; o {@link KnowAllMcpBridge} resolve o token → escopo do run e serve a
 * busca/leitura com a IDENTIDADE DO PRODUTO. Não é a superfície rejeitada pela 7c (D2): sem
 * credencial no {@code env} do agente, sem endpoint ator-AGENT — o token não é identidade do agente,
 * é um handle por-run de leitura read-only (search livre + fetch_body capado no bridge). Rota
 * {@code /internal/**} (interna, não a API pública {@code /api/companies}). Endurecer o bind a
 * loopback + a fronteira do container ficam para a (ii)-docker (v1.1, D7).
 *
 * <p>Token ausente/desconhecido → 401 (sessão morta). Toda RECUSA de negócio (teto/não-encontrada) é
 * 200 com {@code {status,text}} — não-fatal: o servidor MCP relaya o {@code text} como conteúdo normal
 * da tool e o agente segue com o que tem (D4 anti-silêncio).
 */
@RestController
@RequestMapping("/internal/mcp/knowall")
public class KnowAllMcpBridgeController {

    static final String TOKEN_HEADER = "X-Orq-Mcp-Token";

    private final KnowAllMcpBridge bridge;

    public KnowAllMcpBridgeController(KnowAllMcpBridge bridge) {
        this.bridge = bridge;
    }

    @PostMapping("/search")
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

    @PostMapping("/fetch-body")
    public ResponseEntity<KnowAllMcpBridge.FetchOutcome> fetchBody(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody FetchBodyRequest request) {
        if (!bridge.recognizes(token)) {
            return ResponseEntity.status(401).build();
        }
        String skillKey = request != null ? request.skillKey() : null;
        return ResponseEntity.ok(bridge.fetchBody(token, skillKey));
    }

    public record SearchRequest(String query, Integer limit) {
    }

    public record FetchBodyRequest(String skillKey) {
    }
}
