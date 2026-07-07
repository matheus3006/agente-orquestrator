# Handoff — Grilling do FRONTEND (board do operador) — DECISÕES FECHADAS

> Sessão de grilling 2026-07-02 (mesma sessão que implementou o review gate R.1→R.4). Fechou as 11
> decisões do frontend v1. **Nenhum código de frontend foi escrito** — só decisões + deep-research
> verificada. Fonte de referência: `analisys-paperclip/10-ui-frontend.md` (análise da UI do Paperclip,
> incl. rebuild guide §8). Este doc é a fonte para a sessão de implementação.
> **Resultado-chave: v1 = 5 superfícies (shell, Mission Control, Issues, fila de Review, Agents),
> protótipo cadillac PRIMEIRO (HTML standalone, descartável), polling (sem WS), shadcn+Tailwind v4 com
> theme próprio, rotas `/[companySlug]`, proxy same-origin, X-Board-User no v1. UMA fatia-irmã de
> backend (F.0: 4 endpoints read-only board).**

---

## 0. Ponto de partida (fatos aterrados — não re-derivar)

**Backend (98/98, review gate completo, ADRs 0001-0012).** Endpoints que o front consome:
- `POST/GET /api/companies` · `POST/GET .../agents` (+ `POST .../agents/{id}/api-keys`, `GET/POST .../agents/{id}/skills`)
- `POST/GET .../issues` (CreateIssueRequest = title, description?, riskLevel? 1-5 → 422 fora do range;
  IssueResponse expõe riskLevel) · `POST .../issues/{id}/checkout|complete|unblock{REOPEN|CANCEL}|review{decision, reason?≤2000, reviewId?}`
- `GET .../reviews?status=` (default PENDING, board-only, ordem de chegada; expõe decision_reason)
- `POST .../agents/{id}/wakeups` · `GET .../agents/{id}/heartbeat-runs`
- `GET .../knowall/search?q=` · `POST .../skills/import` · budget (`/budget-policies`, `/cost-events`, `/budget-incidents`)
- Erros: `ApiExceptionHandler` → 400 bad_request (body/param inválido), 422 validation_error, 409 conflict, 403 forbidden, 404 not_found. Formato `{error, message}`.

**GAPS de backend que o front precisa (viram a fatia F.0):**
- `heartbeat_run_event (id, run_id, company_id, type, detail, created_at)` = o StepTrace, JÁ persistido
  (STDOUT por linha via `HeartbeatRunStore.recordStep`), repo lê ordenado — **SEM endpoint HTTP**.
- **SEM** "runs ativos da company + step atual", **SEM** runs-por-Issue (só por-agente), **SEM** leitura
  HTTP do activity_log (repo `findByCompanyIdOrderByCreatedAtDesc` existe), **SEM** WebSocket.

**Frontend scaffold:** Next 16.2.9 + React 19.2.4 + TS em `frontend/`; uma página de companies;
`src/lib/api.ts` = fetcher com header `X-Board-User` (env `NEXT_PUBLIC_DEV_BOARD_USER`); sem
Tailwind/shadcn ainda. `company.slug` é único (V1) → serve de prefixo de URL.

---

## A) Decisões fechadas (por pergunta)

