# ADR-0001: Stack, estrutura de repositório e invariantes

**Status:** Aceito (2026-06-30)

> **Revisão 2026-07-16:** a escolha de baseline do backend (Java 21 + Spring Boot 3.5) foi **substituída pela
> ADR-0043**: Java 25 + Spring Boot 4.1 + Jackson 3 + Spring AI 2.0. Frontend, estrutura e invariantes desta ADR
> permanecem aceitos.

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

## Nota de reconciliação (2026-07-10 — divergência MÉDIA da auditoria doc×código de 2026-07-07)

**jOOQ nunca foi adotado.** O `pom.xml` não tem a dependência e não existe `import org.jooq` no
backend. As queries de corrida que este ADR reservava ao jOOQ foram construídas em **Spring Data
`@Query`**, e o invariante de concorrência **é honrado** por esse outro mecanismo:

- **Checkout atômico** = SQL nativo `IssueRepository.tryCheckout` (UPDATE guardado por
  `status IN ('OPEN','IN_PROGRESS') AND assignee…`; rows==0 → 409, nunca retry).
- **Claim de heartbeat** = JPQL CAS `HeartbeatRunRepository.claim`
  (`UPDATE … WHERE status=:queued`; rows==1 → claimed).

A precisão que motivava o jOOQ ("SQL preciso onde a corrida importa") existe — só que via
`@Modifying @Query`, sem a dependência extra. Direção decidida no levantamento de paralelismo:
**docs passam a refletir o código** (não adotar jOOQ agora). O texto original acima fica como
registro do desenho da época; as 5 menções a jOOQ em `docs/ARQUITETURA.md` foram corrigidas
nesta mesma reconciliação.
