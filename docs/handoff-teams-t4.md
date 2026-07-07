# Handoff — Teams T.4 COMPLETA (contenção: spawn_lineage + fan-out cap + ledger de orquestração)

> Sessão 2026-07-04. Backend-first TDD (RED confirmado em toda peça). Fatia SEM tela e SEM
> endpoint novo (superfície de limites = fatia futura). **Com T.4, a frente Teams fecha o v1**;
> o caminho segue pro isolamento real (F.8/container, ADR-0016). Repo SEM git — nada commitado.

## Piso (verificado)
- Backend **237/237** (`cd backend && mvn -o test`) — eram 219; +18 de T.4.
- **Flyway V19 aplicada + ddl-validate PASS** no Postgres real (`pgdata`); jar V19 no `:8080`
  (log `/tmp/orq-v19.log`).
- **Smoke VIVO na `teams-demo`** (tudo pela API board + pipeline real de conversa/approval):
  1. **Loop recusado no checkout**: Atlas propôs `WAKEUP_AGENT` de si mesmo (bloco ```proposta
     na saída do turno real) → APPROVE → **409** `"acordar um ancestral da propria linhagem e
     recusado (spawn_lineage, ADR-0016)"` → Approval seguiu **PENDING** (rollback total, nunca
     meio-commitada). Arquivada com REJECT depois do smoke.
  2. **Fan-out cap**: time `Dev/smoke-t4` (cap=1 via SQL), 2 agentes; 1º wakeup ocupou o slot →
     2º levou **409** `"fan-out do arquiteto no teto (1 runs ativos…)"`.
  3. **Ledger**: `AGENT_SPAWN`×2 (hires do smoke, causador humano), `WAKEUP`×4, `RUN_OPEN`×4 —
     e a prova da HERANÇA: Atlas propôs acordar o executor → APPROVE 200 → o run do executor
     nasceu com `spawn_lineage=[Atlas]`, executou e SUCCEEDED; o `WAKEUP` desse ato caiu na
     **conta do Atlas** (causador), não do aprovador. Recusas (409) não cobram nada.
- Front intocado (piso de T.3-FE segue: vitest 174/174 + tsc + build ✓).

## O que existe agora (mapa de código)
**`execution/lineage/`** (o módulo profundo do grilling; ADR-0010) — `SpawnLineage` (VO imutável:
`root()`/`decode()`/`encoded()`/`descendTo()`/`contains()`/`causer()`/`depth()`; formato da coluna
é PRIVADO: UUIDs minúsculos unidos por `>`, elos tratados como texto opaco — sem modo de falha de
parse) · `SpawnLineageGuard` (`assertWakeupAllowed` = face enqueue 409; `checkoutBlockReason(run)`
= face checkout, **1 método no dispatcher**; `spawnLineageOf(runId, causer)` esconde o lookup do
run proponente; `MAX_DEPTH=5` — 4 níveis reais board→CEO→líder→arquiteto→executor +1 folga).

**`execution/`** — `HeartbeatRun`/`AgentWakeupRequest` ganham `spawn_lineage` (nasce e não muda;
ctor 5-args continua = raiz, testes antigos intactos) · `WakeupService.enqueue` ganha overload
com `SpawnLineage` (4-args = raiz; guard de linhagem ANTES do gate do arquiteto; fan-out SÓ no
branch que cria run novo; cobra `WAKEUP` no ledger após aceitar) · `TeamFanOutGate`
(`assertFanOutAllowed` conta ATIVOS ≥ cap → 409; `fanOutBlocked` conta EM-VOO ≥ cap → preflight)
· `HeartbeatDispatcher` += 2 preflights (linhagem ilegal → cancel; fan-out no teto → cancel) ·
`HeartbeatRunStore.claim` cobra `RUN_OPEN` **só no attempt 1** (reaper re-executa sem re-cobrar);
`promoteIfNeeded` herda a linhagem **mais funda** dos DEFERRED (anti-lavagem; o checkout ainda
julga) · `HeartbeatRunRepository.countRunsOfTeam` (join com Agent por team_id).

**`governance/budget/`** — `OrchestrationAction` (**preços**: AGENT_SPAWN=100 · WAKEUP=10 ·
RUN_OPEN=1 — relativos: spawnar pesa 10 wakeups; wakeup pesa 10 aberturas) · `CostEvent` ganha
`orchestration_action` (nulo = lançamento de token/dólar) + factory `CostEvent.orchestration(...)`
· somas por MOEDA no repository (`sumBy*` filtra `is null`; `sumOrchestrationBy*` filtra
`is not null`) · `BudgetService.recordOrchestration` (espelho reativo do `recordCost`; enforce
contra `orchestration_limit_cents`) + `orchestrationSpentFor*` + `createPolicy` overload com teto
de orquestração (limitCents=0 deixa o dólar inerte — guard >0) · **`drainScopeWorkToCheckpoint`**
(ex-cancelScopeWork): cancela SÓ `QUEUED`+wakeups; RUNNING termina o passo e liquida — matar
RUNNING no banco nunca matou o processo, só jogava o resultado fora; o preflight barra o sucessor.

**`governance/approval/`** — `dispatchAction` WAKEUP_AGENT usa
`spawnLineageOf(approval.runId, requestedByAgentId)` (a HERANÇA); HIRE_AGENT passa
`causedByAgentId=requestedByAgentId` no `HireCommand`.

