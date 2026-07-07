# Handoff — GRILLING F.14: cap+graduação das rows append-only (fatia FECHADA, pronta para execução)

> Origem: sessão de PLANEJAMENTO (grilling) 2026-07-06, logo após a F.13 (thumbnail do preview v2 —
> ADR-0027). Fatia escolhida pelo operador no menu da §H da ADR-0027 (candidato #1 — o de argumento
> objetivo mais forte). Plano FECHADO pelo grill — a sessão de execução **NÃO re-planeja e NÃO reabre
> ADRs 0001–0027**. Repo SEM git em `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/`
> — NÃO commitar.
> Piso backend: **375/375** (o teto que a F.13 deixou; nunca baixar). **Front RECEBE só o que o VENDO
> exigir** (nenhuma tela nova; o `<img>` do thumbnail e a foto v1 continuam funcionando — a fatia
> PROVA que continuam, não os reescreve).
> Mecanismo: **3º reaper da casa** (`RowRetentionReaper`), molde do `WorkspaceReaper`/`LeaseReaper`,
> mas **PURE-DB** (DELETE por repo/jOOQ — **sem** tocar o seam `ExecutionTarget`; rows não são docker
> nem filesystem).
> Evidência: **exploração de código desta sessão** (§E) — zero trigger de imutabilidade nas 3 tabelas,
> a teia de FKs que fixa a evidência de review, e os 3 perfis de acúmulo distintos.

---

## §A — O que é a fatia

**O 3º reaper: parar o único crescimento sem-teto que sobrou.** A F.12 (ADR-0026) coletou os
**workspaces** (dirs/volumes) mas deixou uma nota explícita: *"as rows do banco ficam — banco inteiro
< 10 MB"*. A F.13 (ADR-0027) **invalidou essa premissa**: criou `preview_server_thumbnail`, uma tabela
**por-run, sem dedup, `bytea` até 2MB por linha** (shot real medido: 322 KB). É a primeira tabela que
faz o Postgres crescer em **MB→GB**, e ela FICA por design (o `WorkspaceReaper` coleta workspaces,
**nunca** rows). Some-se a ela `preview_artifact` (HTML/TEXT até 2MB, por-versão) e `heartbeat_run_event`
(o firehose do StepTrace, 1 row por passo de todo run): **3 fontes append-only, zero teto**.

A fatia: um reaper agendado que **cap**a esse crescimento **sem apagar o registro durável**. O invariante
que a casa vendeu 4× ("a foto/thumbnail é eterna, sobrevive ao GC de workspace") é **preservado e
afiado**: o que é eterno é a **versão VIGENTE por-Issue** (a que a UI mostra) + qualquer versão **fixada
por uma review** (a evidência "R aprovou a v3"); o que coleta é a **cauda superseded** — versões velhas
que nada exibe e nada referencia, de Issues **terminais** vencidas. O agente **não declara nada novo**
(deny-by-default intacto — como os dois reapers anteriores, é observação/coleta pura do pipeline).

---

## §B — DECISÕES-ÁTOMO (racional + alternativas rejeitadas)

### B1. Natureza: 3º reaper agendado, molde da casa — mas PURE-DB (sem o seam `ExecutionTarget`)
`RowRetentionReaper` — módulo profundo, par do `WorkspaceReaper`/`PreviewServerSupervisor`/`LeaseReaper`:
`sweepOnce()` público (kill-switch + loop das companies) + `sweepTarget()`/núcleo profundo testável,
agendado no `HeartbeatScheduler` (irmão do `reapWorkspaces`/`reapOnce`), config `@Value`, teste com
retenção curta. **A diferença estrutural do WorkspaceReaper**: workspaces moram no Docker/filesystem
(exigiram o seam `ExecutionTarget` com split DOCKER×LOCAL + guard de path); **rows moram no Postgres** —
a remoção é um **DELETE em lote por repositório/jOOQ**, **sem** tocar `ExecutionTarget`, **sem** split de
target, **sem** guard de path. Este reaper é *mais simples* que o da F.12.
**Racional**: a indústria e a casa convergem no reaper agendado com política declarativa (§E da F.12:
K8s TTL-after-finished, BuildKit GC, Jenkins WorkspaceCleanupThread; faxina pontual foi refutada — a dor
volta a cada fatia). O molde já é provado 3× (`LeaseReaper`, `PreviewServerSupervisor`, `WorkspaceReaper`).
**Rejeitadas**: **estender o `WorkspaceReaper`** (mistura dois domínios — filesystem/docker vs Postgres —
num módulo; o WorkspaceReaper passa pelo seam, este não); **job SQL puro / cron externo** (fora da
observabilidade da casa, sem auditoria no `activity_log`, sem kill-switch tipado); **`@SQLDelete`/cascade
no ORM** (deleção implícita, não-auditável, difícil de testar em lote).

### B2. Política v1 = TTL desde a TRANSIÇÃO TERMINAL (reusa a âncora provada da F.12)
- Configs: `orquestrador.rows.gc.enabled:true` · `…retention-days:30` · `…sweep-ms:3600000` (1h — TTL de
  dias não pede varredura de minuto).
- **Âncora = `issue.updated_at`** comparada **SEMPRE com o relógio do BANCO** (`DatabaseClock`, o mesmo
  que a F.12 provou — mesmo frame do Hibernate; o bug de 3h que o TDD da F.12 pegou não se repete se
  reusar `DatabaseClock`). Só entram no colecionável rows de Issues **TERMINAIS** (DONE/CANCELLED)
  **vencidas** (updated_at + retention < now-do-banco). Direção de falha conservadora: toque posterior só
  ADIA.
- **Por que 30 dias (e não os 7 dos workspaces)**: a *versão vigente* é eterna (não tem TTL — B3); o TTL
  governa só a **cauda superseded**, que é barata e às vezes útil pra diff visual. 30d dá folga sem
  reintroduzir crescimento. Config separada (`rows.gc.*`) para afinar sem mexer no `workspace.gc.*`.
