# Handoff — GRILLING: retenção do rastro (run_events) dos turnos do CEO sem Issue (fatia FECHADA, pronta para execução)

> **Data:** 2026-07-06 · **Tipo:** plano fechado de grill (não é código). Fruto de uma sessão de
> `/grilling` com mapeamento adversarial do código real (agente Explore + greps dirigidos).
> **Estende:** a **F.14** (`RowRetentionReaper`, **ADR-0028**) — que tratou só o rastro de runs
> LIGADOS a uma Issue e deixou os runs SEM Issue (turnos de conversa do CEO) explicitamente fora do
> v1 ("âncora diferente, sub-política própria", ADR-0028 §B5/§H). Esta é essa sub-política.
> **Rótulo de trabalho:** F.15 (próxima após F.13 thumbnail, F.14 cap+graduação rows).
> **Executar numa sessão NOVA** com `/codebase-design` `/tdd` `/verify` (padrão `/nova-sessao`).

---

## §A — O que é a fatia (uma frase)

Cada mensagem sua ao CEO vira **1 turno = 1 Run** (`V16:2-3`) com `issue_id` NULL, e esse Run deixa
um **rastro passo-a-passo** em `heartbeat_run_event` (a "2ª fonte de contagem" que a F.14 não varreu).
Esta fatia **coleta esse rastro** depois de um TTL, **sem jamais tocar** o texto da conversa
(`conversation_turn.owner_message`/`ceo_reply`, durável) nem o contexto do CEO — apagar o rastro é
ortogonal à memória do CEO. É a graduação: some o "como pensou", ficam o "o que foi dito" e o Run.

---

## §B — DECISÕES-ÁTOMO (arrancadas no grill; dono **H** = Matheus)

| ID | Decisão | Racional (arrancado) | Alternativa rejeitada |
|----|---------|----------------------|-----------------------|
| **D1** | **Âncora por-TURNO** (cada rastro vence sozinho, X dias depois que aquele turno terminou). | O texto já está salvo em `conversation_turn`; não há motivo pra amarrar o rastro à atividade da conversa. Espelho da F.14 (prazo por unidade terminal); segura o crescimento de forma justa. | **Por-conversa** (todo rastro sobrevive enquanto a conversa estiver ativa) → exigiria descobrir "última atividade de cada conversa" (dependência nova) pra pouco ganho; conversa ativa por 1 ano guardaria rastro de 1 ano à toa. |
| **D2** | **Carimbo = `COALESCE(finished_at, created_at)`** do Run. Vencido = `carimbo < now − retenção`. | AQUI está a "âncora diferente" que a F.14 apontou. Na F.14 um item não-terminal é trabalho VIVO (protegido); um turno de conversa não-terminal de 30 dias é **lixo abandonado** (turnos duram minutos), então limpar é o certo. `finished_at` é setado no `finishSuccess/finishFailure` (`HeartbeatRunStore:64,72`); o fallback `created_at` varre o Run "fantasma" (backend caiu sem carimbo). Robusto a ter ou não recuperador de runs presos. Turno rodando agora tem `created_at` recente → nunca tocado. | **Só `finished_at`** (fiel à F.14) → deixa o fantasma sem carimbo com rastro eterno. |
| **D3** | **Estender o `RowRetentionReaper`** com uma **2ª passada** no mesmo `sweepCompany`, não um reaper irmão. | Mesmo trabalho (limpar rastro de Run), mesma engrenagem (`DatabaseClock`, delete em chunk, evento `ROWS_COLLECTED`), mesma família de config. A F.14 é o "molde da casa" pra isso. Irmão duplicaria andaime (schedule/chunk/audit) e criaria 2ª fonte de `ROWS_COLLECTED` pra reconciliar. | **`ConversationRunEventReaper` irmão** → duplicação sem ganho. |
| **D4** | **Alvo = só os turnos de conversa** (Runs referenciados por `conversation_turn.run_id`), **NÃO** "todo `issue_id IS NULL`". | `issue_id IS NULL` **≠** turno de conversa: há outras fontes de Run sem Issue — wakeup manual (`WakeupController:38`, issueId do request) e disparo de aprovação (`ApprovalService:207`). Varrer por `issue_id IS NULL` comeria o rastro DELAS sem análise. `conversation_turn.run_id` (`ConversationTurn:43-44`, "o motor faz o CLAIM, preenche run_id, guardado") é o elo **exato e à prova de futuro** (novo tipo de Run sem Issue amanhã não é varrido por engano). | **`issue_id IS NULL`** → escopo maior que o pedido, varre wakeup/aprovação, não à prova de futuro. |
| **D5** | **Reusar a manopla dos 30 dias da F.14** (`orquestrador.rows.gc.retention-days=30`), sem chave nova. | Um dial, um modelo mental ("rastro com +30 dias gradua"); sem evidência de que conversa precise de prazo diferente; manopla própria depois é trivial (YAGNI). | **`conversation-retention-days` própria (mais curta)** → +1 dial pra manter/explicar, sem necessidade provada. |

