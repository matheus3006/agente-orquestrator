# Handoff — Grilling: Frente C, o método de memória do CEO (a ASSINATURA)

> **Data:** 2026-07-14 · **Faixa:** R14-A (rodada 14, GRILL-ONLY — precedente R5-A/R11-A)
> **Base:** `main` = `3d02801` · piso 672 backend / 288 front · PRs #1–#24 MERGED
> **Status:** **GRILL FECHADO** — 16 decisões (C1–C16) · 3 fatias derivadas · zero residual aberto
> **Grill original (o método):** [`handoff-grilling-memoria-ceo-segundo-cerebro.md`](handoff-grilling-memoria-ceo-segundo-cerebro.md) (D1–D12)
> **ADRs que este grill EMENDA:** 0019 (o átomo) · 0020 (o racional) · 0003 (o bridge MCP)

## ▶ Próxima ação (pra quem pegar isto)

**Emitir a F-C1** (sessão de controle). As 3 fatias são **estritamente sequenciais** — **uma por vez, nunca
2 em voo nesta frente** (ver **C16**: cada fatia fabrica a razão de existir da próxima). A F-C1 também
**commita este arquivo** (hoje untracked) + as emendas de ADR.

- **Nada foi implementado nesta faixa** — grill não commita (contrato). Zero código, zero reserva consumida.
- **O que a F-C1 precisa reservar:** **V29** (`decision.seq` + `decision.grau`) · **ZERO ADR nova**
  (emendas em 0019 + 0020 + 0003) — detalhe em **§ Reservas necessárias**.
- **Zero residual aberto** neste grill: as 16 decisões (C1–C16) estão fechadas; o que sobrou é execução.

---

## O que esta sessão fechou

O **átomo** da memória já estava no código e provado (Decisão-como-átomo + racional por grill +
carta como projeção determinística zero-LLM — ADR-0019/0020). O que faltava era a **ASSINATURA
do método**: o anti-inchaço. Este grill fecha as 4 peças ausentes — **graduação · cap · índice/JIT
· os writers mortos** — e descobre a **5ª que ninguém tinha visto: o endereçamento**.

**O achado central:** o método de findability tem **3 pernas** (endereçamento → índice → JIT). As
pernas 2 e 3 eram o objeto do grill. A **perna 1 não existia no código** — o `render()` da carta
cospe `"- [data] (área) o_que_mudou — racional: X"` **sem ID nenhum**. Sem endereço: o CEO não
consegue **citar** o que supersede (D11 impossível), o `memory_fetch` não tem o que buscar, e a
linhagem de um princípio não tem o que citar. **Três peças diferentes travavam na mesma pedra.**

---

## Decisões (C1–C16)

### Bloco A — o tier durável (o que nasce)

| # | Decisão | Racional |
|---|---------|----------|
| **C1** | **Um tier durável só: `MemoryKind.PLAYBOOK`.** Fecha o **residual 2 da ADR-0019**. | O durável-**pontual** já É o Decision-log — uma `Decision` *é* o registro pontual, datado e endereçável. Criar um tier "ADR-da-Empresa" duplicaria a tabela `decision`. O que falta é só o **destilado** (o degrau episódico→semântico que as 2 pesquisas apontam como o mais mal-servido). |
| **C2** | **A entrada do Playbook é AUTORADA, via propose-then-commit:** o **CEO propõe** a frase, o **dono sela** (writer-of-record). A **materialização** do Playbook segue **projeção determinística sobre os princípios JÁ SELADOS**. | Heurística **não se destila deterministicamente** — uma projeção pura só re-lista decisões (o Log já faz isso); o valor é a *frase sintetizada*. Propose-then-commit é a espinha da casa (venceu 4×) e mantém **um humano como writer-of-record** → o caminho **automático** de escrita continua **zero-LLM**: o invariante anti-poisoning fica intacto (o CEO **propõe**; o humano **sela**). |
| **C3** | **Um princípio É uma Decisão — num eixo NOVO: `grau: DECISAO \| PRINCIPIO`.** NÃO um valor de `tipo`. | 🔴 **`tipo=PRINCIPIO` seria erro de categoria.** `tipo` (DIRECAO/OPERACIONAL/TECNICA) é o eixo do **ASSUNTO** e é **derivado da área** (`DecisionLog.tipoFor` é função pura). `grau` é o eixo do **ALCANCE** (resolve *um* caso × governa *casos futuros*). Colapsar os dois faria o **Log filtrável MENTIR** (`?tipo=TECNICA` perderia **todo princípio técnico**) e quebraria a derivação. Um princípio técnico = `grau=PRINCIPIO, tipo=TECNICA`. **`grau` é derivado da PORTA, `tipo` da ÁREA — nenhum é julgado (D9 se estende de graça).** |
| | *Alternativa rejeitada* | **Tabela `principle` própria.** Daria **dois espaços de endereço** (`#D-7` / `#P-3`) → o CEO teria que desambiguar, o tool JIT precisaria de 2 fetchers, a gramática de citação dobrava. E **trairia o D4** ("a Decisão é o átomo endereçável"). O `grau` preserva **UM ÚNICO espaço de endereços** pra memória inteira — o maior ganho de findability da frente. |

