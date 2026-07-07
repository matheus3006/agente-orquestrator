-- Fase 2, Fatia 7a: anexo de skill por-Issue (ADR-0003 revisado + ADR-0008). Escopo por-ISSUE,
-- IMUTAVEL (append-only): um anexo PINA uma company_skill_version JA instalada na biblioteca da
-- company e vale para todos os Runs daquela Issue (aumento dinamico sobre o SkillSet base do agente).
--
-- Efeito EFEMERO (ADR-0003): a materializacao em disco expira quando a Issue FECHA (o resolveForRun
-- deixa de incluir os anexos de Issue DONE/CANCELLED); o REGISTRO persiste para auditoria. company_id
-- lidera os indices (invariante deny-by-default, ADR-0001). unique (issue_id, company_skill_version_id)
-- torna o re-anexo da MESMA versao idempotente.
--
-- created_in_run_id: audit do Run que originou o anexo (nullable). Em 7a (board via REST) e sempre
-- NULL; passa a ser preenchido em 7c (agente-como-tool anexando durante um Run).

create table issue_skill_attachment (
    id                       uuid        primary key,
    company_id               uuid        not null references company (id),
    issue_id                 uuid        not null references issue (id),
    agent_id                 uuid        not null references agent (id),
    company_skill_version_id uuid        not null references company_skill_version (id),
    attached_by_actor        uuid        not null,                  -- id do ator (board userId em 7a)
    created_in_run_id        uuid,                                  -- audit; NULL em anexos via REST
    created_at               timestamptz not null default now(),
    unique (issue_id, company_skill_version_id)
);

create index idx_isa_company on issue_skill_attachment (company_id);
create index idx_isa_issue on issue_skill_attachment (issue_id);
create index idx_isa_issue_agent on issue_skill_attachment (company_id, issue_id, agent_id);
