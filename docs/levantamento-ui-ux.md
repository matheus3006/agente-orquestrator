# Levantamento de UI/UX — agente-orquestrador (frontend)

> **Data:** 2026-07-06 · **Escopo:** todas as superfícies do board (`frontend/`, Next.js 16 + Tailwind v4).
> **Objetivo:** mapear onde a experiência está fraca, o que tem "cara de IA", o que pesa/cansa, e
> **como** deixar mais intuitivo e mais leve — com crítica real, não elogio genérico.
> **Status:** diagnóstico. Nada aqui foi implementado. É o insumo da fase de execução de UI/UX.

## Método

Auditoria feita **vendo o app rodando** (dev server na :3000, company `teams-demo`) tela a tela, em
desktop (1440×900) e mobile (375×812), cruzada com a leitura integral do design system
(`src/app/globals.css`, 2851 linhas) e do shell (`view.tsx`, `sidebar.tsx`,
`[companySlug]/layout.tsx`). Números de contraste e de layout foram **medidos** no DOM, não estimados.

**Skills de UI/UX usadas** (todas instaladas em `~/.claude/skills/`):

| Skill | Para quê entrou |
|---|---|
| `design-audit` | Protocolo-mãe: as 15 dimensões (hierarquia, densidade, motion, estados, a11y…) e o "filtro de redução" (todo elemento tem que justificar existência). |
| `emil-design-eng` | Lente de polimento e de motion — curvas de easing, movimento com propósito vs. gratuito. |
| `bencium-{impact,innovative,controlled}-ux-designer` | Lente "fugir da estética genérica de IA" — o que denuncia UI gerada por IA. |
| `ui-typography` | Escala tipográfica, legibilidade, uso de mono. |
| `ui-ux-pro-max` | Referência de densidade, cor e hierarquia. |

## Telas auditadas

Company picker (`/`) · Mission Control (`/[company]`) · Issues lista + detalhe · Agents roster (+ toggle organograma) · Review · Servidores · Moldes (`/moldes`, global) · estados vazios/idle.

---

## Veredito honesto (leia isto antes da tabela)

**O produto NÃO tem "cara de IA genérica".** É o oposto do template Tailwind roxo-com-emoji. Tem uma
identidade autoral forte e coerente — "sala de máquinas": carvão azul + verde-fósforo, Chakra Petch +
IBM Plex, LEDs de status, mini-terminais dentro dos cards, grid-blueprint e grain quase subliminares.
Isso é **um ativo**. A maior parte do trabalho de "melhorar" **não** é trocar o visual — é **tirar peso
e aparar exageros** de um sistema que já é bom.

Os três problemas reais, em ordem de impacto:

1. **É desktop-only de fato.** O shell não é responsivo (sidebar fixa de 232px, `h-screen`). Abaixo de
   ~900px o conteúdo é espremido/cortado. Isso é o buraco mais grave e o menos "de gosto".
2. **Pesa porque empilha densidade demais ao mesmo tempo** — a Mission Control é observatório + sala de
   controle + chat na mesma dobra, com terminais vivos em cada card. Muito para processar de uma vez.
3. **Os poucos tells de "IA" são de execução, não de conceito** — entrada escalonada por `nth-child`,
   "tudo é card", glow como padrão em vez de sinal, radius/efeito em monocultura.

E há **uma falha objetiva de acessibilidade**: o tom `--text-faint` reprova WCAG AA (medido **3.32:1**)
e é justamente o tom usado em massa nos rótulos mono de 9.5–11px.

---

## Tabela de pontos

Severidade: **Alta** = machuca uso/ a11y hoje · **Média** = eleva bastante a experiência · **Baixa** = polimento.
"IA?" = quão "cara de IA" o ponto é (Sim / Parcial / Não).

