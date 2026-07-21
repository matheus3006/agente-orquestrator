# Ficha de segurança — `POST /issues/{id}/complete` e a cunhagem de chave de agente

> Origem: grill "posse do run", **D-POSSE-8** (rodada 2, 2026-07-21). Achado que não é do grill nem
> da fatia que o encontrou — por isso mora em arquivo próprio, com dono e gatilho.
>
> 🔴 **TRAVA DE ORDEM, decisão do dono.** O furo abaixo **não podia ser fechado** antes de a Fatia I
> (lock órfão) estar em `main`: até lá ele era a **única cura existente** para uma Issue travada, e
> fechá-lo primeiro transformaria dano contornável em dano permanente de verdade.

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

**Nos dois ramos o destino é `BLOCKED`**, de onde o `unblock {REOPEN|CANCEL}` do board decide entre
refazer e encerrar.

---

## Condição de expiração — é um FATO, não uma data

A seção **Procedimento manual** acima perde a maior parte da razão de existir **quando o
`LockOrfaoReaper` estiver em `main`**: a partir daí a colheita é automática para Issues carimbadas
por Run `CANCELLED`, `FAILED` ou `SUCCEEDED`, com no máximo um tick (5s) de atraso.

**O que a Fatia I NÃO cobre, e por isso o procedimento não é apagado inteiro:** o Run
**`INTERRUPTED`** fica **fora** do reaper de propósito (o kill do interrupt é best-effort — o
processo pode estar vivo e ainda vai escrever). Uma Issue carimbada por um Run `INTERRUPTED` cujo nó
morreu antes do settle **continua sem cura automática**, e o destrave acima é a única saída, até o
grill do contrato do settle sob autoridade revogada (**D-POSSE-6**) decidir se `INTERRUPTED` entra
no reaper.

## Gatilho do conserto

📌 **Com a Fatia I em `main`, a trava de ordem está satisfeita e o furo descrito no topo desta ficha
está LIBERADO para conserto.** A liberação viaja com o trabalho que a satisfaz, não com a memória de
alguém — nada no sistema avisa "o reaper entrou, pode fechar agora" (limite conhecido: a trava é
auditável porque está escrita; a **liberação** depende de alguém ler isto).

Escopo sugerido do conserto, quando for puxado:

- `complete` deve **derivar** o `runId` da Issue (ou validar que o `runId` do corpo pertence ao ator)
  em vez de aceitá-lo do corpo sem conferência;
- `mint` de chave de agente deve exigir **board**, não `assertMember`.
