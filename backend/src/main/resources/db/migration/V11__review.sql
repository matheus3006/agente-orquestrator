-- Review gate (ADR-0012): risco 1-5 na Issue + entidade review (request→decision, pós-hoc).
-- O status novo PENDING_REVIEW não custa migration (issue.status é text livre).

alter table issue add column risk_level int not null default 1;

create table review (
    id                  uuid        primary key,
    company_id          uuid        not null references company (id),
    issue_id            uuid        not null references issue (id),
    run_id              uuid        not null references heartbeat_run (id),
    status              text        not null default 'PENDING',
    reviewer_actor_type text,
    reviewer_actor_id   uuid,
    decision_reason     text,
    requested_at        timestamptz not null,
    decided_at          timestamptz
);

create index idx_review_company_issue  on review (company_id, issue_id);
create index idx_review_company_status on review (company_id, status);

-- Guard de PRODUCAO (Postgres-only, indice parcial): no maximo 1 review PENDING por Issue.
-- O H2 dos testes nao forca (JPA nao declara indice parcial) — desvio consciente registrado na entidade.
create unique index uq_review_pending on review (issue_id) where status = 'PENDING';
