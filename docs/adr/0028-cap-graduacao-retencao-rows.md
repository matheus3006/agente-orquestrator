# ADR-0028: cap+graduação da retenção de rows — o TERCEIRO reaper da casa (RowRetentionReaper, PURE-DB)

**Status:** Aceito (2026-07-06, execução F.14 — `docs/handoff-grilling-cap-graduacao-rows-fechado.md`)

**EMENDADA 2026-07-20 (ADR-0049): a premissa "terminal = nunca mais volta" DEIXOU de valer também
para este reaper** (ver §Revisão no fim)

## Contexto
A F.12 (ADR-0026) coletou os **workspaces** (dirs/volumes) mas deixou uma premissa explícita: *"as
rows do banco ficam — banco inteiro < 10 MB"*. A F.13 (ADR-0027) **invalidou** essa premissa: criou
`preview_server_thumbnail`, uma tabela **por-run, sem dedup, `bytea` até 2MB por linha** (shot real
medido: 322 KB). É a primeira tabela que faz o Postgres crescer em **MB→GB**, e ela FICA por design
(o `WorkspaceReaper` coleta workspaces, **nunca** rows). Somam-se `preview_artifact` (HTML/TEXT até
2MB, por-versão) e `heartbeat_run_event` (o firehose do StepTrace, 1 row por passo de todo run):
**3 fontes append-only, zero teto**. É o 3º e último crescimento sem-teto da casa.

Evidência (exploração de código 2026-07-06, §E do handoff): **zero trigger de imutabilidade** nas 3
tabelas (só `V8:trg_activity_log_immutable` existe → DELETE é mecanicamente permitido); a teia de
FKs **fixa a evidência de review** (tudo referencia `heartbeat_run(id)`; NADA referencia as 3
tabelas-alvo como PAI → deletar suas rows é FK-safe); **3 perfis de acúmulo distintos** (thumbnail =
por-run, sem dedup, maior ofensor de BYTES; artifact = por-versão, deduped, moderado; run_event =
por-passo, ofensor de CONTAGEM). O desenho foi forçado por restrições DURAS do schema e reusa o
padrão já pesquisado da F.12 (reaper agendado + TTL-terminal + `DatabaseClock` + `ActorType.SYSTEM`).

## Decisão
Um TERCEIRO reaper, par do `WorkspaceReaper`/`PreviewServerSupervisor`/`LeaseReaper`:
**`RowRetentionReaper`** agendado no `HeartbeatScheduler`, que **capa** o crescimento das 3 tabelas
append-only **sem apagar o registro durável**, via TTL desde a transição terminal da Issue, com
auditoria durável. **A diferença estrutural do WorkspaceReaper**: workspaces moram no Docker/
filesystem (exigiram o seam `ExecutionTarget`); **rows moram no Postgres** → a remoção é um **DELETE
em lote por repositório/jOOQ**, **PURE-DB**, **sem** tocar `ExecutionTarget`, **sem** split de
target, **sem** guard de path. Este reaper é *mais simples* que o da F.12.

### B1. Natureza: 3º reaper agendado, molde da casa — mas PURE-DB (sem o seam `ExecutionTarget`)
`RowRetentionReaper` — módulo profundo: `sweepOnce()` público (kill-switch + loop das companies) +
`sweepCompany()` (núcleo profundo testável), agendado no `HeartbeatScheduler`, configs `@Value`,
teste com retenção curta. **Rejeitadas**: estender o `WorkspaceReaper` (mistura filesystem/docker vs
Postgres num módulo); job SQL puro / cron externo (fora da observabilidade da casa, sem auditoria no
`activity_log`, sem kill-switch tipado); `@SQLDelete`/cascade no ORM (deleção implícita,
não-auditável).

### B2. Política v1 = TTL desde a TRANSIÇÃO TERMINAL (reusa a âncora provada da F.12)
- Configs: `orquestrador.rows.gc.enabled:true` · `…retention-days:30` · `…sweep-ms:3600000` (1h).
- **Âncora = `issue.updated_at`** comparada **SEMPRE com o relógio do BANCO** (`DatabaseClock` — o
  mesmo que a F.12 provou; o bug de 3h que o TDD da F.12 pegou não se repete se reusar a classe, não
  coagir `now()` na mão). Só entram no colecionável rows de Issues **TERMINAIS** (DONE/CANCELLED)
  **vencidas** (`updated_at + retention < now-do-banco`). Direção de falha conservadora: toque
  posterior só ADIA.
