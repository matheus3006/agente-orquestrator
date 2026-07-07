# Plano de trabalho — F.7 "Motores" fatias D→G + fechamento (pós B/C verdes)

> Sessão de 2026-07-03 (continuação) aplicou as **fatias B e C** sobre a fundação (fatia A) e SEGUE na MESMA
> sessão (janela em ~22% — longe do gatilho de 50-60% do ADR-0015, que é da janela de contexto real). Este doc é
> o **plano de execução** de D→G, não uma passagem de turno. **FONTE DE VERDADE das decisões:**
> `docs/handoff-grilling-motores-fechado.md` (A/B/C/D). O handoff de entrada da sessão B/C:
> `docs/handoff-f7-motores-continuacao.md`. Aqui: **estado exato** + **shapes já aterrados** (não re-derivar) +
> as fatias **D→G + fechamento** ordenadas.

## 0. Estado (fatos aterrados)
- **Piso backend 135/135 offline** (`mvn -o test`, do dir `backend/`). Frontend intocado nesta leva (fase 2 = G).
- **Postgres real de V12/V13 AINDA NÃO validado** — fica no **fechamento do backend** (após D/E), junto com a
  adversarial 3-lentes. V12/V13 existem desde a fatia A (`gen_random_uuid()` só roda no PG16, não no H2).
- **Fatias DONE:** A (schema+colunas+controller), **B** (seam Context/Result+cost), **C** (ClaudeCodeAdapter).
- **Testes = H2 + Hibernate `create-drop`** (schema das ENTIDADES, Flyway OFF). Seed do V13 NÃO existe no H2 →
  o teste da fatia E semeia `engine_catalog` via repo.

## 1. Shapes JÁ ATERRADOS (fatias B/C — NÃO re-ler o código, só consumir)
- **Seam (`execution/adapter/AgentAdapter.java`):**
  - `Context(companyId, agentId, runId, issueId, workDir, command, env, target, image, volumeName, priorSession,
    model, effort)` — **13 componentes** (model/effort String nullable no fim).
  - `Result(succeeded, error, session, List<CostEmission> costEvents)` — **construtor compacto** blinda
    (null→`List.of()`, senão `List.copyOf` → nunca-nulo/imutável). Factories: `ok(session)`, `ok(session, costEvents)`,
    `fail(error)`, **`fail(error, costEvents)`** (tokens contam na falha).
  - `record CostEmission(String provider, String model, int tokensInput, int tokensOutput)`.
- **`AdapterRunExecutor`:** injeta `agent.getModel()`/`getEffort()` no Context; após `adapter.execute`,
  `recordCosts(...)` roteia CADA emissão → `budget.recordCost(company,agent,run,issue, provider,model, 0L,
  tokensIn,tokensOut)` — ANTES do branch succeeded (sucesso E falha). `BudgetService` no construtor (one-way, sem
  ciclo). Adapter que LANÇA (não retorna Result) não roteia — quem quer contabilizar devolve `fail(error, costEvents)`.
- **`CostEvent`:** ganhou getters `getRunId/getProvider/getModel/getTokensInput/getTokensOutput`.
  `CostEventRepository.findByRunId(UUID)`.
- **Padrão do adapter rico (fatia C, o MOLDE que D reusa):** três módulos flat em `execution.adapter`:
  - **`ClaudeArgv`** (pura, package-private, `static build(prompt,model,effort)→List<String>`): argv DIRETO ao
    ProcessBuilder (SEM shell — prompt é UM elemento). Regras do esforço moram só aqui.
  - **`ClaudeStreamParser`** (pura, package-private, stateful, `Step accept(String line)` + getters
    `session()/model()/tokensInput()/tokensOutput()/sawUsage()`; `record Step(type, detail)`): JSON→step semântico
    (type nativo UPPER + detalhe), não-JSON/branco→`STDOUT` fail-soft (catch `JsonProcessingException`), usage
    last-wins, tokensInput soma cache. Jackson `ObjectMapper.readTree` (static).
  - **`ClaudeCodeAdapter`** (`@Component`, `type()="claude-code"`, raso): costura argv+parser+`context.target().run`
    linha-a-linha; no-op verde sem prompt; exit 0→`ok(session, costs)` / exit≠0→`fail(error, costs)`;
    `CostEmission(provider="anthropic", model-do-stream??pedido, tokens)` só se `sawUsage`. Auto-registra no
    `AdapterRegistry` (que faz `Collectors.toMap` por `type()` — colisão de type ESTOURA no boot).
  - **Teste sem Spring:** um `FakeTarget implements ExecutionTarget` (streama fixture JSONL, exit configurável,
    captura o `Spec`) + `RecordingSink implements StepSink` → prova argv + steps + session + CostEmission
    isoladamente (o CLI real NÃO roda no teste — regra de ouro ADR-0010). Copiar esse molde na fatia D.

