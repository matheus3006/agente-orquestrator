# HANDOFF — R22-B: plano de caça de bugs — **PRODUTO MONTADO, aguardando aceite do controle**

> Faixa **R22-B** (sessão satélite, 2026-07-16/17). UNTRACKED na raiz (precedente R20-A/R21-A/R22-A).
> Ledger: `docs/controle-paralelismo.md` (Rodada 22). **A pesquisa e a reconciliação pós-#34 estão
> FECHADAS; o produto está montado.** Resta o aceite do controle e a limpeza do staging (§Pendências).

## Produto

**`docs/plano-descoberta-bugs.md`** (untracked, 545 linhas) — o mapa da fase. Contém: método (as 4
lições viradas regra de bateria) · estado do mundo pós-merge · os 10 achados pré-fase reconciliados
com o roadmap real · **inventário condensado de 1085 afirmações falseáveis em 42 ADRs** · **13
achados novos levantados, 12 sobreviventes** (a `N2` caiu na minha conferência e ficou registrada
riscada, para não ser re-levantada) · priorização por fronteira com as **5 primeiras candidatas** ·
**Bateria 1 (roda hoje) e Bateria 1b (pós-reboot)** roteirizadas · esqueleto do roadmap (itens
#11–#18 propostos) · honestidade de cobertura (11 ressalvas) · handoff de volta.

## O que esta retomada fez (as 8 pendências do handoff anterior)

1. **Base = `main` `4c1b35f`** (decidido pelo controle). Tudo lido por `git show main:` — o checkout
   compartilhado nunca foi tocado nem trocado. ✅
2. **4 fragmentos re-extraídos contra a main** — `adr-0037` (+5 afirmações), `adr-0004` (+7),
   `adr-0011` (+6), `adr-0001` (conferido: **nada a mudar**). ✅
3. **134 `⛔ BUG#1` reclassificadas uma a uma** → **125 `🎯 AGORA (pós-reboot)`**, **7 `🚫`** (galho
   DOCKER), **1 `🎯 AGORA`** (0018-A21), **1 `❓ N/C`** (0018-A22). **O estado ⛔ está extinto.** Não
   foi carimbo: cada exceção tem motivo escrito no fragmento. ✅
   **Decomposição dos 145 `🎯 (pós-reboot)` do inventário** (confere com a tabela do produto §3):
   **125** (das 134 ⛔) **+ 17** (das 18 afirmações NOVAS que as emendas trouxeram — a 18ª,
   `0011-A25`, virou 🚫) **+ 3** (que o conserto moveu de outro estado: `0037-A32` e `0004-A23` de
   🔴, `0037-A36` de 🎯). Efeitos colaterais na tabela: 🔴 19→17 · 🚫 207→215 · ❓ 7→8 · N 1067→1085.
4. **Ambiente vivo confirmado PRÉ-fix** — o reboot do `:8080` é do operador e é o único
   pré-requisito da Bateria 1b. Provado que o `#34` **não toca** frontend/`ApprovalService`/busca de
   skill/`activity_log`/hooks ⇒ **a Bateria 1 pode rodar hoje** sem que o reboot invalide o dado. ✅
5. **0021/0031/0035 aceitos sem re-verificação** (decisão do controle), com a ressalva no produto
   (§8.1). Mitigação colhida no caminho: as 14 linhas BUG#1 da `0021` tiveram as âncoras conferidas
   contra a main — **14/14 conferem**. ✅
6. **Contagem N/C reconciliada = 8** (era "9 × 7"). A crítica de completude **contava prosa como
   classificação**: o `adr-0014` tem **zero** N/C (sua nota diz literalmente *"nenhuma afirmação vaga
   o suficiente para exigir ❓ N/C"* — o marcador na frase foi contado). ✅
7. **Reconciliação com o roadmap real** — a seção "Roadmap de ajustes" **já existe** (`docs/roadmap-
   interno.md` ~:223-245) com **itens 1–10**; o produto adota essa numeração (achado novo entra como
   #11+) e mapeia P1→#1 … P8→#7. O carimbo do item 1 (`✅ EM PR` → MERGED/#34) é **carona da R23-A**;
   não o fiz. ✅
8. **Staging NÃO apagado** — ver §Pendências.

## Achado que mudou de sinal

**O "achado crítico" do handoff anterior está fechado e era menor do que parecia.** A contaminação
pela branch da Frente E é **exatamente 4 ADRs**: `git diff main feat/backend-spring-ai-2 -- docs/adr/`
toca só `0001`+`0043`; `git diff 83c5fe9 main -- docs/adr/` toca só `0004`/`0011`/`0037`. A união é o
conjunto re-extraído. **As outras 38 ADRs são byte-idênticas entre branch e main** — o material
sempre esteve limpo no eixo texto-de-ADR. (O `adr-0001` foi extraído da MAIN, não da branch: as 21
âncoras batem e o offset de −4 não se aplicou.)

## Pendências (para o controle)

1. **A sentada do operador** — reiniciar o `:8080` com o jar da `4c1b35f`. Destrava **145
   afirmações** (13% do inventário). Maior alavanca isolada do plano.
2. **Emitir a Bateria 1** — está pronta e **não espera nada**. O passo `B1.1` (um `select seq,
   conversation_id from issue`) é o mais barato e mais valioso do documento.
3. **3 decisões que NÃO tomei** (produto §8): o veredito **DOCKER** (`🎯 AGORA [DK]` × `🚫`, o
   inventário está inconsistente e ambas as leituras se defendem) · o rebaixamento de `0012-A4`
   (✅ PROVADA órfã) · re-verificar ou não `0021`/`0031`/`0035`.
4. **`docs/plano-descoberta-bugs.materiais/` NÃO foi apagado** (contrato: só após o aceite). **Antes
   de apagar, uma pergunta:** o produto condensa mas **não carrega as 1085 linhas**, e as baterias
   citam afirmações por ID (`0037-A46`, `0012-A4`…) que **só existem lá dentro**. Recomendo
   **preservar até a última bateria fechar** (ou promover a tracked). Decisão + execução: controle.

## Contrato

- **ZERO código/commit/branch.** `HEAD` = `main` `4c1b35f`, zero arquivo tracked modificado.
- **Ambiente vivo intocado** (leitura apenas). O `:8080` segue no jar pré-fix.
- **Vxx consumida:** nenhuma (a V30 foi da R22-A). V31+ livres. **ADR-0043 existe só na branch da
  Frente E**, não na main.
- **Piso:** 750/294 na `4c1b35f` (não re-medido — nenhum código tocado).
- **Untracked tocados por esta faixa:** `docs/plano-descoberta-bugs.md` (produto) ·
  `docs/plano-descoberta-bugs.materiais/` (staging) · este handoff.
