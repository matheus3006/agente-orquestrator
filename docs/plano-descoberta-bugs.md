# Plano de caça — fase de DESCOBERTA DE BUGS

> **Contrato:** UNTRACKED de propósito (ler-sempre-commitar-nunca; o destino final é do fecho da
> fase). Produto da faixa **R22-B** (rodada 22 do ledger `docs/controle-paralelismo.md`).
> **Montado:** 2026-07-17. **Base:** `main` **`4c1b35f`** (PRs #1–#34), piso **750 back / 294 front**.
> **Escopo verbatim da fase (dono, 2026-07-16):** *"essa proxima fase sera de descovery de bugs,
> voce ira planejar e executar todos os testes das aplicacao usando o que esta descrito como
> funcionalidade nas ADRs e comprovar o que esta funcionando e o que nao esta, apartir dai
> criaremos um roadmap de ajustes"*.
>
> Este documento **PLANEJA**; as baterias são faixas próprias, emitidas pela sessão de controle a
> partir daqui. Ele não executa bateria nenhuma e não é evidência de nada — é o mapa.

---

## 0. Método da fase (as 4 lições da R21-A, operacionalizadas como regras de bateria)

As lições custaram ~4h de prova que acharam 1 bug grave + 4 achados que o piso verde não viu
(fonte: `docs/handoffs/HANDOFF-prova-final-v1.md` §10). Nas baterias elas viram regras operacionais:

| # | Lição | Regra operacional da bateria |
|---|-------|------------------------------|
| 1 | **A ADR afirma; o DADO decide.** | Toda afirmação do inventário (§3) é hipótese falseável. O veredito de cada passo vem de OBSERVAÇÃO (SQL read-only / UI / log / arquivo em workspace) — nunca de "o teste unitário cobre" nem de laudo de subagente sobre premissa não observada. Exemplo pago: ADR-0037 §7(8) "anti-loop = freio humano por construção" (falso ao vivo); §5 "todo time nasce com arquiteto" (verdade — mas o executor nasce fora do time). |
| 2 | **Bug mora no CRUZAMENTO entre ADRs.** | Assinatura vista 3× (loop do #33 · vão 0037§7(8)×0030 · bug #1 = gate+no-op+settle): peças corretas isoladamente compondo um buraco. As baterias são agrupadas por FRONTEIRA (§5), não por ADR isolada; cada roteiro declara o par produtor×consumidor que exercita. |
| 3 | **O caminho que o fixture nunca pisa é onde o bug vive.** | Cada passo declara QUEM PRODUZ o insumo no mundo real e testa POR ESSE produtor (proposta nasce do CEO, decisão nasce do dono na UI, issue nasce do `NewIssueDialog` — nunca curl/SQL de escrita/campo digitado à mão). O bug #1 sobreviveu por DOIS desvios de produtor: todo smoke fabricava o agente à mão E digitava a tarefa no `runtime_command` — a issue era enfeite. Evidência que usou produtor desviado está MARCADA no inventário e vira candidata a re-teste. |
| 4 | **Não julgar sistema EM VOO.** | Antes de classificar qualquer resultado: `heartbeat_run` com 0 QUEUED/RUNNING **e** `conversation_turn` com 0 PENDING (SQL read-only). Estado transitório lido como terminal desperdiçou uma investigação inteira na R21-A. Laudo de subagente sobre premissa própria NÃO é evidência; o dado é. |

**Regras de ambiente (herdadas do handoff da prova §8 + ledger R21):**
- Backend vivo = **host `java -jar`** (`:8080`), front `:3000` (`next dev`), Postgres
  `agente-orquestrator-db-1`. SQL read-only:
  `docker exec agente-orquestrator-db-1 psql -U orquestrador -d orquestrador -c "…"`.
- **NÃO usar `preview_start {name:"frontend"}`** — o `.claude/launch.json` (tracked) força
  `BACKEND_ORIGIN=:18081` (instância de `/verify`, morta) = tela cega. Usar
  `preview_start {url:"http://localhost:3000/…"}`. (Já é o item 8 do roadmap de ajustes.)
- Browser pane mente (árvore stale / coordenadas dessincronizadas): agir por JS
  (`focus()`+`.click()`) com guarda de conteúdo antes de cada ação.
- Baterias que ESCREVEM estado rodam em company de teste (`prova-v1` ou nova) — a `teams-demo`
  está neutralizada e **não se reabre** (decisão R21).
- Gates/decisões do dono: o roteiro marca `[DONO]` em todo passo que exige a mão dele na UI.

---

## 1. Estado do mundo — o que mudou entre o kickoff desta faixa e a montagem deste plano

Esta faixa foi emitida quando a **R22-A (conserto do bug #1) estava EM VOO**. Ela **mergeou no
meio da pesquisa** — PR #34, `main` = **`4c1b35f`**, **2026-07-17 01:06:22 -03**. Isso mudou a
premissa central do inventário (134 afirmações estavam classificadas "⛔ bloqueado até o fix") e
obrigou a reconciliação que este documento incorpora. Registro honesto, não escondido: **nenhuma
das afirmações foi inventada** — a base ficou desatualizada por fatia concorrente, que é
exatamente o tipo de coisa que esta fase existe para não deixar passar batido.

**Três fatos que valem para todo o resto do documento:**

1. **O conserto está na `main`, não no ar.** O `java -jar` do `:8080` (PID 46677) subiu em
   **2026-07-16 10:29**, do jar da `83c5fe9` — **~15h ANTES do merge**. O ambiente vivo serve o
   binário **PRÉ-fix**. O reboot é do **operador humano** (o harness barra `kill` do `:8080`) e é
   **pré-requisito da Bateria 1b** (§6), já registrado no ledger.
2. **A contaminação do material foi medida e é exatamente 4 ADRs.** Os fragmentos foram extraídos
   lendo o checkout compartilhado enquanto ele estava na branch `feat/backend-spring-ai-2`
   (Frente E, WIP, LOCAL-only). Auditoria: `git diff main feat/backend-spring-ai-2 -- docs/adr/`
   toca **só `0001` e `0043`**; `git diff 83c5fe9 main -- docs/adr/` toca **só `0004`, `0011`,
   `0037`**. A união — **{0001, 0004, 0011, 0037}** — é precisamente o conjunto re-extraído nesta
   reconciliação. **As outras 38 ADRs são byte-idênticas entre branch e main**: ler do working
   tree na branch dava o mesmo texto e as mesmas linhas. O material está seguro no eixo
   texto-de-ADR. (Ressalva no eixo CÓDIGO: §8.)
3. **O `#34` tem raio pequeno e conhecido** (`git diff --stat 83c5fe9 4c1b35f`, 22 arquivos):
   `IssuePromptComposer` (novo) · `AdapterRunExecutor` · `RunOutcome` · `AgentAdapter` + os 3
   adapters · `IssueService` · `Conversation` · `PersonaContinuationPoster` · `V30` · testes ·
   ADRs 0004/0011/0037 · roadmap. **Não toca `frontend/`, nem `ApprovalService`, nem busca de
   skill, nem `activity_log`, nem hooks.** É por isso que a Bateria 1 (§6) pode rodar **hoje, no
   jar pré-fix**, sem que o reboot invalide o resultado.

**O conserto, em uma frase cada** (ADRs 0037/0011/0004 emendadas na main):
- **Compositor da issue** (`IssuePromptComposer`): todo run de ISSUE recebe `#N` + título +
  descrição + a ESTRATÉGIA do Planejador pelo seam T.3. Sem estratégia → só a issue, nunca null.
- **Trava no-op** (`Result.okNoop` → `RunOutcome.noop` → `BLOCKED` no settle): run que não invocou
  o motor nunca completa Issue; vai a FAILED legível e a issue estaciona em BLOCKED.
- **`runtime_command` = só script do arquétipo `process`**; adapter rico não o lê mais. **V30**:
  `conversation.bastao_path` (endereço da estratégia, last-wins).

---

## 2. Achados PRÉ-FASE — entram no roadmap SEM re-descoberta

Registrados na R21-A (`docs/handoffs/HANDOFF-prova-final-v1.md` §1–§3, §6) e no ledger (rodada
21). O plano NÃO gasta bateria re-descobrindo o que já tem evidência viva; afirmações de ADR
contraditas por eles aparecem no inventário como **🔴 REFUTADA** citando o achado.

| # | Achado | Grav. | Destino |
|---|--------|-------|---------|
| P1 | **Bug #1: organização contratada não trabalha e carimba DONE fantasma.** Gate contrata com `runtimeCommand=null` → adapters tratam comando vazio como no-op VERDE → `settleAfterRun` → DONE. Run medido: 58,6ms, zero motor. | 🔴 | **CONSERTADO — PR #34 merged** (roadmap item 1). Re-execução viva = Bateria 1b. |
| P2 | **Cascata: a estratégia não chega em quem trabalha.** `estrategia.md` mora no workspace da CONVERSA do CEO; run de issue usa workspace agente+issue; endereço só existia no texto do sentinela. | 🔴 | **PARCIALMENTE consertado** — ver **N1** (§4): o conserto só alcança a issue nascida no board. |
| P3 | **Vão ADR-0037 §7(8) × ADR-0030:** "anti-loop = freio humano por construção" é FALSO — a continuação de contexto retomou o para-e-avisa sem o dono. | 🟠 | roadmap item 2 (💡 A GRELHAR) |
| P4 | **`HIRE_AGENT` não carrega `teamId`** (`ApprovalService:336-353`): o gate cria o time e o executor nasce FORA dele. | 🟠 | roadmap item 3. **Ver N6** — a mesma linha perde 3 campos. |
| P5 | **`FILTRO_TIPOS` stale** (`frontend/src/lib/approvals.ts:12-22`): 3 de 7 ações do gate. | 🟡 | roadmap item 4 |
| P6 | **"você" chumbado em turno do SISTEMA** (`issue-conversa-section.tsx:44`), com UUID cru visível ao dono. | 🟡 | roadmap item 5 |
| P7 | Cosmético: `..` duplicado no texto do para-e-avisa. | ⚪ | roadmap item 6 |
| P8 | **Aprovação em LOTE não dá feedback na UI** — o ato commita (7 Decisões no banco) e a tela "não faz nada". | 🟠 | roadmap item 7 |
| P9 | **Sujeira da fixture `prova-v1`** (não neutralizar — vira massa de teste): issue #1 DONE incorreta · Pesquisador sem time · approval `18db8641` REJECTED. | ℹ️ | massa da Bateria 1b |
| P10 | **Drift documental herdado:** `roadmap:184` "ZERO fatia de código restante" duplamente falso; "park-for-human" sobrecarregado (ADR-0011 × #33). | 🟡 | carona docs. **A família cresceu:** ver N8/N9/N13. |

**Reconciliação com o roadmap real (pendência 7 do handoff — RESOLVIDA aqui):** a seção "Roadmap
de ajustes" **já existe** em `docs/roadmap-interno.md` (linhas ~223–245), aberta pela R22-A, com
**10 itens numerados**. O mapeamento é: P1→#1 · P3→#2 · P4→#3 · P5→#4 · P6→#5 · P7→#6 · P8→#7 ·
(`launch.json`)→#8 · (candidata v1.1 #14)→#9 · (candidata v1.1 #15)→#10. P2 está absorvido no #1;
P9 na nota "Massa de teste pós-merge"; P10 é carona docs. **Consequência para as baterias: achado
novo entra como item #11+ — a numeração é a do roadmap, não a deste plano.** O carimbo do item 1
(hoje `✅ EM PR — R22-A`, defasado: o PR #34 mergeou) é **carona da R23-A em voo** — não é desta
faixa e está anotado como coberto.

**As 15 candidatas v1.1** (`docs/roadmap-interno.md` §Candidatas, linhas ~219–241) são hipóteses
PRÉ-EXISTENTES de lacuna conhecida — não entram como "bug a descobrir", mas as baterias podem
colher evidência que promova ou mate cada uma (ex.: #12 paridade Docker, que **N6 já promove**).

---

## 3. Inventário condensado — 1085 afirmações falseáveis, 42 ADRs

Cada ADR (0001–0042, com emendas/revisões) foi varrida linha a linha; toda afirmação de
funcionalidade/invariante virou hipótese falseável com âncora `:linha`, produtor real do insumo e
cruzamentos. **39 das 42 passaram por verificação adversarial** (2ª passada cética que corrige
âncoras/estados/cobertura); 3 não (§8).

**Os 6 estados**, nesta ordem de prioridade:

| Estado | Significado |
|---|---|
| **🔴 REFUTADA** | Contradita por achado pré-fase (P1–P10) ou por inspeção de código conclusiva. |
| **✅ PROVADA** | Cita item do dossiê de evidências com o MESMO mecanismo (rebaixa se fatia posterior tocou o mecanismo). |
| **🎯 AGORA** | Testável ao vivo hoje, produtor real, sem executor-em-issue. |
| **🎯 AGORA (pós-reboot)** | **Estado novo desta reconciliação.** Era ⛔ BUG#1; o `#34` removeu o bloqueio **no código da main**, mas o `:8080` serve o jar pré-fix → testável assim que o operador reiniciar. |
| **🚫 NÃO-TESTÁVEL** | Diz por quê e o que cobriria. |
| **❓ N/C** | Não classificável — nunca silenciar por incerteza. |

**⛔ BUG#1 (o 7º estado) está EXTINTO**: as 134 afirmações foram julgadas uma a uma (§8 registra
como). Regra aplicada, **não automática**: só virou `🎯 AGORA (pós-reboot)` quem tinha o bug #1
como ÚNICO bloqueio; quem exige mais foi para `🚫`/`🎯`/`❓` com o motivo real escrito.

| ADR | N | ✅ | 🔴 | 🎯 | 🎯 pós-reboot | 🚫 | ❓ |
|---|---|---|---|---|---|---|---|
| 0001 | 21 | 2 | 0 | 12 | 0 | 7 | 0 |
| 0002 | 16 | 8 | 0 | 7 | 0 | 1 | 0 |
| 0003 | 28 | 12 | 0 | 9 | 5 | 2 | 0 |
| 0004 | **38** | 4 | 1 | 11 | **11** | 11 | 0 |
| 0005 | 19 | 7 | 0 | 5 | 0 | 7 | 0 |
| 0006 | 21 | 4 | 0 | 4 | 4 | 9 | 0 |
| 0007 | 21 | 4 | 0 | 14 | 2 | 1 | 0 |
| 0008 | 10 | 2 | 0 | 5 | 1 | 1 | 1 |
| 0009 | 18 | 2 | 1 | 9 | 1 | 5 | 0 |
| 0010 | 11 | 1 | 0 | 1 | 0 | 9 | 0 |
| 0011 | **26** | 6 | 0 | 3 | **12** | 5 | 0 |
| 0012 | 26 | 9 | 1 | 6 | 8 | 2 | 0 |
| 0013 | 37 | 0 | 1 | 23 | 1 | 12 | 0 |
| 0014 | 28 | 6 | 1 | 15 | 1 | 5 | 0 |
| 0015 | 16 | 2 | 1 | 6 | 1 | 3 | 3 |
| 0016 | 36 | 1 | 0 | 22 | 1 | 12 | 0 |
| 0017 | 31 | 4 | 2 | 22 | 1 | 2 | 0 |
| 0018 | 57 | 27 | 1 | 11 | 8 | 7 | 3 |
| 0019 | 32 | 17 | 0 | 10 | 1 | 4 | 0 |
| 0020 | 27 | 13 | 0 | 8 | 3 | 2 | 1 |
| 0021 | 25 | 2 | 0 | 8 | 14 | 1 | 0 |
| 0022 | 19 | 3 | 0 | 7 | 3 | 6 | 0 |
| 0023 | 39 | 0 | 0 | 7 | **26** | 6 | 0 |
| 0024 | 17 | 7 | 0 | 3 | 3 | 4 | 0 |
| 0025 | 17 | 7 | 0 | 1 | 1 | 8 | 0 |
| 0026 | 24 | 8 | 0 | 6 | 0 | 10 | 0 |
| 0027 | 38 | 13 | 0 | 8 | 7 | 10 | 0 |
| 0028 | 27 | 9 | 0 | 9 | 1 | 8 | 0 |
| 0029 | 19 | 10 | 0 | 5 | 0 | 4 | 0 |
| 0030 | 28 | 7 | 2 | 12 | 1 | 6 | 0 |
| 0031 | 43 | 25 | 1 | 12 | 1 | 4 | 0 |
| 0032 | 20 | 6 | 0 | 9 | 3 | 2 | 0 |
| 0033 | 19 | 7 | 1 | 10 | 0 | 1 | 0 |
| 0034 | 25 | 9 | 1 | 11 | 1 | 3 | 0 |
| 0035 | 27 | 12 | 1 | 11 | 1 | 2 | 0 |
| 0036 | 16 | 2 | 0 | 10 | 1 | 3 | 0 |
| 0037 | **46** | 11 | 3 | 16 | **13** | 3 | 0 |
| 0038 | 18 | 5 | 0 | 5 | 2 | 6 | 0 |
| 0039 | 27 | 11 | 0 | 10 | 1 | 5 | 0 |
| 0040 | 33 | 12 | 0 | 15 | 1 | 5 | 0 |
| 0041 | 17 | 9 | 0 | 3 | 0 | 5 | 0 |
| 0042 | 22 | 0 | 0 | 15 | 1 | 6 | 0 |
| **TOTAL** | **1085** | **306** | **18** | **396** | **142** | **215** | **8** |

> **[Correção do controle, 2026-07-17 — re-validação adversarial da sessão de controle #3]:** três
> ajustes sobre a tabela da R22-B: (1) typo 0037×pós-reboot **12→13** (o fragmento `adr-0037.md:125`
> declara e contém 13 — corrigido, a linha fecha em 46); (2) typo TOTAL×🔴 **16→17** (as 42 linhas
> somam 17; o handoff R22-B registra "🔴 19→17"); (3) **`0012-A4` rebaixada ✅→🎯 pós-reboot** por
> decisão do controle (N12: a evidência única era o dossiê item 7, que documentava o próprio bug —
> re-testar na B1b). Com esses ajustes os totais fechavam em 301✅/17🔴/398🎯/146pós/215🚫/8❓ = 1085.
>
> **[Atualização do controle, 2026-07-17 (mais tarde) — Bateria 1 / R25-A aplicada]:** 6
> reclassificações medidas ao vivo e RECONTADAS nos fragmentos (a conta do handoff da faixa, 305/397,
> estava errada em 1 — erro auto-compensado que soma 1085 mesmo assim; a recontagem decide):
> 0037-A9 🎯→✅ [B1.1] · 0009-A10 pós→🔴 [B1.5] · 0009-A11 pós→✅ [B1.5] · 0009-A14 🎯→✅ [B1.3] ·
> 0012-A12a pós→✅ [B1.5] · 0012-A12b pós→✅ [B1.5]. **Totais VIGENTES: 306+18+396+142+215+8 =
> 1085** ✓ (linhas 0009/0012/0037 e rodapés dos fragmentos atualizados juntos).

**Como ler:** **396 🎯 AGORA** = o volume de caça disponível hoje, sem depender de ninguém.
**142 🎯 (pós-reboot)** = destravam com **uma sentada do operador** — é a maior alavanca isolada
do plano (13% do inventário por um restart). **306 ✅** = já provado ao vivo, não se re-testa.
*(Nota do controle: o reboot ACONTECEU em 2026-07-17 13:47, direto na `fef6073` — decisão do dono,
um-reboot-só; as "pós-reboot" viraram testáveis assim que o processo estiver de pé.)*
**215 🚫** = fora de alcance, com o motivo escrito em cada um.

**As 4 ADRs em negrito ganharam afirmações** nesta reconciliação (+18: 0037 +5, 0004 +7, 0011 +6):
o texto das emendas do `#34` é matéria-prima nova, e traz promessas fortes que ninguém observou
ainda ("sem estratégia → só a issue, **nunca null**" · "adapter rico **nunca mais** lê
`runtime_command`" · "fora de Issue o no-op **segue verde**" · "`bastao_path` **last-wins**").

---

## 4. Achados NOVOS desta reconciliação (não são bateria — já nascem com evidência de código)

A reconciliação foi feita lendo a `main` por `git show`, não o working tree. No caminho, os
verificadores levantaram **13 coisas que ninguém tinha visto** — a maioria por cruzar o texto do
conserto novo com o código que ele não tocou. **12 sobreviveram** à conferência que fiz contra a
`4c1b35f`; **a N2 não** (fica na tabela, riscada, para não ser re-levantada — e porque o registro
de uma hipótese morta é parte do método). **Nenhum destes é um veredito de bateria** (o dado vivo
ainda não falou), mas todos têm âncora de código conferida.

| # | Achado | Grav. | Fronteira | Onde vai |
|---|--------|-------|-----------|----------|
| **N1** | **A ESTRATÉGIA só chega na issue nascida no board — a do GATE e a corretiva degradam.** `ancorarConversa` tem **um único chamador** (`AbsorcaoDeIssueRouter:97` — o outro hit é a própria declaração, `Issue.java:132`); `IssueService.createAtribuida:122-131` (a porta do gate) não ancora conversa; `IssuePromptComposer:75-76` retorna cedo com `conversationId == null`. O briefing `#N`+título+descrição vai sempre — só a ESTRATÉGIA degrada. **Nuance que a bateria tem de resolver:** o comentário do próprio código (`:76`) chama isso de *"o trilho V27 honesto — só a issue"*, e a emenda **sanciona** "sem estratégia → só a issue, nunca null". Logo o dano **não** é automático: só existe se a issue do gate tiver, a montante, uma conversa **com** estratégia que simplesmente não a alcança. Se tiver, o P2 sobrevive na porta mais automatizada, **e em silêncio** (o "anti-silêncio" da emenda só cobre estratégia *ilegível*, não *não-ancorada*). | 🔴 **se confirmado** | 0037×0034×0018×0011 | **C1** (§5) · roadmap #11 |
| **N2** | **~~A trava no-op tem uma porta que a fura~~ — REFUTADO por inspeção nesta própria montagem.** A hipótese era: `IssueService.complete():155-157` chama o settle com `RunOutcome.success()` HARDCODED ⇒ a trava nunca veria um no-op por ali. **Não se sustenta:** o único chamador é `IssueController:83`, um endpoint `requireAgent` que **o agente chama durante o próprio run** — e um run no-op é, por definição, aquele em que **o motor nunca foi invocado**, logo não há agente para chamar o endpoint. O caminho é inalcançável a partir do no-op. Sobra a observação estrutural (verdadeira, sem dano conhecido): DONE tem mais de um writer, e este fabrica o outcome em vez de observá-lo — mas o Javadoc `:150-153` mostra que é deliberado e o risco continua gateado (`:179`). **Fica registrado para não ser re-levantado.** | — | 0012×0011 | §8 (item 11) |
| **N3** | **O `#34` não faz o agente do gate trabalhar — faz ele falhar honesto.** `Agent.adapterType` default = `"process"` (`org/agent/Agent.java:36`) + o gate passa `runtimeCommand=null` (`ApprovalService:348`) ⇒ hire **sem `adapterType`** segue MUDO, só que agora dá **BLOCKED legível** em vez de DONE fantasma. **Fork que a bateria decide:** `adapterType` **pode** chegar — `:347` é `preferNonBlank(doDono.adapterType(), textOrNull(alvo, "adapterType"))`, ou seja, vem do override do dono **ou** do alvo da proposta do CEO. A pergunta viva é: **a proposta do CEO carrega `adapterType` na prática?** Se não carrega, a organização contratada continua não trabalhando — só que agora avisa. | 🔴 **se confirmado** | 0018×0004×0011×0037 | **C2** (§5) · roadmap #12 |
| **N4** | **Nenhum código cria Issue a partir de run de ISSUE — o arquiteto empregado não tem canal.** `ProposalScanner` só é chamado do `ConversationTurnCoordinator:156,169` (galho TURNO); turno só existe para `role='ceo'` (`ConversationService:66-70`); do run de issue sai só `SkillRequestGate` (`AdapterRunExecutor:446-449`). A mesma ausência **refuta** 0018-A20 / 0017-A14 ("arquiteto é a FONTE das tasks") e **confirma** 0018-A35 / 0031-A33. | 🔴 | 0018×0017×0031 | **C4** (§5) · roadmap #13 |
| **N5** | **Breaker (0018) × BLOCKED (0011): deadlock silencioso.** Toda falha pós-checkout manda a issue a BLOCKED — o guard é `IssueService:177` (`outcome.succeeded() && !outcome.noop()`), e todo o resto cai no `tryBlock` de `:185` ⇒ os "3 FAILED consecutivos do par" só acumulam com **2 REOPENs do dono**; ao 3º, o par cortado deixa a issue **OPEN eterna, sem run e sem aviso** (só o wakeup manual do root fura). Atalho que acumula sozinho: falha **antes** do checkout → `tryBlock` casa 0 linhas. | 🟠 | 0018×0011×0012 | **C5** (§5) · roadmap #14 |
| **N6** | **O v1 não tem produtor real para `executionTarget=DOCKER` — em lugar nenhum.** Provado em 5 pontos: default `LOCAL` (`org/agent/Agent.java:42`) · gate passa `null` nas DUAS portas (`ApprovalService:348` HIRE_AGENT, `:405` CREATE_TEAM) · UI chumba `"LOCAL"` (`hire-agent-dialog.tsx:69`, literal, sem campo) · `PATCH /agents/{id}` só troca adapterType/model/effort · `git grep '"DOCKER"' -- backend/src/main` = **zero hits**. Existe endpoint (`POST /agents` aceita o campo), **não existe produtor**. **Precisão (conferida no `HireCommand`, `AgentHireService:139-141` = `name, role, reportsToId, adapterType, runtimeCommand, executionTarget, model, effort, …`):** o `null, null` de `:348` são **`runtimeCommand` e `executionTarget`**; o **`teamId` do P4 nem existe como campo do `HireCommand`** — é a raiz do P4, não um null. | 🟠 | transversal (0016/0023/0025/0026/0027/0041/0042) | roadmap #15 (irmã do P4; promove candidata v1.1 #12) |
| **N7** | **O no-op atravessa o `succeeded()` do pedido de skill.** `AdapterRunExecutor:446` gateia o `skillRequestGate.handle(...)` em `result.succeeded()` — TRUE para `okNoop` ⇒ run no-op sobre Issue ainda chama o gate, com texto vazio. É o **único** ponto pós-`#34` em que o no-op cruza um `succeeded()` sem a guarda `!noop` (o gate do preview usa o `outcome`, já protegido). Inócuo hoje. | ⚪ | 0004×0003×0011 | roadmap #16 (carona) |
| **N8** | **Drift de FORMA no `activity_log`.** O writer grava as chaves em **camelCase**; a ADR-0009:28-29 promete `detail.review_id`/`detail.run_id`. **Confirmado direto no código durante esta montagem:** `IssueService:193` grava `Map.of("runId", runId)` no `ISSUE_BLOCKED` — e o mesmo padrão está em `:217`/`:286` (review). Mesma classe do `requestedByAgentId`, mas a Nota de reconciliação de 07-14 só reconciliou o lado da ação delegada; o lado da review/bloqueio ficou de fora. | 🟡 | 0009×0012 | **B1.5** (§6) → roadmap se confirmar |
| **N9** | **A letra "env vazio" (0018:30) morreu e a 0018 não foi emendada.** `AdapterRunExecutor:243` (`env = prompt != null ? Map.of(PROMPT_ENV, prompt) : Map.of()`) injeta `ORQ_PROMPT` em **todo** run com prompt — e o run de issue **sempre** tem prompt agora. Somando `LocalExecutionTarget:374` (`putAll` sobre o env do host), "env vazio" já era falso no LOCAL. A R22-A emendou 0037/0011/0004, **não a 0018**. O espírito (selado / sem inbound) segue de pé. | 🟡 | 0018×0004×0016 | roadmap #17 (emenda documental) |
| **N10** | **O registro "run X declarou comando Y porta Z" não existe no review gate.** A ADR-0023 §2 (:28) o promete; `ReviewServerEmbed` (`preview-server-section.tsx:177-191`) monta só aviso + `ServerControls`, **que nem recebe command/port**. A linha `$ {command} :{port}` existe SÓ na `IssueServerSection:138-140`. | 🟠 | 0023×0013 | 🔴 esperada na Bateria 1b |
| **N11** | **Diagnóstico errado no checkout.** `AdapterRunExecutor:213` diz *"checkout da issue falhou (ja em execucao por outro run)"* **também** quando a causa é issue fechada. | ⚪ | 0005×0012 | roadmap #18 (carona) |
| **N12** | **O dossiê documentava o BUG como se fosse prova.** O item 7 ("run fantasma settlou DONE") era a evidência de `0012-A4` (✅ PROVADA) e de `0011-A1` — mas pós-`#34` aquele mesmo cenário dá **BLOCKED**. O `adr-0011.md` já removeu o item 7 de A1 (sobrou o #30 [MISTO] sustentando); **`0012-A4` não tem segundo item ⇒ PROVADA órfã**, candidata a rebaixamento. | 🟡 | integridade do inventário | §8 · decisão do controle |
| **N13** | **A ADR-0004 quebrou as próprias âncoras.** As notas `:87`/`:98` citam `AdapterRunExecutor.java:339,356` — linhas que o **próprio `#34`** deslocou (→ `:377`/`:394`). E a nota `:56-58` afirma que o `Result` tem **4 campos**; na main são **6** (`+sessionLost` desde o PR #16, `+noop` desde o #34) — defasada **antes** do #34. | 🟡 | família P10 | roadmap #17 (junto com N9) |

> **Por que isto vale mais que uma bateria:** N1/N3/N4/N5 são hipóteses de bug **no conserto que
> acabou de entrar** e no código que ele deliberadamente não tocou. Foram encontradas em horas, de
> graça, por cruzar texto novo com código velho — a lição 2 do método (*o bug mora no cruzamento*)
> se pagando de novo, sem gastar um run sequer.
>
> **E a lição 1 se pagou aqui dentro:** os achados chegaram de subagentes, e **conferi cada âncora
> das candidatas no código da main antes de publicar**. Três não passaram: **N2 caiu inteira**
> (hipótese sem caminho de código), **N3 mudou de forma** (o `adapterType` *pode* chegar pelo
> `preferNonBlank` — virou um fork a observar, não um fato) e **N6 teve a contagem corrigida**
> (`:348` perde 2 campos, não 3; o `teamId` do P4 nem é campo). *Laudo de subagente sobre premissa
> própria não é evidência* — vale para os meus também. Se eu não tivesse conferido, este plano
> mandaria uma bateria caçar fantasma em `/complete`.

---

## 5. Priorização por fronteira — as 5 primeiras candidatas

Ordenadas por **(o que uma bateria consegue liquidar mais rápido) × (gravidade se verdadeiro)**.
Cada uma declara o par **produtor × consumidor** que exercita (lição 2) e **quem produz o insumo
no mundo real** (lição 3).

### C1 — A estratégia não chega na issue do gate nem na corretiva `[N1]`
- **Fronteira:** 0037 (compositor/absorção) × 0034 (caso B corretiva) × 0018 (gate cria issue) × 0011.
- **Hipótese falseável, em duas pernas** (a 2ª é o que decide se há bug):
  1. *(estática, quase certa — o código já mostra)* issue criada pelo **gate** ou pela **corretiva
     do caso B** nasce com `conversation_id NULL` ⇒ `IssuePromptComposer:75-76` retorna cedo ⇒
     briefing **sem ESTRATÉGIA**.
  2. *(a que importa)* **existe, a montante dessa issue, uma conversa COM estratégia?** Se existe,
     a estratégia existe e não chega — e **em silêncio**: o "anti-silêncio" da emenda cobre
     estratégia *ilegível*, não *não-ancorada*. Se não existe, o comportamento é o que a emenda
     sanciona ("sem estratégia → só a issue") e **não há bug** — só uma ADR que promete no título
     mais do que entrega no corpo.
- **Por que não é óbvio:** o comentário do código (`IssuePromptComposer:76`) chama o caminho de
  *"o trilho V27 honesto — só a issue"*. Quem escreveu **decidiu** isso. A emenda, no entanto,
  abre com "**todo** run de issue recebe … a ESTRATÉGIA". Tensão texto × código não resolvida.
- **Produtor real:** o dono aprovando um `CREATE_ISSUE` no gate; e o caso B (review REJECT → corretiva).
- **Dano (se a perna 2 fechar):** o P2 — o bug que a R22-A foi consertar — **continua vivo nas duas
  portas mais automatizadas**, e nada no board diz "esta issue foi sem estratégia".
- **Custo: BAIXO. Meia-prova HOJE, sem reboot, read-only:**
  `select seq, conversation_id, created_at from issue order by seq;` na `prova-v1` — fecha a perna
  1 antes de gastar um run. → **Bateria 1, passo B1.1.**

### C2 — A organização contratada trabalha agora? `[N3]`
- **Fronteira:** 0018 (gate contrata) × 0004 (contrato do adapter) × 0011 (trava) × 0037 (compositor).
- **Hipótese falseável:** o agente contratado pelo gate **continua mudo** — não mais por
  `runtimeCommand=null` (que virou correto), mas porque `Agent.adapterType` **default é
  `"process"`** ⇒ arquétipo `process` sem comando ⇒ `okNoop` ⇒ **BLOCKED**. O `#34` trocou "DONE
  fantasma" por "BLOCKED honesto"; a questão é se trocou também por "trabalha".
- **O fork exato a observar:** `ApprovalService:347` é
  `preferNonBlank(doDono.adapterType(), textOrNull(alvo, "adapterType"))` — o `adapterType` **pode**
  vir do override do dono **ou** do alvo da proposta do CEO. Então a pergunta viva não é "o campo
  existe?" (existe), é: **a proposta que o CEO escreve, na prática, carrega `adapterType`?**
- **Produtor real:** o dono aprovando `HIRE_AGENT` no gate (a porta do P1) a partir de uma proposta
  REAL do CEO — não um hire montado à mão (seria o desvio da lição 3, o mesmo que escondeu o bug #1).
- **Dano:** se a proposta não carrega, **o bug #1 está meio-consertado**: o board deixou de mentir
  (o invariante do dono), mas a organização contratada pelo CEO **ainda não trabalha** — a pergunta
  central da fase segue com resposta "não", só que agora audível.
- **Custo: BAIXO pós-reboot.** É a re-execução da **#1 da `prova-v1`** já prevista no ledger.
  → **Bateria 1b, passo B1b.1.**

### C3 — A busca de skill nunca recebeu o conserto que a busca de memória recebeu
- **Fronteira:** 0007 × 0019 × 0003 × 0008.
- **Hipótese falseável:** `memory_search` foi corrigida **ao vivo** para casar por **TERMOS**
  depois que o codex, perguntando como humano, não achou nada com busca por frase exata (dossiê
  itens 19 e 82 — achado real, já pago). A busca de **SKILL** (`/knowall/search` →
  `PgTrgmSkillSearch.searchMetadataTrigram`) **nunca recebeu o mesmo tratamento**: casa por ILIKE
  da **frase inteira** + ranking trigram. ⇒ pergunta em prosa natural cujo termo relevante só
  existe na `description`/`triggers` **não acha a skill que deveria achar**.
- **Produtor real:** o dono buscando na UI do molde (`KnowallCompositor` → `POST /knowall/match`),
  ou o CEO montando um time.
- **Dano:** skill existente e correta fica **invisível** para consulta em prosa — o dono conclui
  "não existe skill pra isso" e cria duplicata, ou o gap fica sem solução porque ninguém acha o que
  já foi resolvido. **É a lição 3 com precedente:** a MESMA classe de bug, já provada e consertada
  de um lado, nunca replicada do outro.
- **Custo: BAIXO, HOJE, sem reboot** (o `#34` não toca busca de skill). → **Bateria 1, passo B1.4.**
- **Por que subiu ao top-5:** ocupou a vaga da hipótese `/complete` (N2), que **não sobreviveu à
  conferência** (§4/§8). Esta tem precedente vivo a favor; aquela não tinha caminho de código.

### C4 — O arquiteto empregado não consegue originar as tasks que a ADR diz que ele origina `[N4]`
- **Fronteira:** 0018 (G-Q-E, arquiteto é a FONTE das tasks) × 0017 × 0031 (personas).
- **Hipótese falseável:** não existe caminho de código para um run de ISSUE criar Issue.
  `ProposalScanner` só roda no galho TURNO; turno só existe para `role='ceo'`; o run de issue só
  varre pedido-de-skill. ⇒ o arquiteto empregado **não decompõe trabalho**.
- **Produtor real:** um arquiteto real, contratado pelo gate, rodando uma issue de decomposição.
- **Dano:** se confirmado, a promessa "o time se organiza sozinho" não existe no v1 — o dono é o
  único decompositor. É achado de **especificação**, não só de código.
- **Custo: MÉDIO pós-reboot.** Registrado como **hipótese a falsear**, não como refutação — o dado
  decide (lição 1). → **Bateria 1b, passo B1b.4.**

### C5 — Breaker × BLOCKED: a issue que fica OPEN para sempre `[N5]`
- **Fronteira:** 0018 (circuit-breaker, teto c) × 0011 (park-for-human) × 0012.
- **Hipótese falseável:** como toda falha pós-checkout parqueia em BLOCKED, os 3 FAILED do par
  agente×issue só acumulam se o dono der 2 REOPENs; ao 3º o breaker corta o par e a issue fica
  **OPEN eterna, sem run e sem aviso**. Pela porta "falha antes do checkout", acumula sozinho.
- **Produtor real:** agente mal-configurado pelo dono (o cenário que a emenda da 0011 diz cobrir) +
  2 REOPENs; ou uma falha pré-checkout repetida.
- **Dano:** a emenda declara que o breaker contar no-ops é "comportamento desejado" — mas o
  desejado, ao vivo, pode ser uma issue que some do fluxo sem ninguém ser avisado.
- **Custo: MÉDIO pós-reboot.** O roteiro precisa **escolher a porta** (pré ou pós-checkout).
  → **Bateria 1b, passo B1b.5.**

### Ranking completo das fronteiras

| # | Fronteira | ADRs | Custo | Reboot? | Bateria |
|---|-----------|------|-------|---------|---------|
| 1 | **C1** — estratégia não chega (gate/corretiva) | 0037×0034×0018×0011 | baixo | **não** | B1.1 |
| 2 | **C2** — a organização contratada trabalha? | 0018×0004×0011×0037 | baixo | sim | B1b.1 |
| 3 | **C3** — busca de skill por **frase inteira** × memória já corrigida por **termos** | 0007×0019×0003×0008 | baixo | **não** | B1.4 |
| 4 | **C4** — arquiteto não origina tasks | 0018×0017×0031 | médio | sim | B1b.4 |
| 5 | **C5** — breaker × BLOCKED (OPEN eterna) | 0018×0011×0012 | médio | sim | B1b.5 |
| 6 | `activity_log` sob **lote**: 1 evento por ação (0009) × 1 Decisão por lote (R18-A) | 0009×0035×0018×0020 | baixo | **não** | B1.3 |
| 7 | Drift de forma no `detail` do `activity_log` (camelCase × snake_case da 0009) `[N8]` | 0009×0012 | baixo | **não** | B1.5 |
| 8 | Flag `--settings` do **ultracode** sobrescreve o arquivo de hooks de segurança | 0014×0042×0016 | médio | **não** | B1.6 |
| 9 | O registro "run X declarou comando Y porta Z" não existe no review gate `[N10]` | 0023×0013 | baixo | sim | B1b |
| 10 | Escritor de workspace de Issue sobrescreve o `CLAUDE.md` que o próprio agente escreveu | 0022×0019×0011×0038 | médio | sim | B1b.6 |
| 11 | Criação concorrente de `#N` pelo par real (dono na UI × CEO no gate) | 0033×0035×0018 | médio | não | B2 |
| 12 | `agent_session` sobrevive à perda REAL do volume (GC) × fallback de sessão | 0002×0026×0038 | alto | não | B2 |
| 13 | Loop de handoff nunca dispara porque a continuação herda a sessão nativa | 0015×0018×0030×0038 | alto | não | B2 |
| 14 | Custo de run que FALHA por exceção nunca chega ao ledger de budget | 0004×0006×0014×0018 | médio | não | B2 |
| 15 | Grill do racional para pedido originado por **AGENTE** (não pelo CEO) | 0006×0020×0035×0003 | médio | sim | B2 |
| 16 | `/complete` como 2º writer de DONE com outcome fabricado `[N2]` | 0012×0011 | — | **REFUTADA por inspeção** (§4) | — |
| 17 | Codex sob **DOCKER** com saída estruturada e preview | 0025×0039×0014×0016×0023 | — | **bloqueada por N6** | — |

> **A fronteira 15 não é bateria: é item de roadmap.** N6 provou que nenhum produtor real cria um
> agente DOCKER. Testá-la exigiria `POST /agents` com o campo na mão = exatamente o desvio de
> produtor da lição 3 — o desvio que deixou o bug #1 passar. **Destrava com uma fatia de
> superfície** (o hire carregando `executionTarget`, irmã do P4), não com uma bateria.

---

## 6. Bateria 1 (hoje, sem reboot) e Bateria 1b (pós-reboot) — roteirizadas

**Passo 0 de TODA bateria (lição 4 — não julgar sistema em voo).** Antes de classificar qualquer
resultado:
```sql
select status, count(*) from heartbeat_run group by status;      -- exige 0 QUEUED e 0 RUNNING
select status, count(*) from conversation_turn group by status;  -- exige 0 PENDING
```

### Bateria 1 — roda AGORA, no jar pré-fix, e o reboot NÃO invalida

Justificativa de que o resultado sobrevive ao reboot: `git diff --stat 83c5fe9 4c1b35f` **não toca
`frontend/`, `ApprovalService`, busca de skill, `activity_log` nem hooks** (§1, fato 3). Os passos
abaixo ou leem DADO (que o jar não muda) ou exercitam código que o `#34` não tocou.

| Passo | Alvo | Ação | Veredito |
|---|---|---|---|
| **B1.1** | **C1 / N1** (o mais valioso do lote) | `select seq, conversation_id, created_at from issue order by seq;` na `prova-v1`. Cruzar com a origem de cada issue (board × gate × corretiva). | `conversation_id NULL` em issue de origem gate/corretiva **prova a perna estática de N1** sem gastar um run. |
| **B1.2** | **N6** (fecha a ressalva) | `select id, name, adapter_type, execution_target, runtime_command, team_id from agent;` | Zero linhas com `execution_target='DOCKER'` ⇒ N6 confirmado e a fronteira 15 sai das baterias. Se houver agente legado DOCKER, **as 6 pernas 🚫 do §3 reabrem** (com produtor [DESVIADO] na origem — marcar). Também mede o P4 (`team_id NULL`) de graça. |
| **B1.3** | **Fronteira 7** | `[DONO]` aprovar um **LOTE** novo no gate (company de teste) → `select action, actor_type, actor_id, entity_id, detail from activity_log where action = 'APPROVAL_APPROVED' order by created_at;` contar `APPROVAL_APPROVED` por `entity_id` (= o approval, `entity_type='approval'`) do lote. *[SQL corrigido pelo controle 2026-07-17 — colunas reais: `action`/`actor_type`/`actor_id`/`entity_type`/`entity_id`; não existem `type`/`actor`/`approval_id`.]* | N eventos (letra da 0009) × fusão (espírito da R18-A). Se sub-conta, o invariante "tudo registrado" tem buraco sistemático **na ferramenta que o dono mais usa**. |
| **B1.4** | **Fronteira 6** | `GET /knowall/search` com pergunta em **prosa natural** cujo termo relevante só existe em `description`/`triggers`, não na frase digitada. Produtor real: o dono buscando na UI do molde (`KnowallCompositor` → `POST /knowall/match`). | Skill existente e correta não é achada ⇒ **mesma classe de bug já provada e consertada do lado da memória** (dossiê 19/82), nunca replicada do lado da skill. |
| **B1.5** | **N8** | `select action, detail from activity_log where action in ('ISSUE_SUBMITTED_FOR_REVIEW','REVIEW_APPROVED','REVIEW_REJECTED','ISSUE_BLOCKED') limit 20;` *[SQL corrigido pelo controle 2026-07-17 — a coluna é `action`, e 'REVIEW_REQUESTED' não existe no dado vivo: o pedido de review é `ISSUE_SUBMITTED_FOR_REVIEW`.]* | Chaves em `camelCase` (`reviewId`/`runId`) ⇒ 🔴 contra ADR-0009:28-29 ⇒ roadmap. |
| **B1.6** | **Fronteira 8** | `[DONO]` criar agente com `effort=ultracode` e rodar um **turno real**; tentar mutar `.claude/skills` (D6) e ler sessão de outro agente (D7). | Se `--settings '{"ultracode":true}'` **sobrescreve** em vez de mesclar `.claude/settings.json`, o agente mais agressivo roda **sem o cinto de segurança** — bypass silencioso. |

**Massa:** `prova-v1` (P9 — sujeira é massa, não se neutraliza). `teams-demo` **não se reabre**.

### Bateria 1b — destravada pela sentada do operador

**Pré-requisito único:** operador reinicia o `:8080` com o jar da **`4c1b35f`** (o processo vivo é
PID 46677, do jar da `83c5fe9`; o harness barra `kill`). O boot aplica a **V30** e a coluna
`conversation.bastao_path` passa a existir. **Isso destrava as afirmações `🎯 (pós-reboot)` — 142
vigentes após a Bateria 1.** *(Nota do controle 2026-07-17: o reboot foi feito direto na `fef6073`,
decisão do dono — V30+V31 aplicadas; o pré-requisito está CUMPRIDO.)*

| Passo | Alvo | Ação | Veredito |
|---|---|---|---|
| **B1b.1** | **C2 / N3** — *a pergunta central da fase* | Re-executar a **#1 da `prova-v1`** (DONE incorreta, P9): `[DONO]` REOPEN → wakeup → observar `heartbeat_run` (duração, steps), o motor e o board. | Run com motor real e trabalho ⇒ o bug #1 morreu. Run **BLOCKED** com erro legível ⇒ **N3 confirmado: meio-conserto** (board honesto, organização ainda muda). Run 58ms/DONE ⇒ o fix não subiu. |
| **B1b.2** | as promessas novas das emendas | Observar o briefing que chega ao motor: contém `#N`+título+descrição? a estratégia? a lacuna é **sinalizada** quando ilegível (anti-silêncio)? | São afirmações **novas** (§3) que ninguém observou: "nunca null", "lacuna sinalizada", "adapter rico nunca mais lê `runtime_command`". |
| **B1b.3** | **N10** | Um ciclo de review real com servidor declarado; olhar o review gate. | Ausência do registro "run X declarou comando Y porta Z" ⇒ 🔴 contra 0023 §2 (:28). Caso a testar de graça na carona: run 1 **não** declara, run 2 declara → o review do run 1 mostra "Rodar app"? (o botão é gateado pela existência de manifest na Issue, **não** pelo run sob revisão). |
| **B1b.4** | **C4 / N4** | Arquiteto real contratado pelo gate rodando issue de decomposição. | Zero Issue criada ⇒ 🔴 contra 0018-A20 / 0017-A14. |
| **B1b.5** | **C5 / N5** | Agente mal-configurado pelo dono + 2 REOPENs (porta pós-checkout) **ou** falha pré-checkout ×3 (porta que acumula sozinha). | Issue OPEN eterna, sem aviso ⇒ 🟠. |
| **B1b.6** | Fronteira 9 | 2+ runs da MESMA issue (o caminho comum: re-run pós-REOPEN) com motor real; observar se o `CLAUDE.md` escrito pelo agente sobrevive. | "preserva" (0022) × "REPLACE incondicional" (0019/0011) nunca convergiram no dado. |

**Armadilhas do roteiro (achadas na reconciliação — respeitar):**
- **`0023-A18` prova o OPOSTO se rodar em LOCAL** (não existe container/`:ro`/rede `orq-preview`).
  6 afirmações da 0023 são DOCKER-only.
- **`tryCheckout` é idempotente para o mesmo par:** guarda por `assignee is null OR assignee =
  :agentId` **e** `execution_lock_run_id is null OR = :runId`. "2 checkouts = 1 sucesso + 1
  conflito" **só vale com 2 agentes distintos** — mesmo agente + mesmo runId dá 2 sucessos, e isso
  **não** é violação de single-assignee. Roteiro com 1 ator = falso-positivo.
- **MCP só abre em LOCAL** (`AdapterRunExecutor:353-354`): `0003-A18/A24/A25` e `0007-A13` exigem
  executor LOCAL — um roteiro DOCKER as mataria em silêncio.
- **A ponte Issue→agente arbitrário é o wakeup**, não o assignee: `POST /agents/{id}/wakeups
  {"issueId": ...}` (`WakeupController:31-40`). `CreateIssueRequest` não tem o campo.
- **`runtime_command` em agente `process` deixou de ser desvio de produtor** (decisão 2 da R22-A
  re-legitimou `process`+script): `acceptsPromptAsCommand()=false` → prompt via `ORQ_PROMPT`.
- **A descrição da issue virou canal de instrução** — as baterias de captura ganham produtor real
  de graça ("publique em `.orq/preview/index.html`"), sem `runtime_command` na mão.

---

## 7. Esqueleto do roadmap de ajustes

**A seção existe** (`docs/roadmap-interno.md`, ~linhas 223–245) com **itens 1–10** (§2). Achado
novo entra como **#11+**, no formato abaixo (espelho do item 1). O carimbo do item 1 (`✅ EM PR` →
MERGED/#34) é **carona da R23-A**.

| # | Achado (1 frase: o que NÃO funciona) | Afirmação violada (âncora ADR:linha, ID do §3) | Onde achou | Evidência (o dado observado) | Grav. | Fronteira | Conserto | Estado |
|---|---|---|---|---|---|---|---|---|
| 1 | Organização contratada não trabalha; issue vira DONE fantasma | 0037 · 0004/0005 | pré-fase (R21-A) | run `e67ff7dd` 58,6ms / zero motor; #1 DONE | 🔴 | gate × adapter × settle | compositor + trava no-op + V30 | **MERGED (PR #34)** — carimbo é carona R23-A |
| 2–10 | *(P3–P8 + launch.json + candidatas #14/#15)* | | | | | | | 💡 A GRELHAR |
| **11** | **A estratégia não chega na issue do gate nem na corretiva** | 0037:119 (A46) · 0034 · 0018 | **N1** (reconciliação R22-B) | `ancorarConversa` 1 chamador (`AbsorcaoDeIssueRouter:97`); `createAtribuida:122-131` não ancora; `IssuePromptComposer:75-76` early-return, comentado como *"trilho V27 honesto"* | 🔴 **se B1.1+perna 2** | 0037×0034×0018×0011 | grill pendente | 💡 **A GRELHAR** (B1.1 mede a perna estática) |
| **12** | **Agente do gate continua mudo (`adapterType` default `process`)** — a menos que a proposta do CEO carregue o campo | 0018 · 0037 emenda | **N3** | `org/agent/Agent.java:36` · `ApprovalService:348` (`null, null`) · fork em `:347` (`preferNonBlank`) | 🔴 **se B1b.1** | 0018×0004×0011 | grill pendente | 💡 A GRELHAR |
| **13** | **Arquiteto empregado não tem canal para originar Issues** | 0018-A20 · 0017-A14 | **N4** | `ProposalScanner` só em `ConversationTurnCoordinator:156,169`; turno só p/ `role='ceo'` | 🔴 | 0018×0017×0031 | grill pendente | 💡 A GRELHAR |
| **14** | **Breaker × BLOCKED: issue pode ficar OPEN eterna sem aviso** | 0018 (teto c) · 0011 emenda | **N5** | `IssueService.settleAfterRun:185`; portas `:153,189,204` | 🟠 | 0018×0011×0012 | grill pendente | 💡 A GRELHAR |
| **15** | **Hire não carrega `executionTarget` — postura DOCKER inalcançável** | 0016/0023/0025/0026/0027/0041 | **N6** | 5 provas (§4); `git grep '"DOCKER"'` = 0 | 🟠 | transversal | **irmã do P4** (mesma linha `ApprovalService:348`) — fatia única de superfície; promove candidata v1.1 #12 | 💡 A GRELHAR |
| **16** | No-op atravessa `succeeded()` no pedido de skill | 0004 emenda · 0003 | **N7** | `AdapterRunExecutor:446` | ⚪ | 0004×0003 | carona | 💡 A GRELHAR |
| **17** | Drift documental: "env vazio" (0018:30) morto · ADR-0004 cita âncoras que o #34 quebrou · "Result tem 4 campos" (são 6) | 0018:30 · 0004:87,:98,:56-58 | **N9 · N13** | `AdapterRunExecutor:243` · `:377`/`:394` · `AgentAdapter:120-121` | 🟡 | família P10 | emenda documental | 💡 A GRELHAR |
| **18** | Diagnóstico errado: "já em execução por outro run" quando a issue está fechada | 0005/0012 | **N11** | `AdapterRunExecutor:213` | ⚪ | — | carona | 💡 A GRELHAR |

**Regras de entrada (do método):**
- **Gravidade:** 🔴 = mente para o dono ou perde trabalho · 🟠 = funcionalidade prometida não
  acontece, com contorno · 🟡 = superfície/UX/registro errado · ⚪ = cosmético.
- Achado SEM afirmação de ADR correspondente = **lacuna de ESPECIFICAÇÃO** — entra mesmo assim,
  com "afirmação violada" = *(não especificado — candidato a ADR/emenda)*. **N4 e N6 são desta
  família.**
- Achado que confirma candidata v1.1 existente referencia o nº dela em vez de duplicar.
- **"Funciona" TAMBÉM se registra:** o passo de bateria que passou vira ✅ no inventário, com a
  citação da bateria — é o que impede re-teste eterno.

---

## 8. Honestidade de cobertura

**O que este plano NÃO garante, dito antes de alguém descobrir:**

1. **3 ADRs não passaram pela verificação adversarial** — `0021`, `0031`, `0035` (a 2ª passada
   estourou 64000 tokens de saída em 2 tentativas). **Decisão do controle: não re-tentar agora**;
   os fragmentos ficam na forma da extração inicial. Mitigação parcial: ao reclassificar, as **14
   linhas de `0021` tiveram as âncoras conferidas contra a main — 14/14 conferem, zero âncora
   quebrada, zero estado absurdo**. Isso é evidência de que a extração inicial é sólida, **não**
   substituto da verificação. → **Entra como bateria própria de revisão** (não bloqueia a caça).
   **[DECIDIDO pelo controle 2026-07-17, re-derivado na re-validação: sem re-verificação agora;
   bateria de revisão própria em fila — veto do dono aberto no grill.]**
2. **Contagem de N/C reconciliada — o número é 8, e o porquê importa.** Havia divergência entre
   duas sínteses (9 × 7). Contando **direto nos fragmentos**: a crítica de completude **contou
   prosa como classificação** — o `adr-0014.md` não tem N/C nenhum (sua nota diz literalmente
   *"nenhuma afirmação vaga o suficiente para exigir ❓ N/C"*, e o marcador na frase foi contado),
   e o `adr-0015.md` tem 3 (a 4ª ocorrência é a nota citando A14 em prosa). Total real pré-
   reconciliação = **7**; com `0018-A22` (novo) = **8**. Distribuição: 0008(1) · 0015(3) ·
   0018(3) · 0020(1). **`0014` tem zero.**
3. **Eixo CÓDIGO tem drift residual — bounded, não zerado.** As 38 ADRs não re-extraídas têm texto
   idêntico (§1), mas a branch da Frente E toca **101 arquivos (88 `.java`)**, e o próprio `#34`
   **deslocou linhas** (`AdapterRunExecutor:339→377` · `:356→394` · `:401-402→413` ·
   `ClaudeCodeAdapter:116→117`). **Citação de linha de CÓDIGO em fragmento não re-extraído pode
   estar defasada** — em especial as que apontam para `AdapterRunExecutor`. Toda bateria deve
   reconferir a âncora de código que for usar (`git show main:<caminho> | cat -n`). Não corrigi em
   massa: seria re-verificar 38 fragmentos para um ganho que a bateria obtém de graça no passo em
   que usa a âncora.
4. **Inconsistência conhecida e NÃO resolvida — o veredito DOCKER.** A mesma pré-condição (agente
   `executionTarget=DOCKER`) recebeu **estados diferentes** em fragmentos diferentes: uns
   `🎯 AGORA [DK]` (porque `POST /agents` aceita o campo e o dossiê item 74 o exercitou ao vivo),
   outros `🚫 NÃO-TESTÁVEL` (porque nenhum produtor **real** cria esse agente — curl com o campo na
   mão é o desvio da lição 3). **Ambas as leituras são defensáveis e a divergência está no
   inventário.** Recomendação (§5, fronteira 15): tratar como **item de roadmap (N6/#16)**, não
   como bateria; se o dono quiser exercitar o galho DOCKER assim mesmo, que seja com produtor
   **[DESVIADO] declarado**. **Decisão do controle/dono — não a tomei.** **[DECIDIDO pelo controle
   2026-07-17, re-derivado na re-validação (N6 re-conferido: `git grep '"DOCKER"' -- backend/src/main`
   = 0 hits em `4c1b35f` E `fef6073`): DOCKER = item #15 do roadmap, NÃO bateria; a inconsistência
   fica ANOTADA no inventário — veto do dono aberto no grill.]**
5. **`🎯 AGORA` que não fecham o roteiro.** Fora do escopo da reclassificação (que só tocou
   `⛔ BUG#1`), há linhas `🎯 AGORA` cujo roteiro pressupõe **turno DOCKER** — que **não tem
   produtor nenhum** (personas nascem `LOCAL`; a conversa nem escolhe agente). Sinalizadas:
   `0022-A4/A5/A15/A18` · `0024-A5/A6` · `0042-A22` · `0032-A10`. Quem roteirizar precisa saber.
6. **Uma ✅ PROVADA órfã (N12):** `0012-A4` tem como única evidência o item 7 do dossiê, que
   documentava o **bug** — pós-`#34` o mesmo cenário dá BLOCKED. O irmão `0011-A1` já o removeu.
   **Candidata a rebaixamento; não a rebaixei** (fora do escopo da reclassificação). Decisão do
   controle. **[EXECUTADO pelo controle 2026-07-17: rebaixada ✅→🎯 pós-reboot no fragmento
   `adr-0012.md` e na tabela §3 — re-testar na B1b.]**
7. **Nenhuma afirmação subiu para ✅ PROVADA nesta reconciliação, por construção.** O
   `dossie-evidencias.txt` (82 itens) **não tem uma única entrada pós-fix** (grep por
   compositor/bastao/ORQ_PROMPT/noop: zero). O `/verify` da R22-A provou em instância descartável
   (`:18082`), **não no vivo** — e "a ADR afirma; o dado decide" vale para o conserto também. As
   `🎯 (pós-reboot)` (142 vigentes) são **promessas de código, não fatos observados**.
8. **Duas outras N/C têm resposta no código e ficaram abertas:** `0020-A24` (o pós-hoc é coberto
   por `fabricaDecisaoDaReview:287,295-300` com `DecisionOrigem.DONO_NO_GATE`) e `0018-A22`
   (não existe entidade `feature`). Não as fechei — não eram `⛔ BUG#1`.
9. **`0014-A24` segue 🔴 REFUTADA embora o `#34` tenha removido a causa** — só a re-execução viva
   decide. E a **premissa "REFUTADA = refutada pelo bug #1" é falsa**: das 4 🔴 da 0037, **só uma**
   (A32) vinha do P1; as outras vêm de P3/P4/P6, que **não andam com o reboot**.
10. **Este plano não observou nada ao vivo.** Contrato da faixa: leitura apenas. Todo veredito aqui
    é sobre **texto de ADR e código**, nunca sobre comportamento — por isso tudo vira bateria.
11. **Os achados do §4 vieram de subagentes e passaram por conferência minha — 3 não sobreviveram.**
    Antes de publicar, reconferi no código da main **toda âncora das candidatas**. Resultado:
    **N2 foi REFUTADA** (a hipótese "`/complete` fura a trava no-op" não tem caminho de código: o
    endpoint é `requireAgent` e o agente só existe se o motor rodou — logo um no-op nunca o
    alcança; ficou registrada em §4 para não ser re-levantada); **N3 foi corrigida** de fato para
    fork (o `adapterType` chega se a proposta/override o trouxer — `ApprovalService:347`); **N6 foi
    corrigida** na contagem (`:348` = 2 nulls, não 3; o `teamId` não é campo do `HireCommand`). Um
    dos agentes também citou `Agent.java` num pacote que não existe (o certo é `org/agent/`).
    **Consequência honesta para quem lê:** os achados N7–N13, de menor gravidade, tiveram as
    âncoras conferidas mas **não** o mesmo escrutínio de raciocínio das candidatas — trate-os como
    hipóteses com âncora, não como fatos. O filtro aqui não foi cético o bastante para eu jurar
    pelos 13; foi cético o bastante para os 5 que viram bateria.

---

## 9. Handoff de volta (contrato do ledger)

- **Branch/PR:** nenhum — faixa de planejamento, **ZERO código/commit/branch** (contrato honrado;
  `HEAD` segue `main` `4c1b35f`, zero arquivo tracked modificado).
- **Arquivos tocados:** `docs/plano-descoberta-bugs.md` (este, novo, untracked) +
  `docs/plano-descoberta-bugs.materiais/` (staging untracked, ver abaixo). Nada mais.
- **Vxx consumida:** nenhuma (a **V30 foi consumida pela R22-A**; V31+/ADR-0043+ seguem livres —
  a ADR-0043 existe **só** na branch da Frente E, não na main).
- **Contagem de testes:** não medida (nenhum código tocado; piso vigente **750/294** na `4c1b35f`).
- **Ambiente vivo:** **intocado** (contrato: leitura). O `:8080` segue no jar pré-fix.

**O que fica pendente para o controle:**
1. **A sentada do operador** — reiniciar o `:8080` com o jar da `4c1b35f`. É o **único**
   pré-requisito da Bateria 1b e destrava as afirmações pós-reboot (**142** vigentes; 13% do
   inventário). Maior alavanca isolada do plano. *(CUMPRIDO em 2026-07-17 13:47 — na `fef6073`,
   decisão do dono.)*
2. **Emissão das baterias como faixas próprias.** A **Bateria 1 está pronta e não espera nada** —
   pode ser emitida hoje. B1.1 é o passo mais barato e mais valioso do documento.
3. **3 decisões que não tomei** (§8): o veredito DOCKER (item 4) · o rebaixamento de `0012-A4`
   (item 6) · re-verificar ou não `0021`/`0031`/`0035` (item 1, hoje = bateria de revisão).
4. **`docs/plano-descoberta-bugs.materiais/`** (48 arquivos: dossiê de 82 itens + 42 fragmentos com
   as 1085 afirmações + 3 sínteses) **ainda NÃO foi apagado** — o contrato manda apagar só depois
   deste handoff ser aceito. **Antes de apagar, uma pergunta ao controle:** este plano condensa,
   mas **não carrega as 1085 linhas**; as baterias citam afirmações por ID (`0037-A46`,
   `0012-A4`…) que **só existem lá dentro**. Apagar o staging torna esses IDs pendurados no vazio.
   Recomendo **preservar o diretório até a última bateria fechar** (ou promovê-lo a tracked) —
   mas a decisão é do controle, e a execução (apagar) fica com ele.

**Decisões novas tomadas nesta faixa (registradas, não silenciosas):**
- **Estado `🎯 AGORA (pós-reboot)`** criado (decisão do controle) e aplicado a 145 afirmações
  (o número vigente anda com as baterias — ver a tabela §3 e suas notas de atualização) — a
  distinção "consertado no código × não no ar" não existia no método e agora carrega o plano todo.
- **Reclassificação julgada uma a uma, não em bloco:** das 134 `⛔ BUG#1`, 7 foram para `🚫`, 1
  para `🎯 AGORA`, 1 para `❓ N/C` — e cada exceção tem motivo escrito no fragmento.
- **O achado crítico do handoff anterior foi medido e fechado:** a contaminação era exatamente 4
  ADRs, as 4 foram re-extraídas contra a main, e as outras 38 estão provadamente limpas no eixo
  texto (§1, fato 2).
