# Laudo — Auditoria motor-nativo (R4-C, 2026-07-11)

> **O que é:** laudo READ-ONLY (precedente: laudo do lease, R1-C) sobre o quanto o orquestrador
> USA, SUPRIME ou REIMPLEMENTA as capacidades nativas dos dois motores (claude code e codex).
> Zero código, zero commit — nasce untracked; a sessão de controle decide o destino.
> **Este laudo GATEIA o desenho das fatias da Frente D** (interrupção mid-run/streaming · handoff
> pros executores · auto-kickback amplo · agente-como-tool · propose-then-commit de molde ·
> contabilidade off-book).

**Snapshot auditado:** main `713a3f7` (piso 523 backend / 270 front).
**Versões pinadas dos CLIs** (`EngineContainer.java:22-23`, travadas por `EngineContainerPinTest`
aos ARGs dos Dockerfiles em `infra/engines/*/Dockerfile`):

| Motor | Imagem | CLI pinado |
|---|---|---|
| claude code | `orq-claude-code:2.1.201` | `@anthropic-ai/claude-code@2.1.201` |
| codex | `orq-codex:0.142.5` | `@openai/codex@0.142.5` |

**Norte do dono (VERBATIM, 2026-07-11):** *"Tanto o cloude code quanto o codex GPT 5.5 possui
otimas ferramentas para controle de arquivo e sessao, e nao parece que estamos usando o que esses
agentes nos proporcionam nativamente, pelo contrario sinto que estamos regredindo a qualidade
deles. Lembre-se um agente especializado nada mais é do que um agente que tem o contexto daquele
ponto, ou produto e possui skills que lidam com aquilo, nao deveria ser tao dificil."*

**Insumo reaproveitado:** a auditoria doc×código de 2026-07-11 (63 auditores + 2 céticos por
achado, snapshot `2ab8eac`) já havia confirmado o padrão central com file:line — este laudo NÃO
redescobriu; verificou as âncoras no main atual e rodou a passada suplementar do diff
`2ab8eac..713a3f7` (Apêndice A).

---

## §0 Sumário executivo

O sentimento do dono está **confirmado no código, e é DESIGN, não descuido**. O padrão em uma
frase: **os dois CLIs são tratados como funções puras `prompt → texto`, re-executadas do zero a
cada turno**, e todo o resto — memória entre turnos, continuidade de contexto, detecção de
entregável, roteamento entre personas, limiar de janela — foi **reimplementado por fora**, em
maquinário próprio (prompt recomposto + arquivos `.orq/*` + regex sobre a saída + contadores de
token no ledger). O maquinário funciona (as provas VENDO passam), mas:

1. **A continuidade nativa de sessão NUNCA é usada para trabalho** — o handle é capturado e
   persistido a cada turno, e lido por UM único consumidor (o resume-cirúrgico de destilação).
2. **A regressão de qualidade percebida tem mecanismo identificável**: o modelo perde o próprio
   raciocínio a cada turno e só herda o que o maquinário externo destilou (estado vivo capado +
   dossiê que ele mesmo tem que reler). Sintomas observados ao vivo estão catalogados em §2.
3. Onde o produto RESPEITOU a convenção nativa (ADR-0032: carta/skills no formato de cada motor;
   stream-json/--json como transporte), a qualidade subiu na hora — o contra-exemplo positivo
   prova a tese do dono por inversão.
4. Bônus da auditoria: **um bug latente encontrado** — a gramática de resume que o produto emite
   pro codex é inválida na versão pinada (R6, §2), e o fail-open a esconde. E as capacidades
   nativas andaram na direção do produto: subagentes e hooks do codex hoje são ESTÁVEIS e
   ligados por default; o claude tem saída validada por schema e compactação automática até no
   headless — o custo de reimplementar só cresce daqui pra frente.

Recomendação de fundo (detalhe em §4): **ligar a continuidade nativa por conversa** (o plumbing
já existe dos dois lados do seam) e **migrar os contratos de bloco-por-regex para saída
estruturada nativa** — as duas mudanças juntas desmontam a maior parte do maquinário wrapper que
a Frente D iria empilhar ainda mais alto.

---

## §1 Inventário — capacidade nativa × uso atual

Veredito por linha: **USA** (aproveita o nativo) · **SUPRIME** (desliga/ignora deliberadamente) ·
**REIMPLEMENTA** (constrói por fora o que o motor dá). Evidência sempre no main `713a3f7`.

### 1.1 Sessões / continuidade de contexto

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | `--resume <session-id>` restaura a conversa COMPLETA (funciona com `-p`); `--continue` (mais recente do cwd), `--fork-session` (ramifica sem tocar a original), `--session-id <uuid>` (o orquestrador dita o id). Sessão = JSONL gravado continuamente em `~/.claude/projects/<cwd-codificado>/<id>.jsonl`; lookup do resume é ESCOPADO ao diretório do projeto [C-1] | **subcomando** `codex exec resume <SESSION_ID> "<prompt>"` (ou `resume --last`) retoma preservando o contexto do thread; sessões = JSONL em `~/.codex/sessions/YYYY/MM/DD/`; `--ephemeral` para run sem persistir [X-1] |
| **Uso atual** | **SUPRIME (deliberado) + REIMPLEMENTA.** Todo turno roda sessão NOVA: `AdapterRunExecutor.java:252-255` passa `priorSession=null` literal no caminho normal; `ClaudeArgv.java:27-30,45-50` e `CodexArgv.java:32-34,43-48` só emitem `--resume` quando preenchido — no caminho normal, nunca vem. | idem (mesmo executor, mesmo seam) |