## 2. Fatias ORDENADAS (D→G) + fechamento

### D — `CodexAdapter` (reusa 100% o molde da C; provider="openai")
- **`CodexArgv.build(prompt, model, effort)`:** `codex exec --json` + `--model <model>` (só se não-branco) +
  `-c model_reasoning_effort=<effort>` (só se não-branco; **um par `-c chave=valor`**, não flag dedicada) + o
  **`<prompt>` posicional no fim**. `null`→omite cada flag. Sem caso especial tipo ultracode (o esforço do Codex é
  direto). Direto ao ProcessBuilder (sem shell).
- **`CodexStreamParser`:** JSONL do `codex exec --json` — eventos `thread.started` (traz `thread_id`/session →
  `Result.session`), `turn.completed` (traz `usage` — inspecionar o shape real: provável `input_tokens`/
  `output_tokens` ou `usage.input`/`.output`), `item.*` (mensagens/tool calls → steps semânticos). Mesmo contrato
  do `ClaudeStreamParser` (Step + acumuladores + `sawUsage`); não-JSON→`STDOUT` fail-soft. **O shape exato do
  `usage`/session do Codex NÃO foi aterrado por fixture real** — o grounding deu os NOMES dos eventos, não o JSON
  campo-a-campo; montar a fixture do teste a partir do formato documentado do `codex exec --json` e deixar o parser
  robusto (campos ausentes→0, fail-soft). Marcar como risco explícito p/ a adversarial.
- **`CodexAdapter`** (`@Component`, `type()="codex"`): idêntico ao ClaudeCodeAdapter trocando argv/parser e
  `provider="openai"`.
- **Testes:** `CodexArgvTest` (model/effort/`-c`; null omite; prompt posicional no fim), `CodexStreamParserTest`
  (fixture JSONL→steps+session+tokens; não-JSON→STDOUT), `CodexAdapterTest` (FakeTarget→argv+steps+CostEmission
  openai; falha carrega tokens; sem-comando no-op).

### E — `engine_catalog` (entidade + repo + endpoint)
- Entidade `EngineCatalog` mapeando a tabela da **V13** (`id, adapter_type, model, effort, context_window, label,
  enabled, created_at`); **DECLARAR na entidade** o que o H2 precisa forçar (índice `enabled` é PG-only, mas o
  mapeamento de colunas/tipos vale p/ o create-drop). Repo `findByEnabledTrueOrderBy…` (só habilitados, ordenado).
- `GET /api/companies/{id}/engines` **board-only** (`assertBoardMember` — o guard elevado da F.0). Global (o
  catálogo não tem `company_id`), mas a rota é company-scoped por consistência das superfícies (como as outras).
  Resposta = lista de `{adapterType, model, effort, contextWindow, label}` (o `enabled=false` não sai).
- **Teste (H2 não tem o seed):** o teste **semeia via repo** algumas linhas, chama o endpoint board→200 com a lista
  ordenada só-habilitados, agente/cross-tenant→403. Espelha `KnowAllSearchHttpTest`/`AgentEngineHttpTest`.

### F — docs (textos prontos na §B do fechado)
Emendar **ADR-0002** (Revisão: `--resume` bruto secundário ao handoff), **ADR-0004** (Context +model/effort;
Result.costEvents; StepTrace semântico + usage; "zero mudança no core" confirmado), **ADR-0006** (hard-stop de
dólares inócuo na assinatura; ledger acumula tokens; governador real = rate-limit+contexto, fatia futura). Criar
**ADR-0016 stub** (reservado F.8 — Docker/isolamento; esquema validado na §D do fechado). Atualizar **CONTEXT.md**
(Motor/Engine, Adapter refinado, Session refinado, Handoff de contexto, `engine_catalog`, ExecutionTarget LOCAL-only).

