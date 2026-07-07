# Handoff — Implementação do FRONTEND, fatias F.2→F.6 (shell → telas)

> Sessão de 2026-07-02 fechou: **F.0** (4 endpoints de observação board-only, backend 98→107/107),
> **P.1** (protótipo cadillac **APROVADO pelo usuário → spec visual CONGELADA** em `prototipos/`) e
> **F.1** (fundação do frontend: theme portado, fetcher único, queryKeys, CompanyLink, rotas
> `[companySlug]`, docs ADR-0013/CONTEXT/ARQUITETURA aplicados).
> **Este doc é a fonte da próxima sessão: implementar F.2 (shell) → F.3 (Mission Control) →
> F.4 (Issues) → F.5 (Review) → F.6 (Agents).** Decisões macro: `docs/handoff-grilling-frontend-fechado.md`
> (11 decisões) + `docs/adr/0013-arquitetura-frontend-board.md`. NÃO re-derivar nada disso.

---

## 0. Fatos aterrados (estado exato do repo)

**Backend:** `backend/` — **107/107 verde** (98 do review gate + 9 da F.0), migrations V1→V11 validadas
em Postgres 16 real, ZERO migration pendente. Jar builda com `mvn -q clean package -DskipTests`.

**Endpoints da F.0 (novos, board-only via `CompanyAccessGuard.assertBoardMember`; módulo
`com.orquestrador.board`) — shapes EXATOS que as telas consomem:**

1. `GET /api/companies/{id}/mission-control` → `{"agents":[{ "agent":{id,name,role},
   "status":"RUNNING|QUEUED|BLOCKED|IDLE", "activeRun":{id,issueId,issueTitle,startedAt,attempt}|null,
   "lastSteps":[{runId,type,detail,createdAt}] }]}` — ≤5 steps, cronológicos; `lastSteps` vem do run
   ativo OU do mais recente (card ocioso mantém rastro); cards ordenados por criação (não dançam);
   em QUEUED o `startedAt` é null; `detail` null em eventos de ciclo de vida (estilizar pelo `type`).
2. `GET .../runs/{runId}/steps?offset=N&limit=M` → `{runId, runStatus, steps:[{type,detail,createdAt}],
   nextOffset}` — offset por CONTAGEM (append-only ⇒ estável): repita `nextOffset` no próximo poll;
   página vazia = sem novidade; `runStatus` junto = o front para de pollar sem 2º endpoint.
   Clamps: offset≥0, limit [1..500] default 200. Run de outra company → 404.
3. `GET .../issues/{issueId}/runs` → `[{id,agentId,issueId,status,attempt,createdAt,startedAt,
   finishedAt,lastError}]` desc. Issue de outra company → 404.
4. `GET .../activity?limit=` → `[{id,actorType,actorId,action,entityType,entityId,detail,createdAt}]`
   desc; `detail` é JSON CRU (objeto, não string); limit clamp [1..200] default 50.

**Endpoints pré-existentes** (ver §0 do fechado): companies, agents (+api-keys, +skills), issues
(create/checkout/complete/unblock{REOPEN|CANCEL}/review{decision,reason?,reviewId?}), reviews?status=,
wakeups, heartbeat-runs, knowall/search. Erros: `{error,message}` com 400/403/404/409/422.

**Frontend `frontend/` (fundação F.1 PRONTA):**
- `src/app/globals.css` — theme OKLCH portado do protótipo (`:root` + `@theme inline`): utilities
  `bg-background/-deep`, `bg-raised`, `bg-overlay`, `bg-terminal`, `border-line/-soft/-hot`,
  `text-foreground/dim/faint`, `text-phosphor/-soft/-dim`, `bg-phosphor-bg`, `amber/coral/ice(+-bg)`,
  `idle`, `font-display/sans/mono`, easing `--ease-out-strong`. Atmosfera global (grid+grain) já no body.
