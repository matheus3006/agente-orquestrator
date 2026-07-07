# Handoff — F.11 EXECUÇÃO: completar o adapter codex + retomar o smoke

> Origem: sessão de PROVA (F.11a, LOCAL-first) 2026-07-05. A prova **validou o parser** mas achou
> **3 lacunas de integração** que impedem QUALQUER run codex. Operador aprovou corrigir tudo com
> processo. **Esta sessão EXECUTA — não re-planeja, não reabre ADRs 0002/0014/0015/0016/0021/0022/0023/0024.**
> Repo SEM git em `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/` — NÃO commitar.
> Piso backend: **328/328** (não baixar). Front intocado.

## Estado / North
A promessa "Codex ganha de graça" (ADR-0021/0023) é HOJE **falsa**: o adapter codex nunca rodou de
verdade e está incompleto. Esta fatia a torna FATO completando o adapter (mínimo, no seam) e provando
a pilha inteira com codex. O **parser NÃO muda** (já bate com o CLI real — ver §A).

## §RESULTADO (sessão de execução 2026-07-05 — parcial: LOCAL provado, DOCKER pendente de login)
- **Adapter COMPLETO e provado no LOCAL.** ADR-0025 escrito (Aceito). TDD red→green em 5 ciclos:
  `CodexArgv.build(prompt, model, effort, posture)` (EXTERNALLY_SANDBOXED→[skip-git, bypass];
  HOST→[skip-git, `-s workspace-write`]; prompt último); `ExecutionTarget.posture()` default HOST
  + override DOCKER; `CodexAdapter` fia `context.target().posture()`; `LocalExecutionTarget.run`
  com stdin de `/dev/null` (E3 — teste `LocalExecutionTargetRunTest` com `cat` pendurado→verde).
  **Suíte 334/334** (piso novo: 328+6). Jar novo no `:8080` (log `/tmp/orq-f11.log`).
- **F.11a COMPLETO — "Codex ganha de graça" é FATO no LOCAL**: cobaia `smoke-codex-local`
  (`60a8a573`), issue `67aa95e5`, run `5b33c111` SUCCEEDED (~3,5min). StepTrace legível
  (THREAD.STARTED/TURN.*/ITEM.* + 1 STDOUT benigno de stdin, como previsto). Codex ACHOU a skill
  com o prompt neutro, leu o SKILL.md e publicou `.orq/preview/index.html` → preview v1 (26.870 B)
  → **fallback AGENTS.md NÃO precisou disparar** (ADR-0024 dec.2 segue adormecida). Custo
  provider=openai 230.709 in / 10.175 out (model null — nuance benigna §A; a 2ª linha do ledger é
  o débito RUN_OPEN da governança, normal). Aprovado VENDO no board (:3000, kanban renderizado no
  iframe sandbox) → review `e83f7e89` APPROVED → issue **DONE**.
- **thread_id**: capturado pelo parser (`Result.session`); a persistência de handle é por
  (Agent, Conversation) — ADR-0002 — e wakeup por issue não cria conversa → sem linha em
  `agent_session`, SIMÉTRICO ao claude. Não é lacuna do eixo motor; retomada segue no recorte.
- **⚠ INCIDENTE — RESOLVIDO 2026-07-05 na sessão grilling F.12** (ver
  `docs/handoff-grilling-gc-retencao-workspaces.md` §F: era 1 de 4 aprovações acidentais; tudo
  revertido/limpo; da239e6d segue PENDING para decisão real do operador; o SQL abaixo NÃO deve ser
  re-executado — e o DELETE de activity_log dele é bloqueado por trigger append-only). Registro
  original: um clique cego na UI durante a aprovação atingiu o
  Mission Control e APROVOU por engano a approval `da239e6d` do Atlas (CREATE_ISSUE
  "Issue de direcao: nicho"), criando a issue `2a8503c9` "Definir nicho do produto" e uma decision
  no log com racional FALSO (texto do smoke). A reversão por SQL foi bloqueada pelo classificador
  de permissão (governança alheia — decisão do operador). **SQL de reversão pronto** (rodar se
  concordar; `decide()` só escreve os campos abaixo; `racional_tipo` é do propose e fica):
  ```sql
  BEGIN;
  DELETE FROM decision WHERE approval_id='da239e6d-0ce6-4623-b827-a17550559789';
  DELETE FROM issue WHERE id='2a8503c9-4700-4a1e-9630-68bd79ebe600';
  DELETE FROM activity_log WHERE detail::text LIKE '%2a8503c9%' OR detail::text LIKE '%da239e6d%';
  UPDATE approval SET status='PENDING', decided_by_actor_type=NULL, decided_by_actor_id=NULL,
    decision_reason=NULL, racional=NULL, racional_arrancado=false, grill_rounds=0, decided_at=NULL
    WHERE id='da239e6d-0ce6-4623-b827-a17550559789';
  COMMIT;
  ```
