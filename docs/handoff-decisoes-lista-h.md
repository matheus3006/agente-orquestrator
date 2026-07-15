# Handoff — decisões da lista H (fecho do v1)

> **O que é:** o produto da faixa **R17-B** (sessão de `/grilling`, 2026-07-14) — as **5 decisões que só o
> dono toma**, colhidas UMA A UMA sobre `docs/lista-h-fecho-v1.md` (compilada pela R16-B/PR #27).
> Cada item traz: **veredito · racional verbatim do dono · achados do grill · o trabalho que implica**.
>
> **Regime da faixa:** DECISÕES-ONLY. **Zero commit, zero código, zero arquivo tocado além deste.**
> A `lista-h-fecho-v1.md` tracked **NÃO foi editada** (as correções que ela precisa estão no §6 abaixo,
> como insumo). Nenhuma decisão foi **aplicada** — nem as de texto.
>
> **Estado no momento da colheita:** main `3a42e92`, PRs #1–#27 MERGED. A **F-C3 rodava em paralelo**
> no checkout compartilhado (branch `feat/frente-c-indice-jit`) — leitura apenas, HEAD intocado.
> Piso: **702** backend · **294** front.
>
> **UNTRACKED de propósito** — o **fecho residual** commita e APLICA.

---

# 🔴 ACHADO CRÍTICO — bug de produto vivo em main (fora da pauta)

> **Leia isto antes de qualquer outra coisa deste documento.** Não estava na lista H, não é
> housekeeping de demo, e **não é hipotético**: está em `main` hoje, e dispara no **uso normal** de
> uma feature do v1.

## A ADR-0035 (lote) nunca foi reconciliada com a F-C1 (writers de Decision acesos)

Duas fatias landaram em momentos diferentes e **ninguém cruzou as duas**:

- **PR #12 / ADR-0035** construiu a **aprovação em lote**.
- **PR #25 / F-C1** acendeu os **writers de Decision no gate** — e essa porta *"sempre nasce"*
  (`ApprovalService.java:238-248`).

### O mecanismo

`ApprovalBatchService.decideBatch` recebe **UM racional para o lote inteiro** e o **carimba em cada
`decide()`**, item a item (`ApprovalBatchService.java:36,42-45`):

```java
decideBatch(UUID companyId, ApprovalDecision decision, String racional, ...)   // ← UM racional
    for (Item item : items)
        approvals.decide(..., racional, decisionReason(motivo, item.observacao()), ...)
                          //  ↑ o MESMO racional, N vezes → N Decisões IDÊNTICAS
```

O **modelo de dados já sabe** que o lote é **um ato do dono com N alvos** (racional único do lote +
observação por item). O **código esqueceu de contar assim**.

### O dano — e ele é em PRODUÇÃO, não na demo

1. **N Decisões clonadas** no Decision-log (append-only — **ficam para sempre**; nenhum reaper as
   coleta).
2. 🔴 **Uma nudge FALSA de "candidata a graduação" na CARTA.** O detector C5 varre o log **inteiro,
   sem filtrar tipo**, e dispara com **3 decisões na mesma área**
   (`CompanyMemoryConsolidator.java:178-184`). Rejeitar 3+ propostas do mesmo tipo em lote **basta**.
3. **A carta é o artefato que os AGENTES leem.** Ou seja: o uso normal de uma feature do v1
   **envenena a memória da company** com um sinal inventado — e o sistema inteiro da Frente C existe
   justamente para que a memória seja *derivada, nunca auto-escrita* (defesa contra memory-poisoning).

### O que atenua (por que não é catástrofe)

A área do `HIRE_AGENT` é `"estrutura-org"`, e `DecisionLog.tipoFor()` a classifica como
**`OPERACIONAL`** (`:77-89`) → **não entra na carta** (que só projeta `DIRECAO`+`DECISAO`) **nem no
playbook** (só `PRINCIPIO`). **O envenenamento é do log e da nudge — não do corpo da carta.**
Mas a nudge **aparece na carta**, e é ela que mente.

### Como apareceu

**Não estava na pauta.** A lista H tratava o item 5 como housekeeping ("o que fazer com os 27
approvals PENDING da demo") e **sugeria rejeitá-los em lote** como "mais uma prova viva". O bug só
veio à tona quando o dono respondeu **"quero poder rejeitar em lote sim"** — e o grill foi verificar
o que a rejeição em lote de fato faz hoje.

### Decidido

**Consertar no v1** — **1 Decisão por lote, não por item** — e **só depois** rejeitar os 27 da demo
(a rejeição vira a prova viva da correção). Detalhes, contorno e armadilhas: **[Item 5](#item-5-lista-h--destino-dos-artefatos-vivos-de-vendo--virou-bug-de-produto)**.

---

## Ordem da colheita (e por quê)

Grilado fora da ordem da lista: **3 → 1 → 4 → 2 → 5**. Os itens 3, 1 e 4 são a mesma pergunta
("obra nova agora × v1.1"), e o **item 3 é o que fixa a régua** — decidido ele, os outros dois se
resolvem sob a mesma luz. Depois o veto de redação (2) e o housekeeping (5).

**O princípio que o dono estabeleceu no item 3 governou os itens seguintes** (ele o aplicou
explicitamente em 1 e 5; ele aponta para o lado *contrário* em 4, e isso foi grilado):

> *"se sabemos que pode dar um problema, façamos já"*

---

## Item 3 (lista H) — os 3 tetos de escalação da ADR-0018 — **RESOLVIDO: obra no v1 (parcial)**

### Veredito

| Teto prometido pela ADR-0018 | Veredito |
|---|---|
| (a) gasto por token | **NÃO É LACUNA** — a própria ADR o dispensa no v1. Nada a fazer. |
| (b) plano com muitos riscos | **v1.1** |
| (c) circuit-breaker de erro repetido | **v1 — fatia nova** |
| a **cascata** (executor→arquiteto→líder→CEO→dono) | **NÃO fazer** — escalação **direta ao dono**; emendar a ADR-0018 |

### Racional verbatim do dono

> "Acredito que podemos fazer isso ja no V1 , nao deve ser algo que leve tanto tempo assim, e se
> sabemos que pode dar um problema , façamos ja"

E, ao recorte apresentado ((c) no v1 · (b) no v1.1 · cascata emendada para fora):

> "sim"

### Achados do grill (corrigem a lista H)

1. **"M9 corta reincidência" é FALSO.** A recomendação registrada na lista H apoiava-se nessa perna.
   Os `M9` são **marcadores locais de cada ADR**: o M9 da ADR-0030 é a *rede do resume-cirúrgico*
   (`ContextHandoffLoop.java:53`); o M9 da ADR-0037 é a *issue da 2ª porta* (`IssueService.java:116`).
   **Nenhum dos dois olha erro repetido.**
2. **Dos 3 tetos da ADR-0018, ZERO existem** — e o (a) **nunca precisou existir**. A ADR diz verbatim
   (linha 26): *"(a) gasto por token — **só no modo API paga** (no v1/assinatura o custo é 0, sem gate
   de gasto)"*. O campo que a lista H apontou como "o único que existe"
   (`BudgetPolicy.orchestrationLimitCents`) é o **teto de ATOS da ADR-0016** (T.4, anti-enxame) —
   **outro mecanismo, de outra ADR**.
3. **O freio real de loop hoje tem outro nome:** o teto de orquestração (T.4) **cobra o ato na
   abertura**, então um agente em loop de erro drena o teto e para. É um circuit-breaker
   **econômico**, não semântico: corta **tarde, caro e sem diagnóstico**.
4. **`riskLevel` NÃO é metadado inerte** (afirmação corrigida durante o grill, e confirmada no
   backend): R1–R5 **já decide o review gate** — `IssueService.java:55`
   (`orquestrador.review.risk-threshold`, default **5**) e `:162` (*"Sucesso → `DONE`, OU
   `PENDING_REVIEW` se `risk_level >= limiar` (o gate intercepta…)"*). **Já existe um teto de risco
   vivo.** O buraco do (b) é **mais estreito** do que a lista H pintou: falta contar os
   riscos que **o agente** identificou, e para isso **não existe artefato** (o `TurnOutputSchema` só
   conhece `handoff`, `absorcao`, `proposta[...]`) — seria **bloco novo no schema por-persona +
   parser + limiar + escalação**. Foi por isso que (b) caiu para o v1.1: pelo próprio critério do
   dono, aqui **não sabemos que dá problema**.
5. **A cascata é a parte cara e ninguém a tinha mencionado.** A ADR promete *"tetos sobem em cascata
   (executor→arquiteto→líder→CEO→dono)"* — uma escada de autoridade inteira. Todo o resto do sistema
   escala **direto ao dono** (gate, avisos M6). Decisão: manter a coerência, emendar a ADR.

### Trabalho que implica

- **Fatia nova (v1) — circuit-breaker de erro repetido.** Contorno já levantado:
  - chokepoint **único** já existe: `HeartbeatRunStore.finishFailure` (`:72`);
  - matéria-prima já persistida: `lastError`, `agentId`, `issueId` no `HeartbeatRun`;
  - ⚠️ o campo `attempt` (`HeartbeatRun.java:38`) **NÃO serve**: ele conta tentativas do *mesmo* run
    (reaper), não falhas consecutivas de runs diferentes. O contador é novo;
  - escalação: **direta ao dono**, pelo padrão **M6 já provado** (turno já-`ANSWERED` na thread, zero
    run novo — molde do `ProposalDescarteNotice` / `postaProtocolo` da absorção).
  - **Parâmetros abertos (grill da fatia, não desta sessão):** o **limiar** (recomendação registrada:
    3) e o que exatamente **"para"** (recomendação: não reenfileirar wakeup daquele par
    *agente × issue* até o dono agir — "para só o pedaço travado", como a ADR pede).
- **Emenda à ADR-0018:** (i) cascata **FORA** (escalação direta ao dono); (ii) teto (a) registrado como
  **não-promessa do v1**; (iii) teto (b) → **v1.1**.
- **Registro v1.1:** a candidata **#11** do roadmap fica **só com o (b)**.

---

## Item 1 (lista H) — feedback do revisor → Issue (ADR-0011) — **RESOLVIDO: v1, pela terceira via**

### Veredito

**Fazer no v1** — mas **NÃO** por endpoint de edição de Issue. Pela **terceira via**: **materializar,
para o executor no re-run, as Decisões ancoradas naquela issue**. E **emendar a ADR-0011**.

### Racional verbatim do dono

> "perfeito façamos no v1"

*(aplicando explicitamente o princípio que ele fixou no item 3; a terceira via e a emenda foram
apresentadas e endossadas.)*

### Achados do grill

1. **A pergunta da lista H era uma FALSA DICOTOMIA** ("puxar fatia de edição de Issue × aceitar como
   está"). Existe uma terceira via, e ela é curta.
2. **A lista H está DESATUALIZADA neste item.** Ela cita a auditoria de 2026-07-11 — **anterior à F-C1
   (PR #25)**. Hoje, em main:
   - o **REJECT já exige motivo** (bloqueia sem ele — `IssueService.java:252`);
   - esse motivo **já vira Decisão**: `DONO_NO_GATE`, grau `DECISAO`, **racional = o feedback**,
     **ancorada na review e na issue** (`IssueService.java:282-296`, via `GateDecisionFactory`).
   - **O feedback NÃO fica mais "preso em `review.decision_reason`".** Ele já é um átomo de memória
     com endereço `#N`.
3. **O buraco real é outro:** o **executor roda sem memória nenhuma**. O feedback existe, tem endereço,
   e **ninguém o entrega ao agente**.
4. **Nem a F-C3 resolve** (verificado no trabalho em voo): ela dá ao executor **os princípios** (grau
   `PRINCIPIO`, o índice enxuto com os `#N`) e **explicitamente nunca a carta**. A Decisão do reject é
   grau **`DECISAO`** → **fora do escopo do executor mesmo pós-F-C3**. Mas a F-C3 constrói **todo o
   encanamento** (`MemoryAudience` + materializer por audience + bridge MCP + fetch por `#N`).
5. **O circuit-breaker (item 3) NÃO cobre este caso:** aqui o run **SUCEDE** — quem reprova é o humano
   no review. São dois loops de reincidência diferentes.
6. **A premissa da ADR-0011 morreu.** Ela diz (ecoada em `IssueService.java:221`): *"o feedback do
   revisor entra **editando a Issue** antes do REOPEN"*. A Frente C tornou isso obsoleto. **Não falta
   endpoint: falta ENTREGA.**

### Trabalho que implica

- **Fatia nova (v1) — feedback do review ao executor.** ⚠️ **DEPENDE DA F-C3** (usa o `MemoryAudience`
  e o materializer que ela está construindo). Cai naturalmente **depois** dela — como o fecho residual.
  - Contorno: **mais uma dieta** na audience do executor = *as Decisões ancoradas na issue do run*.
  - **PUSH, não pull**: **materializar** (o agente *recebe*), não deixar no `memory_fetch` (o agente
    *escolheria* buscar — e pode não buscar). Feedback de reject não é opcional.
- **Emenda à ADR-0011** (a premissa "editando a Issue antes do REOPEN" está morta).
- **NÃO fazer:** endpoint de edição de Issue.

---

## Item 4 (lista H) — bump do pino claude 2.1.201 → 2.1.206 — **RESOLVIDO: manter o pino**

### Veredito

**Manter o pino em 2.1.201.** O bump vai numa **fatia de infra própria** (rebuild + smoke dos 2 motores),
**quando houver um segundo motivo** para mexer na imagem. **Nunca de carona, nunca na véspera do fecho.**

### Racional verbatim do dono

> "perfeito, podemos seguir assim"

### Achados do grill

Este é o item em que **o princípio do dono aponta para o lado CONTRÁRIO** — e isso foi explicitado a ele
para que "façamos já" não virasse carimbo automático:

1. **O problema conhecido é do BUMP, não da manutenção.** Não é *"sabemos que pode dar problema"* — é
   *"sabíamos, e já cintamos"*. A gotcha (claude **<2.1.205 ignora schema inválido em silêncio**) já está
   coberta pelos **2 cintos** (schema construído programaticamente — não há typo possível; JSON
   faltando/malformado é **marcado `malformado`** pelo backend).
2. **A ADR-0039 já escreveu a defesa e já deixou a decisão ao dono** (linha 111): *"o valor de subir para
   ≥2.1.205 (erro duro em vez de silêncio) é **marginal** frente ao custo/risco de mexer no pino."*
3. **O host (2.1.206) é MAIS estrito que a imagem** — erro duro em vez de silêncio. É o lado **seguro**
   para depurar. A divergência host×imagem corre a favor, não contra.
4. **O pino é o ESTADO PROVADO:** os VENDOs e o `/verify` nos 2 motores reais passaram **com 2.1.201**.
   Bumpar agora **troca a base de execução na véspera do fecho** e **invalida as provas que o sustentam**.
5. **Superfície de acoplamento enorme:** só o `ClaudeArgv` depende de `-p`, `--output-format stream-json`,
   `--verbose`, `--json-schema`, `--permission-mode bypassPermissions`, `--mcp-config`,
   `--strict-mcp-config`, `--resume` — mais o `ClaudeStreamParser`, os hooks PreToolUse (F-N6) e o
   MCP-stdio (F-AT2).
6. **A F-C3 está mexendo no MCP bridge AGORA.** Bumpar a CLI justo enquanto a faixa paralela toca o MCP
   é procurar um bug de duas cabeças.
7. **Não é obra de produto.** Não fecha promessa de ADR nem tapa buraco de governança — a régua
   anti-círculo nem se aplica. É higiene de infra.

### Trabalho que implica

**Nenhum agora.** Registrar apenas o **gatilho**: *"o próximo segundo motivo para mexer na imagem"*.
Disciplina mantida (ADR-0014/0016): o bump vai **com racional no PR**.

---

## Item 2 (lista H) — veto de redação dos textos shippados — **RESOLVIDO: manter 6, corrigir 1**

### Veredito

**Os 6 textos ficam como estão.** **Uma correção:** **acentuar as strings de usuário do
`RacionalPolicy`**.

### Racional verbatim do dono

> "perfeito seguiremos assim por agora"

### Achado do grill — o deslize que só aparece lendo lado a lado

**Os textos do `RacionalPolicy` são os ÚNICOS sem acento no sistema — e não há razão para isso.**

```
"Registro obrigatorio (ADR-0020): qual e a razao desta decisao?"
"Probe maieutico (ADR-0020/D10): o que exatamente te agrada aqui? O que voce notou que o agente nao viu?"
"Alguma ressalva ou risco que valha registrar? (nao bloqueia o approve; sem resposta, nada vira memoria)"
```

Os vizinhos **acentuam normalmente**: *"Proposta descartada — nada chegou ao gate"*, *"A #12 não coube em
nenhum time"*, *"Estouro do cap: 3 direções vigentes ficaram fora da carta"*.

**A causa é CONTÁGIO.** No mesmo arquivo, as *constantes de comparação* (`CONFAB_FORMS`,
`CREATIVE_AREA_MARKS`) **precisam** ser sem acento — o matcher **normaliza** o texto antes de comparar
(`RacionalPolicy.java:29`). O autor tirou o acento por necessidade técnica ali, e **o hábito vazou para as
mensagens humanas**. Prova de que não é encoding: os **comentários do próprio arquivo são acentuados**
("classificação", "maiêutico", "presença obrigatória").

E são justamente os textos que **o dono lê toda vez**, no gate e no review.

### Trabalho que implica

- **Correção (fecho residual) — acentuar as 7 strings de usuário do `RacionalPolicy`:**
  - `grillProbe` vazio (INTUICAO): *"Registro obrigatorio (ADR-0020): o que te agradou (ou incomodou) aqui?"*
  - `grillProbe` vazio (LOGICO): *"Registro obrigatorio (ADR-0020): qual e a razao desta decisao?"*
  - `grillProbe` confab (prefixo): *"\"Porque quis\" nao e razao nem intuicao (ADR-0020/D7). "*
  - `grillProbe` confab (INTUICAO): *"Diga a preferencia real: o que te agradou?"*
  - `grillProbe` confab (LOGICO): *"Diga a razao substantiva: que premissa sustenta isso?"*
  - `maieuticProbe`: *"Probe maieutico (ADR-0020/D10): …"*
  - `reviewProbe` (LOGICO, o texto do C14): *"Alguma ressalva ou risco que valha registrar? …"*
  - 🚨 **NÃO TOCAR** `CONFAB_FORMS` nem `CREATIVE_AREA_MARKS` — **têm de ficar sem acento** (o matcher
    compara contra texto normalizado; acentuá-las **quebra a detecção de confabulação**).
  - ⚠️ **Arrastam junto:** `RacionalPolicyTest` (asserta os textos) e o fallback do frontend
    (`decision-dialog.tsx:106`).
- **Aprovados, sem mexer (6):**
  1. `ProposalDescarteNotice` — prefixo + os 2 textos (emissor barrado · shape quebrado) — ADR-0036;
  2. os **5 avisos M6 da absorção** (já tem dona · não coube em time · time sem arquiteto · mudou de
     estado · o protocolo feliz) — ADR-0037;
  3. o assunto da conversa absorvida: `#N — <título>` (V27/ADR-0037);
  4. o card `CREATE_TEAM` no gate — *"Criar time"* (ADR-0037);
  5. as **3 nudges** da carta/playbook (candidata · estouro-carta · estouro-playbook) — F-C2;
  6. o card `GRADUATE_PRINCIPLE` — *"Graduar princípio"* (frase editável) — F-C2.

---

## Item 5 (lista H) — destino dos artefatos vivos de VENDO — **VIROU BUG DE PRODUTO**

### Veredito

1. **Consertar o lote — UMA Decisão por lote, não por item — fatia do v1.**
2. **Rejeitar os 27 PENDING da demo DEPOIS do conserto** (a rejeição vira a prova viva da correção).
3. Os demais artefatos ((b) cascata parada da Demo Fixtures · (c) artefatos da G3) — **ficam**; o dono
   não pediu limpeza deles.

### Racional verbatim do dono

> "quero poder rejeitar em lote sim"

E, ao conserto proposto (1 Decisão por lote, rejeição dos 27 depois dele):

> "pode ser, podemos fazer assim"

### Achado do grill — o mais valioso da sessão

**A lista H tratava isto como housekeeping da demo. É um BUG DE PRODUTO.**

`ApprovalBatchService.decideBatch` recebe **UM racional para o lote inteiro** e o **carimba em cada
`decide()`**, item a item (`:36`, `:42-45`):

```java
decideBatch(UUID companyId, ApprovalDecision decision, String racional, ...)   // ← UM racional
    for (Item item : items)
        approvals.decide(..., racional, decisionReason(motivo, item.observacao()), ...)
```

**O modelo de dados já sabe que o lote é UM ato com N alvos** (racional único do lote + observação por
item). Mas desde que a **F-C1 (PR #25)** acendeu os writers, **cada `decide()` fabrica sua própria
Decisão** — e essa porta *"sempre nasce"* (`ApprovalService.java:238-248`). Resultado: **um ato do dono
vira N Decisões idênticas, com o mesmo racional literal, no Decision-log.**

> 🔴 **A ADR-0035 (lote, PR #12) NUNCA foi reconciliada com a F-C1 (writers acesos, PR #25).**
> As duas fatias landaram em momentos diferentes e ninguém cruzou.

**Consequência em PRODUÇÃO, não só na demo:** rejeitar N propostas em lote → N Decisões clonadas **+ uma
nudge FALSA de candidata a graduação**. O detector C5 varre o log **inteiro sem filtrar tipo** e dispara
com **3** na mesma área (`CompanyMemoryConsolidator:178-184`). A nudge aparece **na carta** — que é o
artefato que **os agentes leem**.

**O que ATENUA (e por que não é catastrófico):** a área do `HIRE_AGENT` é `"estrutura-org"`, e
`DecisionLog.tipoFor()` a classifica como **`OPERACIONAL`** (`:77-89`) → **não entra na carta** (que só
projeta `DIRECAO`+`DECISAO`) **nem no playbook** (só `PRINCIPIO`). O envenenamento é do **log** e da
**nudge** — não da carta em si.

**Fatos de apoio verificados:**
- **Nada expira.** O `RowRetentionReaper` coleta só *rastro de eventos* (`preview_server_thumbnail`,
  `preview_artifact`, `heartbeat_run_event` + run-events de conversa). **Não toca approvals nem
  conversas.** Nenhum outro reaper mexe no `ApprovalRepository`. "Manter" é **para sempre** — mas
  "rejeitar" **também deixa rastro para sempre**.
- **Risco de aprovar os 27 por acidente: baixo.** A tela de lote tem cinto: *"o lote só habilita com
  filtro de tipo ativo (D2)"* e age *"nos SELECIONADOS (D3)"* (`approvals/page.tsx:36-37`).

### Trabalho que implica

- **Fatia nova (v1) — reconciliar o lote com a Frente C: 1 Decisão por lote.**
  - A linhagem dos N approvals vai na **`evidencia`** — o campo **já existe** e a F-C2 **já o usa assim**
    (a linhagem da graduação).
  - Precisa: um **`Anchor` de lote** (hoje `Anchor.NaApproval` é **singular**) e um **seam para suprimir**
    a fabricação por-item quando a chamada vem do lote.
  - ⚠️ **`ApprovalBatchService` é DELIBERADAMENTE não-transacional** (cada `decide()` abre a própria tx —
    `:14-15`). O desenho tem de respeitar isso.
- **Emenda à ADR-0035** (e provavelmente menção cruzada na 0019/0020).
- **Housekeeping da demo, PÓS-conserto:** rejeitar os 27 `HIRE_AGENT` PENDING em lote.
- **Caminho DESCARTADO (registrado com o porquê):** rejeitar os 27 **antes** do conserto — sujaria o
  Decision-log com 27 clones e acenderia a nudge falsa na carta da demo.

---

## §6 — Correções que a `lista-h-fecho-v1.md` (TRACKED) precisa

**Não aplicadas nesta faixa** (a lista tracked não foi tocada). Insumo do fecho residual:

| # | O que a lista H afirma | O que o código mostra |
|---|---|---|
| 1 | *"M9 corta reincidência"* (item 3) | **Falso.** Os `M9` são marcadores locais por ADR; nenhum olha erro repetido. |
| 2 | *"Só o primeiro [teto] existe (`BudgetPolicy.orchestrationLimitCents`)"* (item 3) | **Errado duas vezes.** O teto (a) é **dispensado pela própria ADR-0018** no v1; e o campo citado é o teto de **atos** da **ADR-0016** (T.4) — outro mecanismo. **Dos 3 tetos da 0018, zero existem.** |
| 3 | *"o feedback do revisor fica preso em `review.decision_reason`"* (item 1) | **Desatualizado** (pré-F-C1). O REJECT já **exige** motivo e ele **já vira Decisão** ancorada na issue. O buraco é que **o executor não recebe memória**. |
| 4 | *"a própria tela de lote rejeita todos numa ação, que é em si mais uma prova viva"* (item 5) | **Desaconselhado hoje:** o lote fabrica **N Decisões clonadas** + nudge falsa. Vira prova viva **depois** do conserto. |
| 5 | ADR-0018 promete **cascata** de escalação | **Nunca existiu**, e foi **decidido não construir** (escalação direta ao dono). Emendar. |

---

## Handoff de volta à sessão de controle (contrato do ledger)

| Item do contrato | R17-B |
|---|---|
| **Branch / PR / merge** | **Nenhuma.** Faixa DECISÕES-ONLY: zero commit, zero branch. O checkout compartilhado estava (e **permanece**) em `feat/frente-c-indice-jit` com a **F-C3 em voo** — **HEAD intocado**, leitura via `git show main:<path>` e leitura do working tree. |
| **Arquivos tocados** | **1, novo e untracked:** `docs/handoff-decisoes-lista-h.md` (este). Os 4 untracked deliberados **intocados**. `docs/lista-h-fecho-v1.md` (tracked) **NÃO editada**. |
| **`Vxx` consumido** | **Nenhum.** |
| **Testes / piso** | **Não rodados** (nenhum código tocado). Piso segue **702** backend · **294** front. |
| **O que ficou por fazer** | **As 5 decisões geraram 3 fatias de código no v1** (ver §consolidado) — nenhuma aplicada aqui, **por construção do regime**. |
| **Decisões novas no caminho** | 4 correções de fato na lista H tracked (§6) + **1 bug de produto descoberto** (lote × F-C1). |

### Consolidado do trabalho novo — **o v1 CRESCEU**

Cada decisão foi tomada isolada; o **agregado** é maior que a soma percebida. **Três fatias de código
novas entram no v1:**

| # | Fatia (v1) | Depende de | Tamanho estimado |
|---|---|---|---|
| 1 | **Circuit-breaker de erro repetido** (item 3c) | — | pequena (chokepoint único já existe) |
| 2 | **Feedback do review → executor** (item 1) | 🔗 **F-C3** | pequena (reusa o `MemoryAudience` da F-C3) |
| 3 | **Lote × Decision: 1 Decisão por lote** (item 5) | — | pequena/média (`Anchor` de lote + seam de supressão) |

**Mais, no fecho residual:**
- correção de texto: **acentuar as 7 strings de usuário do `RacionalPolicy`** (+ testes + fallback do front);
- **3 emendas de ADR:** **0018** (cascata fora · teto (a) não-promessa · teto (b) → v1.1) · **0011**
  (premissa "editar a Issue" morta) · **0035** (1 Decisão por lote);
- **correção da `lista-h-fecho-v1.md`** tracked (§6 — 5 itens);
- **housekeeping pós-conserto:** rejeitar os 27 `HIRE_AGENT` PENDING da demo.

**v1.1 (registro):** candidata **#11** do roadmap fica **só com o contador de riscos do plano** (item 3b).

**Sem trabalho:** o **pino** (item 4) — só o gatilho registrado.

### Alerta ao controle

> As 3 fatias novas **não estavam no plano de fecho**. O item 2 (feedback→executor) **depende da F-C3**,
> então **não pode ser emitido antes dela**. As outras duas (circuit-breaker, lote×Decision) são
> **independentes** e podem ir em paralelo — respeitada a regra **máximo 2 faixas em voo** e a regra
> **faixa paralela = git worktree** (F-N5/ADR-0041).
