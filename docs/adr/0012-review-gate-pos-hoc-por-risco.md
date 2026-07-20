# ADR-0012: Review gate (revisão pós-hoc disparada por risco, runtime-enforced)

**Status:** Aceito (2026-07-02, sessão grilling `docs/handoff-grilling-approval-fechado.md`) ·
**EMENDADA 2026-07-20 (ADR-0048): "revisor = board" ganha a sua primeira exceção deliberada — o
gestor PROMOVIDO decide o ciclo de uma entrega do próprio braço (ver Revisão abaixo)** ·
**EMENDADA 2026-07-20 (ADR-0049): `DONE` deixa de ser sink — a entrega aprovada é REABRÍVEL pelo
dono, o que fecha a contradição com a D-ORG-2 que a emenda da 0048 tinha deixado em aberto**

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

## Revisão 2026-07-20 (ADR-0048 — o gestor promovido decide o ciclo)

O contrato **"revisor = board"** (`:18`) ganha a sua **PRIMEIRA exceção deliberada**: um gestor
**PROMOVIDO** (`team.gestor_status`, ADR-0046) decide o ciclo de review de uma entrega do **próprio
braço**, com `reviewer_actor_type = 'AGENT'` — valor que **o dado já aceitava desde esta ADR** (a
coluna nasceu `text` livre na V11); o que faltava era o **writer**. Decidido pelo dono no grill de
org-design (**D-ORG-1**: validação delegada é **só trabalho**, a governança segue 100% humana;
**D-ORG-2**: o gestor não nasce decidindo — nasce em ESTÁGIO, dando parecer, e o dono o promove por
ato explícito) e executado na fatia F-ORG-C. Registro completo:
`docs/adr/0048-review-delegado-parecer-do-gestor.md`.

**A exceção é ESTREITA por desenho** (escopo de segurança da ADR-0048 §2/§3, molde replicado
elemento por elemento da ADR-0047): só sobre entrega cujo **assignee** pertence à **subárvore** de
um time que o ator gere (`ChainOfCommandService.pertenceASubarvore`) · **nunca sobre a própria
entrega** — o gestor não se auto-revisa, porque `pertenceASubarvore` inclui a raiz nela mesma e,
sem essa cerca, um gestor assignado a uma issue do próprio time aprovaria o próprio trabalho,
exatamente o que este gate existe para impedir · só pelo método atrás do guard **revalidado no
service**, nunca confiando no chamador · `ActorType.AGENT` como **literal fixo**, jamais parâmetro
(um `decidirReviewComo(ActorType, …)` seria bypass permanente do `requireBoard` para qualquer ator).

✅ **A quinta cerca — "o `unblock` do dono continua soberano" — valia só de um lado, e a ADR-0049
fechou o outro.** A ADR-0048 (`:63-64`) e o `ParecerService` afirmavam que *"nada aqui é
irreversível"*; a conferência do PR #50 (achado 🔴 1) **mediu o contrário**, e o furo era real:

- **REJECT** → `tryBlock` → `BLOCKED` → o `unblock {REOPEN|CANCEL}` do §7 pega. **Reversível** ✅
  (sempre foi).
- **APPROVE** → `tryApprove` → **`DONE`**. Enquanto `tryReopen` e `tryCancel` eram os únicos
  UPDATEs guardados de recovery — ambos `where status = 'BLOCKED'` —, `DONE` **não aparecia como
  estado-ORIGEM** de transição guardada nenhuma, só como alvo: era **sink**, e o `unblock` lançava
  `ConflictException`. **Hoje aparece:** `IssueRepository.tryReabrirAprovada` é
  `where status = 'DONE'` → `BLOCKED`, e dali o `unblock` de sempre. **Reversível** ✅

> 📌 **Por que este parágrafo cita MÉTODO e não linha.** A forma anterior ancorava em
> `IssueRepository:206` e `:219`. O `:206` sobreviveu por sorte; o `:219` **apodreceu em ~33
> minutos**, quando o merge da ADR-0049 inseriu `tryReabrirAprovada` entre `tryReopen` e
> `tryCancel` e passou a apontar para prosa de javadoc. A âncora foi reescrita **de novo** no fecho
> do parecer, ao acrescentar javadoc ao mesmo método. Duas podridões no mesmo par de números em um
> dia: âncora de linha para código vivo é dívida, não precisão. **Nome de método, sempre.**

