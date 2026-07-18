-- Uso B — validador de planos (docs/desenho-spring-ai-verificador-auxiliar.md §2/§4.b), fatia B-ii.
-- O registro PRÓPRIO do validador: uma linha por CHECAGEM conferida sobre o plano-prosa de um run.
-- Deliberadamente separado do `context_assessment` (V31) — aquele é linter de FONTES de contexto
-- (severidade/categoria/ação sobre ContextSource) e não tem vocabulário para "cumpriu a checagem X".
--
-- DUPLO PAPEL (decisão do grill):
--   (i)  RÉGUA DE PRECISÃO — `owner_audit` nullable é onde o dono marca, depois, se o local acertou
--        aquela checagem. É o que transforma "promoção exige evidência" (Inviolável §4) em dado.
--   (ii) STORE DO PUSH — é daqui que o materializer lê o veredito mais recente da issue para montar
--        a seção `## Checklist do validador` no run N+1 (padrão PUSH da R19-A).
--
-- `presence` guarda TRÊS valores (PRESENT/ABSENT/INDETERMINATE). O INDETERMINATE existe para que
-- "não deu para saber" (resposta vazia/truncada do modelo — MEDIDO acontecendo com orçamento de
-- tokens curto) jamais seja gravado como ABSENT: falso negativo sistemático é o pior modo de falha
-- desta fatia, porque empurraria ao arquiteto um buraco factual que não existe.
CREATE TABLE plan_validation (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES company(id),
    run_id UUID NOT NULL REFERENCES heartbeat_run(id),
    agent_id UUID NOT NULL REFERENCES agent(id),
    issue_id UUID NOT NULL REFERENCES issue(id),
    check_id TEXT NOT NULL,
    check_text TEXT NOT NULL,
    presence TEXT NOT NULL,
    evidence TEXT,
    model TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- NULL = ainda não auditado pelo dono; TRUE/FALSE = o local acertou/errou aquela checagem.
    owner_audit BOOLEAN
);

-- A leitura do push: "o veredito mais recente DESTA issue" (o materializer roda por issue, no prep).
CREATE INDEX idx_plan_validation_issue ON plan_validation(issue_id, created_at DESC);
-- A leitura da régua: todas as checagens de um run, na ordem em que foram conferidas.
CREATE INDEX idx_plan_validation_run ON plan_validation(run_id);