### Q1 — Escopo do v1: **5 superfícies.**
1. **Shell** (sidebar + company switcher + navegação); 2. **Mission Control** (home da company — o
diferencial #4); 3. **Issues** (lista/board + detail); 4. **Fila de Review** (o review gate na UI);
5. **Agents** (roster + detail).
**FORA do v1:** dashboard de métricas/charts, **Costs/budget UI** (decisão do usuário: ele usa planos
de assinatura Anthropic/OpenAI, não API por evento → o ledger nunca acumula; budget hard-stop segue
como invariante de backend), skills/KnowAll UI, org chart, routines, inbox, goals/projects, command
palette, onboarding, i18n. **Desktop-first** (uso no PC pessoal; mobile não foi requisito).

### Q2 — Mission Control = **forma A: grid de cards ao vivo, 1 card por agente.**
Card: status derivado (rodando/ocioso/bloqueado), Issue em execução, **mini-terminal com as últimas
3-5 linhas do StepTrace rolando**, tempo decorrido do run. Clicar → transcript completo do run (mesmo
componente do Issue detail). Abaixo do grid: feed de atividade recente (activity log). **Timeline
horizontal estilo Gantt (forma B) = evolução futura** (precisa densidade de dados que não existe ainda).

### Q3 — Fatia-irmã de backend F.0: **endpoint AGREGADO + 3 read-only, tudo board-only.**
- `GET /api/companies/{id}/mission-control` — o servidor faz o join (regra de ouro: módulo profundo):
  por agente → `{agent, statusDerivado, activeRun?{id, issueId, issueTitle, startedAt, attempt}, lastSteps[3-5]}`.
  1 poll = a tela inteira (evita N+1 no poll de 2s).
- `GET .../runs/{runId}/steps?offset=N` — transcript incremental (append-only → offset por contagem é
  estável; devolve steps + nextOffset).
- `GET .../issues/{issueId}/runs` — ledger de runs do Issue detail (query nova company-led).
- `GET .../activity?limit=` — controller novo sobre repo existente.
**Board-only nas 4** (mesmo `requireBoard` da fila de review): superfícies de operador; StepTrace é
stdout cru — não é para agentes lerem. Rito: TDD, piso 98 não regride, validação Postgres real; fatia
leve (read-only) → **sem** revisão adversarial.

### Q4 — Realtime v1: **polling. WS = fatia futura já desenhada.**
- Mission Control: `refetchInterval: 2000` no agregado, **`refetchIntervalInBackground: false`** (pausa
  em aba oculta). Transcript aberto: poll 2s no `steps?offset=` só com run ativo (delta incremental).
- Issues/Review/Agents: `staleTime` 30s + refetch on focus + invalidação manual pós-mutação.
- **Por quê:** valor do WS aparece com multi-usuário/multi-aba; solo v1 o polling entrega a mesma
  experiência percebida por ~5% do custo. Quando o WS entrar (invalidation-first, desenho do Paperclip
  §4.4 da análise — cooldown de toasts, supressão de rota visível, teardown StrictMode-safe), ele
  substitui o polling **sem mudar as telas** (React Query é a mesma camada; muda quem invalida).

### Q5 — Design system: **shadcn/ui + Tailwind v4 (copy-in) com theme PRÓPRIO, dark-first.**
- Theme OKLCH próprio do agente-orquestrator desenhado NO protótipo (não default neutro; não herda
  NutriApp — console técnico, identidade própria). Script pré-paint anti-flash. Ícones `lucide-react`.
- **DIRETRIZ OBRIGATÓRIA do usuário:** a passada visual usa as 4 skills JUNTAS —
  `/bencium-impact-designer` + `/bencium-innovative-ux-designer` + `/emil-design-eng` + `/ui-ux-pro-max`.
  Buscar **design VIVO com movimentação** (steps fluindo, indicadores pulsando, transições com
  propósito — `emil-design-eng` é a lente de "quando animar") e **tirar a cara de design feito por
  IA/shadcn default**. Respeitar `prefers-reduced-motion`.
- Trade-off aceito: diverge do padrão NutriApp (CSS-vars puro); dois paradigmas entre produtos.

### Q6 — Navegação + integração (SELADA COM DEEP-RESEARCH — 104 agentes, claims verificadas):
- **Rotas:** `app/[companySlug]/{issues,issues/[issueId],reviews,agents,agents/[agentId]}` + página
  raiz `/` → redireciona para a última company (gravada client-side) ou picker. Layout do segmento
  carrega a company pelo slug (fetch server-side) e chama **`notFound()`** para slug inválido
  (`not-found.tsx` escopado no segmento) — padrão do Dub em produção. **`loading.tsx` no segmento**
  (habilita prefetch parcial de rota dinâmica).
- **SEM middleware/rewrite para tenant:** Next 16 posiciona proxy como "last resort"; rewrite de tenant
  **quebra o prefetch do Link** (exigiria `as`+`href` manuais). Nota Next 16: `middleware.ts` →
  `proxy.ts` (renomeado, Node runtime) — e **não precisamos dele**.
- **`CompanyLink` + `useCompanyNav`:** wrapper FINO `'use client'` sobre `next/link`
  (`React.ComponentProps<typeof Link>`, lê slug via `useParams`, prepende ao href, spread) — padrão
  next-intl (~4M downloads/sem). **Regra: NUNCA importar `next/link`/`useRouter` direto nas páginas**
  (o shim é load-bearing, lição do Paperclip). Wrapper NÃO reimplementa prefetch. Listas grandes
  (tabela de issues): prefetch hover-intent (`prefetch={active ? null : false}` + onMouseEnter).
- **React Query:** `getQueryClient()` padrão oficial v5 (client novo por request no servidor via
  `isServer`, singleton no browser — **NUNCA QueryClient em escopo de módulo**: vaza entre requests)
  em `providers.tsx`; company do layout prefetched server-side + `dehydrate`/`HydrationBoundary`;
  **telas de polling = client components puros** (`useQuery({refetchInterval})`), sem prefetch
  server-side ("both patterns are fine to mix"). Registro central `queryKeys` parametrizado por
  companyId — toda query e invalidação passam por ele.
- **Proxy same-origin via `rewrites` no `next.config`:** `/api/:path*` → `http://localhost:8080/api/:path*`
  (env var p/ prod). Cookies de sessão futuros nascem first-party; sem CORS.
- **Caching:** fetch de browser NÃO toca Data/Router Cache do Next → nada a desligar para o polling.
  Cuidado único: o endpoint agregado do Spring não mandar headers HTTP de cache. Router Cache: desde
  Next 15 `staleTimes.dynamic=0` (default ok).
- **Última company:** gravar client-side no clique do switcher + redirect em `app/page.tsx`. NÃO gravar
  server-side em request prefetchável (prefetch executaria o side-effect — armadilha documentada do
  next-intl).

### Q7 — Protótipo cadillac das **5 superfícies** (não só as densas). O PORQUÊ (palavras do usuário):
o protótipo permite (a) validar se o **theme** agrada e faz sentido, (b) conferir se os **detalhes**
desejados estão lá, (c) sentir o **produto final**, (d) **adicionar/remover** o que não gostar e trazer
**features novas**, e (e) como temos o modelo do Paperclip, construir rápido e **auditar o que o
Paperclip tem que de fato queremos** (adotar/cortar). Theme validado DENTRO das telas (não em passo
separado). Aprovação do usuário **congela o protótipo como spec visual** das fatias de implementação.

### Q8 — Meio do protótipo: **B — HTML/JSX standalone DESCARTÁVEL** (decisão do usuário).
Vive em `prototipos/` na raiz do repo (fora de `frontend/src`) — **não polui a aplicação real**; depois
reconstruímos no stack de produção. Salvaguardas: animações do protótipo em CSS/JS vanilla que mapeiem
1:1 para o React (o movimento aprovado = o movimento que vai ao ar); dados fake plausíveis usando
**steps reais gravados** do backend (por isso F.0 vem antes); Preview MCP valida nas duas fases.

### Q9 — Auth v1: **A — manter `X-Board-User` (header de dev).**
Uso próprio no PC pessoal; login agora = desperdício (palavras do usuário). Ressalvas de arquitetura:
header injetado num **ÚNICO ponto** (o fetcher) → troca futura por sessão/cookie toca 1 arquivo e
nenhuma tela; o proxy same-origin (Q6) já deixa o cookie futuro first-party.

### Q10 — Piso de verificação do frontend (o "98/98" daqui):
1. **Mecânico por fatia:** `tsc --noEmit` + `eslint` + `next build` verdes.
2. **TDD com Vitest SÓ nos helpers puros de `lib/`** (fetcher/erros, queryKeys, merge incremental do
   transcript por offset, derivação de status, formatadores) — padrão Paperclip: "logic in tested lib
   helpers, thin components".
3. **Telas: verificação VIVA** — cada fatia fecha com o app contra o backend real (`docker compose up
   db` + jar) validado via **Preview MCP** (snapshot/inspect dos estados: rodando/ocioso/bloqueado,
   fila vazia/cheia, erro de API), comparado com o protótipo aprovado.
4. **Revisão adversarial 3-lentes** só nas fatias densas: **F.3 (Mission Control)** e **F.4 (Issue detail)**.
5. **Adiados:** RTL exaustivo e Playwright/e2e. Racional do usuário: o protótipo aprovado é a base
   visual real e a comparação manual dele descarta E2E visual.

### Q11 — Ordem das fatias (racional: F.0 destrava tudo e grava steps reais p/ fixtures do protótipo;
F.4 antes de F.5 para a fila de review linkar um Issue detail existente — o revisor vê o output antes
de decidir). Ver §C.

