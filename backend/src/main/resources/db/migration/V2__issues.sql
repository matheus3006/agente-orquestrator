-- Fase 1, fatia 1: Issue + checkout atomico.
-- Invariante: single-assignee + execution_lock; o claim e um UPDATE guardado (ver IssueRepository).

create table issue (
    id                    uuid        primary key,
    company_id            uuid        not null references company (id),
    title                 text        not null,
    description           text,
    status                text        not null default 'OPEN',
    assignee_agent_id     uuid        references agent (id),
    execution_lock_run_id uuid,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

create index idx_issue_company_status on issue (company_id, status);
create index idx_issue_company_assignee on issue (company_id, assignee_agent_id);