- **F.11b COMPLETO** (retomada 2026-07-05, operador presente): device-auth no container →
  "Successfully logged in", `auth.json` (4.477 B) NASCIDO no volume `orq_codex_creds` (ADR-0002
  honrado — nada copiado do host). Levou 5 códigos: os 4 primeiros expiraram porque o poll
  bloqueava o turno antes de o operador ver o código — o padrão que funcionou foi lançar o login
  em background, ENCERRAR o turno com o código visível e deixar a task notification acordar a
  sessão quando o exit 0 chegasse.
- **F.11c COMPLETO — smoke DOCKER v1**: cobaia `smoke-codex-docker` (`034c7264`), issue
  `63ba8b99`, run `3b13ebe4` SUCCEEDED (~4,5min). **Credencial seletiva provada VIVA durante o
  run**: `docker inspect` no container real mostrou SÓ `orq_codex_creds:/home/node` + workspace
  (zero volume do claude). Skill lida no container (bash, não zsh), publicada no contrato, custo
  openai 364.053/12.675, preview v1 32.861 B → aprovado VENDO (kanban na moldura) → **DONE**.
- **F.11d COMPLETO — preview v2 (servidor) via codex**: mesma cobaia, issue `96c1d2ea`, run
  `226f5373` SUCCEEDED com DUAS skills instaladas — o codex ESCOLHEU a `criar-app-servidor`
  sozinho pelo prompt neutro. `server.json` → manifest persistido (`node server.js`:3000) →
  start pelo board → **app viva no iframe** (`127.0.0.1:56843`, Bloco de notas) → API própria
  provada por fora (GET seed do codex; POST criou nota com workspace `:ro` — runtime em
  memória/tmpfs como a skill manda) → aprovado VENDO → **DONE**. **ADR-0023 conferido no 4º
  container VIVO**: só workspace montado `rw=false` (SEM credencial), tmpfs `/tmp`, porta
  `127.0.0.1` efêmera, caps pids=256/mem=512MB/cpu=1.0, rede `orq-preview`, labels `orq.*`.
  Stop pelo board derrubou E removeu o container (0 sobras).
- **F.11 FECHADA: "Codex ganha de graça" (ADR-0021/0022/0023) é FATO nos dois alvos** — mesmo
  materializer, mesmos contratos de lugar, zero condicional de motor. O fallback AGENTS.md
  (ADR-0024 dec.2) segue adormecido: nunca precisou disparar. Suíte 334/334 (nenhum código de
  produção tocado na retomada). Smokes vivos como demonstração: `67aa95e5` (LOCAL v1),
  `63ba8b99` (DOCKER v1), `96c1d2ea` (DOCKER v2 servidor).
- Gotcha novo (sangue): `preview_click` por coordenada NÃO dispara o onClick do React nesta UI —
  o dialog de decisão só abre com `el.click()` programático; e a fila de review é `/reviews`
  (plural). O clique cego `button:not([disabled])` foi a causa-raiz do incidente acima.

## §A — ACHADOS DA PROVA (evidência empírica; probes diretos `codex exec --json` no host)
- **VERDE — parser retido.** `CodexStreamParser` bate com o stream real do `codex 0.142.5`:
  `{"type":"thread.started","thread_id":"…"}` (thread_id TOP-LEVEL ✓ session), `{"type":"turn.completed",
  "usage":{"input_tokens":…,"cached_input_tokens":…,"output_tokens":…}}` (usage TOP-LEVEL ✓ custo),
  `{"type":"item.completed","item":{"type":"agent_message","text":"…"}}` (item.text ✓). **Nenhum /tdd de parser.**
  Nuance benigna: runs curtos não trazem `model` no stream → `cost_event.model=null` (esperado, não é falha).
- **E1 git/trust** — `codex exec` recusa dir não-git: *"Not inside a trusted directory and --skip-git-repo-check
  was not specified."* `CodexArgv` NÃO passa `--skip-git-repo-check`. Atinge LOCAL **e** DOCKER (workspace/volume não é git).
- **E2 sandbox read-only** — `codex exec` default = read-only+approval → NÃO escreve: *"patch rejected: writing is
  blocked by read-only sandbox; rejected by user approval settings"*. `CodexArgv` NÃO passa `-s`/approval.
  Atinge LOCAL **e** DOCKER → a skill não publica `.orq/preview/index.html` → **mata o DoD v1/v2**.
