# Laudo — Sonda MCP-consume (R12-B, 2026-07-12) — GATE da F-AT2

> **Produto da faixa R12-B da rodada 12.** Responde a UMA pergunta de fato que gateia a F-AT2
> (`docs/handoff-grilling-agente-como-tool.md` §3): **claude `-p` e codex `exec` conseguem
> descobrir e CHAMAR ferramentas de um servidor MCP stdio na invocação EXATA do produto
> (headless, os argv reais do orquestrador)?** Molde R6-B: CLIs dirigidos DIRETAMENTE em
> workspaces descartáveis do scratchpad — **zero código de produto, zero commit, zero instância
> do produto, zero porta tocada**. Este arquivo nasce UNTRACKED (a F-AT2 commita). Snapshot: main
> `3fdda05` (piso 640/286), lido só via `git show main:...` (a R12-A pode estar editando a working
> tree em paralelo — não tocada aqui).

**Versões testadas no host:** `codex-cli 0.142.5` (== pino do produto). `claude 2.1.206` (mesma
assimetria já registrada no R6-B: pino de referência 2.1.201, host tem 2.1.206 — sem impacto nos
vereditos, são provas de comportamento do CLI).

**Método:** espelhei a invocação REAL lida em `ClaudeArgv.java`/`CodexArgv.java` @ `3fdda05`
(`git show main:backend/src/main/java/com/orquestrador/execution/adapter/{Claude,Codex}Argv.java`)
mais o parâmetro de MCP candidato do handoff (§5): claude `--mcp-config`; codex `-c
mcp_servers...`. Servidor MCP de brinquedo (`scratchpad/mcp-probe/toy-server.js`, JSON-RPC 2.0
puro sobre stdio, zero dependência) com duas tools: `ping` (sem args) e `echo_body` (1 arg,
simula `knowall_fetch_body`) — cada uma devolve um marcador conhecido, não ambíguo com nada que o
modelo poderia alucinar.

---

## Veredito por motor

| Motor | Descobre a tool? | Chama a tool? | Resposta chega ao modelo/saída? | Aparece no usage normal (sem off-book)? | Fricção de aprovação? |
|---|---|---|---|---|---|
| **claude** `-p` | **SIM** | **SIM** | **SIM** (`PONG_MARKER_7f3a9c` / `BODY_MARKER_START[[SKILL_BODY_XYZ42]]BODY_MARKER_END` no `result`) | **SIM** (usage único do turno, tool call é round-trip normal, não subagente) | **NENHUMA** sob `--permission-mode bypassPermissions` (com ou sem anotação `readOnlyHint`) |
| **codex** `exec` | **SIM** | **SIM, COM PRÉ-REQUISITO** (ver Gate abaixo) | **SIM** (idem, nos itens `mcp_tool_call`→`agent_message`) | **SIM** (usage único do `turn.completed`, mesma lógica) | **SIM por padrão** — cancela silenciosamente sem a tool anotada `readOnlyHint:true` (ou config equivalente) |

**Veredito da pergunta de fato: PASS nos dois motores**, com uma dependência de desenho
NOVA e CONCRETA para a F-AT2 do lado codex (abaixo) — não é um "falso", é um requisito de
construção do servidor MCP do KnowAll.

---

## Prova — claude

### Grammar exata que funciona
```
claude -p "<prompt>" \
  --output-format stream-json --verbose \
  --permission-mode bypassPermissions \
  --mcp-config <arquivo.json> \
  --strict-mcp-config
```
`<arquivo.json>` (formato mínimo confirmado, sem campo `"type"` — infere stdio pela presença de
`command`):
```json
{
  "mcpServers": {
    "toy": { "command": "node", "args": ["/caminho/toy-server.js"] }
  }
}
```
`--strict-mcp-config` é **recomendado** para a F-AT2 (não obrigatório para o veredito): isola o
run para enxergar SÓ o servidor do KnowAll, ignorando qualquer `.mcp.json`/conector já configurado
no host/operador — o `mcp_servers` do `system.init` confirmou `[{"name":"toy","status":
"connected"}]`, nada mais vazou.

### Prova — ping (sem args)
Run `claude-run1.jsonl`: `system.init.tools` já lista `mcp__toy__ping`/`mcp__toy__echo_body`;
`mcp_servers:[{"name":"toy","status":"connected"}]`. O modelo chamou `ToolSearch({query:
"select:mcp__toy__ping"})` **antes** de chamar a tool (ver Gotcha 1), depois `mcp__toy__ping`
(`tool_use_meta.server_display_name:"toy-mcp-probe"`), recebeu `tool_result` com
`PONG_MARKER_7f3a9c` e ecoou exatamente isso no `result` final. Zero prompt de aprovação.

### Prova — echo_body (1 arg, simula fetch_body)
Run `claude-run3.jsonl`: mesma sequência (`ToolSearch` → `mcp__toy__echo_body({text:
"SKILL_BODY_XYZ42"})`) → `tool_result`: `BODY_MARKER_START[[SKILL_BODY_XYZ42]]BODY_MARKER_END` →
`result` final idêntico. Argumento ida-e-volta íntegro.

