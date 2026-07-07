# ADR-0025: Completar o adapter codex — política de sandbox por postura do ExecutionTarget

**Status:** Aceito (2026-07-05, execução F.11 — `docs/handoff-f11-correcao-adapter-codex.md`)

## Contexto
A prova F.11a (LOCAL-first, ADR-0024) validou o `CodexStreamParser` contra o stream real do
`codex 0.142.5` (thread_id, usage e item.text batem — parser RETIDO, zero mudança) mas revelou
que o adapter codex **nunca rodou de verdade**: três lacunas de integração impedem QUALQUER run.
Evidência empírica (probes diretos `codex exec --json` no host):

- **E1 git/trust** — `codex exec` recusa diretório não-git (*"Not inside a trusted directory and
  --skip-git-repo-check was not specified"*). O workspace/volume do Run nunca é repo git → atinge
  LOCAL e DOCKER.
- **E2 sandbox read-only** — o default do `codex exec` é read-only+approval (*"patch rejected:
  writing is blocked by read-only sandbox"*) → o agente não escreve `.orq/preview/index.html` →
  mata o DoD v1/v2 nos dois alvos.
- **E3 stdin no LOCAL** — `LocalExecutionTarget.run` cria o filho com stdin no default (pipe
  aberto, nunca escrito, nunca fechado) → `codex exec` TRAVA lendo stdin. Só LOCAL; o DOCKER não
  passa `-i` ao `docker run`.

Flags CONFIRMADOS empiricamente (não chute): DOCKER escreve com `--skip-git-repo-check
--dangerously-bypass-approvals-and-sandbox` (flag *"intended solely for environments that are
externally sandboxed"* — exatamente o container do ADR-0023); LOCAL escreve com
`--skip-git-repo-check -s workspace-write` (provado: arquivo escrito, exit 0, zero approval).
Ordem: flags depois de `exec --json`, prompt SEMPRE o último (posicional).

## Decisão

### 1. A política de sandbox/trust mora no seam `ExecutionTarget`, como postura de isolamento
Sandbox/trust não é gramática de motor nem flag solto no adapter — é propriedade do **ambiente**
onde o run executa. O `ExecutionTarget` ganha `posture()` devolvendo o enum `IsolationPosture
{ EXTERNALLY_SANDBOXED, HOST }`, **agnóstico de motor**:

- **Default da interface = `HOST`** (fail-safe): alvo novo nasce pedindo self-sandbox ao motor;
  bypass jamais acontece por omissão.
- `DockerExecutionTarget` sobrescreve para `EXTERNALLY_SANDBOXED` — o container É o sandbox
  externo (isolamento por container, ADR-0016/0023).
- `LocalExecutionTarget` fica no default `HOST` (dev-only, ADR-0016).

### 2. `CodexArgv` permanece o dono único dos flags do codex — a postura é input mínimo
`CodexArgv.build(prompt, model, effort, posture)` traduz a postura na gramática do CLI
(módulo profundo, ADR-0010 — todo flag do codex num lugar só):

- Sempre: `--skip-git-repo-check` (E1 — o workspace nunca é git, nos dois alvos).
- `EXTERNALLY_SANDBOXED` → `--dangerously-bypass-approvals-and-sandbox` (E2 no DOCKER).
- `HOST` → `-s workspace-write` (E2 no LOCAL). **Bypass no host foi RECUSADO**: seria execução
  arbitrária do modelo com as permissões do operador.
- Prompt continua o último elemento (posicional).

`CodexAdapter.execute` fia `context.target().posture()` → `CodexArgv.build`. `ClaudeArgv`/
`ClaudeCodeAdapter` não mudam (o claude não consome postura — self-sandbox próprio).

### 3. Fix E3 no próprio alvo: stdin do filho vem de `/dev/null`
`LocalExecutionTarget.run` redireciona o stdin do subprocesso de `/dev/null` (EOF imediato).
Propriedade do ALVO, não do motor: nenhum adapter conversa com o filho por stdin (o prompt é
argv), e motor que lê stdin não pode pendurar o run. DOCKER intocado (sem `-i` por construção).

## Riscos → estratégias (registro da sessão de prova; R2/R6 são os headline)
- **R2 — bypass no DOCKER dá poder total ao codex dentro do container** (rede + credencial do
  plano montada): é o MESMO risco já aceito para o claude, que roda no container com credencial
  e rede desde a F.8 — consistente com ADR-0023 (as verificações do 4º container/preview seguem
  valendo). Aceito conscientemente, aqui registrado.
- **R6 — tocar um seam provado (`ExecutionTarget`) pode quebrar contratos vivos**: mudança
  ADITIVA (método default + enum), default fail-safe `HOST`, TDD red→green e piso da suíte
  **≥328 verde** antes de qualquer smoke. Zero toque em skills-path (`.claude/skills`),
  `.orq/preview/*` e parser.
- **stdin→/dev/null afeta o claude no LOCAL**: o claude não usa stdin (prompt no argv) — seguro;
  validado no próprio smoke.
- **`workspace-write` no LOCAL ainda escreve no host**: blast-radius limitado ao workspace e o
  alvo LOCAL segue dev-only (ADR-0016) — andaime de prova, não produção.

## Alternativas rejeitadas
- **Flags de sandbox dentro do `CodexArgv` decididos por `ExecutionTarget.Type`** — acopla a
  gramática do motor ao TIPO do alvo; um alvo futuro (VM, remoto) forçaria a gramática a
  conhecer cada tipo. Postura é o invariante certo: o que o motor precisa saber é "o ambiente
  já me isola?", não "sou docker?".
- **Bypass também no LOCAL** — execução arbitrária do modelo direto no host do operador;
  recusado mesmo sendo dev-only.
- **Postura como parâmetro do `Spec`** — o `Spec` descreve UM run; a postura é propriedade
  estável do alvo. No alvo, o default fail-safe mora no seam e vale para todo caller.
- **Fechar stdin no adapter (motor a motor)** — repetiria o remédio em cada adapter; no alvo é
  um fix só, para todo motor presente e futuro.

## Recorte explícito (fora desta fatia)
Retomada de sessão (thread_id/resume) · UI de model/effort · rate limit/quota do plano ·
codex LOCAL como alvo de produção · fallback AGENTS.md (pré-decidido no ADR-0024 dec.2, só
entra mediante prova de que o codex ignora a skill).

## Relaciona
ADR-0010 (módulo profundo — `CodexArgv` dono único da gramática) · ADR-0014 (adapter rico do
codex) · ADR-0016 (LOCAL dev-only; isolamento por container; montagem seletiva) · ADR-0021/0022
(contratos de workspace que o E2 bloqueava) · ADR-0023 (container = sandbox externo; risco de
bypass consistente com o já aceito) · ADR-0024 (a prova que achou E1/E2/E3; parser retido).
