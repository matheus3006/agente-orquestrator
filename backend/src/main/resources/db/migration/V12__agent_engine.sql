-- F.7 (ADR-0014): motor por agente. O adapter_type (já existe, V4) decide qual PLANO de assinatura
-- autentica; model/effort são as flags que o adapter traduz para o CLI do seu runtime.
-- Ambos nullable (sem default) — null = usa o default do plano (o adapter omite a flag).
alter table agent add column model  text;
alter table agent add column effort text;
