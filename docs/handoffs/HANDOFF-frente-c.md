# HANDOFF — Frente C (memória do CEO): **A FRENTE FECHOU** (F-C1 → F-C2 → F-C3)

> **▸ Proveniência (fecho residual, 2026-07-15):** estado vivo da **faixa R17-A / Frente C**, **congelado** e
> movido de `/HANDOFF-frente-c.md` (raiz, untracked) para `docs/handoffs/` no fecho documental residual do v1
> (R20-A). O norte do v1 é "TUDO registrado" — este é o registro final da frente. Desfechos das pendências
> anotados no fim do §Pendências.

> Faixa R17-A encerrada em 2026-07-14. Fonte da verdade do plano: `docs/handoff-grilling-frente-c-memoria.md`
> (C1–C16 + §RESULTADO das 3 fatias, TRACKED na main). Este arquivo é o estado vivo da FRENTE.
> UNTRACKED de propósito — **o fecho documental residual decide o destino dele** (era pra rodar depois da
> F-C3; a F-C3 acabou).

## Estado

- **F-C1** (PR #25) e **F-C2** (PR #26) **MERGED**. Piso após elas: **702 back / 294 front**.
- **F-C3 "índice + navegação JIT"** — **MERGED (PR #28, `main a00815a`, 2026-07-14)**. Merge LIMPO (FF do
  branch `feat/frente-c-indice-jit`/commit `0d08e7e`, que saiu de `3a42e92` sem divergência — a árvore da
  main é idêntica ao commit testado). Branch local apagado; remoto ainda de pé. HEAD em `main`.
  **Piso canônico: 721 back (702 + 19) / 294 front (intocado).** **ZERO migração** (V30+ livres).
  **RODADA 17 COMPLETA.**
- **A R16-B (fecho documental parcial) foi MERGED durante esta faixa** — PR #27, `main 3a42e92`. O meu
  branch saiu de LÁ (não do `124fcfa` do kickoff), então **a colisão prevista na tabela §Frente B do
  roadmap não aconteceu**: as correções dela já estavam na base e eu só acrescentei a linha da §Frente C.

## O que a F-C3 entregou

- **Split do sempre-on (C9)** — `memory/MemoryAudience`: **CEO = carta + princípios** · **EXECUTOR =
  princípios SÓ**. O executor **não recebia memória nenhuma** antes (D12 = letra morta). Os princípios são
  o índice enxuto e carregam o `#N` → a dieta **ensina o trilho JIT de graça**.
- **`memory/MemoryQuery` ✚** — `search` (metadados, LIVRE, casa por **TERMOS**) + `fetch(#N)` (corpo:
  racional/evidência/linhagem/cluster). **A fronteira C10.b é o invariante da classe.**
- **`skills/mcp/`** — as 2 tools novas no **MESMO bridge da F-AT2** (zero canal novo); rotas
  `/internal/mcp/memory/*` sob o mesmo token/guard; **teto D6 no core** (cap=3 distintos/run + dedup +
  recusa-com-motivo não-fatal, `MEMORY_FETCHED`/`MEMORY_FETCH_REFUSED`); **`McpToolProfile`** (executor vê
  KnowAll+memória · **CEO só memória** — a F-AT2/D6 fica intacta).
- **`AdapterRunExecutor`** — trilho nas **2 pontas** + `memoryFile` materializado em **todo** run.
- **Emendas:** ADR-0003 (tools de memória no bridge) · ADR-0019 (split C9 + memoryFile do produto) ·
  roadmap §Frente C (**COMPLETA**). **ZERO ADR nova — a 0043 segue LIVRE.**
- **Carona:** corpo da `being-ceo` com o trilho do **caso C** + **sync nas 2 demos** (md5==banco).

## Decisões de kickoff desta faixa (racional nas ADRs)

- **`McpToolProfile` (perfil por ponta)** — abrir a 2ª ponta **não pode ampliar autonomia por efeito
  colateral**: sem o perfil, o CEO ganharia `knowall_fetch_body` de graça (F-AT2/D6 = "personas FORA").
- **Teto PRÓPRIO, não compartilhado** (3 corpos de skill + 3 de decisão) — contador único acoplaria duas
  fomes que ninguém decidiu acoplar.
- **🔴 `memoryFile` = território do PRODUTO em TODO run — JUDGMENT CALL DO DONO (colhido no kickoff).**
  Colidia com um invariante **testado** ("o pipeline nunca toca o `AGENTS.md` de um run de Issue"), que era
  **consequência** de o executor não receber memória, não princípio próprio. **Consequência assumida:** o
  que o AGENTE escrever nesse caminho é substituído pela dieta durável. Os 2 testes-guarda foram
  reescritos para codificar a regra nova.
- **Busca casa por TERMOS** (não por frase) — **achado do /verify**, ver abaixo.

## 🔴 Achado do /verify (corrigido dentro da fatia)

A busca casava a **frase inteira** por substring. O **codex** mandou *"a decisão original de cobrança por
assento"*, **não casou nada** e caiu de volta no princípio que já tinha no sempre-on → **respondeu o `#N`
errado (#5)**. Quem chama a tool é um **LLM em linguagem natural**, não um `grep`. Corrigido para
casamento **por TERMOS** (ranqueado por nº de termos; palavras de ligação fora; casamento **por palavra** —
"por" casava dentro de "su**por**te"). Re-provado ao vivo: o codex passou a achar e responder **#1**.
Teste de regressão nomeia o achado. **Lição pra próxima fatia que expuser tool a agente: prove a tool com
o motor REAL fazendo a pergunta como um humano faria — o teste unitário passava com a busca quebrada.**

## §VENDO F-C3 (`:18081` + pg efêmero `fc3-pg`; `:8080` INTOCADO)

| prova | resultado |
|---|---|
| executor busca mid-run → usa → **termina no MESMO run** | ✅ **claude** E ✅ **codex** (reais): fetch → racional **literal** no arquivo → `RUN_SUCCEEDED` |
| cap=3 + 4º recusado com motivo + dedup | ✅ `1/3, 2/3, 3/3` (repetido **não** consumiu slot) + 2 `MEMORY_FETCH_REFUSED` → agente leu, seguiu, terminou |
| ponta do CEO | ✅ turno real citou a **evidência LITERAL** do princípio (string que **só existe no corpo**) |
| split | ✅ executor = **playbook sem carta** (nos 2 motores) · CEO = **carta + playbook** |
| 🔍 fronteira C10.b | ✅ `#N` do log cru **recusado**; racional sigiloso **não vazou** pra nenhum arquivo do workspace |
| carona being-ceo | ✅ **md5 corpo==banco** nas 2 demos (v2; v1 preservada no savelog) |

## Pendências (para o fecho final do v1)

1. **⚠️ CORREÇÃO desta sessão: o fecho NÃO é só "documental + prova".** A F-C3 foi a última fatia **da
   Frente C** — mas o grill da **R17-B** (Lista H, 2026-07-14) fez o fecho do v1 crescer para **3 fatias de
   código + fecho documental + prova final**. Fonte autoritativa: `docs/handoff-decisoes-lista-h.md`
   (untracked; racional verbatim do dono + file:line). Resumo na memória `fecho-final-v1-escopo.md`.
   - **Fatia A — Lote × Decision (1 Decisão por lote):** 🔴 **BUG DE PRODUÇÃO VIVO na main** — rejeitar N
     approvals em lote fabrica N Decisões clonadas + **nudge FALSA de graduação na carta** (ADR-0035 nunca
     reconciliada com a F-C1). Independente. Confirmar reprodução ao vivo antes de consertar.
   - **Fatia B — circuit-breaker de erro repetido** (promessa da ADR-0018 que nunca existiu). Independente.
     Params abertos = mini-grill (limiar, o que "para").
   - **Fatia C — feedback do review → executor:** dependia da F-C3 (agora **destravada**) — +1 dieta no
     `MemoryAudience` (PUSH). É onde a superfície da F-C3 será REusada.
   - **Documental residual:** acentuar as 7 strings do `RacionalPolicy` (NÃO `CONFAB_FORMS`/`CREATIVE_AREA_MARKS`) ·
     3 emendas ADR (0018/0011/0035) · corrigir `lista-h-fecho-v1.md` (§6) · rejeitar os 27 HIRE_AGENT
     PENDING **depois** do fix A · os itens 3/4 abaixo · destino dos untracked de contrato.
2. **Ambiente:** o Postgres compartilhado estava na **V27** e eu **apliquei V28 + V29** nele (autorizado por
   você no kickoff) para poder rodar o import do `being-ceo` nas 2 demos. O **processo :8080 nunca foi
   tocado** (segue desligado como estava) — no próximo boot dele o Flyway acha tudo aplicado.
3. **Fraseado do `oQueMudou` da review** (os 2 espaços de `#N`: "#6 … [APPROVE REVIEW] #5 …") — **NÃO
   tocado** (cosmético, reversível, mexe em texto de decisões vivas). Entra no documental residual.
4. **Dívida de NOME (não bloqueia):** `KnowAllMcpBridge`/`KnowAllMcpBridgeController` agora servem **duas**
   famílias (skill + memória) — o nome honesto seria `McpBridge`. Rename mecânico. Candidato ao fecho.
5. **Destino DESTE arquivo:** o fecho residual decide (era a regra desde a F-C1).

> **▸ Desfechos das pendências (fecho residual R20-A, 2026-07-15) — congelamento:**
> 1. **As 3 fatias de código do v1 LANDARAM:** Fatia A lote×Decision (**R18-A/PR #29**) · Fatia B circuit-breaker
>    (**R18-B/PR #30**) · Fatia C feedback→executor (**R19-A/PR #31**). `main fd3be27`, piso canônico **736/294**.
>    O "documental residual" é ESTA faixa (R20-A): as 7 strings do `RacionalPolicy` foram acentuadas de carona na
>    R18-A/#29; as 3 emendas de ADR (0035/0018/0011) landaram COM as fatias; `lista-h-fecho-v1.md` §6 corrigida
>    aqui; rejeitar os 27 HIRE_AGENT PENDING = **OPERADOR** na prova final.
> 2. **Ambiente:** V28 + V29 **aplicadas** no Postgres compartilhado (pela própria R17-A); o `:8080` segue
>    desligado — no próximo boot o Flyway acha tudo aplicado. Rebuild+boot = sentada do operador (prova final).
> 3. **Fraseado do `oQueMudou`:** → **candidata v1.1 #14** (roadmap) — cosmético; decisão do controle (anti-círculo).
> 4. **Dívida de nome `KnowAllMcpBridge`/`...Controller` → `McpBridge`:** → **candidata v1.1 #15** (roadmap) — rename puro.
> 5. **Destino deste arquivo:** RESOLVIDO — movido para `docs/handoffs/` e commitado neste fecho residual.
