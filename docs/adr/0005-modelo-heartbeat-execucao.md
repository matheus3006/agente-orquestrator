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
