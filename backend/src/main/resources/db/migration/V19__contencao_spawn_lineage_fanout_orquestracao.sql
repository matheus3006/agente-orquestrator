-- Teams T.4 (ADR-0016 rev. 2026-07-04): a rede de CONTENÇÃO sob o CEO com mãos rodando LOCAL.
-- 1) spawn_lineage: a cadeia de quem CAUSOU o trabalho (raiz '' = ato humano direto). Guard nas
--    duas faces (409 no enqueue + cancel no checkout do dispatcher): acordar ancestral da própria
--    linhagem = recusado (mata loop A→B→A) + cap de profundidade. Módulo execution.lineage.
alter table heartbeat_run        add column spawn_lineage text not null default '';
alter table agent_wakeup_request add column spawn_lineage text not null default '';

-- 2) Fan-out cap por arquiteto: o time não espalha mais runs concorrentes que o cap
--    (409 no enqueue quando criaria run novo + preflight cancela o que entrou por fora).
alter table team add column fan_out_cap int not null default 5;

-- 3) Ledger de orquestração: AGENT_SPAWN/WAKEUP/RUN_OPEN cobrados NA ABERTURA como cost_event
--    (V5) em MOEDA PRÓPRIA — orchestration_action não-nulo marca a moeda; as somas de dólar
--    filtram por nulo. O teto é ORÇAMENTO (orchestration_limit_cents na policy), não contador
--    solto; a conta cai em quem CAUSOU (agent_id = último elo da linhagem; nulo = humano).
alter table cost_event    add column orchestration_action text;
alter table budget_policy add column orchestration_limit_cents bigint;
