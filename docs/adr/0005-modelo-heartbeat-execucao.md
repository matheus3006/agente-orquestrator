# ADR-0005: Modelo de heartbeat / execução

**Status:** Aceito (2026-06-30)

## Contexto
O motor de execução é o subsistema mais difícil (no Paperclip virou um arquivo de ~12.528 linhas). Precisa: prevenir double-work e runaway, nunca perder sinal de wakeup, e — melhoria sobre o Paperclip — não ficar preso a single-node.

## Decisão
- **Concorrência:** no máximo **1 Run ativo por Agent**; lease chaveado por Agent (um funcionário foca numa coisa por vez).
- **DB-as-queue:** claim via `SELECT … FOR UPDATE SKIP LOCKED` + compare-and-swap (`UPDATE … WHERE status='queued'`). Sem broker externo no v1.
- **Coalescing:** wakeups redundantes para o mesmo Agent **mesclam** no Run vivo; se chegam tarde demais, **promote-on-finalize** (novo Run logo após o finalize). Nada se perde, sem duplicar.
- **Recovery (multi-nó):** cada Run vivo carrega `lease_owner` + `lease_expires_at`; o worker renova o lease; um **reaper** agendado recoloca leases expirados em recovery (idempotente).
- **Dois locks separados** (checkout vs. execução) liberados em UPDATEs distintos, para um handoff de retry nunca clobberar o outro (herdado do Paperclip).

## Alternativas consideradas
- Enfileirar todo wakeup (1=1 Run) — gera Runs redundantes, desperdiça budget.
- Debounce/descartar enquanto roda — perde sinais.
- Liveness process-local (Paperclip) — single-node only (teto duro de escala).

## Consequências
- Multi-nó possível desde cedo (lease é a chave que o `heartbeat_runs` do Paperclip não tinha).
- Complexidade concentrada no módulo `execution` com fronteiras claras — resistir ao god-file.
- Budget é checado no dispatch deste motor (ver ADR-0006).

## Nota de reconciliação (2026-07-10 — SÓ o trecho do claim/SKIP LOCKED; divergência MÉDIA da auditoria de 2026-07-07)

**Escopo desta nota: apenas o mecanismo de claim do bullet "DB-as-queue".** O trecho de
**renovação de lease** (bullet "Recovery") é uma divergência ALTA independente, com decisão já
tomada (implementar a renovação — laudo `docs/laudo-lease-adr0005.md`, 2026-07-10) e fatia
própria de execução; ele segue **intocado** aqui de propósito.

**O claim real não usa `SELECT … FOR UPDATE SKIP LOCKED`.** O mecanismo construído é:

1. **Enumeração sem lock** — `HeartbeatRunRepository.findTop50ByStatusOrderByCreatedAtAsc(QUEUED)`
   (SELECT simples).
2. **CAS no claim** — JPQL `HeartbeatRunRepository.claim`: `UPDATE … SET status=RUNNING,
   lease…, attempt+1 WHERE id=:id AND status=:queued`; rows==1 → claimed, rows==0 → outro
   worker venceu (no-op, não erro).

A metade compare-and-swap do desenho original existe; a metade SKIP LOCKED não. O invariante que
o SKIP LOCKED servia (**nenhum double-claim**) é honrado pelo CAS sozinho: workers concorrentes
podem enumerar as mesmas linhas QUEUED, mas só um vence o UPDATE guardado — o custo é contenção
benigna na enumeração, não double-work. O único `FOR UPDATE` do backend é o `PESSIMISTIC_WRITE`
no **Agent** (`AgentRepository.findByIdForUpdate`), que serializa o dispatch por agente — não é o
claim do Run. Direção decidida no levantamento de paralelismo: **doc passa a refletir o código**;
adotar SKIP LOCKED fica como otimização possível se a enumeração virar gargalo multi-worker (não
é correção de corretude).
