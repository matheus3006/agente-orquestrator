# Handoff — Teams T.3.a COMPLETA (CEO conversacional + memória + Approval, backend)

> Sessão 2026-07-04. Backend-first TDD (RED confirmado em toda fatia). **T.3-FE (a tela) NÃO está
> aqui** — vira sessão própria (ADR-0015); o mapa dela está em `/tmp/handoff-t3-fe-tela-ceo.md`
> (espelho: seção "PRÓXIMA FATIA" abaixo). Repo SEM git — nada commitado.

## Piso (verificado)
- Backend **219/219** (`cd backend && mvn -o test`) — eram 186; +33 de T.3.a.
- **Flyway V16→V18 aplicado + ddl-validate PASS** no Postgres real (`pgdata`); jar combinado no
  `:8080` (log `/tmp/orquestrador-v18.log`).
- **Smoke VIVO na `teams-demo`**: mensagem→turno ANSWERED (~1s, scheduler real) → proposta na
  saída → Approval PENDING ancorada → grill 422 (probe D6/D7, 2 rodadas) → APPROVE com racional →
  Issue criada + Decision DIRECAO no log + **carta v1 consolidada** + **CLAUDE.md materializado**
  no workspace do CEO no turno seguinte.
- Front intocado (piso de T.2 segue: vitest 151/151 + tsc + build ✓).

## O que existe agora (mapa de código)
**`conversation/`** — `Conversation` (chat por assunto, uq company+subject) · `ConversationTurn`
(turno PENDING→ANSWERED/FAILED; `agent_id` = CEO endereçado; `run_id` no claim) ·
`ConversationService` (board fala SÓ com o CEO — resolve `role='ceo'` mais antigo, 409 sem CEO;
postMessage → turno + `WakeupService.enqueue(issueId=null)`) · `ConversationTurnCoordinator`
(**o seam do motor**: claim guardado idempotente por runId; prompt POR-RUN = estado vivo +
mensagem; settle grava reply, escaneia propostas e DRENA a fila — 1 turno = 1 Run sob coalescing)
· `LiveStateProjector` (roster+issues+atividade, IDs→nomes, capado 30/10) ·
`ConversationController` (POST/GET conversations, POST messages→202, GET turns; board-only).