### Gotcha 1 — hop extra de `ToolSearch` (custo/latência, não bloqueio)
Nas DUAS provas o modelo emitiu um `ToolSearch({query:"select:mcp__toy__<tool>"})` **antes** de
chamar a tool MCP de fato, mesmo a tool já aparecendo em `system.init.tools`. É o mecanismo padrão
de tool deferido deste build do Claude Code — **não é específico do MCP nem do produto**, mas é
um turno a mais (latência + tokens) que o desenho da F-AT2 deve orçar no prompt do executor.

---

## Prova — codex

### O GATE (achado central desta sonda)

Primeira tentativa, grammar ingênua (espelhando `CodexArgv` + `-c mcp_servers.toy.command=...`),
**FALHOU de forma limpa e caracterizável**:
```
{"type":"item.completed","item":{"id":"item_2","type":"mcp_tool_call","server":"toy","tool":"ping",
 "result":null,"error":{"message":"user cancelled MCP tool call"},"status":"failed"}}
```
Não é flag inexistente (a tool FOI descoberta e a chamada FOI tentada) nem timeout — é uma
**política de aprovação que auto-nega no headless** porque não há terminal pra responder o prompt.
`-c approval_policy="never"` e `--disable guardian_approval` **não resolveram** (testados e
descartados). Rastreei a causa no source do tag pinado (`rust-v0.142.5`,
`codex-rs/core/src/mcp_tool_call.rs` + `codex-rs/codex-mcp/src/mcp/mod.rs`):

- `requires_mcp_tool_approval(annotations)` (mcp_tool_call.rs:2151) retorna **`true` por padrão**
  a menos que a tool declare `annotations.readOnlyHint == true` — sem essa anotação,
  `destructive_hint.unwrap_or(true)` mantém o gate ligado.
