# ADR-0029: retenção do rastro (run_events) dos turnos de conversa do CEO — a 2ª passada do RowRetentionReaper

**Status:** Aceito (2026-07-06, execução F.15 — `docs/handoff-grilling-retencao-rows-conversa-fechado.md`)

## Contexto
A F.14 (ADR-0028) capou o crescimento de `heartbeat_run_event` **só** para os runs LIGADOS a uma
Issue terminal-vencida, e deixou explicitamente fora do v1 (ADR-0028 §B5/§H) o **2º acúmulo real**
de `run_event`: o rastro dos runs **sem Issue** — os turnos de conversa do CEO. Cada mensagem do
board ao CEO vira **1 turno = 1 Run** (`V16:2-3`) com `issue_id` NULL, e esse Run deixa um rastro
passo-a-passo em `heartbeat_run_event` que a passada de Issue nunca varre (só coleta eventos de runs
obtidos por `findByIssueId…`). ADR-0028 antecipou a solução como "âncora diferente, sub-política
própria". Esta ADR é essa sub-política.

O dado **durável** de um turno — o texto (`conversation_turn.owner_message`/`ceo_reply`) e o handle
do motor (`agent_session`) — mora em tabelas que a faxina JAMAIS toca. O `heartbeat_run_event` de um
turno é puro rastro diagnóstico ("como o CEO pensou"), a 1 clique pelo `run_id` (o StepTrace). Apagar
esse rastro é **ortogonal** à memória do CEO: `--resume` está desligado (ADR-0002/`AdapterRunExecutor`),
`agent_session` é write-only, e o prompt do turno é remontado do estado vivo — o rastro **não
alimenta** o contexto. Some o "como pensou"; ficam o "o que foi dito" e o Run.

## Decisão
Uma **2ª passada** dentro do mesmo `RowRetentionReaper.sweepCompany` — não um reaper irmão — que
coleta os `heartbeat_run_event` dos runs que são turnos de conversa **vencidos**, reusando relógio
(`DatabaseClock`), chunk, auditoria (`ROWS_COLLECTED`/`SYSTEM`) e schedule da F.14. **Zero migração,
zero índice novo; só `heartbeat_run_event` é tocado.**

### D1 — Âncora por-TURNO (cada rastro vence sozinho)
Cada rastro gradua X dias depois que **aquele turno** terminou, não enquanto a conversa estiver ativa.
O texto já está salvo em `conversation_turn`; não há motivo pra amarrar o rastro à atividade da
conversa. Espelha a F.14 (prazo por unidade terminal). **Rejeitada:** âncora por-conversa — exigiria
descobrir "última atividade de cada conversa" (dependência nova) para pouco ganho; uma conversa ativa
por 1 ano guardaria rastro de 1 ano à toa.

### D2 — Carimbo = `COALESCE(finished_at, created_at)` do Run (a divergência consciente da F.14)
Vencido = `COALESCE(run.finished_at, run.created_at) < now − retenção`. **Aqui está a "âncora
diferente" que a F.14 apontou.** Na F.14 um item não-terminal é trabalho VIVO (protegido); um turno de
conversa não-terminal de 30 dias é **lixo abandonado** (turnos duram minutos, não dias), então limpar
é o certo. `finished_at` é o carimbo terminal (setado em `finishSuccess`/`finishFailure`); o fallback
`created_at` varre o turno "fantasma" (backend caiu sem carimbar `finished_at`) — robusto a haver ou
não recuperador de runs presos. Um turno rodando/recente tem `created_at` recente → `COALESCE < cutoff`
FALSO → **nunca tocado** (proteção do em-voo por construção, sem guard de status). **Rejeitada:** só
`finished_at` (fiel à F.14) — deixaria o fantasma sem carimbo com rastro eterno.

### D3 — Estender o RowRetentionReaper (2ª passada), não um reaper irmão
Mesmo trabalho (limpar rastro de Run), mesma engrenagem (`DatabaseClock`, drain em chunk, evento
`ROWS_COLLECTED`), mesma família de config. A 2ª passada é aditiva dentro de `sweepCompany`, guardada
à parte (best-effort isolado) para que uma falha nela não aborte a company nem esconda a auditoria da
passada de Issue já removida (ADR-0028 B7). **Rejeitada:** `ConversationRunEventReaper` irmão —
duplicaria andaime (schedule/chunk/audit) e criaria 2ª fonte de `ROWS_COLLECTED` pra reconciliar.

### D4 — Alvo = SÓ os turnos de conversa (via `conversation_turn.run_id`), NÃO "todo `issue_id IS NULL`"
`issue_id IS NULL` **≠** turno de conversa: há outras fontes de Run sem Issue — wakeup manual
(`WakeupController`, `issueId` do request pode ser null) e disparo de aprovação (`ApprovalService`).
Varrer por `issue_id IS NULL` comeria o rastro DELAS sem análise. A query de enumeração mira via
`exists (conversation_turn t where t.run_id = r.id)` — o elo **exato e à prova de futuro** (um novo
tipo de Run sem Issue amanhã não é varrido por engano). **Rejeitada:** `issue_id IS NULL` — escopo
maior que o pedido, não à prova de futuro.