- **E3 LOCAL stdin** — `LocalExecutionTarget.run` faz `new ProcessBuilder(argv).start()` sem fechar/redirecionar
  o stdin do filho (default = pipe aberto) → `codex exec` TRAVA lendo stdin. Atinge LOCAL. DOCKER ok
  (`DockerExecutionTarget.buildArgs` não passa `-i` → container sem stdin).

## §B — FLAGS CONFIRMADOS (empírico, NÃO chute)
- **DOCKER** (container = sandbox externo, ADR-0023): `--skip-git-repo-check --dangerously-bypass-approvals-and-sandbox`.
  O flag é *literalmente* "intended solely for environments that are externally sandboxed".
- **LOCAL** (host dev-only): `--skip-git-repo-check -s workspace-write`. PROVADO escrevendo arquivo, exit 0, zero approval.
- Ordem no argv: flags entram DEPOIS de `exec --json` e ANTES do prompt (o prompt é SEMPRE o último, posicional).

## §C — DESIGN (codebase-design): a política mora no seam ExecutionTarget
Política de sandbox/trust NÃO é gramática de motor nem flag solto — é propriedade do **ambiente**. Logo:
- `ExecutionTarget` (`backend/.../execution/runtime/ExecutionTarget.java`) ganha uma **postura de isolamento**
  (sugestão: enum `IsolationPosture { EXTERNALLY_SANDBOXED, HOST }`). **Default fail-safe = HOST** (self-sandbox);
  `DockerExecutionTarget` sobrescreve p/ `EXTERNALLY_SANDBOXED`; `LocalExecutionTarget` = HOST (default). Agnóstico de motor.
- `CodexArgv.build(prompt, model, effort, posture)` permanece o **dono único** dos flags do codex:
  EXTERNALLY_SANDBOXED → `--skip-git-repo-check --dangerously-bypass-approvals-and-sandbox`;
  HOST → `--skip-git-repo-check -s workspace-write`. (Módulo profundo: todo flag do codex num lugar; posture = input mínimo.)
- `CodexAdapter.execute` fia a posture via `context.target().posture()` → passa a `CodexArgv.build`.
  `ClaudeArgv`/`ClaudeCodeAdapter` NÃO mudam (claude não consome posture).
- **E3**: em `LocalExecutionTarget.run`, `localProcess.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))`.
  Beneficia todo motor no LOCAL; DOCKER intocado.
- **Zero toque** em skills-path (`.claude/skills`), `.orq/preview/*`, ou qualquer contrato provado.

## §D — RISCOS → ESTRATÉGIAS
- bypass no DOCKER dá poder total ao codex dentro do container (rede+creds) = MESMO risco já aceito p/ claude
  (roda com creds+rede) → consistente com ADR-0023; registrar no ADR-0025.
- tocar seam provado (ExecutionTarget) → default fail-safe HOST + método aditivo; TDD; rodar piso **≥328**.
- stdin→/dev/null afeta claude LOCAL → claude não usa stdin → seguro (validar no run).
- LOCAL usa `workspace-write` (não bypass) → limita blast-radius ao workspace no host (LOCAL dev-only, ADR-0016). Consciente.

## §E — EXECUÇÃO (TDD, nesta ordem)
1. **ADR-0025** `docs/adr/0025-completar-adapter-codex-sandbox-por-posture.md` — decisão-átomo + racional (B/C/D acima),
   referencia ADR-0024 (achados) e 0016/0023. Status Aceito.
2. **TDD `CodexArgvTest`** (`backend/src/test/java/com/orquestrador/execution/adapter/CodexArgvTest.java` — confirmar path):
   EXTERNALLY_SANDBOXED → argv contém `--skip-git-repo-check` e `--dangerously-bypass-approvals-and-sandbox`;
   HOST → `--skip-git-repo-check` e `-s workspace-write`; prompt continua último; model/effort mantêm regras atuais.
   Red→green em `CodexArgv.build`.
3. **`ExecutionTarget.posture()`** (default HOST) + override `EXTERNALLY_SANDBOXED` em `DockerExecutionTarget` (+ testes triviais).
   Fiar `context.target().posture()` em `CodexAdapter.execute`.
4. **`LocalExecutionTarget.run`**: redirecionar stdin de `/dev/null` (fix E3).
5. `cd backend && mvn -o test` → **piso ≥328 verde**. Rebuild jar (`mvn -o -DskipTests package`), matar pid do :8080 e subir o novo.
6. **Retomar o smoke** (§F).
7. **F.11e docs**: atualizar este handoff com o resultado — "Codex ganha de graça" vira FATO (ou bug remanescente).