---

## §C — MECANISMOS propostos (dono **A** = agente; veto humano no review)

- **M1 — NADA precisa de proteção "eterna" (a grande divergência da F.14).** A F.14 teve toda a
  dor do B3 ("reconciliação eterna": versão vigente + fixada-por-review sobrevivem ao TTL). **Aqui
  isso EVAPORA:** o dado durável (o texto do turno) mora em `conversation_turn`, uma tabela que a
  faxina NUNCA toca. Em `heartbeat_run_event` de conversa não há "vigente" nem "fixado" a blindar —
  é puro rastro diagnóstico. Logo: **TTL puro, zero allowlist de proteção.** ✅ **CONFIRMADO pelo Matheus (2026-07-06).**
- **M2 — Auditoria: reusar `ROWS_COLLECTED` / `ActorType.SYSTEM`** (`RowRetentionReaper:223`), com
  um **bucket próprio no detail** (ex.: `conversation_run_events`) ao lado do `run_events` da F.14
  (`:224-225`). O detail é JSON → adicionar chave é de graça (sem migração). Assim a observabilidade
  separa "quanto veio da faxina de conversa" vs "de Issue". 1 RESUMO por sweep-por-company (não 1 por row).
- **M3 — Zero migração, zero índice novo em v1.** `heartbeat_run_event` não tem trigger de
  imutabilidade (só `activity_log` tem, `V8:14-16`) → DELETE é mecanicamente permitido. A F.14 já
  adia índice de varredura pra "só se o profiler pedir" (ADR-0028 §H) → preserva o gate B8. Já
  existem `idx_turn_run` em `conversation_turn(run_id)` (`V16:34`) e `(run_id, created_at)` em
  `heartbeat_run_event` (`V3:52`) — suficientes pra o join e a coleta.
- **M4 — Reusar o frame da F.14 inteiro:** kill-switch `orquestrador.rows.gc.enabled`
  (`RowRetentionReaper:78`), `chunk-size:500` (`:80`), `DatabaseClock` (`:69`, `clock.now():134`),
  `@Scheduled sweep-ms:3600000` (`HeartbeatScheduler.reapRows`), best-effort não-abortante,
  re-read fresco por company. A 2ª passada é aditiva dentro de `sweepCompany` (`:133`).
- **M5 — Proteção do "em voo" por construção:** turno rodando/recente tem `COALESCE < cutoff` FALSO
  (created_at recente) → nunca coletado. Não precisa de guard de status explícito.
- **M6 — Consequências honestas aceitas:**
  - **StepTrace vazio pós-coleta:** clicar o StepTrace (`RunStepsController`, `/runs/{runId}/steps`)
    de um turno de +30 dias devolve vazio — o Run e o texto do turno seguem; só o "como pensou" some.
    É a graduação (`ConversationTurn:18`: "o StepTrace do Run fica a 1 clique pelo run_id").
  - **Coalescing (1 Run → N turnos):** se um Run drena vários turnos (`ConversationService:67`),
    coletar os `run_event` desse Run é 1 unidade só — sem quebra (todos os turnos compartilham o Run).
  - **O Run e o `conversation_turn` sobrevivem** (só deletamos `heartbeat_run_event`); o `run_id` do
    turno continua válido apontando pra um Run existente porém sem eventos.

