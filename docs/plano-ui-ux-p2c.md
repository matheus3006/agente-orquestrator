# Plano de execução — UI/UX P2-C (#11 vocabulário/onboarding · #12 estado ocioso)

> **Data:** 2026-07-06 · **Tipo:** plano fechado de execução (não é código). Fruto de uma sessão
> de grilling (skills `/grilling` `/design-audit` `/codebase-design` `/human-architect-mindset`),
> com 6 verificadores adversariais de código (lentes de correctness do `/code-review`, read-only) e
> uma tentativa de `/deep-research` (30 agentes teto: ~24 no research + 6 na verificação local).
> **Precede:** as fases P0, P1, P2-A, P2-B — todas FECHADAS e verificadas VENDO (test 207/207).
> **Cobre:** os dois únicos pontos ADITIVOS que restam do levantamento (`docs/levantamento-ui-ux.md`).
> **Fonte da verdade do diagnóstico:** `docs/levantamento-ui-ux.md`, pontos #11 e #12 + §3.

> ## ✅ FASE UI/UX FECHADA — 2026-07-06
> **Os 5 pontos de veto humano foram decididos pelo Matheus: MANTER em todos.** Nenhuma alteração
> de código nesta sessão de fechamento (accept-all).
> - **E1** (guard de Esc em `board-shell.tsx`, §5) → **MANTER** — de-acordo explícito dado; era o
>   único ponto que tocou o P0 e fica aprovado.
> - **E2** (rótulo do entry do glossário na sidebar, §5) → **MANTER "Glossário"** — não vira "Como
>   funciona" nem renomeia a seção "Catálogo".
> - **E0** (copy do ocioso com `PENDING_REVIEW`, D-12-04-b/§5) → **MANTER** — aponta a pendência
>   ("há review aguardando você — responda na conversa"), não "dê nova direção".
> - **FOLEGO_MS = 8000** (fôlego anti-flicker, M-12-B) → **MANTER 8000**.
> - **Hint "(designe pelo menu ⋮)"** removido do chip travado do `team-toolbar` (Fatia 2) →
>   **MANTER removido** (a dica segue no NovoTimeDialog/toast/menu ⋮).
>
> **Gates reconfirmados AO VIVO no fechamento:** `lint 0 · typecheck 0 · test 242/242` (20 arquivos).
> Âncoras conferidos no disco: E1 em `board-shell.tsx:44`, E0 em `page.tsx:150`, FOLEGO em
> `page.tsx:44`, E2 em `glossario-dialog.tsx`, hint ausente do `team-toolbar.tsx`. Backend `:8080`
> intocado. Repo sem git — nada commitado. **A volta ao cronograma pausado é outra fase (Parte B).**

---

## 1. Constituição (gates verificáveis — o plano NÃO pode violar)

Todo item abaixo é um gate: a fatia que o violar não fecha.

| # | Gate | Como se verifica |
|---|------|------------------|
| C1 | **Identidade "sala de máquinas" preservada.** Todo elemento aditivo passa pelo filtro de redução: justifica a existência ou não nasce. | VENDO: nenhum elemento novo compete com os cards vivos / verde-fósforo; nada de moldura genérica de SaaS. |
| C2 | **Zero mudança de backend/dados.** Estado de onboarding/ocioso é **client-side** e **derivado** dos dados que o board já tem (precedência: lente em localStorage, `last-company.ts`). **Nenhum endpoint novo.** | Diff toca só `frontend/src/**`; zero chamada a rota inexistente; backend `:8080` intocado e UP. |
| C3 | **Zero `oklch(` cru fora do `:root`.** Guard `src/lib/globals-tokens.test.ts`. Exceção herdada: `oklch(0 0 0 / α)` (preto de scrim/sombra) permanece cru. | `test` verde (o guard falha se violado). |
| C4 | **Raio pela escala `--r-sm/md/lg` (4/8/12).** Sem número de raio novo cru. | Census VENDO / grep no diff. |
| C5 | **Mono com piso 11px.** Texto de explicação (glossário/tooltip) é **prosa sans**, não label mono. | Inspeção de `font-family`/`font-size` no DOM. |
| C6 | **Um movimento perpétuo por tela.** A afordância de ocioso entra com **fade único** (evento), nunca um loop. `prefers-reduced-motion` zera. | VENDO + `animation-iteration-count`. |
| C7 | **Copy PT no tom do produto.** Direta, sem tutorial, sem exclamação de marketing. | Revisão de string. |
| C8 | **P0/P1/P2-A/P2-B intocáveis.** Uma única exceção declarada e aprovável: o guard de Esc em `board-shell.tsx` (ver §5, E1) que **adiciona** segurança de composição sem alterar o comportamento P0. | Regression VENDO do shell (drawer/inert/Esc/rail) + revisão do gate C8 no review humano. |
| C9 | **Repo sem git — não commitar.** Gates internos de `frontend/`: `lint 0 · typecheck 0 · test`. | Rodar os três; nada versionado. |

