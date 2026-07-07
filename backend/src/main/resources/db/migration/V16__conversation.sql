-- Teams T.3 (ADR-0018): a conversa do board com o CEO. Chats nomeados por ASSUNTO (todos com o
-- mesmo CEO — muda o assunto, não o interlocutor); cada mensagem do dono vira um TURNO (1 turno =
-- 1 Run). O claim do turno é do MOTOR (run_id preenchido no claim guardado, como o checkout de
-- Issue); a resposta capturada da SAÍDA do Run vira a thread (disclosure: profundidade fica no
-- StepTrace, 1 clique pelo run_id).

create table conversation (
    id         uuid        primary key,
    company_id uuid        not null references company (id),
    subject    text        not null,
    created_at timestamptz not null default now(),
    constraint uq_conversation_company_subject unique (company_id, subject)
);

create index idx_conversation_company on conversation (company_id);

create table conversation_turn (
    id              uuid        primary key,
    conversation_id uuid        not null references conversation (id),
    company_id      uuid        not null references company (id),
    -- O CEO endereçado no momento do post (resolvido por role='ceo'; auditável se o CEO trocar).
    agent_id        uuid        not null references agent (id),
    owner_message   text        not null,
    ceo_reply       text,
    run_id          uuid        references heartbeat_run (id),
    status          text        not null default 'PENDING',
    created_at      timestamptz not null default now(),
    answered_at     timestamptz
);

create index idx_turn_conversation on conversation_turn (conversation_id, created_at);
-- O claim do motor: turno mais antigo PENDING do agente.
create index idx_turn_agent_status on conversation_turn (agent_id, status, created_at);
create index idx_turn_run on conversation_turn (run_id);

-- ADR-0002 (revisão Teams): a Session do adapter generalizada para (Agent + Conversation).
-- FIAÇÃO PRONTA, FEATURE DESLIGADA: o v1 só ESCREVE o handle capturado por Run de turno; o
-- --resume por-Conversation religa lendo daqui. Status/custo/lease da forma-alvo ficam de fora
-- até existir leitor (YAGNI consciente).
create table agent_session (
    id                  uuid        primary key,
    company_id          uuid        not null references company (id),
    agent_id            uuid        not null references agent (id),
    conversation_id     uuid        not null references conversation (id),
    adapter_type        text        not null,
    external_session_id text,
    last_run_id         uuid        references heartbeat_run (id),
    updated_at          timestamptz not null default now(),
    constraint uq_agent_session_agent_conversation unique (agent_id, conversation_id)
);

create index idx_agent_session_company on agent_session (company_id);
