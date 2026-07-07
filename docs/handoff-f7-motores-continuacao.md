# Handoff — F.7 "Motores" CONTINUAÇÃO (backend-first, pós-fundação)

> Sessão de 2026-07-03 aplicou o grilling `handoff-grilling-motores-fechado.md`: **fatia A (fundação)** feita e
> verde, **ADR-0014/0015** criados. Esta é a passagem de turno (regra de ouro ADR-0015 — destilar a 50-60%, não
> arrastar a sessão pelos adapters). **FONTE DE VERDADE das decisões:** `docs/handoff-grilling-motores-fechado.md`
> (seções A/B/C/D). Aqui ficam só o **estado exato** + os **shapes já aterrados** (pra não re-grelhar o código).

## 0. Estado (fatos aterrados)
- **Piso backend 114/114 offline** (`mvn -o test`). Postgres real V12/V13 **NÃO validado ainda** (fazer na próxima).
- **Fatia A DONE:** `V12__agent_engine.sql` (colunas `model`/`effort` nullable) + `V13__engine_catalog.sql`
  (tabela + seed 7 combinações via `gen_random_uuid()`); `Agent` +`model`/`effort`/`configureEngine`; `AgentController`
  `CreateAgentRequest`/`AgentResponse` +`model`/`effort` + **`PATCH /agents/{agentId}`** (`updateEngine`); testes
  `AgentEngineTest`(5)+`AgentEngineHttpTest`(2). ADR-0014 + ADR-0015 escritos.
- **Testes = H2 + Hibernate `create-drop` (schema das ENTIDADES, Flyway OFF)** — migrations só validam no PG real.
  Logo: o **seed do V13 NÃO existe no H2** → testes do catálogo semeiam via repo. `gen_random_uuid()` roda só no PG16.
- **`AdapterRunExecutor` é instanciado via Spring nos testes** (zero `new` manual) → adicionar `BudgetService` ao
  construtor é transparente.

## 1. Shapes do seam (JÁ LIDOS — não re-ler)
- `AgentAdapter` (`execution/adapter/AgentAdapter.java`):
  - `record Context(companyId, agentId, runId, issueId, workDir:Path, command:String, env:Map, target:ExecutionTarget,
    image:String, volumeName:String, priorSession:String)` → **fatia B: add `model`, `effort` (String)**.
  - `record Result(boolean succeeded, String error, String session)` + `ok(session)` / `fail(error)` →
    **fatia B: add `costEvents` (List<CostEmission>)** + `record CostEmission(provider, model, tokensInput, tokensOutput)`;
    preservar `ok(session)`/`fail(error)` (defaultam `costEvents=List.of()`) + novo `ok(session, costEvents)`.
  - `interface StepSink { void emit(String type, String detail); }`.
- `AdapterRunExecutor.runToOutcome` (linha ~91): monta o Context com `agent.getRuntimeCommand(), Map.of(), target,
  "alpine:3", volumeName, null`. **Fatia B: injetar `agent.getModel()`/`agent.getEffort()`; após `adapter.execute`,
  rotear `result.costEvents()` → `budget.recordCost(...)` (success E fail — tokens gastos contam).**
- `BudgetService.recordCost(companyId, agentId, runId, issueId, provider, model, costCents:long, tokensIn:int,
  tokensOut:int) : CostEvent` — `enforce()` só abre incidente se houver **policy** E `spent >= limit` → **`costCents=0`
  é inócuo** (Q7). `CostEvent` já tem `provider/model/tokensInput/tokensOutput` (só faltam getters se precisar).
- `ExecutionTarget.run(Spec{workDir:Path, command:List<String>, env:Map, image, volumeName}, Consumer<String>
  onStdoutLine) : Result{exitCode:int, error}` — streama stdout **linha-a-linha**. `LocalExecutionTarget` =
  `ProcessBuilder` com `redirectErrorStream(true)` (stderr→stdout), **herda o env do host** (é assim que o `claude`/
  `codex` logados respondem no LOCAL).
- `AdapterRegistry(List<AgentAdapter>)` auto-registra por `type()`; default `"process"`; desconhecido → `IllegalStateException`.
  **ClaudeCodeAdapter/CodexAdapter = só `@Component` + `type()="claude-code"|"codex"`** (auto-registram).
