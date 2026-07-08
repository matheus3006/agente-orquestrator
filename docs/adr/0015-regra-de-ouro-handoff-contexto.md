# ADR-0015: Regra de ouro — handoff de contexto a 50-60%

**Status:** Aceito (2026-07-02, sessão grilling `docs/handoff-grilling-motores-fechado.md`)

## Contexto
Encher a janela de contexto de uma sessão de agente degrada a qualidade e gera bugs difíceis de reproduzir (o
"bug esquisito" da janela cheia). O `--resume` nativo dos CLIs (Claude/Codex) só faz a janela **crescer** até
degradar — recarrega a conversa inteira. A continuidade de **arquivos** entre Runs já existe (Workspace, volume
nomeado por `company/agent/issue`); a continuidade de **contexto** (Session, ADR-0002) nunca foi ligada
(`priorSession=null`).

## Decisão
**Invariante de qualidade, transversal ao produto:** nenhum agente usa mais que **~50-60%** da janela de contexto
de uma sessão. Ao cruzar o limite (ou numa task que estourou), o agente **destila** o estado num **handoff**
(documento de passagem de turno, gravado no Workspace que já persiste) e uma **sessão nova** assume lendo o handoff
— em vez de recarregar a conversa inteira. A continuidade preferida é **destilação**, não recarga bruta.

**v1 (parte que a F.7/ADR-0014 constrói):** o adapter rico **instrumenta o uso de tokens** (lê `usage` do stream)
e emite `CostEvent`; os 50-60% se calculam contra a **janela de contexto** do `engine_catalog` (V13). Isso é só a
**medição** — a fiação para detectar o limite.

**Fatia futura (o loop completo):** detectar o limite → disparar a destilação (mandar o agente escrever o handoff)
→ iniciar um turno novo lendo o bilhete → retomar o plano. Não construído no v1.
**→ CONSTRUÍDA na [ADR-0030](0030-loop-handoff-contexto.md)** (Fatia 1 do v1 do CEO, 2026-07-07): o loop
existe para o CEO conversacional (gatilho pós-settle, bilhete `.orq/handoff/<conv>.md`, continuação fresca,
trava anti-loop N=5, fail-open).

## Consequências
- Positiva: qualidade estável independente do tamanho da task; sessões novas começam "frescas" com só o essencial;
  torna o `--resume` bruto **secundário** (ADR-0002 reorientado). É a mesma disciplina aplicada às sessões de
  desenvolvimento do próprio produto (destilar handoff e recomeçar em vez de arrastar uma sessão gigante).
- Custo: exige uma boa skill de destilação (o handoff precisa ser fiel e acionável) e a medição de tokens (v1).

## Relaciona
ADR-0014 (instrumentação de tokens + `context_window` do catálogo são o insumo), ADR-0002 (o handle nativo de
sessão é capturado mas não persistido; `--resume` bruto vira secundário à destilação).