---

## §D — DoD (VENDO, nível teams-demo — para a sessão de execução)

Backend `:8080` é infra COMPARTILHADA (não reiniciar sem necessidade; se rebuild, `mvn -o
-DskipTests package` e conferir pid). DB container **`agente-orquestrator-db-1`**.

1. **Seed:** numa company de teste, criar uma conversa com o CEO e ≥2 turnos ANSWERED (owner_message
   + ceo_reply preenchidos), cada um com seu Run e alguns `heartbeat_run_event`.
2. **Envelhecer:** ou baixar `orquestrador.rows.gc.retention-days=0` (como a F.14 provou), ou recuar
   o carimbo via `DatabaseClock`/SQL de teste. Rodar 1 sweep (`RowRetentionReaper.sweepOnce()`).
3. **Provar coleta:** os `heartbeat_run_event` dos Runs desses turnos **somem**; e no MESMO banco:
   - `conversation_turn.owner_message`/`ceo_reply` **INTACTOS** (o texto sobrevive);
   - `agent_session` **intacto** (contexto/handle do motor sobrevive);
   - o `heartbeat_run` do turno **intacto** (só os eventos foram);
   - evento `ROWS_COLLECTED` `SYSTEM` gravado com o bucket `conversation_run_events` contando certo.
4. **Precisão do alvo (D4):** um Run **sem Issue que NÃO é turno** (ex.: wakeup manual) com rastro
   antigo **NÃO** é coletado (prova que miramos `conversation_turn.run_id`, não `issue_id IS NULL`).
5. **Em voo (M5):** um turno recente/rodando **NÃO** perde o rastro.
6. **F.14 intacta:** a passada de Issue segue coletando thumbnail/artifact/run_events como antes
   (regression); o RESUMO agora tem os dois buckets.
7. **Gates backend:** `mvn -o test` verde (+ testes novos de policy/reaper da 2ª passada). Repo sem
   git — nada commitado.

---

## §E — Evidência (código real, file:line — base de `backend/src/main/java/com/orquestrador/`)

- **F.14 a estender:** `execution/runtime/RowRetentionReaper.java` — `sweepCompany:133` (núcleo
  testável, re-read fresco `:143`), `collectRunEvents:193` (pagina por chunk), `deleteInChunks:210`,
  `@Value` `enabled:78`/`retention-days:79`/`chunk-size:80`, `DatabaseClock:69`+`clock.now():134`,
  audit `ROWS_COLLECTED`+`SYSTEM:223`, detail `{thumbnail,artifact,run_events}:224-225`.
  `execution/runtime/RowRetentionPolicy.java` (`decide`, filtro fixado-por-review `:100`).
- **Agendamento:** `execution/HeartbeatScheduler.java` — `reapRows()` já `@Scheduled(sweep-ms:3600000)`.
- **Schema (`resources/db/migration/`):** `V3__heartbeat.sql` — `heartbeat_run.issue_id` NULLABLE
  `:8`, `status:9`, `finished_at:17` (nullable), `created_at:15`; `heartbeat_run_event.run_id` FK
  not-null `:45`, índice `(run_id,created_at):52`; SEM trigger de imutabilidade (só `activity_log`
  em `V8:14-16`). `V16__conversation.sql` — `conversation_turn.owner_message:23`, `ceo_reply:24`,
  `status:26`, `answered_at:28`, `run_id` + `idx_turn_run(run_id):34`; `conversation.company_id`
  + `idx_conversation_company:15`.
- **O elo do alvo (D4):** `conversation/ConversationTurn.java` — `run_id:43-44`, `getRunId:106`
  ("o motor faz o CLAIM, preenche run_id"). `ConversationTurnCoordinator.java:47-51` (claim por runId).