## §F — RETOMAR O SMOKE (F.11a → F.11e)
Modelo (provado por evidência nesta sessão): run prompt = `agent.runtime_command` (`AdapterRunExecutor.java:142`);
skills materializam de `agent_skill_selection` (nível agente) ∪ `issue_skill_attachment` (nível issue); cobaia
dispensa `agent_api_key` (6fcaff70 tem 0 e roda). Preview capturado no settle; risk 5 → PENDING_REVIEW → aprovar VENDO → DONE.

**F.11a — Smoke LOCAL** (cobaia `smoke-codex-local`, codex+LOCAL). SQL (dollar-quote; `docker exec -i`):
```sql
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO agent (id, company_id, name, role, adapter_type, execution_target, runtime_command)
VALUES (gen_random_uuid(), '142f1ac5-16d0-4e75-acde-fbad93a666c3', 'smoke-codex-local', 'executor',
        'codex', 'LOCAL',
        $cmd$Você tem skills instaladas em .claude/skills/ — use a que se aplicar. Crie um protótipo de um quadro kanban de tarefas.$cmd$)
RETURNING id AS cobaia_id \gset
INSERT INTO agent_skill_selection (id, company_id, agent_id, company_skill_id, pinned_version_id)
VALUES (gen_random_uuid(), '142f1ac5-16d0-4e75-acde-fbad93a666c3', :'cobaia_id',
        'ddc4f1a3-4f0d-4957-872f-66239fcee19a', 'c9b18703-f360-46a7-a636-551dde089ea3'); -- criar-prototipo
INSERT INTO issue (id, company_id, title, description, status, assignee_agent_id, risk_level)
VALUES (gen_random_uuid(), '142f1ac5-16d0-4e75-acde-fbad93a666c3',
        $t$Protótipo: quadro kanban — smoke F11a (codex LOCAL)$t$,
        $d$Smoke segundo motor codex+LOCAL: prompt neutro, valida skill+preview+parser no run real.$d$,
        'OPEN', :'cobaia_id', 5)
RETURNING id AS issue_id \gset
COMMIT;
\echo '>>> cobaia_id =' :cobaia_id
\echo '>>> issue_id  =' :issue_id
```
Wakeup (NUNCA INSERT em agent_wakeup_request):
`curl -s -X POST localhost:8080/api/companies/142f1ac5-16d0-4e75-acde-fbad93a666c3/agents/<cobaia_id>/wakeups -H 'Content-Type: application/json' -H 'X-Board-User: b0a7d000-0000-4000-8000-000000000001' -d '{"issueId":"<issue_id>","reason":"smoke F11a"}'`
Verificar: run SUCCEEDED (`heartbeat_run`); StepTrace legível (`heartbeat_run_event`: THREAD.STARTED, TURN.*, ITEM.* — 1 STDOUT benigno "Reading additional input from stdin…"); `thread_id` no trace; `cost_event` provider=openai com tokens (model pode ser null — ok); **codex leu a skill** e publicou `.orq/preview/index.html`; preview v1 capturado (`preview_artifact`). Subir dev server :3000 e aprovar v1 VENDO → DONE.
**Se codex ignorar a skill** → fallback JÁ decidido (ADR-0024 dec.2): `SkillMaterializer` escreve também `AGENTS.md` na raiz do workspace, INCONDICIONAL (não condicionar a motor), teste no `SkillMaterializerTest`. (Hoje NÃO existe AGENTS.md no código.)

**F.11b — Login do container** (operador presente): `bash infra/engines/setup-codex.sh` — device-auth no browser (uma vez).
NUNCA copiar auth.json do host (ADR-0002). O script cria o volume `orq_codex_creds` e verifica `auth.json` nele.

**F.11c — Smoke DOCKER v1**: cobaia definitivo `smoke-codex-docker` (codex+**DOCKER**), MESMO prompt neutro,
skill criar-prototipo (`agent_skill_selection` → company_skill `ddc4f1a3…`/csv `c9b18703…`). Issue risk 5 → wakeup → run DOCKER real
→ preview v1 → aprovar VENDO → DONE. Conferir DURANTE o run: container monta SÓ `orq_codex_creds`.

**F.11d — Smoke DOCKER v2 (servidor)**: mesma cobaia DOCKER, issue nova, skill **criar-app-servidor**
(`agent_skill_selection` → company_skill `95f98f47…`/csv `c84f6fb8…`; prompt "app de notas com servidor e API própria")
→ `.orq/preview/server.json` → manifest no settle → start pelo board → app viva no iframe (fetch à própria API) → aprovar VENDO → DONE.
Verificar ADR-0023: 4º container SEM credencial, `:ro`+tmpfs, porta só loopback, caps; TTL/stop derruba.
(Loop v2-mesma-issue: review REJECT → unblock REOPEN → re-run → APPROVE. DONE não reabre.)

