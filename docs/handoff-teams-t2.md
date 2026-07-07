# Handoff — Teams T.2 (time + arquiteto obrigatório + cadeia de comando) — ✅ COMPLETA (2026-07-04)

> Fatia **T.2** do plano Teams (ADR-0017 §"Time = entidade", §"Cadeia de comando";
> `handoff-grilling-teams-fechado.md` §C-T.2). Backend-first TDD + FE canvas editável, verificados
> vivos. Piso: **backend 186/186** (`cd backend && mvn -o test`) · **frontend vitest 151/151 + tsc
> limpo + next build ✓**. **Flyway V14→V15 + ddl-validate PASS** no Postgres real (`pgdata`); jar
> combinado (T.2 + GET /versions do T.1) no :8080. Repo sem git — nada commitado.

## O que T.2 entregou

### Backend (TDD, RED confirmado antes)
- **V15__team.sql:** `team` (id, company_id FK, dominio, foco, arquiteto_agent_id FK nullable,
  `uq_team_company_dominio_foco`) + `agent.team_id` FK + índices. Arquiteto NASCE nulo — a trava é
  no DISPATCH, não na criação (montar aos poucos).
- **`org.team`** (módulo profundo): `Team`/`TeamRepository`/`TeamService`/`TeamController`.
  Invariantes no service: unicidade (pré-check → 409; unique da entidade = backstop H2/PG),
  **arquiteto é MEMBRO do time** (designar valida; SAIR do time LIMPA o posto na mesma tx — nunca
  ponteiro stale), e a **trava do dispatch** em duas faces:
  1. `assertDispatchAllowed(agent)` → **409 no `WakeupService.enqueue`** ANTES de criar wakeup/run
     (mensagem acionável: "time sem arquiteto nao dispara trabalho (ADR-0017): designe...").
  2. `dispatchBlocked(agent)` → **preflight no `HeartbeatDispatcher`** cancela run QUEUED que entrou
     por fora do enqueue (promote-on-finalize) — espelha o preflight de budget, defesa em profundidade.
- **`org.agent.ChainOfCommandService`** ("ligar o reports_to"): `reparent(companyId, agentId,
  novoLiderId|null)` — null = vira raiz; **anti-ciclo** (sobe a cadeia a partir do novo líder com
  visited-set; self incluso → 409; dado pré-cíclico não trava o walk). Profundidade LIVRE no v1
  (residual 5 do fechado: a árvore proíbe só ciclo — cap de profundidade é do spawn_lineage/T.4).
- **Superfície HTTP** (estrutura organizacional = GOVERNANÇA → mutações **board-only**, coerente com
  o hire pós-R1; leitura member):
  - `GET/POST /api/companies/{id}/teams` · `PATCH /teams/{teamId}/arquiteto` `{agentId|null}`.
  - `PATCH /agents/{agentId}/reports-to` `{reportsToId|null}` · `PATCH /agents/{agentId}/team`
    `{teamId|null}` — sub-recursos próprios porque **null é VALOR legal** (raiz / sair do time).
  - `AgentResponse` ganhou `teamId`; **o hire agora VALIDA `reportsToId`** (líder inexistente/de
    outra company → 404 — antes aceitava UUID solto).
- **Testes novos:** `TeamServiceTest` (7) · `ChainOfCommandTest` (5) · `TeamHttpTest` (4, incl.
  wakeup 409 e hire 404) · `DispatchArchitectGateTest` (2, enqueue + preflight). 166→184 (+ 2 do
  GET /versions da task paralela = 186).
- **Smoke Postgres real** (teams-demo): POST /teams 201 → PATCH team do agente → wakeup **409** com
  mensagem → PATCH arquiteto → wakeup **202**.

### Frontend (T.2-FE — o canvas virou EDITÁVEL)
- **`api.ts`:** `Team` + `listTeams`/`createTeam`/`setTeamArquiteto`/`reparentAgent`/
  `assignAgentTeam`; `AgentSummary.teamId`. `query-keys.ts` += `teams(companyId)`.
- **`team-canvas.tsx`:** **drag-to-reparent** (arrasta o nó e solta SOBRE o novo líder →
  `PATCH /reports-to`; solto no vazio re-assenta sem mudar nada; `nodeDragThreshold=4`;
  `getIntersectingNodes` decide o alvo; erro do backend → toast `bad` + re-assenta do dado real —
  **o canvas espelha, o backend impõe**). Menu **⋮ por nó** (`nodrag`): mover para time (com cor e
  aviso "sem arquiteto"), sair do time, **designar arquiteto**, desligar do líder (vira raiz). Nó
  ganhou **listra do time** (`teamHue`, determinística) e marca "· arquiteto". Poll de 2s re-assenta
  posições EXCETO durante arrasto (ref `arrastando`).
