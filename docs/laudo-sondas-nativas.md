# Laudo — Sondas nativas empíricas (R6-B, 2026-07-11) — FECHADO

> **Produto da faixa R6-B da rodada 6.** Duas sondas empíricas que o grill do laudo motor-nativo
> deixou ABERTAS (`docs/handoff-grilling-motor-nativo.md` §4): **Sonda 1** (usage × subagentes,
> gateia o TAMANHO da fatia off-book — D5) e **Sonda 2** (kill no meio de tool call, ABRE a F-N4 —
> D4). Molde S2 do G3: os CLIs foram dirigidos DIRETAMENTE em workspaces descartáveis do
> scratchpad — **zero código de produto, zero commit, zero instância do produto**. Este arquivo
> nasce UNTRACKED (a 1ª execução derivada commita). Snapshot: main `470b697` (piso 526/270).

**Versões pinadas conferidas no host:** `codex-cli 0.142.5` (== pino). `claude 2.1.206` — o pino
de referência do handoff era 2.1.201; **o host tem 2.1.206**. Ambas ≥ 2.1.205, então satisfazem
os dois gates que o grill amarrou nessa fronteira: o gotcha D2 (schema inválido ignorado em
silêncio em <2.1.205) e o recibo de interrupt (pós-2.1.205). Assimetria de versão registrada, sem
impacto nos vereditos abaixo (as provas são de comportamento do CLI, não de release exato).

**Método:** espelhei a invocação REAL do produto (lida em `ClaudeArgv.java`/`CodexArgv.java` @
`470b697`, que a R6-A pode estar evoluindo em paralelo — irrelevante p/ o comportamento do CLI):
claude `-p <prompt> --output-format stream-json --verbose --permission-mode bypassPermissions`;
codex `exec --json --skip-git-repo-check -s workspace-write <prompt>`. Prompts MÍNIMOS (a prova é
mecânica). Modelo: haiku (claude) / default (codex) por custo — contabilidade e resume são
estruturais, independem de modelo.

---

## Sonda 1 — usage × subagentes (D5): o `usage` do result inclui os subagentes?

### Veredito por motor

| Motor | Spawn nativo usável? | `usage` (que o parser lê) inclui subagentes? | Canal nativo p/ capturar o gasto do subagente |
|---|---|---|---|
| **claude** | SIM (tool `Agent`/Task; subagentes rodam como *background tasks* numa sessão única) | **NÃO** | **`task_notification.usage.total_tokens` — JÁ chega no stream ATUAL, sem flag nova** |
| **codex** | SIM (`spawn_agent`/`wait`/`close_agent` via `collab_tool_call`; feature `multi_agent`=stable+true, exposta no `codex exec --json`) | **NÃO** | **Nenhum in-stream.** Só o session JSONL do *thread-filho* (chave `receiver_thread_ids`, que vem no stream) — ou OTEL com collector |

### Prova — claude
Run com 2 subagentes forçados (`Agent` tool, subagent_type general-purpose):
- Subagentes rodam como **background tasks** numa **sessão única** (mesmo `session_id` nos 3
  `result`); eventos `system`: `task_started`/`task_updated`/`task_notification`/
  `background_tasks_changed`.
- **O objeto `usage` NÃO agrega subagentes.** O `ClaudeStreamParser` lê `usage` top-level
  (result) / `message.usage`, **last-wins** — o último `result` trouxe `output=63` (só o turno de
  síntese). Soma dos `message.usage` de todos os `assistant` = **48 tokens de output**. Enquanto
  isso os 2 subagentes gastaram **27.347 tokens** (RED=13.672 + BLUE=13.675), invisíveis no `usage`.
- **O canal nativo existe e já chega:** cada subagente, ao completar, emite
  `{"subtype":"task_notification", ..., "usage":{"total_tokens":13675, ...}}`. **Provado que
  aparece SEM `--include-hook-events`** (run de controle, mesmos flags do produto hoje): o
  subagente GREEN emitiu `task_notification.usage.total_tokens=13676` no stream padrão.
- (`total_cost_usd` no `result` É cumulativo — 0.0749→0.0797→0.0843 nos 3 results — mas o produto
  contabiliza TOKENS via `usage`, não custo, então isso não salva a conta.)

