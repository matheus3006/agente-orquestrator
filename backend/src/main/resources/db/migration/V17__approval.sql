-- Teams T.3 (ADR-0018): a Approval PRÉ-HOC — o CEO propõe pela SAÍDA, o board decide, APPROVE
-- dispara a ação real com ator=aprovador. ESPELHA a review (V11): request→decision, motivo e
-- racional NA TABELA (nunca no activity_log — ADR-0009). Racional por grill (ADR-0020):
-- racional_tipo derivado do artefato no propose (D9); grill_rounds conta as insistências (a taxa
-- auto-liquidante de D12); racional_arrancado = veio de grill.

create table approval (
    id                    uuid        primary key,
    company_id            uuid        not null references company (id),
    requested_by_agent_id uuid        not null references agent (id),
    -- A âncora da thread (mockup §4): a proposta nasceu num turno de conversa.
    conversation_id       uuid        references conversation (id),
    run_id                uuid        references heartbeat_run (id),
    action_type           text        not null,
    -- Os ARGUMENTOS da ação (jsonb canônico de campos mínimos/IDs — a prosa fica na thread).
    target_detail         jsonb       not null,
    -- md5 do alvo canônico (chaves ordenadas): a identidade do dedupe de PENDING.
    target_fingerprint    text        not null,
    area                  text        not null,
    resumo                text,
    status                text        not null default 'PENDING',
    decided_by_actor_type text,
    decided_by_actor_id   uuid,
    decision_reason       text,
    racional              text,
    racional_tipo         text        not null,
    racional_arrancado    boolean     not null default false,
    grill_rounds          int         not null default 0,
    requested_at          timestamptz not null,
    decided_at            timestamptz
);

create index idx_approval_company_status on approval (company_id, status, requested_at);
create index idx_approval_company_conversation on approval (company_id, conversation_id, requested_at);

-- Guard de PRODUCAO (Postgres-only, indice parcial — mesmo desvio consciente da uq_review_pending):
-- re-emissão da MESMA proposta (agente+ação+alvo) não empilha PENDING na fila do board. O H2 dos
-- testes não força; o dedupe do ApprovalService é a 1ª face.
create unique index uq_approval_pending
    on approval (company_id, requested_by_agent_id, action_type, target_fingerprint)
    where status = 'PENDING';