- O handle nativo É capturado e persistido a cada turno: `AgentSessionStore.record`
  (`AgentSessionStore.java:24-34`, tabela `agent_session`, V16, chave `(agent_id,
  conversation_id)`), com "o handle mais novo VENCE (o CLI rotaciona o session-id a cada uso)"
  (`AgentSession.java:61`). **A plumbing existe e funciona nas DUAS pontas** — o `Context` do
  adapter já tem o campo `priorSession` (`AgentAdapter.java:58-60`).
- **Único consumidor de produção:** o resume-cirúrgico do bilhete de handoff
  (`ContextHandoffLoop.java:83-106`, ADR-0030) — religa a MESMA sessão SÓ para gravar o bilhete
  quando o self-distill falhou, descarta o rastro e volta a `priorSession=null` no turno seguinte.
  Leitor único: `AgentSessionStore.currentHandle` (`AgentSessionStore.java:41-46`).
- Em vez de continuar a sessão, o produto **reconstrói o contexto a cada turno**: prompt
  recomposto com estado vivo + instruções de persona (`ConversationTurnCoordinator.composePrompt`,
  `ConversationTurnCoordinator.java:156-180` — blocos de ~40 linhas re-enviados TODO turno),
  carta reescrita (`CompanyMemoryMaterializer`), skills rematerializadas (`SkillMaterializer`,
  idempotente, "reescreve a pasta inteira a cada Run").
- Vários ADRs marcam o `--resume` desligado como decisão deliberada ("fatia futura", "religar é
  barato depois" — ADR-0002/0018; D4 do grill do handoff). É design consciente — e é exatamente
  o que o dono sente como regressão.
- **Nuances de infra que JÁ jogam a favor de religar:** (a) no Docker, o JSONL de sessão vive no
  home-volume compartilhado do motor (`orq_claude_creds` montado em `/home/node`,
  `EngineContainer.java:25-33`) — sobrevive entre runs, é por isso que o resume-cirúrgico
  funciona hoje; (b) o cwd é sempre `/workspace`, então TODAS as sessões caem no mesmo diretório
  de projeto — resume por ID explícito funciona (é o que o produto grava), mas `--continue`
  seria perigoso (pegaria a sessão "mais recente" de QUALQUER agente); (c) sessões `-p` são
  retomáveis por ID mesmo sem aparecer no picker interativo [C-1]; (d) **observação de
  segurança que já vale HOJE** (independe de religar): os JSONLs de sessão de TODAS as
  companies/agentes do motor ficam no MESMO home-volume, e o modelo tem Bash — um run pode ler
  `~/.claude/projects/` e ver transcripts de outros tenants. Não é regressão desta auditoria
  (é consequência do home-como-volume, ADR-0016), mas o desenho de qualquer fatia de sessão
  deve olhá-la (ex.: `CLAUDE_CONFIG_DIR` por-company dentro do volume, ou retention agressiva).
- **ACHADO NOVO (lado codex): a sintaxe do resume que o produto emite é INVÁLIDA na 0.142.5.**
  `CodexArgv.java:45-48` monta `codex exec --json --resume <handle> …` (flag), mas a gramática
  real do CLI pinado é o SUBCOMANDO `codex exec resume <SESSION_ID> "<prompt>"` — verificado no
  source do tag `rust-v0.142.5` (`codex-rs/exec/src/cli.rs`) [X-1]. Consequência: o único
  consumidor do resume (a rede resume-cirúrgico do handoff) deve FALHAR em CEO codex — e falha
  em SILÊNCIO, porque o loop é fail-open (`ContextHandoffLoop.java:102-105` só loga WARN). O
  próprio código admite: *"a sintaxe exata do resume no codex é best-effort no v1"*
  (`CodexArgv.java:43-44`). Não quebra nada do caminho normal (que nunca resume), mas é a prova
  de que o caminho nativo nunca foi exercitado no codex.

### 1.2 Compactação de contexto / janela

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | **compactação automática é nativa INCLUSIVE no headless `-p`** (parte do loop compartilhado do agente: limpa outputs de tool antigos, depois sumariza; dispara no limite do modelo, dial `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` 1–100 — vale para conversa principal E subagentes; evento `compact_boundary` visível no transcript; hooks `PreCompact`/`PostCompact`) [C-2] | auto-compactação no core, vale no `exec` (frontend-agnóstico); dial `model_auto_compact_token_limit`; hooks `PreCompact`/`PostCompact` [X-2] |
| **Uso atual** | **REIMPLEMENTA** (e só para o CEO). | idem |

- O produto mede a janela POR FORA: `HandoffThresholdDetector` soma os tokens de entrada dos
  `CostEmission` do run e divide pela `context_window` do `engine_catalog`
  (`HandoffThresholdDetector.java:34-37`, limiar default 55%). FAIL-OPEN: engine sem janela
  catalogada ⟹ gatilho desligado em silêncio (WARN no log).
- Cruzou → skill sintética `distillation` injetada por-run (`DistillationSkill.java:11-20`)
  instrui o modelo a escrever o bilhete `.orq/handoff/<conv>.md` (`HandoffNote.java:21-23`);
  rede resume-cirúrgico se ele não escreveu; continuação fresca via `ContinuationTurnPoster`.
  É o pipeline inteiro do ADR-0030 (6 classes) reimplementando o que o motor faz sozinho quando
  a sessão é contínua.
- **Nota a favor do wrapper:** o bilhete-formato-`/nova-sessao` é MAIS controlável que a
  compactação nativa (formato auditável, sobrevive no workspace). O ponto não é "o wrapper é
  pior" — é que ele só é NECESSÁRIO porque a sessão nunca continua (D1 do detector diz isso
  textualmente: "`--resume` desligado ⟹ a janela só enche por-invocação"). Com sessão contínua,
  a compactação nativa cuida da SOBREVIVÊNCIA (dial `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` permite
  dispará-la ao nível da regra de ouro dos 50-60% [C-2]) e o bilhete vira o que sempre foi de
  melhor nele: REGISTRO durável e auditável, não o único fio de vida.
