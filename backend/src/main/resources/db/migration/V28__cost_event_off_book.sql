-- F-N5 (ADR-0041, D5): contabilidade OFF-BOOK de subagentes. O gasto de um subagente e REAL mas
-- de OUTRA janela de contexto (nao soma na do turno que o causou) — off_book so MARCA a linha para
-- o handoff (ADR-0015/0030) excluir da conta, sem criar moeda nova (espelha orchestration_action,
-- V19: marcador nao-monetario no mesmo ledger append-only).
alter table cost_event add column off_book boolean not null default false;

create index idx_cost_run_off_book on cost_event (run_id, off_book);