- **Fontes de Run sem Issue (por que D4):** `conversation/ConversationTurnCoordinator.java:89` (null),
  `conversation/ConversationService.java:70` (null) = conversa; `execution/WakeupController.java:38`
  (issueId do request, pode ser null) e `governance/approval/ApprovalService.java:207` = NÃO-conversa.
- **Conclusão do Run (carimbo D2):** `execution/HeartbeatRunStore.java` — `finishSuccess:64` /
  `finishFailure:72` setam `finished_at`. Criação: `HeartbeatRun.java:78-80` (issueId + spawnLineage).
- **Contexto ≠ rastro (por que é seguro):** `--resume` DESLIGADO (`AdapterRunExecutor.java:212`),
  `agent_session` WRITE-ONLY (`AgentSessionStore.java:10`); prompt do turno remontado do estado vivo
  (`ConversationTurnCoordinator.java:59,94-97`) — o rastro não alimenta o contexto.
- **ADR:** `docs/adr/0028-cap-graduacao-retencao-rows.md` (F.14; §B5/§H = runs sem Issue fora do v1).

---

## §F — Gotchas / ambiente

- **Backend `:8080` compartilhado** — não reiniciar à toa; rebuild = `cd backend && mvn -o
  -DskipTests package`, conferir pid (`lsof -iTCP:8080`). **DB:** `docker exec -i
  agente-orquestrator-db-1 psql -U orquestrador -d orquestrador` (user/db COM 'd'; `-i` p/ heredoc).
- **Migrations:** maior hoje = **V22** (esta fatia **NÃO cria migration**, M3). **ADRs:** maior = 0028
  → **não** precisa ADR novo (é a sub-política que a ADR-0028 §H já previu; registrar como adendo à 0028
  OU ADR-0029 curto, a critério do executor).
- **Repo SEM git** — nada commitado. macOS Darwin (`gtimeout`, não `timeout`).
- **`DatabaseClock`, não `Instant.now()` cru** — o bug de 3h que o TDD da F.14 pegou (ADR-0028/§G).
- **Seed do VENDO + ambiente compartilhado:** fatos de ambiente (board-user header
  `X-Board-User: b0a7d000-0000-4000-8000-000000000001`, companies `teams-demo` e `thumb-demo-9368`,
  runs reais 2-4min) vivem em `docs/handoff-grilling-cap-graduacao-rows-fechado.md` §G — reusar.
  Semear/achar conversa + turnos: `POST /api/companies/{companyId}/conversations` +
  `.../conversations/{conversationId}/messages` (`ConversationController:52`). O CEO precisa de um
  motor que RODE (é o que gera os `run_event`) — teams-demo tem, provado no VENDO da F.13/F.14. O
  envelhecimento é por `retention-days=0` (como a F.14 provou) ou recuo de carimbo via `DatabaseClock`.

---

## §G — Fora do escopo (explícito)

- **Rastro de wakeup manual / aprovação** (outros Runs sem Issue) — não é conversa (D4); fatia própria se doer.
- **Cap por bytes/contagem/LRU** — camada madura da F.14 (ADR-0028 §H), não este TTL.
- **O loop de handoff de contexto do CEO (ADR-0015 "fatia futura")** — detectar 50-60% → destilar
  handoff → turno novo lê o bilhete → retoma. É a feature que o Matheus quer a seguir; **épico próprio**,
  ortogonal a esta faxina. Ligar o `--resume` (ADR-0002) também.
- **Índice de apoio à varredura** — só se o profiler pedir (ADR-0028 §H).
- **Tocar `conversation_turn`/`conversation`/`agent_session`/`heartbeat_run`** — só `heartbeat_run_event`.

---

## §H — Ordem de execução sugerida (TDD)

1. **Query de enumeração** (repo): Runs que são turnos de conversa de uma company, vencidos —
   drive por `conversation_turn` (run_id NOT NULL) ∧ `COALESCE(run.finished_at, run.created_at) <
   cutoff`. (Alternativa turno-nativa equivalente: `COALESCE(answered_at, created_at)` do turno —
   coincidem no normal; o executor escolhe o join mais limpo, mantendo a semântica de D2/M5.)