---

## B) Diffs de docs a aplicar (na sessão de implementação)

### NOVO ADR-0013 — Arquitetura do frontend (board do operador)
> **Contexto:** primeiro frontend real; modelo de referência = UI do Paperclip (task manager estilo
> Linear com agentes como assignees; análise `analisys-paperclip/10-ui-frontend.md` §8); diferencial #4
> = ver agentes trabalhando + steps. **Decisão:** Next 16 App Router com tenancy por path
> `/[companySlug]` (layout carrega company + `notFound()` escopado; SEM middleware/rewrite de tenant);
> `CompanyLink`/`useCompanyNav` como ÚNICO caminho de navegação (wrapper fino estilo next-intl);
> TanStack Query v5 como única fonte de estado de servidor (`getQueryClient()` isServer/singleton +
> registro central `queryKeys`); realtime v1 = polling (2s agregado/transcript, staleTime 30s no resto),
> WS invalidation-first adiado; proxy same-origin via rewrites (`/api` → Spring 8080; sessão futura
> first-party); auth v1 = `X-Board-User` num único fetcher; shadcn+Tailwind v4 copy-in com theme OKLCH
> próprio dark-first; protótipo cadillac standalone descartável como spec visual aprovada; piso =
> typecheck+lint+build + Vitest em lib/ + verificação viva Preview MCP.
> **Alternativas rejeitadas:** middleware/rewrite de tenant (last resort no Next 16; quebra prefetch);
> WS no v1 (custo alto, valor só multi-usuário); tokens NutriApp (console técnico, primitivas densas
> caras à mão); protótipo dentro do app (poluiria a aplicação real — decisão do usuário); login real
> (uso pessoal local); RTL/e2e exaustivo (protótipo-como-spec + comparação manual).
> **Consequências:** trocar polling→WS não toca telas; trocar header→cookie toca 1 arquivo; toda query
> nova DEVE registrar em queryKeys; toda navegação DEVE usar CompanyLink.

