# agente-orquestrator

Control-plane de orquestração de agentes de IA (Java + Next.js), derivado da análise do Paperclip.

- `backend/` — Java 25 + Spring Boot 4.1
- `frontend/` — Next.js 16 + React 19
- `docs/` — arquitetura e ADRs

## Documentação
- [CONTEXT.md](CONTEXT.md) — glossário canônico do domínio
- [docs/ARQUITETURA.md](docs/ARQUITETURA.md) — arquitetura, Constituição, decisões
- [docs/PRD.md](docs/PRD.md) — requisitos do produto
- ADRs:
  - [0001 — Stack, estrutura e invariantes](docs/adr/0001-stack-estrutura-invariantes.md)
  - [0002 — Persistência da Session do adapter](docs/adr/0002-persistencia-session-adapter.md)
  - [0003 — SkillSet × KnowAll](docs/adr/0003-skillset-knowall.md)
  - [0004 — Contrato AgentAdapter.execute()](docs/adr/0004-contrato-agentadapter-execute.md)
  - [0005 — Modelo de heartbeat/execução](docs/adr/0005-modelo-heartbeat-execucao.md)
  - [0006 — Governança em runtime (budget + revisão)](docs/adr/0006-governanca-runtime-budget-revisao.md)
  - [0007 — Modelo de dados de Skills & KnowAll](docs/adr/0007-modelo-dados-skills-knowall.md)
