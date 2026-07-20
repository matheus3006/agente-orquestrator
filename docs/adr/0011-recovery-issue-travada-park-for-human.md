# ADR-0011: Recovery de Issue travada por Run FAILED (park-for-human)

**Status:** Aceito (2026-07-01, sessão grilling 7c) ·
**EMENDADA 2026-07-20 (ADR-0049): `BLOCKED` ganha um TERCEIRO motivo — a reabertura de entrega
aprovada — e o `unblock` do §7 deixa de ser a única porta de volta de uma Issue liquidada
(ver Revisão 2026-07-20 abaixo)**

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

## Revisão 2026-07-20 (reabertura de entrega aprovada, ADR-0049)

> Emenda ADITIVA do fecho do parecer. Esta ADR **nunca tinha sido tocada** pela esteira da ADR-0049,
> apesar de ser a dona do estado `BLOCKED` e do `unblock` — conferência dos PRs #51/#52.

- **`BLOCKED` passa a cobrir TRÊS motivos**, não dois. Ao lado de run **FAILED** (o §7 original) e
  review **REJEITADA** (ADR-0012), entra a **reabertura de entrega aprovada** (ADR-0049): o dono
  desfaz um `DONE` — possivelmente carimbado por um gestor promovido sem humano nenhum ter visto o
  ciclo — e a Issue volta para `BLOCKED`. Os três seguem se distinguindo pelo **evento** no
  activity log, não pelo status: `ISSUE_BLOCKED` · `REVIEW_REJECTED` · **`ISSUE_REABERTA`**.
- **O `unblock` continua servindo os três sem mudança**, e continua soberano sobre `BLOCKED`. O que
  mudou é que ele **deixou de ser a única porta de volta** de uma Issue liquidada: agora existe um
  degrau ANTES dele para entregas aceitas (`DONE → BLOCKED`), e os dois se compõem em vez de
  competir. A reabertura NÃO é um segundo jeito de estar "em revisão" — ela converge para o
  caminho que já existia.
- **O terceiro motivo tem uma recusa que os outros dois não têm:** se o `WorkspaceReaper` já
  coletou o workspace da entrega (retenção default de 7 dias), reabrir é **RECUSADO com 409** em
  vez de devolver uma Issue viva apontando para um artefato que não existe mais (D-FP-1). Ver
  ADR-0049 §Emendas — é a peça que a ADR-0049 não tinha varrido.

## Nota de reconciliação (2026-07-14 — fecho documental; divergência MÉDIA da auditoria doc×código de 2026-07-11)

**"O feedback do revisor entra na edição da Issue antes do REOPEN" não tem caminho no código.**
Não existe endpoint de edição de Issue no backend (superfície completa:
`IssueController.java:43-99` — create/list/checkout/complete/review/unblock); o feedback do
reject fica em `review.decision_reason` (visível no board, no ciclo de review), sem caminho
para o `description` da Issue. O REOPEN re-executa a Issue com o texto original — o agente
não vê o motivo do reject. É a única ficha MÉDIA da auditoria que segue **genuinamente em
aberto como decisão do dono** — registrada na **lista H do fecho do v1**
(`docs/lista-h-fecho-v1.md`, item 1, com recomendação). Corpo original intocado.

## Emenda 2026-07-14 (R19-A — fecho do v1: a premissa morta vira ENTREGA materializada)

