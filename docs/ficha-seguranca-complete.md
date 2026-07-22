# Ficha de segurança — `POST /issues/{id}/complete` e a cunhagem de chave de agente

> Origem: grill "posse do run", **D-POSSE-8** (rodada 2, 2026-07-21). Achado que não é do grill nem
> da fatia que o encontrou — por isso mora em arquivo próprio, com dono e gatilho.
>
> 🔴 **TRAVA DE ORDEM, decisão do dono.** O furo abaixo **não podia ser fechado** antes de a Fatia I
> (lock órfão) estar em `main`: até lá ele era a **única cura existente** para uma Issue travada, e
> fechá-lo primeiro transformaria dano contornável em dano permanente de verdade.
>
> ✅ **TRAVA LIBERADA** (Fatia I em `main` desde `454d8eb`; Fatia I-bis fecha o ponto cego da rede).
> Ver §Gatilho do conserto no fim desta ficha.

---

## O furo, medido

`POST /api/companies/{c}/issues/{i}/complete`:

- lê o **`runId` do corpo da requisição** (`IssueController.CompleteRequest`);
- exige ator do tipo `AGENT` (`requireAgent`) mas **descarta a identidade do ator** — não a compara
  com o `agent_id` do run nem com o `assignee_agent_id` da Issue;
- o `executionLockRunId` necessário é **exposto na própria listagem de issues**.

E `POST /api/companies/{c}/agents/{a}/api-keys` (`AgentApiKeyController.mint`) chama **só
`assertMember`**, devolvendo o segredo em claro. Somando: **qualquer membro da company cunha uma
chave de agente e conclui qualquer Issue em nome de qualquer run.**

> ⚠️ O guard do settle **não** olha o status do Run — basta **passar** um `runId` que case com
> `execution_lock_run_id`. É o mesmo erro que o grill acusa no PR #56: confundir a pergunta sobre a
> **Issue** com a pergunta sobre o **Run**.

---

## Procedimento manual de destrave (enquanto for necessário)

O destrave tem **DOIS RAMOS**, e o ramo que importa é justamente o das Issues mais críticas. O que
decide é o `risk_level` da Issue contra `orquestrador.review.risk-threshold` (**default 5**), porque
`IssueService.complete` fixa `RunOutcome.success()` e o settle roteia por risco.

**Passos 1–3 são comuns aos dois ramos:**

1. `GET /api/companies/{c}/issues` → ler o `executionLockRunId` **e o `riskLevel`** da Issue travada.
2. `POST /api/companies/{c}/agents/{a}/api-keys` → cunhar a chave do agente.
3. `POST /api/companies/{c}/issues/{i}/complete` `{"runId": "<o lock lido no passo 1>"}` (header de
   agente) → **libera o lock**. Para onde a Issue vai depende do ramo:

### Ramo A — `riskLevel` **abaixo** do limiar (o caminho comum, default: 1–4)

O settle cai em `tryComplete` ⇒ a Issue passa por **`DONE`**.

4. `POST /api/companies/{c}/issues/{i}/reabrir` `{"motivo": "<por que foi destravada à mão>"}`
   (**board-only**, guard `status = 'DONE'`, ADR-0049) ⇒ a Issue pousa em **`BLOCKED`**, exatamente
   onde o conserto na fonte a põe, com o motivo em texto no log.

> **A 4ª chamada é OBRIGATÓRIA, não opcional.** Sem ela o procedimento **fabrica entrega falsa**: uma
> Issue que ninguém entregou aparece concluída no board. O javadoc do `tryReabrirAprovada` cobre este
> caso explicitamente — a Issue de risco baixo que chegou a `DONE` por `tryComplete` e **nunca teve
> review**.

### Ramo B — `riskLevel` **≥** o limiar (default: 5) — **o ramo que a forma de 4 chamadas do grill quebrava**

