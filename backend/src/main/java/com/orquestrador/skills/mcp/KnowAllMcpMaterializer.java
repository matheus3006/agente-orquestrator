package com.orquestrador.skills.mcp;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializa o SERVIDOR MCP-stdio do KnowAll no workspace de um Run de EXECUTOR (F-AT2, grill
 * agente-como-tool D2) — irmão do {@code HookMaterializer} (F-N6): um script Node ÚNICO, motor-
 * agnóstico, gravado em {@code .orq/mcp/knowall-server.js}. O {@code .orq/} é protocolo do produto
 * (fora do replace de materialização, ADR-0030/M6) e no LOCAL (v1) É o cwd do processo — o CLI
 * spawna {@code node .orq/mcp/knowall-server.js <baseUrl> <token>} como subprocesso MCP.
 *
 * <p>O script é ESTÁTICO (como o {@code GUARD_SCRIPT}): baseUrl + token efêmero do run chegam por
 * ARGV (não {@code env} — o codex nem herda o env do pai, laudo Gotcha 2), então o mesmo script serve
 * qualquer run. Node é garantido no PATH (os 2 CLIs são pacotes npm; ambos os Dockerfiles têm node).
 * Cada tool se anuncia {@code readOnlyHint:true} — é VERDADE (leitura pura) e satisfaz o gate próprio
 * do codex no headless (laudo R12-B). A busca/leitura de fato mora no {@code KnowAllMcpBridge} via
 * callback HTTP loopback com o token; o script é um relay burro (nunca enforça o teto — isso é core).
 */
@Component
public class KnowAllMcpMaterializer {

    /** Caminho raiz-relativo do servidor MCP no workspace (o cwd do run no LOCAL). */
    public static final String SCRIPT_RELATIVE_PATH = ".orq/mcp/knowall-server.js";

    /** O nome do servidor no registro MCP dos dois motores (claude {@code --mcp-config}/codex {@code -c}). */
    public static final String SERVER_NAME = "knowall";