**Racional**: TTL-desde-terminal é a âncora canônica (K8s `lastTransitionTime`); ancorar em terminal
**dissolve de graça** o risco de review-viva (Issue terminal → reviews decididas) e o risco de comer a
Issue ativa (não-terminal nunca entra). Reusar `DatabaseClock`/`updated_at` = zero superfície nova.
**Rejeitadas**: **cap por bytes/contagem com histerese/LRU** (a evolução madura — §E F.12 — mas LRU pode
evictar o registro vigente de uma Issue quente; camada FUTURA §H); **TTL desde a criação** (comeria
cauda de Issue de vida longa); **default desligado K8s-style** (a dor volta calada); **âncora
`terminal_at` dedicada** (migração sem necessidade — a F.12 já rejeitou).

### B3. ⚠ A RECONCILIAÇÃO "ETERNA" — a única decisão que reenquadra um invariante citado (VETÁVEL pelo operador)
Quatro ADRs (0021, 0023, 0026, 0027) venderam durabilidade com a frase *"a foto/thumbnail é eterna"*.
Esta fatia **afia** — não quebra — esse invariante: **eterno = a versão VIGENTE por-Issue + as versões
FIXADAS por evidência (review), NÃO cada row append-only.** Coletável = a **cauda superseded, não-fixada,
de Issue terminal vencida.**
- **`preview_server_thumbnail`**: mantém o **último por-Issue** (V22: "o vigente de uma Issue é o ÚLTIMO"
  — a mesma regra de exibição). Coleta os thumbnails de runs anteriores da mesma Issue. **Sem pin de
  review** (thumbnail é novo, só-exibição — nada o referencia). O `<img>` que a F.13 provou sobreviver ao
  GC **continua sendo o vigente → continua eterno.**
- **`preview_artifact`**: mantém a **maior `version` por-Issue** (o que a UI mostra por padrão) **+**
  qualquer versão cujo `run_id` seja referenciado por uma **`review`** (evidência "R aprovou a v3" —
  `review.run_id → heartbeat_run`, NOT NULL, confirmado no schema). Coleta as versões superseded e
  não-referenciadas. A foto v1 vigente **continua eterna**; a evidência de review **nunca orfana.**
- **Consequência aceita (a metade "graduação")**: o *seletor de versões* de uma Issue terminal antiga
  passa a mostrar **a vigente + as fixadas por review**, não o histórico completo. Numa Issue DONE de 30+
  dias, ver a v1 quando a v3 é o resultado tem valor marginal — a superseded **gradua para fora**. Para
  binário/evento **não há tier semântico pra promover** (ao contrário do decision-ledger da ADR-0019, onde
  graduação = mover pra ADR-da-Empresa): aqui "graduação" = *a vigente/fixada É o registro promovido; a
  cauda é o rascunho que sai*.
**Racional**: preserva TODA promessa já feita (o que sobrevive ao GC da F.13 = o último thumbnail = MANTIDO;
o que a review mostra = a versão fixada = MANTIDA). Não reabre 0021 — é a continuação que 0021/0026/0027
**explicitamente adiaram** ("quando o volume doer, é cap+graduação, outra fatia").
**Rejeitadas**: **apagar por run sem exceção de vigente** (mataria o registro durável — quebraria o VENDO
da F.13 e o "eterna" de 0021); **manter tudo eterno, só alertar** (não fecha o débito — vira o candidato
"só detecção" que o operador já descartou ao escolher construir #1); **pin manual opt-in** (superfície +
armadilha de pin-pós-expiração — a F.12 já rejeitou pin; aqui o "pin" é DERIVADO da review, não uma feature).
**→ VETO AQUI**: se o operador quiser "eterno literal = zero coleta de artifact/thumbnail", a fatia
encolhe para **só `heartbeat_run_event`** (o firehose, que ninguém chama de eterno) e o resto vira §H.

### B4. Dois mecanismos numa cabeça só (não um reaper uniforme)
O núcleo profundo é uma **política pura** `RowRetentionPolicy` (entra: rows candidatas + status/updated_at
da Issue + `clock.now()`; sai: ids-a-coletar vs ids-a-manter), com **duas formas** espelhando os dois
perfis de acúmulo:
- **Policy A — byte-heavy / keep-current-per-issue (thumbnail + artifact)**: agrupa por Issue, mantém o
  vigente (+ pins de review no artifact), coleta o resto da Issue terminal-vencida.
- **Policy B — count-heavy / TTL-por-run (`heartbeat_run_event`)**: coleta os eventos dos **runs cujo
  `issue_id` é uma Issue terminal-vencida**; o **run permanece** (é hub de FK — review/approval/artifact/
  thumbnail/cost/conversation apontam pra ele); só os **filhos-evento** vão. É count-heavy (1 row/passo),
  não byte-heavy.
**Racional**: os perfis são genuinamente diferentes (thumbnail: por-run, sem dedup, 2MB → o maior
ofensor de BYTES; artifact: por-versão, deduped, 2MB → moderado, "vai de carona" na Policy A; run_event:
por-passo → o ofensor de CONTAGEM). Um reaper uniforme mentiria sobre isso.
**Rejeitadas**: **um DELETE só com WHERE genérico** (não distingue "manter vigente" de "coletar cauda");
**3 reapers separados** (3× o boilerplate de agendamento/auditoria; a política pura já separa as formas).

### B5. Escopo do colecionável + o que é PROTEGIDO por construção
- **Coleta**: rows de `preview_server_thumbnail`/`preview_artifact`/`heartbeat_run_event` ligadas a Issue
  **terminal vencida**, **exceto** o vigente/fixado (B3).
