# Handoff — Grilling do Review gate + risco (2ª metade do ADR-0006) — DECISÕES FECHADAS

> Sessão `grill-with-docs` (domain-modeling), 2026-07-02. Fechou as 5 decisões abertas do **review gate** —
> a última invariante de governança (#4) ainda não construída. **Nenhum código de feature foi escrito** —
> só decisões. Fonte de entrada: `docs/handoff-grilling-approval.md`. Este doc é a fonte para a sessão de
> implementação aplicar: emendas de ADR, ADR novo, mudanças de CONTEXT.md e a lista ORDENADA de mudanças.
> **Resultado-chave: v1 = review gate ESPECÍFICO (pós-hoc), disparado por RISCO (1-5), revisado pelo BOARD;
> reject reusa 100% o recovery do §7. UMA migration nova (V11 — `risk_level` + tabela `review`).**

---

## 0. Ponto de partida
- **72/72** testes offline; migrations **V1→V10** validadas em Postgres 16.14 real (zero drift).
- Fase 0 + Fase 1 + **Fase 2 inteira** (incl. 7c operador-only + §7 recovery park-for-human) prontas.
- Faltava só a **2ª metade do ADR-0006** (review gate + primitiva de Approval) — agora fechada.

**Fatos aterrados no código nesta sessão (não re-derivar):**
- `issue.status` = **`text not null default 'OPEN'`, SEM `CHECK`, SEM enum de banco** (V2__issues.sql:9) →
  adicionar `PENDING_REVIEW` **custa ZERO migration** (igual `BLOCKED` na §7).
- `agent.reports_to_id` = **`uuid references agent(id)`, NULLABLE** (V1__init.sql:35), self-reference
  agente→agente → "revisor derivado de `reports_to`" = revisor é **outro agente** = constraint #5 (adiado).
- `IssueService.settleAfterRun(companyId, issueId, runId, RunOutcome)` (IssueService.java:63) já é o ponto de
  interceptação; hoje o branch `outcome.succeeded()` faz `tryComplete → DONE | NOT_SETTLED`. `IssueSettlement`
  hoje = `COMPLETED / BLOCKED / NOT_SETTLED`. As transições são UPDATEs nativos guardados por
  `execution_lock_run_id = runId AND status = 'IN_PROGRESS'`.

---

## A) Decisões fechadas (por pergunta)

### Q1 — o PIVÔ (escopo): **review gate ESPECÍFICO, pós-hoc — UMA primitiva.**
v1 constrói o **review gate específico** (revisão do output de um run concluído), no `settleAfterRun`.
A **Approval-de-ação genérica** (autorizar uma ação ANTES de executá-la) **NÃO** se constrói agora.

**Razão (vai pro ADR como o *porquê*):** "review gate" (pós-hoc — o trabalho já aconteceu, você revisa o
resultado) e "approval request" (pré-hoc — você autoriza a ação antes) têm **forma temporal oposta**. Os **dois**
outros consumidores do genérico — **agente-como-tool** e **config-change** — são **ambos pré-hoc e ambos já
adiados**. Generalizar agora = abstrair de **um** exemplo pra servir **dois consumidores hipotéticos de forma
diferente** (a pior abstração especulativa). Além disso, o genérico **não pouparia** o trabalho do lado-Issue: o
review é **soldado** à máquina de estados da Issue (é ele que empurra `IN_PROGRESS → DONE`); um `approval` genérico
"ao lado" só adiciona uma tabela pra sincronizar, sem remover nada. Mesmo movimento que fechou a 7c: *"não
introduza um seam a menos que algo varie através dele"* (ADR-0010). A Approval-de-ação genérica nasce **quando o
agente-como-tool entrar** — aí com **dois exemplos reais** (agente-tool + config-change) pra desenhar a interface.

**Sub-decisão "duas aprovações" (o usuário quer aprovar o PLANO e depois o RESULTADO):** isso **NÃO** é uma
segunda máquina nem flipa a Q1. É **o MESMO review gate disparado em DUAS Issues**:
1. **Issue-plano** (ArchitectBot roda → produz o plano) → gate → você aprova o plano.
2. **Issue-implementação** (DevBot roda → produz o código) → gate → você aprova o resultado.

"Aprovar o plano" **é** revisar o output do run do architect (pós-hoc), que por acaso acontece *antes* da
implementação. Você ganha o sabor "aprovo antes de implementar" **sem** uma segunda primitiva pré-hoc.
- **Fora do v1 (confirmado):** a Issue-impl **esperar automaticamente** o plano ficar `DONE` = **dependência entre
  Issues** (feature própria; no v1 a sequência é **manual** — você só abre a Issue-impl depois de aprovar o plano).
- **Alternativa MORTA:** "uma Issue só que pausa no meio" (o agente planeja, para, espera aprovação, retoma) —
  exige **canal inbound** pro container selado (recebe "aprovado, siga"), furando isolamento (#3) e o "StepSink só
  de saída" (#2). É o constraint #5. **Rejeitado**: plano e implementação são **runs/Issues separados**.

**Forma do plano (técnico p/ o DevBot seguir + resumo humano com motivo/abordagem/riscos/mitigações):**
**contrato da SKILL do agente de arquitetura** (skills-in-DB) + UI do **diferencial #4** (visibilidade de
plano/step). **Fora do schema do review gate** — o gate fica **artifact-agnostic** (estaciona a Issue, te mostra o
output do run, registra o motivo da decisão). **Sem entidade `plan` nesta fatia.**

### Q2 — o GATILHO ("exige revisão"): **`risk_level` 1-5 na Issue + limiar.**
Uma escala de risco **1-5** (1 = sem risco; 5 = alto risco, exige approve humano). O `requires_review` booleano é
só o caso degenerado disto (`= risco ≥ limiar`).
- **Modelo:** coluna **`risk_level int not null default 1`** na Issue (V11). Gate = **`risk_level ≥ limiar`**.
  Limiar = **config global** `orquestrador.review.risk-threshold`, default **5**, tunável (não coluna por-company
  no v1). Guarda o 1-5, mas o gate v1 é **só um limiar** (não roteia por-nível).
- **Fonte no v1 = você seta** (por-Issue, na criação). **Sem** default-de-risco por-agente/role no v1 (follow-up
  barato). A avaliação de risco do **architect** (impacto / custo de ajuste / dificuldade de detecção — "problema
  silencioso é grande problema" / número de riscos) é **conteúdo do resumo humano do plano**, que te ajuda a
  **setar** o `risk_level` — **NÃO** um número que o sistema confia.
- **Por que o agente não dá o número que decide (vai pro ADR):** (a) fura o ADR-0006 (revisão runtime-enforced,
  não confiada ao agente — "o agente esqueceu/pulou"); (b) o risco que mais machuca (o **silencioso**) é
  **exatamente** o que o agente mais subestima (se ele enxergasse, não seria silencioso); (c) precisaria do canal
  agente→core (constraint #5). **Quando o número do agente eventualmente fluir (futuro): regra escala-só-pra-cima**
  — o agente pode **SUBIR** o risco acima de um **piso** que você define, **nunca ABAIXAR**. Assim você ganha o
  alarme dele sem entregar o poder de te esconder algo.

### Q3 — quem REVISA: **board/humano (você). Agente-revisor adiado.**
Solo v1: há um humano ("o approve no caso meu"). Agente-como-reviewer cai no constraint #5 (aprovar = AGIR no core
autenticado, o mesmo obstáculo que adiou o agente-como-tool) e no `reports_to` apontar pra outro **agente**.
Registrar agente-revisor (via `reports_to`) como **fatia futura**, mesmo espírito da Rota A da 7c.

### Q4 — a MÁQUINA DE ESTADOS: **novo `PENDING_REVIEW`; approve→`DONE`; reject→`BLOCKED` (reusa §7).**
- **Novo status `PENDING_REVIEW`** entre `IN_PROGRESS` e `DONE` (**zero migration** — `status` é `text`).
- **O gate mora no `settleAfterRun`, branch de sucesso:** `if risk_level ≥ limiar` → `trySubmitForReview`
  (→ `PENDING_REVIEW`, **libera o lock**, cria a `review` PENDING + evento `ISSUE_SUBMITTED_FOR_REVIEW`, **na
  MESMA tx**); `else` → `tryComplete` (→ `DONE`, como hoje). **Falha continua → `BLOCKED` (§7 intacto)** — o gate
  só ramifica **dentro** do sucesso. A transição é UPDATE guardado por `execution_lock_run_id = runId AND status =
  'IN_PROGRESS'` (mesmo padrão do `tryComplete`/`tryBlock`) → **segurança grátis do guard**: run que não detinha a
  Issue casa 0 linhas → no-op (vítima intacta), igual ao §7.
- **Aprovar → `DONE`** (endpoint board-only, guard `status='PENDING_REVIEW'`; `tryApprove`).
- **Rejeitar → `BLOCKED`** (guard `status='PENDING_REVIEW'`; `tryReject`) **+ reusa 100% o `unblock` do §7**: você
  edita a Issue/plano com o feedback e usa `unblock {REOPEN|CANCEL}` já construído (REOPEN → `OPEN` mantendo o
  agente → re-roda pelo caminho normal; CANCEL → `CANCELLED`).
  - **Por que reject→BLOCKED e não →OPEN direto:** →OPEN tornaria a Issue **pegável na hora** → o próximo wakeup
    **re-roda antes** de você editar; e o agente selado (**sem canal inbound**) **não sabe** por que foi rejeitado
    → re-produz a mesma coisa (loop inútil, constraint #5). `BLOCKED` está **fora do conjunto pegável** → nada
    re-roda até você **editar e reabrir explicitamente** — que é onde o feedback entra na Issue (human-mediated).
  - **`BLOCKED` tem o significado AMPLIADO:** antes só "run falhou" (§7); agora **também** "review rejeitada".
    Parked, precisa de decisão humana. Os dois casos se distinguem pelo **evento** (`ISSUE_BLOCKED` vs
    `REVIEW_REJECTED`), não pelo status. (Diff no ADR-0011.)
  - **Alternativa rejeitada:** `reject{next: RETRY|ABANDON}` num passo — duplica a decisão do `unblock` sem ganho.
- **Interação com budget:** Issue em `PENDING_REVIEW` **não roda** (lock solto, sem run em voo) → **não gasta**
  enquanto espera. Approve/reject são ações do board (zero compute). Re-run pós-REOPEN gasta como qualquer run
  (sujeito ao hard-stop preflight). **Sem interação especial.**

### Q5 — ENTIDADE + eventos: **tabela `review` própria (agora). NÃO generalizar `BudgetIncident`.**
Decisão do usuário: construir a `review` já — é inevitável, e a V11 já vem pelo `risk_level`.
- **Forma:** `review(id, company_id, issue_id, run_id, status, reviewer_actor_type, reviewer_actor_id,
  decision_reason, requested_at, decided_at)`.
  - **Linha mutável** (nasce `PENDING` no `settleAfterRun`; vira `APPROVED`/`REJECTED` na decisão) — entidade
    **request→decision**, não log. O activity_log segue como a trilha imutável.
  - **Uma linha por ciclo** (re-run pós-reject → nova review, novo `run_id`). **No máximo 1 `PENDING` por Issue**
    → `unique(issue_id) where status='PENDING'` (parcial, **Postgres-only**; a entidade DECLARA o que o H2
    consegue, lição das fatias).
  - `run_id` **NOT NULL** (toda review corresponde a um run concluído). `reviewer_*`/`decided_at`/`decision_reason`
    nullable até a decisão. Queries **lideradas por `company_id`** (deny-by-default).
  - **`decision_reason` mora na tabela `review`** (texto livre, dado de negócio) — **NUNCA no activity_log**
    (regra PII do ADR-0009).
- **`review.id` preenche o slot `approval_id` do ADR-0009** — mas nomeado **`review_id`** no `detail` dos eventos.
  O review-gate **NÃO é ação delegada** (o sistema impôs; o agente não pediu) → os eventos referenciam
  `entity=issue` e carregam `detail.review_id`/`detail.run_id` (só IDs). O `approval_id` genérico segue reservado
  pro **agente-como-tool** (ação delegada de verdade).
- **NÃO generalizar `BudgetIncident`:** ele é incidente/override (`OPEN→RESOLVED`), forma diferente de
  request→decision. A Q1 já separou os dois.
- **`ReviewStatus`** = `PENDING / APPROVED / REJECTED` (enum Java, persistido como `text`).
- **Eventos novos (ActivityAction):** `ISSUE_SUBMITTED_FOR_REVIEW` (ator = o agente que rodou, igual ao
  `ISSUE_BLOCKED`; `detail` = `review_id`+`run_id`), `REVIEW_APPROVED` / `REVIEW_REJECTED` (ator = board;
  `detail` = `review_id`; **sem** o texto do motivo).
- **Fora do escopo (confirmado):** **config-change approval** e **agente-tool approval** — são a Approval-de-ação
  pré-hoc, adiada.

---

## B) Diffs de ADR/CONTEXT a aplicar (ready-to-apply)

### ADR-0006 (emendar — nova seção "Revisão 2026-07-02")
- **A 2ª metade (gate de revisão runtime-enforced) foi CONSTRUÍDA** — específica, pós-hoc, disparada por **risco**.
- O gate vive na função `IssueService.settleAfterRun` (a "função pura de execution-policy" da ADR): no sucesso,
  `risk_level ≥ limiar` → `PENDING_REVIEW` (intercepta o `DONE`), com a **decisão gravada na MESMA tx** (linha
  `review` + evento). Revisor = **board** (agente-revisor adiado, constraint #5).
- **Distinção que a ADR passa a registrar:** **review** (pós-hoc, sobre o output de um run) ≠ **approval-de-ação**
  (pré-hoc, autoriza antes). Só o **review** foi construído. A **Approval-de-ação genérica** (config-change,
  agente-tool) **continua adiada** — nasce com o agente-como-tool.
- **Fonte do risco no v1 = humano** (você seta). A avaliação do agente-arquiteto é advisory; quando fluir,
  **escala-só-pra-cima** acima de um piso humano (nunca de-escala) — pra não reintroduzir o "o agente pulou".

### ADR-0011 (emendar — nota sobre o `BLOCKED`)
- **`BLOCKED` passa a cobrir dois motivos:** run FAILED (§7 original) **e** review REJEITADA (esta fatia). Ambos =
  "parked, precisa de decisão humana"; distinguidos pelo **evento** (`ISSUE_BLOCKED` vs `REVIEW_REJECTED`), não
  pelo status. O `unblock {REOPEN|CANCEL}` **serve os dois** sem mudança (REOPEN mantém o agente → re-run normal).

### ADR-0009 (emendar — nota sobre review × approval_id)
- **Review NÃO é ação delegada.** Os eventos de review (`ISSUE_SUBMITTED_FOR_REVIEW`/`REVIEW_APPROVED`/
  `REVIEW_REJECTED`) referenciam `entity=issue` e carregam `detail.review_id`/`detail.run_id` (só IDs). O
  **`decision_reason` (texto livre) fica na tabela `review`, NUNCA no `detail`** (regra PII). O slot `approval_id`
  do padrão de ação delegada segue **reservado** pro agente-como-tool.

### NOVO ADR-0012 — Review gate (revisão pós-hoc disparada por risco, runtime-enforced)
> **Contexto:** autonomia 24/7 exige que trabalho de alto risco não vire `DONE` sem um humano conferir, e que isso
> seja **imposto pelo sistema** (ADR-0006), não pelo prompt. Não existia primitiva de review; `IssueStatus` não
> tinha `PENDING_REVIEW`; a Issue não tinha "exige revisão".
> **Decisão:** a Issue ganha **`risk_level` (1-5)**; no `settleAfterRun` (sucesso), `risk_level ≥ limiar` →
> **`PENDING_REVIEW`** (libera o lock, guardado por `execution_lock_run_id`), criando uma linha **`review`**
> PENDING + evento, **na mesma tx**. O **board** decide via endpoint board-only: **APPROVE → `DONE`**;
> **REJECT → `BLOCKED`**, reusando o `unblock {REOPEN|CANCEL}` do §7 (ADR-0011). Fonte do risco no v1 = **humano**;
> revisor = **board**. **UMA primitiva** (review, pós-hoc) — a Approval-de-ação genérica (pré-hoc: config-change,
> agente-tool) fica adiada. *"Duas aprovações"* (plano + resultado) = o **mesmo gate em duas Issues** (plano/impl),
> não uma segunda máquina.
> **Alternativas rejeitadas:** Approval genérica agora (abstrai de 1 exemplo p/ 2 consumidores hipotéticos de forma
> diferente; não poupa o lado-Issue); agente dá o próprio risco (fura o ADR-0006; subestima o risco silencioso);
> reject→OPEN direto (re-run prematuro + loop sem canal inbound); uma-Issue-que-pausa (canal inbound = constraint
> #5); generalizar `BudgetIncident` (incidente/override ≠ request/decision).
> **Consequências:** "o agente pulou a revisão" deixa de ser modo de falha; `PENDING_REVIEW` é observável e não
> re-roda sozinha; `review.id` serve de `review_id` no log; `decision_reason` fora do activity_log; **UMA migration
> nova (V11)**. Reserva a Approval-de-ação genérica p/ o agente-como-tool (2 consumidores reais).
> **Lentes:** `human-architect-mindset` (runtime-enforced, não prompt; escala-só-pra-cima; failure-isolation via
> guard; observabilidade via eventos) + `codebase-design` (gate no seam `settleAfterRun` que já carrega o
> `RunOutcome`; transições guardadas espelham `tryComplete`/`tryBlock`; `review` paralela ao `BudgetIncident`;
> reject reusa o §7).

### CONTEXT.md (glossário — termos a atualizar/adicionar)
- **`risk_level` (NOVO):** 1-5 na Issue (1 = sem risco; 5 = alto). Gate = `risk_level ≥ limiar`
  (`orquestrador.review.risk-threshold`, default 5). No v1 você seta; a nota do architect é conteúdo do plano.
- **Review gate / `PENDING_REVIEW` (NOVO):** run de alto risco não vira `DONE` — vai pra `PENDING_REVIEW` (lock
  solto); o board **APPROVE → `DONE`** ou **REJECT → `BLOCKED`**. Pós-hoc (revisa o output de um run concluído).
- **`review` (NOVA entidade):** request→decision (`PENDING→APPROVED/REJECTED`) por ciclo de revisão; guarda
  `run_id`, `reviewer`, `decided_at`, `decision_reason`. Máx. 1 `PENDING` por Issue.
- **"Duas aprovações" (NOVO):** aprovar o **plano** e depois o **resultado** = o mesmo gate em **duas Issues**
  (plano/impl); a sequência entre elas é **manual** no v1 (sem dependência automática).
- **Approval-de-ação (NOVO, _avoid_):** autorizar uma ação **antes** de executá-la (config-change, agente-tool) —
  **NÃO** é o review gate (pós-hoc) e **não** existe no v1; é a Approval genérica **adiada**.
- **`BLOCKED` (refinar):** agora cobre **run FAILED** (§7) **e** **review REJEITADA**; distinguidos pelo evento.

---

## C) Impactos de schema/migration — ORDENADOS

### ⭐ HEADLINE: **UMA migration nova — V11** (diferente da 7c/§7). `PENDING_REVIEW` em si = zero migration.
- **V11** adiciona `issue.risk_level` **e** cria a tabela `review`. O status novo `PENDING_REVIEW` **não** custa
  migration (`issue.status` é `text` livre).

### Migration V11 (`V11__review.sql`)
1. `alter table issue add column risk_level int not null default 1;`
2. `create table review (id uuid pk, company_id uuid not null references company(id), issue_id uuid not null
   references issue(id), run_id uuid not null references heartbeat_run(id), status text not null default 'PENDING',
   reviewer_actor_type text, reviewer_actor_id uuid, decision_reason text, requested_at timestamptz not null,
   decided_at timestamptz);`
3. Índices **liderados por `company_id`** (`idx_review_company_issue`, `idx_review_company_status`) +
   **`create unique index uq_review_pending on review (issue_id) where status = 'PENDING';`** (Postgres-only guard).

### Código ORDENADO — fatias finas, TDD + revisão adversarial
1. **`R.1` — schema + enums:** V11; `IssueStatus += PENDING_REVIEW`; novo `ReviewStatus {PENDING,APPROVED,REJECTED}`;
   `ActivityAction += ISSUE_SUBMITTED_FOR_REVIEW, REVIEW_APPROVED, REVIEW_REJECTED`; entidade `Review` (DECLARA a
   unique parcial no que o H2 força) + `ReviewRepository` (company-led); `IssueSettlement += SUBMITTED_FOR_REVIEW`.
2. **`R.2` — o gate (o coração):** `IssueRepository.trySubmitForReview(issueId, companyId, runId)` — `UPDATE issue
   SET status='PENDING_REVIEW', execution_lock_run_id=null WHERE id=:issueId AND company_id=:companyId AND
   execution_lock_run_id=:runId AND status='IN_PROGRESS'` (espelho do `tryComplete`). `IssueService.settleAfterRun`
   no branch de sucesso: carrega a Issue, lê `risk_level`; se `≥ limiar` → `trySubmitForReview` + cria `review`
   PENDING (`run_id=runId`) + `ISSUE_SUBMITTED_FOR_REVIEW` (ator=AGENT/assignee), **tudo na mesma @Transactional**,
   retorna `SUBMITTED_FOR_REVIEW`; senão → `tryComplete` (inalterado). **`AdapterRunExecutor` NÃO muda** (o seam já
   chama `settleAfterRun`; o branch é interno). Limiar via `@Value("${orquestrador.review.risk-threshold:5}")`.
3. **`R.3` — a decisão:** `IssueRepository.tryApprove(issueId, companyId)` (guard `status='PENDING_REVIEW'` →
   `DONE`) e `tryReject(issueId, companyId)` (guard `status='PENDING_REVIEW'` → `BLOCKED`). `IssueService.review(
   company, issue, decision, reason, actorType, actorId)` — APPROVE → `tryApprove` + atualiza a `review`
   (`APPROVED`, reviewer, `decided_at`) + `REVIEW_APPROVED`; REJECT → `tryReject` + atualiza a `review` (`REJECTED`,
   `decision_reason`, reviewer, `decided_at`) + `REVIEW_REJECTED`; guard 0 linhas → 409 (não está `PENDING_REVIEW`);
   **board-only**. `IssueController`: `POST /api/companies/{id}/issues/{issueId}/review {decision: APPROVE|REJECT,
   reason?}` (board-only, mesmo caminho de ator do `unblock`).
4. **`R.4` (opcional, p/ a UI):** `GET /api/companies/{id}/reviews?status=PENDING` board-only (fila de revisão).
5. **Reject → recovery reusa o §7:** nenhuma mudança no `unblock`/`tryReopen`/`tryCancel` — só documentar que
   REOPEN pós-reject é o caminho de "refazer com feedback" (editar a Issue antes de reabrir).

### Ordem recomendada
`R.1` (schema+enums) → `R.2` (gate no `settleAfterRun`) → `R.3` (endpoint de decisão) → `R.4` (fila, opcional).
`R.2` e `R.3` são as fatias com TDD+revisão adversarial mais densas.

### Testes (piso atual 72/72)
- **Gate:** run sucesso `risk_level ≥ limiar` → `PENDING_REVIEW` + `review` PENDING + `ISSUE_SUBMITTED_FOR_REVIEW`;
  `risk_level < limiar` → `DONE` (comportamento atual **inalterado**); sucesso de run que **não** detinha a Issue →
  no-op mesmo no branch de review (guard); **budget:** `PENDING_REVIEW` não cria run/gasto.
- **Decisão:** APPROVE → `DONE` + `review` APPROVED + `REVIEW_APPROVED`; REJECT → `BLOCKED` + `review` REJECTED com
  `decision_reason` gravado + `REVIEW_REJECTED`; decisão em Issue não-`PENDING_REVIEW` → 409; **board-only**
  (agente 403); **cross-tenant** 403.
- **Reuso do §7:** REJECT → `BLOCKED` → `unblock REOPEN` → re-run pelo wakeup → (novo ciclo) → concluído; máx. 1
  `review` PENDING por Issue (concorrência / unique parcial).

---

## D) Constraints / limitações do v1 a lembrar
- **Fonte do risco = humano (você seta por-Issue).** Sem default por-agente/role (follow-up barato). Auto-scoring
  pelo agente-arquiteto = **futuro** (canal agente→core); quando entrar, **escala-só-pra-cima** acima de um piso.
- **Limiar = config global** (`orquestrador.review.risk-threshold`, default 5); por-company = futuro.
- **Revisor = board/humano.** Agente-como-reviewer (via `reports_to`) = **adiado** (constraint #5).
- **Reject não tem canal de feedback pro agente:** o "porquê" tem que **entrar na edição da Issue no REOPEN**,
  senão o re-run reproduz o mesmo output (human-mediated, como o resto do v1).
- **Só review pós-hoc.** A **Approval-de-ação pré-hoc** (aprovar o plano como GATE-da-ação antes de implementar,
  config-change, agente-tool) = **primitiva genérica futura** (nasce com o agente-como-tool; config-change de 2º
  exemplo).
- **"Duas aprovações" = duas Issues** (plano/impl); **sequência manual** no v1 (sem dependência automática entre
  Issues — feature própria).
- **Forma do plano** (técnico + resumo humano) = **contrato da skill do architect + UI do diferencial #4**, NÃO
  modelada aqui. Gate é artifact-agnostic.
- **`decision_reason`** vive na tabela `review` (dado de negócio), **não** no activity_log (regra PII do ADR-0009);
  o revisor não deve colar segredos.
- **UMA migration nova (V11)** — diferente da 7c/§7 ("puro código"). `PENDING_REVIEW` em si = zero migration.
- **`BLOCKED` ampliado:** cobre run FAILED **e** review REJEITADA; distinção pelo evento, não pelo status.

### Fatias futuras reservadas (fora do v1)
- **Approval-de-ação genérica** (pré-hoc): o agente emite o pedido pelo `StepSink` de saída → core cria Approval
  PENDING → board decide → efeito no próximo run. Reusável em: agente-como-tool install, config-change, e "aprovar
  o plano como gate-da-ação". **Sem** rota inbound nem credencial run-scoped.
- **Auto-fluxo do risco do architect → `risk_level` da Issue-impl** (mesmo canal agente→core; escala-só-pra-cima).
- **Auto-fluxo do plano do architect → input da Issue-impl** (passagem de artefato entre runs).
- **Dependência automática entre Issues** (impl espera o plano ser aprovado).
- **Default de risco por-agente/role**; **limiar por-company**; **agente-como-reviewer** (via `reports_to`).

---

## Nota de reconciliação (2026-07-14 — fecho documental; divergência MÉDIA da auditoria doc×código de 2026-07-11)

Sobre o invariante **máx-1-review-PENDING por Issue** (`uq_review_pending`, índice parcial
V11): (a) a entidade JPA **não declara** o índice parcial — JPA não expressa `where
status='PENDING'`, e o guard vive SÓ no Postgres (H2 dos testes não força). É **desvio
consciente e hoje documentado no próprio javadoc** de `Review.java:21-23`; (b) **não existe
teste dedicado de concorrência** do invariante (dois submits em corrida → um vence, outro
estoura o índice) — verificado de novo na main `60ac302`: os testes de review cobrem
nascimento/fila/decisão/cross-company, não a corrida. Fica registrado como lacuna de teste
consciente, não de mecanismo (o índice parcial É o guard, e a corrida real exigiria Postgres
no teste — o parque roda H2). Corpo original intocado.
