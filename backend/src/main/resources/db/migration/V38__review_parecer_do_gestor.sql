-- F-ORG-C (ADR-0048, D-ORG-1/2): o PARECER consultivo do gestor em ESTÁGIO, ao lado do card de
-- review do dono. Molde da V36: valor + QUEM + QUANDO — o rastro de autoria é parte do dado, não
-- convenção.
--
-- Campo NOVO, não sequestro do decision_reason: aquele texto livre já está semanticamente ocupado
-- pelo revisor que DECIDE (obrigatório no REJECT, IssueService) — misturar os dois apagaria a
-- distinção entre "quem opinou" e "quem decidiu", que é exatamente a fronteira desta fatia.
--
-- Cardinalidade 1-para-1 com o CICLO (a linha review), não com a issue: reject → REOPEN → re-run
-- abre um ciclo NOVO, com slot de parecer novo. É isso que faz prosa reformulada pelo LLM não
-- furar o dedupe sem fingerprint (ADR-0048 §4) — o zumbi do lease-reaper reencontra o mesmo ciclo
-- já com parecer e o gate descarta com aviso.
--
-- ⚠️ CORREÇÃO (fecho do parecer, 2026-07-20): este comentário dizia que a cardinalidade fazia o
-- dedupe "sair de graça". Isso foi REFUTADO no hardening do PR #50 e a ADR e o Java já tinham sido
-- emendados — só este arquivo carregava a alegação. De graça saía o dedupe SEQUENCIAL; sob
-- concorrência real o ramo ESTÁGIO era read-then-write puro e 6 settles simultâneos gravavam os 6.
-- Hoje quem arbitra é o UPDATE guardado (`and status = 'PENDING' and parecer is null`), não a
-- cardinalidade. Migração já aplicada não se reescreve; o comentário, sim.
--
-- O ramo PROMOVIDO NÃO escreve aqui: lá o gestor decide, e o rastro dele são as colunas de decisão
-- que já existem desde a V11 (reviewer_actor_type = 'AGENT' + reviewer_actor_id + decided_at).
--
-- Nada de columnDefinition pg-only (lição da V32): tipos crus que o H2 também entende.

alter table review add column parecer text;
alter table review add column parecer_por_agent_id uuid references agent (id);
alter table review add column parecer_em timestamptz;