- **Protegido por construção**: Issue **não-terminal** (OPEN/BLOCKED/PENDING_REVIEW — nunca entra);
  Issue terminal **não-vencida**; a **versão vigente** por-Issue; versão **fixada por review**; a tabela
  **`activity_log`** (protegida por `trg_activity_log_immutable` — DELETE proibido no banco; nem é alvo);
  o próprio **`heartbeat_run`** (hub de FK — só os filhos-evento coletam); todas as **outras tabelas**
  (fora da lista explícita — allowlist ESTRUTURAL de 3 tabelas, não denylist).
- **Eventos de run SEM Issue** (`heartbeat_run.issue_id` NULL — os turnos de conversa do CEO, "1 turno =
  1 Run"): **fora do v1** (§H). São um 2º acúmulo real de `run_event`, mas de âncora diferente (turno de
  conversa, não transição de Issue); v1 mantém a âncora única "por-Issue terminal" (consistente com a F.12).
**Racional**: ancorar em Issue terminal é a mesma disciplina provada da F.12; a allowlist de 3 tabelas é
auditável de relance (o oposto de "delete por padrão").
**Rejeitadas**: **incluir run_events de conversa no v1** (âncora diferente, infla a fatia — §H); **coletar
por company inteira sem checar status** (comeria trabalho vivo).

### B6. Mecânica: lote + re-read fresco + best-effort não-abortante
- **World-driven pela derivação do banco**: 1×/ciclo, por company, lista as Issues terminais vencidas
  (`IssueRepository`, filtro status-terminal + `updated_at < now-retention`), resolve as rows candidatas,
  aplica a `RowRetentionPolicy`, DELETA em **lote limitado** (chunk — nunca um `DELETE` de tabela inteira
  numa transação gigante; volumes de rows podem ser grandes).
- **Re-read fresco** imediatamente antes de cada lote de remoção (padrão K8s TTL / F.12: a Issue ainda é
  terminal? — fecha a race com REOPEN/checkout).
- **Best-effort não-abortante**: falha ao coletar as rows de uma Issue **nunca** aborta o sweep (segue
  para as demais); erro → WARN no log. O sweep **nunca** participa da máquina de estados (como os 2
  reapers anteriores).
**Rejeitadas**: **DELETE único sem chunk** (transação longa travando o pool); **deleção assíncrona
via fila** (Resource Disposer — evolução §H, desnecessário pra v1); **marcar `collected_at` no banco**
(estado especular + migração — a F.12 já rejeitou; o mundo/derivação é a verdade).

### B7. Auditoria: evento-RESUMO por sweep, ator SYSTEM, detail só-contagens
Evento novo `ActivityAction.ROWS_COLLECTED` no `activity_log`, **1 por ciclo-de-sweep-por-company** (não
1 por row — seria dilúvio), `detail` só-contagens/enum (`{thumbnail:n, artifact:n, run_events:n}` — ADR-0009,
números não são PII), ator **`ActorType.SYSTEM`** (reusa o ator que a F.12 criou — o reaper é ato do
PIPELINE; o switch exaustivo do `CompanyAccessGuard` já tem `case SYSTEM -> false`). Gravado **após** a
remoção real (só o que de fato coletou audita — nunca um falso "coletado").
**Rejeitadas**: **evento por row** (flood — milhares de eventos por sweep); **auditar como BOARD/AGENT**
(mente a atribuição — a classe de sujeira do incidente Atlas); **não auditar** (deleção sem rastro durável).

### B8. Config + zero migração
- `orquestrador.rows.gc.{enabled:true, retention-days:30, sweep-ms:3600000}` (namespace próprio, `@Value`).
- **ZERO migração de schema** (espelha a F.12): a fatia é lógica + config + DELETE sobre tabelas que já
  existem; nenhuma coluna nova, nenhum trigger. Se a query de varredura ficar lenta em volume real, um
  índice de apoio é **otimização FUTURA** (§H), não requisito do v1 (as tabelas já têm
  `(company_id, issue_id)` e `run_event(run_id, created_at)`).

### B9. Deny-by-default: o agente não declara NADA
Como os 2 reapers anteriores, é coleta pura do pipeline sobre dados que o pipeline mesmo produziu. Zero
contrato de lugar novo, zero canal agente→core. A superfície de deny-by-default não muda.

### B10. Registro: ADR-0028
`docs/adr/0028-cap-graduacao-retencao-rows.md`, status **Aceito**, com B1–B9, riscos, recortes (§H) e a
evidência (§E). Relaciona: ADR-0026 (1º/2º reaper, `ActorType.SYSTEM`, `DatabaseClock`, âncora
TTL-terminal), 0021 (foto v1 eterna — o invariante que B3 afia), 0027 (o thumbnail que criou a 3ª fonte
e disparou o gatilho), 0012 (estados terminais vs não-terminais), 0009 (detail só-IDs/contagens), 0010
(módulo profundo — `RowRetentionReaper`/`RowRetentionPolicy`).

---

## §C — DoD (verificável, nível VENDO)

1. **ADR-0028** escrito (Aceito).
2. **TDD red→green**:
   - `RowRetentionPolicyTest` (**PURO**, sem DB — o coração): mantém o último thumbnail por-Issue; mantém
     a maior version de artifact por-Issue; mantém version de artifact fixada por review; **coleta** as
     superseded não-fixadas; **NÃO** coleta Issue não-terminal; **NÃO** coleta terminal não-vencida;
     run_events de run de Issue terminal-vencida coletados / de run recente mantidos.
   - Repos: métodos de contagem + DELETE em lote (round-trip no H2 modo Postgres).
   - `RowRetentionReaperTest` (molde `WorkspaceReaperTest`, fakes/scripted): sweep por company, re-read
     fresco, best-effort não-abortante, evento **`ROWS_COLLECTED`** resumo ator **SYSTEM** com contagens;
     kill-switch; retenção curta.
