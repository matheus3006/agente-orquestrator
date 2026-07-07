# Handoff — Implementação do FRONTEND, fatias F.5→F.6 (Review → Agents)

> Sessão de 2026-07-02 fechou **F.2 (shell)**, **F.3 (Mission Control)** e **F.4 (Issues)** — cada uma
> com o rito completo (TDD nos helpers → piso → verificação viva contra backend real → revisão
> adversarial 3-lentes nas densas → memória). Pisos: **frontend 110/110**, **backend 107/107**
> (com 1 mudança: `IssueResponse` ganhou `description`).
> **Este doc é a fonte da próxima sessão: implementar F.5 (Review) → F.6 (Agents).**
> Decisões macro: `docs/handoff-grilling-frontend-fechado.md` + `docs/adr/0013-arquitetura-frontend-board.md`.
> Handoff anterior (F.2→F.6, ainda vale como referência de shapes da F.0): `docs/handoff-frontend-f2-f6.md`.
> NÃO re-derivar nada disso.

---

## 0. Fatos aterrados (estado exato do repo)

**Backend:** `backend/` — **107/107 verde**, migrations V1→V11, ZERO migration pendente.
`IssueResponse` agora expõe `description` (mudança da F.4, TDD em `IssueRiskLevelTest`); o jar em
`backend/target/` JÁ está rebuildado com ela. Rebuild: `mvn -q clean package -DskipTests` (rede).

**Endpoints que a F.5 consome (shapes EXATOS, aterrados no código):**

1. `GET /api/companies/{id}/reviews?status=` (default `PENDING`; `APPROVED|REJECTED`; valor inválido → 400)
   — **BOARD-only**, ordem de CHEGADA (`requestedAt` asc) →
   `[{id, issueId, runId, status, reviewerActorType|null, reviewerActorId|null, decisionReason|null,
   requestedAt, decidedAt|null}]`. `decisionReason` é dado de negócio (PII fica FORA do activity_log,
   ADR-0009) — só aparece aqui. Título/risco/assignee da issue NÃO vêm: resolver via lista de issues
   (`queryKeys.issues`), como o feed já faz.
2. `POST /api/companies/{id}/issues/{issueId}/review` body `{decision: "APPROVE"|"REJECT",
   reason?: string ≤2000, reviewId?: uuid}` — BOARD-only. **A UI SEMPRE manda o `reviewId` do card da
   fila** (âncora do ciclo, decisão travada da R.3): âncora ≠ ciclo aberto → **409** (decisão stale não
   aprova ciclo novo); enum/UUID inválido no body → **400**; `reason` >2000 → **422**; issue não
   PENDING_REVIEW → **409**. APPROVE→issue `DONE` + review `APPROVED`; REJECT→issue `BLOCKED` + review
   `REJECTED` (o recovery é o unblock REOPEN/CANCEL que a F.4 já construiu no detail da issue).
3. **Latente R.2 aceito (tolerar, não "consertar"):** review `PENDING` pode apontar um run que acabou
   `FAILED` (janela de crash settle→finalize). O card deve mostrar o **status REAL do run** (o drawer
   já faz: `runStatus` vem na página de steps) — nunca assumir SUCCEEDED.

**Endpoints que a F.6 consome:**

4. `GET /api/companies/{id}/agents` → `[{id, companyId, name, role, reportsToId|null, adapterType,
   executionTarget, createdAt}]` (fetcher `listAgents` JÁ existe em `api.ts`).
5. `GET .../agents/{agentId}/heartbeat-runs` → `[{id, agentId, issueId|null, status, attempt,
   createdAt}]` desc. **ATENÇÃO: shape REDUZIDO** — SEM `startedAt/finishedAt/lastError` (diferente do
   ledger de issue da F.0). A coluna de tempo usa `createdAt` ("há X"); erro não vem — o drawer conta.
6. `GET .../agents/{agentId}/skills` (qualquer membro) → `[{companySkillId, skillKey|null, version}]`
   (chips versionados; `skillKey` null se a versão pinada não resolver — degradar, nunca quebrar).
7. `POST .../agents/{agentId}/api-keys` (sem body) → **201 `{apiKey}`** — o segredo em claro aparece
   **UMA vez** (reveal `key-reveal` + "guarde agora — o servidor só armazena o hash SHA-256").
   NUNCA guardar em estado além do necessário pro reveal; não logar.
