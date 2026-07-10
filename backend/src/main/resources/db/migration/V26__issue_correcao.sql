-- Caso B (ADR-0034): issue.corrige_issue_id — a LIGAÇÃO task-corrige-task (self-FK 1:1).
-- Vazia em task normal; preenchida SÓ na corretiva criada pelo settle do caso B. "Reincidência
-- visível" (racional do B2) vira consulta de 1 linha: quantas corretivas apontam para a #N.
-- A linhagem (correção de correção) é a corrente corrige_issue_id → corrige_issue_id → ... → raiz.

alter table issue add column corrige_issue_id uuid null references issue(id);

create index idx_issue_corrige on issue(corrige_issue_id);