3. Suíte backend **≥ 375 + novos, verde** (`cd backend && mvn -o test`); contratos provados intocados
   (`.claude/skills`, `.orq/preview/*`, `CodexStreamParser`, posture ADR-0025, **seam `ExecutionTarget`
   NÃO tocado** — esta fatia é pure-DB).
4. **Prova VIVA na sujeira real** (rebuild jar → `:8080`):
   - **Seed de cauda**: numa Issue terminal, gerar **múltiplos** thumbnails (subir a app v2 N vezes →
     N rows por-run em `preview_server_thumbnail`), **múltiplas** versions de `preview_artifact`, e
     **muitos** `heartbeat_run_event`. (A company **Thumbnail Demo** `thumb-demo-9368` da F.13 é o
     ponto de partida; o smoke da 0023 sobe a app v2 repetível.)
   - `SELECT count(*)` das 3 tabelas ANTES; `rows.gc.retention-days=0` TEMPORÁRIO → sweep;
   - **VENDO no board — o coração**: a cauda some (contagem cai), **MAS**: o **último thumbnail
     continua renderizando** (o `<img>` da F.13 na Issue e em Servidores — o registro durável NÃO foi
     comido, mesmo com retention=0); a **foto v1 vigente continua abrindo**; se houver review, a
     **versão que ela aprovou continua abrindo**; o **StepTrace de um run recente/não-terminal continua
     aparecendo**;
   - **1 evento `ROWS_COLLECTED`** por company no feed (ator SYSTEM, detail `{thumbnail,artifact,run_events}`);
   - falha sintética (ex.: forçar erro numa company) → WARN, sweep **segue** nas demais (best-effort);
   - **restaurar `rows.gc.retention-days=30`** + rebuild/restart final (estado de repouso = default).
5. **Front**: nenhuma tela nova; o VENDO confirma que thumbnail/foto/StepTrace **continuam** renderizando
   (diff do front vazio ou mínimo).
6. §RESULTADO neste handoff; **ADR-0015** respeitado (cruzou ~50-60% → destilar e recomeçar).

---

## §D — Threads vivos herdados (pendências do OPERADOR, não tarefa de agente)

- **Company de demo `Thumbnail Demo` (`thumb-demo-9368`)** — a prova viva da F.13; **é o ponto de partida
  ideal do seed de cauda desta fatia** (subir a app dela mais vezes gera os thumbnails superseded). Remover
  só depois, se quiser.
- **Volume docker anônimo `6bea9500…`** (49MB, pgdata órfão) — herdado da F.12; irreversível apagar;
  decisão do operador. NÃO é tarefa desta fatia.
- **Aprovação `da239e6d` do Atlas** — segue **PENDING** na fila do operador. Não decidir por ele.

---

## §E — Evidência (exploração de código, sessão de grilling 2026-07-06)

- **Zero trigger de imutabilidade nas 3 tabelas-alvo.** `grep` de `create trigger` em todas as migrations:
  **só** `V8:trg_activity_log_immutable` (before update or delete on `activity_log`). `preview_artifact`
  (V20), `preview_server_thumbnail` (V22) e `heartbeat_run_event` (V3) **não têm trigger** → DELETE é
  mecanicamente permitido, sem barreira a contornar.
- **A teia de FKs fixa a evidência de review.** Tudo referencia `heartbeat_run(id)`: `review.run_id`
  NOT NULL, `preview_artifact.run_id` NOT NULL (+ `uq (issue_id, version)` — "R aprovou a v3"),
  `preview_server_thumbnail.run_id` NOT NULL, `heartbeat_run_event.run_id` NOT NULL, além de
  `approval`/`conversation`/`cost_event`. **Nada referencia as 3 tabelas-alvo como PAI** → deletar suas
  rows é FK-safe; o "pin" de review é uma escolha **semântica** (não perder a evidência exibida), não uma
  trava de banco.
- **3 perfis de acúmulo distintos** (fundamenta B4): `preview_server_thumbnail` = por-run, **sem dedup**,
  `bytea` até 2MB → maior ofensor de BYTES; `preview_artifact` = por-version, **deduped-vs-última** no
  capturador → moderado; `heartbeat_run_event` = 1 row/passo, `(run_id, created_at)` → ofensor de CONTAGEM.
- **A premissa que caiu**: ADR-0026 §Contexto + F.12 §H disseram *"as rows ficam — banco < 10 MB"* e
  adiaram retenção de rows explicitamente ("quando doer, é cap+graduação, outra fatia"). ADR-0027 §H
  registrou o thumbnail como a **3ª fonte** e reafirmou o adiamento. Esta fatia é a cobrança consciente
  desse adiamento — o gatilho (uma tabela de 2MB/row) agora é real, não hipotético.
- **Gap confesso**: NÃO rodei deep-research de mercado (o desenho foi forçado por restrições DURAS do
  schema — FKs, ausência de trigger, os 3 perfis — e reusa o padrão já pesquisado da F.12: reaper
  agendado + TTL-terminal + `DatabaseClock` + `ActorType.SYSTEM`). Se o operador quiser o rigor da F.12
  nos **caps por byte/contagem/LRU** (a camada futura §H), aí uma deep-research curta paga — nenhum
  átomo do v1 depende dela.

---

## §G — Ambiente (fatos) + gotchas de sangue

- Backend jar no `:8080` (rebuild = pid NOVO, conferir `lsof -iTCP:8080`; `cd backend && mvn -o
  -DskipTests package`). Front: launch.json "frontend" (`:3000`).
- DB: container **`agente-orquestrator-db-1`** (sem 'd'); `docker exec -i agente-orquestrator-db-1
  psql -U orquestrador -d orquestrador` (user/db COM 'd'; `-i` obrigatório para heredoc).
