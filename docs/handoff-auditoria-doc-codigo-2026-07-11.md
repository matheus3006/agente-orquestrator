# Handoff — Auditoria doc × código (2026-07-11) → sessão de controle

**O que é:** resultado de uma auditoria independente (sessão separada, fora do ledger de
paralelismo) que rodou 63 auditores + verificação cética dupla sobre roadmap + 34 ADRs + 22
grills/laudos `-fechado`, comparando cada afirmação verificável contra o código real. Não é
uma faixa do ledger — é um insumo externo para a sessão de controle decidir o que fazer com ele.

**Snapshot auditado:** commit `2ab8eac` (branch `feat/caso-b-adr0034`, = main + ADR-0034).
Relatório completo (63 fichas por documento + evidência `file:line` + filtro por veredito):
https://claude.ai/code/artifact/999b266f-208a-4502-a499-e841a0b17e27

**Headline: zero divergência ALTA.** Nenhum dos 63 documentos tem comportamento
documentado-como-pronto que não existe ou que o código contradiz — nem antes da refutação
cética. 158 achados brutos → 54 confirmados + 66 incertos (os 2 céticos discordam) + 38
refutados. Descontando 94 cosméticos (BAIXA), sobram **26 de severidade MÉDIA** (12
confirmadas + 14 incertas), detalhadas abaixo.

---

## Para a R4-C (auditoria motor-nativo) — evidência já levantada

Esta auditoria não sabia da R4-C ao rodar (ela nasceu depois, do reclame do Matheus sobre
"regredir a qualidade nativa dos motores"), mas cruzou o mesmo território por outro ângulo —
verificar 6 ADRs diferentes contra o código — e o padrão abaixo apareceu repetido em TODAS
elas, de forma consistente. Pode economizar boa parte da descoberta da R4-C.

**Padrão confirmado: a continuidade nativa de sessão dos 2 CLIs nunca é usada para trabalho
de verdade.**

- Todo turno roda como sessão NOVA: `AdapterRunExecutor` passa `priorSession=null` literal no
  caminho normal (`AdapterRunExecutor.java:252-255`) — `ClaudeArgv`/`CodexArgv` só emitem
  `--resume <handle>` quando `priorSession` vem preenchido (`ClaudeArgv.java:27-50`,
  `CodexArgv.java:32-48`); no caminho normal, nunca vem.
- O handle nativo de sessão (session_id do claude / thread_id do codex) É capturado a cada
  turno e persistido em `agent_session` (V16, chave `(agent_id, conversation_id)`) via
  `AgentSessionStore.record` — a plumbing existe e funciona.
- Mas o ÚNICO consumidor de produção desse handle é uma rede de UM turno, cirúrgica:
  `ContextHandoffLoop.surgicalDistill` (ADR-0030) religa a MESMA sessão nativa só para pedir
  "escreva um bilhete de handoff pra você mesmo" quando a conversa cruza ~55-60% de contexto —
  descarta `Result` e steps desse turno, não persiste nada dele, e o PRÓXIMO turno volta a
  `priorSession=null` (`ContextHandoffLoop.java:83-106`, leitor único em
  `AgentSessionStore.currentHandle`, `AgentSessionStore.java:41-46`).
- Ou seja: em vez de deixar o CLI continuar a própria sessão entre turnos, o orquestrador
  reconstrói contexto do zero a cada turno (skills rematerializadas no workDir, prompt
  recomposto, carta reescrita) e usa `--resume` só nesse único golpe de "espremer um resumo
  antes de resetar". Múltiplos ADRs descrevem isso como decisão deliberada ("fatia futura",
  "religar é barato depois", "desligado no v1") — não é descuido, é design — mas é exatamente
  o padrão que as palavras do Matheus apontam.