Ou seja: **a direção irreversível ERA justamente a perigosa** — um gestor promovido carimbava
entrega ruim e fechava a issue sem nenhum humano tê-la visto como PENDING. Isso **contradizia a
D-ORG-2** (*"gestor decide sozinho… e qualquer entrega reabrível pelo dono"*), que é decisão do
DONO — e a reabertura era a válvula que justificava dar autoridade de decisão a um agente. Enquanto
o furo esteve aberto, o único recurso era issue corretiva (`CorrecaoDeEntregaRouter`), que é
**trabalho novo, não reabertura**; **hoje ela deixou de ser o único** — a reabertura de verdade
existe, e as duas convivem (corrigir por cima × desfazer o aceite).

✅ **DECIDIDO PELO DONO (2026-07-20) — ADR-0049**, não mais pendente. A entrega aprovada volta para
`BLOCKED` (o estado que o §7 da ADR-0011 já sabia destravar), o ciclo de review **continua
`APPROVED` com o carimbo do gestor intacto** (o rastro é a metade da D-ORG-2 que paga pela outra) e
a reabertura fabrica uma Decisão que **SUPERSEDE** a do carimbo desfeito. Uma ressalva ficou:
passada a retenção do `WorkspaceReaper`, reabrir é **RECUSADO com 409** em vez de devolver uma
Issue viva apontando para artefato coletado (D-FP-1; ADR-0049 §Emendas).

**O `:16` fica incompleto, não errado.** *"O board decide via endpoint board-only"* segue valendo
para o caminho humano, mas deixou de ser o **único**: `ParecerService.decidirReviewComoGestor`
chama o **MESMO** `IssueService.review(...)` que o board chama, **sem passar pelo `requireBoard` do
`ReviewController`** (`:55`). É o segundo caminho de autorização não-humana da casa (o primeiro foi
o `DIRECT_ISSUE` da ADR-0047) e o **primeiro que não passa por um `requireBoard`**. A garantia
migra do **controller** para o **guard do service** — revalidado **antes de qualquer EFEITO** e
antes do dedupe nas duas entradas públicas (lição do hardening do PR #49: guard só no gate é seguro
por **convenção**, não por **construção**).

> ⚠️ **Precisão corrigida no fecho do parecer (2026-07-20).** Este parágrafo dizia "revalidado como
> **primeira linha** das duas entradas públicas". Deixou de ser literalmente verdade no PR #52: o
> guard passou a rodar em SEGUNDO nas duas, depois de `cicloAbertoAncorado`, porque a cerca de
> auto-review passou a mirar em **quem ENTREGOU** e o entregador só se resolve a partir do ciclo. O
> que precede é leitura pura + `throw`, de efeito ZERO — **a propriedade de segurança continua
> valendo**, e é ela que este parágrafo queria afirmar. Corrigido junto com o javadoc do
> `ParecerService`, que carregava a mesma frase: era o par exato de afirmação-que-parou-de-descrever-
> o-código que esta rodada veio limpar, e deixá-lo só no Java teria criado mais um órfão.

**A entidade `review` do `:29-30` ganhou 3 colunas (V38)**, todas do ramo consultivo: `parecer` (o
texto composto deterministicamente pelo service, `"APROVARIA — <porquê>"`), `parecer_por_agent_id`
e `parecer_em` — molde valor/quem/quando da V36. O ramo PROMOVIDO **não escreve nelas**: lá o rastro
são as colunas de decisão que já existem desde a V11. **Não há coluna de voto legível por máquina, e
isso é deliberado** — a D-ORG-2 proíbe pré-selecionar a decisão do dono a partir do parecer, e com
um enum ao lado do texto a proibição valeria por disciplina; sem ele, a UI **não tem como** quebrá-la.
O `decision_reason` desta ADR **não foi sequestrado**: ele segue pertencendo a quem **decide** — a
fronteira entre "quem opinou" e "quem decidiu" é justamente a que a ADR-0048 desenha.

**Nota de precisão sobre a escada de autoridade.** A escada é de **ATORES** — operador > gestor
promovido > CEO (mediado) > arquiteto > executor. O que esta emenda registra é o **segundo PODER**
do degrau que a ADR-0047 abriu, **não um degrau novo**: o gestor promovido *dirige o próprio braço
sem gate* (ADR-0047) **e agora** *decide o review do próprio braço* (ADR-0048). Nenhum nível novo
foi criado entre operador e CEO.
