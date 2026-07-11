-- Absorção de issues do board (ADR-0037, D4): issue = conversa DIRECIONADA — toda issue
-- ABSORVIDA ganha a SUA conversa, ancorada 1:1 (assunto determinístico "#N — <título>").
-- Nula em issue não-absorvida (gate/corretiva/legado); o trilho fica pronto para a
-- generalização "toda issue nasce com conversa" (fatia futura, §G.4 do handoff).

alter table issue add column conversation_id uuid null references conversation(id);

create index idx_issue_conversation on issue(conversation_id);