- **Contra-exemplo, não é regressão:** a descoberta de skills se ADAPTOU à convenção nativa de
  cada motor (`.claude/skills/`+`CLAUDE.md` no claude vs `.codex/skills/`+`AGENTS.md` no
  codex, ADR-0032, `WorkspaceConvention.java:22-34`) — isso é o orquestrador respeitando o que
  cada CLI espera nativamente, o oposto do problema.

Achados-fonte (todos BAIXA/cosmético isoladamente — o valor está no padrão agregado, não em
nenhum achado individual): ADR-0002, ADR-0014, ADR-0015, ADR-0018, ADR-0024, ADR-0029,
grill·plano-codex-fechado, grill·retencao-rows-conversa-fechado — todos confirmam pedaços do
mesmo quadro (ver artifact completo para o texto de cada um).

---

## Drift: main avançou 6 commits além do snapshot auditado

`git merge-base --is-ancestor 2ab8eac origin/main` confirma que o snapshot está CONTIDO em
`main` (nada do que foi confirmado ficou errado) — mas `main` cresceu depois:

- **PR #12 (ADR-0035, aprovação em lote + kickback)** — feature inteira não auditada:
  `docs/adr/0035-aprovacao-em-lote-kickback-recusa.md` + seu grill (170 linhas) +
  `governance/approval/ApprovalBatchService` + rota `/approvals` no front. Reaproveita o MESMO
  transporte M6 (poster de continuação) que a Fatia 2/ADR-0030 construíram — é exatamente o
  tipo de reaproveitamento de seam que esta auditoria foi desenhada pra checar, e nunca viu.
- **PR #11 (skills-ceo/)** — 6 arquivos novos (`being-ceo`, `delegation`, `writing-doc-plans`,
  `sourcing.md`, imports) não auditados.
