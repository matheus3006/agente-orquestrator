---
name: verify
description: Verifica mudanças do backend ao vivo SEM tocar o :8080 compartilhado — instância descartável (porta 18081 + Postgres efêmero) dirigida pela API real. Use antes de PR de mudança no motor de execução ou em qualquer fluxo que o teste de unidade não observa em runtime.
---

# Verify — instância descartável (o :8080 é do operador)

**Regra da casa:** o processo do :8080 e o DB `agente-orquestrator-db-1` são compartilhados —
o agente NÃO reinicia nem escreve neles (restart é do operador; leitura read-only ok). Toda
verificação de runtime roda numa instância própria.

## Receita (validada 2026-07-10, fatia do lease)

```sh
# 1. Postgres efêmero (schema real via Flyway no boot do app)
docker run --rm -d --name <fatia>-pg -p 15432:5432 \
  -e POSTGRES_USER=orquestrador -e POSTGRES_PASSWORD=orquestrador \
  -e POSTGRES_DB=orquestrador postgres:16-alpine

# 2. JAR + app próprio (porta livre; overrides por argumento)
cd backend && mvn -o package -DskipTests -q
java -jar target/orquestrador-0.0.1-SNAPSHOT.jar \
  --server.port=18081 \
  --spring.datasource.url=jdbc:postgresql://localhost:15432/orquestrador \
  --orquestrador.workspace.root=<scratchpad>/ws &   # + properties da fatia
# health: curl http://localhost:18081/actuator/health
```

## Dirigir pela API real

- **Ator board (dev):** header `X-Board-User: <uuid qualquer>` (ActorResolutionFilter).
- **Company:** `POST /api/companies` `{"name":…,"slug":…}` (criador vira membro).
- **Agente que dorme o quanto você quiser** (observa o motor sem CLI de agente):
  `POST /api/companies/{c}/agents` com `"adapterType":"process"`,
  `"runtimeCommand":"sleep 12; echo ok"`, `"executionTarget":"LOCAL"`.
- **Run:** `POST /api/companies/{c}/agents/{a}/wakeups` `{"reason":"prova"}` → runId.
- **Observar:** `docker exec <fatia>-pg psql -U orquestrador -d orquestrador -c "select …"`
  sobre `heartbeat_run` / `heartbeat_run_event` (o scheduler roda por default fora do perfil
  test; encurte cadências via property, ex.: `--orquestrador.heartbeat.lease-renew-ms=3000`).

## Teardown

```sh
pkill -f "orquestrador-0.0.1-SNAPSHOT.jar --server.port=18081"; docker stop <fatia>-pg
```

## Gotchas

- Perfil `test` desliga o scheduler (`orquestrador.scheduling.enabled=false`) — para ver o motor
  vivo, NÃO use `--spring.profiles.active=test`; use o default + Postgres efêmero.
- Simular "outro nó levou o run": `update heartbeat_run set lease_owner='node-intruso' where id=…`
  no pg efêmero, com o run RUNNING.

## Dirigir CONVERSA determinística (validado 2026-07-10, fatia do lote)

- Company nova auto-semeia as 3 personas CEO (V25) e o balcão resolve para o **Questionador**
  com motor claude REAL — mensagem de conversa vira run de LLM de verdade. Para dirigir
  determinístico: no pg efêmero, `update agent set adapter_type='process',
  runtime_command='cat <arquivo-reply>', execution_target='LOCAL', model=null, effort=null
  where id='<questionador>'` — a resposta do turno vira o CONTEÚDO do arquivo (reescreva-o
  entre turnos para variar; blocos ```proposta``` em coluna 0 viram approvals PENDING).
- O PATCH /agents/{id} só troca o trio do motor, NÃO o runtime_command — daí o update direto.

## UI ao vivo contra a instância descartável

O checkout compartilhado pode estar em OUTRA branch (sessões paralelas): rode o front de um
`git worktree add <scratchpad>/wt <branch>` + `cp -Rc frontend/node_modules` (clonefile APFS;
symlink quebra o Turbopack) + `.env.local` do worktree com
`BACKEND_ORIGIN=http://localhost:18081` e o board-user de dev. `next dev -p 13000` para não
colidir com o :3000.

## Dirigir issue→run→review→superfícies (validado 2026-07-18, R29-A)

- O wakeup aceita a issue no corpo: `POST …/wakeups` `{"reason":…,"issueId":…}`. O RUN_STARTED
  só vem **~60s depois** do enqueue (due-time do wakeup) — o tick de 1s do dispatcher não é o
  gargalo; não conclua "travado" antes disso.
- Review na fila nasce APENAS com `issue.risk_level >= orquestrador.review.risk-threshold`
  (default 5): crie a issue com `"riskLevel":5` para o card aparecer em `/reviews`.
- UI viva contra a instância: entrada no launch.json da RAIZ do diretório de trabalho
  (`npm run dev --prefix <worktree>/frontend -- -p 13000`) + `.env.local` no frontend com
  `BACKEND_ORIGIN=http://localhost:18081` e `NEXT_PUBLIC_DEV_BOARD_USER=<uuid do board>`.