- **Por que 30 dias (e não os 7 dos workspaces)**: a *versão vigente* é eterna (sem TTL — B3); o TTL
  governa só a **cauda superseded**. Namespace próprio (`rows.gc.*`) para afinar sem mexer no
  `workspace.gc.*`.
- **Rejeitadas**: cap por bytes/contagem com histerese/LRU (a camada madura §H — LRU pode evictar o
  vigente de uma Issue quente); TTL desde a criação (comeria cauda de Issue de vida longa); default
  desligado K8s-style (a dor volta calada); âncora `terminal_at` dedicada (migração sem necessidade).

### B3. A RECONCILIAÇÃO "ETERNA" — afia, não quebra, o invariante citado por 0021/0023/0026/0027
Quatro ADRs venderam durabilidade com *"a foto/thumbnail é eterna"*. Esta fatia **afia**: **eterno =
a versão VIGENTE por-Issue + as versões FIXADAS por evidência (review), NÃO cada row append-only.**
Coletável = a **cauda superseded, não-fixada, de Issue terminal vencida.**
- **`preview_server_thumbnail`**: mantém o **último por-Issue** (V22: "o vigente é o ÚLTIMO", a mesma
  regra de exibição — `findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc`). Coleta os thumbnails de
  runs anteriores. **Sem pin de review** (thumbnail é só-exibição — nada o referencia). O `<img>` que
  a F.13 provou sobreviver ao GC **continua sendo o vigente → continua eterno.**
- **`preview_artifact`**: mantém a **maior `version` por-Issue** (o que a UI mostra por padrão) **+**
  qualquer versão cujo `run_id` seja referenciado por uma **`review`** (evidência "R aprovou a v3").
  Coleta as versões superseded e não-referenciadas. A foto v1 vigente **continua eterna**; a
  evidência de review **nunca orfana.**
- **Consequência aceita (a metade "graduação")**: o seletor de versões de uma Issue terminal antiga
  passa a mostrar a vigente + as fixadas por review, não o histórico completo. Para binário/evento não
  há tier semântico pra promover (ao contrário do decision-ledger da ADR-0019): aqui "graduação" = *a
  vigente/fixada É o registro promovido; a cauda é o rascunho que sai*.
- **Rejeitadas**: apagar por run sem exceção de vigente (quebraria o VENDO da F.13 e o "eterna" de
  0021); manter tudo eterno e só alertar (não fecha o débito); pin manual opt-in (superfície +
  armadilha de pin-pós-expiração — aqui o "pin" é DERIVADO da review, não uma feature).

### B4. Dois mecanismos numa cabeça só: a política pura `RowRetentionPolicy`
O núcleo profundo é uma **política pura** (sem DB): entra o status/`updated_at` da Issue + as rows
candidatas + os run_ids fixados por review + `now`/retenção; sai a decisão (thumbnails a coletar,
artifacts a coletar, se coleta os run_events). Duas formas espelhando os dois perfis:
- **Forma A — keep-current-per-issue (thumbnail + artifact)**: mantém o vigente (+ pins de review no
  artifact), coleta o resto da Issue terminal-vencida.
- **Forma B — TTL-por-run (`heartbeat_run_event`)**: quando a Issue é terminal-vencida, coleta os
  eventos de **todos os runs dela**; o **run permanece** (é hub de FK — review/approval/artifact/
  thumbnail/cost/conversation apontam pra ele); só os **filhos-evento** vão. É count-heavy.
- **Rejeitadas**: um DELETE só com WHERE genérico (não distingue "manter vigente" de "coletar cauda");
  3 reapers separados (3× o boilerplate; a política pura já separa as formas).

