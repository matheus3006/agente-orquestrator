package com.orquestrador.skills.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * O baseUrl LOOPBACK que o servidor MCP-stdio do KnowAll usa para chamar de volta o core (F-AT2): o
 * subprocesso {@code node} recebe este endereço por ARGV e POSTa no {@link KnowAllMcpBridgeController}.
 * Capturado da PORTA REALMENTE ligada ({@link WebServerInitializedEvent}) — funciona igual em prod
 * (8080), na instância descartável do /verify (18081) e em teste de porta aleatória, sem configuração.
 * Override explícito por {@code orquestrador.mcp.callback-base-url} (ex.: apontar outro host no dev).
 *
 * <p>{@code null} até o servidor web subir — mas nenhum Run executa antes disso, e o executor só liga o
 * MCP no alvo LOCAL (a (ii)-docker é v1.1, D7): sob {@code WebEnvironment.MOCK} (sem servidor) fica
 * null e o executor simplesmente não anexa o servidor MCP (o run segue normal, o caminho pré-F-AT2).
 */
@Component
public class McpCallbackAddress {

    private volatile String baseUrl;

    public McpCallbackAddress(@Value("${orquestrador.mcp.callback-base-url:}") String override) {
        this.baseUrl = (override != null && !override.isBlank()) ? override.trim() : null;
    }

    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {
        if (baseUrl == null) {
            baseUrl = "http://127.0.0.1:" + event.getWebServer().getPort();
        }
    }

    /** O baseUrl do callback loopback, ou {@code null} se o servidor web ainda não ligou. */
    public String baseUrl() {
        return baseUrl;
    }
}