| # | Onde | Crítica real (o que está fraco hoje) | IA? | Sev. | Como melhorar |
|---|------|--------------------------------------|-----|------|---------------|
| 1 | Shell (`[companySlug]/layout.tsx:37`) | `grid-cols-[232px_1fr]` + `h-screen` **fixos, sem breakpoint**. Em 375px a sidebar ocupa 232px e sobra 143px pro board → conteúdo cortado, nomes de time truncados mid-palavra, coluna do CEO fora da tela. Não há hambúrguer nem drawer. | Não | **Alta** | Sidebar → rail só-ícone <1200px e **off-canvas** (drawer) <768px. Trocar `h-screen` por `min-h-dvh`. Sem isso o app é inutilizável em telas médias/pequenas. |
| 2 | Mission Control (`central-docada`) | Três zonas densas na mesma dobra (sidebar + times **com mini-terminal de 116px por card** + thread do CEO). Cada card ainda roda log mono vivo. Em board cheio = parede de texto se atualizando a cada 2s. Carga cognitiva alta — é o "pesado" que você sentiu. | Parcial | **Alta** | Mini-terminal **colapsado por padrão**: mostrar só a última linha + indicador de atividade; expandir no hover/clique. Reservar o terminal cheio pro drawer. Deixar o board *respirar*. |
| 3 | Global (`--text-faint`, `globals.css:25`) | **Reprova WCAG AA: 3.32:1** sobre `--bg` (medido). E é o tom dos rótulos mono de 9.5–11px (horas, ids, hints, meta) — ou seja, a falha está no texto mais usado. | Não | **Alta** (a11y) | Subir L do faint de 0.5 → ~0.60 (mira ≥4.5:1) **ou** proibir faint abaixo de 12px. `--text-dim` (7.4:1) e `--text` (16:1) estão ótimos — o problema é só o faint. |
| 4 | Tipografia (todo o CSS) | Mono minúsculo em volume: 9.5px (`balao-hora`, `composer-hint`), 10px (dezenas de labels), 10.5/11px por toda parte. IBM Plex Mono a 10px em dpr1 cansa e mal passa de legível. | Parcial | Média | Piso do mono em 11–12px; reservar <10px só pra cromo verdadeiramente secundário; +0.05 de line-height nos blocos mono densos. |
| 5 | Toda lista (`card-enter`, `roster-row`, `review-card`, `agent-card` — `nth-child` 1..6) | Entrada **escalonada por `nth-child(1..6)`** em cascata. É a assinatura nº 1 de UI gerada por IA. Pior: só cobre 1–6, então o 7º+ item entra com delay 0 (visível no roster de 14). E em lista que faz poll a cada 2s pode re-disparar. | **Sim** | Média | Tirar o stagger de **listas de dados** (não são conteúdo-herói): animar o container uma vez, ou um fade curto único. Se mantiver stagger, gerar delay por `--index`/`calc()`, não por `nth-child`, e com teto. |
| 6 | Cards em geral | **"Tudo é card"**: `raised` + borda 1px + radius 10 + sombra aplicado igual a agent, issue, review, server, ledger, props, org-node. Elevação uniforme = nada tem prioridade; lê como templatizado. | Sim | Média | Reservar o card elevado pro **objeto interativo/primário**. Listas secundárias viram `row` plano com divisória (o padrão `.row` já existe — usar mais, "card" menos). |
| 7 | Glow/box-shadow (buttons, badges, avatars, tally, `msg-quem`, `preview-badge`) | Glow virou **padrão decorativo**, não sinal. A marca diz "verde-fósforo = trabalho acontecendo" (`globals.css:27`) — mas se botão parado, avatar e badge também brilham, o glow perde o significado. | Parcial | Média | Glow **só** no estado semântico "running/live". Tirar o glow de cromo estático (avatar, botão primário em repouso, tally sem atividade). |
| 8 | Superfícies (`--bg` .145 / `--raised` .175 / `--overlay` .21) | Passos de lightness minúsculos (~0.03) → cards se separam **quase só pela borda de 1px**. Em tela cheia vira "sopa de bordas", profundidade fraca, massa escura chapada. | Não | Média | Alargar os passos de L (ex.: raised → .19–.20) **ou** adotar um token único de sombra de elevação e reduzir o nº de contêineres com borda por tela. |
| 9 | Motion perpétuo (`led-breathe`, `led-blink`, `cursor-blink`, `pensando`, `feed-flash`) | Vários loops infinitos simultâneos numa tela = inquieto, "vivo demais". (Ponto positivo: `prefers-reduced-motion` está tratado.) | Parcial | Média | Um único movimento perpétuo por tela — o LED "running". Todo o resto anima **no evento** (mudou → animou uma vez). |
| 10 | Tokens (`globals.css`, vários) | Literais OKLCH crus **fora de token**, violando a regra escrita "Zero cor fora destes tokens" (`:8`): hover do `.btn` (`oklch(0.24 …)`), o azul do balão `.msg.minha` (`oklch(0.2 0.03 200 …)` + `oklch(0.4 0.05 210 …)` — uma cor que nem está na paleta), e dezenas de alfas `oklch(0.86 0.215 152 / X)` inline em vez de `--phosphor-*`. | Não | Média | Tokenizar os alfas recorrentes e o "azul do balão"; regra de lint proibindo `oklch(` fora do `:root`. Dívida de design system que barateia toda mudança futura. |
| 11 | Vocabulário / onboarding | Termos inventados sem explicação de primeiro uso: **Moldes, Motor, Carta, Grill, "ancorada", "sala de máquinas"**. Ótimos pra power-user, opacos pra quem chega. O modelo mental (company → CEO → times → issues → aprovações) não é ensinado em lugar nenhum. | Não | Média | Overlay "como o board funciona" no primeiro acesso (dispensável), tooltips nos termos inventados, e um first-run de company vazia que ensina o loop. |
| 12 | Estado ocioso (Mission Control / Agents) | Com tudo "ocioso", **o sistema fósforo (verde=trabalho) fica invisível** — o board vira parede cinza sem próxima-ação óbvia. O design brilha quando há run; não guia quando não há. | Não | Média | Mission Control ociosa deve destacar "próximo passo" (iniciar run, abrir chat, contratar) como afordância primária, não só listar times parados. |
| 13 | Agents roster | Redundância de status: a linha-doing diz "ocioso — nunca executou" **e** ainda há a palavra-status "ocioso" à direita. Diz a mesma coisa duas vezes por linha, 14×. | Parcial | Baixa | Manter a linha-doing descritiva; **dropar a palavra-status redundante** (ou usá-la só quando agrega: running/blocked). Filtro de redução. |
| 14 | Issues lista | Todas "Concluídas" mas 7/9 com badge **R5 coral** → lista "pronta" pintada de vermelho-perigo. Cor diz perigo, status diz terminado — tensão semântica. | Não | Baixa | Mutar a cor de risco quando a issue é DONE/CANCELLED (risco é sobre trabalho pendente, não concluído). |
| 15 | Grids fixos (`roster-row`, `feed-row`, `detail-grid`, `moldes-grid`) | Colunas em px fixos sem fallback: `220px 1fr 130px 150px 90px`, `74px 20px 1fr`, `1fr 280px`, `300px 1fr`. Abaixo da largura de projeto, quebram/estouram. | Não | Média | Media/container-query que **empilha** as colunas fixas em telas estreitas. Anda junto do #1. |
| 16 | Moldes (`/moldes`) | Rota global tem **shell próprio** (header sem sidebar) — sair da company parece sair do app. Quebra de continuidade. | Não | Baixa | Manter a sidebar em Moldes (é alcançável por ela) ou enquadrar Moldes como catálogo em overlay/modal. |
| 17 | Radius | Quase tudo é radius 10 (cards, botões, chips, inputs, painéis) — monocultura de raio, sensação "de fôrma". | Parcial | Baixa | Escala pequena de raio (4 / 8 / 12) mapeada ao tamanho do elemento — chip pequeno raio menor, painel raio maior. |
| 18 | Empty states | Tratamentos diferentes: `grupo-vazio` (dashed), `empty-state` (dashed + ícone), Review (ícone centrado sem borda). Inconsistência entre telas. | Parcial | Baixa | Um único componente de empty-state (ícone + título + ação sugerida) reusado em todas as superfícies. |

