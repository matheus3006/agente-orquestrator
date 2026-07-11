# ADR-0035: Aprovação em lote + kickback de recusa — a fila do gate agregada por company

**Status:** Aceito (2026-07-10) · grill fechado em 2026-07-10
(`handoff-grilling-aprovacao-em-lote.md`, D1–D15) · migração **V27 reservada e NÃO consumida**
(os campos existentes bastaram)

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
`batch_id` (especulação sem caso de uso).

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

## Relaciona
ADR-0018 (gate pré-hoc; a fila é a MESMA, terceira projeção) · ADR-0020 (grill intocado; no lote o
probe pula em vez de 422) · ADR-0031 (transporte de personas reusado; A4 segue exclusivo do
individual) · ADR-0034 (precedente verbatim-sem-paráfrase e do turno-de-sistema na conversa).
