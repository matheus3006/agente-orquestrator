# ADR-0013: Arquitetura do frontend (board do operador)

**Status:** Aceito (2026-07-02, sessão grilling `docs/handoff-grilling-frontend-fechado.md`; implementação iniciada na F.1)

## Contexto
Primeiro frontend real do produto. Modelo de referência = UI do Paperclip (task manager estilo Linear com
agentes como assignees; análise em `analisys-paperclip/10-ui-frontend.md`, rebuild guide §8). O diferencial #4
(ver agentes trabalhando + steps ao vivo) é o centro da experiência. v1 = 5 superfícies: shell, **Mission
Control** (home da company), Issues (lista + detail), fila de Review, Agents. Uso solo, desktop-first, PC
pessoal.

## Decisão
- **Next 16 App Router com tenancy por path `/[companySlug]`**: o layout do segmento carrega a company pelo
  slug (fetch server-side) e chama `notFound()` escopado (`not-found.tsx` no segmento) — padrão Dub. **SEM
  middleware/rewrite de tenant** (Next 16 posiciona proxy como last resort; rewrite de tenant quebra o
  prefetch do `Link`). `loading.tsx` no segmento (habilita prefetch parcial de rota dinâmica).
- **`CompanyLink`/`useCompanyNav` como ÚNICO caminho de navegação**: wrapper fino `'use client'` sobre
  `next/link` (lê o slug via `useParams`, prepende ao href, spread do resto) — padrão next-intl. **Regra:
  páginas NUNCA importam `next/link`/`useRouter` direto** (o shim é load-bearing; lição do Paperclip). O
  wrapper não reimplementa prefetch. Exceções explícitas: raiz `/` e `not-found` (fora de company).
- **TanStack Query v5 como única fonte de estado de servidor**: `getQueryClient()` padrão oficial (client
  novo por request no servidor via `isServer`; singleton no browser — NUNCA `QueryClient` em escopo de
  módulo: vaza entre requests) em `providers.tsx`; company do layout prefetched server-side +
  `dehydrate`/`HydrationBoundary`; telas de polling = client components puros (`useQuery` com
  `refetchInterval`). **Registro central `queryKeys`** parametrizado por companyId — toda query e invalidação
  passam por ele; prefixo `["company", id]` invalida o escopo inteiro.
- **Realtime v1 = polling**: Mission Control 2s no agregado (`refetchIntervalInBackground: false` — pausa em
  aba oculta); transcript aberto 2s incremental (`steps?offset=`) só com run ativo; resto `staleTime` 30s +
  refetch on focus + invalidação pós-mutação. **WS adiado** (invalidation-first, desenho do Paperclip §4.4):
  quando entrar, substitui o polling sem tocar as telas (React Query é a mesma camada; muda quem invalida).
- **Proxy same-origin via `rewrites`**: `/api/:path*` → Spring `:8080` (`BACKEND_ORIGIN`). Cookies futuros
  first-party; sem CORS. Fetch server-side fala com o Spring direto (mesma env).
- **Auth v1 = `X-Board-User` num ÚNICO fetcher** (`src/lib/api.ts`): trocar por sessão/cookie toca 1 arquivo
  e nenhuma tela. O fetcher também é o único tradutor de erro: `{error, message}` do `ApiExceptionHandler` →
  `ApiError{status, error, message}`.
- **Design system: shadcn/ui + Tailwind v4 (copy-in) com theme OKLCH PRÓPRIO dark-first** — "sala de
  máquinas": carvão azul profundo + verde-fósforo (= trabalho acontecendo), âmbar (fila/review), coral
  (bloqueado/falha); Chakra Petch (display) + IBM Plex Sans (UI) + IBM Plex Mono (terminal/dados).
  Anti-flash: no v1 dark-only a classe `dark` ESTÁTICA no `<html>` servido já elimina o flash (React 19
  reclama de script inline re-renderizado e `next/script` inline `beforeInteractive` não executa no App
  Router) — o script pré-paint lendo `orq.theme` entra junto com o light theme. Tokens em `globals.css`
  (`:root` + `@theme inline`) — zero cor fora deles.
- **Protótipo-como-spec**: protótipo cadillac HTML standalone DESCARTÁVEL em `prototipos/` (fora do app),
  com fixtures REAIS gravadas na F.0, aprovado pelo operador em 2026-07-02 → é a spec visual congelada das
  fatias F.2–F.6. A aplicação real não recebe código de protótipo.