- Migrations em `backend/src/main/resources/db/migration/`; **maior hoje = V22** — esta fatia **NÃO cria
  migration** (B8). ADRs em `docs/adr/`; **maior hoje = 0027** → esta é **0028**.
- Molde a espelhar: **`WorkspaceReaper`** (F.12) — `sweepOnce()`/`sweepTarget()`, `@Scheduled` no
  `HeartbeatScheduler` (`reapWorkspaces`), configs `@Value` `workspace.gc.*`, `DatabaseClock` (mesmo
  frame do `issue.updated_at` — ⚠ o bug de 3h que o TDD pegou: **reusar `DatabaseClock`, não coagir
  `now()` na mão**), `ActorType.SYSTEM` + `WORKSPACE_COLLECTED`. `RowRetentionReaper` é o irmão pure-DB.
- Exibição a NÃO quebrar: `PreviewServerThumbnailController` (último-da-Issue), `PreviewArtifactController`
  (versões por-Issue), `RunStepsController`/`IssueRunLedgerController` (StepTrace por-run). O VENDO
  confirma que os 3 seguem servindo o vigente.
- `activity_log` é append-only por trigger (V8) — só INSERT (o `ROWS_COLLECTED` é INSERT normal).
- macOS **Darwin**: `gtimeout` (não `timeout`); probes de CLI no host sempre `</dev/null`. Runs reais
  2–4min. Board user `b0a7d000-0000-4000-8000-000000000001` (header `X-Board-User`).
- Companies com histórico: `Thumbnail Demo` `thumb-demo-9368` (F.13, com thumbnail vivo — seed ideal),
  teams-demo `142f1ac5-…`, `b92cb312-…`.

---

## §H — Fora do escopo / recortes conscientes

- **Caps por BYTES/CONTAGEM, histerese (85/80), eviction LRU** — a camada madura sobre o TTL (§E F.12);
  entra quando o TTL sozinho não segurar. Deep-research paga aqui, não no v1.
- **`run_event` de runs SEM Issue** (turnos de conversa do CEO, `issue_id` NULL) — 2ª fonte de contagem,
  âncora diferente (turno, não transição de Issue); fatia/sub-política própria.
- **Graduação SEMÂNTICA de conteúdo** (mover essência pra tier durável, estilo decision-ledger ADR-0019) —
  não se aplica a binário/evento (não há tier pra promover); aqui "graduação" = manter vigente+fixado.
- **Delete assíncrono** (Resource Disposer / fila) · **índice de apoio à varredura** (otimização, só se o
  profiler pedir) · **pin manual opt-in** (o "pin" aqui é derivado de review, não feature).
- **`preview_artifact` como parte do escopo** pode ser **vetada** (B3) → a fatia encolhe pra só
  `heartbeat_run_event` + `preview_server_thumbnail` se o operador insistir em "artifact eterno literal".
- Não reabrir ADRs 0001–0027; parser, contratos de lugar e o seam `ExecutionTarget` não mudam.

---

## §I — Ordem de execução sugerida (TDD)

1. **ADR-0028** (B10).
2. **`RowRetentionPolicy`** (núcleo PURO, sem DB) red→green — as 2 formas (keep-current-per-issue+pins de
   review; run_event por run-de-Issue-terminal). Testes table-driven do §C.2.
3. **Repos**: contagem + DELETE em lote nas 3 tabelas (round-trip H2 modo Postgres); consulta de Issues
   terminais vencidas (`IssueRepository`) + resolução das rows candidatas + set de runs fixados por review.
4. **`RowRetentionReaper.sweepOnce()/sweepTarget()`** red→green (molde `WorkspaceReaper`): world-driven por
   derivação, re-read fresco, chunk, best-effort não-abortante, `ROWS_COLLECTED` resumo ator **SYSTEM**
   (conferir exaustão de switch).
5. **Agendamento** no `HeartbeatScheduler` (`@Scheduled` `rows.gc.sweep-ms`) + configs `@Value`.
6. `mvn -o test` **≥375+novos** → rebuild → `:8080`.
7. **Prova viva §C.4** (seed de cauda na Thumbnail Demo → `retention-days=0` → cauda coletada, **vigente
   SOBREVIVE VENDO**, evento resumo SYSTEM → restaurar 30).
8. **§RESULTADO** neste handoff + mensagem `/nova-sessao` da próxima fatia.

---

## Mensagem /nova-sessao (copiar para iniciar a EXECUÇÃO)

