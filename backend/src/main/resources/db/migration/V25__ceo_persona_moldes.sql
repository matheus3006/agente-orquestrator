-- Fatia 2 do v1 (ADR-0031, M3): os 3 MOLDES-DE-SISTEMA de CEO (espelham os moldes V14 — globais,
-- company_id NULL) + o BACKFILL das companies existentes (D9: as personas pré-existem "como a
-- Constituição institui os poderes antes do governo"; a teams-demo do VENDO entra por aqui).
--
-- skill_keys dos moldes = as 3 skills internas da fatia (being-ceo / writing-doc-plans / delegation),
-- cuja AUTORIA corre na faixa R1-D: skill_key sem corpo no catálogo é PULADA no copySkillSet (T.1.b)
-- — o molde já aponta o alvo; o corpo chega depois. Skills EXTERNAS (grill-me etc.) entram numa v2
-- do molde quando o sourcing passar pelo veto do dono (savelog versionado: editar não mexe em quem pinou).

insert into role_template (id, tipo_funcao, role_especifica, company_id, current_version_id) values
  ('0e1b0000-0000-4000-8000-000000000001', 'LIDERANCA', 'ceo-questionador', null, '0e1b0000-0000-4000-8000-0000000000b1'),
  ('0e1b0000-0000-4000-8000-000000000002', 'LIDERANCA', 'ceo-planejador',   null, '0e1b0000-0000-4000-8000-0000000000b2'),
  ('0e1b0000-0000-4000-8000-000000000003', 'LIDERANCA', 'ceo-executor',     null, '0e1b0000-0000-4000-8000-0000000000b3');

insert into role_template_version
  (id, role_template_id, version, adapter_type, model, effort, skillset, reports_to_default, change_type, change_description, status) values
  ('0e1b0000-0000-4000-8000-0000000000b1', '0e1b0000-0000-4000-8000-000000000001', 1, 'claude-code', 'opus', 'high',
     '["being-ceo"]'::jsonb,                     null, 'inicial', 'molde de sistema (CEO persona questionador)', 'APPROVED'),
  ('0e1b0000-0000-4000-8000-0000000000b2', '0e1b0000-0000-4000-8000-000000000002', 1, 'claude-code', 'opus', 'xhigh',
     '["being-ceo","writing-doc-plans"]'::jsonb, null, 'inicial', 'molde de sistema (CEO persona planejador)',   'APPROVED'),
  ('0e1b0000-0000-4000-8000-0000000000b3', '0e1b0000-0000-4000-8000-000000000003', 1, 'claude-code', 'opus', 'high',
     '["being-ceo","delegation"]'::jsonb,        null, 'inicial', 'molde de sistema (CEO persona executor)',     'APPROVED');

-- BACKFILL das companies que JÁ têm CEO: o canônico (mais antigo, o mesmo critério do resolvedor)
-- vira o Questionador — preserva identidade, motor e histórico (o Atlas segue o balcão único)…
update agent a set persona = 'questionador'
where a.role = 'ceo' and a.persona is null
  and a.id = (select b.id from agent b
               where b.company_id = a.company_id and b.role = 'ceo'
               order by b.created_at asc, b.id asc limit 1);

-- …e o trio se completa COPIANDO O MOTOR do canônico (o backfill precisa RODAR de verdade — na
-- teams-demo isso herda o codex/LOCAL provado do Atlas; moldes ficam para companies novas). O pin
-- aponta o molde da persona para o re-pin/skills futuros. Idempotente (not exists).
insert into agent (id, company_id, name, role, persona, reports_to_id, adapter_type, runtime_command,
                   execution_target, model, effort, pinned_template_version_id, team_id, created_at)
select gen_random_uuid(), canonico.company_id, p.nome, 'ceo', p.persona, null, canonico.adapter_type,
       canonico.runtime_command, canonico.execution_target, canonico.model, canonico.effort,
       p.molde_versao, null, now()
from (select distinct on (company_id) company_id, adapter_type, runtime_command, execution_target, model, effort
        from agent where role = 'ceo'
       order by company_id, created_at asc, id asc) canonico
cross join (values
    ('CEO Planejador', 'planejador', '0e1b0000-0000-4000-8000-0000000000b2'::uuid),
    ('CEO Executor',   'executor',   '0e1b0000-0000-4000-8000-0000000000b3'::uuid)
) as p(nome, persona, molde_versao)
where not exists (select 1 from agent existente
                   where existente.company_id = canonico.company_id
                     and existente.role = 'ceo' and existente.persona = p.persona);