- `src/lib/api.ts` — `apiFetch<T>` (X-Board-User SÓ aqui; browser same-origin, server BACKEND_ORIGIN;
  `ApiError{status,error,message}`; 204→undefined) + `listCompanies`/`findCompanyBySlug`. **Todo fetch
  novo passa por `apiFetch`.**
- `src/lib/query-keys.ts` — registro central (prefixo `["company", id]`; `companyScope` p/ invalidação
  em bloco). **Toda query nova registra aqui.**
- `src/lib/get-query-client.ts` + `src/app/providers.tsx` — padrão oficial v5 (staleTime 30s).
- `src/components/company-link.tsx` — **`CompanyLink` + `useCompanyNav` = ÚNICO caminho de navegação**
  (nunca `next/link`/`useRouter` direto em páginas; exceções já marcadas: raiz e not-founds).
- Rotas: `app/[companySlug]/layout.tsx` (company por slug server-side + `notFound()` + Hydration),
  `loading.tsx`, `page.tsx` (placeholder "F.1 de pé" — a F.3 substitui), `app/page.tsx` (redirect
  última company via `orq.lastCompanySlug` gravada NO CLIQUE / picker), not-found em 2 níveis (ver §D).
- `next.config.ts` — rewrites `/api/:path*` → `BACKEND_ORIGIN` (default :8080).
- Vitest: `vitest.config.ts` (node, `src/**/*.test.ts`, alias @→src); `api.test.ts` (7) +
  `query-keys.test.ts` (6). `npm run test|typecheck|lint|build`.
- `.env.local`: `NEXT_PUBLIC_DEV_BOARD_USER=b0a7d000-0000-4000-8000-000000000001` + `BACKEND_ORIGIN`.
- Deps instaladas: @tanstack/react-query v5, tailwindcss v4 (+postcss), cva/clsx/tailwind-merge,
  lucide-react, vitest. **shadcn: NÃO rodou init CLI** — copy-in por demanda (dialog/dropdown etc. nas
  fatias que precisarem; utils `cn` criar quando o 1º componente entrar).

**Protótipo = SPEC CONGELADA:** `prototipos/{index.html,styles.css,app.js}` (servido pela config
`prototipo` do `.claude/launch.json`, python http.server :4173). **É a referência visual pixel-a-pixel
e de MOVIMENTO das fatias F.2→F.6** — abrir lado a lado ao implementar cada tela. Fixtures reais em
`prototipos/fixtures/` (7 JSONs + README com notas de shape). Dados demo no Postgres (volume `pgdata`):
company **"Demo Fixtures" slug `demo-14262`** com Atlas/Nix/Echo e runs/steps/feed reais gravados.

**Design congelado (resumo operacional):** dark-only; verde-fósforo = marca = "trabalho acontecendo"
(RUNNING/CTA/wordmark são a MESMA cor); âmbar = fila/review; coral = bloqueado/falha; Chakra Petch
(display) + IBM Plex Sans (UI) + IBM Plex Mono (terminal/IDs/tempo, `tabular-nums` em contadores);
movimento ≤300ms ease-out forte, steps translateY+fade, LED RUNNING respira 2.4s, feed flash verde
1.4s, stagger 40ms, drawer 260ms da direita, hover -2px, active scale(0.97-0.99), reduced-motion
respeitado. **Zero cor fora dos tokens; zero shadcn default sem retematizar.**

---

## A) Regras que regem a implementação (não re-decidir)

1. **Protótipo-como-spec:** divergência visual/comportamental do protótipo = bug, salvo decisão
   explícita do usuário na sessão. As 4 skills de design (/bencium-impact-designer,
   /bencium-innovative-ux-designer, /emil-design-eng, /ui-ux-pro-max) só voltam se surgir decisão
   visual NOVA não coberta pelo protótipo.
2. **Polling (ADR-0013):** Mission Control `refetchInterval: 2000` + `refetchIntervalInBackground:
   false`; transcript aberto poll 2s no `steps?offset=` SÓ com run ativo (delta incremental — parar
   quando `runStatus` terminal); Issues/Review/Agents `staleTime` 30s + refetch on focus + invalidação
   manual pós-mutação (via `queryKeys`).