Aqui o passo 3 **não** produz `DONE`. O settle intercepta em `trySubmitForReview` — que também casa,
porque a Issue órfã **é** `IN_PROGRESS` — e a Issue pousa em **`PENDING_REVIEW`**, com um ciclo de
`review` PENDING aberto. Nesse estado o `/reabrir` do Ramo A devolve **409** (o guard dele é
`status = 'DONE'`).

4. `POST /api/companies/{c}/issues/{i}/review`
   `{"decision": "REJECT", "reason": "<por que foi destravada à mão>"}` (**board-only**) ⇒
   `tryReject`: `PENDING_REVIEW → BLOCKED`. Mesmo destino do Ramo A, por outra porta, e o motivo
   vira Decisão ancorada na review **e** na issue (entregue ao agente no re-run, ADR-0011 §Emenda
   R19-A).

> 🔴 **O Ramo B NÃO FUNCIONA se o `runId` do lock for SINTÉTICO** (sem linha em `heartbeat_run`) —
> descoberto na Fatia I-bis, e é exatamente a classe que o `/checkout` sem corpo fabrica sozinho.
> O passo 3 casa o `trySubmitForReview` (a Issue **é** `IN_PROGRESS`), mas em seguida a checagem
> "toda review corresponde a um run REAL desta company" falha ⇒ `ConflictException` ⇒ **rollback
> TOTAL**: o UPDATE guardado é desfeito e a Issue volta a `IN_PROGRESS` com o lock de pé. O
> operador recebe 409 e **a Issue continua travada**.
>
> **Não há 4ª chamada que conserte isso** — o problema é o passo 3. Para essa classe, a saída é a
> **REDE**: desde a Fatia I-bis o `LockOrfaoReaper` colhe lock que aponta para Run sem linha, então
> **não faça nada e espere a varredura** (ver §Condição de expiração). Provado em
> `DestraveManualDosDoisRamosTest.riscoALTOcomRunSINTETICO_aFichaNAOdestrava_masARedeCOLHE`.

**Nos dois ramos o destino é `BLOCKED`**, de onde o `unblock {REOPEN|CANCEL}` do board decide entre
refazer e encerrar.

---

## Condição de expiração — é um FATO, não uma data

