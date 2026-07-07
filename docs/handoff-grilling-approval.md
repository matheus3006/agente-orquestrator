# Handoff — Grilling do Approval / Review gate (2ª metade do ADR-0006)

> Fonte-de-verdade para uma sessão nova de **grilling** (`grill-with-docs` / `/grilling`) FECHAR as decisões
> abertas do **review gate + primitiva de Approval** — a última invariante de governança (#4) ainda não
> construída. **NÃO re-derivar** — este doc aponta as fontes e enquadra o que falta decidir. Nenhum código de
> feature deve ser escrito na sessão de grilling: só decisões, no formato de saída do §8.
> Data: 2026-07-01. Idioma: PT-BR. Repo: `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/`.

---

## 0. Estado (o que já está pronto e verde)
- **72/72** testes offline (`mvn -o -f backend/pom.xml test`, H2-PostgreSQL). Migrations **V1→V10** validadas em
  **Postgres 16.14 real** (zero drift).
- Fase 0 + Fase 1 (slices 1–4: checkout atômico, heartbeat, execução real, budget hard-stop) + **Fase 2 inteira**
  (skills 2-camadas, activitylog, materialização, Fatia 7 completa: 7a anexo, 7b KnowAll, 7c install-on-attach,
  §7 recovery park-for-human). **Falta a 2ª metade do ADR-0006: o review gate + a primitiva de Approval.**

## 1. Onde ler ANTES (fontes; não re-derivar)
- **Memória `agente-orquestrator-stack.md`** (log vivo por-slice, autoritativo).
- **ADR-0006** (governança runtime): a **1ª metade** (budget hard-stop) foi construída; a **2ª metade** (review
  runtime-enforced) diz: *"Toda transição de status passa por uma função pura de execution-policy. Marcar `done`
  numa Issue que exige revisão é interceptado e roteado ao reviewer. O registro de decisão é gravado na MESMA
  transação da mudança de estado."* — é o que esta fatia implementa.
- **ADR-0009** (activity log): ação delegada = **1 evento** (ator=aprovador, `detail.requested_by_agent`,
  `detail.approval_id`) → **pressupõe uma entidade Approval com id**.
- **ADR-0011** (recovery, recém-criado): o seam **`IssueService.settleAfterRun(company, issue, runId, RunOutcome)`**
  já é o ponto de interceptação — hoje sucesso→`DONE`; o review gate entra AQUI (sucesso→revisão em vez de `DONE`).
- **ADR-0003/0006** e o handoff da 7c (`handoff-grilling-7c-fechado.md`) — precedente: o **agente-como-tool** foi
  ADIADO (Rota A operador-only) porque fura #1/#3 e precisa de canal agente→core inexistente.

## 2. O que JÁ está travado (não reabrir)
- **Review é runtime-enforced, não por convenção/prompt** (ADR-0006): o sistema intercepta, o agente não "lembra".
- **A decisão de review grava na MESMA tx da mudança de estado** (ADR-0006 + padrão do activitylog).
- **O seam existe:** `settleAfterRun` é a "função pura de execution-policy"; o review gate mora nele (não no
  executor). Ele já recebe o `RunOutcome`.
- **Deny-by-default** (queries lideram por `company_id`), **board-only** nos endpoints de curadoria/decisão,
  **isolamento do container intacto** (`env=Map.of()`), **skill nunca embute segredo**.

## 3. Constraints técnicos DUROS (o que o código impõe hoje)
1. **NÃO existe primitiva de Approval/Review** (grep confirmou: nada). Só governança de **budget**
   (`BudgetIncident` OPEN→RESOLVED) — que é um modelo de **incidente/override**, NÃO de **request→decision**.
2. **`IssueStatus` = OPEN, IN_PROGRESS, BLOCKED, DONE, CANCELLED.** Não há `PENDING_REVIEW`/`IN_REVIEW`.
3. **A Issue NÃO tem campo "exige revisão"** hoje. O `Agent` TEM **`reports_to_id`** (linha de reporte já modelada).
4. **`settleAfterRun` no sucesso → `tryComplete`/`DONE`** (libera o lock). `tryComplete`/`tryBlock` são UPDATEs
   guardados por `execution_lock_run_id`. Um novo estado de review seguiria o mesmo padrão (guardado + libera lock).
5. **Agente-como-reviewer reintroduz o problema do canal agente→core** (um agente aprovar/rejeitar = AGIR no core,
   autenticado — o MESMO obstáculo que adiou o agente-como-tool na 7c). Logo, agente-reviewer provavelmente
   também adia; v1 = board-reviewer (a decidir no grilling).

## 4. Decisão ABERTA (i) — ESCOPO da primitiva (o PIVÔ)
v1 constrói o **review gate ESPECÍFICO** (revisão na conclusão da Issue) ou uma **Approval GENÉRICA** reusável?
- **Consumidores potenciais do "Approval":** (a) review-on-Issue-done (ADR-0006, ESTA fatia); (b) agente-como-tool
  futuro (install disparado por agente → aprovação); (c) config changes (CONTEXT.md: "mudanças de config são
  revisionadas/rollback").
- **Tensão:** ADR-0009 (`approval_id`) + (b) + (c) puxam **genérico**; YAGNI + solo v1 + o precedente da 7c (adiar
  o genérico até o consumidor existir) puxam **específico**.
- **Sutileza a reconciliar:** "review gate" (INTERCEPTAR a transição done→reviewer) e "approval request" (uma FILA
  de pedidos que um humano aprova) são a MESMA primitiva ou DUAS? A revisão é sobre o **output de um run já
  concluído**; a aprovação-de-ação é sobre **autorizar uma ação antes de executá-la**.

## 5. Decisão ABERTA (ii) — FONTE do "exige revisão"
O que faz uma Issue passar por revisão antes de `DONE`? (a) **flag por-Issue** (`requires_review`); (b) **por-Agent**
(o output daquele agente sempre é revisado — ex.: agente júnior); (c) **por-policy** (regra por-company/project);
(d) **derivado de `reports_to`** (o trabalho do agente é revisado por quem ele reporta — reusa a estrutura que já
existe). Define o **modelo de dados** (coluna? tabela de policy? derivação?).

## 6. Decisão ABERTA (iii) — QUEM revisa
**board-only** (solo v1, pragmático — há um humano) vs **agente-como-reviewer** (via `reports_to`, alinhado à visão
de times de agentes). ⚠ agente-reviewer cai no constraint #5 (canal agente→core) → provável adiamento. Confirmar
v1 = board-reviewer + registrar agente-reviewer como fatia futura (mesmo espírito da Rota A da 7c).

## 7. Decisão ABERTA (iv) — MÁQUINA DE ESTADOS + (v) ENTIDADE
- **Estados:** a Issue ganha `PENDING_REVIEW` (ou `IN_REVIEW`)? Fluxo: run sucede → se exige-review →
  `PENDING_REVIEW` (lock liberado, como DONE/BLOCKED) ; senão → `DONE`. Approve → `DONE`. **Reject → ?** (volta a
  `OPEN` p/ re-run, como o REOPEN do §7? `BLOCKED`? `CANCELLED`?). Interação com o **recovery §7** (uma
  `PENDING_REVIEW` pode ser bloqueada? reject≈reopen?) e com **budget** (uma Issue esperando revisão não gasta).
  O gate vive em `settleAfterRun` (sucesso ramifica review vs done); a decisão de review (approve/reject) é outra
  transição guardada, gravada na mesma tx do evento.
- **Entidade + reuso:** entidade `review`/`approval` própria (PENDING→APPROVED/REJECTED, com `id` p/ o `approval_id`
  do ADR-0009) OU generalizar `BudgetIncident`? (Provável: **separada** — incidente/override ≠ request/decision.)
  Forma mínima candidata: `review(id, company_id, issue_id, run_id, requested_at, reviewer_actor, status,
  decided_at, decision_reason?)`. Eventos novos: `ISSUE_SUBMITTED_FOR_REVIEW` / `REVIEW_APPROVED` / `REVIEW_REJECTED`?
- **Fora do escopo desta fatia (confirmar):** config-change approval (é sobre config, não output de run) e
  agente-como-tool approval.

## 8. Perguntas de grilling (respondê-las FECHA a fatia → "zero decisão", como a 7c)
- **Q1 (pivô):** v1 = review gate **específico** (revisão na conclusão) ou **Approval genérica**? "review gate" e
  "approval request" são uma primitiva ou duas?
- **Q2 (fonte):** o que dispara "exige revisão"? (flag por-Issue / por-Agent / policy / derivado de `reports_to`).
- **Q3 (reviewer):** board-only no v1, ou agente-como-reviewer? (constraint #5 empurra p/ board-only + futuro).
- **Q4 (estados):** novo status `PENDING_REVIEW`? Reject → OPEN(re-run)/BLOCKED/CANCELLED? Interação com §7 e budget?
  O gate mora no `settleAfterRun` (sucesso ramifica) — confirmar.
- **Q5 (entidade):** entidade própria (PENDING→APPROVED/REJECTED, `approval_id`) vs generalizar `BudgetIncident`?
  Eventos do activity log? Confirmar que config-approval e agente-tool-approval ficam FORA desta fatia.

## 9. Entregável esperado da sessão de grilling
Um doc **no mesmo formato** de `handoff-grilling-7c-fechado.md` / `handoff-grilling-skills-knowall.md`: (A) decisões
fechadas por pergunta (com o *porquê*); (B) diffs de ADR/CONTEXT a aplicar (emendar ADR-0006; novo ADR de Approval/
review se genérico; CONTEXT.md); (C) mudanças de código/schema **ORDENADAS** (nova migration? novo status? entidade?
seam no `settleAfterRun`; endpoints board-only); (D) constraints/limitações do v1. Objetivo: deixar a fatia
**totalmente especificada, zero decisão aberta** — pronta p/ implementar em fatias finas com TDD + revisão adversarial.