---

## 2. Evidência externa (⚠ NÃO verificada — falha de infraestrutura)

O `/deep-research` foi executado mas **abortou por falta de créditos de uso** no meio: os 25 painéis
de verificação adversarial erraram (100%), então **nenhuma alegação foi confirmada**. O que se
salvou foram as *alegações extraídas* das fontes, **sem verificação de fidelidade**. Registro-as
honestamente como corroboração fraca — dirigem, não decidem. Todas apontam no mesmo sentido das
decisões abaixo:

| Decisão | Alegação extraída (⚠ não verificada) | Fonte alegada |
|---|---|---|
| D-12-02 (uma ação canônica) | Carbon (IBM): empty state não deve apresentar múltiplas opções — escolha a ação mais importante e mantenha o foco nela. | carbondesignsystem.com/patterns/empty-states-pattern |
| D-12-02 | NN/g: 3ª diretriz de empty state = oferecer um caminho direto (link/instrução) para popular o estado. | nngroup.com/articles/empty-state-interface-design |
| D-12-04 (uma primária por vez) | Carbon: evitar múltiplos botões primários visíveis ao mesmo tempo; demote todos menos um. | carbondesignsystem.com/patterns/empty-states-pattern |
| D-11-02 (sem tour) | NN/g: ajuda em contexto é mais memorável que onboarding longo — o usuário aplica na hora. | nngroup.com/articles/empty-state-interface-design |
| D-11-02 | Carbon: tours de onboarding são suplemento opcional — nunca substituem o empty state contextual; a base tem que funcionar sem tour. | carbondesignsystem.com/patterns/empty-states-pattern |
| D-12-05 (fôlego/hysteresis) | "Hysteresis" é conceito nomeado de UI (emprestado da física): estado que atrasa de propósito para evitar oscilação/flicker entre estados. | en.wikipedia.org/wiki/Hysteresis |
| D-12-05 | React (Suspense experimental): padrão anti-flicker codificado em `busyDelayMs`/`busyMinDurationMs`. TanStack Query: flicker entre loading/success é defeito reconhecido (`keepPreviousData`). | react docs / tanstack.com |

**Ação:** reexecutar o `/deep-research` quando os créditos voltarem, para elevar isto de
"corroboração fraca" a "verificado". **Não bloqueia** o P2-C — a verificação de viabilidade que
importa (o código real) foi feita pelos 6 verificadores locais (§5).

---

## 3. Tabela de decisões

Legenda de dono: **H** = decisão humana (arrancada no grill, racional do Matheus) · **A** =
proposta-do-agente (mecanismo; veto/aceite do humano).