### B5. Escopo do colecionável + o que é PROTEGIDO por construção
- **Coleta**: rows das 3 tabelas ligadas a Issue **terminal vencida**, **exceto** o vigente/fixado.
- **Protegido por construção**: Issue **não-terminal** (nunca entra); Issue terminal **não-vencida**;
  a **versão vigente** por-Issue; versão **fixada por review**; a tabela **`activity_log`** (trigger
  V8; nem é alvo); o próprio **`heartbeat_run`** (hub de FK — só os filhos-evento coletam); todas as
  **outras tabelas** (allowlist ESTRUTURAL de 3 tabelas, não denylist).
- **Eventos de run SEM Issue** (`heartbeat_run.issue_id` NULL — turnos de conversa do CEO): **fora do
  v1** (§H). Âncora diferente (turno, não transição de Issue); v1 mantém a âncora única "por-Issue
  terminal". Por construção: só deletamos eventos de runs obtidos por `findByIssueId…` → runs de
  issue_id NULL nunca são tocados.

### B6. Mecânica: lote + re-read fresco + best-effort não-abortante
- **World-driven pela derivação do banco**: 1×/ciclo, por company, lista as Issues terminais
  (`IssueRepository`), a política aplica o gate terminal+vencida, resolve as rows candidatas, DELETA
  em **lote limitado** (chunk — nunca um `DELETE` de tabela inteira numa transação gigante).
- **Re-read fresco** imediatamente antes de processar cada Issue (a Issue ainda é terminal? — fecha a
  race com REOPEN/checkout).
- **Best-effort não-abortante em DOIS níveis**: falha ao coletar uma Issue nunca aborta as demais da
  company (WARN + segue); falha numa company nunca aborta as demais companies (WARN + segue). O sweep
  **nunca** participa da máquina de estados.
- **Rejeitadas**: DELETE único sem chunk (transação longa travando o pool); deleção assíncrona via
  fila (§H); marcar `collected_at` no banco (estado especular + migração — o mundo/derivação é a
  verdade).

### B7. Auditoria: evento-RESUMO por sweep, ator SYSTEM, detail só-contagens
Evento novo `ActivityAction.ROWS_COLLECTED` no `activity_log`, **1 por ciclo-de-sweep-por-company**
(não 1 por row — seria dilúvio), `detail` só-contagens (`{thumbnail:n, artifact:n, run_events:n}` —
ADR-0009, números não são PII), ator **`ActorType.SYSTEM`** (reusa o ator que a F.12 criou — o reaper
é ato do PIPELINE; o switch exaustivo do `CompanyAccessGuard` já tem `case SYSTEM -> false`).
`entity` = a company. Gravado **após** a remoção real e só quando de fato coletou algo (nunca um falso
"coletado").
**Rejeitadas**: evento por row (flood); auditar como BOARD/AGENT (mente a atribuição); não auditar.

### B8. Config + ZERO migração de schema
`orquestrador.rows.gc.{enabled:true, retention-days:30, sweep-ms:3600000}` (+ `chunk-size:500`,
detalhe de implementação do lote). **ZERO migração** (espelha a F.12): lógica + config + DELETE sobre
tabelas que já existem; nenhuma coluna nova, nenhum trigger. Índice de apoio à varredura é otimização
FUTURA (§H) — as tabelas já têm `(company_id, issue_id)` e `run_event(run_id, created_at)`.

### B9. Deny-by-default: o agente não declara NADA
Como os 2 reapers anteriores, é coleta pura do pipeline sobre dados que o pipeline mesmo produziu.
Zero contrato de lugar novo, zero canal agente→core. A superfície de deny-by-default não muda.

## Riscos → estratégias
- **Coletar o registro vigente (quebrar o "eterna")** — a política mantém EXATAMENTE a regra de
  exibição (thumbnail: máx `created_at` = `findTop…OrderByCreatedAtDesc`; artifact: máx `version`,
  única por Issue) + os pins de review; testada PURA. A prova viva confirma VENDO que o último
  thumbnail/foto/versão-de-review seguem renderizando após a coleta com `retention-days=0`.
- **Coletar demais / cedo** — colecionável restrito a Issue TERMINAL + retenção + re-read fresco;
  allowlist estrutural de 3 tabelas. Direção de falha do TTL é conservadora (adia, não antecipa).
- **Transação gigante travando o pool** — DELETE em chunk (cada lote = 1 transação limitada), nunca
  tabela inteira; o sweep é best-effort e pode retomar no próximo ciclo.