- Lado codex: a auto-compactação também é NATIVA e vale no `exec` (implementada no core,
  frontend-agnóstico; dial `model_auto_compact_token_limit`; hooks `PreCompact`/`PostCompact`)
  — verificado no source do tag (`core/src/state/auto_compact_window.rs`) [X-2].

### 1.3 Subagentes / Task

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | tool `Agent` (ex-`Task`, renomeada na 2.1.63; regras `Task(...)` seguem valendo como alias) + agentes custom em `.claude/agents/*.md` (frontmatter rico: tools, model, permissionMode, maxTurns, skills pré-carregadas, mcpServers, hooks, memória persistente por agente) + `--agents '<json>'` por sessão; **`-p` spawna subagentes nativamente** (espera os de background, teto 10min); allowlist/deny por `Agent(nome)` nas permission rules [C-3] | **multi-agente nativo ESTÁVEL e LIGADO por default na 0.142.5** (feature `multi_agent`): tools spawn/wait/send_input/resume_agent/close_agent; roles custom em TOML (`.codex/agents/`, `~/.codex/agents/`); **tetos NATIVOS por config: `[agents] max_threads` (default 6), `max_depth`, `job_max_runtime_seconds`**; as chamadas aparecem como itens `collab_tool_call` no próprio stream `--json` [X-3] |
| **Uso atual** | **USA (via prompt), mas às cegas** — permitido sem observabilidade nem contabilidade de produto. | idem |