8. `POST .../agents` body `{name!, role!, reportsToId?, adapterType?, runtimeCommand?,
   executionTarget?}` → 201 ("Contratar agente"; para demo local: `adapterType: "process"`,
   `executionTarget: "LOCAL"`, `runtimeCommand` livre — um echo já dá card vivo no MC).
9. Status/"doing" do roster deriva do **agregado** `GET .../mission-control` (mesma queryKey da home →
   cache compartilhado; poll 2s com `refetchIntervalInBackground: false`).

**Frontend `frontend/` — TUDO da F.1→F.4 pronto para reusar:**

- **lib:** `api.ts` (fetcher único + TODOS os tipos/fetchers: companies, mission-control, runSteps,
  activity, issues+create+unblock, issueRuns, agents), `query-keys.ts` (**`reviews(companyId, status)`,
  `agents`, `agentRuns`, `agentSkills` JÁ registrados** — F.5/F.6 não criam chave nova, só usam),
  `format.ts` (fmtClock/fmtAgo/fmtElapsed/shortId), `transcript.ts` (+`caughtUp`), `mission-control.ts`
  (statusWord/tallies/`issuesListBehindAggregate`), `feed.ts`, `issues.ts` (grupos/riskNote/
  `issueBehindLedger`/`ledgerKeepsPolling`/ledgerWhen), `agent-hue.ts`, `use-active-company`, `use-now`,
  `use-run-transcript`, `api-latency`, `toaster.ts` (auto-dismiss NA store), `shell.ts`, `utils.ts`.