2. **Estender `RowRetentionPolicy`/`RowRetentionReaper`** (TDD, teste pela interface como a F.14):
   2ª passada em `sweepCompany` coletando `run_event` por `run_id` em chunk (reusar `collectRunEvents`/
   `deleteInChunks`). Testes: coleta o vencido; **protege o recente/em-voo** (M5); **não toca** wakeup
   sem Issue (D4); **não toca** `conversation_turn`/`agent_session`/`heartbeat_run`.
3. **Auditoria** (M2): bucket `conversation_run_events` no detail do `ROWS_COLLECTED`; teste do RESUMO.
4. **Registro:** adendo à ADR-0028 (ou ADR-0029 curto) com D1–D5 e M1 (nada eterno aqui).
5. **VENDO** (§D) em teams-demo; reverter seeds/config; backend intocado; nada commitado.
6. **§RESULTADO** neste handoff + memória de fechamento.

---

## Mensagem `/nova-sessao` (copiar para iniciar a EXECUÇÃO numa sessão nova)

> Execute a fatia **retenção do rastro (run_events) dos turnos do CEO sem Issue** (extensão da
> F.14/ADR-0028). **Fonte da verdade:** `docs/handoff-grilling-retencao-rows-conversa-fechado.md`
> (seguir, não replanejar — o grill já fechou D1–D5 e os mecanismos M1–M6). Skills:
> `/codebase-design` (módulo profundo/seam da 2ª passada) → `/tdd` (teste-primeiro, pela interface,
> como a F.14) → `/verify` (VENDO teams-demo, §D). Molde da casa: `RowRetentionReaper` (F.14).
> **Gotchas:** repo sem git (não commitar); backend `:8080` compartilhado (não reiniciar à toa;
> `mvn -o -DskipTests package`); DB `agente-orquestrator-db-1`; `DatabaseClock`, não `Instant.now()`;
> zero migração/índice (M3). **Só `heartbeat_run_event` é tocado.** Ao fim: adendo à ADR-0028, gates
> `mvn -o test` verdes, e §RESULTADO neste handoff.

## Skills a usar (execução): `/codebase-design` · `/tdd` · `/verify`.

## Pendências de veto humano (sinalizadas; NÃO decididas sozinho)
- **M1** (TTL puro, **nada** de proteção eterna — a evaporação do B3 da F.14). ✅ **CONFIRMADO (2026-07-06).**
- Nome do bucket de auditoria (`conversation_run_events`) — cosmético, veto livre.
- Registrar como **adendo à ADR-0028** vs **ADR-0029 novo** — a critério, sinalizar preferência.

---

## §RESULTADO — F.15 FECHADA (2026-07-06)

**A fatia está FECHADA.** Código construído por TDD red→green (pela interface, como a F.14), gates
verdes, e a prova viva §D VENDO na sujeira real (teams-demo) — com a divergência D2 provada.

### O que foi construído (arquivos, só `heartbeat_run_event` tocado — ZERO migração)
- **Query de enumeração** — `HeartbeatRunRepository.findConversationTurnRunIdsExpired(companyId, cutoff)`:
  `select r.id … where company_id=? and coalesce(finished_at, created_at) < cutoff and exists(conversation_turn
  t where t.run_id=r.id)`. É a "política" da conversa, declarativa no SQL — encarna D2 (COALESCE), D4
  (`exists` em `conversation_turn.run_id`, dedup do coalescing) e M5 (recente escapa por construção).
- **`RowRetentionReaper`** — 2ª passada aditiva em `sweepCompany` (best-effort ISOLADO: falha na conversa
  não aborta a company nem esconde a auditoria da passada de Issue já removida, B7); `collectConversationRunEvents`
  reusa o novo `drainEventsOf` compartilhado com `collectRunEvents` (extraído no refactor — o chunk-drain
  count-heavy atrás de 1 seam). `SweepReport` ganhou o campo `conversationRunEvents` (+ `total()`); cutoff =
  `now.minus(retention)` com o MESMO `clock.now()` (D5 reusa a manopla `retention-days=30`; `DatabaseClock`).