    /**
     * O servidor MCP-stdio do KnowAll — JSON-RPC 2.0 newline-delimited (molde provado na sonda R12-B),
     * zero dependência (só {@code readline}/{@code http}). Duas tools read-only que fazem callback
     * loopback pro {@code KnowAllMcpBridgeController} com o token do run no header {@code X-Orq-Mcp-Token}.
     * Toda recusa do teto/não-encontrada volta como CONTEÚDO normal da tool (isError:false) — não fatal.
     */
    static final String SERVER_SCRIPT = """
            'use strict';
            // Servidor MCP-stdio do KnowAll (F-AT2). argv[2]=baseUrl do callback, argv[3]=token do run.
            const readline = require('readline');
            const http = require('http');
            const { URL } = require('url');

            const BASE_URL = process.argv[2];
            const TOKEN = process.argv[3];
            // F-C3: a PONTA deste run ('executor' | 'ceo') — decide QUAIS tools são anunciadas. O core
            // recusa de novo, por perfil: este script é relay burro (o agente poderia adulterá-lo).
            const PROFILE = process.argv[4] || 'executor';

            const rl = readline.createInterface({ input: process.stdin, terminal: false });

            function send(obj) {
              process.stdout.write(JSON.stringify(obj) + '\\n');
            }

            const KNOWALL_TOOLS = [
              {
                name: 'knowall_search',
                description: 'Search the global KnowAll skill catalog by metadata (name/description/triggers). '
                  + 'Read-only. Returns matching skills with skillKey, name, description and install state. '
                  + 'Use it to DISCOVER the skillKey of a capability you are missing, then knowall_fetch_body it.',
                inputSchema: {
                  type: 'object',
                  properties: {
                    query: { type: 'string', description: 'free-text query matched against skill metadata' },
                    limit: { type: 'number', description: 'max results (optional, default 20)' },
                  },
                  required: ['query'],
                  additionalProperties: false,
                },
                annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
              },
              {
                name: 'knowall_fetch_body',
                description: 'Fetch the full instruction body (SKILL.md) of a catalog skill by its skillKey, '
                  + 'to USE RIGHT NOW in this run without waiting for an install. Read-only. There is a per-run '
                  + 'cap on how many distinct bodies you may load; over the cap the call returns a refusal '
                  + 'message and you should continue with what you already have.',
                inputSchema: {
                  type: 'object',
                  properties: {
                    skillKey: { type: 'string', description: 'the global skillKey (from knowall_search)' },
                  },
                  required: ['skillKey'],
                  additionalProperties: false,
                },
                annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
              },
            ];

            // F-C3 (C10): a memória DURÁVEL SELADA da empresa — princípios (grau=PRINCIPIO) e direções
            // do dono, endereçáveis por #N. As duas pontas (executor e CEO) enxergam estas tools.
            const MEMORY_TOOLS = [
              {
                name: 'memory_search',
                description: 'Search the company\\'s sealed durable memory (owner-sealed PRINCIPLES and '
                  + 'DIRECTIONS) by metadata: area and what changed. Read-only and FREE (no per-run cap). '
                  + 'Returns each decision\\'s address #N, date, area, tipo, grau, what changed, and whether '
                  + 'it is still current (a superseded one names the #N that replaced it). Use it to find the '
                  + '#N of a past decision, then memory_fetch it to learn WHY it was made. The raw '
                  + 'operational/technical decision log is the owner\\'s material and is not reachable here.',
                inputSchema: {
                  type: 'object',
                  properties: {
                    query: { type: 'string', description: 'free-text query matched against area + what changed' },
                    limit: { type: 'number', description: 'max results (optional, default 20)' },
                  },
                  required: ['query'],
                  additionalProperties: false,
                },
                annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
              },
              {
                name: 'memory_fetch',
                description: 'Fetch the BODY of one sealed decision by its address #N: the RATIONALE (why the '
                  + 'owner decided it), the evidence, the lineage (what it superseded / what superseded it) and '
                  + 'the cluster of sibling decisions in the same area. Read-only. This is how you answer '
                  + '"why did we decide #7?". There is a per-run cap on how many DISTINCT bodies you may load; '
                  + 'over the cap the call returns a refusal message and you should continue with what you have. '
                  + 'Re-fetching the same #N does not consume the cap.',
                inputSchema: {
                  type: 'object',
                  properties: {
                    seq: { type: 'number', description: 'the decision address #N (from memory_search or from '
                      + 'the always-on principles), as a plain number: 7 for #7' },
                  },
                  required: ['seq'],
                  additionalProperties: false,
                },
                annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
              },
            ];

            // A ponta do CEO NÃO recebe o trilho de corpo de skill (F-AT2/D6: "personas FORA" — o CEO
            // PROPÕE skill pelo gate). A do executor recebe os dois trilhos.
            const TOOLS = (PROFILE === 'ceo') ? MEMORY_TOOLS : KNOWALL_TOOLS.concat(MEMORY_TOOLS);

            function callCore(path, payload) {
              return new Promise((resolve, reject) => {
                const url = new URL(path, BASE_URL);
                const body = JSON.stringify(payload);
                const req = http.request({
                  hostname: url.hostname,
                  port: url.port,
                  path: url.pathname,
                  method: 'POST',
                  headers: {
                    'content-type': 'application/json',
                    'content-length': Buffer.byteLength(body),
                    'x-orq-mcp-token': TOKEN,
                  },
                }, (res) => {
                  let data = '';
                  res.on('data', (chunk) => { data += chunk; });
                  res.on('end', () => {
                    if (res.statusCode < 200 || res.statusCode >= 300) {
                      reject(new Error('core HTTP ' + res.statusCode));
                      return;
                    }
                    try { resolve(JSON.parse(data)); } catch (parseError) { reject(parseError); }
                  });
                });
                req.on('error', reject);
                req.write(body);
                req.end();
              });
            }

            function textResult(id, text, isError) {
              send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: text }], isError: !!isError } });
            }

            rl.on('line', (raw) => {
              let msg;
              try { msg = JSON.parse(raw); } catch (parseError) { return; }
              const id = msg.id;
              const method = msg.method;
              const params = msg.params;

              if (method === 'initialize') {
                send({ jsonrpc: '2.0', id, result: {
                  protocolVersion: (params && params.protocolVersion) ? params.protocolVersion : '2025-03-26',
                  capabilities: { tools: {} },
                  serverInfo: { name: 'knowall-mcp', version: '1.0.0' },
                } });
                return;
              }
              if (method === 'notifications/initialized') { return; }
              if (method === 'tools/list') { send({ jsonrpc: '2.0', id, result: { tools: TOOLS } }); return; }
              if (method === 'ping') { send({ jsonrpc: '2.0', id, result: {} }); return; }

              if (method === 'tools/call') {
                const name = params && params.name;
                const args = (params && params.arguments) || {};
                if (name === 'knowall_search') {
                  callCore('/internal/mcp/knowall/search', { query: args.query || '', limit: args.limit || 20 })
                    .then((results) => textResult(id, JSON.stringify(results), false))
                    .catch((callError) => textResult(id, 'knowall_search falhou: ' + callError.message, true));
                  return;
                }
                if (name === 'knowall_fetch_body') {
                  callCore('/internal/mcp/knowall/fetch-body', { skillKey: args.skillKey || '' })
                    .then((r) => textResult(id, (r && typeof r.text === 'string') ? r.text : JSON.stringify(r), false))
                    .catch((callError) => textResult(id, 'knowall_fetch_body falhou: ' + callError.message, true));
                  return;
                }
                if (name === 'memory_search') {
                  callCore('/internal/mcp/memory/search', { query: args.query || '', limit: args.limit || 20 })
                    .then((results) => textResult(id, JSON.stringify(results), false))
                    .catch((callError) => textResult(id, 'memory_search falhou: ' + callError.message, true));
                  return;
                }
                if (name === 'memory_fetch') {
                  // O veredito volta ESTRUTURADO ({status, corpo, motivo}) — a recusa do teto/fora-do-
                  // escopo viaja como CONTEÚDO normal da tool (isError:false): o agente lê e segue.
                  callCore('/internal/mcp/memory/fetch', { seq: args.seq })
                    .then((r) => textResult(id, JSON.stringify(r), false))
                    .catch((callError) => textResult(id, 'memory_fetch falhou: ' + callError.message, true));
                  return;
                }
                textResult(id, 'unknown tool ' + name, true);
                return;
              }

              if (id !== undefined) {
                send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found: ' + method } });
              }
            });

            process.stdin.on('end', () => process.exit(0));
            """;

    public void materialize(Path workDir) {
        Path scriptPath = workDir.resolve(SCRIPT_RELATIVE_PATH);
        try {
            Files.createDirectories(scriptPath.getParent());
            Files.writeString(scriptPath, SERVER_SCRIPT, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao materializar o servidor MCP do KnowAll em " + workDir, exception);
        }
    }
}