- A skill `delegation` manda o CEO usar os subagentes nativos como "mãos"
  (`docs/skills-ceo/delegation/SKILL.md:11,16,24` — "sub-agentes internos do seu CLI
  (Task/collab)"; teto MAX 10 de profundidade **só por prompt**).
- O prompt de conversa PROÍBE subagente para pergunta trivial
  (`ConversationTurnCoordinator.java:187-188`) — enforcement por prosa, não por
  `--disallowedTools`/permission rules nativos.
- O produto não vê nem contabiliza o que os subagentes gastam (a "contabilidade off-book" é
  exatamente a fatia ADIADA da Frente D). Pergunta aberta: o `usage` do result do stream já
  INCLUI os subagentes? (ver §4-G5 — muda o tamanho da fatia).
- **Dois tetos que hoje são prosa têm mecanismo nativo pronto:** no codex, `[agents]
  max_threads`/`max_depth` via `-c` [X-3]; no claude, deny rules `Agent(nome)` /
  `--disallowedTools` [C-3][C-7]. E no codex a OBSERVABILIDADE é de graça: o
  `CodexStreamParser` já consome o stream onde os `collab_tool_call` chegam — só não os trata.

### 1.4 Skills + carta (CLAUDE.md / AGENTS.md)

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | `.claude/skills/` (descrições no início da sessão, corpo sob demanda; funcionam em `-p` via `/skill-name` no prompt) + `CLAUDE.md` em camadas (managed → `~/.claude/` → projeto, com imports `@path` e `.claude/rules/*.md`); AGENTS.md NÃO é lido nativamente pelo claude. `--bare` pula TODA a descoberta (skills/hooks/MCP/CLAUDE.md) e a doc avisa: "vai virar o default do `-p` em release futura" — **valida a defesa do pino** (`EngineContainer.java:8-10`) [C-4] | `AGENTS.md` em cadeia (global `~/.codex/AGENTS.md` → raiz-do-projeto→cwd, um por diretório, cap `project_doc_max_bytes` default 32KiB) + skills em `SKILL.md` (roots na 0.142.5: `.codex/skills`, `~/.codex/skills`, `/etc/codex/skills` E `.agents/skills`/`~/.agents/skills` — a doc atual enfatiza `.agents/`); lista de skills injetada a ≤2% da janela, corpo sob demanda [X-4] |
| **Uso atual** | **USA — o contra-exemplo positivo.** | **USA** (pós-ADR-0032) |

- `WorkspaceConvention.java:27-33`: cada adapter declara o layout nativo do SEU motor
  (`.claude/CLAUDE.md`+`.claude/skills` × `AGENTS.md`+`.codex/skills`); materializers escrevem
  no lugar certo; faxina permanente apaga a convenção inativa
  (`AdapterRunExecutor.java:158-162,200-204`). Provado ao vivo (VENDO G3 5/5, PR #4).
- A história recente prova a tese do dono **por inversão**: antes da ADR-0032 o codex rodava
  "pelado" (tudo materializava em `.claude/` que ele não lê — sintoma G3: *"A skill distillation
  não está disponível nesta sessão"*, `docs/handoff-grilling-insatisfacoes-funcionamento-ceo.md:35`).
  Respeitar a convenção nativa = salto de qualidade imediato.
- Resíduo REIMPLEMENTA: a rematerialização é POR RUN (idempotente, reescreve tudo) — correta
  enquanto cada run é efêmero; se a sessão continuar (§4-G1), skills que não mudaram não
  precisariam ser re-lidas pelo motor a cada turno.

### 1.5 Hooks

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | ~30 eventos de ciclo de vida em settings.json (PreToolUse/PostToolUse/Stop/SubagentStart/SubagentStop/PreCompact/SessionStart…); handler `http` POSTa cada evento num endpoint do ORQUESTRADOR; `PreToolUse` pode NEGAR/reescrever a tool call; `--include-hook-events` põe os eventos de hook no próprio stream-json [C-5] | **hooks ESTÁVEIS e ligados por default na 0.142.5** (`hooks.json` em `~/.codex/` ou `.codex/`, eventos SessionStart/PreToolUse/PermissionRequest/PostToolUse/PreCompact/PostCompact/SubagentStart/SubagentStop/Stop; só handler `command`; exit 2 = bloqueia; gotcha de automação: hooks não-managed exigem trust — flag `--dangerously-bypass-hook-trust`) + `notify` legado no config.toml [X-5] |
| **Uso atual** | **SUPRIME (não usa; nunca avaliado em ADR).** | idem |

- Zero menção a hooks no backend inteiro (grep em `backend/src/main/java`: nada). Oportunidades
  concretas: enforcement REAL do "skills read-only por persona" (hoje só prosa no prompt),
  observabilidade por-tool-call no StepTrace, gatilho determinístico de "entregável gravado"
  (hoje: regex na resposta).

### 1.6 MCP

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | `--mcp-config <arquivo-ou-JSON-inline>` injeta servers por-run; `--strict-mcp-config` ignora todo o resto (injeção determinística de orquestrador); `mcpServers` por-subagente no frontmatter; MCP server pode ser o broker de permissão (`--permission-prompt-tool`) [C-6] | client via `[mcp_servers.<id>]` no config.toml (stdio/HTTP, allow/deny por tool) e **server via `codex mcp-server`: expõe as tools `codex` (inicia sessão) e `codex-reply` (CONTINUA por threadId)** — um agente codex consumível como tool, com continuidade, pronto [X-6] |
| **Uso atual** | **SUPRIME (não usa).** | idem |

- Estado vivo, KnowAll e board chegam ao modelo por prompt-stuffing (`LiveStateProjector` →
  `composePrompt`) em vez de tools consultáveis. Relevância direta para agente-como-tool (§3.4).

### 1.7 Permission modes / allowedTools

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | `--permission-mode` (default/acceptEdits/plan/auto/dontAsk/bypassPermissions), `--allowedTools`/`--disallowedTools` com regras finas (`Bash(git commit *)`, paths gitignore-style, `Agent(nome)`), e **`--permission-prompt-tool <mcp_tool>`: uma tool MCP decide allow/deny de cada prompt de permissão em modo não-interativo** [C-7] | `-s` (read-only/workspace-write/danger-full-access), `-a` approval policy (**no `exec` o default é FORÇADO a `never`: aprovação que não pode aparecer FALHA, não trava** — verificado no source), `--dangerously-bypass-approvals-and-sandbox` (alias `--yolo`), tuning `[sandbox_workspace_write]` (writable_roots, network_access) [X-7] |
| **Uso atual** | **USA (grosso, coerente): o container é a fronteira.** | **USA (fino e correto por postura).** |

- claude: `--permission-mode bypassPermissions` fixo (`ClaudeArgv.java:43-44`) — headless não
  tem quem aprovar; a fronteira de segurança é o container (ADR-0016/0023). Racional sólido.
- codex: a postura do alvo vira gramática (`CodexArgv.java:57-63`):
  `EXTERNALLY_SANDBOXED → --dangerously-bypass-approvals-and-sandbox` (o container É o sandbox);
  `HOST → -s workspace-write` (bypass no host = exec arbitrário, recusado). **Este é o uso
  nativo BEM feito.**
- Lacuna fina: granularidade por-tool (`--allowedTools`) nunca usada — todo enforcement
  comportamental fino é prosa no prompt.

### 1.8 Output formats / streaming / saída estruturada

| | claude code 2.1.201 | codex 0.142.5 |
|---|---|---|
| **Nativo** | `--output-format text\|json\|stream-json` (json traz result+session_id+usage+custo por modelo); **`--json-schema '<schema>'` = saída final VALIDADA por JSON Schema (campo `structured_output`) — presente na 2.1.201**; deltas token-a-token via `--include-partial-messages`; **`--input-format stream-json` = processo VIVO multi-turno via stdin** (o transporte por baixo do Agent SDK), com `interrupt()`/`setModel()`/steer mid-run [C-8] | `exec --json` (JSONL; itens verificados no tag: `agent_message`, `reasoning`, `command_execution`, `file_change`, `mcp_tool_call`, `collab_tool_call`, `web_search`, `todo_list`); **`--output-schema <arquivo>` = mensagem final PRESA a um JSON Schema (funciona também com `exec resume`)**; `-o/--output-last-message` [X-8] |
| **Uso atual** | **USA o stream para ler; SUPRIME o stream para o vivo; REIMPLEMENTA a estrutura.** | idem |

- USA: `ClaudeArgv.java:37-39` (`stream-json --verbose`) e `CodexArgv.java:42` (`--json`);
  parsers dedicados extraem session/model/usage/steps (`ClaudeStreamParser.java:12-17`,
  `CodexStreamParser.java:11-19`; o fix last-wins do `agent_message` já provado ao vivo).
- SUPRIME o vivo: os steps morrem no banco e a UI faz REST-poll incremental
  (`RunStepsController.java:26-30`, `GET /runs/{id}/steps?offset=`) — o stream que o CLI entrega
  em tempo real vira polling. (É a fatia "interrupção mid-run/streaming" da Frente D.)
- REIMPLEMENTA a estrutura: TODOS os contratos tipados do produto são blocos cercados parseados
  por regex sobre prosa livre — ```proposta (`ProposalScanner.java:40-41`), ```handoff e
  ```needs-grilling (`PersonaHandoffScanner.java:32-35`), ```correcao (ADR-0034), ```absorcao
  (ADR-0037). O custo já apareceu ao vivo: **caso C** nasceu do Questionador emitindo proposta
  com shape inventado → gate descartou em silêncio (ADR-0036); a resposta foi MAIS maquinário
  anti-silêncio (`ProposalDescarteNotice`, cintos de emissor) em vez de schema nativo.

### 1.9 Ferramentas de arquivo / workspace

| | ambos os motores |
|---|---|
| **Nativo** | Read/Write/Edit/Glob/Grep (claude); ferramentas de arquivo do codex — os dois operam o workspace direto |
| **Uso atual** | **USA bem.** O chão `.orq/*` é protocolo do produto ESCRITO pelas mãos nativas do modelo: dossiê/estratégia (`ConversationBaton.java:20-23`), bilhete (`HandoffNote.java:21-23`); o backend só lê (`ExecutionTarget.readWorkspaceFile`, `ContextHandoffLoop.java:69-75`). Guard-rail: `.orq/*` fora do replace de materialização (`AdapterRunExecutor.java:225-231`). |

- Ressalva: o bastão-por-arquivo é, na prática, a **memória entre turnos reimplementada** — o
  prompt do Questionador diz textualmente *"O dossiê é a sua memória entre turnos: releia-o
  antes de perguntar"* (`ConversationTurnCoordinator.java:202-204`). Com sessão contínua, o
  dossiê continuaria valendo como REGISTRO durável (auditável, cross-agente), mas deixaria de
  ser a única memória.

### 1.10 Extras nativos que o produto não conhece (registro, sem veredito)

- **Supervisor de sessões em background do próprio claude**: `claude --bg`, `claude agents`
  (painel de despacho/monitor), `claude attach/logs/stop/respawn <id>` — um mini-orquestrador
  in-box [C-9]. Não substitui o produto (sem governança/board/multi-tenant), mas mostra a
  direção do motor.
- **Memória automática nativa** (`~/.claude/projects/<proj>/memory/` com índice `MEMORY.md`
  carregado a cada sessão) e **memória persistente por-subagente** (`memory:` no frontmatter)
  [C-3][C-4] — paralelo direto do par carta/segundo-cérebro do produto, hoje 100% reimplementado
  via `CompanyMemoryMaterializer`.
- `--max-turns`, `--max-budget-usd` — tetos nativos por run que hoje o produto só tem via prompt
  (MAX 30/10) ou ledger pós-hoc [C-8].
- Lado codex: `codex app-server` (JSON-RPC: threads, steer, interrupt, approvals servidas ao
  ORQUESTRADOR — `item/commandExecution/requestApproval` → accept/decline) [X-9]; export OTEL
  [X-10]; `web_search` nativo; `codex apply` (aplica o diff do agente como `git apply`);
  `--ephemeral` (turno sem rastro de sessão); `CODEX_API_KEY` honrado só pelo `exec` [X-1][X-8].

---

## §2 Regressões priorizadas (o "regredindo" do dono, com sintoma)

**R1 — Amnésia estrutural por turno (a regressão-mãe).** Toda a inteligência acumulada num turno
(raciocínio, nuance do que o dono disse, hipóteses descartadas) morre no fim do run; o turno
seguinte herda só o que o maquinário destilou (estado vivo CAPADO top-N + dossiê que o modelo
tem que reler + carta). Sintomas observados: o modelo CHUTA estado que não vê (caso B ao vivo:
*"o opus chutou 'encaminho pro Feynman' enquanto o registro roteou, correto, à arquiteta"* —
comentário em `ConversationTurnCoordinator.java:215-217`); o protocolo do dossiê existe COMO
COMPENSAÇÃO explícita. Evidência: `AdapterRunExecutor.java:252-255` (+§1.1).
**Custo composto:** re-enviar estado vivo + blocos de persona todo turno paga tokens de entrada
crescentes — o G4 mediu o turno trivial em ~8,5s/9 eventos DEPOIS do hotfix; a fatura de
recomposição segue em todo turno.

**R2 — Contratos por regex onde o motor oferece schema.** Cinco tipos de bloco cercado parseados
de prosa (§1.8). Sintoma vivo: caso C (shape inventado → gate vazio em silêncio, ADR-0036);
cascata de maquinário-remendo (cinto de emissor, aviso de descarte, kickback de recusa — PRs
#12–#14 são majoritariamente isso, ver Apêndice A). Cada bloco novo repete o ciclo:
regex → descarte silencioso → anti-silêncio.

**R3 — Compactação reimplementada e restrita ao CEO.** O pipeline ADR-0030 (detector externo +
skill sintética + bilhete + rede + continuação) reconstrói o que a sessão contínua daria; e SÓ o
CEO tem — um executor de task longa que encher a janela não tem NADA (nem handoff, nem
compactação, porque a sessão dele também nunca continua e ninguém mede a janela dele). Sintoma
esperado: executor perde o fio em task longa — é a fatia "handoff pros executores" da Frente D,
que pode ser SUPERSEDIDA (§3.2). Evidência: `HandoffThresholdDetector.java` (só invocado pelo
loop do CEO via `ContextHandoffLoop`), fail-open silencioso sem `context_window` catalogada.

**R4 — Stream vivo degradado a poll.** O CLI entrega evento-a-evento em tempo real; o produto
persiste e a UI consulta por offset (`RunStepsController.java:26-30`). O dono assiste o board
com atraso de poll e não existe canal para interromper/steerar mid-run. Evidência: §1.8.

**R5 — Subagentes nativos às cegas (qualidade E contabilidade).** O CEO delega às "mãos" nativas
por instrução de skill, mas o produto não observa profundidade, não contabiliza gasto (off-book,
Frente D) e o teto MAX 10 é prosa. Se o usage do stream já incluir subagentes, metade da fatia
off-book já está resolvida de graça (§4-G5). Evidência: §1.3.

**R6 — Resume do codex com gramática inválida (bug latente, silencioso).** O único caminho que
usa sessão nativa no codex emite `--resume` como flag; a 0.142.5 só aceita o subcomando
`codex exec resume <id>` — a rede resume-cirúrgico deve falhar em CEO codex e o fail-open
engole (WARN no log, sem continuação). Evidência: `CodexArgv.java:43-48` vs source do tag
[X-1]; detalhe em §1.1. Barato de corrigir e vira pré-requisito do G1/G3 no codex.

*(Menor, registrado sem rank: enforcement fino por prosa em vez de `--allowedTools`/hooks — §1.5/§1.7.)*

---

## §3 Implicações por fatia da Frente D

**3.1 Interrupção mid-run + streaming.** O motor muda o desenho três vezes: (a) o stream JÁ É
vivo — a fatia é transportá-lo (SSE/WebSocket do backend), não criá-lo; (b) a sessão do claude é
gravada CONTINUAMENTE em JSONL [C-1], então **interromper = matar o processo + `--resume` para
retomar** — um kill não perde o trabalho da sessão; (c) para steering fino sem kill, o claude
tem o modo `--input-format stream-json` (processo vivo, `interrupt()` e mensagens novas via
stdin — é o transporte do Agent SDK) [C-8]. Desenhar interrupção sem isso levaria a um sistema
de checkpoint próprio (mais wrapper). O desenho da fatia deveria ESCOLHER entre kill+resume
(barato, cabe no executor atual de processo-por-run) e processo-vivo (muda o `ExecutionTarget`,
ganha steer). No codex, os equivalentes são: sessões JSONL persistentes + `exec resume` para
kill+resume, e **`codex app-server` (JSON-RPC bidirecional com `turn/steer` e `turn/interrupt`,
experimental na 0.142.5)** para o processo-vivo [X-9].

**3.2 Handoff pros executores.** Candidata a **SUPERSEDIDA por `--resume` nativo**: executor de
task longa continua a PRÓPRIA sessão entre heartbeats/runs em vez de ganhar o pipeline de bilhete
do CEO. O bilhete continua valendo como REGISTRO durável de handoff entre AGENTES diferentes
(cross-engine, auditável) — mas dentro do MESMO agente/task, sessão contínua resolve sem classe
nova. Decisão de desenho vira §4-G3.

**3.3 Auto-kickback amplo.** Depende de detecção de entregável confiável. Com saída estruturada
nativa (§1.8), o "entregável detectado" vira campo validado por schema em vez de regex — o
kickback amplo fica trivial e deixa de precisar de cinto/anti-silêncio por tipo de bloco.
Sequência correta: G2 (§4) ANTES desta fatia.

**3.4 Agente-como-tool.** O motor dá dois caminhos prontos: subagentes nativos
(`.claude/agents/*.md` no claude; roles TOML em `.codex/agents/` no codex — um "agente
especializado" É um arquivo de contexto+skills, exatamente a frase do norte do dono) e MCP.
No codex o MCP já vem MONTADO: `codex mcp-server` expõe `codex`/`codex-reply` (continua por
threadId) — um agente-como-tool com continuidade, de fábrica [X-6]. Ambos evitam construir um
protocolo próprio de attach. A fatia deveria começar escolhendo entre esses dois, não
desenhando transporte novo.

**3.5 Propose-then-commit de molde.** Mesmo insumo do 3.3: proposta de molde como saída
estruturada validada. Nada de novo além do G2.

**3.6 Contabilidade off-book.** Se o usage nativo do result já agrega subagentes (verificação
G5), a fatia encolhe para "rotular o que já chega". Senão, o motor dá DOIS canais prontos de
captura: hooks `SubagentStart`/`SubagentStop`/`PostToolUse` com handler `http` POSTando no
backend [C-5], ou `--include-hook-events` no próprio stream-json que o parser já consome [C-8]
— nenhum dos dois exige proxy de rede próprio. No codex, além dos `collab_tool_call` que já
chegam no `--json` [X-3], existe **export OTEL nativo** (`[otel]
exporter=otlp-http|otlp-grpc`) — telemetria de run direto para um collector do produto [X-10].

---

## §4 Perguntas de grill preparadas (decisão real; recomendação + porquê)

**G1 — Ligamos a continuidade nativa de sessão nos turnos de conversa (CEO/personas)?**
Exemplo concreto: hoje, se o dono manda 3 mensagens seguidas no chat, cada uma paga o estado
vivo inteiro de novo e o modelo relê o dossiê para "lembrar" da mensagem anterior; com
`--resume` por conversa, a 2ª e a 3ª chegam numa sessão que JÁ lembra.
**Recomendação: SIM, por conversa, mantendo o loop de handoff como rede.** O plumbing já existe
inteiro (`agent_session` + `priorSession` no Context + argv pronto dos dois lados); a mudança de
código é ler `currentHandle` no `AdapterRunExecutor.java:252-255`. O loop ADR-0030 vira o que
sempre quis ser: a rede de QUANDO a janela enche (destila e recomeça), não o substituto da
memória. **Condição:** emagrecer o `composePrompt` junto (mandar só a mensagem nova + delta do
estado vivo), senão pagamos contexto em dobro. Pré-requisito no codex: corrigir a gramática do
resume (R6 — flag → subcomando). Risco a grelhar: turno com sessão continuada que falha no meio
— o retry reusa o handle? (o CLI rotaciona o id a cada uso, `AgentSession.java:61`).

**G2 — Migramos os 5 blocos tipados para saída estruturada nativa?**
Exemplo concreto: o caso C só existiu porque o Questionador inventou um shape de ```proposta e o
gate descartou em silêncio — com schema nativo, o shape errado é IMPOSSÍVEL de emitir (o CLI
valida na saída).
**Recomendação: SIM nos dois motores — as flags JÁ EXISTEM nas versões pinadas** (codex:
`--output-schema <arquivo>`; claude: `--json-schema '<schema>'` com `--output-format json`,
presente na 2.1.201 [C-8]). Ressalva de desenho: schema-de-saída valida o turno INTEIRO — serve
direto para turnos de entregável (proposta/handoff/absorção); para prosa+bloco misturados, a
alternativa é campo `resposta` + campo `blocos[]` no mesmo schema. Porquê: cada bloco novo hoje
custa regex + cinto de emissor + aviso de descarte + teste disso tudo (PRs #12–#14 no Apêndice A
são o preço medido).

**G3 — Executor de task longa: `--resume` nativo por heartbeat em vez de estender o bilhete do
CEO (fatia "handoff pros executores")?**
Exemplo concreto: um executor no meio de uma task de 2 dias hoje recomeça DO ZERO a cada run e
re-deriva tudo do workspace; com resume, o run N+1 continua o raciocínio do run N.
**Recomendação: SIM — supersede a fatia da Frente D como planejada.** O bilhete fica para
handoff entre agentes DIFERENTES. É a mesma mudança de plumbing do G1 aplicada a run de Issue.

**G4 — Interrupção mid-run: desenhamos sobre kill+resume nativo?**
Exemplo concreto: o dono vê o CEO indo na direção errada e aperta "parar" — hoje não existe;
com kill+resume, parar é matar o processo (a sessão nativa sobrevive em disco) e retomar com a
correção do dono no prompt.
**Recomendação: SIM** — evita construir checkpoint próprio. Pré-requisito: G1 (sem sessão
contínua, kill perde o turno inteiro).

**G5 — Contabilidade off-book: antes de desenhar a fatia, MEDIMOS se o usage do stream já inclui
os subagentes?**
Exemplo concreto: um turno do CEO que spawna 5 Tasks — o `usage` do result reporta os tokens das
5 ou só do loop principal? A resposta decide se a fatia é "rotular" ou "capturar".
**Recomendação: medir primeiro (uma sonda de 1 run, tipo a S2 do G3)** — barato e muda o
tamanho da fatia inteira.

**G6 — Enforcement fino (skills read-only por persona, teto de subagente) migra de prosa para
mecanismo nativo (`--disallowedTools`/hooks)?**
Exemplo concreto: hoje "não use subagente para pergunta trivial" é um pedido educado no prompt;
um hook PreToolUse pode simplesmente NEGAR o Task nesse contexto.
**Recomendação: SIM mas SEM PRESSA (prioridade abaixo de G1–G3)** — é robustez, não é a dor
atual do dono.

---

## §5 O que ficou fora do laudo e por quê

- **Medições de latência/custo por turno em produção** — o :8080 está fora do ar (regra da
  faixa); os números citados (G4: ~8,5s/9 eventos) vêm da re-medição registrada no grill.
- **Prova empírica das capacidades nativas nas versões pinadas** (ex.: usage do result inclui
  subagentes? resume-pós-kill no meio de uma tool call?) — exigiria rodar os CLIs (fora do
  read-only desta faixa). Mitigação: lado codex foi verificado no SOURCE do tag exato
  (`rust-v0.142.5`); lado claude, a doc oficial carrega marcadores `min-version` por feature e
  a nota de versão do Apêndice B lista os únicos pós-2.1.201 relevantes. As duas sondas que
  restam viraram o G5 do grill.
- **Front-end** — o consumo por poll está anotado (R4), mas o desenho de SSE/WebSocket é da
  fatia de streaming, não deste laudo.
- **Motores futuros (Gemini etc.)** — fora do escopo do norte (claude + codex).

---

## Apêndice A — Passada suplementar do diff `2ab8eac..713a3f7` (PRs #11–#14)

A auditoria de 63 docs parou no snapshot `2ab8eac`; este laudo cobriu o delta:

- **Camada motor INTOCADA:** zero mudança em `execution/` (argv, adapters, parsers, session
  store) e `handoff/` — o padrão confirmado pela auditoria segue válido no main atual.
- **O delta REFORÇA o diagnóstico R2:** PR #12 (lote ADR-0035) adicionou kickback de recusa
  (`PersonaContinuationPoster.postRecusaDoGate`); PR #13 (caso C ADR-0036) adicionou cinto de
  emissor + `ProposalDescarteNotice` (anti-silêncio de bloco descartado); PR #14 (absorção
  ADR-0037) adicionou o 5º bloco regex (```absorcao```, restrito ao Planejador em
  `PersonaBatonRouter`). Três PRs seguidos empilhando maquinário sobre o contrato bloco-por-regex.
- **PR #11 (skills-ceo/)**: docs-only; a skill `delegation` documenta o uso das "mãos" nativas
  (insumo do §1.3), `being-ceo` os dois modos de grill.

## Apêndice B — Fontes das capacidades nativas

**Nota de versão (claude code):** o changelog mais recente é 2.1.207; a doc descreve a última e
carrega marcadores `min-version` por feature. TUDO que este laudo cita como disponível existe na
2.1.201; os únicos pós-2.1.201 relevantes (NÃO usados nas recomendações): recibo de interrupt
`interrupt_receipt_v1` (2.1.205+), `--append-subagent-system-prompt` (2.1.205+), erro duro para
`--json-schema` inválido (2.1.205+; antes, schema inválido era ignorado em silêncio — atenção ao
implementar G2).

- **[C-1] Sessões/resume:** https://code.claude.com/docs/en/sessions ·
  https://code.claude.com/docs/en/headless (padrão headless: `claude -p --output-format json`
  devolve `session_id`; depois `claude -p "..." --resume "$id"`) ·
  https://code.claude.com/docs/en/cli-reference (`--session-id`, `--fork-session`,
  `--no-session-persistence`)
- **[C-2] Compactação automática (inclusive headless):**
  https://code.claude.com/docs/en/how-claude-code-works#when-context-fills-up ·
  https://code.claude.com/docs/en/env-vars (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`) ·
  https://code.claude.com/docs/en/sub-agents#auto-compaction
- **[C-3] Subagentes:** https://code.claude.com/docs/en/sub-agents (tool `Agent` ex-`Task` desde
  2.1.63; `.claude/agents/*.md`; `--agents`; nested até profundidade 5; memória por-agente) ·
  https://code.claude.com/docs/en/headless (subagentes em `-p`, espera de background 10min)
- **[C-4] Skills/CLAUDE.md/--bare:** https://code.claude.com/docs/en/skills ·
  https://code.claude.com/docs/en/memory (ordem de carga; AGENTS.md não é lido nativamente;
  auto-memória) · https://code.claude.com/docs/en/headless (`--bare` "vai virar default do -p")
- **[C-5] Hooks:** https://code.claude.com/docs/en/hooks (eventos; handlers `command`/`http`/
  `mcp_tool`/`prompt`; `PreToolUse` nega/reescreve; `Stop` força continuação) ·
  https://code.claude.com/docs/en/permissions#extend-permissions-with-hooks
- **[C-6] MCP:** https://code.claude.com/docs/en/mcp · https://code.claude.com/docs/en/cli-reference
  (`--mcp-config`, `--strict-mcp-config`)
- **[C-7] Permissões:** https://code.claude.com/docs/en/permissions (modos; regras finas; ordem
  deny→ask→allow) · https://code.claude.com/docs/en/cli-reference (`--permission-prompt-tool`)
- **[C-8] Output/streaming/estrutura:** https://code.claude.com/docs/en/headless
  (`--output-format`, `--json-schema` → `structured_output`, eventos do stream-json) ·
  https://code.claude.com/docs/en/cli-reference (`--input-format stream-json`,
  `--include-partial-messages`, `--max-turns`, `--max-budget-usd`) ·
  https://code.claude.com/docs/en/agent-sdk/typescript (`interrupt()`, steering — só em modo
  streaming-input)
- **[C-9] Supervisor background:** https://code.claude.com/docs/en/cli-reference (`--bg`,
  `claude agents`, `attach`, `logs`, `daemon`)

**Nota de versão (codex):** a doc oficial migrou de `developers.openai.com/codex` para
`learn.chatgpt.com/docs` (308); a doc descreve ~0.144. Itens marcados **[tag-verified]** foram
confirmados no SOURCE do tag `rust-v0.142.5` (github.com/openai/codex) — mais forte que doc.
Pós-0.142.5 (NÃO usados nas recomendações): `remote-control pair` (0.143.0), auth interativa de
MCP mid-run (0.144.0), `multi_agent_v2` (routing por task-path; ainda experimental/off no tag).

- **[X-1] Sessões/resume:** https://learn.chatgpt.com/docs/non-interactive-mode (subcomando
  `codex exec resume <SESSION_ID>`, `--last`, `--ephemeral`, `CODEX_API_KEY`) — **[tag-verified:
  `codex-rs/exec/src/cli.rs`]**; storage JSONL **[tag-verified: `codex-rs/rollout/src/lib.rs`]**
- **[X-2] Auto-compactação:** https://learn.chatgpt.com/docs/config-file/config-reference
  (`model_auto_compact_token_limit`) — **[tag-verified: `core/src/state/auto_compact_window.rs`,
  `core/src/tasks/compact.rs`]**
- **[X-3] Multi-agente:** https://learn.chatgpt.com/docs/agent-configuration/subagents —
  **[tag-verified: `Feature::Collab` Stable/default-true em `codex-rs/features/src/lib.rs`;
  handlers em `core/src/tools/handlers/multi_agents/`; `AgentsToml` (max_threads/max_depth) em
  `config_toml.rs`; item `collab_tool_call` em `exec/src/exec_events.rs`]**
- **[X-4] AGENTS.md/skills:** https://learn.chatgpt.com/docs/agents-md ·
  https://learn.chatgpt.com/docs/build-skills — **[tag-verified: `core/src/agents_md.rs`;
  roots em `core-skills/src/loader.rs`]**; skills existem desde rust-v0.64.0 (2025-12)
- **[X-5] Hooks:** https://learn.chatgpt.com/docs/hooks — **[tag-verified: `Feature::CodexHooks`
  Stable/default-true; eventos no schema `codex-rs/hooks/schema/generated/`;
  `--dangerously-bypass-hook-trust` em `shared_options.rs`]**; `notify` legado no config.toml
- **[X-6] MCP:** https://learn.chatgpt.com/docs/extend/mcp?surface=cli (client) — server:
  subcomando `codex mcp-server` com tools `codex`/`codex-reply` **[tag-verified]**
- **[X-7] Sandbox/approvals:** https://learn.chatgpt.com/docs/developer-commands?surface=cli —
  **[tag-verified: default `AskForApproval::Never` no exec em `exec/src/lib.rs`; `--yolo`;
  trap do `--full-auto` removido]**
- **[X-8] Output/schema:** https://learn.chatgpt.com/docs/non-interactive-mode —
  **[tag-verified: `--output-schema`, `-o`, tipos de evento/item em `exec/src/exec_events.rs`]**
- **[X-9] app-server (steer/interrupt):** https://learn.chatgpt.com/docs/app-server
  (`thread/start|resume|fork`, `turn/steer`, `turn/interrupt`, approvals servidas ao cliente)
  — **[tag-verified: subcomando `AppServer` experimental no tag; `codex proto` REMOVIDO]**
- **[X-10] OTEL:** https://learn.chatgpt.com/docs/config-file/config-reference (`[otel]`)