### CONTEXT.md (glossário — adicionar)
- **Mission Control** — a home da company: grid de cards ao vivo (1 por agente: status, Issue,
  mini-terminal do StepTrace, tempo) + feed de atividade. A materialização v1 do diferencial #4.
  *Timeline Gantt por agente = evolução futura.*
- **Protótipo-como-spec** — protótipo cadillac HTML standalone (em `prototipos/`, descartável) aprovado
  pelo operador vira a spec visual congelada das fatias de implementação; a aplicação real não recebe
  código de protótipo.
- **CompanyLink** (frontend) — o único caminho de navegação; auto-prefixa o `companySlug` ativo.
  *Evitar:* importar `next/link` direto em páginas.
- **F.0 / endpoints de observação** — leituras board-only que servem o Mission Control:
  `mission-control` (agregado), `runs/{id}/steps?offset=`, `issues/{id}/runs`, `activity`.

### ARQUITETURA.md (nota curta na seção do diferencial #4)
- "Timeline por funcionário" v1 materializa como **Mission Control (cards ao vivo)**; timeline
  horizontal = evolução. UI: polling v1, WS adiado.

---

## C) Mudanças ORDENADAS (fatias)

| # | Fatia | Conteúdo | Fecha com |
|---|---|---|---|
| **F.0** | Backend-irmã (Java) | 4 endpoints read-only board-only (§A-Q3): `MissionControlController` (agregado; join no servidor), `steps?offset=` (incremental), `issues/{id}/runs` (query nova company-led `findByIssueIdAndCompanyIdOrderByCreatedAtDesc`), `ActivityController` (`limit` clampado). DTOs sem PII além do stdout do próprio run. | TDD (testes ANTES; ~5-7 novos), piso 98 não regride, PG real via docker compose. Sem revisão adversarial (read-only). |
| **P.1** | Protótipo cadillac | 5 superfícies navegáveis em `prototipos/` (HTML/JSX standalone, theme OKLCH próprio embutido, movimento vanilla 1:1); fixtures com steps REAIS gravados na F.0; **as 4 skills de design juntas**; iteração no Preview MCP. | **Aprovação explícita do usuário** → spec congelada. |
| **F.1** | Fundação frontend | Tailwind v4 + shadcn init + theme portado do protótipo + pré-paint anti-flash; `providers.tsx` (`getQueryClient()`); rewrite proxy same-origin; fetcher único (X-Board-User, erros `{error,message}`→ApiError); `queryKeys`; `CompanyLink`/`useCompanyNav`; `app/[companySlug]/layout.tsx` (company por slug + `notFound()` + HydrationBoundary) + `not-found.tsx` + `loading.tsx`; `/` → última company (client-side). | typecheck/lint/build + Vitest (fetcher, queryKeys). |
| **F.2** | Shell | Sidebar + company switcher + navegação das 5 superfícies (rotas placeholder). | piso + verificação viva. |
| **F.3** | **Mission Control** | Cards vivos (poll 2s, pausa aba oculta) + mini-terminal + feed activity + drill-down transcript (componente compartilhado com F.4). | piso + **revisão adversarial 3-lentes** + validação viva com agente REAL rodando. |
| **F.4** | Issues | Lista por status (hover-intent prefetch) + detail: props, risk badge, ledger de runs, StepTrace, ações unblock REOPEN/CANCEL. | piso + **revisão adversarial**. |
| **F.5** | Fila de Review | Tabela PENDING (+filtro status) + decisão APPROVE/REJECT com **âncora `reviewId`** e reason (dialog); link p/ Issue detail/output. | piso + verificação viva. |
| **F.6** | Agents | Roster + detail (runs, skills selecionadas leitura, mint de API key). | piso + verificação viva. |

Ao fim de CADA fatia: atualizar a memória `agente-orquestrator-stack.md`. NÃO commitar (repo sem git).

---

## D) Limites do v1 (a lembrar)
- **Sem WS** (polling; desenho invalidation-first do Paperclip reservado). **Sem login** (X-Board-User;
  1 fetcher). **Sem Costs/budget UI** (assinaturas, ledger vazio — hard-stop segue no backend).
- **Sem** command palette, inbox, org chart, routines, goals/projects, skills/KnowAll UI, i18n,
  onboarding. **Desktop-first** (mobile não é requisito do uso pessoal).
- Mission Control = cards (Gantt futuro). Steps = stdout linha-a-linha (parser rico por adapter tipo
  Paperclip `transcriptPresentation` = evolução; hoje `type`+`detail` bastam).
- E2E/RTL adiados (protótipo-como-spec + comparação manual do operador).
- Latente aceito herdado da R.2 (backend): crash settle→finishSuccess pode deixar review PENDING
  apontando run FAILED — a UI da fila deve tolerar (mostrar status do run, não assumir SUCCEEDED).
