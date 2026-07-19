-- F-ORG-A (ADR-0045, D-ORG-9): A ÁRVORE DE TIMES É O ORGANOGRAMA. A empresa é a RAIZ e NÃO é um
-- time: parent_team_id NULO = pendurado direto na empresa (o "departamento"). Não existe linha de
-- raiz sintética — a raiz é a própria company, e é lá que moram o CEO e as 3 personas (sem time).
--
-- GESTOR ≠ ARQUITETO (decisão D-ORG-A.1): dois postos, dois propósitos, dois campos.
--   · arquiteto_agent_id (V15) = responsável TÉCNICO; sua ausência TRAVA o dispatch (ADR-0017).
--   · gestor_agent_id (aqui)   = responsável ORGANIZACIONAL; é de quem a chefia DERIVA, e sua
--     ausência NÃO trava nada (a busca sobe a árvore). Fundir os dois faria "designar gestor"
--     destravar trabalho — gestor não responde tecnicamente pela entrega.
-- Ambos guardam o MESMO invariante: o posto é sempre de um MEMBRO do time (mantido por construção
-- no TeamService — sair do time limpa o posto, nunca ponteiro stale).
--
-- Sem backfill de dado: parent nulo já significa "filho da raiz", então todo time existente JÁ
-- nasce correto na árvore (é exatamente o que a D-ORG-9 pede) e nenhum gestor é inventado. Nada de
-- columnDefinition pg-only (lição da V32): tipos crus que o H2 também entende.

alter table team add column parent_team_id uuid references team (id);
alter table team add column gestor_agent_id uuid references agent (id);

-- Descer a árvore (filhos de um time) é o walk mais quente: subárvore na re-derivação da chefia.
create index idx_team_parent on team (parent_team_id);
