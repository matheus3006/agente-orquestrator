-- Fase 2, Fatia 6: SkillSet base por-agente (ADR-0003). O dono seleciona skills instaladas na
-- biblioteca da company para um Agent, PINANDO uma versão específica (company_skill_version).
-- É o conjunto materializado por padrão em cada Run do agente (Fatia 7 soma os anexos por-Issue).

create table agent_skill_selection (
    id                uuid        primary key,
    company_id        uuid        not null references company (id),
    agent_id          uuid        not null references agent (id),
    company_skill_id  uuid        not null references company_skill (id),
    pinned_version_id uuid        not null references company_skill_version (id),
    created_at        timestamptz not null default now(),
    unique (agent_id, company_skill_id)
);

create index idx_ass_company on agent_skill_selection (company_id);
create index idx_ass_agent on agent_skill_selection (agent_id);
