-- Teams T.1 (ADR-0017): MOLDES de role (role-templates) — presets reutilizáveis GLOBAIS e VERSIONADOS.
-- Espelha o catálogo global de Skills (skill/skill_version, V7) + o savelog (company_skill_version).
-- Molde = identidade (role_template) + savelog append-only (role_template_version). O preset carrega o
-- MOTOR como TRIO (adapter/model/effort — desacoplado do engine_catalog, ADR-0014), o SKILLSET (refs
-- skill_key do catálogo global, jsonb) e o reports_to_default (tipo/role SUGERIDO, texto — a cadeia de
-- comando liga em T.2, NÃO é agent id). company_id NULL = global (v1 é sempre global; coluna p/ T.2).

create table role_template (
    id                 uuid        primary key,
    tipo_funcao        text        not null,          -- TipoFuncao (enum app-level, @Enumerated STRING)
    role_especifica    text        not null,          -- a "carne": architect/executor/... (catálogo aberto)
    company_id         uuid,                            -- NULL = global
    current_version_id uuid,
    created_at         timestamptz not null default now()
);

create index idx_role_template_company on role_template (company_id);

-- Unicidade da role GLOBAL. NULL é distinto em unique no Postgres → índice PARCIAL where company_id is
-- null (Postgres-only, como o uq_run_active_agent/V3). O H2 (schema das entidades, sem este índice) fica
-- coberto pelo pré-check + ConflictException do RoleTemplateService (mesmo dual-guard do install-on-attach).
create unique index uq_role_template_global on role_template (role_especifica) where company_id is null;

create table role_template_version (
    id                  uuid        primary key,
    role_template_id    uuid        not null references role_template (id),
    version             int         not null,
    adapter_type        text,                            -- motor: TRIO (desacopla do engine_catalog)
    model               text,
    effort              text,
    skillset            jsonb,                           -- lista de refs skill_key do catálogo global
    reports_to_default  text,                            -- tipo/role sugerido (NÃO agent id)
    change_type         text,
    change_description  text,
    proposed_by         uuid,
    approved_by         uuid,
    status              text        not null default 'APPROVED',  -- DRAFT/APPROVED (governança reusa Review)
    created_at          timestamptz not null default now(),
    constraint uq_rtv_version unique (role_template_id, version)
);

create index idx_rtv_template on role_template_version (role_template_id);

-- Pin por agente (ADR-0017): a versão de molde que o agente copiou no hire. Editar o molde não mexe aqui.
alter table agent add column pinned_template_version_id uuid;

-- Seed curado dos MOLDES DE SISTEMA (differentiator #6 — "time pronto de prateleira"): a base do canvas.
-- current_version_id aponta a v1 já na inserção (sem FK sobre current_version_id; as versões vêm logo abaixo).
-- Os skill_key referenciam o catálogo global do dono (importado à parte); refs que faltam são puladas no hire.
insert into role_template (id, tipo_funcao, role_especifica, company_id, current_version_id) values
  ('0e1a0000-0000-4000-8000-000000000001', 'PLANEJAMENTO_ARQUITETO', 'architect',     null, '0e1a0000-0000-4000-8000-0000000000a1'),
  ('0e1a0000-0000-4000-8000-000000000002', 'EXECUCAO',               'executor',      null, '0e1a0000-0000-4000-8000-0000000000a2'),
  ('0e1a0000-0000-4000-8000-000000000003', 'GESTAO_PO',              'product-owner', null, '0e1a0000-0000-4000-8000-0000000000a3'),
  ('0e1a0000-0000-4000-8000-000000000004', 'LIDERANCA',              'leader',        null, '0e1a0000-0000-4000-8000-0000000000a4');

insert into role_template_version
  (id, role_template_id, version, adapter_type, model, effort, skillset, reports_to_default, change_type, change_description, status) values
  ('0e1a0000-0000-4000-8000-0000000000a1', '0e1a0000-0000-4000-8000-000000000001', 1, 'claude-code', 'opus',   'ultracode',
     '["codebase-design","domain-modeling","tdd"]'::jsonb, 'LIDERANCA',              'inicial', 'molde de sistema', 'APPROVED'),
  ('0e1a0000-0000-4000-8000-0000000000a2', '0e1a0000-0000-4000-8000-000000000002', 1, 'claude-code', 'sonnet', 'high',
     '["tdd","diagnosing-bugs"]'::jsonb,                   'PLANEJAMENTO_ARQUITETO', 'inicial', 'molde de sistema', 'APPROVED'),
  ('0e1a0000-0000-4000-8000-0000000000a3', '0e1a0000-0000-4000-8000-000000000003', 1, 'claude-code', 'sonnet', 'high',
     '["product-management:write-spec"]'::jsonb,           'LIDERANCA',              'inicial', 'molde de sistema', 'APPROVED'),
  ('0e1a0000-0000-4000-8000-0000000000a4', '0e1a0000-0000-4000-8000-000000000004', 1, 'claude-code', 'opus',   'xhigh',
     '[]'::jsonb,                                          null,                     'inicial', 'molde de sistema', 'APPROVED');
