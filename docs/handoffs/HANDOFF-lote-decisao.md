# HANDOFF — R18-A: lote × Decision (1 Decisão por lote) — **MERGED (PR #29)**

> **▸ Proveniência (fecho residual, 2026-07-15):** estado vivo da **faixa R18-A**, **congelado** e movido de
> `/HANDOFF-lote-decisao.md` (raiz, untracked) para `docs/handoffs/` no fecho documental residual do v1 (R20-A).

> Fatia A do fecho do v1 (rodada 18). Conserto de 🔴 bug de produção vivo em `main`.
> Fonte da verdade do plano/decisões do dono: `docs/handoff-decisoes-lista-h.md` item 5 (R17-B).
> Ledger da orquestração: `docs/controle-paralelismo.md` (linha R18-A, ~:1842 — **EM VOO**, a
> sessão de controle a fecha ao reconciliar este handoff). UNTRACKED de propósito.

## Estado

- **Branch `feat/lote-uma-decisao-adr0035`** de `main a00815a` → **[PR #29](https://github.com/matheus3006/agente-orquestrator/pull/29) MERGED pelo Matheus** (2026-07-15T01:57Z; commit `1d187c4`, merge commit `05d9b6d`). O "OK do Matheus" foi ele mesmo mergeando.
- **`main` local já FF para `05d9b6d`** (feito pela sessão de controle) — contém o fix. `origin/main`
  já avançou além, para `fd3be27`: **R18-B (circuit-breaker, PR #30) e R19-A (feedback→executor, PR
  #31) também mergearam** — as **3 fatias de código do fecho do v1 landaram**. A sessão de controle
  já reconciliou a R18-A no ledger ("★ R18-A ✔ (PR #29)... piso canônico 723/294 na `05d9b6d`").
- Os 5 untracked de contrato intocados (+ este HANDOFF).
- Piso: **723 backend** (721 + 2) / **294 front** (intocado). tsc/lint limpos.
- Sem migração (V30+ livres) · sem ADR nova (0043 livre).

## O que a fatia entregou

**O bug** (ADR-0035/PR #12 nunca reconciliada com a F-C1/PR #25): cada `decide()` fabricava a sua
Decisão desde que a F-C1 acendeu os writers → o lote (UM ato do dono com N alvos) virava **N
Decisões clonadas** (mesmo racional/área) no Decision-log append-only + **nudge C5 falsa de
"candidata a graduação" na carta** (o detector conta grau≠PRINCIPIO por área sem filtrar tipo).

**A correção — 1 Decisão por lote:**
- `ApprovalService.decideNoLote` — o item do lote dispara a ação real e commita a Approval, mas
  **suprime** a fabricação da Decisão por item (flag `fabricaDecisaoPorItem=false` no `decide`).
- `ApprovalService.fabricaDecisaoDoLote` — registra **UMA** Decisão por (lote × área) depois do
  loop, com a **linhagem dos N na `evidencia`** (padrão F-C2/graduação). Reflete só as `decididas`
  (o lote é não-transacional por design, D9).
- `GateDecisionFactory.Anchor.NoLote` — 3ª âncora da fábrica; `approval_id` nulo (a Decisão do lote
  não tem approval singular; precedente no caso B, que ancora só na issue).
- `ApprovalBatchService` liga o caminho novo (javadoc da classe atualizado).
- **Intactos:** o `activity_log` por-item (auditoria D11), o kickback agregado por conversa (D7), o
  `decisionReason` por item (D8) e o skip GRADUATE_PRINCIPLE-APPROVE (ortogonal ao novo flag).

**Carona (lista H item 2):** as **7 strings de usuário** do `RacionalPolicy` acentuadas
(`:63,:64,:68,:70,:71,:78-79,:92-93`). `CONFAB_FORMS`/`CREATIVE_AREA_MARKS` **byte-idênticas** (o
matcher normaliza sem acento — provado pelo diff, que só começa na `grillProbe` :60) + cinto novo
no `RacionalPolicyTest` (`estetica`/`sem razao`/`so porque sim`). Arrastados 3 testes
(`maieutico`→`maiêutico`): `GateDecisionFactoryTest:86` · `ReviewDecisionServiceTest:211` ·
`ApprovalGateTest:570`. Front: o fallback de `decision-dialog.tsx:106` já estava ortograficamente
limpo — nada a acentuar; os mocks de `reviews.test.ts`/`api.test.ts` são isolados, não quebram.

**Arquivos (12 tracked):** 4 produção (`ApprovalService` · `ApprovalBatchService` ·
`GateDecisionFactory` · `RacionalPolicy`) · 5 testes · 3 docs (`adr/0035` Revisão R18-A ·
`adr/0019` bullet Anchor.NoLote · `roadmap-interno.md` linha na Frente B + nota das 3 fatias).

## Decisões novas desta fatia (racional)

1. **Agrupa por ÁREA** — "1 Decisão por lote" foi implementado como **1 Decisão por (lote × área)**.
   O tipo (`DecisionLog.tipoFor`) e o detector C5 são por-área; no lote homogêneo real (filtro por
   tipo, D2) é exatamente 1 Decisão, e um lote multi-área nunca clona N numa área. **Refino de
   implementação, não redecisão — sinalizado ao dono no corpo do PR #29 para revisão.**
2. **`Anchor.NoLote` com `approval_id` nulo** — a rastreabilidade das N vive na `evidencia` (texto),
   com precedente no caso B (`anchoredToIssue` já deixa `approval_id` nulo).
3. **ADR-0020 não foi tocada** — o "menção cruzada em 0019/0020 se couber" não coube: o grill não
   muda no lote (o probe já pula em vez de 422, doc na própria 0035 §3).

## Achado do RED (corrigido dentro da fatia)

O 1º RED falhou por `decided:1` (não 3): o `propoeHire` de teste reusava o mesmo `target_detail`
→ **dedupe por fingerprint** da `propose` devolvia a MESMA Approval. Corrigido variando o `name` no
alvo (fingerprint distinto por item). Bug do teste, não do código — mas mostra que o dedupe está vivo.

## §VERIFY (`:18081` + pg efêmero; `:8080` INTOCADO, estava desligado)

Via API real (`GET /decisions` + `GET /memory/carta`), 2 companies:
- **Lote** de 3 HIRE_AGENT REJECT → **1** Decisão em `estrutura-org` (evidência *"Lote (ADR-0035): 1
  ato do dono, 3 alvos — Contratar backend; Contratar frontend; Contratar design"*); carta **sem** nudge.
- As **mesmas 3 rejeições individuais** → **3** Decisões + a nudge *"Candidata a graduação (C5): 3
  decisões em 'estrutura-org' … (#2, #3, #4)"* — detector vivo, provando que a diferença é o fix.
- Confirmação cruzada por SQL (`decision` por área × company) bateu. Teardown feito.

## Pendências

- ✅ **MERGE FEITO** pelo Matheus (PR #29, 2026-07-15). ✅ **ff da main local FEITO** (`05d9b6d`).
  ✅ **Ledger fechado** pela sessão de controle ("★ R18-A ✔").
- **Housekeeping pós-merge (NÃO é desta faixa):** rejeitar os 27 HIRE_AGENT PENDING da demo — do
  operador, depois do rebuild do `:8080` (a prova viva do fix no ambiente real). DESTRAVADO.
- **Sobra menor já rastreada pela sessão de controle** (p/ o fecho documental residual): a linha da
  fatia no `roadmap-interno.md` (Frente B) ainda diz "pende OK p/ merge" — verdade quando escrita,
  desatualizada agora que mergeou. Correção docs-only, fora desta faixa.

> **▸ Desfechos (fecho residual R20-A, 2026-07-15):** merge/ff/ledger = **feitos** (nada a fazer) · rejeitar os
> 27 HIRE_AGENT PENDING = **OPERADOR** na prova final (rebuild+boot do `:8080` da `fd3be27`) · a sobra do roadmap
> (linha R18-A "pende OK p/ merge") = **corrigida nesta faixa** → "MERGED (PR #29, main `05d9b6d`)". Arquivo movido
> para `docs/handoffs/`.
