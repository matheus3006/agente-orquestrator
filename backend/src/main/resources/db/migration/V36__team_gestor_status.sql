-- F-ORG-B (ADR-0046, D-ORG-2): o marcador de autoridade delegada do gestor. Todo time com
-- gestor_agent_id preenchido nasce em ESTAGIO; um ato explícito e board-only do dono promove pra
-- PROMOVIDO, com rastro de quem e quando. Backfill honesto: DEFAULT 'ESTAGIO' cobre todo time
-- existente (com ou sem gestor hoje) — nenhuma promoção de graça.
--
-- Estado no TIME, não no agente (mini-grill da fatia, Pergunta 2): a confiança do dono é no
-- julgamento daquele agente NAQUELE braço específico, não um selo que atravessa a empresa — por
-- isso reseta pra ESTAGIO toda vez que o titular do posto de gestor troca (TeamService.setGestor).
--
-- Nada de columnDefinition pg-only (lição da V32): tipos crus que o H2 também entende.

alter table team add column gestor_status varchar(20) not null default 'ESTAGIO';
alter table team add column gestor_promovido_em timestamp;
alter table team add column gestor_promovido_por_actor_type varchar(20);
alter table team add column gestor_promovido_por_actor_id uuid;