- `ProcessAgentAdapter` (arquétipo): monta `Spec(workDir, List.of("sh","-c",command), env, image, volumeName)`,
  `target.run(spec, line -> steps.emit("STDOUT", line))`, exit 0 → `Result.ok(priorSession)`.
- `RunOutcome(succeeded, error)` — **sem erro tipado**; `model_refusal`/`transient_upstream` do ADR-0004 = fatia futura.
  No v1 a recusa vira `Result.fail("<mensagem>")` (fail-soft, string).

## 2. Próximas fatias ORDENADAS (seção C do fechado)
- **B — Context+Result+executor+cost** (mecânica, unblocka adapters): estender Context/Result acima; injetar model/
  effort; rotear costEvents. Testes: Context carrega model/effort; costEvent costCents=0 não dispara incidente.
- **C — `ClaudeCodeAdapter` E2E (MARCO do adapter rico):** monta argv `claude -p "<command/prompt>" --output-format
  stream-json --verbose --model <model>` + esforço (`--effort <effort>`; `effort="ultracode"` → `--settings
  '{"ultracode":true}'`; null → omite); roda via `target.run`; **parseia stream-json** (cada linha JSON → step
  semântico `steps.emit(<tipo do evento>, <detalhe>)`; linha não-JSON → `"STDOUT"`, fail-soft) e **extrai `usage`/
  tokens**; captura `session_id` → `Result.session`; emite `CostEmission(provider="anthropic", model, tokensIn,
  tokensOut)`. Testes: argv correto (incl. ultracode→--settings, null→omite); parser de fixture JSONL → steps + tokens;
  erro → fail-soft. **NÃO dá pra rodar o CLI real no teste** — testar o **argv builder** + o **parser** isoladamente
  (módulos profundos atrás de seams pequenos, regra de ouro ADR-0010). Considerar extrair `ClaudeStreamParser` puro.
- **D — `CodexAdapter`:** reusa o padrão; `codex exec --json --model <model> -c model_reasoning_effort=<effort>
  "<prompt>"`; eventos JSONL `thread.started`/`turn.completed`/`item.*`; `provider="openai"`.
- **E — `engine_catalog`:** entidade + repo + `GET /api/companies/{id}/engines` (board-only, `assertBoardMember`),
  só `enabled=true`, ordenado. Teste semeia via repo (H2 não tem o seed).
- **F — docs restantes:** emendar ADR-0002/0004/0006 (textos prontos na §B do fechado) + ADR-0016 stub (reservado F.8)
  + CONTEXT.md (Motor, Adapter refinado, Session refinado, Handoff de contexto, engine_catalog, ExecutionTarget).
- **G — frontend (fase 2):** `lib/api.ts` `Engine{adapterType,model,effort,contextWindow,label}` + fetch `/engines`;
  `AgentSummary`/`HireAgentInput` +`model`/`effort`; `hire-agent-dialog` troca o hardcode `process` por **dropdown
  "Motor"** (itens = catálogo, selecionar preenche os 3); `agents/[agentId]` motor editável no kv-panel (PATCH);
  `runtime` do detail e a `roster-cell` mostram o **label do motor** + target. **Reconvocar as 4 skills de design.**

## 3. Fechamento (fatia densa — não pular)
Rito completo: **TDD** por sub-fatia → piso (`mvn -o test` + `next build`) → **validação Postgres real** (subir db,
Flyway V1→V13 incremental **success**, `ddl-auto=validate` PASS, seed do V13 presente, motor ponta-a-ponta se der p/
logar um CLI no host) → **revisão adversarial 3-lentes** (correção/segurança do argv+parsing, contrato do seam,
regra-de-ouro) → memória `agente-orquestrator-stack.md`. **F.7 antes da passada de consistência pós-F.6.**

## 4. Riscos/limites já cravados (ADR-0014/§D do fechado)
v1 **LOCAL, não isola** (badge na UI); **fixar a versão do `claude`** (risco `--bare` vira default de `-p` e exige API
key); rate-limits de plano não gerenciados; fresh-session (memória real = handoff, ADR-0015, fatia futura); Docker/
isolamento por container = **F.8/ADR-0016** (esquema validado por deep-research; login nasce no container, nunca copiar
do host; ToS: self-hosted-pessoal ✅ vs SaaS-roteando-assinatura ❌).