- **componentes:** `View/ViewHead/SurfacePlaceholder`, `Sidebar`, **`StepTraceDrawer`** (modo agente =
  streaming / modo congelado `agentName:null` = fiel ao openDrawerForFrozenRun), `ActivityFeed`
  (prop `emptyNote`), **`AgentCard`** (com MiniTerm interno — o "trace ao vivo" do detail F.6 pode
  reusá-lo direto), `AgentDot`, `RiskBadge`/`AssigneeChip` (**null = "— livre" / undefined = "—"
  enquanto agents carrega — manter a distinção**), `ui/dialog` + `ui/dropdown-menu` (radix copy-ins
  retematizados), `NewIssueDialog`, `UnblockIssueDialog`, `ToastZone`+`pushToast`,
  `CompanyLink`/`useCompanyNav` (push/replace/**prefetch** — hover-intent).
- **CSS já portado** (`globals.css`, `@layer components`): leds, tallies, agent-card/mini-term,
  feed, drawer/transcript/type-tags, run-status, btn(primary/danger/ghost), empty-state, agent-dot,
  status-group/row/risk-badge/assignee-chip, crumb/detail-grid/props-panel/status-pill, ledger,
  action-bar, dialog(.dialog-scrim/panel/body/title/text/actions), toast.
  **FALTA portar (protótipo `styles.css` ~linhas 843–976):** `.filter-tabs/.filter-tab(.on)` (F.5),
  `.review-card` (+nth-child delays, `.leaving`, `.review-title`, `.review-meta`, `.review-actions`,
  `.waiting-clock`) (F.5), `.roster/.roster-row` (+delays, `.roster-id/.roster-name/.roster-role/
  .roster-doing/.roster-cell`) (F.6), `.kv-panel`, `.skill-chip(+.v)/.chips`, `.key-reveal` (F.6).

**Padrões estabelecidos nesta sessão (SEGUIR, não re-decidir):**
- Invalidação pós-mutação = `queryClient.invalidateQueries({queryKey: queryKeys.companyScope(id)})`.
- Consistência agregado(2s)↔lista(30s) = **predicado puro testado + invalidação guardada por
  `dataUpdatedAt`** (exemplares: `issuesListBehindAggregate` na home, `issueBehindLedger` no detail).
- TODA query com ramo `isError` visível (nada de skeleton infinito/empty-state mentiroso).
- Erro de mutação com cache provado errado (ex.: 409) → toast bad + invalidar companyScope.
- Polls sempre `refetchIntervalInBackground: false`; activity SEMPRE `limit 50` (chave compartilhada).
- Drawer ESC tem guard `event.defaultPrevented` (dialog radix aberto consome o ESC primeiro).

**Protótipo = SPEC CONGELADA:** `prototipos/` (config `prototipo` do launch.json, :4173). Para F.5 ver
`viewReviews` + `openDecisionDialog` (app.js ~519–556 e ~734–771); para F.6 `viewAgents` +
`viewAgentDetail` + `mintKey` (app.js ~558–643 e ~799–805). Fixture de reviews: array `REVIEWS` no
app.js (âncora `rv-xxx` visível em mono no card).

**Dados demo no pgdata (V11):** company `demo-14262` (Atlas echo-sucesso ~14s/12 steps; Nix
echo-falha exit 1; Echo ocioso) + `playground-lab` (vazia). Issues: clássica do Nix BLOCKED
(`c981a028`, 5+ FAILED no ledger), "Publicar release notes da F.4" BLOCKED, várias DONE de ensaio.
**A fila de review está VAZIA** — a verificação viva da F.5 cria o cenário: issue `riskLevel: 5` →
wakeup **Atlas** → sucesso estaciona em PENDING_REVIEW (limiar default = 5) → review PENDING na fila.
Board user de dev: `b0a7d000-0000-4000-8000-000000000001`.

---

## A) Regras que regem a implementação (não re-decidir)

1. **Protótipo-como-spec:** divergência visual/comportamental = bug. As 4 skills de design só voltam
   com decisão visual NOVA não coberta (na F.4, o dialog "Nova issue" foi composto 100% do vocabulário
   congelado sem elas — mesmo critério).
2. **F.5 e F.6 são fatias LEVES: SEM revisão adversarial obrigatória** (o handoff original manda
   workflow só nas densas F.3/F.4, já feitas). Rito: TDD nos helpers de `src/lib` → piso
   (tsc+eslint+`vitest run`+build) → verificação VIVA → atualizar a memória
   `agente-orquestrator-stack.md` → **NÃO commitar** (repo sem git).
3. **Toda query nova em `queryKeys` (as da F.5/F.6 JÁ existem); todo fetch via `apiFetch`; toda
   navegação via `CompanyLink`/`useCompanyNav`.**
4. Polling (ADR-0013): reviews/agents = staleTime 30s + refetch on focus + invalidação pós-mutação;
   agregado do roster = a mesma query de 2s do Mission Control.
5. **Decisão de review SEMPRE ancorada:** payload `{decision, reason?, reviewId: <id do card>}`.

## B) Ambiente — como subir

```bash
cd agente-orquestrator
docker compose up -d db
pkill -f orquestrador-0.0.1-SNAPSHOT.jar 2>/dev/null   # pode haver um jar antigo no :8080
java -jar backend/target/orquestrador-0.0.1-SNAPSHOT.jar   # :8080 (jar JÁ tem description)
# frontend: preview_start "frontend" (:3000); protótipo: preview_start "prototipo" (:4173)
```
Piso: `cd frontend && npm run typecheck && npm run lint && npm run test && npm run build` (110) e
`cd backend && mvn -o test` (107).

## C) Fatias ORDENADAS restantes (conteúdo + fechamento)

| # | Fatia | Conteúdo | Fecha com |
|---|---|---|---|
| **F.5** | **Review** | Fila `PENDING` default + `filter-tabs` PENDING (com count)/APPROVED/REJECTED. `review-card`: título da issue (link p/ detail via CompanyLink), `RiskBadge` + `AssigneeChip` (resolvidos pela lista de issues/agents), link do run em ice (abre o **StepTraceDrawer** — mostra o `runStatus` REAL, latente R.2), **âncora `reviewId` visível em mono**, `waiting-clock` âmbar "há X" (tick 1s). Decisão APPROVE/REJECT via dialog (textos do `openDecisionDialog`: reason ≤2000, "recomendado no reject", nota "fica na tabela review, nunca no activity log") → POST com `{decision, reason?, reviewId}` → **card sai animado `.leaving` (~320ms) ANTES de invalidar** `companyScope` → toast. Abas decididas mostram `decidedNote` (reason em aspas + "decidida há X"). Empty: "fila limpa — nenhum ciclo {status} no momento" com shield. Erros: 409 âncora stale → toast bad + invalidar (padrão F.4); 422 reason → no dialog. Helpers TDD candidatos: montagem do card (review+issue+agente resolvidos), "esperando há". | piso + **viva DE VERDADE**: issue risk 5 → wakeup Atlas → PENDING_REVIEW → fila mostra → APPROVE → DONE (+toast/feed) e outro ciclo REJECT → BLOCKED → reopen pelo detail da F.4. |
| **F.6** | **Agents** | Roster (`roster-row` por agente: `AgentDot` 26px, nome/role, "doing" derivado do agregado MC (executando/na fila para/travado em/ocioso — reusar `stationIssueLine`/`statusWord`), runtime `adapterType · executionTarget`, "N skills no set", `agent-status-word`). Detail (`agents/[agentId]`): crumb, header (dot+nome+role+runtime+status), **Trace ao vivo = reusar `AgentCard`** (mini-term ~150px no protótipo) + drawer, "Runs recentes" (heartbeat-runs — **shape reduzido**: colunas run-status + "há X" + id curto; linha abre drawer), props (issue atual/adapter/id curto), `kv-panel` skillset (`skill-chip` `{skillKey} v{version}`), **mint de API key** (`POST api-keys` → `key-reveal` one-time + aviso; nunca persistir/logar), "Contratar agente" (dialog nome/role → POST → invalidar + aparece no roster e no MC). Agente inexistente → `notFound()` gated `isFetchedAfterMount` (padrão F.4). | piso + viva (mint real, contratar agente de teste na `playground-lab` p/ não sujar a demo, roster ao vivo com Atlas rodando). |

**Pós-F.6:** passada final de consistência (navegação cruzada entre as 5 superfícies, empty states da
`playground-lab`, console limpo) + **decidir a próxima frente com o usuário** (candidatos: git init +
primeiro commit; light theme + script pré-paint; WS invalidation-first; Costs UI; skills/KnowAll UI).

## D) Gotchas acumulados (não re-tropeçar)

- **`notFound()` em LAYOUT resolve no not-found do segmento PAI**; not-found interno (issue/agent) é
  das PAGES → cai em `app/[companySlug]/not-found.tsx` com a sidebar de pé. Gate anti-falso-404:
  `isFetchedAfterMount && isSuccess && !encontrado`.
- **React 19:** classe `dark` estática (sem script pré-paint até o light theme); regra
  `react-hooks/refs` proíbe ler ref no render → snapshot de 1º render = `useState(initializer)`;
  `useSyncExternalStore` com server-snapshot ESTÁVEL (nunca `() => []` inline).
- **Preview MCP:** `preview_click` em Link do App Router pode não navegar → `el.click()` via
  `preview_eval`; **aba fica `document.hidden` fora de screenshots e, com 2+ abas de preview, PERMANENTEMENTE**
  → polls/focus-refetch pausam (é o pause do ADR!): validar transições por **reload** (fetch de mount
  dispara mesmo oculto) ou pela **network**, e FECHAR outros previews (`preview_stop`) antes; timers de
  eval são clampados em aba oculta (linhas do tempo mentem); console logs são ACUMULATIVOS (comparar
  contagem); screenshots saem minúsculos em DPR 2 → `preview_resize` 1440×900 antes de julgar layout.
- Steps `detail: null` = evento de ciclo de vida → estilizar pelo `type` (nunca `String(null)`).
- Radix + drawer: ESC do dialog é consumido primeiro (guard `defaultPrevented` já existe no drawer).
- `agente-orquestrator/` NÃO é repo git (não commitar; não rodar git init sem pedido).
- Backend: `mvn -o test` roda offline; `mvn package` precisa de rede. Porta 8080 pode estar ocupada
  por jar antigo (`pkill` antes). Runs de sucesso NÃO geram evento no activity_log (feed só acende com
  bloqueio/review/decisões/skills).

## E) Limites v1 (relembrar)

Sem WS (polling), sem login (X-Board-User no fetcher), sem Costs/budget UI, sem command palette /
inbox / org chart / routines / skills-KnowAll UI / i18n / mobile (desktop-first). Feed = janela finita
(activity limit 50; endpoint por-entidade = futuro). Steps = stdout linha-a-linha. RTL/e2e adiados
(protótipo-como-spec + comparação manual). Heartbeat-runs do agente = shape reduzido (sem timing fino).