**A condição está SATISFEITA.** Fatia I em `main` desde `454d8eb`; **Fatia I-bis** (commit
`8c0cebb`, merge `49be0e8`, PR #60, 2026-07-21) fecha o ponto cego. O `LockOrfaoReaper` colhe
automaticamente toda Issue `IN_PROGRESS` cujo lock **não é segurado por nenhum Run com futuro** — o
que inclui **quatro** classes:

1. Run em `CANCELLED`, `FAILED`, `SUCCEEDED` (Fatia I);
2. Run em **`INTERRUPTED`** (Fatia I-bis, decisão do dono — o argumento da exclusão invocava
   liveness, que a própria justificativa da colheita dispensa);
3. Run **sem linha nenhuma** em `heartbeat_run` (Fatia I-bis — o ponto cego);
4. Run **real, mas de OUTRA company** (Fatia I-bis — a cerca `r.companyId = i.companyId` do
   sub-`exists`). Esta 4ª classe **estava faltando nesta lista**: a própria I-bis a criou e não a
   escreveu aqui.

Ficam de fora só `QUEUED`, `RUNNING` e `RECOVERING`: Runs que **ainda têm futuro**, e cuja Issue
seria arrancada de trabalho que ia continuar.

> 🔴 **E existe uma 5ª classe que a rede NÃO alcança, por construção:** lock nomeando um Run **vivo
> de OUTRA issue da MESMA company**. A cerca correlaciona por `r.id` + `r.companyId`, não por
> `r.issueId`, então esse lock nomeia um Run "com futuro" e a varredura nunca o toca. **Não há
> saída pela rede para essa classe** — ela só se fecha na fonte, e é o que a Fatia I-ter fez
> (componente `issueId` da procedência). Ver ADR-0011 §Resíduo.

**Portanto o procedimento manual acima perdeu a razão de existir para a colheita**, e para a classe
do `runId` sintético com risco alto ele nunca funcionou (ver o 🔴 no Ramo B). Ele permanece nesta
ficha por dois motivos: descrever o **furo de segurança** que ainda não foi consertado, e servir de
saída manual caso alguém precise liquidar **antes** do próximo tick da varredura.

> ⏱️ **O atraso, dito com honestidade:** a varredura roda na cadência
> `orquestrador.heartbeat.reap-ms` (default 5s) e tem **teto de 50 Issues por passada**, drenando o
> `updated_at` mais antigo primeiro. Uma Issue isolada sai no tick seguinte; um passivo de N sai em
> mais de um tick — isso é **conta, não medição**.

## Gatilho do conserto — ✅ EXECUTADO na Fatia I-ter (2026-07-22)

📌 A trava de ordem foi satisfeita pela Fatia I (`454d8eb`) e a liberação foi confirmada
incondicional na conferência do PR #60. **O conserto foi feito.**

Escopo — **os três itens, e o `/checkout` estava faltando na lista**:

| # | Item | Estado |
|---|------|--------|
| 1 | `POST /checkout`: **corpo obrigatório** (parou de fabricar `UUID.randomUUID()`) + `runId` validado | ✅ feito |
| 2 | `POST /complete`: valida que o `runId` do corpo **pertence ao ator** (não mais `requireAgent` sem atribuição) | ✅ feito |
| 3 | `mint` de chave de agente exige **board** (`assertBoardMember`), não `assertMember` | ✅ feito |

> 🔴 **O item 1 não estava nesta lista, e devia estar desde a Fatia I-bis.** O handoff e o javadoc
> do `IssueService.checkout` afirmavam que o `/checkout` "pertence a esta ficha" — mas a §Escopo
> listava só `complete` e `mint`. A emenda foi **declarada e não aplicada**, a marca registrada da
> casa (mesmo padrão do PR #50). Pior: entre a I-bis e a I-ter o `/checkout` sem corpo virou uma
> **regressão ativa** — ele carimbava a Issue com um lock sintético e a rede, agora enxergando essa
> classe, liquidava a Issue **viva** em ≤5 s.

**Como ficou (a cerca, uma só, nos dois endpoints).** `IssueService` exige **procedência** do
`runId`: existe em `heartbeat_run`, é **desta company**, é **deste agente** e é **desta issue**. As
quatro componentes fecham quatro furos distintos, e a última fecha o produtor intra-company que a
**rede jamais alcançaria** (lock nomeando Run vivo de OUTRA issue — ver ADR-0011 §Resíduo). A cerca
mora no **service**, não nos controllers: é o chokepoint que o balcão HTTP e o `AdapterRunExecutor`
compartilham, e é o precedente da casa (o `submitForReview` já fazia assim).

Todos os motivos devolvem o **mesmo 403**, sem distinguir "não existe" de "não é seu" — senão o
endpoint viraria um oráculo de existência de runs para qualquer chave de agente.

### ⚠️ O que o conserto TIROU do operador

A cerca é sobre **identidade**, não sobre **liveness** — um Run em status terminal tem procedência
plena, então o destrave manual abaixo **continua funcionando** para quem é dono do run. Mas duas
coisas mudaram, e ambas restringem o procedimento:

1. **O passo 2 exige o board** (o `mint` virou board-only) — na prática já era assim, já que quem
   executa a ficha é o operador.
2. **A chave tem de ser a do agente DONO do run** que aparece no `executionLockRunId`, não a de um
   agente qualquer da company. Se o dono não for descobrível, o procedimento não é executável.
3. 🔴 **Para lock SINTÉTICO (sem linha em `heartbeat_run`) a ficha agora falha em AMBOS os ramos.**
   Antes falhava só no ramo de risco alto (409 no gate de review); agora a cerca de procedência
   recusa antes do funil, com **403**, para qualquer `riskLevel`. **A única saída para essa classe é
   a REDE** — que já a colhia sozinha. Provado em
   `DestraveManualDosDoisRamosTest.riscoALTOcomRunSINTETICO_aFichaNAOdestrava_masARedeCOLHE`.
