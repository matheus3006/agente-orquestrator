-- R22-A (conserto do bug #1 do roadmap de ajustes, ADR-0037 emendada — decisão 4 do dono,
-- 2026-07-16): o sistema ANOTA o endereço do entregável do bastão.
--
-- conversation.bastao_path — o campo `bastao` do último bloco ```handoff``` postado na conversa
-- (ex.: ".orq/conversa/<conv>/estrategia.md"). Antes, o endereço só existia DENTRO do texto do
-- sentinela de continuação (PersonaContinuationSentinel) — não-consultável; é por isso que a
-- ESTRATÉGIA do Planejador nunca chegava a quem trabalha (achado §3 da prova final do v1).
--
-- Contrato: LAST-WINS — o nome do arquivo VARIA por etapa (dossie.md no Q→P, estrategia.md no
-- P→E; quem batiza é o CEO), então a coluna guarda o endereço VIGENTE, atualizado a cada bastão
-- postado (PersonaContinuationPoster.postBaton). É índice, nunca conteúdo (ADR-0019): o arquivo
-- vive no workspace da conversa. Nula em conversa sem bastão (nenhum handoff ainda) — o
-- compositor da issue compõe só issue nesse caso, nunca null.
--
-- Sem backfill: o texto histórico dos sentinelas não é fonte confiável de parse (nunca confiar
-- em bloco/texto de LLM — o mesmo princípio do `origem` do PersonaBatonRouter); conversas velhas
-- ficam nulas e o próximo bastão anota.

alter table conversation add column bastao_path text;