**A premissa da §Revisão 2026-07-02 ("no reject, o feedback do revisor entra na edição da Issue
antes do REOPEN") está SUPERSEDIDA — e a divergência MÉDIA da nota de reconciliação acima está
RESOLVIDA.** O grill da R17-B (lista H, item 1) achou que a pergunta original ("puxar fatia de
edição de Issue × aceitar como está") era uma **falsa dicotomia**: existe uma **terceira via**, e
ela é curta.

**O que mudou desde a premissa (Frente C, PRs #25–#28):** o REJECT já **exige motivo** e esse
motivo já **É uma Decisão** endereçada por `#N` — grau `DECISAO`, racional = o feedback, **ancorada
na review E na issue** (`GateDecisionFactory` via `Anchor.NaReview`, que grava o `issue_id`). O
feedback **não fica mais preso em `review.decision_reason`**. O buraco real nunca foi "falta um
endpoint": era **falta de ENTREGA** — o executor rodava sem receber esse átomo no re-run.

**A decisão (R19-A):** **materializar (PUSH) no `memoryFile` do run de Issue as Decisões ancoradas
naquela issue.** No REOPEN → re-run, o executor **RECEBE** o motivo do reject (e as demais Decisões
da issue) como uma dieta direcionada, **sem depender de escolher buscar** — feedback de reject não é
opcional (PUSH, não pull). O agente selado não tem canal inbound; a materialização É esse canal.

- **Fio no código:** `DecisionRepository.findByCompanyIdAndIssueIdOrderBySeqAsc` (finder novo) →
  `CompanyMemoryMaterializer` (a dieta direcionada, renderizada com `#N` + racional) ←
  `AdapterRunExecutor` passa o `runContext.issueId()` na materialização. Cobre o reject
  (`anchoredToReview`) **e** as Decisões do caso B (`anchoredToIssue`).
- **NÃO se faz:** endpoint de edição de Issue (a superfície do `IssueController` segue intocada).
- **Convive com o trilho JIT selado (C10.b do `MemoryQuery`), sem alargá-lo:** a entrega é um
  caminho **NOVO e DIRECIONADO** (só as Decisões da issue do run). A Decisão do reject (grau
  `DECISAO`, tipo `OPERACIONAL`/`TECNICA`) **continua FORA** do `memory_fetch` — o log cru segue
  board-only. Os dois convivem por construção: um ENTREGA (direcionado à issue), o outro é o índice
  durável selado (`grau=PRINCIPIO || tipo=DIRECAO`).
- **Ponta do CEO inalterada:** turno de conversa tem `issueId` nulo → nenhuma seção de issue.
- **Lentes:** `human-architect-mindset` (o canal inbound que faltava ao agente selado; entrega ≠
  busca) + `codebase-design` (seam pequeno — o `issueId` que já existia no `RunContext` passa a
  fluir para a dieta; módulo fundo no materializer).

## Emenda 2026-07-16 (R22-A — bug #1 do roadmap de ajustes: run NO-OP nunca fecha issue)

**O "sucesso → DONE" do corpo ganha uma exceção estrutural:** um run que **não fez nada** — o
adapter sinalizou que o motor **nunca foi invocado** (`AgentAdapter.Result.noop`, ex.: arquétipo
`process` sem comando) — **NUNCA completa a Issue**, nem entra em review (não há entregável a
revisar). O `settleAfterRun` o estaciona em **`BLOCKED`** pelo mesmo park-for-human desta ADR
(lock liberado, evento `ISSUE_BLOCKED`, destrava = `unblock` do board), com o porquê legível no
erro do run.

- **Por quê (invariante do dono, grill 2026-07-16):** *"um run que não fez nada nunca mais fecha
  issue"*. Era o elo 3 do bug #1 (DONE fantasma): gate contratava sem comando + adapters tratavam
  comando vazio como no-op VERDE + este settle completava. Mesmo com o compositor da issue
  (ADR-0037 emendada) matando a CAUSA, comando vazio por outra causa voltaria a mentir o board —
  a trava é independente do conserto.
- **Onde mora:** o guard fica **no settle** (o chokepoint que cunha o DONE — "um único caminho
  para DONE", ADR-0012), não só no executor: `RunOutcome` ganhou o marcador `noop` e até um
  outcome "verde" com noop cai no ramo de bloqueio. O executor traduz o sinal do adapter em
  `RunOutcome.noop` **só quando há Issue**; fora de Issue o no-op segue verde (o "loop verde" de
  agente não configurado não tem o que mentir).
- **Interações:** o run vai a `FAILED` com erro legível (a trilha de runs não finge); o
  circuit-breaker da ADR-0018 (teto c) conta essas falhas e para o par agente×issue reincidente —
  comportamento desejado para agente mal-configurado; o wakeup manual destrava, como lá.