```
/nova-sessao

Executar a fatia CAP+GRADUAÇÃO DAS ROWS APPEND-ONLY (F.14) do agente-orquestrator — plano FECHADO no
grill em docs/handoff-grilling-cap-graduacao-rows-fechado.md. Sessão de EXECUÇÃO (TDD red→green), NÃO
de replanejamento: NÃO reabrir ADRs 0001–0027 nem os contratos provados (.claude/skills, .orq/preview/*,
CodexStreamParser, posture/ADR-0025, seam ExecutionTarget — esta fatia é PURE-DB, não toca o seam). Repo
SEM git em /Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/ — NÃO commitar.

## Skills a usar
- /tdd — red→green por fatia vertical (a política pura primeiro; é a espinha).
- /codebase-design — RowRetentionReaper como módulo profundo (par do WorkspaceReaper), política pura
  atrás de seam pequeno; PURE-DB (sem seam ExecutionTarget).
- /nova-sessao — ao cruzar ~50-60% do contexto (ADR-0015): destilar §RESULTADO e recomeçar.

## Leia primeiro (ordem)
1. docs/handoff-grilling-cap-graduacao-rows-fechado.md — o plano fechado (§A o quê; §B B1–B10 com
   racional+rejeitadas; ⚠ B3 é a reconciliação "eterna" — o átomo vetável, já decidido: mantém vigente +
   fixado por review; §C DoD VENDO; §E evidência; §G ambiente; §H recortes; §I ordem TDD).
2. docs/adr/0026 + docs/handoff-grilling-gc-retencao-workspaces.md — o molde do reaper (WorkspaceReaper,
   DatabaseClock, ActorType.SYSTEM, âncora TTL-terminal). Reusar, não reinventar. ⚠ o bug de 3h do
   DatabaseClock: reusar a classe, não coagir now() na mão.
3. docs/adr/0021 + 0027 — a foto v1 e o thumbnail v2 (os registros VIGENTES que B3 mantém eternos).

## A fatia (o essencial)
3º reaper da casa (RowRetentionReaper), molde do WorkspaceReaper mas PURE-DB (DELETE em lote por
repo/jOOQ — sem seam). Política pura RowRetentionPolicy com 2 formas: (A) byte-heavy keep-current-per-issue
para preview_server_thumbnail + preview_artifact (mantém o vigente + versões fixadas por review, coleta a
cauda superseded de Issue terminal vencida); (B) count por-run para heartbeat_run_event (coleta eventos de
runs de Issue terminal vencida; o run FICA, só os filhos-evento vão). Âncora: TTL desde terminal
(issue.updated_at vs DatabaseClock), retention-days=30, configs rows.gc.*. Auditoria: ROWS_COLLECTED
resumo por sweep, ator SYSTEM, detail só-contagens. ZERO migração. Agente não declara nada.

## DoD VENDO (o coração)
Seed de cauda na company Thumbnail Demo (subir a app v2 N vezes → N thumbnails; N versions de artifact;
muitos run_events) → retention-days=0 → sweep coleta a cauda (contagem cai) MAS o ÚLTIMO thumbnail
CONTINUA renderizando na Issue e em Servidores, a foto v1 vigente abre, a versão aprovada por review
abre, o StepTrace de run recente aparece → 1 evento ROWS_COLLECTED ator SYSTEM com {thumbnail,artifact,
run_events} → restaurar retention-days=30 + rebuild. Suíte ≥375+novos verde. Seguir a ordem TDD §I.

## Pendências do OPERADOR (não decidir por ele)
- Volume anônimo 6bea9500… (pgdata órfão, 49MB) — intacto; ele decide.
- Aprovação da239e6d do Atlas — PENDING na fila dele.
- Company Thumbnail Demo — é o seed ideal da prova; remover só depois se quiser.
```

---

## §RESULTADO — PARCIAL (2026-07-06): código FECHADO e provado verde; falta só a PROVA VIVA §C.4

**Estado**: o mecanismo inteiro está construído (TDD red→green) e a suíte backend passou de **375 → 395
verde** (+20 novos, 0 falhas). Rebuild feito, backend **saudável no `:8080`** (pid novo), Flyway
**"No migration necessary"** (ZERO migração confirmada em runtime — B8), o bean novo wireado no
`HeartbeatScheduler` sem erro. **NADA commitado** (repo sem git). Falta **apenas** a prova viva §C.4
(browser VENDO na Thumbnail Demo) — a razão do handoff (ADR-0015: cruzou ~50-60% do contexto antes da
fase pesada de seed+runs+browser).

### O que foi construído (arquivos)
- **ADR-0028** `docs/adr/0028-cap-graduacao-retencao-rows.md` — Aceito (B1–B9, riscos, §H, Relaciona).
- **`RowRetentionPolicy`** (núcleo PURO, `execution/runtime/`) — o gate terminal+vencida + as 2 formas
  (thumbnail: máx `created_at`; artifact: máx `version` + pins de review; run_event: gate booleano).
  `RowRetentionPolicyTest` **10/10**.
- **`RowRetentionReaper`** (`execution/runtime/`, PURE-DB, molde do `WorkspaceReaper`) —
  `sweepOnce()` (kill-switch + loop companies) / `sweepCompany()` (núcleo): re-read fresco, best-effort
  não-abortante em 2 níveis, DELETE em chunk, run_events paginados, auditoria `ROWS_COLLECTED` RESUMO
  ator SYSTEM. `RowRetentionReaperTest` **6/6** (coleta cauda + mantém vigente + pin de review +
  não-terminal + retenção-não-vencida + kill-switch + best-effort company + auditoria SYSTEM).
- **Repos** (métodos novos, `RowRetentionRepositoryTest` **4/4**): `IssueRepository
  .findByCompanyIdAndStatusIn`; thumbnail/artifact `.countByCompanyIdAndIssueId` + `.deleteByIdIn`
  (bulk JPQL `@Modifying @Transactional` — nunca carrega o content 2MB); `HeartbeatRunEventRepository
  .findIdsByRunIdIn(Pageable)` + `.countByRunIdIn` + `.deleteByIdIn`; `ReviewRepository
  .findRunIdsByCompanyIdAndIssueId` (os pins).
- **`ActivityAction.ROWS_COLLECTED`** (novo valor; switches de `ApprovalActionType` não são afetados).
- **`HeartbeatScheduler.reapRows()`** `@Scheduled ${orquestrador.rows.gc.sweep-ms:3600000}`.
- Configs por `@Value` (como o `workspace.gc.*`, NÃO no application.yml): `orquestrador.rows.gc.{enabled:
  true, retention-days:30, sweep-ms:3600000, chunk-size:500}`.

### O que FALTA (a próxima sessão) — a prova viva §C.4, VENDO
1. Seed de cauda na **Thumbnail Demo `thumb-demo-9368`**: subir a app v2 N vezes (smoke da 0023) → N
   thumbnails por-run; N versions de artifact; muitos run_events. `SELECT count(*)` das 3 tabelas ANTES.
2. `rows.gc.retention-days=0` TEMPORÁRIO (rebuild com `-Dorquestrador.rows.gc.retention-days=0` +
   `sweep-ms` curto, OU esperar o sweep) → o sweep coleta a cauda.
