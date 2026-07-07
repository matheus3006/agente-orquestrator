# ADR-0001: Stack, estrutura de repositório e invariantes

**Status:** Aceito (2026-06-30)

## Contexto
Novo control-plane de orquestração de agentes derivado do Paperclip (TS/Express/Drizzle/React), mas com restrições do dono: backend em Java, frontend no padrão NutriApp (Next.js), execução em Docker, e operação de vários produtos (várias companies).

## Decisão
- **Backend:** Java 21 LTS + **Spring Boot 3.5.x** (patch confirmado no scaffold; Spring Boot 4.0 é caminho futuro). Postgres + JPA/Hibernate, com **jOOQ** nas queries de corrida (checkout, claim de heartbeat).
- **Frontend:** Next.js 16 + React 19 + TS, reusando o padrão NutriApp (tokens, prototype-first).
- **Repo:** `agente-orquestrator/` separado em `backend/` + `frontend/`. Monólito modular (não microserviços).
- **Invariantes não-negociáveis:** single-assignee + checkout atômico (409, nunca repetir); budget hard-stop + auto-pause (ledger = verdade); company-scoping; gates de aprovação; activity log; **deny-by-default**; sem PII/segredos em logs.

## Alternativas consideradas
- Quarkus/Micronaut (cloud-native, menor) — preteridos pela maturidade/ecossistema do Spring.
- Adotar a stack TS do Paperclip — descartada: o dono quer Java + reuso do NutriApp.
- Microserviços — descartado: overkill para single-node/solo no v1.

## Consequências
- Reaproveita o **modelo** do Paperclip, não o código; tradução TS→Java documentada em `docs/ARQUITETURA.md §5`.
- jOOQ adiciona uma dependência, mas é onde a corrida importa.
- deny-by-default exige um wrapper de rota desde a Fase 0 (custo pequeno, evita furo de cross-tenant).