## §G — AMBIENTE (fatos)
- Backend jar no `:8080` (health `/actuator/health` UP; era pid 13741 → após rebuild é OUTRO pid, conferir `lsof -iTCP:8080`). Flyway V21.
- DB: container **`agente-orquestrator-db-1`** (ATENÇÃO: "orquestrator", sem 'd'); `docker exec -i agente-orquestrator-db-1 psql -U orquestrador -d orquestrador` (user/db = "orquestrador", com 'd'). Use `-i` p/ heredoc.
- teams-demo company `142f1ac5-16d0-4e75-acde-fbad93a666c3` · board user `b0a7d000-0000-4000-8000-000000000001`.
- Cobaia claude `6fcaff70-f3d1-4b0c-88df-d50c3eeb849e` — NÃO tocar (fonte do prompt neutro; já copiado acima).
- Skills teams-demo: criar-prototipo `cs=ddc4f1a3-4f0d-4957-872f-66239fcee19a`/`csv=c9b18703-f360-46a7-a636-551dde089ea3`;
  criar-app-servidor `cs=95f98f47-c94e-4339-bcb6-1d8b20adbb4c`/`csv=c84f6fb8-f84c-4e2e-88b2-f03536900979`.
- engine_catalog: adapter_type `codex`, model `gpt-5.5` — mas cobaia usa model/effort NULL (default do plano, ADR-0024).
- Imagens: `orq-claude-code:2.1.201`, `orq-codex:0.142.5` (pin travado por `EngineContainerPinTest` — NÃO bumpar sem sintoma).
  Volume `orq_codex_creds` NÃO existe até F.11b. Host: `codex 0.142.5` logado (`~/.codex/auth.json`, sem config.toml).

## §H — GOTCHAS (sangue)
- Wakeup por API (header `X-Board-User` literal — zsh não faz word-splitting) · NUNCA INSERT em `agent_wakeup_request`.
- `docker exec -i` para heredoc (sem `-i` engole o SQL) · dollar-quote `$cmd$…$cmd$` p/ runtime_command.
- `DONE` não reabre: REJECT → unblock REOPEN → re-run → APPROVE.
- Runs reais ~2-4min (poll com folga) · `timeout` NÃO existe no macOS (é `gtimeout`) · probes de codex direto no host: SEMPRE `</dev/null` (senão trava no stdin).
- Container do run codex herda o PATH do jar; `codex` está em `/opt/homebrew/bin` — se o run LOCAL falhar "Cannot run program codex", é PATH do java.

## §I — FORA DO ESCOPO / não reabrir
- Retomada de sessão (`--resume`/thread_id de volta) — ausente e simétrica nos dois motores; fatia própria.
- UI de model/effort · rate limit/quota · codex LOCAL como alvo de produção (dev-only).
- Menu Fase 2 (GC, thumbnail, redação, edição no board, multi-arquivo v1, recortes v2). Não limpar smokes vivos da teams-demo.
- NÃO reabrir ADRs 0002/0014/0015/0016/0021/0022/0023/0024. O parser NÃO muda.

## §J — CRITÉRIOS DE ACEITE
1. ADR-0025 escrito (Aceito) com racional + riscos R2/R6.
2. `CodexArgv` posture-aware (TDD verde): EXTERNALLY_SANDBOXED→[skip-git,bypass], HOST→[skip-git,workspace-write]; prompt último.
3. `ExecutionTarget.posture()` (default HOST, DOCKER=EXTERNAL) + `LocalExecutionTarget` stdin `/dev/null`.
4. Suíte backend **≥328 verde**; front intocado; zero mudança em contrato provado (skills-path, `.orq/*`, parser).
5. Run LOCAL codex SUCCEEDED: StepTrace legível, thread_id, custo provider=openai; **skill em uso**; preview v1 aprovado VENDO → DONE.
6. `orq_codex_creds` vivo com auth.json (login nascido no container).
7. Run DOCKER codex SUCCEEDED montando SÓ `orq_codex_creds`; preview v1 VENDO → DONE.
8. Preview v2 (servidor) codex: app viva no board, aprovado VENDO → DONE, com as verificações de segurança do ADR-0023.
9. ADR-0015: ao cruzar ~50-60% do contexto, destilar handoff parcial e recomeçar.