3. **VENDO no board (o coração)**: contagem cai MAS o **último thumbnail continua renderizando** (o
   `<img>` na Issue e em Servidores), a **foto v1 vigente abre**, a **versão aprovada por review abre**,
   o **StepTrace de um run recente/não-terminal aparece**.
4. **1 evento `ROWS_COLLECTED`** por company no feed (ator SYSTEM, detail `{thumbnail,artifact,run_events}`).
5. Falha sintética numa company → WARN, sweep segue (best-effort) — opcional VENDO (já provado em teste).
6. **Restaurar `rows.gc.retention-days=30`** + rebuild/restart final (estado de repouso = default).

### Gotchas herdados p/ a prova viva (§G)
- DB: `docker exec -i agente-orquestrator-db-1 psql -U orquestrador -d orquestrador` (`-i` p/ heredoc).
- Rebuild = pid NOVO (conferir `lsof -iTCP:8080`); log em `/tmp/orq-f14.log`. macOS: `gtimeout`.
- Board user `b0a7d000-0000-4000-8000-000000000001` (header `X-Board-User`). Runs reais 2–4min.
- `DatabaseClock` REUSADO (não coagir `now()` na mão — o bug de 3h da F.12). Seam `ExecutionTarget`
  NÃO tocado (esta fatia é PURE-DB). Contratos provados intocados.

---

## §RESULTADO FINAL — F.14 FECHADA (prova viva §C.4 VENDO, 2026-07-06)

**A fatia está FECHADA.** A prova viva §C.4 rodou na sujeira real (company Thumbnail Demo
`thumb-demo-9368` / `8de8ab8e-d50e-4ae2-803f-27511a2cd59a`), o invariante B3 foi provado **VENDO**, e o
estado de repouso (`retention-days=30`) foi restaurado. ZERO migração confirmada em runtime.

### O que foi provado (VENDO, em ordem)
1. **Seed da cauda** (mantendo o vigente REAL intacto): na Issue terminal DONE `dfdb97f1` (que carrega o
   thumbnail real da F.13 `46c741da`, 322241 B @ `00:56:13`), semeei +3 thumbnails superseded (created_at
   ANTERIOR ao vigente → o real segue sendo o máx), +3 artifacts (v1/v2/v3), +1 run `a3a3a3a3` com 12
   `heartbeat_run_event`. Contraste: Issue NÃO-terminal `b1b1b1b1` (IN_PROGRESS) + run `b2b2b2b2` + 5
   eventos (o StepTrace que deve sobreviver). Snapshot ANTES do sweep: thumbnail=**4**, artifact=**3**,
   run_events(terminal)=**12**, run_events(contraste)=**5**.
2. **Sweep** com `-Dorquestrador.rows.gc.retention-days=0 -Dorquestrador.rows.gc.sweep-ms=5000` (restart
   sem rebuild). Log do reaper (1 linha/company), na nossa:
   `GC rows company 8de8ab8e-…: 3 thumbnail, 2 artifact, 12 run_events coletado(s)`.
3. **O coração — a cauda cai, o vigente SOBREVIVE** (contagem DEPOIS): thumbnail **4→1** (só o vigente
   `46c741da`), artifact **3→1** (só a **v3** = máx version), run_events(terminal) **12→0** — mas o **run
   `a3a3a3a3` FICA** (SUCCEEDED; só os filhos-evento graduaram); run_events(contraste) **5→5**.
4. **VENDO no board (`:3000`)**: na Issue terminal, o `<img>` do vigente renderiza (naturalWidth 1280×800,
   `complete`) e o artifact vigente **v3** abre no preview (v1/v2 graduaram → o seletor mostra só o
   vigente = a "graduação" de B3); na Issue não-terminal o StepTrace **continua** (`/runs/b2b2b2b2-…/steps`
   serve os 5 passos; `/runs/a3a3a3a3-…/steps` vem `[]` com o run ainda SUCCEEDED); `ROWS_COLLECTED`
   aparece na ATIVIDADE RECENTE do Mission Control.
5. **Prova durável do vigente por API** (DEPOIS da coleta, com retention=0):
   `GET …/preview-server-thumbnails/46c741da/content` → **HTTP 200 · image/png · 322241 bytes** (PNG real
   1280×800, confirmado por `file`).
6. **Auditoria** — exatamente **1** evento por company: `action=ROWS_COLLECTED`, `actor_type=SYSTEM`,
   `actor_id=NULL`, `entity_type=company`, `entity_id=8de8ab8e-…`,
   `detail={"thumbnail":3,"artifact":2,"run_events":12}`. (3 no total = 1 por company varrida.)
7. **Restauração do repouso**: backend reiniciado SEM os -D → `java -jar …` puro (**retention 30 /
   sweep 1h**), pid 37152, health UP, Flyway "No migration necessary", vigente ainda serve HTTP 200 png.

### Consequências honestas (blast radius do `retention=0`)
- O sweep é GLOBAL (o reaper varre TODAS as companies). Com `retention=0` as outras duas demo-companies
  tiveram a cauda de `run_events` de suas Issues terminais coletada: `b92cb312-…` = **72** e
  `142f1ac5-…` = **338** run_events (0 thumbnail, 0 artifact — nada durável tocado). É IRREVERSÍVEL e é o
  comportamento projetado (só cauda superseded de Issue terminal; o vigente e o StepTrace de não-terminal
  sobrevivem). Em repouso (retention 30) só ocorreria para Issues 30d+ terminais.
- **Seed de prova sobrevivente** deixado como evidência viva inspecionável (o operador remove quando
  quiser): Issue `b1b1b1b1` + run `b2b2b2b2` + 5 eventos; artifact `a2a2a2a2-…-003` (v3) + run
  `a3a3a3a3` na Issue `dfdb97f1`. O vigente REAL da F.13 (`46c741da`, run `9c364a63`) e o evento de
  auditoria (imutável por V8) NÃO foram tocados. (SQL de seed em `scratchpad/f14-seed.sql`.)