---

## Aprofundamento por tema

### 1. Peso e densidade (o "menos pesado")
A heaviness não vem de "feio" — vem de **tudo pedindo atenção ao mesmo tempo**: superfícies quase
isoluminantes (#8) separadas só por borda, texto mono minúsculo em alto volume (#3, #4), motion
perpétuo múltiplo (#9) e a Mission Control tentando ser três telas numa (#2). O caminho de "leveza"
é **subtração**, não redesenho: menos contêineres com borda, terminal colapsado, glow só onde
significa, um movimento vivo por vez. O sistema fica mais premium *tirando*, não *acrescentando*.

### 2. "Cara de IA" — a resposta honesta
Pouca. O tema é autoral. O que **denuncia geração automática** é execução repetível:
- **Stagger `nth-child` em cascata** (#5) — o tell mais forte e o mais fácil de matar.
- **"Tudo é card" + radius/efeito em monocultura** (#6, #17) — falta de hierarquia intencional.
- **Glow como enfeite padrão** (#7) — "brilho porque dá", não porque significa.
Consertar esses quatro já move a percepção de "gerado" para "desenhado".

### 3. Intuitividade (o "mais intuitivo")
O modelo é poderoso mas **não se explica** (#11). O pior momento é o **estado ocioso** (#12): é
quando um usuário novo chega, e é exatamente quando a UI não guia. Os empty-states por-tela são bons
(Review, server-failure com tail, preview-wait) — falta o **empty-state do sistema todo**: "você não
tem nada rodando; comece por aqui".

### 4. Acessibilidade — números medidos
| Par | Contraste | AA (≥4.5) |
|---|---|---|
| `--text` / bg | 16.12:1 | ✓ |
| `--text-dim` / bg | 7.41:1 | ✓ |
| **`--text-faint` / bg** | **3.32:1** | **✗** |
| **`--text-faint` / raised** | **3.19:1** | **✗** |
| `--phosphor` / bg | 13.91:1 | ✓ |
| `--phosphor-dim` / terminal | 5.90:1 | ✓ |
| `--amber` / bg | 11.53:1 | ✓ |

Só o `--text-faint` reprova — mas reprova no texto mais frequente da UI (#3). Fora isso, a base de
a11y é **boa**: `:focus-visible` com outline fósforo, `prefers-reduced-motion` completo, `tabular-nums`
nos readouts. (Não auditei fluxo de leitor de tela na Mission Control docada — vale um passe dedicado.)

### 5. O que está BOM (preservar — não mexer)
- Identidade "sala de máquinas" coesa e distinta (o maior ativo).
- `prefers-reduced-motion` tratado de ponta a ponta (`globals.css:2837`).
- `:focus-visible` com outline fósforo em todo foco.
- `tabular-nums` em todos os números que mudam (toque profissional).
- Estados de erro/vazio honestos e temáticos (Review limpo, `server-failure` com tail legível).
- Sistema semântico de cor (fósforo/amber/coral/ice) com significado consistente.
- Par tipográfico forte (Chakra Petch display + IBM Plex Sans/Mono).
- Padrões autorais do produto: terminal-dentro-do-card, aprovação **ancorada** na thread do CEO.

---

## Plano faseado (pronto para a sessão de execução)

Ordem por impacto/esforço. Cada fase é aprovável e verificável isoladamente (VENDO).

**P0 — Crítico (uso e a11y; não é gosto)**
- #1 Shell responsivo (rail colapsável + drawer off-canvas) e `min-h-dvh`.
- #3 Corrigir contraste do `--text-faint` (subir L ou piso de tamanho).
- #15 Fallback de empilhamento pros grids fixos (anda junto do #1).

**P1 — Refinamento (tira peso, mata os tells de IA)**
- #2 Mini-terminal colapsado por padrão na Mission Control.
- #5 Remover stagger `nth-child` das listas de dados.
- #6 Demote de "tudo é card" para `row` plano nas listas secundárias.
- #7 Glow só no estado "live".
- #8 Alargar passos de superfície / token de elevação.
- #4 Piso tipográfico do mono.

**P2 — Polimento**
- #9 Um movimento perpétuo por tela. #10 Tokenizar OKLCH crus. #11 Onboarding/tooltips.
- #12 Empty-state do sistema. #13 Redundância do roster. #14 Risco vs status. #16 Shell do Moldes.
- #17 Escala de radius. #18 Empty-state unificado.

---

## Apêndice — referências de arquivo

- Design system: `frontend/src/app/globals.css` (tokens `:root` :9–46; componentes em `@layer components`).
- Shell/grid: `frontend/src/app/[companySlug]/layout.tsx:37` · `frontend/src/components/view.tsx:16` · `frontend/src/components/sidebar.tsx`.
- Mission Control docada: `globals.css:1800+` (`central-docada`, `zona-*`, `ceo-*`, `msg`, `aprovacao`).
- Listas com stagger: `agent-grid`/`agent-card` (`:234`), `roster-row` (`:1059`), `review-card` (`:1001`), `feed-row` (`:442`).
- Contraste medido via canvas no DOM da `teams-demo` em 2026-07-06.
