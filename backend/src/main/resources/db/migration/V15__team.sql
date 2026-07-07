-- Teams T.2 (ADR-0017): TIME = entidade real (não só reports_to). Domínio (macro: Dev/Marketing) +
-- foco (livre: frontend/SEO); vários times por domínio — a dupla (dominio, foco) identifica o time
-- dentro da company. O arquiteto é DESIGNADO (arquiteto_agent_id) e NASCE nulo: a trava é no
-- DISPATCH (time sem arquiteto não dispara trabalho), não na criação — montar aos poucos.
-- O invariante "arquiteto é membro do time" é mantido por construção no TeamService (sair do time
-- limpa o posto) — nunca ponteiro stale.

create table team (
    id                 uuid        primary key,
    company_id         uuid        not null references company (id),
    dominio            text        not null,
    foco               text        not null,
    arquiteto_agent_id uuid        references agent (id),
    created_at         timestamptz not null default now(),
    constraint uq_team_company_dominio_foco unique (company_id, dominio, foco)
);

create index idx_team_company on team (company_id);

-- Pertencimento do agente (T.2). NULL = fora de time (dispatch livre — a trava é DO TIME).
-- O reports_to_id (V1, até aqui sem uso) passa a ser a cadeia de comando editável (PATCH
-- /agents/{id}/reports-to, anti-ciclo no ChainOfCommandService).
alter table agent add column team_id uuid references team (id);

create index idx_agent_team on agent (team_id);