- **`team-toolbar.tsx`:** régua de chips de time (LED verde = arquiteto ok; âmbar + tag "SEM
  ARQUITETO" = **dispatch travado**, espelho do gate) + dialog "Novo time" (dominio/foco; avisa que
  nasce travado).
- **Verificado VIVO** (Preview, teams-demo): criar time → chip travado; mover Lyra pro time (menu ⋮,
  listra aparece); designar arquiteta → toast "destravou o dispatch" + chip verde + "· arquiteto";
  **drag Marquinhos → Lyra** = toast "Cadeia religada", 3ª aresta, layout re-assenta; **drag Lyra →
  Echo (ciclo)** = toast `bad` com a mensagem do backend + snap back. (Nota p/ evals sintéticos: o
  drag do xyflow é d3-drag → **MouseEvent**, não PointerEvent.)

## Decisões da fatia (registradas)
1. **Gate em DUAS faces** (enqueue 409 + preflight cancela) — o promote-on-finalize criava runs por
   fora do enqueue; uma face só deixaria a janela aberta.
2. **Arquiteto-é-membro por construção** (mover/sair limpa o posto) em vez de re-validar membership
   no dispatch — invariante barato no ponto de escrita, dispatch O(1).
3. **Board-only para estrutura organizacional** (teams, arquiteto, reparent, team do agente);
   leitura member. PATCH de motor segue member (comportamento pré-existente, não tocado).
4. **Null explícito via sub-recurso** (reports-to/team/arquiteto) — PATCH genérico não distingue
   ausente de null.
5. Durante a fatia, a task paralela do **GET /versions** aterrissou (residual #1 do T.1 FECHADO —
   ver `handoff-t1-fe-canvas.md` decisão 2); reconciliei a árvore (jar combinado, migração FE do
   savelog real concluída) e os pisos re-rodaram verdes.

## Residuais de T.2 (aceitos, decidir depois)
1. **Warning dev-only "002" do xyflow em rajadas pós-mount** do organograma (idle limpo; produção
   sem efeito; `nodeTypes` é módulo-const — investigar interação Turbopack/re-render burst).
2. **Editar/excluir time** (rename dominio/foco, DELETE) — fora do v1; criar + arquiteto bastam.
3. **Drop-zone TIPADA por tipo de função** (nascer válido no drop) — a visão-alvo do ADR-0017 §UI;
   v1 entrega drag-reparent + menu; a tipagem do drop entra quando `tipo_funcao` do AGENTE existir
   (hoje só o molde carrega tipo).
4. **Times como GRUPOS visuais no canvas** (React Flow parent nodes) — hoje o time aparece como
   listra/cor + régua; agrupar espacialmente é evolução.
5. `reports_to_default` do molde segue SUGESTÃO (texto) — auto-ligar no hire quando houver um
   candidato inequívoco é decisão futura.

## Ambiente / repro
- db up (`pgdata`, **V15**); jar combinado em :8080 (`/tmp/orquestrador-v15b.log`). Rebuild:
  `cd backend && mvn -o -q clean package -DskipTests` → `pkill -f orquestrador` → `java -jar ...`.
- Front: `preview_start frontend` (:3000; **`rm -rf .next` antes se rodou `next build`** — cache do
  Turbopack envenena o CSS servido). Board dev `b0a7d000-...-0001`; company de teste `teams-demo`
  (times `Dev/frontend` [Lyra arquiteta ← Echo/Marquinhos/Nix] e `Dev/smoke` [SmokeBot]).

## Depois de T.2 → **T.3** (o grande; cruza ADR-0016/0018/0019/0020)
CEO conversacional + memória + Approval: migrations `conversation`/`company_memory`/`approval`/
`decision` + `agent_session.conversation_id`; `CompanyMemoryMaterializer`/`LiveStateProjector`/
`CompanyMemoryConsolidator` (zero LLM); seam inbound mensagem-do-dono → turno → `WakeupService`;
gate de proposta (SAÍDA) → approval PENDING → endpoint board aprova/rejeita; racional-arrancado
(ADR-0020) na Approval; report-por-Issue vira Ledger (D3). **Protótipo aprovado:**
`frontend/prototipos/ceo-conversa-mockup.{html,md}` (a conversa é COLUNA DOCADA no Mission
Control, não 6ª superfície). Depois **T.4** (contenção: spawn_lineage/fan-out/ledger).
