# Plano de caça — fase de DESCOBERTA DE BUGS (R22-B)

> **Contrato:** UNTRACKED de propósito (ler-sempre-commitar-nunca; o destino final é do fecho da
> fase). Produto da faixa **R22-B** (rodada 22 do ledger `docs/controle-paralelismo.md`).
> **Data:** 2026-07-16. **Base:** main `83c5fe9` (PRs #1–#33), piso 738 back / 294 front.
> **Escopo verbatim da fase (dono, 2026-07-16):** *"essa proxima fase sera de descovery de bugs,
> voce ira planejar e executar todos os testes das aplicacao usando o que esta descrito como
> funcionalidade nas ADRs e comprovar o que esta funcionando e o que nao esta, apartir dai
> criaremos um roadmap de ajustes"*.
>
> Este plano PLANEJA; as baterias são faixas próprias emitidas pela sessão de controle a partir
> daqui. A **R22-A** (conserto do bug #1, branch `fix/issue-compositor-adr0037`, V30 consumível)
> está EM VOO em paralelo — nada aqui redesenha o conserto (4 decisões do dono, handoff da prova §4).

## 0. Método da fase (as 4 lições da R21-A, operacionalizadas como regras de bateria)

As lições custaram ~4h de prova que acharam 1 bug grave + 4 achados que o piso 738/294 verde
não viu (fonte: `HANDOFF-prova-final-v1.md` §10 — na raiz até a R22-A movê-lo para
`docs/handoffs/`). Nas baterias elas viram regras operacionais:

| # | Lição | Regra operacional da bateria |
|---|-------|------------------------------|
| 1 | **A ADR afirma; o DADO decide.** | Toda afirmação do inventário (§2) é hipótese falseável. O veredito de cada passo vem de OBSERVAÇÃO (SQL read-only / UI / log / arquivo em workspace) — nunca de "o teste unitário cobre" nem de laudo de subagente sobre premissa não observada. Exemplo pago: ADR-0037 §7(8) "anti-loop = freio humano por construção" (falso ao vivo); §5 "todo time nasce com arquiteto" (verdade — mas o executor nasce fora do time). |
| 2 | **Bug mora no CRUZAMENTO entre ADRs.** | Assinatura vista 3× (loop do #33 · vão 0037§7(8)×0030 · bug #1 = gate+no-op+settle): peças corretas isoladamente compondo um buraco. As baterias são agrupadas por FRONTEIRA (§4), não por ADR isolada; cada roteiro declara o par produtor×consumidor que está exercitando. |
| 3 | **O caminho que o fixture nunca pisa é onde o bug vive.** | Cada passo de bateria declara QUEM PRODUZ o insumo no mundo real e testa POR ESSE produtor (proposta nasce do CEO, decisão nasce do dono na UI, issue nasce do `NewIssueDialog` — nunca curl/SQL de escrita/campo digitado à mão). O bug #1 sobreviveu por DOIS desvios de produtor: todo smoke fabricava o agente à mão E digitava a tarefa no `runtime_command` — a issue era enfeite. Evidência registrada que usou produtor desviado está MARCADA no inventário e vira candidata a re-teste na fronteira correspondente. |
| 4 | **Não julgar sistema EM VOO.** | Antes de classificar qualquer resultado: `heartbeat_run` com 0 QUEUED/RUNNING **e** `conversation_turn` com 0 PENDING (SQL read-only). Estado transitório lido como terminal desperdiçou uma investigação inteira na R21-A. Laudo de subagente sobre premissa própria NÃO é evidência; o dado é. |

**Regras de ambiente herdadas (handoff da prova §8 + ledger R21):**
- Backend vivo = **host `java -jar`** (`:8080`), front `:3000` (`next dev`), Postgres
  `agente-orquestrator-db-1`. SQL read-only:
  `docker exec agente-orquestrator-db-1 psql -U orquestrador -d orquestrador -c "…"`.
- **NÃO usar `preview_start {name:"frontend"}`** — o `.claude/launch.json` (tracked) força
  `BACKEND_ORIGIN=:18081` (instância de /verify, morta) = tela cega. Usar
  `preview_start {url:"http://localhost:3000/…"}`.
- Browser pane mente (árvore stale / coordenadas dessincronizadas): agir por JS
  (`focus()`+`.click()`) com guarda de conteúdo antes de cada ação.
- Baterias que ESCREVEM estado (criar issue, decidir gate) rodam em company de teste
  (`prova-v1` ou nova) — a `teams-demo` está neutralizada e **não se reabre** (decisão R21).
- Gates/decisões do dono: o roteiro marca `[DONO]` em todo passo que exige a mão dele na UI.

## 1. Achados PRÉ-FASE — entram no roadmap de ajustes SEM re-descoberta

Registrados na R21-A (`HANDOFF-prova-final-v1.md` §1–§3, §6) e no ledger (rodada 21). O plano
NÃO gasta bateria re-descobrindo o que já tem evidência viva; afirmações de ADR contraditas por
eles aparecem no inventário como **🔴 REFUTADA** citando o achado.

| # | Achado | Gravidade | Fonte | Destino |
|---|--------|-----------|-------|---------|
| P1 | **Bug #1: organização contratada não trabalha e carimba DONE fantasma.** Gate contrata com `runtimeCommand=null` (`ApprovalService:348`; preset sem o campo; `AgentHireService:73` sem fallback) → adapters tratam comando vazio como no-op VERDE (`ClaudeCodeAdapter:69`/`CodexAdapter:77`/`ProcessAgentAdapter:38`) → `settleAfterRun:172-179` → DONE. Run medido: 58,6ms, zero motor. Causa-forma: o caminho da ISSUE não tem compositor de prompt (o do turno tem). | 🔴 grave | prova §1–§2; run `e67ff7dd` | **R22-A EM VOO** (conserto grelhado §4 — 4 decisões do dono, não reabrir) |
| P2 | **Cascata: a estratégia não chega em quem trabalha.** `estrategia.md` mora no workspace da CONVERSA do CEO; run de issue usa workspace agente+issue; endereço só existe no texto do sentinela (`PersonaContinuationSentinel:29`), sem coluna. | 🔴 grave | prova §3 | R22-A (decisões 3 e 4: compositor carrega estratégia; **V30** anota o endereço) |
| P3 | **Vão ADR-0037 §7(8) × ADR-0030:** "anti-loop = freio humano por construção" é FALSO — o para-e-avisa esperava o dono e a continuação de contexto retomou sem ele (re-triagem + aviso duplicado, run real queimado). `parkedForHuman` (#33) só pega KICKBACK; para-e-avisa não é kickback; idempotência `[gatilho <turnoId>]` não pega porque re-emissão vem de turno novo. | 🟠 | prova §6.1 | roadmap de ajustes (item próprio; mesma família do #33) |
| P4 | **`HIRE_AGENT` não carrega `teamId`** (`ApprovalService:336-353`): o gate cria o time e contrata o executor que o time foi desenhado para ter — e o executor nasce FORA (medido: Pesquisador `team_id NULL`). Só organograma manual conserta. | 🟠 | prova §6.2 | roadmap de ajustes |
| P5 | **`FILTRO_TIPOS` stale** (`frontend/src/lib/approvals.ts:12-22`): 3 de 7 ações do gate; a fila lista card em que não age e a dica aponta filtro inexistente. Não-bloqueador (o `ApprovalThreadCard` decide individualmente). | 🟡 | prova §6.3 | roadmap de ajustes |
| P6 | **"você" chumbado em turno do SISTEMA** (`issue-conversa-section.tsx:44`), com UUID cru visível ao dono — nos 2 lugares (detalhe da issue e chat). | 🟡 | prova §6.4 | roadmap de ajustes |
| P7 | Cosmético: `..` duplicado no texto do para-e-avisa. | ⚪ | prova §6.5 | roadmap de ajustes (carona) |
| P8 | **Aprovação em LOTE não dá feedback na UI** — o ato commita (7 Decisões no banco, SQL confirmou) e a tela "não faz nada"; um dono real re-clicaria ou daria o lote por perdido. Defeito de UX do gate da ADR-0035 (o fix #29 está correto no dado). | 🟠 | ledger R21 (herdada do P1 da prova) | roadmap de ajustes |
| P9 | **Sujeira da fixture `prova-v1`** (não neutralizar — vira massa de teste): issue #1 DONE incorreta · Pesquisador sem time · approval `18db8641` REJECTED. | ℹ️ | prova §7.3 / ledger R21 | massa da 1ª bateria pós-fix (reabrir/re-executar a #1) |
| P10 | **Drift documental herdado:** `roadmap:184` "ZERO fatia de código restante" duplamente falso; `roadmap:92` cita `IssueService:108` mas o `tryBlock` mora na `:182`; "park-for-human" sobrecarregado (ADR-0011 × #33); falta linha do #33. | 🟡 | prova §7.4 / ledger R21 | carona docs da R22-A (já no kickoff dela) |

**As 15 candidatas v1.1 acumuladas** (`docs/roadmap-interno.md` §Candidatas, linhas 219–241) são
hipóteses PRÉ-EXISTENTES de lacuna conhecida — não entram como "bug a descobrir", mas as baterias
podem colher evidência que promova ou mate cada uma (ex.: #12 paridade Docker, #11 teto (b) da
ADR-0018). Referenciadas no §6 (roadmap de ajustes) como fila própria.
