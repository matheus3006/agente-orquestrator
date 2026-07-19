-- Dirigir sem gate (ADR-0047, D-DSG-3/6/7 do grill dirigir-sem-gate).

-- O SELO da issue dirigida sem gate (D-DSG-6): qual gestor PROMOVIDO a dirigiu pro próprio
-- braço. Nula em issue normal E na issue do ramo ESTÁGIO (aprovada em lote no gate — decisão
-- consciente do dono: só o caminho sem-gate carrega a marca).
alter table issue
    add column dirigida_por_gestor_agent_id uuid references agent (id);

-- Backstop do DEDUPE do caminho nasce-decidida (conferência, item 7): é o ÚNICO caminho de
-- criação de Approval sem o uq_approval_pending (que só cobre PENDING). Settle reexecutado
-- (reaper) com o MESMO run + a mesma ordem não pode duplicar cartão+issue: a 1ª face é o
-- jaProtocolado do ApprovalService; este índice parcial é a 2ª (Postgres-only, mesmo desvio
-- consciente do uq_approval_pending — o H2 dos testes não força).
create unique index uq_approval_dirigir_por_run
    on approval (company_id, run_id, action_type, target_fingerprint)
    where action_type = 'DIRECT_ISSUE' and run_id is not null;
