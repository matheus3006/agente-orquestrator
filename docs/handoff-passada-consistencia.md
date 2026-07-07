> ⚠️ **SUPERSEDED (2026-07-04):** handoff histórico, já consumido (números de piso da época: 152/133).
> A entrada atual é `docs/handoff-teams-estado-e-reconciliacao.md` (piso 166/166 · 139/139 · V14).

# Handoff — Passada de consistência das 5 superfícies do board (pós-F.7)

> **Entrada da próxima sessão.** F.7 "Motores" está **COMPLETA e verificada** (backend 152/152 + frontend
> 133/133, Postgres real, revisão adversarial aplicada, jar atual em :8080). Esta é a frente **reservada desde
> o grilling do frontend** ("consistência depois evita varrer as telas duas vezes"): uma **varredura única de
> coerência/polish** das 5 superfícies do board agora que TUDO existe — **NÃO é feature nova**. Destilado num
> boundary limpo (regra de ouro ADR-0015). Fonte de verdade do histórico: memória `agente-orquestrator-stack.md`
> (entradas F.2→F.7). Spec visual congelada = protótipo P.1 (`prototipos/`, aprovado).

## 0. Estado (fatos aterrados — não re-derivar)
- **Piso:** backend **152/152** offline (`cd backend && mvn -o test`); frontend **133/133**
  (`cd frontend && npm run typecheck && npm run lint && npx vitest run`) + `npm run build` verde. PG em **V13**.
