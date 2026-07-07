# ADR-0011: Recovery de Issue travada por Run FAILED (park-for-human)

**Status:** Aceito (2026-07-01, sessão grilling 7c)

## Contexto
Um Run que falha estacionava a Issue travada **para sempre**: `checkout` seta `execution_lock_run_id`;
`complete` só o zera **no sucesso**; na falha o executor deixava travado. O reaper só re-enfileira Runs
`RUNNING` com lease expirado — um Run `FAILED` (terminal) nunca era tocado, e um Run novo falhava o
`tryCheckout` (a trava é por run-id, então **nem o mesmo agente** re-adquiria). O "travar para recovery" era
intencional, mas o mecanismo de destrava **nunca existiu**. `IssueStatus.BLOCKED` já existia no enum, reservado
para isto.

## Decisão
Na saída terminal de um Run, **`IssueService.settleAfterRun(companyId, issueId, runId, RunOutcome)`** liquida a
Issue: **sucesso → `DONE`**, **falha → `BLOCKED`** — **sempre liberando o lock**, sob o guard
`execution_lock_run_id = runId` (só o run detentor liquida). O board destrava via
`POST /api/companies/{id}/issues/{issueId}/unblock {decision: REOPEN|CANCEL}` (**board-only**, guard
`status = BLOCKED`): `REOPEN → OPEN` mantendo o `assignee_agent_id` (o próximo wakeup re-executa a mesma Issue
com o mesmo agente, pelo caminho normal); `CANCEL → CANCELLED`.

- **Segurança grátis do guard:** o executor chama `settleAfterRun` **incondicionalmente** na saída terminal; se
  o run não detinha a Issue (falhou **antes** do checkout, ou perdeu a corrida de checkout) casa **0 linhas →
  no-op** — uma Issue-vítima **nunca** é bloqueada por um run que não a possuía.
- **Sem auto-retry no v1:** o sinal para distinguir transiente × determinístico é o **erro tipado do ADR-0004**
  (`transient_upstream`+`retryNotBefore` vs `model_refusal`), que **não está cabeado** (o adapter hoje devolve
  só `String`). Auto-retry cego de falha determinística queima budget num loop (contra o hard-stop do ADR-0006).
- **Antecipa o futuro sem reescrita:** `settleAfterRun` recebe o **`RunOutcome`** (não um estado-alvo). Quando o
  erro tipado for cabeado, o ramo `transient_upstream → RECOVERING`(+`retryNotBefore`) entra **dentro** de
  `settleAfterRun`, com zero mudança no call site e zero mudança nos testes existentes.
- **Seam correto:** vive no `IssueService` (onde `checkout`/`complete` já moram), não no executor (vazaria a
  máquina de estados para um caller) nem numa policy pura separada (seam **hipotético** — hoje há **um** só
  mapeamento falha→BLOCKED). Evento `ISSUE_BLOCKED`/`ISSUE_REOPENED`/`ISSUE_CANCELLED` no activity log (`detail`
  só com IDs — a mensagem de erro nunca entra, ADR-0009; vive no StepTrace do run).

## Alternativas consideradas
- **Auto-retry já no v1** — inseguro sem o erro tipado do ADR-0004; prematuro para a escala solo.
- **Seam no executor / policy pura especulativa** — rejeitados (locality + "não introduza um seam a menos que
  algo varie através dele").

## Consequências
- "Issue travada para sempre por um Run que falhou" deixa de ser um modo de falha.
- `BLOCKED` é **observável** (evento + fora do conjunto pegável `status in ('OPEN','IN_PROGRESS')` → não
  re-executa sozinha) — só o board destrava.
- Reserva `RECOVERING` (Run) para o auto-retry futuro; sem `failure_count` no v1.
- **Lentes:** `human-architect-mindset` (loyalty ao ADR-0004; failure-isolation; observabilidade obrigatória) +
  `codebase-design` (seam profundo no `IssueService`, assinatura carregando o `RunOutcome`, guard como contrato).

## Revisão 2026-07-02 (review gate, ADR-0012)
- **`BLOCKED` passa a cobrir dois motivos:** run **FAILED** (o §7 original desta ADR) **e** review **REJEITADA**
  (ADR-0012). Ambos significam o mesmo estado: *parked, precisa de decisão humana*. Os dois casos se distinguem
  pelo **evento** no activity log (`ISSUE_BLOCKED` vs `REVIEW_REJECTED`), não pelo status.
- O `unblock {REOPEN|CANCEL}` **serve os dois sem mudança** (REOPEN mantém o agente → re-run normal). No reject,
  o feedback do revisor entra na **edição da Issue antes do REOPEN** (human-mediated — o agente selado não tem
  canal inbound).
