-- Fase 2, fatia 5: catalogo de Skills. Banco = FONTE DE VERDADE (ADR-0003 / ADR-0007).
-- Import filesystem (.claude/skills/*/SKILL.md) -> banco; materializacao no workspace vem na fatia 6.
--
-- Tudo company-scoped (invariante deny-by-default, ADR-0001): identidade, instalacao e versoes
-- ficam isoladas por Company. REFINAMENTO sobre ADR-0007: 'skill' tambem carrega company_id
-- (a invariante de company-scoping e nao-negociavel; catalogo cross-company nao tem uso no v1 solo).
-- Reversivel se um catalogo global virar requisito.
--
-- Hashes: content_hash = SHA-256 do SKILL.md JA REDIGIDO (LGPD: segredo nunca toca o banco).
-- origin_hash = hash visto na origem no ultimo import; em v1 coincide com content_hash e serve de
-- guarda de drift na reimportacao. Versao nova so quando o conteudo redigido muda.

create table skill (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    skill_key  text        not null,
    created_at timestamptz not null default now(),
    unique (company_id, skill_key)
);
create index idx_skill_company on skill (company_id);

create table company_skill (
    id           uuid        primary key,
    company_id   uuid        not null references company (id),
    skill_id     uuid        not null references skill (id),
    source       text        not null,                      -- LOCAL_PATH | CATALOG | GITHUB | URL
    source_ref   text,                                       -- caminho/URL de origem
    origin_hash  text,                                       -- hash na origem (detecta drift)
    content_hash text        not null,                       -- hash da versao corrente instalada
    state        text        not null default 'INSTALLED',   -- INSTALLED | DISABLED
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    unique (company_id, skill_id)
);
create index idx_company_skill_company on company_skill (company_id);

create table company_skill_version (
    id               uuid        primary key,
    company_id       uuid        not null references company (id),
    company_skill_id uuid        not null references company_skill (id),
    version          int         not null,                   -- sequencial por company_skill
    content_hash     text        not null,
    name             text        not null,                   -- frontmatter: name
    description      text,                                    -- frontmatter: description (redigido)
    triggers         text,                                    -- frontmatter: triggers (redigido)
    body             text        not null,                    -- SKILL.md inteiro, redigido
    created_at       timestamptz not null default now(),
    unique (company_skill_id, content_hash),
    unique (company_skill_id, version)
);
create index idx_csv_company_skill on company_skill_version (company_skill_id);
create index idx_csv_company on company_skill_version (company_id);
