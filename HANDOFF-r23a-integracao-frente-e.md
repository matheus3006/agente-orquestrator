# HANDOFF — R23-A: integração da Frente E (Spring AI 2) na main — **FECHADA (PR #35 MERGED)**

> Registro da faixa **R23-A** (sessão satélite, 2026-07-17). UNTRACKED na raiz (precedente
> R20-A/R21-A/R22-A). Handoff de volta completo no ledger: `docs/controle-paralelismo.md`
> (§Handoff de volta — R23-A). A faixa fez **rebase + reconciliação + prova + PR** — NÃO
> re-planejou a migração nem reimplementou nada (a implementação veio pronta da frente).
> ⚠️ Os caminhos de código citados existem na branch `feat/backend-spring-ai-2`; o checkout
> compartilhado segue na `main` (ver §Notas).

## Estado final

**PR #35 MERGED pelo dono** (2026-07-17 13:55Z, merge commit **`fef6073`** = `origin/main`) —
https://github.com/matheus3006/agente-orquestrator/pull/35 · **14 commits** (+3792/−337).
O merge saiu **depois** do fecho da faixa: a sessão entregou o PR aberto e o dono mergeou; a
verificação de fecho confirmou `origin/main = fef6073`, com a branch como ancestral e o conserto
da ADR-0043 + o carimbo do roadmap na main.
`feat/backend-spring-ai-2` rebasada de `83c5fe9` → **`4c1b35f`** (main pós-#34) e **pushada**
(1º push — a branch era LOCAL-only, sem ref remota; ninguém a sobrescrever, como previsto).

> ⚠️ **A `main` do checkout compartilhado ainda está em `4c1b35f`** (atrás de `origin/main`).
> O ff é chore do controle — a R22-B está usando o checkout; esta faixa não mexeu no HEAD dele.

Conteúdo integrado (da frente, pronto): Java 25.0.3 · Maven Wrapper 3.9.16 · Spring Boot
4.1.0/Framework 7 · Jackson 3 exclusivo · Spring AI 2.0.0 (só starter Ollama, **OFF por
default**) · F-PLAT0/F-SAI0/F-SAI1 · `V31__run_context_manifest_and_assessment.sql` ·
ADR-0043 + `docs/plano-implementacao-spring-ai.md`.

**14 commits = 11 rebasados + 3 de docs** (`8f12a73` alheio · `6ca7ec8` minha correção ·
`034e62b` carona do carimbo). Eram 12 rebasados — 1 caiu, ver §Decisões.

## Os conflitos: 3 previstos, **1 real**

1. **`backend/.../execution/AdapterRunExecutor.java` — o único conflito de fato (2 hunks).
   DOIS LADOS PRESERVADOS.** O #34 içou a composição do prompt acima do galho turno/issue; a
   Frente E extraiu a materialização para o `RunContextCompiler` (módulo profundo novo em
   `backend/.../context/`). Reconciliação: o **prompt composto entra no compilador como insumo**
   (`RunContextCompiler.Request.prompt`) e volta pelo `ContextPackage`, que passa a ser o
   portador vivo de prompt/schema.
   **⚠️ A ARMADILHA (o que quase reintroduziu o bug #1):** o lado da Frente E reatribuía
   `command`/`env` sob `if (turno != null)` — **exatamente o gate que o #34 REMOVEU**. Mantê-lo
   devolveria o bug #1 **em silêncio, com a suíte verde** (run de issue sem prompt). Agora são
   incondicionais, derivados de `contextPackage.prompt()`.
2. **`backend/.../execution/adapter/ClaudeCodeAdapter.java` — SEM conflito textual.** A E só
   reescreveu o import (Jackson 3); o #34 mexeu no `execute()`. Hunks disjuntos; `Result.okNoop`
   intacto.
3. **`docs/roadmap-interno.md` — trivial**, seções distintas.

Imports, campos e construtor auto-mergearam certo (`issueComposer` do #34 convive com
`contextCompiler`/`contextIntelligence` da E).

## /verify (descartável :18081 + pg efêmero; :8080/pg do dono e o `orq-sai-pg` da frente intocados)

- **Perna (g) do #34 RE-PROVADA com motor REAL:** agente contratado (`runtime_command` **NULL**,
  como o gate cria) executou a issue e o **claude REAL citou o sentinela `R23A-SENT-9F42-KQ`**,
  que só existia na estratégia do workspace **da conversa** (issue → conversa V27 → `bastao_path`
  V30 → `readWorkspaceFile`). Run de **19,7s** com report + artefato — não o relâmpago de
  59ms/zero-motor. **Codex REAL** citou o mesmo sentinela (45,1s, artefato próprio).
- **Briefing composto capturado LITERAL** pelo canal `ORQ_PROMPT` (arquétipo `process` com
  `runtimeCommand='printenv ORQ_PROMPT'`): `#N` + título + descrição + **estratégia** + report.
  Os **dois canais** do seam T.3 provados (command nos adapters ricos, env no process).
- **Trava no-op:** `process` mudo sobre issue → run **FAILED em 54ms** com erro legível e issue
  **BLOCKED** (nunca DONE fantasma). `RUN_CONTEXT_USAGE: estimated_tokens=618
  actual_input_tokens=0` corrobora por instrumentação **da própria Frente E** que o motor não rodou.
- **Spring AI OFF por default:** `context_assessment` = **0 linhas**; o compilador determinístico
  roda normalmente.
- **Hash determinístico intacto:** manifesto pós-rebase = `8d558ff6…b2b2b2 / 2471 bytes / 618
  tokens` — **idêntico ao registrado na ADR-0043 antes do rebase**. O prompt não entra no hash.
- **Flyway em pg limpo:** **31 migrations, 31 versões distintas, 0 falhas**, em ordem
  (V29 → **V30** `conversation.bastao_path` da main → **V31** da frente).
- **Probe:** estratégia anotada mas ilegível → o briefing **SINALIZA a lacuna** (anti-silêncio
  do #34 vivo), não engole.

## Suítes

**Backend 769** (0 falhas/erros/skips) = 757 da branch + 12 do #34 — medido em árvore **limpa**.
**Front 294** (21 arquivos; o front é byte-idêntico à main). Os 4 testes do #34 presentes e
verdes: `IssuePromptComposerOnRunTest`(5) · `AgentRunNoopTest`(2) · `IssueNoopNuncaFechaTest`(3) ·
`ConversationBastaoAnotadoTest`(2). **Piso proposto pós-merge: 769/294** (o controle re-mede).

## Decisões fechadas (não reabrir)

- **Fold do rename da V31 (12 → 11 commits, sem squash).** O rebase simples deixava **4 commits
  intermediários com dois arquivos na versão Flyway V30** (o V30 da main + o da frente pré-rename)
  → Flyway rejeita versão duplicada → esses commits **não sobem**, quebrando o *"rollback = o
  commit anterior verde"* da **ADR-0043 §2**. Defeito **CRIADO PELO REBASE** (a branch original
  era verde em todo commit). Consertado fazendo o arquivo **nascer V31** no commit que o introduz
  e dropando o `fix: reserve Flyway V31…` (redundante — seu propósito ERA reservar a V31 para esta
  integração). **Árvore final idêntica** ao rebase simples, verificada por diff. Backup em
  `backup/pre-v31-fold`.
- **Commit alheio na ADR-0043: MANTER e CONSERTAR** (decisão do dono, colhida ao vivo quando o
  commit apareceu). Ver §Notas.
- **`command`/`env` incondicionais** (derivados de `contextPackage.prompt()`) — o gate
  `if (turno != null)` da Frente E não volta.

## Pendências / próximos passos

1. ~~MERGE do PR #35~~ — **FEITO pelo dono** (13:55Z, main `fef6073`).
2. **Chores pós-merge = controle** (todos ABERTOS): **ff da `main` do checkout compartilhado**
   (`4c1b35f` → `fef6073`) · remover o worktree `../agente-orquestrator-spring-ai` · apagar as
   branches `feat/backend-spring-ai-2` (local+remota), `parked/adr0043-racional-verbatim` e
   `backup/pre-v31-fold` · **re-medir o piso na main mergeada** (proposto **769/294**) · fechar a
   R23-A no ledger.
3. **Reboot do :8080** — o `:8080` (PID 46677) segue no jar **pré-migração**. Decisão da
   principal: a **1ª bateria da descoberta roda no v1 DECLARADO** (`4c1b35f`, pré-migração,
   pós-#34) — os findings mapeiam limpo ao v1. O reboot no jar da migração (Java 25/Boot 4.1) é
   sentada do operador, **quando a principal decidir** — não é pré-requisito da bateria 1.
4. **FORA e não autorizados (intocados):** `ACTIVE` · F-SAI2/3/4 · métricas SHADOW ·
   provider/egress · ADR-0044 · **MCP dentro do Docker** (segue NÃO atendido — registrado como
   está, não "consertado").

## Achados (não consertei — fora de escopo)

- **A suíte H2 NÃO exercita Flyway:** o V30 duplicado passou por **769 testes verdes** e só caiu
  ao **BOOTAR** contra pg. Se o piso é a única guarda, colisão de migration só aparece em runtime
  — eco direto da lição da fase ("o piso 738/294 verde não viu").
- **A skill `verify` está defasada:** manda `mvn -o package`, mas a frente pinou o **Maven
  Wrapper** (ADR-0043 §1) — usei `./mvnw`. Não editei a skill (o comando não me steerou errado,
  só envelheceu); se o controle quiser, é uma linha.
- **Codex CLI 0.142.5** loga `ERROR codex_models_manager::cache: failed to load models cache:
  missing field supports_reasoning_summaries` e conclui assim mesmo — ruído do CLI, não do
  backend (vizinho do caveat de modelo já registrado na ADR-0043).
- **Inserir `conversation` direto no pg acorda as personas CEO** (5 runs de Planejador reais na
  fila) — a sonda ficou QUEUED atrás delas. Custo de prova, não bug.

## Notas (estado do ambiente ao encerrar)

- **⚠️ COMMIT ALHEIO NA BRANCH.** `8f12a73 docs: preserve owner Spring AI rationale` apareceu
  **durante o rebase** (10:10, autor `Matheus de Souza <matheussouza18@live.com>`), por um
  cherry-pick que **esta sessão não executou**, aplicado sobre um commit que o rebase tinha
  acabado de criar; o commit de origem **não é alcançável por ref nenhuma**. Preservado em
  `parked/adr0043-racional-verbatim`. **Perguntei ao dono → "manter e consertar".** O apêndice
  dizia preservar o texto "verbatim" mas **cortava o parágrafo `LIMITES ARQUITETURAIS`**,
  alegando incorporação no §3 — **o §3 não cobre**: faltavam segredos/PII sem política, log de
  prompts/respostas sensíveis, executar tools, pull automático de modelos, transação aberta
  durante chamada e "falha de provider não bloqueia/invalida o run". Como a ADR-0043 é a **fonte
  canônica do gate do ACTIVE**, o corte apagava justamente o que o gate precisa. Restaurado
  verbatim em `6ca7ec8`; o apêndice é agora **cópia byte-idêntica** da fonte
  (`orquestrador-agentes/scratchpad/racional-spring-ai-verbatim-dono.md`, 73/73 linhas,
  conferido por diff). Sinalizado no comentário do PR #35.
  **Lição de controle:** worktree próprio protege o **checkout**, não a **BRANCH** — conferir
  `git log` da própria branch antes do push.
- **Checkout compartilhado intocado, segue na `main`** (livre para a R22-B). Todo o trabalho
  correu no worktree `../agente-orquestrator-spring-ai` (VIVO até o chore pós-merge).
- **Vivo intocado:** `:8080` (PID 46677), `:3000`, pg `agente-orquestrator-db-1` e o
  `orq-sai-pg` (sobra da própria Frente E) — conferidos um a um no teardown. O /verify usou só
  a descartável (`:18081` + pg `r23a-pg`, ambos já derrubados).
- **Untracked de contrato preservados** (lidos, nunca commitados): `docs/controle-paralelismo.md`
  · `docs/laudo-lease-adr0005.md` · `docs/handoff-auditoria-doc-codigo-2026-07-11.md` ·
  `HANDOFF-r22a-conserto-bug1.md` · `HANDOFF-r22b-plano-descoberta-bugs.md` ·
  `docs/plano-descoberta-bugs.materiais/`. Conferido: **zero** deles entrou nos commits.
- **Memória atualizada:** `r23a-integracao-frente-e-executada.md` (+ índice `MEMORY.md`).