- **Auditoria** — bucket `conversation_run_events` no `detail` do `ROWS_COLLECTED` (ator SYSTEM), ao lado do
  `run_events` da F.14; 1 RESUMO por sweep-por-company, JSON, sem migração (M2). Log da linha por-company
  estendido. Javadoc de `HeartbeatRunEventRepository.findIdsByRunIdIn` reconciliado (conversa agora chega lá).
- **ADR-0029** `docs/adr/0029-retencao-rastro-turnos-conversa.md` — Aceito (D1–D5 + M1 + mecânica +
  consequências). **Preferência sinalizada: ADR-0029 novo** (não adendo à 0028), pois D2 DIVERGE
  conscientemente da 0028 (turno de conversa não-terminal = lixo, não trabalho vivo) — merece registro
  próprio. Forward-ref adicionado no recorte da 0028.

### Gates
- `mvn -o test` **403 verde** (piso F.14 = 395; **+8 novos**: 4 de enumeração D2/D4/M5 no round-trip H2
  modo-PG, 3 de reaper [coleta+intactos+D4+M5], 1 combinado dos-dois-buckets). Zero falha.
- **Nada commitado** (repo sem git). Rebuild `mvn -o -DskipTests package` OK; :8080 restaurado ao repouso
  (`java -jar` puro, retention 30 / sweep 1h, health UP) rodando o jar F.15.

### VENDO (§D) — prova viva na sujeira real (teams-demo `142f1ac5-…`)
Ambiente compartilhado: o kill do :8080 (pid pré-existente) foi barrado pela política do harness; o operador
autorizou e **rodou ele mesmo** o restart cirúrgico (`scratchpad/f15-vendo.sh`) — todo o resto (verificação
read-only + cleanup) foi do agente. Seed cirúrgico envelhecido a **40d** (só ele é 30d+ no DB inteiro —
SAFETY confirmado ANTES=2 conv-turn runs 30d+ = exatamente RA/RB): conversa + 3 turnos ANSWERED (2 vencidos
+ 1 recente) + Issue DONE vencida, com runs (issue_id NULL p/ conversa) e eventos; + 1 wakeup sem turno (D4).
- **Linha do reaper** (1 sweep, retention 30 default): `GC rows company 142f1ac5-…: 0 thumbnail, 0 artifact,
  **6 run_events, 9 conversation_run_events** coletado(s)` — os DOIS buckets num só sweep.
- **Coletado**: RA(5)+RB(4)=**9** eventos de conversa vencida; RX(6) da Issue vencida (F.14 intacta).
- **Preservado**: RW (wakeup sem turno, 40d+) **3→3** (D4 — mira `conversation_turn.run_id`, não `issue_id
  NULL`); RC (turno recente) **2→2** (M5); os **5 runs sobrevivem** (só os filhos-evento foram); os **3
  turnos ANSWERED com owner_message+ceo_reply INTACTOS**; **agent_session intacto**.
- **Auditoria**: `ROWS_COLLECTED`, `actor_type=SYSTEM`, `actor_id=NULL`, `entity=company`, `detail=
  {"artifact":0,"thumbnail":0,"run_events":6,"conversation_run_events":9}`.
- **Repouso restaurado + seed removido** (cleanup FK-safe; `f15 rows restantes: 0`); dado real do operador
  intocado (nada dele era 30d+).

### Veredito
**PASS** — a 2ª passada coleta o rastro dos turnos de conversa vencidos e SÓ ele (D4/D2/M5 provados VENDO
na DB real, além dos 403 testes), o durável (texto do turno + agent_session + o Run) sobrevive, a auditoria
SYSTEM-resumo cai com os dois buckets certos, a passada de Issue da F.14 segue intacta, e :8080 está no
repouso saudável. **F.15 FECHADA.**
