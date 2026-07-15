# ADR-0035: Aprovação em lote + kickback de recusa — a fila do gate agregada por company

**Status:** Aceito (2026-07-10) · grill fechado em 2026-07-10
(`handoff-grilling-aprovacao-em-lote.md`, D1–D15) · migração **V27 reservada e NÃO consumida**
(os campos existentes bastaram) · **reconciliado com a F-C1 — UMA Decisão por lote, não por item
(2026-07-14, R18-A; ver Revisão abaixo)**

## Contexto
O gate pré-hoc (ADR-0018/0020) decidia um-a-um, embutido na thread (`ApprovalThreadCard`). A
cascata real da cafeteria gerou **27 approvals `HIRE_AGENT` PENDING de uma vez** — pedido verbatim
do dono: *"deveria existir uma fila/tela de contratações pendentes, em lote, com `aprovar todos` e
`recusar todos`"*. E o grill expôs um segundo buraco, maior que a ergonomia: recusa era **terminal
e silenciosa** — o CEO nunca sabia POR QUE uma proposta caiu (*"isso pode reforçar a necessidade de
um aprofundamento no grill ou até evidenciar uma falha no racional do CEO"*). `decisionReason`
existia morto ponta a ponta: schema/DTO sim, nenhum consumo funcional.

## Decisão

### 1. Tela dedicada `/[companySlug]/approvals` (D1) — a fila agrega por company, não por thread
Clona o padrão estrutural de `/reviews/page.tsx` (tabs de status + lista + dialog de decisão).
Approvals atravessam N conversas; uma fila de verdade vive por company. A thread segue projetando
os cards ancorados — mesma fonte, duas projeções (nada muda na ADR-0018).

### 2. Lote homogêneo por tipo POR CONSTRUÇÃO (D2/D3)
O backend aceita qualquer `ApprovalActionType` (reaproveita o `dispatchAction`); a UI **exige
filtro por tipo ativo** para habilitar os botões — misturar contratação com issue num clique, não.
Semântica: checkboxes por item + "selecionar tudo" + **"Aprovar/Recusar selecionados"** (cobre o
caso raiz "aprovar todos" E o realista "aprovar 25, vetar 2"). Itens do POST saem das VISÍVEIS sob
o filtro (`itensDoLote`): selecionada de outro tipo nunca viaja, mesmo que a seleção sobreviva a um
clique errado.

### 3. `POST /approvals/batch-decision` (D10) — loop sequencial sobre o `decide()` intocado (D4/D9/D14)
`ApprovalBatchService` deliberadamente **NÃO transacional**: cada `decide()` abre a própria tx —
item cujo dispatch quebra (alvo malformado) desfaz SÓ a si (deny-by-default POR ITEM) e o lote
segue. Item já decidido/inexistente/**grelhado pelo probe** vira linha em `skipped` com o porquê;
a contagem sai do loop de graça — SEM UI de resumo parcial (single-user por design). O grill no
lote NÃO devolve 422: o item grelhado é pulado (rodada contada, segue PENDING, resolvível na
thread) — o 422-com-probe continua sendo o contrato do endpoint individual. Sem teto de execução
nesta fatia; debounce do `CompanyMemoryConsolidator.refresh` ficou como plano B condicional a
medição real.

### 4. Recusa VOLTA ao proponente (D5/D6) — 2º consumidor do transporte de personas
`PersonaContinuationPoster.postRecusaDoGate`: turno na `conversation_id` de ORIGEM, endereçado ao
`requestedByAgentId` (por id — vale para persona E CEO legado), PENDING + wakeup — o CEO processa
a recusa como próximo turno da mesma conversa. O gatilho é a MÃO DO DONO na API (não um settle
reexecutável): sem guard de idempotência, e o turno **ZERA a régua M9** como qualquer fala do dono
— deliberado, não acidente. O gancho fica nos CALLERS (`ApprovalController` no individual,
`ApprovalBatchService` no lote), nunca dentro do `decide()`: o lote precisa agregar ANTES de
postar. Kickback é fail-soft em toda ponta (sem conversa de origem, proponente inexistente, falha
de post = log): ele **informa, não governa** — nunca desfaz o gate.

### 5. UM turno síntese por conversa (D7) + motivo comum e observação por item (D8)
No lote, as recusas agrupam por `conversation_id`: N recusas da mesma thread viram UM turno
(`RecusaKickback`) — motivo comum uma vez, cada proposta na sua linha, observação por item
distinta quando presente. Motivo **verbatim** do dono (precedente do caso B, ADR-0034: zero
paráfrase de LLM). Na linha da Approval, `decisionReason` = `motivo — observação: <obs>` — o campo
morto vira o registro do feedback **sem coluna nova** (V27 não consumida). Motivo comum é
obrigatório no REJECT em lote (400 sem ele); no individual é recomendado — sem `reason`, o
racional (sempre presente no commit) é o que viaja.

### 6. Kickback também na recusa individual (D15)
O pedido do dono foi sobre QUALQUER recusa, não só lote: a `ApprovalThreadCard` ganha o campo de
motivo no reject (todo commit passa pela grelha — o form dela é o ponto único), disparando o mesmo
mecanismo como lote de UM.

### 7. SEM override de motor no lote (D12) e auditoria por item (D11)
O lote roda sempre o motor protocolado na proposta — motor errado em `HIRE_AGENT` cria agente de
verdade, síncrono; o escape é decidir aquele item na thread, onde o A4 (ADR-0031) segue valendo.
Auditoria mantém a granularidade: 27 decisões em lote = 27 `activity_log` + 27 `Decision`, sem
`batch_id` (especulação sem caso de uso). **[EMENDADO — R18-A: o `27 Decision` era a premissa de
2026-07-10, antes de a F-C1 acender os writers do gate; virou `1 Decision por lote`. O `27
activity_log` (auditoria do ATO de decidir cada item, D11) PERMANECE. Ver Revisão abaixo.]**

## Alternativas rejeitadas
- **Fila embutida na thread com "aprovar todos"** — approvals cruzam N conversas; agregação por
  thread não resolve a cascata.
- **Botões secos "aprovar todos" sem seleção** — não cobre o caso intermediário real (aprovar 25
  de 27, dado o viés de motor já observado).
- **Recusa via consolidação de carta** — lenta e difusa; o CEO precisa do motivo NA conversa em
  que propôs (kickback direto, risco de ruído aceito e mitigado pela agregação D7).
- **Kickback dentro do `decide()`** — quebraria a agregação D7 (o lote postaria N turnos).
- **Lote numa tx única** — um alvo malformado desfaria 26 decisões legítimas.
- **`batch_id` de auditoria / UI de resumo parcial / teto de execução** — engenharia especulativa
  no contexto single-user; o plano B (debounce do refresh) fica condicionado a medição.
- **Coluna nova V27 para observação por item** — `decisionReason` morto já era o lugar do feedback
  do board; texto composto resolve sem schema.

## Consequências
- O transporte de continuação de personas (ADR-0031, M6) ganha o **2º consumidor** fora do
  settle — o poster passa a ser seam de "sistema fala com agente na conversa", não só de bastão.
- `decisionReason` deixa de ser letra morta: escrito pelo lote/card, lido pelo kickback.
- Recusar em lote N conversas distintas gera N turnos + N wakeups (1 por conversa) — ruído
  aceito no grill; a agregação por conversa é o freio.
- O turno de recusa zera a régua M9 da conversa (é ação do dono) — kickbacks autônomos
  subsequentes recomeçam a contagem.
- `BadRequestException` (400) entra no vocabulário de erros da casa para contrato condicional de
  request (o Bean Validation não expressa "motivo obrigatório SÓ no REJECT").

## Revisão 2026-07-14 (R18-A — reconciliação com a F-C1: UMA Decisão por lote)

> **Bug de produção vivo em `main`**, achado pela sessão de decisões da lista H (R17-B,
> `docs/handoff-decisoes-lista-h.md` item 5). Decidido e recortado pelo dono; esta fatia executa.

**O buraco.** A §7 acima prometia "27 decisões em lote = **27 Decision**". Isso valia em 2026-07-10
(PR #12): o gate ainda **não** fabricava Decisões — os writers estavam apagados. A **F-C1** (PR #25,
2026-07-14) os acendeu: cada `decide()` passou a fabricar a sua Decisão, e a porta *"sempre nasce"*
(`ApprovalService.fabricaDecisaoDoGate`). As duas fatias landaram em momentos diferentes e **ninguém
cruzou** — então o lote passou a carimbar o **mesmo racional literal** em **N Decisões clonadas** na
**mesma área**, no Decision-log append-only (nada as coleta). Pior: o detector de graduação (C5) conta
grau≠PRINCIPIO por área **sem filtrar tipo** e dispara com 3 — logo rejeitar 3+ propostas do mesmo
tipo em lote acendia uma **nudge FALSA de "candidata a graduação" na carta** (o artefato que os
AGENTES leem). A Frente C inteira existe para a memória ser *derivada, nunca auto-escrita* — e o lote
a envenenava com um sinal inventado.

**A correção — 1 Decisão por lote, não por item.** O lote é **UM ato do dono com N alvos** (o modelo
de dados já contava assim: um `racional` do lote + `Item(approvalId, observacao)` por item). Agora:
- `ApprovalService.decideNoLote` (o item do lote) dispara a ação real e commita a Approval, mas
  **SUPRIME** a fabricação da Decisão por item (flag `fabricaDecisaoPorItem=false` no `decide`);
- `ApprovalService.fabricaDecisaoDoLote` registra **UMA** Decisão depois do loop, com a **linhagem
  dos N alvos na `evidencia`** — exatamente como a F-C2/graduação registra suas fontes (C5). Reflete
  só os itens que de fato commitaram (o lote é não-transacional por design, D9);
- âncora nova **`GateDecisionFactory.Anchor.NoLote`**: a Decisão do lote não aponta uma approval
  singular (não há uma "approval do lote"); `approval_id` fica nulo, com precedente no caso B
  (Decisão ancorada só na issue). A rastreabilidade das N vive na `evidencia`.

**O que NÃO muda (invariantes preservados).** A **auditoria** segue granular: **N `activity_log`**
(um `APPROVAL_REJECTED`/`APPROVED` por item — o registro do ATO de decidir cada approval) permanece;
só a **memória durável** (`Decision`) reconcilia. O kickback de recusa segue agregado por conversa
(D7); o `decisionReason` por item (D8) segue na linha da Approval; o skip de GRADUATE_PRINCIPLE-APPROVE
(o dispatch já fabrica o princípio) é **ortogonal** ao novo flag.

**Decisão de implementação (refino, não redecisão): agrupa por ÁREA.** Como o TIPO
(`DecisionLog.tipoFor`) e o detector C5 são **por-área**, a fabricação do lote agrupa as decididas por
área → **uma Decisão por (lote × área)**. No lote homogêneo real (o filtro por tipo da UI, D2 → mesma
área) isso é **exatamente 1 Decisão**; um lote que tocasse áreas distintas dá uma por área (nunca N
clones numa área) — o invariante correto é "um ato ≤ 1 Decisão por área", que é o eixo do detector.

**C5 fora do escopo (decidido pelo dono).** 3 rejeições **separadas** (fora de lote) na mesma área
seguem acendendo a nudge — *by-design* (3 atos SÃO tendência; REJECT registra a recusa como Decisão
normal). O bug era **um ato contar como N**; só isso muda.

**Prova viva** (`/verify`, descartável `:18081` + Postgres efêmero, API real): lote de 3 HIRE_AGENT
REJECT → **1** Decisão em `estrutura-org` (evidência: *"Lote (ADR-0035): 1 ato do dono, 3 alvos — …"*),
carta **sem** nudge; as MESMAS 3 rejeições **individuais** → 3 Decisões + a nudge *"Candidata a
graduação (C5): 3 decisões em 'estrutura-org' … (#2, #3, #4)"* — o detector vivo, provando que a
diferença é o fix, não uma detecção quebrada.

## Relaciona
ADR-0018 (gate pré-hoc; a fila é a MESMA, terceira projeção) · ADR-0020 (grill intocado; no lote o
probe pula em vez de 422) · **ADR-0019 (a fábrica única de Decisões da F-C1 — a `GateDecisionFactory`
que o lote agora usa UMA vez, com a `Anchor.NoLote` nova; R18-A)** · ADR-0031 (transporte de personas
reusado; A4 segue exclusivo do individual) · ADR-0034 (precedente verbatim-sem-paráfrase e do
turno-de-sistema na conversa).