3. **Toda query nova em `queryKeys`; todo fetch via `apiFetch`; toda navegação via
   `CompanyLink`/`useCompanyNav`.** CompanyId vem da company do layout (cache hidratado
   `queryKeys.companies()` + `useParams().companySlug`) — criar hook `useActiveCompany()` na F.2.
4. **Lógica em `src/lib` com Vitest; componentes finos.** Candidatos já previstos: merge incremental
   do transcript por offset (F.3/F.4), formatadores de tempo (elapsed mm:ss com tabular-nums, "há Xs"),
   mapeamento status→cor/label, agrupamento de issues por status (F.4).
5. **Rito por fatia:** piso (tsc+eslint+`vitest run`+`next build`) + **verificação VIVA** no Preview
   MCP contra backend real comparando com o protótipo → atualizar a memória
   `agente-orquestrator-stack.md` → **NÃO commitar** (repo sem git). Revisão adversarial 3-lentes
   (workflow) SÓ na F.3 e na F.4.
6. Board user de dev: `b0a7d000-0000-4000-8000-000000000001` (membro da company demo).

---

## B) Ambiente — como subir tudo

```bash
# 1. Postgres (volume pgdata já tem V1→V11 + dados demo)
cd agente-orquestrator && docker compose up -d db
# 2. Backend (jar já buildado em backend/target; rebuild: mvn -q clean package -DskipTests)
java -jar backend/target/orquestrador-0.0.1-SNAPSHOT.jar   # :8080
# 3. Frontend + protótipo: via Preview MCP (.claude/launch.json já tem as configs)
#    preview_start "frontend"  → next dev :3000
#    preview_start "prototipo" → spec visual :4173 (abrir lado a lado)
```
Suíte backend: `cd backend && mvn -o test` (107/107). Piso frontend: `cd frontend && npm run
typecheck && npm run lint && npm run test && npm run build`.

---

## C) Fatias ORDENADAS restantes (conteúdo + fechamento)

