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

**A condição está SATISFEITA** (Fatia I em `main` desde `454d8eb`; Fatia I-bis fecha o resto). O
`LockOrfaoReaper` colhe automaticamente toda Issue `IN_PROGRESS` cujo lock **não é segurado por
nenhum Run com futuro** — o que inclui:

- Run em `CANCELLED`, `FAILED`, `SUCCEEDED` (Fatia I);
- Run em **`INTERRUPTED`** (Fatia I-bis, decisão do dono — o argumento da exclusão invocava
  liveness, que a própria justificativa da colheita dispensa);
- Run **sem linha nenhuma** em `heartbeat_run` (Fatia I-bis — o ponto cego, e a classe que o
  `/checkout` sem corpo fabrica sozinho).

Ficam de fora só `QUEUED`, `RUNNING` e `RECOVERING`: Runs que **ainda têm futuro**, e cuja Issue
seria arrancada de trabalho que ia continuar.

**Portanto o procedimento manual acima perdeu a razão de existir para a colheita**, e para a classe
do `runId` sintético com risco alto ele nunca funcionou (ver o 🔴 no Ramo B). Ele permanece nesta
ficha por dois motivos: descrever o **furo de segurança** que ainda não foi consertado, e servir de
saída manual caso alguém precise liquidar **antes** do próximo tick da varredura.

> ⏱️ **O atraso, dito com honestidade:** a varredura roda na cadência
> `orquestrador.heartbeat.reap-ms` (default 5s) e tem **teto de 50 Issues por passada**, drenando o
> `updated_at` mais antigo primeiro. Uma Issue isolada sai no tick seguinte; um passivo de N sai em
> mais de um tick — isso é **conta, não medição**.

## Gatilho do conserto

📌 **A trava de ordem está satisfeita e o furo descrito no topo desta ficha está LIBERADO para
conserto** — sem condição pendente. A liberação viaja com o trabalho que a satisfaz, não com a
memória de alguém: nada no sistema avisa "o reaper entrou, pode fechar agora" (limite conhecido: a
trava é auditável porque está escrita; a **liberação** depende de alguém ler isto).

Escopo sugerido do conserto, quando for puxado:

- `complete` deve **derivar** o `runId` da Issue (ou validar que o `runId` do corpo pertence ao ator)
  em vez de aceitá-lo do corpo sem conferência;
- `mint` de chave de agente deve exigir **board**, não `assertMember`.
