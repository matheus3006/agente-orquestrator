-- F-ORG-C (ADR-0048, D-ORG-1/2): o PARECER consultivo do gestor em ESTÁGIO, ao lado do card de
-- review do dono. Molde da V36: valor + QUEM + QUANDO — o rastro de autoria é parte do dado, não
-- convenção.
--
-- Campo NOVO, não sequestro do decision_reason: aquele texto livre já está semanticamente ocupado
-- pelo revisor que DECIDE (obrigatório no REJECT, IssueService) — misturar os dois apagaria a
-- distinção entre "quem opinou" e "quem decidiu", que é exatamente a fronteira desta fatia.
--
-- Cardinalidade 1-para-1 com o CICLO (a linha review), não com a issue: reject → REOPEN → re-run
-- abre um ciclo NOVO, com slot de parecer novo. É isso que faz o dedupe do settle reexecutado sair
-- de graça, sem fingerprint (ADR-0048 §4) — o zumbi do lease-reaper reencontra o mesmo ciclo já
-- com parecer e o gate descarta com aviso, mesmo que o LLM tenha reformulado a prosa.
--
-- O ramo PROMOVIDO NÃO escreve aqui: lá o gestor decide, e o rastro dele são as colunas de decisão
-- que já existem desde a V11 (reviewer_actor_type = 'AGENT' + reviewer_actor_id + decided_at).
--
-- Nada de columnDefinition pg-only (lição da V32): tipos crus que o H2 também entende.

alter table review add column parecer text;
alter table review add column parecer_por_agent_id uuid references agent (id);
alter table review add column parecer_em timestamptz;