| # | Fatia | Conteúdo | Fecha com |
|---|---|---|---|
| **F.2** | **Shell** | Portar do protótipo: sidebar fixa 232px (`bg-deep`, wordmark com glifo+glow, company switcher com avatar/gradiente, nav das 5 superfícies com ícones lucide + estado ativo `phosphor-bg`+barra ::before + contadores, sys-lines do rodapé com LED), layout do segmento passa a envolver `children` no grid `sidebar+main`. Hook `useActiveCompany()`. Switcher: dropdown com as companies (grava `orq.lastCompanySlug` no clique + navega). Rotas placeholder: `issues/`, `issues/[issueId]/`, `reviews/`, `agents/`, `agents/[agentId]/` (páginas mínimas tematizadas). Contadores da nav podem nascer estáticos/omitidos (ligam na F.3+). | piso + verificação viva (navegar as 5 rotas, ativo correto, switcher funciona) comparada ao protótipo. |
| **F.3** | **Mission Control** (densa) | A home real: tallies (rodando/fila/bloqueados/ociosos derivados do agregado), grid de cards (LED animado, status word, issue line, mini-terminal ≤5 linhas com cores por tipo/prefixo `[warn]`/`[error]`/`[done]` + cursor quando RUNNING, foot com elapsed tick 1s + attempt), feed de atividade (ícone por action, "há Xs", flash em entrada nova), **drawer de StepTrace** (componente COMPARTILHADO com F.4: type-tags, timestamps, autoscroll, streaming incremental por `nextOffset` enquanto RUNNING, runStatus badge). Poll 2s no agregado (pausa aba oculta). Helpers testados: merge incremental por offset, formatadores, mapa status→apresentação. | piso + **revisão adversarial 3-lentes** + validação viva com AGENTE REAL rodando (wakeup no Atlas echo da company demo e ver o card+drawer streamando). |
| **F.4** | **Issues** (densa) | Lista agrupada por status (ordem: IN_PROGRESS, PENDING_REVIEW, BLOCKED, OPEN, DONE, CANCELLED) com LED, risk badge R1-5, assignee chip, id curto; hover-intent prefetch nas linhas (`prefetch={false}` + onMouseEnter). Detail: título/descrição/props panel (status pill, risco, assignee, criada, id), ledger de runs (F.0 endpoint; linha abre o drawer de transcript), ações **unblock REOPEN/CANCEL** (dialog de confirmação; POST + invalidação), link p/ review quando PENDING_REVIEW. Issue inexistente → `notFound()` (cai no not-found do segmento ✓). Criar issue ("Nova issue": title/description/riskLevel 1-5 → 422 fora do range). | piso + **revisão adversarial** + viva (fluxo real: criar → wakeup → BLOCKED → reopen). |
| **F.5** | **Review** | Fila PENDING (default) + filtro status; card com issue/risco/assignee/run link (abre drawer)/**âncora reviewId** (mono)/esperando-há; decisão APPROVE/REJECT com dialog de reason (≤2000; recomendado no reject) mandando `{decision, reason?, reviewId}` (SEMPRE ancorar com o id da fila); card sai animado; invalidar reviews+issues+missionControl. Tolerar review PENDING apontando run FAILED (latente R.2: mostrar status do run, não assumir SUCCEEDED). | piso + viva (aprovar/rejeitar de verdade contra o backend). |
| **F.6** | **Agents** | Roster (grid/linhas: avatar hue por nome, role, runtime `adapterType · executionTarget`, nº skills, status derivado do agregado). Detail: trace ao vivo (reusa card/drawer), runs recentes (`GET .../agents/{id}/heartbeat-runs`), skillset em chips versionados (`GET .../agents/{id}/skills`, leitura), **mint de API key** (POST api-keys → reveal one-time "guarde agora — só o hash fica no servidor"). "Contratar agente" (POST agents nome/role) se couber. | piso + viva. |

Pós-F.6 (fora deste handoff): passada final de consistência + decidir próxima frente com o usuário.

---

## D) Gotchas descobertos nesta sessão (não re-tropeçar)

- **`notFound()` chamado em LAYOUT resolve no not-found do segmento PAI** (App Router). Por isso:
  `app/not-found.tsx` = slug/company inválida; `app/[companySlug]/not-found.tsx` = recurso interno
  (issue/agent — é o que as PAGES da F.4/F.6 acionam). Não "consertar" isso de volta.
- **React 19 loga erro com `<script>` inline re-renderizado** e `next/script` inline
  `beforeInteractive` NÃO executa no App Router → v1 dark-only usa classe `dark` estática (anti-flash
  garantido). Script pré-paint só volta com o light theme (nota no ADR-0013).
- **Preview MCP:** screenshots às vezes saem minúsculos (DPR 2) — para checagens finas usar
  `preview_inspect`/`preview_eval`; `preview_click` logo após troca de rota pode correr contra o
  render (re-tentar/aguardar; não é bug do app). Console logs são ACUMULATIVOS da sessão do browser —
  comparar contagem antes/depois para saber se um erro é novo.
- **Turbopack (next dev) pode 500 transitório durante hot-reload** de edits em layout — reload
  resolve; só investigar se persistir em estado estável.
- Steps com `detail: null` (RUN_STARTED/RUN_SUCCEEDED/RUN_FAILED/RUN_QUEUED) — estilizar pelo `type`;
  nunca `String(null)`.
- `agente-orquestrator/` NÃO é repo git (não commitar; não rodar git init sem pedido).
- Backend precisa de rede só para `mvn package` (plugins); `mvn -o test` roda offline.

## E) Limites v1 (relembrar)

Sem WS (polling), sem login (X-Board-User no fetcher), sem Costs/budget UI, sem command palette /
inbox / org chart / routines / skills-KnowAll UI / i18n / mobile (desktop-first). Steps = stdout
linha-a-linha (`type`+`detail` bastam; parser rico por adapter = evolução). RTL/e2e adiados
(protótipo-como-spec + comparação manual). Mission Control = cards (Gantt = futuro).