- **Clock skew / reset no restart** — âncora durável (`updated_at`) + relógio único do banco
  (`DatabaseClock`, reusado — não coagir `now()` na mão).
- **Deleção sem rastro** — `activity_log` (append-only por trigger V8) é o órgão de auditoria; o
  evento-resumo é gravado APÓS a remoção REAL.
- **FK / evidência de review orfanada** — deletar rows das 3 tabelas é FK-safe (nada as referencia
  como PAI); o "pin" de review preserva a versão exibida por escolha SEMÂNTICA.

## Recorte explícito (fora desta fatia — §H do handoff)
Caps por bytes/contagem/histerese/LRU (a camada madura sobre o TTL; deep-research paga aí, não no
v1) · `run_event` de runs SEM Issue (turnos de conversa do CEO, `issue_id` NULL — âncora diferente,
fatia própria; **resolvido na F.15, ADR-0029**) · graduação SEMÂNTICA de conteúdo (não se aplica a binário/evento) · delete
assíncrono (Resource Disposer) · índice de apoio à varredura (só se o profiler pedir) · pin manual
opt-in (o "pin" aqui é derivado de review) · não reabrir ADRs 0001–0027; parser, contratos de lugar
e o seam `ExecutionTarget` **não mudam** (esta fatia é PURE-DB).

## Relaciona
ADR-0026 (1º/2º reaper — `ActorType.SYSTEM`, `DatabaseClock`, âncora TTL-terminal; o molde reusado) ·
ADR-0021 (foto v1 eterna — o invariante que B3 afia) · ADR-0027 (o thumbnail que criou a 3ª fonte e
disparou o gatilho) · ADR-0012 (estados terminais vs não-terminais) · ADR-0009 (activity_log, detail
só-IDs/contagens) · ADR-0010 (módulo profundo — `RowRetentionReaper`/`RowRetentionPolicy`) ·
ADR-0019 (decision-ledger — o contraste de "graduação semântica" que aqui NÃO se aplica).

## Revisão 2026-07-20 (reabertura de entrega aprovada, ADR-0049)

> Emenda recíproca do PR da rodada 2 do parecer, gêmea da §Revisão da ADR-0026. A ADR-0049
> §Emendas declarava emendar esta ADR e **nunca a tinha tocado** (conferência do PR #53, 🟠 3).

**O que mudou: a PREMISSA, não o comportamento.** `RowRetentionPolicy.collectible` segue
`isTerminal(status) && updated_at < now - retention`, com `isTerminal` = `{DONE, CANCELLED}` — o
mesmo molde herdado da ADR-0026. A ADR-0049 tirou de `DONE` a condição de sink
(`tryReabrirAprovada`, `DONE` → `BLOCKED`), então a âncora TTL-desde-terminal deste reaper passou a
medir um estado do qual **se volta**.

**Por que aqui dói MENOS que na ADR-0026, com o porquê explícito:**

- este reaper já tem **exceção de pin** — o artifact fixado por uma review é preservado mesmo
  superseded (B3), então a evidência "R aprovou a v3" sobrevive à reabertura. O `WorkspaceReaper`
  não tem exceção nenhuma, e é por isso que a cerca de 409 nasceu lá e não aqui;
- a retenção default daqui (**30 dias**) é 4× a de lá (**7**), então nos defaults a cerca do
  workspace já recusou a reabertura muito antes de qualquer row vencer.

**⚠️ O segundo ponto é coincidência de configuração, não invariante.** As duas retenções são
propriedades independentes (`orquestrador.rows.gc.retention-days` e
`orquestrador.workspace.gc.retention-days`); invertê-las é permitido e nada no código as amarra.
Com `rows < workspace`, uma reabertura passaria pela cerca do workspace e encontraria
`heartbeat_run_event` e thumbnails já colhidos — perda parcial de rastro, silenciosa. Fica
registrado como limite conhecido desta emenda, **não** como proteção existente: nenhuma linha de
código hoje verifica essa ordem.

## Relaciona (acréscimo de 2026-07-20)
ADR-0049 (reabertura de entrega aprovada — a ADR que mudou a premissa acima).
