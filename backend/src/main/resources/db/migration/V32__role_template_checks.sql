-- Uso B — validador de planos (docs/desenho-spring-ai-verificador-auxiliar.md §2/§4.b), fatia B-i.
-- As CHECAGENS pontuais que o molde do papel declara: asserções FACTUAIS (presença/ausência) que dá
-- pra responder olhando o texto do plano, sem opinar. DUPLA FUNÇÃO, sem duplicar: o MESMO conjunto
-- (i) entra no briefing do arquiteto (IssuePromptComposer) — ele já sabe o que o plano deve ter — e
-- (ii) alimentará o validador da B-ii. Metadado de briefing POR-VERSÃO (NÃO é snapshot de hire → fora
-- do Preset). Espelha o skillset jsonb (V14:31): a coluna é (de)serializada pelo RoleTemplateService.
-- Cada check = {"id": string, "text": string}.
alter table role_template_version add column checks jsonb;

-- Backfill do seed v1 do papel ARQUITETO (role_especifica='architect', V14:51/58-59). O pin é IMUTÁVEL
-- por agente e os arquitetos vivos estão pinados nesta v1 semeada; anexar uma v2-com-checks NÃO os
-- alcançaria. Como `checks` é coluna RECÉM-CRIADA e esta é a linha-SEED de SISTEMA (não uma versão que
-- um usuário autorou), o backfill único aqui NÃO fere o append-only — que protege as versões autoradas,
-- não o seed inicial de uma coluna nova. Assim TODO agente pinado na v1 do arquiteto ganha as 3
-- checagens nível-plano (prosa-amigáveis) aprovadas pelo dono. (Alternativa rejeitada: anexar v2 +
-- re-pinar os agentes vivos = mais invasiva.)
update role_template_version
   set checks = '[
     {"id": "seam",      "text": "O plano aponta o seam/encaixe (arquivo/módulo) que vai tocar?"},
     {"id": "file-line", "text": "Cada tarefa/passo cita arquivo:linha concreto?"},
     {"id": "test",      "text": "O plano tem seção ou menção explícita de teste?"}
   ]'::jsonb
 where id = '0e1a0000-0000-4000-8000-0000000000a1';