**`governance/approval/`** — `Approval` (espelha review + racional/racional_tipo/racional_arrancado/
grill_rounds/area/resumo/fingerprint; `target_detail` jsonb) · `ProposalScanner` (bloco
```` ```proposta ````JSON na SAÍDA; parse determinístico fail-soft; alvo canônico) ·
`RacionalPolicy` (D7–D10: área→tipo, "porque quis" rejeitado, lógico bloqueia/intuição commita,
probe maiêutico) · `ApprovalService` (propose com dedupe por fingerprint; decide = grill →
Grilled(422)/Committed; APPROVE **dispara ação real na MESMA tx** — HIRE_AGENT/CREATE_ISSUE/
WAKEUP_AGENT com ator=aprovador; fábrica de Decisão D5; direção → consolida carta) ·
`ApprovalController` (GET queue/por-conversa; POST decision → 200 | **422 {grilled,probe,rounds}**).

**`memory/`** — `Decision` (átomo D4, tabela própria — residual 3 FECHADO; imutável, `supersedes`)
· `DecisionLog` (record com backstop de racional; query filtrável; `tipoFor(area)`:
direcao/estrategia/roadmap→DIRECAO, tecnica/arquitetura/stack→TECNICA, resto→OPERACIONAL) ·
`CompanyMemory`+`MemoryKind.CARTA` (versionada append-only) · `CompanyMemoryConsolidator`
(projeção determinística das DIRECAO vigentes, zero LLM, idempotente por conteúdo, respeita
supersedes) · `CompanyMemoryMaterializer` (carta → `.claude/CLAUDE.md`, remove se não há carta) ·
`IssueLedger` (report por-Issue = saída final do run concluinte — residual 1 FECHADO) ·
controllers: `GET /decisions` · `GET /memory/carta` · `GET /issues/{id}/report`.

**`execution/`** — `AdapterRunExecutor`: run sem Issue pergunta ao coordinator (claim); com turno:
materializa CLAUDE.md, `command`=prompt (adapters ricos) + env `ORQ_PROMPT` (sempre), sink
decorado por `RunFinalOutput`, settle answered/failed + `AgentSessionStore.record` ·
`RunFinalOutput` (**regra única**: RESULT > prosa ASSISTANT > STDOUT, cap 20k — serve reply de
turno E report do Ledger) · `AgentAdapter.acceptsPromptAsCommand()` (default true; process=false)
· `AgentSession`+store (V16, write-only — ADR-0002 revisado no arquivo).

**Migrations:** V16 (conversation, conversation_turn, agent_session) · V17 (approval +
`uq_approval_pending` parcial por company+agente+ação+fingerprint) · V18 (company_memory,
decision). **ActivityAction** += CEO_PROPOSED / APPROVAL_APPROVED / APPROVAL_REJECTED.

## Decisões de implementação (não re-derivar)
1. **Turno drena um-por-Run**: coalescing junta N posts num run QUEUED; o settle re-enfileira se
   restam PENDING (invariante 1 turno = 1 Run de A.8).
2. **Prompt por env `ORQ_PROMPT` no process** (prosa em `sh -c` executaria como shell); adapters
   ricos recebem como `command` (`-p`). Smoke de CEO echo: `printf '%s\n' "$ORQ_PROMPT"`.
3. **Carta vai por CLAUDE.md, estado vivo vai no prompt** (ADR-0018/A.8 ao pé da letra).
4. **Dedupe de proposta** = md5 do alvo CANÔNICO (chaves ordenadas), só contra PENDING — re-propor
   depois de decidida abre ciclo novo (é feature; o smoke deixou 1 PENDING de demo na teams-demo).
5. **DecisionTipo derivado da ÁREA declarada no bloco** (`direcao-roadmap` → DIRECAO consolida a
   carta). Intuição existe por área criativa (design/visual/criativo/video/imagem/marca/estetica/copy).
6. **Grill em rodadas persistidas** (`grill_rounds`; 422 nunca faz rollback do contador —
   `Grilled` é retorno, não exceção). `racional_arrancado = grill_rounds > 0` no commit — a
   métrica auto-liquidante de D12 já é colunável.
7. **Testes de contexto compartilhado**: quem enfileira sem despachar DRENA no `@AfterEach`
   (`while (dispatcher.dispatchOnce() > 0)`); `BudgetPreflightTest` drena antes do `==0` (era
   order-fragile — corrigido).

## Recorte consciente / residuais
- Grill de **triviabilidade** (ADR-0020) segue aberto — todo approval é grelhado no v1.
- **Tier ADR-da-Empresa/Playbook** (ADR-0019 residual 2) segue aberto (MemoryKind só CARTA).
- CLAUDE.md materializa **só em run de turno** (o CEO); executores em Issue não carregam a carta
  (consciente — revisar quando executor precisar dela).
- `WAKEUP_AGENT` aprovado usa a MESMA trava do arquiteto do enqueue (herdada — nada novo).
- Sem WS/SSE novo; a tela vai de polling (o que a Central já faz).

## PRÓXIMA FATIA — T.3-FE (sessão própria) — ✅ ENTREGUE (2026-07-04, `docs/handoff-teams-t3fe.md`)
Spec congelada: `frontend/prototipos/ceo-conversa-mockup.md` + `.html` (**DESIGN APROVADO**; não
re-derivar). Skills: `/ui-ux-pro-max` (só busca) + `/bencium-impact-designer` + `/emil-design-eng`
(+`/ui-typography` opcional). Endpoints prontos (todos board-only): conversations CRUD-lite +
messages(202)+turns · approvals?status=&conversationId= + decision (422 grill!) · decisions
filtrável · memory/carta · issues/{id}/report. Dados de demo vivos na `teams-demo`: CEO Atlas
(echo de proposta), chat "Geral" com 2 turnos ANSWERED, 1 approval APPROVED + 1 PENDING, carta v1.
Gotcha Turbopack: `rm -rf frontend/.next` entre `next build` e `next dev`.