### Veredito
**PASS** — o invariante "eterno = vigente + fixado, NÃO cada row" (B3) está provado VENDO na sujeira real:
a cauda superseded graduou, o registro durável sobreviveu (thumbnail vigente + artifact v3 + StepTrace de
run recente), a auditoria SYSTEM-resumo caiu com as contagens certas, e o repouso (retention 30) está
restaurado e saudável. **F.14 FECHADA.**

---

## §RESULTADO FINAL — ADENDO (2026-07-06): o braço FIXADO-POR-REVIEW provado VENDO (fecha o ÚLTIMO braço de B3)

A prova viva original cobriu o vigente (thumbnail máx `created_at` + artifact máx `version`), o run que
fica e o StepTrace de Issue não-terminal — mas o braço **"versão superseded FIXADA por review sobrevive"**
tinha sido provado só em teste (`RowRetentionReaperTest`), não VENDO. Esta prova fecha esse braço.
**Suíte segue 395 (piso)**: zero código tocado, zero rebuild — o MESMO jar provado; restart só com `-D`.

### Seed dedicado (Issue nova, NÃO reusa dfdb97f1 nem b1b1b1b1)
Na company Thumbnail Demo (`8de8ab8e-…`): Issue **`f14af00d-…001`** DONE com `updated_at = now()-2d`
(terminal vencida p/ retention=0), 3 runs SUCCEEDED (`f14a0a0a`/`f14a0b0b`/`f14a0c0c`) e 3 versions de
`preview_artifact`:
- **vA** `f14aaaa1-…001` (v1, run RA) — superseded **FIXADA**: `review` **`f14ae0e0-…`** APPROVED,
  `run_id=RA`, reviewer BOARD `b0a7d000-…` (o pin é DERIVADO da existência da review —
  `ReviewRepository.findRunIdsByCompanyIdAndIssueId` —, não do verdict; B3/B4).
- **vB** `f14abbb2-…002` (v2, run RB) — superseded **NÃO-fixada** (o alvo da coleta).
- **vC** `f14accc3-…003` (v3, run RC) — **VIGENTE** (máx version).
ANTES: `count(*)` = **3**; API servia as 3 (`GET …/preview-artifacts/{id}/content` → 3× HTTP 200).
(SQL do seed em `scratchpad/f14-seed-review-pin.sql` da sessão da prova.)

### O sweep e o VENDO (em ordem)
1. Restart `-Dorquestrador.rows.gc.retention-days=0 -Dorquestrador.rows.gc.sweep-ms=5000` (sem rebuild),
   health UP, Flyway "No migration necessary". Linha do reaper no 1º sweep:
   `GC rows company 8de8ab8e-…: 0 thumbnail, 1 artifact, 0 run_events coletado(s)` — **exatamente 1**.
2. **artifact 3→2**: sobrevivem **vA (v1, fixada)** E **vC (v3, vigente)**; coletada **só a vB**. Os 3
   runs FICAM (hub de FK) e a review segue APPROVED apontando RA — **a evidência de review nunca orfana**.
3. **Por API (com retention=0 ainda ativo)**: vA → **HTTP 200** com o HTML íntegro (**"a aprovada abre"**,
   mesmo superseded); vB → **404**; vC → **200**. O seletor de versões da Issue
   (`GET …/issues/f14af00d-…/preview-artifacts`) mostra **[v3, v1]** — a "graduação" de B3: vigente +
   fixada, sem a cauda.
4. **Auditoria**: exatamente **1** evento novo `ROWS_COLLECTED`, `actor_type=SYSTEM`, `actor_id=NULL`,
   `entity=company 8de8ab8e-…`, `detail={"artifact": 1, "thumbnail": 0, "run_events": 0}` — contando a vB.
5. **Repouso restaurado**: kill + `java -jar …` puro (SEM -D → retention 30 / sweep 1h), **pid 39177**,
   health UP, `lsof -iTCP:8080` confirma cmdline sem -D; vA continua HTTP 200 no repouso.

### Veredito do braço
**PASS** — com retention=0 (o pior caso), a versão superseded **fixada por review sobreviveu** ao lado da
vigente, e só a superseded não-fixada graduou. **B3 agora está 100% provado VENDO em todos os braços**
(vigente thumbnail · vigente artifact · StepTrace não-terminal · run-que-fica · **fixado-por-review**).
Seed desta prova (Issue `f14af00d`, runs `f14a0a0a/0b0b/0c0c`, artifacts v1+v3, review `f14ae0e0`) fica
como evidência viva inspecionável — remoção é decisão do operador, como o seed sobrevivente anterior.

### Adendo do adendo (2026-07-06): finding de FRONT `feed.ts:47` corrigido VENDO
Único finding aberto da auditoria de overlays: o feed do Mission Control rotulava ator **SYSTEM** como
"board" (linha anterior ao `ActorType.SYSTEM` da F.12) — a UI mentia a atribuição que o **B7 do ADR-0028** e
o **ADR-0026 (Atlas)** exigem correta, com o banco já certo (`actor_type=SYSTEM, actor_id=NULL`). Fix **só de
exibição** em `frontend/src/lib/feed.ts`: fatia própria por tipo de ator, `SYSTEM → "sistema"` antes do
fallback "board" (AGENT segue nome/`"agente"`). **VENDO** no feed da Thumbnail Demo (`thumb-demo-9368`): os 3
eventos SYSTEM (2× `ROWS_COLLECTED` + 1× `WORKSPACE_COLLECTED`) foram de "board" → **"sistema"**, e os 4
`PREVIEW_SERVER_*` (BOARD real) seguem "board" — screenshot antes/depois. Backend/banco/reaper intocados
(health UP); suíte front **196 verde** (+2 casos `SYSTEM`/`BOARD` em `feed.test.ts`); nada commitado (repo sem git).