| ID | Decisão | Racional (arrancado) | Alternativa rejeitada | Dono |
|----|---------|----------------------|-----------------------|------|
| **D-12-01** | Persona primária do board ocioso = **dono-operador**; UMA linha didática ensina o loop "de carona" (a frase ensina, a ação é curta). | Hoje o produto tem 1 usuário real (o dono); cliente/multi-usuário não existe no board. A frase de 1 linha atende o dev avaliando sem virar tutorial. | (a) só o dono, sem didática → dev avaliando fica perdido; (b) estado didático permanente para avaliador → copy pesada numa tela já densa (#2). | H |
| **D-12-02** | O próximo passo canônico = **"fale com o CEO"** (foco no composer, que já está na mesma tela). | O composer do CEO é "o ÚNICO lugar onde o board escreve" (`ceo-conversa.tsx:359`); a zona de times é somente-leitura de propósito. A tese do produto É dirigir pela conversa. Apontar para outro lugar contradiz a tese; e fica na mesma tela (zero navegação). | (a) "abrir issue direto" (/issues → NewIssueDialog) → pula o CEO, ensina o loop errado, troca de tela; (b) ação dependente de contexto → mais regras (a ramificação entra mesmo assim na D-12-04). | H |
| **D-12-03** | First-use e ocioso-experiente = **mesmo componente, 2 copies distintas** (não 2 layouts). | Distinguir é de graça (a lista de issues já está na página; `isTerminalIssueStatus` já existe). "Dê a primeira direção" soa errado com 40 issues entregues; "tudo entregue" é até um momento de valor. | (a) 1 copy única → perde o momento "tudo entregue" e soa redundante para quem já usou 100×; (b) 2 estados visuais → 2 layouts para manter, o cheio esconde os cards úteis dos times. | H |
| **D-11-01** | Ganham explicação os **8 termos**: molde, motor, carta, grill, ancorada, arquiteto, dispatch livre/travado, KnowAll. | Cobertura completa do vocabulário inventado — do loop principal (Moldes→contratar→conversar→aprovar) ao power-user (organograma/dispatch) e ao mais profundo (KnowAll). | Escopo menor (só os 5 do levantamento) → deixaria arquiteto/dispatch/KnowAll opacos. | H |
| **D-11-02** | Mecanismo de explicação = **tooltips contextuais + glossário reencontrável**, **SEM overlay de 1º acesso**. | O job do overlay (ensinar o loop) já é do #12 (D-12-01/02). Sobra ao #11 só explicar TERMOS — e termo se explica no lugar onde aparece (tooltip) + num lugar reencontrável (glossário). Overlay é o elemento que o filtro de redução (C1) cortaria: intrusivo, genérico, e exigiria estado "já vi" (some em browser novo, inclusive para o dono). | (a) só tooltips → "o que era grill mesmo?" obriga caçar o termo; touch sem hover; (b) overlay + tooltips + glossário → repete o ensino do #12, adiciona o elemento mais intrusivo e a decisão extra do "já vi". | H |
| **D-12-04** | "Ocioso" = `running 0 && queued 0 && blocked 0` **E CEO presente**. BLOCKED>0 **exclui** (o próximo passo já é outro). Copy "tudo entregue" só se todas as issues são terminais (`isTerminalIssueStatus`); senão, neutra. | Com algo BLOCKED o próximo passo canônico NÃO é dar direção nova, é resolver o bloqueio — duas chamadas simultâneas violam "um próximo passo". Tudo derivável do que a página já tem (poll 2s + roster + issues). | (a) "sem runs = ocioso" (blocked não impede) → duas primárias competindo; (b) só primeiro-uso → abandona o caso "segunda de manhã". | H |
| **D-12-04-b** | **Extensão** (consequência da própria D-12-04, confirmada pelo verificador M-12-A): `PENDING_REVIEW` conta como "estado que espera o usuário" — mesma classe do BLOCKED. A copy do estado ocioso **não** implica inércia quando há review pendente; aponta a pendência. | `PENDING_REVIEW` deixa agentes idle com trabalho ENTREGUE esperando o operador. "Dê a próxima direção" apontaria para o lado errado, pela mesmíssima regra que excluiu BLOCKED. É a aplicação fiel da D-12-04, não decisão nova — **mas registrada para veto no review humano.** | Ignorar PENDING_REVIEW → o board diz "dê direção" enquanto na verdade espera você aprovar. | H (extensão) — **confirmar no review** |
| **D-12-05** | Afordância **some no ato** ao deixar de ser ocioso; **entra com fôlego** (anti-flicker) ao ficar ocioso. | Some na hora porque o próprio trabalho rodando (LED/terminal) vira o guia — a identidade do produto. Entra com fôlego para não pipocar entre runs encadeados (o inquieto que o P2-A/#9 matou). | (a) simétrico instantâneo → pisca 2s entre runs encadeados; (b) fôlego longo (minutos) → demora a ensinar o loop numa demo/1º uso. | H (duração exata = mecânica, ver M-12-B) |
| **D-00-01** | **3 fatias atômicas independentes**, nesta ordem: (1) #12 ocioso → (2) #11a tooltip+termos → (3) #11b glossário. | #12 primeiro: menor e de maior valor/frequência, ancora no `EmptyState` pronto. Tooltip antes do glossário: o glossário REUSA as mesmas frases (fatia 3 vira quase só composição). Cada fatia verificável VENDO isolada (padrão P2-A/B). | (a) 2 fatias (#11 inteiro junto) → toca ~8 arquivos + primitivo + entry de uma vez, estoura o orçamento de contexto (regra 50-60%); (b) só #12 agora → desperdiça o grill já feito do #11. | H |

---

## 4. Propostas de mecanismo (dono = A; racional + alternativa rejeitada + veredito do verificador)

Cada uma foi lida e atacada por um verificador adversarial que leu o código real. Veredito de todos:
**CONFIRMA-COM-AJUSTES** — nenhum mecanismo refutado; os ajustes estão embutidos abaixo.

### M-12-A — Detecção do estado ocioso (função pura, seam em `mission-control.ts`)
- **Proposta:** módulo profundo `estadoOcioso(agents, ceoNome, issues) → EstadoOcioso | null` em
  `src/lib/mission-control.ts` (interface pequena, deriva 4 estados). `EstadoOcioso =
  "primeiro-uso" | "entregue" | "quieto"`; `null` = não-ocioso ou dados incompletos.
- **Assinatura (ajuste M-12-A #1):** recebe `agents: MissionControlAgent[] | undefined` e
  `issues: IssueSummary[] | undefined` (NÃO `tally`), retornando `null` se `!agents || !issues` —
  isso impede o call-site de lavar o `undefined` com `?? []` e classificar "primeiro-uso" FALSO
  para company com 40 issues ainda em voo (race real em `page.tsx:73-74`).
- **Semântica:** `primeiro-uso` = ocioso ∧ zero issues; `entregue` = ocioso ∧ todas terminais
  (`isTerminalIssueStatus`, `lib/issues.ts:48-50`); `quieto` = ocioso ∧ há issue não-terminal.
  Por D-12-04-b, `quieto` cuja causa é `PENDING_REVIEW` recebe copy que aponta a pendência.
- **Seam de frescor (ajuste M-12-A #2):** a lista de issues não repolla (staleTime só governa
  refetch em focus/mount) → "entregue" nunca chegaria e "quieto" mentiria indefinidamente.
  Espelhar o padrão F.3 já existente: helper `issuesListBehindIdleSettle(agents, issues)` =
  all-idle ∧ alguma issue `IN_PROGRESS` → invalidar `queryKeys.issues` com o **mesmo guard**
  `issuesUpdatedAt < snapshotUpdatedAt` (evita loop; restrito a `IN_PROGRESS` para não refetchar
  em estados estacionários legítimos como `OPEN`/`PENDING_REVIEW`).
- **Guard de erro (ajuste M-12-A #5):** só classificar quando `!snapshotQuery.isError` (o
  react-query mantém `data` stale no erro; sem o guard a afordância apareceria sobre tally velho
  debaixo do banner coral).
- **Alternativa rejeitada:** classificar no componente (inline) → lógica não-testável, dispersa
  pela `page.tsx`; a função pura é o módulo profundo testável pela interface (seam externo).
- **Veredito:** CONFIRMA-COM-AJUSTES (todos embutidos acima).

### M-12-B — Fôlego anti-flicker (client-side puro, sem timer novo)
- **Proposta:** rastrear `lastActiveAt` num `useRef`; "ocioso estável" = `isIdleNow && (nowMs -
  lastActiveAt > FOLEGO_MS)`. **Reusar** o `nowMs` do `useNow(1000)` que a `page.tsx:38` já roda
  (não criar segundo interval). `FOLEGO_MS = 8000` (4 ticks do poll de 2s).
- **Init (ajuste M-12-B #1):** `lastActiveAt = null` = "nunca visto ativo desde o mount" → ocioso
  estável **imediato** quando o board já abre parado (fim de semana). Sem atraso de 8s onde não
  havia flicker a evitar. Só classificar com `snapshotQuery.data !== undefined`.
- **Condição conjuntiva (ajuste M-12-B #2/#5):** atualizar `lastActiveAt` em todo render em que o
  tally esteja ativo **ou** `isError` (tratar erro como tempo não-quieto) — cobre a pausa do poll
  em aba oculta e o blip de erro no meio de cadeia.
- **Fade (ajuste M-12-B #4):** entrada por CSS (`animation`/`transition`), nunca JS-driven —
  `globals.css:3204-3211` já zera duração sob `prefers-reduced-motion`. Zero media query nova (C6).
- **Alternativa rejeitada:** ancorar em `dataUpdatedAt` das queries → inválido: timestampa
  *fetches*, não *transições de atividade* (board ocioso repollado a 2s tem `dataUpdatedAt` sempre
  fresco). O `useRef` com null-init é o correto.
- **Veredito:** CONFIRMA-COM-AJUSTES.

### M-12-C — Renderização da afordância + seam de foco do composer
- **Proposta:** `<EmptyState compact>` (peça pronta do P2-B, `ui/empty-state.tsx`) com `action`
  "falar com o CEO". Copies: `primeiro-uso` = "os times executam o que o CEO abre — dê a primeira
  direção"; `entregue` = "tudo entregue — dê a próxima direção"; `quieto` = "nada rodando — dê a
  próxima direção" (variante review-pendente aponta a pendência, D-12-04-b).
- **Slot de layout (ajuste M-12-C #2):** novo prop-slot `avisoOcioso?: ReactNode` em
  `TimesObservados`, renderizado como **1º filho de `.zona-times-scroll`** (o `children` de hoje é
  o *rodapé* do scroll, `times-observados.tsx:177`). Rola junto (não rouba altura da dobra), não
  toca o `min-width:0` do P0.
- **Seam de foco (ajuste M-12-C #1/#3):** `id="composer-do-ceo"` estável no textarea
  (`ceo-conversa.tsx`) — o repo não usa `forwardRef`; a ação faz `getElementById` →
  `scrollIntoView({block:"center"})` + `focus({preventScroll:true})`. **Caso-título:** com CEO e
  **zero chats**, o textarea está `disabled` (`ceo-conversa.tsx:422`) → focar o gatilho "+" do
  NovoChatDialog nesse caso, não o textarea.
- **Border override (ajuste M-12-C #6):** o `compact` herda `dashed`; para não empilhar 2 caixas
  tracejadas com o "Time sem agentes", passar `className` (o `EmptyState` já repassa) neutralizando
  a borda. Sem componente novo.
- **A11y (ajuste M-12-C #7):** **sem** `aria-live` (região que monta/desmonta com o poll de 2s
  flaparia); gate obrigatório `!isPending && !isError` (evita flash no load/skeleton).
- **Alternativa rejeitada:** EmptyState cheio cobrindo a zona-times → esconde os cards úteis dos
  times (quem existe, última issue de cada um) — o que a D-12-03 rejeitou.
- **Veredito:** CONFIRMA-COM-AJUSTES.

### M-11-A — Primitivo `ui/tooltip.tsx` (Radix)
- **Proposta:** `src/components/ui/tooltip.tsx` sobre `@radix-ui/react-tooltip` (**dep nova**), no
  padrão copy-in shadcn enxuto de `dialog.tsx`/`dropdown-menu.tsx`.
- **Compat (verificado):** react 19.2.4 / next 16.2.9; `@radix-ui/react-tooltip@1.2.11` tem os
  mesmos peers dos radix já instalados e suas transitivas **já estão em node_modules nas versões
  exatas** — dedupe total, footprint ≈ 2 pacotes.
- **Ajuste M-11-A #3 (z-index):** o `z-50` default do shadcn ficaria **atrás** do `.dialog-panel`
  (z-51) e do dropdown (z-55) — e "motor" vive num `DropdownMenuItem` aninhado no hire dialog.
  Usar `z-[56]` no `TooltipContent` (>55, <60 do toast).
- **Ajuste M-11-A #4 (CSS, gates C3/C5):** corpo **sans 12px `text-dim` sobre `--overlay`**, raio
  `--r-md` (8), sombra reusando a exceção `oklch(0 0 0 / 0.45)`. Nunca label mono.
- **Ajuste M-11-A #5/#6:** `delayDuration={300}` (700ms é lento para "o que é este termo"); um
  **único** `TooltipProvider` em `src/app/providers.tsx` (já client) — zero re-render global.
- **Touch (aceito):** Radix Tooltip ignora `pointerType: touch` por design — no mobile o glossário
  (M-11-C) é o canônico reencontrável; o tooltip é progressive enhancement desktop.
- **Alternativa rejeitada:** Popover clicável nos termos → rouba o clique de elementos
  interativos (ex.: "motor" no menu seleciona e fecha).
- **Veredito:** CONFIRMA-COM-AJUSTES.

### M-11-B — Fonte única `lib/glossario.ts` + `<Termo>` **só na prosa**; resto via `title=` nativo
- **Proposta:** `src/lib/glossario.ts` = fonte única (`glossa(id): string`, `TermoId` union de
  compile-time). O glossário-diálogo (M-11-C) consome o MESMO dado.
- **Ajuste M-11-B (o mais importante):** o wrap `<Termo id>` **só nos ~6 sites de texto corrido**
  (hire-dialog prosa/labels, `reviews` sub, prop-keys de motor, KnowAll em label, "grill·rodada"
  em `approval-thread-card.tsx:170`, arquiteto/dispatch em `times-observados.tsx`). Nos demais
  sites — nav (rail esconde o label; Link já tem `title`), `DropdownMenuItem` (motor/arquiteto),
  chip da carta (é `button` controlado), nó React Flow, e "dispatch TRAVADO"/motorLabel que **só
  existem dentro de `title=`** — **consumir `glossa(id)` via `title=` nativo**, que é a **convenção
  já instalada** no produto (`sidebar:112`, `ceo-conversa:575`, `team-toolbar:41-44`). Isso remove
  strings duplicadas em vez de criar superfícies novas.
- **Sublinhado (C1):** não existe convenção `dotted-underline` no CSS. Estrear um pontilhado
  restrito **apenas aos ~6 sites de prosa** (um único estilo em `globals.css`); os sites via
  `title=` não ganham marca visual — respeita o filtro de subtração.
- **Nomes (regra de ouro):** `lib/glossario.ts` segue o padrão (`ceo-conversa.ts`,
  `role-templates.ts`); `<Termo>` aceitável com `id: TermoId`.
- **Alternativa rejeitada:** `<Termo>` universal em todos os 8 termos → inviável em ~5 sites
  (menu item, nó de canvas, título de `ViewHead` que é `string`, chip-button, rail morto) e
  espalharia dotted-underline por chips/prop-keys/nav.
- **Veredito:** CONFIRMA-COM-AJUSTES.

### M-11-C — Glossário como Dialog + entry na sidebar
- **Proposta:** glossário dos 8 termos como **Dialog controlado** (padrão do `CartaDialog`,
  `ceo-conversa.tsx:554-614` — `useState` + `open/onOpenChange`, botão cru, não `DialogTrigger`).
- **Ajuste M-11-C #5 (corpo):** usar `<dl className="max-h-[55vh] overflow-y-auto">` (`.dialog-panel`
  não tem max-height própria), `dt` termo + `dd` no visual de `.dialog-text` — **não** o
  `memoria-corpo` (mono/pre-wrap, errado para prosa). Zero CSS novo. `DialogClose` "Fechar".
- **Ajuste M-11-C #2 (entry):** item na **seção `Catálogo` da sidebar** como `<button
  type="button">` (dialog não navega) **sem** active-bar/`aria-current`; `activeSurfaceId`
  (`lib/shell.ts`) **não** ganha entrada. Ícone `CircleHelp` + `title`. Como a seção chama-se
  "Catálogo" e "Como funciona" não é catálogo, o item leva a **tag `global`** (padrão
  `board-moldes-tag`) — decisão de rótulo marcada para veto (ver §5, E2).
- **Rejeição do "?" no topbar (confirmada):** `.board-topbar` é `display:none` fora de <768px — no
  desktop o topbar **não existe**. A sidebar é o único entry coerente.
- **Alternativa rejeitada:** `DialogTrigger` puro → o `CartaDialog` provou que o padrão do produto
  é dialog controlado; seguir a casa.
- **Veredito:** CONFIRMA-COM-AJUSTES.

---

## 5. Pontos que tocam/estendem decisões suas — **confirmar no review humano**

> **RESOLVIDO (2026-07-06): os 3 pontos abaixo + os 2 defaults herdados (FOLEGO_MS, hint do chip)
> foram decididos pelo Matheus — MANTER em todos. Ver o stamp "FASE UI/UX FECHADA" no topo.**

Nada aqui foi decidido contra você em silêncio; são os 3 pontos onde os verificadores esbarraram
no seu território ou num gate:

- **E0 — D-12-04-b (PENDING_REVIEW):** aplicação fiel da sua regra "estado que espera o usuário
  suprime a nova-direção". Registrado como extensão, não decisão nova. *Se você discordar,* a
  variante `quieto` volta a tratar PENDING_REVIEW como ocioso normal.
- **E1 — exceção ao gate C8 (P0 intocável):** o **único** ponto que toca o P0. Guard de 1 linha em
  `board-shell.tsx` (early-return no handler de Esc se o alvo está dentro de `.dialog-panel`), para
  o glossário aberto de dentro do drawer (<768) não ser duplo-fechado com briga de foco. **Adiciona**
  segurança, não altera o comportamento existente do shell. Precisa do seu de-acordo explícito.
- **E2 — rótulo do entry (conteúdo, C7):** "Como funciona" vs "Glossário" vs renomear a seção
  "Catálogo"→"Geral". Proposta-agente: manter em "Catálogo" com tag `global` e rótulo **"Glossário"**
  (mais honesto que "Como funciona", que prometeria ensinar o loop — job do #12). Veto seu.

---

## 6. Fatias atômicas (task-spec)

### Fatia 1 — #12 Estado ocioso guia o próximo passo
- **Depende de:** nada (independente).
- **Seam(s) declarado(s):**
  - módulo profundo `estadoOcioso` + `issuesListBehindIdleSettle` em `src/lib/mission-control.ts`
    (interface pequena, testável pela interface — sem tocar o call-site para testar);
  - slot `avisoOcioso` (interface nova, mínima) em `src/components/times-observados.tsx`;
  - seam de foco por `id="composer-do-ceo"` em `src/components/ceo-conversa.tsx`.
- **Arquivos de contexto de entrada:** `lib/mission-control.ts`, `lib/issues.ts`, `lib/api.ts`
  (tipos `AgentBoardStatus`/`IssueSummary`/`MissionControlAgent`), `app/[companySlug]/page.tsx`,
  `components/times-observados.tsx`, `components/ceo-conversa.tsx`, `components/ui/empty-state.tsx`,
  `lib/use-now.ts`, `app/globals.css` (fade + border override).
- **Definition of done (VENDO, teams-demo):**
  - **1280px, board todo IDLE, com CEO, alguma issue não-terminal:** aparece `<EmptyState compact>`
    no topo da zona-times com copy "nada rodando — dê a próxima direção" e ação "falar com o CEO";
    clicar rola até e **foca** o textarea do composer (ou o gatilho "+" se não há chat ativo).
  - **Todas as issues terminais (DONE/CANCELLED):** copy vira "tudo entregue — dê a próxima direção".
  - **Zero issues (primeiro-uso):** copy "os times executam o que o CEO abre — dê a primeira direção".
  - **1 agente BLOCKED (resto idle):** afordância **não** aparece. **Issue PENDING_REVIEW (resto
    idle):** copy não implica inércia (aponta a pendência) — D-12-04-b.
  - **Sem CEO:** afordância não aparece (composer já avisa).
  - **Anti-flicker:** disparar 2 runs encadeados (gap ~3s) → a afordância **não pisca** no intervalo;
    ao ficar realmente parado, entra com fade único após ~8s. Board já-parado no load → aparece na
    hora (sem atraso de 8s). `prefers-reduced-motion` → sem animação.
  - **Erro de backend (poll falha):** afordância suprimida; banner coral segue.
  - **375px:** afordância presente e o "falar com o CEO" rola até o composer.
  - **Gates:** `lint 0 · typecheck 0 · test` verde (+ testes de `estadoOcioso`/`issuesListBehindIdleSettle`).

### Fatia 2 — #11a Tooltip contextual nos termos
- **Depende de:** nada (independente; pode rodar antes ou depois da 1).
- **Seam(s) declarado(s):**
  - primitivo `src/components/ui/tooltip.tsx` (adapter fino sobre Radix, no padrão da casa);
  - fonte única `src/lib/glossario.ts` (`glossa(id)`, `TermoId`);
  - componente `src/components/termo.tsx` (`<Termo id>`), usado **só na prosa**.
- **Arquivos de contexto de entrada:** `components/ui/dialog.tsx` e `dropdown-menu.tsx` (padrão),
  `package.json`, `app/providers.tsx`, `app/globals.css` (painel do tooltip + dotted restrito),
  `lib/globals-tokens.test.ts` (guard C3), e os sites de termo mapeados em M-11-B
  (`hire-agent-dialog.tsx`, `reviews/page.tsx`, `agents/[agentId]/page.tsx`, `molde-preset-form.tsx`,
  `approval-thread-card.tsx:170`, `times-observados.tsx`) + os sites `title=`
  (`sidebar.tsx`, `motor-select.tsx`, `tipo-funcao-select.tsx`, `team-canvas.tsx`, `team-toolbar.tsx`,
  `ceo-conversa.tsx:575`).
- **Definition of done (VENDO, teams-demo):**
  - **1280px:** hover em cada um dos ~6 sites de prosa mostra a frase de 1 linha (sans 12px,
    `text-dim`, sobre `--overlay`, raio 8), com dotted-underline sutil **só** nesses sites; delay ~300ms.
  - Tooltip **sobre** o dialog e sobre o dropdown (testar "motor" dentro do hire dialog): não some atrás.
  - Sites não-prosa (nav, menu, chip da carta, dispatch travado) expõem a MESMA frase via `title=`
    nativo, sem string duplicada (a fonte é `glossa(id)`).
  - **375px:** nada quebra (tooltip é no-op no touch; `title` segue).
  - **Gates:** `lint 0 · typecheck 0 · test` verde (guard C3 intacto — **zero** `oklch(` cru novo);
    piso mono 11px intacto (C5).

### Fatia 3 — #11b Glossário reencontrável
- **Depende de:** **Fatia 2** (reusa `lib/glossario.ts` — a mesma fonte de frases).
- **Seam(s) declarado(s):**
  - `src/components/glossario-dialog.tsx` (Dialog controlado, padrão `CartaDialog`);
  - entry `<button>` em `src/components/sidebar.tsx` (seção Catálogo, sem active-state);
  - guard de composição em `src/components/board-shell.tsx` (exceção E1 ao gate C8).
- **Arquivos de contexto de entrada:** `components/ceo-conversa.tsx:554-614` (padrão CartaDialog),
  `components/ui/dialog.tsx`, `components/sidebar.tsx`, `components/board-shell.tsx`, `lib/shell.ts`,
  `lib/glossario.ts`, `app/globals.css` (`.board-nav`, `.board-section-label`, `.dialog-*`).
- **Definition of done (VENDO, teams-demo):**
  - **1280px:** item novo na seção Catálogo da sidebar (tag `global`) abre um dialog com os 8 termos
    (`<dl>` rolável, `dt` termo + `dd` frase), fecha por "Fechar"/Esc; item **não** fica com
    active-bar; a superfície ativa (Mission Control etc.) permanece marcada.
  - **Rail (768-1199):** item icon-only abre o dialog; `title` presente.
  - **Drawer (<768):** abrir o glossário de dentro do drawer e apertar **Esc** fecha **só o dialog**,
    o foco volta ao item (guard E1) — o drawer não é duplo-fechado. Regression do shell (inert/scrim/
    route-change) intacta.
  - **Gates:** `lint 0 · typecheck 0 · test` verde; P0/P1/P2-A/P2-B regression VENDO ok.

---

## 7. Rastreabilidade (levantamento → decisões → fatias — sem órfãos nos dois sentidos)

| Ponto do levantamento | Decisões | Mecanismos | Fatia |
|---|---|---|---|
| **#12** Estado ocioso não guia o próximo passo | D-12-01, D-12-02, D-12-03, D-12-04, D-12-04-b, D-12-05 | M-12-A, M-12-B, M-12-C | **Fatia 1** |
| **#11** Vocabulário/onboarding sem explicação (tooltips) | D-11-01, D-11-02 | M-11-A, M-11-B | **Fatia 2** |
| **#11** Vocabulário/onboarding (glossário reencontrável) | D-11-01, D-11-02 | M-11-B (fonte), M-11-C | **Fatia 3** |
| Corte/ordem/independência | D-00-01 | — | governa as 3 |

Verificação inversa: toda decisão da §3 aparece numa fatia da §6; toda fatia da §6 tem ao menos uma
decisão da §3. Todo mecanismo da §4 rastreia a uma decisão. Sem decisão órfã; sem fatia sem decisão.

---

## 8. Fora do escopo do P2-C (explícito)

- **Overlay/product-tour de 1º acesso** — rejeitado (D-11-02); o ensino do loop é do #12.
- **Estado "já vi" persistido** (localStorage por browser/company para onboarding) — não é
  necessário: tooltip é sob demanda, glossário é reencontrável, afordância de ocioso é derivada do
  estado real. Nenhum flag novo.
- **Qualquer endpoint/backend/dado novo** — gate C2. `PENDING_REVIEW`/terminal vêm do que a página
  já busca.
- **Reabrir P0/P1/P2-A/P2-B** — salvo a exceção declarada E1 (guard de Esc, aprovável).
- **Traduzir/renomear os termos do produto** (molde, motor, carta…) — só explicá-los; o vocabulário
  autoral é ativo (C1).
- **Retomar o cronograma pausado** — o P2-C fecha a fase de UI/UX; a volta ao cronograma é outra sessão.
- **`ViewHead title` como site de tooltip** — é `string`, não `ReactNode`; fica de fora.
- **Reexecução do `/deep-research`** — pendente de créditos; não bloqueia (a viabilidade real foi
  verificada no código, §5).
```