- **As 5 superfícies do board v1 estão DONE:** shell+company-switcher (F.2), **Mission Control** (F.3, o
  diferencial #4), Issues lista+detail (F.4), fila de **Review** (F.5), **Agents** roster+detail (F.6) — mais o
  **Motor por agente** (F.7-G): dropdown no hire, editável no detail (PATCH), label do motor no roster/runtime.
- **Ambiente vivo:** `db` up (Postgres 16, V13, volume `pgdata` intacto); **jar ATUAL em :8080** (todo o F.7 +
  fix do budget-zero-limit; boot valida 13 migrations + `ddl-validate` PASS). **Preview parado.**
- **Dados demo:** board dev `b0a7d000-0000-4000-8000-000000000001`; company `demo-14262` (Demo Fixtures —
  agentes **Atlas**/**Nix**/**Echo** process + **Rex** claude-code/opus/ultracode = **motor heterogêneo**) e
  `playground-lab` (agente **Vega**). Companies throwaway de validação: `engtest-*`, `budsmoke-*` (inócuas).
- **Subir/rebuild:** db já up. Rebuild da jar (só se mexer no backend): `cd backend && mvn -q clean package
  -DskipTests` (precisa de rede) → `pkill -f orquestrador-0.0.1-SNAPSHOT.jar` →
  `java -jar backend/target/orquestrador-0.0.1-SNAPSHOT.jar`. Preview: `preview_start frontend` (:3000, proxy
  `/api`→:8080).

## 1. O que a passada É — e o que NÃO é
- **É:** uma varredura **cross-surface** de coerência: navegação cruzada, empty/loading/error states, console
  limpo, consistência visual/interação com a spec P.1, e a integração do **Motor (F.7)** com o resto. Corrige
  **inconsistências pequenas**.
- **NÃO é:** feature nova; nem re-design (P.1 congelada); nem mexer no backend (salvo um ajuste MÍNIMO de shape
  se uma inconsistência exigir — como foi o `description` da IssueResponse na F.4).
- **Por quê agora:** o grilling adiou a consistência p/ **depois** das 5 superfícies + F.7, pra varrer as telas
  **uma vez só**. É a última costura do board v1.

## 2. Checklist ORDENADO por DIMENSÃO (varre as 5 telas de uma vez, não tela-a-tela)

### A. Navegação cruzada
- Toda navegação por `CompanyLink`/`useCompanyNav` — **zero `next/link`/`useRouter` cru** fora das 3 exceções
  marcadas (raiz, not-founds, company-switcher). `grep -rn "next/link\|useRouter" src` e conferir cada hit.
- Ativo da sidebar bate com a superfície-mãe nas telas de detail; breadcrumbs (agents›nome, issues›título) e
  "back" coerentes.
- Switcher entre companies re-prefixa TODOS os links, grava `orq.lastCompanySlug`, navega pra raiz da company; `/`
  redireciona pra última.
- **Deep-link direto** (colar URL de detail) funciona; slug inválido → 404 da raiz (sem sidebar); recurso
  inexistente (issue/agent/review) → not-found da company (COM sidebar), gated por `isFetchedAfterMount`.
- Hover-intent prefetch (roster, issues) não dispara erro.

### B. Empty states (enumerar e conferir CADA um)
- Roster vazio; issues vazias (por grupo — grupos vazios somem); fila de review vazia (PENDING/APPROVED/REJECTED);
  runs recentes vazios; skillset vazio; activity feed vazio; Mission Control sem agentes; skills-count "…".
- Consistência de copy + estilo (`.empty-state`) + tom ("sala de máquinas"); **nenhum empty state MENTE** —
  atenção ao `null` ("— livre") vs `undefined` ("—") no assignee chip (padrão travado F.4/F.5).
- **Company vazia:** criar uma nova (ou usar `playground-lab`) e varrer as 5 superfícies — todos os empty states
  corretos, sem erro.

### C. Loading / error branches (paridade entre superfícies)
- Cada query tem **isPending** (skeleton) E **isError** (mensagem coral + "recarregue"). Conferir paridade — a
  F.4 estabeleceu o padrão; a F.7 somou o `enginesQuery` (hire/roster/detail).
- `notFound()` gated por `isFetchedAfterMount && isSuccess` em TODOS os detalhes (sem flash de 404).
- **Backend derrubado no meio** (`pkill` a jar) → mensagens de erro coerentes, sem tela branca; religar → recupera.

### D. Console limpo (todas as superfícies + overlays)
- **Zero** erros/warnings React: keys, hydration, `aria-hidden` com foco dentro (→ `inert`), controlled inputs,
  `getServerSnapshot` estável (useSyncExternalStore), `<script>` pré-paint (removido na F.1).
- Varrer com `preview_console_logs` em: as 5 telas + os dialogs (hire, new-issue, unblock, decision, **dropdown
  Motor**) + o **drawer de StepTrace** (modo vivo e congelado).

### E. Consistência visual/interação (spec P.1 = régua)
- **Status words + LEDs** (running/queued/blocked/idle) idênticos entre Mission Control, roster e detail.
- **Motor (F.7):** label curado igual no roster e no runtime do detail; dropdown, LED fósforo e nota
  "**sem isolamento**" (âmbar) coerentes; o **Rex** (motor Claude) renderiza certo em MC card / roster / detail.
- Movimento ≤300ms ease-out, stagger, `prefers-reduced-motion` — conferir que as adições da F.7 respeitam.
- **Zero cor fora dos tokens** do `globals.css` (regra travada): `grep -rn "#[0-9a-fA-F]\{3,6\}\|oklch(" src`
  fora do `globals.css` deve vir vazio (ou só tokens). Mono/tabular em números/IDs/timestamps (ctx window,
  elapsed, shortId, âncora reviewId).

### F. Integração F.7 no todo
- Agente de motor Claude/GPT coerente em TODAS as telas. (Rex **não RODA** sem login de CLI real — o motor prova
  argv/parsing/persistência, não a execução de um plano não-logado; ver §4.)
- `/engines` cacheado por-company (staleTime 5min) — sem refetch em loop; invalida no `companyScope`.

## 3. Rito (fatia LEVE)
- **Por dimensão (A→F):** correção → **piso** frontend (typecheck+lint+vitest+build) a cada mudança →
  **verificação viva** no Preview MCP contra o backend real (5 superfícies + dialogs + drawer).
- **Sem revisão adversarial** — o grilling reservou adversarial só p/ F.3/F.4; consistência é varredura +
  comparação manual com a P.1. Helper novo (se houver) entra com Vitest em `lib/`.
- **Atualizar a memória** `agente-orquestrator-stack.md` ao fim. **NÃO commitar** (repo sem git).
- **Gotchas do Preview MCP (acumulados):** Radix trigger abre por **toggle** (click → `preview_screenshot` que
  foca a aba → `preview_eval` `el.click()` no item); `rAF`-eval **trava** com aba oculta; depois de abrir 2ª aba
  de preview a do front fica `document.hidden` permanente (fechar as outras com `preview_stop` ANTES; ou validar
  por network/reload).

## 4. Latentes v1 ACEITOS (NÃO são alvos da passada — já documentados)
- Activity feed com **janela finita** (limit 50 compartilhado) — endpoint por-entidade = futuro.
- Crash entre settle-commit e finishSuccess deixa review PENDING → run FAILED (pré-existente, análogo DONE).
- **Agentes de motor Claude/GPT não RODAM sem login de CLI real** no host (v1 LOCAL herda o login do host; o
  `claude`/`codex` só respondem se logados). O motor está provado no argv/parser/persistência; a execução real
  de um plano depende de o CLI estar logado na máquina — fora do escopo da consistência.
- v1 **NÃO isola** (LOCAL); Docker/isolamento = F.8/ADR-0016 (fatia futura). Rate-limits de plano não geridos.
- `re-anexo concorrente` 409 e `max(version)+1` — board-gated solo, aceitos.

## 5. Como COMEÇAR a próxima sessão
1. **Ler** este handoff + a memória `agente-orquestrator-stack.md` (F.2→F.7). Não re-derivar o que já está feito.
2. **Subir** o ambiente: db já up; jar em :8080 já atual; `preview_start frontend`. Fechar preview antigo se
   houver.
3. **Varrer por DIMENSÃO** (A→F da §2), corrigindo inconsistências pequenas, com **piso + viva a cada passo**.
   Começar por **D (console limpo)** e **B (empty states)** — os que mais rendem achados baratos — depois A/C/E/F.
4. **Fechar** com a memória. Esta é a **última costura do board v1**; F.7 já vinha antes dela.