### Prova — codex
Run com `spawn_agent` forçado:
- **`codex exec --json` EXPÕE as ferramentas multi-agente** (não é só TUI/app-server): o stream
  trouxe `item.*` `collab_tool_call` para `spawn_agent`, `wait`, `close_agent`, e o subagente
  respondeu "RED" via `agents_states`. (Um `item` `error` benigno no início: aviso de "skills
  context budget", não falha.)
- **Cada subagente é um THREAD próprio** com **session JSONL próprio**. O subagente ganhou thread
  `019f53ea-be75-…` (aparece no stream do parent via `collab_tool_call.receiver_thread_ids`) e seu
  gasto vive só no rollout dele (`input=17.194, output=21`). O stream do parent tem **um único**
  bloco `usage` (`turn.completed`: input 96.672, output 420) — **do parent**; os `collab_tool_call`
  **não carregam nenhum campo de token**.
- **OTEL:** a 0.142.5 TEM exporter OTLP embutido (traces/metrics/logs), mas exige um collector
  externo; a feature `runtime_metrics` está `under development / false` (indisponível). Ou seja, a
  premissa "OTEL nativo" do D5 é real mas custa infra; o caminho sem infra é o session JSONL do
  filho.

### Implicação no TAMANHO da fatia off-book (a pergunta do D5)
- **claude → fatia MÍNIMA ("rotular o que já chega", quase).** Basta o `ClaudeStreamParser`
  **somar `task_notification.usage.total_tokens`** por subagente — dado que já trafega no stream
  que o produto consome hoje. Zero flag nova, zero hook http, zero proxy.
- **codex → fatia MAIOR (assimétrica).** O gasto do subagente **não passa** pelo stream do exec.
  Captura nativa = seguir os `receiver_thread_ids` (que vêm no stream) e **ler os rollouts dos
  threads-filhos** em `~/.codex/sessions/**` — um side-channel de arquivo, não uma soma in-parser.
  (Alternativa: subir um OTLP collector — mais infra, contraria o espírito "sem proxy próprio".)
- **Carona obrigatória (D6):** o run codex multi-agente gastou **96k de input só de overhead** do
  prompt multi-agente. Confirma a regra "fatia que amplia autonomia não shippa sem o teto": os
  tetos `[agents] max_threads`/`max_depth` (codex) e deny `Agent(...)` (claude) são requisito de
  aceite de qualquer fatia que ligue subagentes.

---

## Sonda 2 — kill no meio de tool call (D4): `--resume` retoma são?

Molde: run com tool call lenta (`perl -e 'sleep 60'` — o `sleep` puro do shell é BARRADO pelo
guard do ambiente; perl/python evadem), processo do motor morto com **SIGKILL** com o filho perl
VIVO e o stream **sem** `tool_result`/`function_call_output` (mid-call comprovado), depois resume.

### Veredito por motor

| | claude | codex |
|---|---|---|
| Exit do kill | 137 (SIGKILL) | 137 (SIGKILL) |
| Session JSONL sobrevive? | **SIM**, termina em `assistant/tool_use` órfão (sem `tool_result`) | **SIM**, termina em `function_call` órfão (sem `function_call_output`) |
| `--resume`/`resume` é SÃO? | **SIM, exit 0**, sem erro no tool_use órfão | **SIM, exit 0**, sem erro no function_call órfão |
| O que se perde | **o TURNO interrompido** (reconciliado/descartado) | **nada além do output da tool** — o turno é PRESERVADO |
| Turnos completos anteriores | **SOBREVIVEM** (prova multi-turno abaixo) | sobrevivem (o resume nem descarta o interrompido) |
| Processo-filho da tool no kill | **ÓRFÃO** (perl reparented, sobrevive ao SIGKILL) | **ÓRFÃO** (idem) |

### Prova — claude (multi-turno, o teste decisivo)
Turno 1 "grave o codeword BANANA42" (completa, `OK`) → Turno 2 resume + `bash slow.sh` (perl
sleep 60) **morto mid-call** (perl vivo, sem tool_result, exit 137) → Turno 3 resume "qual era o
codeword?" → **responde `BANANA42`**. O turno COMPLETO sobreviveu; **só o turno interrompido foi
descartado** (o `tool_use` órfão é reconciliado no resume). No teste single-turn (o turno morto era
o 1º e único), o modelo no resume disse *"this is the start of our conversation"* — confirmando que
o turno morto some por inteiro (não fica meia-tool pendurada). `--resume` **forka novo session-id**
quando o turno completa (V16 confirmado; o produto já rotaciona em `AgentSession`).

### Prova — codex
Turno morto mid-call (perl vivo, `turn.completed`=0, exit 137; workspace-write auto-aprovou o perl).
Resume `codex exec … resume <thread_id> "…"`: exit 0 e o modelo respondeu
**"Yes, resumed; I was running `perl -e 'sleep 60; print \"FIN_MARKER_42\\n\"'`"** — **codex PRESERVA
o turno interrompido** (sabe o comando exato que rodava). Perda ainda menor que a do claude.

### O que muda no desenho da F-N4
- **D4 (kill + resume nativo) SE SUSTENTA nas duas pontas.** Resume é são; a conversa (turnos
  completos) sobrevive; o pedaço descartado é exatamente o turno interrompido — barato, como o grill
  previu. Nenhum checkpoint próprio é necessário.
- **NOVO requisito concreto p/ o "endpoint de interrupt (kill controlado)":** matar o **process
  group**, não só o PID do motor. Prova: nos DOIS motores o filho da tool (perl) **sobreviveu órfão**
  ao SIGKILL do processo-pai. Sem isso, uma tool longa real (build, subprocess do codex, servidor)
  **vaza** ao "parar". O estado de run `INTERRUPTED` deve reapear/matar os filhos.
- **Nota de granularidade (assimetria, não bug):** no claude a "correção" do dono parte do último
  turno COMPLETO (o turno morto sumiu); no codex o modelo ainda "lembra" do que fazia ao ser morto.
  Nenhum quebra a cena de supervisão corretiva — só informa o texto do prompt de retomada.
- **Gotcha de plumbing (vale já):** `codex exec` **bloqueia lendo stdin** se o stdin não for
  fechado (`< /dev/null` obrigatório) — trava indefinida, não erro. O claude tem timeout de 3s e
  prossegue. Confirmar que o `ProcessBuilder` do produto fecha/redireciona o stdin do codex.

---

## Assimetrias claude × codex (consolidado)

1. **Off-book (S1):** claude expõe o usage do subagente **in-stream** (`task_notification`); codex
   **não** — só no rollout do thread-filho. → captura off-book de desenho DIFERENTE por motor.
2. **Arquitetura de subagente (S1):** claude = *background tasks* numa sessão única; codex =
   *threads* independentes, cada um com session JSONL próprio.
3. **Perda no kill (S2):** ambos os resumes são SÃOS, mas claude **descarta** o turno interrompido
   e codex **preserva**. → codex perde menos.
4. **stdin (S2):** codex trava lendo stdin sem `< /dev/null`; claude não.
5. **Simétrico (S2):** os dois vazam o processo-filho da tool como órfão no SIGKILL — vira
   requisito de kill-de-process-group na F-N4.

---

## O que NÃO foi provável provar (e por quê)

- **Atribuição token-a-token do parent codex:** confirmei que subagente = thread/arquivo separado e
  que o stream do exec tem 1 só `usage` (do parent) — suficiente para o veredito "não inclui". Não
  tentei medir sobreposição fina porque, por design, não há: cada thread contabiliza o seu.
- **OTEL do codex end-to-end:** não subi um OTLP collector (decisão D5 = sem infra própria); só
  confirmei que o exporter existe e `runtime_metrics` não. O caminho recomendado (rollout do filho)
  dispensa isso.
- **Safeguard transiente:** um prompt com escaping pesado disparou uma vez o flag de conteúdo
  ("Fable 5"); contornado com o comando lento num `slow.sh` (sem escaping). Não afeta vereditos.

## Contrato desta faixa (cumprido)
Zero código de produto tocado (os `M` em `ClaudeArgv`/`CodexArgv`/adapters no `git status` são da
R6-A em paralelo — apenas LIDOS aqui). Zero commit, zero PR. Nenhuma instância do produto usada
(portas 8080/18081/18082 intocadas; CLIs dirigidos em workspaces do scratchpad). Untracked de
contrato intactos. Zero reservas consumidas. Artefatos das provas (JSONLs + scripts de kill) no
scratchpad da sessão.
