-- Teams T.3 (ADR-0019): a memória durável. A DECISÃO é a unidade atômica endereçável (D4) —
-- residual 3 do ADR-0019 FECHADO: tabela própria no Postgres; o Log de Decisões filtrável (D5)
-- lê daqui. A CARTA (company_memory kind=CARTA) é PROJEÇÃO DETERMINÍSTICA das Decisões de
-- direção (zero LLM no caminho de escrita), versionada append-only (savelog) e materializada
-- como .claude/CLAUDE.md a cada Run de turno do CEO (ADR-0018).

create table company_memory (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    kind       text        not null,
    body       text        not null,
    version    int         not null,
    created_at timestamptz not null default now(),
    constraint uq_company_memory_kind_version unique (company_id, kind, version)
);

create index idx_company_memory_company_kind on company_memory (company_id, kind, version desc);

create table decision (
    id                 uuid        primary key,
    company_id         uuid        not null references company (id),
    decided_at         timestamptz not null,
    area               text        not null,
    tipo               text        not null,  -- DIRECAO | OPERACIONAL | TECNICA
    origem             text        not null,  -- DONO_NO_GATE | PROPOSTA_CEO | AGENTE
    o_que_mudou        text        not null,
    racional           text        not null,  -- OBRIGATÓRIO (D6 — regime de presença)
    racional_tipo      text        not null,  -- LOGICO | INTUICAO (derivado, D9)
    racional_arrancado boolean     not null default false,
    -- Linhagem (D11): revisar = NOVA decisão apontando a velha; nunca UPDATE.
    supersedes_id      uuid        references decision (id),
    evidencia          text,
    issue_id           uuid        references issue (id),
    approval_id        uuid        references approval (id),
    review_id          uuid        references review (id)
);

-- Os eixos do "Log de Decisões filtrável" (D5): data, área, tipo — sempre liderados por company.
create index idx_decision_company_data on decision (company_id, decided_at desc);
create index idx_decision_company_area on decision (company_id, area);
create index idx_decision_company_tipo on decision (company_id, tipo);
