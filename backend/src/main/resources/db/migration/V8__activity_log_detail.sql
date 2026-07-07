-- Fase 2 (pré-Fatia 6): activity log HÍBRIDO (ADR-0009). Colunas tipadas (V1) + detail jsonb.
-- detail carrega SÓ IDs/enums/números — nunca body/segredo/PII (mascarado na ingestão pelo writer).

alter table activity_log add column detail jsonb;

-- Imutabilidade no banco (append-only). Guard POSTGRES-ONLY: o H2 (schema gerado das entidades nos
-- testes) não força — mesmo padrão do índice único parcial de runs. O writer é INSERT-only por design.
create or replace function activity_log_block_mutation() returns trigger as $$
begin
    raise exception 'activity_log e append-only (imutavel): % nao permitido', tg_op;
end;
$$ language plpgsql;

create trigger trg_activity_log_immutable
    before update or delete on activity_log
    for each row execute function activity_log_block_mutation();

-- Defense-in-depth (least-privilege): se a app rodar com um role SEM ownership da tabela, negar mutação.
-- No setup atual o role da app pode ser dono (aí o trigger acima é a garantia efetiva). Descomente e
-- ajuste o nome do role quando houver um role de aplicação dedicado:
-- revoke update, delete on activity_log from orquestrador;