- `mcp_permission_prompt_is_auto_approved(...)` (mcp/mod.rs:74) só pula o prompt se
  `tool_approval_mode == Approve` **OU** (`approval_policy == Never` **E** o `permission_profile`
  é `Disabled`/`External` — que é exatamente o que `--dangerously-bypass-approvals-and-sandbox`
  (postura EXTERNALLY_SANDBOXED/container) produziria; **não testei essa combinação** — o
  classificador de permissão do meu próprio ambiente bloqueou rodar essa flag no host por ser
  bypass total de sandbox, e a sonda não deveria precisar dela mesmo (D2 escopo F-AT2 = LOCAL/HOST,
  não container).
- Sem nenhuma das duas, no `exec` não-interativo a decisão vira `Cancel` — mensagem
  `"user cancelled MCP tool call"`, **run termina com exit 0** (não é crash, não é hang; o agente
  recebe o texto do cancelamento e segue — precedente "anti-silêncio" preservado).

### A correção — grammar que FUNCIONA (recomendada para o kickoff)
A tool do servidor precisa se anunciar como **read-only** no próprio `tools/list` — o que é
**verdade** para `knowall_search`/`knowall_fetch_body` (são leitura pura):
```js
// no server MCP do KnowAll, cada tool:
annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false }
```
Com isso, grammar do argv **sem nenhuma flag extra de aprovação**:
```
codex exec --json --skip-git-repo-check -s workspace-write \
  -c 'model="<model>"' \
  -c 'mcp_servers.<nome>.command="<cmd>"' \
  -c 'mcp_servers.<nome>.args=["<arg1>","<arg2>",...]' \
  "<prompt>" < /dev/null
```
**Alternativa** (se por algum motivo a tool não puder ser anotada): `-c
'mcp_servers.<nome>.default_tools_approval_mode="approve"'` força auto-aprovação independente de
anotação — mas a via da anotação é a certa aqui porque é **verdadeira** (a tool É read-only) e não
exige tocar `CodexArgv`/config global algum, só o schema do próprio servidor MCP.

### Prova — ping e echo_body, com a tool anotada
`codex-run6.jsonl`: `mcp_tool_call` item `status:"completed"`, `result.content[0].text:
"PONG_MARKER_7f3a9c"` → `agent_message` final ecoa o marcador. `codex-run7.jsonl`: idem com
`echo_body({text:"SKILL_BODY_XYZ42"})` → `BODY_MARKER_START[[SKILL_BODY_XYZ42]]BODY_MARKER_END`.
Ambos exit 0, sem cancelamento.

### Gotcha 2 — env NÃO é herdado pelo subprocesso MCP (achado incidental, boa notícia)
A variável `TOY_MCP_LOG` do shell-pai NÃO chegou ao servidor MCP filho nas provas com codex (ele
escreveu no path-default `/tmp/toy-mcp.log`, não no que eu exportei) — o codex spawna o
subprocesso MCP com ambiente próprio/limpo, não herdando o `env` do processo `exec`. Alinha
diretamente com o isolamento #3 do produto (`env = Map.of()`, `AdapterRunExecutor.java:207`): o
canal MCP-stdio não é um vetor de vazamento de credencial via env por padrão do próprio CLI.

### Gotcha 3 — pin de modelo do ambiente (nota, não achado de produto)
O `~/.codex/config.toml` pessoal do operador tinha `model = "gpt-5.6-sol"`, que o binário
0.142.5 instalado recusa (`"requires a newer version of Codex"`) — **isso é deriva do ambiente
pessoal do operador, não do produto** (que sempre passa `--model` explícito via `CodexArgv`
quando configurado). Contornado com `-c model="gpt-5.4-mini"` só para rodar a sonda; não afeta o
veredito de MCP.

---

## Assimetrias claude × codex (consolidado)

1. **Aprovação de tool MCP:** claude não tem gate — `bypassPermissions` cobre MCP igual a
   qualquer tool nativa, com ou sem anotação. codex tem gate PRÓPRIO e mais estrito — precisa de
   `readOnlyHint:true` (ou config `approve`) na tool, senão auto-cancela no headless. **Isso vira
   requisito de CONSTRUÇÃO do servidor MCP do KnowAll na F-AT2**, não um recuo — é fácil de
   satisfazer porque as tools são legitimamente read-only.
2. **Hop extra:** só claude insere `ToolSearch` antes da tool MCP (custo/latência a orçar no
   prompt do executor); codex chama direto.
3. **Isolamento de env do subprocesso MCP:** confirmado nos dois motores que o `--mcp-config`
   (claude) / `-c mcp_servers...` (codex) não precisa de credencial no ambiente pai — o mecanismo
   nativo já é compatível com o #3 (D2 do handoff: "sem credencial no `env`, sem rota REST").
4. **Fricção de configuração ad-hoc:** nos dois motores a config foi passada **inteiramente por
   flag/`-c`, sem persistir em `~/.claude.json`/`~/.codex/config.toml`** — replicável 1:1 pelo
   `ClaudeArgv`/`CodexArgv` como parâmetros adicionais no `ProcessBuilder`, sem escrita em disco
   fora do workspace do run.

---

## O que este laudo NÃO cobre (registrar, não resolver)

- **Postura DOCKER/EXTERNALLY_SANDBOXED** (v1.1, D7): não testei
  `--dangerously-bypass-approvals-and-sandbox` (bloqueado pelo classificador de permissão do meu
  próprio ambiente por ser bypass total de sandbox no host — correto ele ter bloqueado). Pela
  leitura do source, essa postura tornaria `permission_profile` `Disabled`/`External` e
  satisfaria `mcp_permission_prompt_is_auto_approved` mesmo sem `readOnlyHint` — mas isso é
  **inferência de código, não prova empírica**. Fora do escopo do v1 da F-AT2 (só LOCAL) mesmo
  assim.
- **Pino exato do claude 2.1.201** (só testei o host 2.1.206) — mesma ressalva já registrada e
  aceita no R6-B; as provas são de comportamento de CLI, não de release exato.
- **Servidor MCP real do KnowAll** — a sonda prova o CONSUMO com um servidor de brinquedo; não
  constrói `knowall_search`/`knowall_fetch_body` (isso é escopo da F-AT2).
- **Teto de corpos/run (D4)** — mecanismo de negócio da F-AT2 (cap+dedup+recusa-com-motivo), não
  testado aqui; o comportamento observado do codex ao CANCELAR uma chamada (turno segue, exit 0,
  mensagem visível) é um precedente favorável de que uma recusa do teto não precisa ser erro
  fatal do run, mas não é o mesmo mecanismo.

---

## Recomendação direta pro kickoff da F-AT2

1. **Anotar TODAS as tools do servidor MCP do KnowAll com `readOnlyHint: true`** (verdade
   objetiva — são leitura). Resolve o gate do codex sem tocar `CodexArgv` além do parâmetro de
   registro do servidor.
2. **claude:** adicionar `--mcp-config <config-do-run>` (+ `--strict-mcp-config` recomendado) ao
   `ClaudeArgv`, na mesma área do F-N1/F-N3 (confirmado pelo handoff).
3. **codex:** adicionar `-c mcp_servers.<nome>.command=...` / `.args=[...]` ao `CodexArgv`, mesma
   área. **Não** precisa de `default_tools_approval_mode` nem de flags de bypass de sandbox — a
   anotação na tool já resolve para a postura HOST/LOCAL do v1.
4. Orçar **+1 turno** (ToolSearch) no prompt/budget do executor pro caminho claude.

---

## Contrato desta faixa (cumprido)

Zero código de produto tocado (leitura só via `git show main:...`); zero commit, zero PR; nenhuma
instância do produto usada (portas 8080/18081/18082 intocadas); os 4 untracked de contrato
(`controle-paralelismo.md`, `laudo-lease-adr0005.md`, `handoff-auditoria-doc-codigo-2026-07-11.md`,
`handoff-grilling-agente-como-tool.md`) **intocados** — só o handoff foi LIDO. Este laudo nasce
**UNTRACKED** (a F-AT2 commita, precedente R6-B). Artefatos das provas (JSONLs + servidor de
brinquedo) em `scratchpad/mcp-probe/` da sessão. HEAD segue em `3fdda05` (main).