### D5 — Reusar a manopla dos 30 dias da F.14 (`orquestrador.rows.gc.retention-days`), sem chave nova
Um dial, um modelo mental ("rastro com +30 dias gradua"); sem evidência de que conversa precise de
prazo diferente; manopla própria depois é trivial (YAGNI). **Rejeitada:**
`conversation-retention-days` própria — +1 dial pra manter/explicar, sem necessidade provada.

### M1 — TTL puro, ZERO proteção "eterna" (a evaporação do B3 da F.14)
A F.14 teve toda a dor do B3 ("reconciliação eterna": versão vigente + fixada-por-review sobrevivem ao
TTL). **Aqui isso EVAPORA:** o dado durável (o texto do turno) mora em `conversation_turn`, uma tabela
que a faxina NUNCA toca. Em `heartbeat_run_event` de conversa não há "vigente" nem "fixado" a blindar —
é puro rastro diagnóstico. Logo: **TTL puro, zero allowlist de proteção.** (Confirmado pelo operador,
2026-07-06.)

## Mecânica
- **Query de enumeração** (`HeartbeatRunRepository.findConversationTurnRunIdsExpired(companyId,
  cutoff)`): `select r.id from HeartbeatRun r where r.companyId = :companyId and
  coalesce(r.finishedAt, r.createdAt) < :cutoff and exists (ConversationTurn t where t.runId = r.id)`.
  É a "política" da conversa, declarativa no SQL — não há julgamento keep-vigente a esconder numa
  `RowRetentionPolicy` (cujo formato `IssueRows` é justamente o que a conversa não tem, M1). O `exists`
  deduplica o coalescing (1 Run → N turnos → 1 id).
- **Coleta**: o reaper resolve os run_ids e drena os `heartbeat_run_event` em chunk pelo mesmo
  `drainEventsOf` compartilhado com a passada de Issue. O Run e o `conversation_turn` **sobrevivem**;
  o `run_id` do turno segue válido apontando pra um Run existente porém sem eventos.
- **Auditoria** (M2): bucket próprio `conversation_run_events` no `detail` do `ROWS_COLLECTED`
  (`ActorType.SYSTEM`), ao lado do `run_events` da F.14 — 1 RESUMO por sweep-por-company, JSON, sem
  migração. A observabilidade separa "quanto veio da faxina de conversa" vs "de Issue".

## Consequências honestas (aceitas)
- **StepTrace vazio pós-coleta**: clicar o StepTrace (`/runs/{runId}/steps`) de um turno de +30 dias
  devolve vazio — o Run e o texto do turno seguem; só o "como pensou" some. É a graduação.
- **Coalescing (1 Run → N turnos)**: coletar os eventos desse Run é 1 unidade só — sem quebra (todos
  os turnos compartilham o Run).
- **Blast radius do relógio único**: o cutoff é o `DatabaseClock` (não `Instant.now()` cru — o bug de
  3h que o TDD da F.12/F.14 pegou); a direção de falha do TTL é conservadora (toque posterior só ADIA).

## Fora do escopo
- Rastro de **wakeup manual / aprovação** (outros Runs sem Issue) — não é conversa (D4); fatia própria
  se doer.
- **Cap por bytes/contagem/LRU** — a camada madura da F.14 (ADR-0028 §H), não este TTL.
- **Índice de apoio à varredura** — só se o profiler pedir (as tabelas já têm `idx_turn_run` e
  `run_event(run_id, created_at)`).
- **O loop de handoff de contexto do CEO** (ADR-0015 "fatia futura") e ligar o `--resume` (ADR-0002) —
  épico próprio, ortogonal a esta faxina.
- Tocar `conversation_turn`/`conversation`/`agent_session`/`heartbeat_run` — só `heartbeat_run_event`.

## Relaciona
ADR-0028 (F.14 — a passada de Issue que esta ESTENDE; o molde de reaper/`DatabaseClock`/`ActorType.SYSTEM`/
`ROWS_COLLECTED` que esta reusa; §B5/§H que previu esta sub-política) · ADR-0026 (1º/2º reaper — âncora
TTL-terminal, `DatabaseClock`) · ADR-0018 (a conversa do board com o CEO — "1 turno = 1 Run") · ADR-0002
(`agent_session` write-only, `--resume` desligado — por que apagar o rastro é seguro) · ADR-0015 (handoff
de contexto — o épico futuro que esta faxina NÃO faz) · ADR-0009 (`activity_log`, detail só-contagens) ·
ADR-0010 (módulo profundo — a query de enumeração como seam, `drainEventsOf` compartilhado).