### Fechamento do BACKEND (fatia DENSA — antes do frontend)
1. **Validação Postgres real:** `docker compose up -d db` (volume `pgdata` intacto em V11) → `cd backend && mvn -q
   clean package -DskipTests` (precisa de rede pros plugins de package) → `pkill -f orquestrador-0.0.1-SNAPSHOT.jar`
   → `java -jar backend/target/orquestrador-0.0.1-SNAPSHOT.jar`. Provar: **Flyway V1→V13 incremental success**
   (V11→V12→V13), **`ddl-auto=validate` PASS** (colunas `model`/`effort` na `agent` + tabela `engine_catalog` batem
   as entidades), **seed do V13 presente** (as 7 combinações via `gen_random_uuid()`), `GET /engines` servindo o
   seed real. Motor ponta-a-ponta SÓ se der p/ logar um CLI no host (opcional). `docker compose down` (mantém volume).
2. **Revisão adversarial 3-lentes** (workflow, como as fatias densas anteriores): lentes = **(a) correção/segurança
   do argv+parsing** dos DOIS CLIs (prompt posicional; ultracode→settings; `-c` do codex; fail-soft; token summing),
   **(b) contrato do seam** (roteamento sucesso E falha; costCents=0 inócuo; sem ciclo de bean), **(c) regra de ouro**
   (nomes que carregam o fluxo; módulos profundos ClaudeArgv/Parser atrás de seams mínimos).
   - **Riscos JÁ anotados p/ mirar:** (i) prompt POSICIONAL entre `-p` e `--output-format` no Claude assume parser
     tolerante — verificar contra a versão FIXADA do CLI (risco `--bare` do ADR-0014); (ii) `tokensInput` somar cache
     (input+cache_read+cache_creation) foi escolha p/ o denominador do handoff (ADR-0015) — a review pode preferir só
     `input_tokens` p/ "custo"; (iii) shape do `usage`/session do **Codex** montado por doc, não por fixture real.

### G — frontend (fase 2; consome o backend fechado)
`lib/api.ts` `Engine{adapterType,model,effort,contextWindow,label}` + fetch `/engines`; `AgentSummary`/`HireAgentInput`
+`model`/`effort`; `hire-agent-dialog` troca o hardcode `process` por **dropdown "Motor"** (itens = catálogo,
selecionar preenche os 3 eixos); `agents/[agentId]` motor editável no kv-panel (**PATCH /agents/{id}** já existe da
fatia A); `runtime` do detail e a `roster-cell` mostram o **label do motor** + target. **Reconvocar as 4 skills de
design** (`/bencium-impact-designer`, `/bencium-innovative-ux-designer`, `/emil-design-eng`, `/ui-ux-pro-max`) SÓ no
dropdown "Motor" (única divergência justificada da spec congelada P.1). Piso frontend: `npm run typecheck && lint &&
test && build` + verificação viva (Preview MCP vs backend real).

## 3. Rito por fatia + ambiente
- **Rito:** TDD (RED confirmado por `mvn -o test-compile` ANTES) → piso `mvn -o test` offline → **memória
  `agente-orquestrator-stack.md` ao fim de CADA fatia**. PG real + adversarial 3-lentes = **no fechamento do
  backend** (não a cada sub-fatia; D/E são finas como B foi).
- **Regra de ouro ADR-0015:** ao cruzar ~50-60% da janela, destilar handoff e recomeçar (foi o que gerou este doc).
- **Ambiente:** rodar do dir `backend/`. `mvn -o test` = offline (H2). Board user dev:
  `b0a7d000-0000-4000-8000-000000000001`. Companies demo: `demo-14262` (Atlas/Nix/Echo), `playground-lab` (Vega).
  Repo SEM git → **não commitar**.
- **Invariantes travadas (não reabrir):** motor = 3 eixos (adapterType decide o plano; model/effort String livre
  validada no runtime pelo adapter, nunca enum; null=default); custo `costCents=0`; v1 LOCAL não-isola;
  adapter encapsula a gramática do seu CLI (ADR-0010).