- **Piso de verificação**: `tsc --noEmit` + `eslint` + `next build` + **Vitest SÓ nos helpers puros de
  `src/lib`** (fetcher/erros, queryKeys, merge incremental, derivações, formatadores — "logic in tested lib
  helpers, thin components"); telas fecham com verificação VIVA (Preview MCP contra backend real) comparada
  ao protótipo aprovado; revisão adversarial 3-lentes só nas fatias densas (F.3 Mission Control, F.4 Issues).
- **Última company client-side**: gravada no CLIQUE (picker/switcher, `localStorage orq.lastCompanySlug`) e
  lida na raiz `/` para redirect. NÃO gravar server-side em request prefetchável (o prefetch executaria o
  side-effect — armadilha documentada do next-intl).

## Alternativas rejeitadas
- Middleware/rewrite de tenant (last resort no Next 16; quebra prefetch; `middleware.ts`→`proxy.ts` renomeado
  e desnecessário aqui).
- WebSocket no v1 (custo alto; valor aparece com multi-usuário/multi-aba; solo, o polling entrega a mesma
  experiência percebida por ~5% do custo).
- Tokens do NutriApp (console técnico denso pede primitivas prontas; identidade própria).
- Protótipo dentro do app (poluiria a aplicação real — decisão do usuário).
- Login real no v1 (uso pessoal local = desperdício).
- RTL/e2e exaustivos (protótipo-como-spec + comparação manual do operador descartam o e2e visual).

## Consequências
- Trocar polling→WS não toca telas (só quem invalida). Trocar header→cookie toca 1 arquivo.
- Toda query nova DEVE registrar em `queryKeys`; toda navegação DEVE usar `CompanyLink`/`useCompanyNav`.
- Slug inválido nunca renderiza company errada (404 escopado no segmento).
- O theme vive nos tokens de `globals.css`; nenhuma tela usa cor fora deles.

## Nota de reconciliação (2026-07-14 — fecho documental; divergência MÉDIA da auditoria doc×código de 2026-07-11)

**O `notFound()` do LAYOUT não resolve no not-found do segmento** — pela semântica do App
Router, `notFound()` lançado num layout sobe ao not-found do segmento PAI (a raiz, aqui). O
que o `not-found.tsx` do segmento `[companySlug]` realmente cobre são as PÁGINAS internas
(issue/agent inexistente); o slug inválido em si renderiza o not-found da RAIZ. O próprio
código já documenta isso corretamente no comentário (`frontend/src/app/[companySlug]/
not-found.tsx:3-6`) — o drift é só do texto desta ADR ("chama `notFound()` escopado"). A
garantia que importa fica de pé: **slug inválido nunca renderiza company errada** (404 em
qualquer dos dois níveis). Corpo original intocado.

## Nota de reconciliação (2026-07-19 — bug front-500, achado da conferência R31)

**O `fetchQuery` do `[companySlug]/layout.tsx` ganhou try/catch** — antes, uma falha de REDE
(backend fora do ar; `fetch()` rejeita, não é um status HTTP) subia incapturada e virava 500
para QUALQUER caminho de um segmento, mesmo os que nunca existiriam independente do backend
(confirmado ao vivo: caminhos multi-segmento sem página correspondente já resolviam 404 direto
pela árvore estática do Next, sem invocar o layout — não precisaram de conserto). Para o
caminho de um segmento (`companySlug` sempre casa estruturalmente; só o backend sabe se é uma
company real), quando o fetch em si falha o layout não chama `notFound()` (mentiria: o slug
podia ser válido) nem deixa a exceção subir pro `error.tsx` (500) — renderiza inline o mesmo
estado "sala de máquinas offline" (`BoardOffline`, compartilhado com `error.tsx`), como um
render normal, HTTP 200. Decisão consciente de manter 200 em vez de buscar um 503: o App
Router só expõe status customizado por `notFound()`/`forbidden()`/`unauthorized()` a partir de
um Server Component — 503 exigiria `middleware.ts` novo, custo não justificado para uma
ferramenta de uso solo (ver Contexto) onde o que importa é a tela, não o código HTTP externo.
Mesmo padrão de "engolir erro, mostrar o próprio estado de falha" que `moldes/layout.tsx` e a
raiz `page.tsx` já usavam — a diferença é que agora `[companySlug]/layout.tsx` também segue
essa linha em vez de deixar a exceção estourar. Handoff: `HANDOFF-front-500-notfound-offline-
KICKOFF.md` (kickoff) e o PR desta correção (evidência ao vivo, curl -i, vivo×morto).