### Bloco B — o endereçamento (a perna que faltava)

| # | Decisão | Racional |
|---|---------|----------|
| **C4** | **`decision.seq` → `#N` por company.** Clone exato do **V23** (ADR-0033, o #N da Issue). **É o V29 — junto com `grau`, a ÚNICA migração da Frente C.** | Sem endereço estável: **D11 é impossível** (o CEO não cita o que substitui), **`memory_fetch` não tem chave**, **a linhagem não tem o que citar**. `#N` e não UUID: carta legível (`"#7 (produto) …"`), o CEO cita **sem alucinar** (UUID de 36 chars é convite a erro), e a casa **já escolheu esse padrão** (ADR-0033). O `render()` do consolidator passa a emitir o `#N`. |

### Bloco C — a graduação (o ritual)

| # | Decisão | Racional |
|---|---------|----------|
| **C5** | **Gatilho = N=3 decisões numa área DESDE o último princípio daquela área.** Detector **determinístico** (zero-LLM), de carona no commit do gate, idempotente. **"N usos" fica FORA do v1.** | 2 é coincidência, 3 é tendência (e a casa já provou "3" 2×). A pergunta real do detector é uma **query pura `(area, decided_at)`** → **mata a tabela de linhagem de graduação** que estava pré-precificada. A linhagem em `evidencia` (`"graduado de #12, #23, #24"`) vira **documentação resolvível** (os `#N` são fetcháveis), não índice load-bearing. |
| **C6** | 🔴 **A graduação é a 7ª AÇÃO DO GATE** — `ApprovalActionType.GRADUATE_PRINCIPLE`. | Precedente **literal**: a F-AT1 adicionou `SKILL_INSTALL_REQUEST` como *"a 6ª ação do gate"*. Herda **de graça**: propose-then-commit · **o grill do racional (D6)** · a fila · **o lote (ADR-0035)** · a proveniência (`approval_id` **NOT NULL** — zero coluna morta). O candidato à graduação **É uma Approval pendente na fila que já existe**. |
| | *Alternativa rejeitada* | **"Graduation-badge" + endpoint de promoção + fluxo de selagem próprio** (foi a minha 1ª proposta). **Morre no teste da deleção:** deleta o módulo e a complexidade **some** — porque o gate já faz tudo isso. Era **módulo raso**: interface grande (endpoint novo + estado de badge + superfície de UI) sobre implementação fina que só **re-implementa propose-then-commit**. Viola "escolher, não inventar". |

### Bloco D — o cap (o anti-inchaço, a assinatura)

| # | Decisão | Racional |
|---|---------|----------|
| **C7** | **Cap por PROJEÇÃO, hard por construção.** Capa-se a **CARTA** (a única superfície sempre-on) — **nunca** o Decision-log. `refresh()` renderiza no máximo **N=12** direções (mais recentes). Constante embutida no consolidator, **provada por teste**. | O log é **história append-only** — o anti-padrão é explícito (*"compactar Ledger = mover pra ADR, não deletar"*). O que **incha** é o sempre-on (entra em TODO contexto como `CLAUDE.md`/`AGENTS.md`) → context-rot → envenena todo run. Com o cap **na projeção**, o sempre-on **não PODE apodrecer**: o consolidator nunca projeta mais que N. Mesmo espírito do `CAP_BODIES_POR_RUN=3` (enforcement de 1ª classe no core). |
| **C8** | **Estouro = GRADUAR ou cair-pra-JIT. Nunca descartar, nunca comprimir.** As direções além de N: continuam **no log** · ficam **JIT-fetcháveis** · e uma **nudge-line determinística** cutuca o dono a destilar o excedente num princípio (compressão **N→1**). | Descartar = perda de história. Comprimir por LLM = **mata o zero-LLM** da carta. A "compressão" honesta **é a graduação** — o excedente é *elevado*, não só rebaixado. |

### Bloco E — o índice + a navegação JIT

| # | Decisão | Racional |
|---|---------|----------|
| **C9** | **Always-on:** CEO = **carta + princípios** · EXECUTOR = **princípios só** (não a carta). Playbook tem **cap próprio: N=12**. | **Os princípios SÃO o "índice enxuto"** do método — frases terças, dono-seladas: indexar um índice é absurdo. Hoje o executor **não recebe carta nenhuma** (`CompanyMemoryMaterializer`: *"Em run de Issue o executor não o chama"*) → o D12 (*"agentes aplicam"*) é **letra morta**, e sem nada always-on o agente sofre **cegueira-de-retrieval** (nunca *pensa* em procurar). Direção do dono não é material de executor → ele **não** recebe a carta. |
| **C10** | 🔴 **Navegação JIT = REÚSO do trilho MCP-loopback da F-AT2.** Dois tools read-only **no MESMO bridge**: `memory_search(query)` (metadados — **livre**, como `knowall_search`) + `memory_fetch(#N)` (corpo: racional/evidência/linhagem/cluster — **cap=3/run** + dedup + recusa não-fatal + evento no StepTrace). **Zero canal novo.**<br>**O trilho abre nas DUAS pontas:** turno do **CEO** (o consumidor primário — é ele que responde *"por que decidimos #7?"*) e run do **EXECUTOR**. `McpRunScope.issueId` vira nullable no turno do CEO. | Regra do transporte (motor-nativo §2): **"escolher, não inventar"**. A F-AT2 já provou nos 2 CLIs reais exatamente esse shape (token efêmero por-run · identidade do PRODUTO, não do agente · cap+dedup+recusa server-side). **Teto D6 vive aqui** (cap=3/run — o `memory_fetch` amplia o alcance do agente). |
| **C10.b** | **Escopo do trilho = DURÁVEL SELADO** (princípios + direções). O **log cru** `OPERACIONAL`/`TECNICA` com racional **segue BOARD-ONLY**. | O código já desenha essa fronteira: `DecisionController` é `assertBoardMember` e *"racional é material do dono"* — nunca no `activity_log`. Hoje o racional só chega a um agente **pela carta** (direções, dono-seladas). O escopo escolhido **não vaza nada novo**. Alargar pro log inteiro = v1.1 com grill próprio. |

### Bloco F — os writers mortos (D5/D11) e a fábrica

| # | Decisão | Racional |
|---|---------|----------|
| **C11** | **Writer D5 ACESO — a review é fábrica de Decisões.** `IssueService.review` passa a chamar a fábrica; `Decision.anchoredToReview` **ganha caller**; `review_id` deixa de ser sempre-NULL.<br>🔴 **Requisito duro: a `area` do Decision da review vem da área do ARTEFATO (a Issue)** — nunca de um default fixo. | `POST /issues/{id}/review` **já é board-only** (`requireBoard("Apenas o operador (board) decide uma revisao")`) — **já É o gate do dono**. E o `tipoFor(area)` faz o review cair em OPERACIONAL/TECNICA → **review nunca polui a carta** (que só projeta DIRECAO). **Se a área cair num default fixo, todo review vira LOGICO e o D10 continua morto** (ver C13). |
| **C12** | **Writer D11 ACESO** — `supersedesId` **opcional** na passagem do gate (as **duas** portas). **Zero migração.** CEO **propõe** o link (citando o `#N`), dono **sela**. | A coluna já existe e **o leitor já funciona** (o consolidator já filtra as supersedidas da carta) → acender o writer faz **a carta se auto-curar na hora**. É também a 2ª válvula do cap (C7): supersedar tira da janela. |
| **C15** | 🔴 **`fabricate(GatePassage)` — EXTRAIR, não copiar.** O `recordGateDecision` (hoje **privado** no `ApprovalService`) vira **módulo profundo** em `memory/`, colado no `DecisionLog.record`. Seam pequeno: `fabricate(GatePassage{companyId, area, origem, oQueMudou, racional, racionalTipo, arrancado, supersedesId, grau, anchor})`. | "Espelhar o `recordGateDecision`" no `IssueService` criaria **duas fábricas de Decisão**, cada uma com sua fiação de grill — **exatamente o drift que produziu os seams mortos que viemos consertar**. Seam **real** (2 adapters hoje: approval + review; 3 amanhã: caso B/ADR-0034), não hipotético. E é a extração que torna a **7ª ação (C6) limpa** (ela parametriza o `oQueMudou`). A regra do C14 mora **dentro** da fábrica. |

### Bloco G — o regime do racional (emendas de texto)

| # | Decisão | Racional |
|---|---------|----------|
| **C13** | **Regime de commit ÚNICO nos dois tipos. Emenda de texto na ADR-0020/D8 — ZERO código.** O código está certo; **o documento está errado**. | **Empírico, não filosófico: `LOGICO` é 100% do tráfego padrão do gate.** As 6 ações derivam pra `estrutura-org`/`backlog`/`operacao`/`skills` — **nenhuma é criativa** (e `CorrecaoDeEntregaRouter:145` / `AbsorcaoDeIssueRouter:174` ainda **hardcodam** LOGICO). Uma barra de qualidade no LOGICO cairia sobre **TODO commit do dono** → *"fricção mata o hábito"* (D6) **+ confabulação em escala** (D7: o único jeito de passar por uma barra que você não satisfaz honestamente é **inventar uma premissa**). **O D8 não contradiz o D6 só na teoria — quebraria o gate na prática.**<br>**Custo do regime único: ZERO mecânico** — `racional_tipo` tem **exatamente 2 consumidores** (o fraseado do probe + a evidência maiêutica). **Nenhuma projeção, detector ou cap depende dele.** Um lógico "mal-tipado" (`tipo: LOGICO, racional: "me agrada o tom"`) é **auto-revelador no texto**.<br>**A diferença lógico↔intuição continua viva onde é real:** o fraseado do probe · a **evidência maiêutica** (só na intuição, D10) · o **sinal de revisabilidade** (D11) que o `racional_tipo` carrega pela vida da Decisão. |
| | *Alternativa rejeitada* (registrada pra **não ser re-litigada**) | Um check de **ESPÉCIE** (não de qualidade): *"me agrada"* numa decisão LOGICO é **preferência, não premissa** — rejeitar isso seria da mesma classe do D7. O javadoc do código até **promete** (*"'me agrada o tom' passa"* na intuição) e **não entrega** (nada em `CONFAB_FORMS` barra isso no lógico). **REJEITADO** porque as palavras de preferência são **AMBÍGUAS**: *"**prefiro** Postgres a Mongo **porque o modelo é relacional**"* contém "prefiro" **e é uma premissa perfeita**. `CONFAB_FORMS` só funciona porque seus membros são **não-respostas inequívocas**. Uma lista de preferência **dispararia falso no loop quente**. |
| | 🔴 **Achado** | **Hoje a maiêutica (D10) é praticamente código morto** — como 100% do tráfego é LOGICO, o `maieuticProbe` quase **nunca dispara**. E ela é **o mecanismo de captura mais valioso do método** (*"tácito vira explícito"*). **O que a ressuscita é o C11**: a **review é onde o dono julga ARTEFATO** — e artefato é onde mora o gosto. Issue de landing-page revisada → área criativa → INTUICAO → **a maiêutica dispara**. |
| **C14** | **Triviabilidade = SILÊNCIO DEPOIS DE SER PERGUNTADO.** Fecha o **residual 4 do grill original** + o **residual da ADR-0020**. Só na porta da **review**. | ver tabela + racional abaixo |

#### C14 — a regra

| ato na review | o gate faz | Decisão |
|---|---|---|
| **REJECT** | **BLOQUEIA** — motivo obrigatório | ✅ nasce |
| **APPROVE com racional** | commita | ✅ nasce — é o *"risco aceito-sem-mudar"* (D5) |
| **APPROVE em silêncio** | commita **+ PERGUNTA** (não-bloqueante), nas palavras da **política**:<br>· criativo → *"o que te agradou? o que você viu que o agente não viu?"* (D10)<br>· não-criativo → *"alguma ressalva ou risco que valha registrar?"* | ✅ **se responder** · ❌ **se pular** — fica só o `REVIEW_APPROVED` no `activity_log` (a história permanece; **memória durável não nasce do nada**) |

**Racional — as duas opções óbvias FALHAM:**
- **Forçar racional em todo approve** → `racional: "ok"` em massa. E **`CONFAB_FORMS` não pega "ok"** — o filler passa batido. O risco-aceito não é capturado: fica **enterrado no ruído**.
- **Não perguntar nada** ("silêncio = trivial", minha 1ª versão) → o dono aprova 20 issues em silêncio e **o writer D5 nasce morto na prática**. E o registro que morre **primeiro** é justamente o *"risco aceito-sem-mudar"* — o **mais valioso dos três casos do D5**: problema **grande** vira REJECT (que bloqueia → fica registrado); problema **pequeno** aceito **evapora por inércia**.

**A saída estava escrita nas decisões o tempo todo:** D6 — *"escriba-que-**INSISTE**, **nunca bloqueia**"* (**duas metades**; eu tinha implementado só a segunda). D10 — *"o probe roda em **AMBOS** os tipos"*. → **Perguntar sempre, bloquear nunca.**

**Não-gameável:** pra escapar do grill você precisa **não dizer nada** — e quem não diz nada **não ganha memória**, que é **a verdade**. Não dá pra gamear pra dentro de uma memória **falsa**; só pra dentro de **nenhuma**.

**Alternativa rejeitada:** um 3º valor no enum (`APPROVE_COM_RESSALVA`). **Adição rasa** — deleta o valor e a **capacidade continua** (o dono digita no racional); só o **empurrão** some. Entrega **nudge, não comportamento**, cobrando 3 estados que todo caller aprende. A insistência é **apresentação**; as **palavras** são **política** (o shape que o código já usa: `RacionalPolicy.maieuticProbe()` já devolve a pergunta como String). **A política é dona das palavras; a UI é dona do momento** → a chamada segue **uma só** (`review(decision, racional?)`).

**A porta da APPROVAL não muda:** ela **CRIA estado** (`dispatchAction` contrata/cria/instala) — o racional é *o porquê aquilo existe*. **Criação merece um porquê; aceitação em silêncio, não.** E o volume lá já tem a resposta da casa: **o lote com racional COMPARTILHADO** (ADR-0035) — *"compartilhe o racional"*, nunca *"pule o racional"*.

**Bônus:** o detector (C5) e o cap (C7) passam a **nunca contar carimbo** — o Playbook não gradua ruído.

### Bloco H — o fatiamento

| # | Decisão | Racional |
|---|---------|----------|
| **C16** | **3 fatias ESTRITAMENTE SEQUENCIAIS** (F-C1 → F-C2 → F-C3). **Zero paralelismo.** | O paralelismo foi **procurado e não existe** — e isso é **propriedade do método**, não falha do fatiamento:<br>**endereço (`#N`) → *permite* → linhagem + cap → *torna necessário* → JIT**<br>Cada fatia **fabrica a razão de existir da próxima**.<br>**Hipóteses de paralelo testadas e caídas:** (a) *docs em paralelo* → colide no `roadmap-interno.md` com todas; (b) *graduação ∥ JIT* → **o JIT sem o cap busca o que já está no contexto** (antes do cap, TODA direção é always-on: `memory_fetch` não teria nada útil); (c) *pré-adicionar enums pra destravar* → **seam morto**, exatamente a doença que esta frente cura. |

---

## Fatias derivadas

### **F-C1 — O átomo completo** (C4 · C11 · C12 · C14 · C15)

**Escopo:** V29 (`decision.seq` + `decision.grau`) · **extrair `fabricate(GatePassage)`** pra `memory/`
(contém a regra "sem substância → sem Decisão") · **writer D5** (área = **área da Issue**) · **writer D11**
(`supersedesId` nas duas portas) · **triviabilidade = silêncio-após-perguntado** · `render()` emite `#N`.

**Arquivos prováveis:**
`backend/.../db/migration/V29__decision_seq_grau.sql` ✚ ·
`memory/{Decision, DecisionGrau ✚, DecisionLog, GateDecisionFactory ✚, CompanyMemoryConsolidator(render)}` ·
`governance/approval/{ApprovalService (perde o `recordGateDecision` privado), ApprovalController, RacionalPolicy}` ·
`work/issue/{IssueService, IssueController}` · testes · **FE:** campo de racional na review + o probe como prompt.

**Prova (/verify):** review gera Decision com **`review_id` NOT NULL** (a coluna morta acorda) ·
supersede faz a direção **sair da carta sozinha** (auto-cura) · `#N` aparece na carta ·
approve **em silêncio → ZERO Decision** · review de artefato **criativo → a maiêutica dispara** (D10 vivo).

**Também commita:** este handoff (untracked — precedente R5-A/R11-A) · emendas **ADR-0019** (schema `grau`,
`seq`/endereçamento, nota de reconciliação D5/D11 → **FECHADA**) + **ADR-0020** (D8 → regime único +
alternativa rejeitada; triviabilidade → **FECHADA**) · roadmap §Frente C.

### **F-C2 — A assinatura: graduação + cap** (C1 · C2 · C3 · C5 · C6 · C7 · C8)

**Escopo:** `MemoryKind.PLAYBOOK` · **`GRADUATE_PRINCIPLE` = 7ª ação do gate** · **detector determinístico**
(N=3/área desde o último princípio — query pura) · **cap 12/12 por projeção** (hard) · **2ª projeção**
(`grau=PRINCIPIO` → `kind=PLAYBOOK`), gêmea da carta · **nudge-lines determinísticas** (candidato + estouro).

**Arquivos prováveis:**
`memory/{MemoryKind, CompanyMemoryConsolidator, GraduationDetector ✚}` ·
`governance/approval/{ApprovalActionType, ApprovalService(dispatch), RacionalPolicy(areaFor)}` · testes ·
**FE:** card de aprovação com a frase do princípio **editável** antes de selar.

**Prova:** 3 decisões numa área → **nudge aparece na carta** → CEO propõe → dono sela **pelo gate** → nasce
`grau=PRINCIPIO` com `approval_id` → **PLAYBOOK materializa**. 13 direções → **só 12 na carta** + nudge de estouro.

**Também commita:** emenda **ADR-0019** (residual 2 **FECHADO** — tier durável = PLAYBOOK) + **ADR-0020**
(D12 sai do texto original: ritual = 7ª ação do gate) · roadmap.

### **F-C3 — Índice + navegação JIT** (C9 · C10)

**Escopo:** `memory_search` (livre) + `memory_fetch` (**cap=3/run**, dedup, recusa não-fatal, evento) **no bridge
da F-AT2** · escopo = **durável selado** (log cru segue **board-only**) · trilho aberto **nas 2 pontas**
(turno do CEO + run do executor; `McpRunScope.issueId` nullable) · **split do always-on**
(CEO = carta+princípios · EXECUTOR = princípios).

**Arquivos prováveis:**
`skills/mcp/{KnowAllMcpBridge, KnowAllMcpBridgeController, KnowAllMcpMaterializer, McpRunScope}` ·
`memory/{CompanyMemoryMaterializer, MemoryQuery ✚}` · `execution/AdapterRunExecutor` (wiring do executor) · testes.

**Prova:** **/verify nos 2 CLIs reais** (padrão F-AT2) — o agente busca mid-run e **termina no mesmo run**;
o **4º corpo é recusado COM MOTIVO** (não-fatal).

**Carona:** **sync do corpo da skill `being-ceo` no DB** (operação de DADO pendente desde a R4) — é a única
fatia que abre a superfície **skills/KnowAll**, e ela mexe justamente no que o CEO recebe always-on
(adjacência temática real). Em fatia não-relacionada seria poluição de diff.

**Teto D6:** ✅ **aqui** — `memory_fetch` amplia o alcance do agente → **cap=3/run embutido**, espelhando o
`CAP_BODIES_POR_RUN` da F-AT2. As outras fatias **não** ampliam autonomia (o `GRADUATE_PRINCIPLE` é **proposta**
que o dono sela — **o gate já É o teto**).

---

## Matriz de colisão (preliminar)

| fatia | arquivos-âncora | colide com |
|---|---|---|
| **F-C1** | `V29__decision_seq_grau.sql` · `memory/{Decision, DecisionGrau, DecisionLog, GateDecisionFactory, CompanyMemoryConsolidator}` · `governance/approval/{ApprovalService, ApprovalController, RacionalPolicy}` · `work/issue/{IssueService, IssueController}` | — **é a base; nada roda em paralelo com ela** |
| **F-C2** | `memory/{MemoryKind, CompanyMemoryConsolidator, GraduationDetector}` · `governance/approval/{ApprovalActionType, ApprovalService, RacionalPolicy}` | **F-C1** (mesmo `CompanyMemoryConsolidator` + mesmo `ApprovalService` + mesma `RacionalPolicy`) → **estritamente depois** |
| **F-C3** | `skills/mcp/*` · `memory/{CompanyMemoryMaterializer, MemoryQuery}` · `execution/AdapterRunExecutor` | **F-C2** (precisa do PLAYBOOK e do **cap** pra ter o que buscar) → **estritamente depois** |

---

## Reservas necessárias

| Recurso | Uso | Nota |
|---|---|---|
| **Migration V29** | `decision.seq` (clone do V23) + `decision.grau` (`not null default 'DECISAO'`) — **uma migração, duas colunas** | **V29 está LIVRE** no ledger (a reserva anterior foi *liberada sem consumo*). **É a ÚNICA migração da Frente C inteira.** |
| **ADR nova** | **ZERO** | **Emendas** em **0019** (schema/`grau`/`seq`; residual 2 FECHADO; nota de reconciliação D5/D11 FECHADA) · **0020** (D8 regime único + alternativa rejeitada; D12 ritual; triviabilidade FECHADA) · **0003** (o bridge MCP ganha tools de memória — **mesmo canal** da F-AT2). Consistente com a regra condicional (**emenda venceu 4× seguidas**) e com o critério carimbado no ledger (*"só abre ADR nova se a decisão for além de 'campo → rota'"* — adicionar a 7ª ação a um enum + um case de dispatch **é** campo→rota). **ADR-0043 segue LIVRE.**<br>*Judgment call do controle:* se o dono achar que "graduação como ritual do gate" merece ADR própria, a 0043 está livre — **minha recomendação é emenda.** |
| **Enums (zero migração)** | `MemoryKind += PLAYBOOK` · `DecisionGrau ✚` · `ApprovalActionType += GRADUATE_PRINCIPLE` | `kind`/`tipo` são `text` **sem check** (V18) + `@Enumerated(STRING)`. **Cada enum entra JUNTO do seu consumidor** — pré-adicionar seria criar seam morto (a doença que esta frente cura). |
| **Piso** | a partir de **672 backend / 288 front** (`main` `3d02801`) | |

**Números embutidos da frente (provados por teste):** graduação **N=3** · carta **cap=12** ·
playbook **cap=12** · `memory_fetch` **cap=3/run**.

---

## Notas ao controle

1. **Premissa DEFASADA em arquivo de contrato:** `handoff-auditoria-doc-codigo-2026-07-11.md:189` afirma
   *"Frente C … está FORA do v1, congelada"*. **É falso** (v1 TOTAL — a Frente C está **DENTRO**). O arquivo é
   um dos **3 untracked de contrato** → **este grill não o tocou**. A reconciliação de texto que a triagem
   prescrevia (marcar o D12 na ADR-0020) **está atribuída à F-C2**.
2. **O grill não commitou nada.** `docs/handoff-grilling-frente-c-memoria.md` fica **UNTRACKED** — a **F-C1**
   (1ª execução derivada) o commita, junto com o §RESULTADO. Os 3 untracked de contrato seguem **intocados**.
   HEAD devolvido em `main` (`3d02801`).
3. **Zero residual aberto** neste grill.

---

## O que este grill DERRUBOU (pra não voltar)

| descartado | por quê |
|---|---|
| `DecisionTipo.PRINCIPIO` | **Erro de categoria** — colapsa o eixo do ASSUNTO com o do ALCANCE; o Log filtrável passaria a **mentir**. → `grau` (C3). |
| Tabela `principle` própria | **Dois espaços de endereço**; trai o D4. → princípio **É** Decisão (C3). |
| Tabela de linhagem de graduação (`principle_source`) | O detector só precisa de **query `(area, decided_at)`** (C5). Não paga o próprio custo. |
| "Graduation-badge" + endpoint de promoção | **Módulo raso** — o **gate já faz** propose-then-commit + grill + lote + fila. → 7ª ação (C6). |
| Espelhar `recordGateDecision` no `IssueService` | **Duas fábricas de Decisão** = o drift que criou os seams mortos. → **extrair** (C15). |
| Diferenciar o bar de commit LOGICO×INTUICAO | **LOGICO é 100% do tráfego** — a barra quebraria o gate e **fabricaria** a confabulação que o D7 proíbe (C13). |
| Check de "racional em forma de preferência" | Palavras de preferência são **ambíguas** (*"prefiro X **porque** Y"* é premissa). Falso-positivo no loop quente (C13). |
| `APPROVE_COM_RESSALVA` (3º valor do enum) | **Adição rasa** — entrega *nudge*, não comportamento; a capacidade sobrevive sem ele (C14). |
| Forçar racional em toda review | `racional: "ok"` em massa — e **`CONFAB_FORMS` não pega "ok"** (C14). |
| "Silêncio = trivial" **sem perguntar** | Deixa o **risco-aceito-sem-mudar (D5)** morrer por inércia e **viola o D10** (C14). |
| Promoção por **N usos** | Exige contar cada fetch JIT — máquina nova. **v1.1** (C5). |
| `memory_fetch` sobre o **log cru** | Vazaria racional `OPERACIONAL`/`TECNICA` (board-only) pra dentro de contexto de agente. **v1.1 com grill próprio** (C10.b). |

---

## §RESULTADO — F-C1 "o átomo completo" EXECUTADA (faixa R15-A, 2026-07-14)

**Suíte 691 backend (piso 672 + 19) / 290 front (piso 288 + 2) · tsc limpo · /verify PASS na
descartável (`:18081` + pg efêmero `fc1-pg:15432`; `:8080`/`:3000` do operador INTOCADOS).**
Branch `feat/frente-c-atomo-completo` de `main` `3d02801`. **V29 CONSUMIDA** · **ZERO ADR nova**
(emendas 0019 + 0020 escritas) · roadmap §Frente C atualizado.

### O que entrou (o §F-C1 na íntegra, nada além)

1. **V29__decision_seq_grau.sql** — clone exato do V23 (`seq` denso por company, backfill
   `(decided_at, id)` com UUID desempatando, unique backstop) + `grau text not null default
   'DECISAO'`. Alocação do `#N` no chokepoint único `DecisionLog.record` (lock da linha da
   company, MAX+1). `DecisionGrau` entrou JUNTO — **só com o valor `DECISAO`**: `PRINCIPIO`
   entra com o writer de graduação (F-C2), pela própria regra "enum junto do consumidor" desta
   frente (registrado no javadoc do enum).
2. **`memory/GateDecisionFactory.fabricate(GatePassage)`** (C15) — o `recordGateDecision`
   privado saiu do `ApprovalService`; as 2 portas viraram adapters finos (`fabricaDecisaoDoGate`
   / `fabricaDecisaoDaReview`). Moram DENTRO: "sem substância → sem Decisão" (C14), a maiêutica
   D10 como evidência e o refresh da carta na MESMA tx. Seam conforme o C15:
   `GatePassage{companyId, area, origem, oQueMudou, racional, racionalTipo, arrancado,
   supersedesId, grau, anchor}` com `Anchor.NaApproval|NaReview` selado.
3. **Writer D5 ACESO** (C11) — `IssueService.review` fabrica; `anchoredToReview` ganhou caller;
   `review_id` da V18 acordou. **Área = time do assignee** (`areaDaIssue`: agente→time→dominio;
   fallback "operacao" SÓ para o caso sem assignee/time — nunca default para todos).
4. **Writer D11 ACESO** (C12) — `supersedesSeq` (o `#N` citado) opcional nas DUAS portas
   (`DecisionRequest` + `ReviewRequest`), resolvido server-side por `DecisionLog.idDoEndereco`
   ANTES de qualquer efeito (`#N` fantasma → 409 acionável, PENDING intacta).
5. **Triviabilidade C14** — REJECT bloqueia sem motivo (400 acionável, antes de tocar estado) ·
   APPROVE com racional → Decisão · APPROVE em silêncio → commit + SÓ `REVIEW_APPROVED` no log.
   A chamada seguiu UMA (`review(decision, racional?)` — o `reason` existente É o racional).
   **Palavras da política**: `RacionalPolicy.reviewProbe(tipo)` (criativo = o próprio
   `maieuticProbe` D10; não-criativo = "alguma ressalva ou risco que valha registrar?").
   O `ReviewController` enriquece cada card com `area`/`racionalTipo`/`racionalProbe` — a UI é
   dona só do MOMENTO.
6. **`render()` emite `#N`** — `"- #7 [data] (área) o_que_mudou — racional: …"`; a carta ensina
   o endereço de graça.
7. **FE** — `decision-dialog`: probe como placeholder do APPROVE + texto C14 ("responder vira
   Decisão; pular deixa só o histórico"); REJECT com motivo obrigatório (botão trava; server é
   backstop). Tipos sincronizados (`ReviewSummary`+3 campos, `supersedesSeq` nos 2 inputs).
   `reviews.test.ts` +2.

### VENDO (as 5 provas do §F-C1 + extras) — tudo dirigido pela API real

| prova | resultado |
|---|---|
| review → Decision com `review_id` NOT NULL | ✅ decisions #3 (growth/LOGICO) e #4 (design/INTUICAO) ancoradas no ciclo, `issue_id` junto |
| supersede → direção SAI da carta sozinha | ✅ proposta REAL via bastão Q→P→E (personas process determinísticas) citando "#1"; dono selou `supersedesSeq:1` → carta v2 SÓ com a #2; **#99 fantasma → 409** e a proposta seguiu PENDING |
| `#N` aparece na carta | ✅ `"- #1 [2026-07-14] (direcao-roadmap) …"` na v1; `"- #2 …"` na v2 |
| approve EM SILÊNCIO → ZERO Decision | ✅ issue #4: `REVIEW_APPROVED` no activity_log, NENHUMA linha em `decision` — e o `#N` seguiu DENSO (o silêncio não queima número) |
| review criativa → maiêutica dispara | ✅ time `design` → INTUICAO → evidência `"Probe maieutico (ADR-0020/D10)…"` — **D10 vivo pela 1ª vez**; fila `GET /reviews` entregou o probe maiêutico no card |
| V29 no padrão do V23 | ✅ Flyway V29 aplicada no pg real (`\d decision`: seq NOT NULL + unique + grau default) + backfill provado com o SQL exato sobre linhas desordenadas de 2 companies (denso 1..3/1, UUID desempatou timestamp idêntico) |
| C14 REJECT sem motivo | ✅ 400 "REJECT exige o motivo…", issue seguiu PENDING_REVIEW, zero Decision |
| FE ao vivo | ✅ `next dev :3000→18081` (`.env.local` restaurado no teardown): dialog do APPROVE criativo com a maiêutica como placeholder (screenshot na sessão) · REJECT travado sem motivo · resposta à maiêutica pela UI fabricou a decision #5 (INTUICAO, evidência D10) |

**Sobre "CLIs reais onde o fluxo exigir":** nenhum passo da F-C1 depende de comportamento de
modelo — os writers são server-side e a citação do `#N` é texto de proposta (dirigida com
personas `process` determinísticas, receita da skill verify). Diferente da F-AT1/F-AT2 (agente
tinha que USAR um tool mid-run), não há fluxo aqui que um CLI real provaria além do que o VENDO
acima provou. Judgment call registrado.

### Decisões de kickoff tomadas (dentro do plano; racional registrado)

- **`DecisionGrau` nasce só com `DECISAO`** (acima) — anti-seam-morto vence a completude do enum.
- **`origem` da Decisão da review = `DONO_NO_GATE` nos 2 atos** (o dono julga artefato; não há
  proposta de CEO na porta pós-hoc).
- **`racional_arrancado=false` na review** — a pergunta C14 é não-bloqueante (não há rodada de
  grill contada); "arrancado" segue significando insistência bloqueante da porta pré-hoc.
- **O supersede na porta pré-hoc entra pelo DECIDE (campo do dono), não pelo alvo da proposta**
  — o CEO cita o `#N` no resumo (prosa), o dono sela no campo; zero mudança em
  `ProposalScanner`/`TurnOutputSchema` (o arquivo-list do §F-C1 não os inclui — deliberado).
- **Fila de review enriquecida degrada, nunca quebra** (issue sumida → card sem área/probe).

### Pendências / notas ao controle

- **F-C2 destravada** (sequencial): graduação + cap; `DecisionGrau += PRINCIPIO` entra lá.
- O `.claude/launch.json` untracked pré-existente no checkout (config de FE→18081 de sessão
  anterior) foi usado e mantido como estava; `.env.local` do operador restaurado byte a byte.
- Zero reserva além da V29; ADR-0043 segue LIVRE.
