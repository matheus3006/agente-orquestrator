# ADR-0006: Enforcement de governança em runtime (budget + revisão)

**Status:** Aceito (2026-06-30)

## Contexto
Autonomia 24/7 só é segura se os limites forem **impostos pelo sistema**, não confiados ao prompt do agente. Dois invariantes: budget hard-stop e gate de revisão.

## Decisão
**Budget hard-stop em duas fases** (ledger = verdade):
1. **Preflight:** todo início de Run consulta o bloqueio do escopo; se pausado/estourado, não inicia.
2. **Reativo:** ao ingerir cada `CostEvent`, recomputa `SUM(cost)` do ledger; se cruzou o hard limit → pausa o escopo + **cancela Runs em voo + cancela wakeups na fila** + abre incidente (com aprovação de override).
As duas juntas fecham a janela de corrida; os contadores denormalizados são só display.

**Revisão runtime-enforced (máquina de estados pura):**
- Toda transição de status passa por uma função pura de execution-policy.
- Marcar `done` numa Issue que exige revisão é **interceptado** e roteado ao reviewer.
- O registro de decisão é gravado na **mesma transação** da mudança de estado.

## Alternativas consideradas
- Budget só preflight ou só reativo — cada um deixa janela de estouro.
- Revisão por convenção/prompt — frágil ("o agente esqueceu").

## Consequências
- "O agente pulou a revisão" e "o budget vazou" deixam de ser modos de falha possíveis.
- Exige que pausar cancele trabalho (flag sozinha não basta — o gasto continua).
- Acopla budget/revisão ao dispatch do heartbeat (ADR-0005) e ao serviço de status de Issue.

## Revisão 2026-07-01
- A **2ª metade** (gate de revisão runtime-enforced + primitiva de **Approval**) **continua NÃO construída**;
  a 7c operador-only **não a exige** (sem agente disparando install, não há o que aprovar).
- **Primeiro pedaço concreto do enforcement runtime da máquina de estados de Issue foi construído: o recovery
  de Run FAILED** (ver ADR-0011). A decisão de estado vive numa função `IssueService.settleAfterRun`, guardada
  por `execution_lock_run_id`, na mesma linha do princípio "decisão gravada na mesma transação da mudança de
  estado". Auto-retry fica adiado (o sinal transiente×determinístico = erro tipado do ADR-0004 não está cabeado;
  auto-retry cego de falha determinística queimaria budget contra o hard-stop desta ADR).

## Revisão 2026-07-02
- **A 2ª metade (gate de revisão runtime-enforced) foi CONSTRUÍDA** (ver ADR-0012) — **específica, pós-hoc,
  disparada por risco**. O gate vive em `IssueService.settleAfterRun` (a "função pura de execution-policy"
  desta ADR): no sucesso, `risk_level ≥ limiar` → `PENDING_REVIEW` (intercepta o `DONE`), com a decisão gravada
  na **mesma tx** (linha `review` + evento). Revisor = **board** (agente-revisor adiado, constraint #5).
- **Distinção que esta ADR passa a registrar:** **review** (pós-hoc, sobre o output de um run concluído) ≠
  **approval-de-ação** (pré-hoc, autoriza a ação antes de executá-la). Só o **review** foi construído. A
  **Approval-de-ação genérica** (config-change, agente-tool) **continua adiada** — nasce com o agente-como-tool
  (dois consumidores reais para desenhar a interface).

## Revisão 2026-07-12 (F-AT1, grill agente-como-tool)
- **A Approval-de-ação-de-agente ganhou o 1º consumidor real.** F-AT1 (`SKILL_INSTALL_REQUEST`, ADR-0003
  emendada): o EXECUTOR de uma Issue pede uma skill fora do seu set → o pedido nasce Approval PENDING com
  `requested_by_agent_id` = o próprio AGENTE (não o CEO/board) → o dono decide (racional arrancado por
  grill, ADR-0020) → **APPROVE** dispara a ação real com **ator=aprovador** (o mesmo invariante desta ADR:
  "ação delegada = UM evento com ator=aprovador e `detail.requested_by_agent`", já citado no comentário do
  `ActivityAction`/ADR-0009). Reusa o MESMO pipeline `propose`/`decide`/`dispatchAction` das 5 ações
  anteriores (todas CEO-originadas) — nenhuma máquina de estados nova, nenhum gate paralelo.
- **O 2º consumidor (config-change) segue não construído.** F-AT2 (KnowAll body inline) é leitura SÍNCRONA
  — não passa pelo Approval (D5 do grill: cost-neutral, sem gate de escrita); a fatia própria segue gateada
  pela sonda MCP-consume.
- **Fonte do risco no v1 = humano** (o operador seta o `risk_level` por-Issue). A avaliação de risco do
  agente-arquiteto é advisory (conteúdo do plano); quando o número do agente fluir (futuro), a regra é
  **escala-só-pra-cima acima de um piso humano** (nunca de-escala) — para não reintroduzir o "o agente pulou".

## Revisão 2026-07-02 (grilling motores, ADR-0014)
- No **modelo de assinatura** (planos Claude/GPT do operador, sem API paga), o hard-stop de **dólares** é
  **inócuo**: o adapter emite `CostEvent` com **`costCents=0`**, então `SUM(cost) ≥ limite` nunca dispara.
  Mantido como está (não acumula dólares); volta a valer só com API paga (aí `costCents>0`).
- O `CostEvent` passa a **acumular tokens** (`tokensInput`/`tokensOutput` reais lidos do stream) para
  observabilidade (consumo por agente/modelo) + insumo do handoff a 50-60% (ADR-0015). O `AdapterRunExecutor`
  roteia cada emissão do adapter para `BudgetService.recordCost(..., costCents=0, ...)` no sucesso E na falha.
- O **governador real** da assinatura (**rate-limit do plano** + **uso de contexto/handoff**) é reconhecido como
  **fatia futura**, não construído aqui. Estimar dólares por `tokens × tabela` foi **rejeitado** — ficção que
  pausaria agentes por dinheiro inexistente.
