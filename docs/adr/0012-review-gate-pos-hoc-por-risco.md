# ADR-0012: Review gate (revisão pós-hoc disparada por risco, runtime-enforced)

**Status:** Aceito (2026-07-02, sessão grilling `docs/handoff-grilling-approval-fechado.md`)

## Contexto
Autonomia 24/7 exige que trabalho de alto risco não vire `DONE` sem um humano conferir, e que isso seja
**imposto pelo sistema** (ADR-0006), não pelo prompt. Não existia primitiva de review; `IssueStatus` não tinha
`PENDING_REVIEW`; a Issue não tinha "exige revisão".

## Decisão
A Issue ganha **`risk_level` (1-5)**; no `settleAfterRun` (branch de sucesso), `risk_level ≥ limiar`
(`orquestrador.review.risk-threshold`, config global, default 5) → **`PENDING_REVIEW`** (libera o lock, guardado
por `execution_lock_run_id`), criando uma linha **`review`** PENDING + evento `ISSUE_SUBMITTED_FOR_REVIEW`,
**na mesma tx**. O **board** decide via endpoint board-only: **APPROVE → `DONE`**; **REJECT → `BLOCKED`**,
reusando o `unblock {REOPEN|CANCEL}` do §7 (ADR-0011). Fonte do risco no v1 = **humano** (seta por-Issue na
criação); revisor = **board**.

**UMA primitiva** (review, pós-hoc — o trabalho já aconteceu, revisa-se o resultado). A **Approval-de-ação
genérica** (pré-hoc: config-change, agente-tool — autorizar a ação *antes*) fica **adiada**: nasce com o
agente-como-tool, quando houver dois consumidores reais para desenhar a interface.

*"Duas aprovações"* (aprovar o **plano** e depois o **resultado**) = o **mesmo gate em duas Issues**
(Issue-plano do architect / Issue-implementação), não uma segunda máquina. A sequência entre elas é **manual**
no v1 (dependência automática entre Issues = feature própria futura). A forma do plano (técnico + resumo humano)
é contrato da skill do architect + UI do diferencial #4 — o gate é **artifact-agnostic** (sem entidade `plan`).

**Entidade `review`** (V11): `(id, company_id, issue_id, run_id NOT NULL, status PENDING→APPROVED/REJECTED,
reviewer_actor_type, reviewer_actor_id, decision_reason, requested_at, decided_at)` — linha **mutável**
request→decision (o activity_log segue como trilha imutável); uma linha por ciclo (re-run pós-reject → nova
review, novo run); **máx. 1 PENDING por Issue** (`uq_review_pending`, índice parcial Postgres-only); queries
lideradas por `company_id`. `decision_reason` mora **na tabela**, nunca no activity_log (ADR-0009).

## Alternativas rejeitadas
- **Approval genérica agora** — abstrai de 1 exemplo para 2 consumidores hipotéticos de forma temporal oposta
  (pós-hoc × pré-hoc); e não pouparia o lado-Issue (o review é soldado à máquina de estados da Issue).
- **Agente dá o próprio risco** — fura o ADR-0006 (enforcement não confiado ao agente); o risco **silencioso**
  é exatamente o que o agente mais subestima; exigiria o canal agente→core (constraint #5). Futuro:
  escala-só-pra-cima acima de um piso humano.
- **Reject → OPEN direto** — a Issue voltaria ao conjunto pegável e re-rodaria **antes** da edição com o
  feedback; o agente selado (sem canal inbound) reproduziria o mesmo output (loop inútil).
- **Uma-Issue-que-pausa-no-meio** (planeja→espera aprovação→retoma) — exige canal inbound para o container
  selado (constraint #5, fura isolamento e o StepSink só-de-saída).
- **Generalizar `BudgetIncident`** — incidente/override (`OPEN→RESOLVED`) ≠ request/decision.

## Consequências
- "O agente pulou a revisão" deixa de ser modo de falha; `PENDING_REVIEW` é observável e **não re-roda sozinha**
  (fora do conjunto pegável; lock solto, sem run em voo → não gasta budget enquanto espera).
- `review.id` serve de `review_id` no `detail` dos eventos (review não é ação delegada — ADR-0009 revisado);
  `decision_reason` fora do activity_log.
- **UMA migration nova (V11)** — `PENDING_REVIEW` em si custa zero migration (`issue.status` é text livre).
- `BLOCKED` ampliado (run FAILED **e** review rejeitada — ADR-0011 revisado); o recovery do §7 é 100% reusado.
- Reserva a Approval-de-ação genérica para o agente-como-tool (2 consumidores reais).
- **Lentes:** `human-architect-mindset` (runtime-enforced, não prompt; escala-só-pra-cima; failure-isolation via
  guard; observabilidade via eventos) + `codebase-design` (gate no seam `settleAfterRun` que já carrega o
  `RunOutcome`; transições guardadas espelham `tryComplete`/`tryBlock`; `review` paralela ao `BudgetIncident`;
  reject reusa o §7).

## Revisão 2026-07-04 (frente Teams — ADR-0018)
- A **Approval-de-ação genérica (pré-hoc) NASCE** — o "2º consumidor real" que esta ADR reservava. Tabela
  `approval` **espelhando a `review`** (PENDING→APPROVED/REJECTED, `uq_approval_pending` parcial, `decision_reason`
  na tabela). **NÃO generalizar o `BudgetIncident`** (incidente ≠ request/decision). O review **pós-hoc** desta
  ADR segue **inalterado** — Approval é **pré-hoc** (o CEO propõe ANTES de agir).
- A "Issue-que-pausa-no-meio morta" vale pros **executores**; o **CEO conversacional é a exceção** (tem canal de
  conversa com o humano — ADR-0018).
