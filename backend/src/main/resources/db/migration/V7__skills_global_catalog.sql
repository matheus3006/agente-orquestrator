-- Fase 2 (pré-Fatia 6): KnowAll vira NÍVEL-OPERADOR (ADR-0008). Modelo de 2 camadas.
-- Camada 1 = catálogo GLOBAL (skill + skill_version/savelog, sem company_id) = o que o KnowAll conhece.
-- Camada 2 = biblioteca da company (company_skill/company_skill_version) — install COPIA a base global.
-- ALTER sobre a V6 (que criou skill/company_skill/company_skill_version company-scoped).

-- pg_trgm para a busca do KnowAll no catálogo global (Postgres-only; H2 gera schema das entidades).
create extension if not exists pg_trgm;

-- 1) skill: per-company -> identidade GLOBAL. Drop cascata remove unique(company_id,skill_key)+idx.
alter table skill drop column company_id cascade;
alter table skill add constraint uq_skill_key unique (skill_key);
alter table skill add column name               text;
alter table skill add column description        text;
alter table skill add column triggers           text;
alter table skill add column current_version_id uuid;  -- ponteiro p/ a versão-base (sem FK: evita ciclo)
alter table skill add column updated_at         timestamptz not null default now();

-- índice trigram multicoluna para o SkillSearch (metadados, nunca o body). Postgres-only.
create index idx_skill_trgm on skill using gin (name gin_trgm_ops, description gin_trgm_ops, triggers gin_trgm_ops);

-- 2) skill_version = SAVELOG global (histórico das versões-base da Camada 1).
create table skill_version (
    id                 uuid        primary key,
    skill_id           uuid        not null references skill (id),
    version            int         not null,
    content_hash       text        not null,
    name               text        not null,
    description        text,
    triggers           text,
    body               text        not null,             -- SKILL.md redigido (LGPD)
    change_description text,                               -- auto (re-import) ou à mão (deliberada)
    origin             text        not null default 'LOCAL_PATH',  -- LOCAL_PATH (v1) | GITHUB | URL
    source_ref         text,
    created_at         timestamptz not null default now(),
    unique (skill_id, version),
    unique (skill_id, content_hash)
);
create index idx_skill_version_skill on skill_version (skill_id);

-- 3/4) company_skill_version: rastreia de qual versão global veio + descrição da mudança.
alter table company_skill_version add column change_description       text;
alter table company_skill_version add column source_global_version_id uuid references skill_version (id);
-- company_skill.source passa a valer 'CATALOG' no v1 (catálogo global -> biblioteca da company).