**`org/`** — `Team.fan_out_cap` (default 5; `definirFanOutCap` recusa ≤0) ·
`AgentHireService.HireCommand` ganha `causedByAgentId` (nulo = mão soberana); o hire cobra
`AGENT_SPAWN` no funil único.

**Migration V19** (`V19__contencao_spawn_lineage_fanout_orquestracao.sql`): `spawn_lineage` nas
duas tabelas (default `''`) · `team.fan_out_cap` (default 5) · `cost_event.orchestration_action`
· `budget_policy.orchestration_limit_cents`.

**Testes novos**: `SpawnLineageTest` (VO) · `DispatchLineageGuardTest` (2 faces + cap + promote
herda + reaper sobrevive) · `ApprovalWakeupLineageTest` (herança + auto-wake 409 e PENDING) ·
`DispatchFanOutCapTest` (2 faces) · `OrchestrationLedgerTest` (moeda separada · RUN_OPEN 1× ·
AGENT_SPAWN na conta do proponente · teto → incidente+pausa) · `BudgetDrainToCheckpointTest`.

## RESIDUAIS DO GRILLING — DECIDIDOS (não reabrir)
1. **WAKEUP_AGENT aprovado HERDA a linhagem do run do CEO proponente** (não abre raiz nova).
   Racional: linhagem é **CAUSALIDADE, não autoria** — no v1 TODO wakeup agente-causado passa
   pelo gate humano por construção; se a aprovação lavasse a cadeia, o guard nasceria letra
   morta. O ator no `activity_log` segue = aprovador (ADR-0018 intacto). Raiz nova = só mão
   soberana DIRETA do operador (`WakeupController` / `postMessage` / drain de turnos).
2. **Dono do ledger de orquestração = o agente CAUSADOR** (último elo da linhagem; nulo = ato
   humano) — espelho do "o agente da Issue escreve" do fechado: a conta cai em quem causou,
   senão o teto do orquestrador nunca acumula. Atos do operador SÃO registrados (observabilidade,
   conta company) — soberania = precedência sobre o CEO, não imunidade ao próprio orçamento.

## Decisões de implementação (não re-derivar)
1. **Coalescing não é fan-out novo**: o cap só é checado quando o enqueue CRIARIA run; re-acordar
   quem já ocupa slot coalescé/deferre sem 409. Preflight conta só o EM-VOO (RUNNING/RECOVERING)
   — os QUEUED à frente são julgados um a um no claim.
2. **Run nasce com a linhagem do wakeup que o criou** e não muda; wakeup mesclado guarda a sua na
   própria linha (o guard já rodou por-wakeup no enqueue). Promote herda a MAIS FUNDA dos
   DEFERRED. Reaper (`requeueExpired`) não toca a coluna.
3. **Recusa não cobra**: os 409 (linhagem/fan-out/arquiteto) fazem rollback do tx inteiro,
   inclusive do lançamento de WAKEUP — o ledger só registra atos aceitos.
4. **O ato que CRUZA o teto ainda acontece** (mesma semântica do dólar): cobra → incidente →
   drena; o que ele criou morre no preflight. Incidente não distingue moeda na linha (consciente;
   `spent/limit` + policy referenciada inferem — coluna `kind` seria superfície sem consumidor).
5. **`AdapterCostRoutingTest` filtra a moeda** ao contar eventos do run (o RUN_OPEN de T.4
   também referencia o run).
6. Preços de orquestração são **constantes no enum** (não config) — o operador calibra pelo
   `orchestration_limit_cents`, não pelos preços.

## Recorte consciente / residuais que seguem abertos
- `BudgetIncident` sem coluna de moeda (item 4 acima). Superfície de LIMITES na tela (cap por
  time, teto de orquestração, recusas visíveis) = fatia futura; a Central docada já observa runs.
- Wakeups do drain de turnos e o RUN_OPEN de runs de turno do CEO são cobrados como atos raiz
  (causador humano) — continuidade de conversa custa barato e visível; revisar se virar ruído.
- Container/isolamento real = F.8 (ADR-0016); WS; comunicação lateral; credencial run-scoped —
  tudo fora, como no recorte da fatia.

## Gotchas (sangue desta sessão)
1. O eco do Atlas na teams-demo é um `printf` FIXO (não ecoa `ORQ_PROMPT`) — pra smoke de
   proposta nova, trocar `runtime_command` via SQL (dollar-quoting `$cmd$...$cmd$`) e RESTAURAR.
2. Teste com `dispatchOnce()==0` DRENA a fila global ANTES (contexto H2 compartilhado) — além do
   `@AfterEach` de quem enfileira sem despachar.
3. RUNNING plantado em teste (sem lease) fica órfão inócuo: o drain só vê QUEUED; não vaza entre
   testes porque times/agentes são locais ao teste.

## Ambiente ao fim
- db `pgdata` em **V19**; jar T.4 no `:8080` (`/tmp/orq-v19.log`); teams-demo com: time
  `Dev/smoke-t4` (cap=1, arquiteto `Smoke Arq T4`), 2 agentes de smoke, 1 approval PENDING
  antiga (demo T.3-FE) + APPROVED/REJECTED do smoke T.4, ledger de orquestração populado.
- Atlas restaurado ao eco original (CREATE_ISSUE de nicho).

## NORTE
A montagem incremental (ADR-0017) segue como freio primário; T.4 é a REDE embaixo que torna
aceitável o CEO com mãos rodando LOCAL. **Frente Teams: v1 FECHADO (T.1 → T.4).** Próximo:
F.8 (container/isolamento real) quando a fila de fatias chamar.