- Emenda em `ADR-0031` (cross-ref pra 0035) e em `docs/roadmap-interno.md` (linha "Autoria dos
  3 SKILL.md" fechada) — ambas pequenas, não invalidam nada confirmado.

Prova concreta de que isso importa: o achado "§ Vetos pendentes" abaixo (item confirmado desta
auditoria) já cita 2 itens como "genuinamente pendentes" que o PR #11 fechou DEPOIS do
snapshot — ou seja, mesmo dentro dos 26 achados MÉDIA há um que já nasceu parcialmente stale.
Recomendação: se/quando a R4-C ou outra faixa tocar esse território, vale rodar uma passada
suplementar só do diff `2ab8eac..main` (pequeno) em vez de confiar cegamente no "63/63" como
cobertura de main HOJE.

---

## Achados MÉDIA confirmados (12) — candidatos ao fecho documental, não urgentes

Nenhum quebra a prova final combinada no "PLANO DE FECHO DO v1". Nenhum pede faixa própria —
encaixam no passo já planejado ("fecho documental: roadmap ADR-0035+, piso, §Vetos stale").

**ADR-0004** — Interface expõe `descriptor()` retornando `AdapterDescriptor` (tipo,
capacidades, detecção de modelo)
- Esperado: método `descriptor()` e tipo `AdapterDescriptor` no seam.
- Encontrado: não existem em lugar nenhum do repo; a superfície real é `type()` +
  `engineContainer()` + `workspaceConvention()` + `acceptsPromptAsCommand()`.
- Evidência: `docs/adr/0004-contrato-agentadapter-execute.md:13` vs
  `backend/src/main/java/com/orquestrador/execution/adapter/AgentAdapter.java:15-48`

**ADR-0004** — RunContext carrega ancestralidade de goal, skillSet, budgetView, secrets e
eventSink
- Esperado: esses 5 itens no contexto passado ao adapter.
- Encontrado: `AgentAdapter.Context` real não carrega nenhum dos 5 (skillSet é materializado
  ANTES do execute, budget vive no `HeartbeatDispatcher`, secrets ficam só no adapter,
  eventSink é parâmetro separado `StepSink`, ancestralidade de goal não viaja em lugar nenhum).
- Evidência: `docs/adr/0004-contrato-agentadapter-execute.md:18` vs
  `AgentAdapter.java:58-62`, `AdapterRunExecutor.java:151-154,252-257`,
  `HeartbeatDispatcher.java:55`

**ADR-0004** — StepTrace "Transmitido por SSE"
- Esperado: canal SSE.
- Encontrado: zero SSE no repo; transporte real é REST com polling incremental
  (`GET /runs/{id}/steps?offset=&limit=`).
- Evidência: `docs/adr/0004-contrato-agentadapter-execute.md:22` vs
  `backend/src/main/java/com/orquestrador/board/RunStepsController.java:19-55`

**ADR-0006** — Reativo: cruzou o hard limit → cancela Runs EM VOO (RUNNING) + wakeups na fila
- Esperado: Runs RUNNING cancelados junto com a fila.
- Encontrado: código drena até o checkpoint — cancela SÓ Runs QUEUED; RUNNING termina o passo e
  liquida no settle, deliberado ("matar RUNNING no banco nunca matou o processo").
- Evidência: `backend/src/main/java/com/orquestrador/governance/budget/BudgetService.java:26,136-144`
  vs `docs/adr/0006-governanca-runtime-budget-revisao.md:11`

**ADR-0009** — Ação delegada grava `detail.requested_by_agent` e `detail.approval_id`
- Esperado: essas 2 chaves no detail do evento.
- Encontrado: chave real é `requestedByAgentId` (camelCase); `approval_id` nunca vai no detail
  (viaja como `entity_id` do próprio evento).
- Evidência: `docs/adr/0009-modelo-activity-log.md:10,35-36` vs
  `ApprovalService.java:138,146`

**ADR-0013** — `notFound()` do layout é "escopado" no segmento `[companySlug]`
- Esperado: slug inválido renderiza o not-found do segmento.
- Encontrado: pela semântica do App Router, `notFound()` lançado no LAYOUT sobe ao not-found da
  RAIZ — o próprio código já documenta isso; o not-found do segmento só cobre páginas internas.
- Evidência: `docs/adr/0013-arquitetura-frontend-board.md:14-16,67` vs
  `frontend/src/app/[companySlug]/not-found.tsx:3-6`

**ADR-0018** — `target_detail` jsonb da approval é "só-IDs"
- Esperado: jsonb só com IDs.
- Encontrado: carrega texto livre (HIRE_AGENT viaja com name/role/racional em prosa).
- Evidência: `docs/adr/0018-orquestracao-ceo-mediado-por-proposta.md:65` vs
  `V17__approval.sql:15-16`, `ApprovalService.java:188-215`

**ADR-0026** — Start de preview em Issue coletada responde 409 via preflight de volume
- Esperado: preflight por volume, resposta 409.
- Encontrado: preflight é pelo evento `WORKSPACE_COLLECTED` no activity_log (não pelo volume);
  resposta é 200 com `StartResponse{started=false, error}` — nenhum 409.
- Evidência: `docs/adr/0026-gc-retencao-workspaces-reaper.md:60-62` vs
  `PreviewServerController.java:48-55`

**grill · approval-fechado** — Entidade `Review` declara a unique parcial + teste de
concorrência do invariante máx-1-PENDING
- Esperado: entidade declara o que o H2 força + teste dedicado do invariante.
- Encontrado: JPA não expressa índice parcial (desvio consciente, documentado); guard vive só
  no índice Postgres; não existe teste do invariante de concorrência.
- Evidência: `Review.java:21-24`, `V11__review.sql:22-24`

**grill · loop-handoff-contexto-fechado** — Seam do skill-set da continuação é
"v1-explícito, pronto pra Fatia 2 plugar"
- Esperado: parâmetro explícito de skill-set no caminho da continuação.
- Encontrado: não existe; a Fatia 2 (ADR-0031) foi implementada como 3 agentes duráveis com
  persona, sem usar esse seam.
- Evidência: `docs/handoff-grilling-loop-handoff-contexto-fechado.md:40,162` vs
  `ContinuationTurnPoster.java:47`, `ContextHandoffLoop.java:109-114`

**grill · memoria-ceo-segundo-cerebro** — D8: Lógico bloqueia commit até razão substantiva;
Intuição commita na hora
- Esperado: regimes de commit distintos por `racional_tipo`.
- Encontrado: `RacionalPolicy.grillProbe` aplica o MESMO critério aos 2 tipos — a distinção
  ficou só na mensagem do probe, não no comportamento.
- Evidência: `docs/handoff-grilling-memoria-ceo-segundo-cerebro.md:29` vs
  `RacionalPolicy.java:56-70`

**roadmap · rastreabilidade** — § Vetos pendentes: 4 decisões da Fatia 2 seguem "aguardando o
Matheus"
- Esperado: 6 decisões em aberto, incluindo essas 4.
- Encontrado: já confirmadas e commitadas em `1b026da` (2026-07-10) — o próprio roadmap se
  contradiz (Frente B linha 145 já diz "vetos H TODOS confirmados" pra mesma fatia). **Nota:**
  dos 2 itens que este achado listou como "genuinamente ainda pendentes" (sourcing de skills
  externas), o PR #11 (R3-A) fechou um deles DEPOIS do snapshot — ver seção de drift acima.
- Evidência: `docs/roadmap-interno.md:337-343` vs `docs/adr/0031-ceo-tres-personas.md:101,103`,
  commit `1b026da`

---

## Achados MÉDIA incertos (14) — 1 cético refutou, o outro não; peçam decisão antes de tocar

**Já resolvido pelo próprio ledger (não é decisão em aberto):**

- **ADR-0020 — D12 (graduação de princípios + tier Playbook)** ausente no código. O "PLANO DE
  FECHO DO v1" já resolveu isso: Frente C (graduação + índice JIT) está FORA do v1,
  congelada — não é pergunta pro Matheus, é reconciliação de texto (marcar D12 como
  planejado/Frente C na própria ADR-0020).

**Genuinamente em aberto (candidatos a decisão, sem pressa — nenhum quebra a prova final):**

**ADR-0004** — `execute()` é "o único call site" do módulo execution
- Encontrado: há 2 — `AdapterRunExecutor.runToOutcome` e o resume-cirúrgico do
  `ContextHandoffLoop.surgicalDistill` (ADR-0030), fora do módulo execution. A exceção é
  deliberada em outra ADR, mas o texto vigente da 0004 (inclusive a nota de 2026-07-10) segue
  afirmando call site único. Provavelmente só precisa de uma nota — mas quem decide é quem
  entende se esse invariante ainda protege algo.
- Evidência: `AdapterRunExecutor.java:264`, `ContextHandoffLoop.java:99`

**ADR-0009** — 3 eventos de review carregam `detail.review_id`/`detail.run_id`
- Encontrado: só o submit carrega os 2; approved/rejected só carregam `reviewId`.
- Evidência: `IssueService.java:170-171,213-214`

**ADR-0011** — Feedback do revisor entra na edição da Issue antes do REOPEN
- Encontrado: não existe endpoint de edição de Issue no backend; feedback fica preso em
  `review.decision_reason`, sem caminho pra Issue.
- Evidência: `IssueController.java:40-95` (superfície completa)

**ADR-0017** — CEO pode CRIAR molde com aprovação do dono
- Encontrado: criar/versionar molde é board-only direto; `ApprovalActionType` nem tem essa
  ação; o comentário do enum marca isso como fatia futura.
- Evidência: `ApprovalActionType.java:8`, `RoleTemplateController.java:53`

**ADR-0017** — Re-pin de agente pra vN de molde é operável
- Encontrado: `RoleTemplateService.pinForAgent` existe e é reversível (testado), mas sem
  chamador de produção — nenhum endpoint expõe. Pin só acontece implicitamente no hire.
- Evidência: `RoleTemplateService.java:75`, `RoleTemplateServiceTest.java:117`

**ADR-0024** — Caminho de skills é único (`.claude/skills/`) pros 2 motores
- Encontrado: superseded pela ADR-0032 (seam por-adapter, codex usa `.codex/skills/`) —
  decisão revertida por ADR posterior, o texto da 0024 não foi emendado.
- Evidência: `WorkspaceConvention.java:22-34`

**grill · 7c-fechado** — Corrida de install concorrente degrada pra attach (catch-and-continue)
- Encontrado: código faz algo mais estrito que o documentado — captura a exceção e devolve 409
  limpo, sem catch-and-continue numa tx abortada. Comportamento correto, doc desatualizado.
- Evidência: `SkillInstallService.java:64-66`

**grill · memoria-ceo-segundo-cerebro** — D5: gate de aprovação E review pós-hoc fabricam
Decisões datadas
- Encontrado: só o gate de Approval pré-hoc fabrica Decisão; `IssueService.review` não chama
  `DecisionLog`; o seam `Decision.anchoredToReview` existe sem caller.
- Evidência: `IssueService.java:188-215`, `Decision.java:99` (sem caller)

**grill · motores-fechado** — 4 alvos de teste frontend (dropdown/hire/detail/roster)
- Encontrado: só helpers puros testados; convenção do repo é vitest só em `lib/`, não em
  componentes — pode ser "achado" sem ação (convenção deliberada).
- Evidência: `frontend/src/lib/engines.test.ts:26-70`

**grill · plano-codex-fechado** — Skills chegam ao codex por prompt em `.claude/skills/`
- Encontrado: mesma história da ADR-0024 acima — revertido pela ADR-0032, doc não emendado.
- Evidência: `WorkspaceConvention.java:22-33`, `CodexAdapter.java:27-31`

**reversa · backend** — `ActorContext` (o tripwire real do deny-by-default) sem menção
nominal em nenhum doc
- Encontrado: `ActorContext.java` É o tripwire (guarda o Actor + o flag
  `companyAccessAsserted`); docs citam as classes vizinhas (`CompanyAccessGuard`,
  `CompanyScopeInterceptor`) mas nunca esta. Puramente documental — sem decisão, só um nome
  faltando.
- Evidência: `ActorContext.java:13-46`

**grill · skills-knowall** — "Catalog sync" nível-operador popula a Camada 1 sem instalar em
company
- Encontrado: `SkillIndexer.syncGlobalCatalog` existe mas nenhum código de produção chama —
  só testes. Caminho real sempre instala na company.
- Evidência: `SkillIndexer.java:54-58`, `SkillService.java:53-67`

**grill · teams-fechado** — 3 tetos escalam o CEO ao dono (gasto/token, riscos do plano,
circuit-breaker de erro)
- Encontrado: só o teto de gasto tem mecanismo dedicado (`OrchestrationAction` +
  `BudgetPolicy.orchestrationLimitCents`). Contador de riscos do plano e circuit-breaker de
  erro repetido não existem em lugar nenhum do backend. Este é o mais substantivo dos incertos
  — é uma lacuna de governança real, não só drift de texto, mas também não bloqueia a prova
  final do v1.
- Evidência: `OrchestrationAction.java:1-22`, `WakeupService.java:80`,
  `ConversationTurnCoordinator.java:150-239`

---

## Apêndice

- **94 achados BAIXA** (confirmados+incertos): puro drift cosmético — contagem defasada
  (ex.: roadmap cita "204 casos vitest", hoje são 248), nuance de redação, residual consciente.
  Zero ação recomendada; lista completa no artifact.
- **Panorama por documento (63):** todos os 34 ADRs + 22 grills/laudos + as 4 seções do
  roadmap + as 3 varreduras reversas, com contagem `claims confirmadas/total` e nº de
  divergências por doc — no artifact.
- **Memória atualizada:** `roadmap-inventario-auditado` (memória persistente do Claude Code)
  já registra este resultado.
