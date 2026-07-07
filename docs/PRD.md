# PRD — agente-orquestrator

> Product Requirements Document do control-plane de orquestração de agentes de IA.
> Complementa (não duplica) a [arquitetura](ARQUITETURA.md) e a análise do Paperclip em `../../analisys-paperclip/`.
> Status: rascunho v0.1 (pós human-architect-mindset + grilling). Para publicar formal no issue tracker: `/to-prd`.

## 1. Resumo

Um deployment self-hosted que deixa o dono operar **vários produtos**, cada um como uma **empresa de agentes de IA** isolada: um org chart de agentes que acordam por heartbeat, pegam tarefas via checkout atômico, gastam contra budgets e agem sob governança — tudo auditado e observável. Reaproveita o modelo provado do Paperclip e adiciona 6 diferenciais focados na dor real do dono.

## 2. Problema

- O dono tem **muitas skills instaladas** que poluem a janela de contexto de todo agente — desperdício e ruído.
- Roda **múltiplos produtos** e perde rastro de o que cada agente está fazendo e por quê.
- Falta **visibilidade** do planejamento e dos passos de cada "funcionário".
- Em tarefas criativas, falta **acompanhar a criação ao vivo** (preview).
- Conhecimento de skills depende do **PC local** — risco de perda.

## 3. Usuário & persona (v1)

O próprio dono (solo operator / indie), tecnicamente avançado, rodando vários produtos. Local-first, mas **execução em Docker é requisito**. PT-BR. Convenções do NutriApp (protocolo de controle, prototype-first, LGPD).

## 4. Objetivos & não-objetivos

**Objetivos (v1):**
- Rodar agentes reais (Claude Code/Codex) em containers, por heartbeat, contra um backlog, sob budget e aprovação.
- Multi-company com isolamento real desde o início.
- Skill-set por agente + KnowAll (acaba com a poluição de contexto).
- Visibilidade de plano/steps + preview ao vivo.

**Não-objetivos (v1):** marketplace público de skills/teams; multi-nó/cloud HA; pipelines (engine paralela); RAG semântico no KnowAll; app desktop/mobile nativo; multiusuário humano com papéis finos.

## 5. Diferenciais como features (com critérios de aceite)

| # | Feature | Critério de aceite (resumo) |
|---|---|---|
| F1 | **Skill-set por agente** | Cada agente só recebe, no Run, as skills do seu `agent_skill_selection`; o contexto não carrega o resto. |
| F2 | **KnowAll** | Pergunta "temos skill para X?" (via tool ou persona) retorna candidatas + how-to-apply, buscando no catálogo do banco. |
| F3 | **Isolamento Docker** | Todo Run roda em container; estado entre heartbeats persiste em volume de workspace; `local` só em dev. |
| F4 | **Visibilidade plano/steps** | UI mostra, por funcionário, o plano e os passos do Run em tempo real (SSE). |
| F5 | **Preview ao vivo** | Saídas de `criar-protótipo`/`melhorar-protótipo` renderizam num iframe device-frame, validadas sem erro de console (Preview MCP). |
| F6 | **Templates de empresa** | Criar a company de um novo produto a partir de um template (org chart + agentes + skills). |

## 6. Escopo por fase

Ver build order detalhado em [ARQUITETURA.md §12](ARQUITETURA.md). Resumo:
- **Fase 0 — Esqueleto:** repo backend/frontend, `company` + auth + memberships + **deny-by-default**, migrations.
- **Fase 1 — MVP loop:** agents/org chart, issues + checkout atômico, heartbeat (Docker) + 1 adapter, budget hard-stop, aprovação, activity log, board UI fino.
- **Fase 2 — Diferenciais:** skills-knowledge (import→DB→materialização) + KnowAll, visibilidade plano/steps, preview ao vivo.
- **Fase 3 — Ecossistema:** templates de empresa, routines, recovery distribuído, RAG no KnowAll.

## 7. Modelo de dados inicial

Partir do conjunto-núcleo descrito em `../../analisys-paperclip/03-data-model.md` e `15-REBUILD-BLUEPRINT.md` (tabelas `company`, `agent`, `issue`, `goal`, `project`, `heartbeat_run`, `cost_event`, `budget_policy`, `approval`, `activity_log`) + as do diferencial: `skill`, `skill_version`, `company_skill`, `agent_skill_selection`, `plan`, `step_trace`, `preview_artifact`. Ver [ARQUITETURA.md §3/§7](ARQUITETURA.md).

## 8. Invariantes (não-negociáveis)

Single-assignee + checkout atômico (409, nunca repetir) · budget hard-stop + auto-pause (ledger = verdade) · company-scoping · gates de aprovação · activity log · **deny-by-default** · sem PII/segredos em logs. Detalhe em [ARQUITETURA.md §2.3](ARQUITETURA.md).

## 9. Métricas de sucesso (v1)

- Tempo do `npx`/onboard até a primeira company rodando um agente: < 15 min.
- Redução de tokens por Run ao usar skill-set por agente vs. carregar tudo (medir baseline).
- Zero double-work (checkout atômico) e zero estouro de budget sem auto-pause em teste de carga.
- KnowAll responde "temos skill para X?" com a skill certa no top-3 em consultas conhecidas.

## 10. Riscos & mitigações

| Risco | Mitigação |
|---|---|
| Persistência de contexto do agente em Docker | volume de workspace por issue/agente (grilling) |
| Corrida de budget | enforcement de 2 fases (preflight + reativo no ledger) |
| Furo de isolamento multi-company | deny-by-default + testes de cross-tenant desde o v1 |
| Complexidade do heartbeat (god-file no Paperclip) | módulo `execution` com fronteiras claras; lease+reaper desde já |
| Skills com segredos/binários | redação no import; blobs em object-storage |

## 11. Dependências externas

CLIs de agente (Claude Code, Codex) · APIs de LLM · Preview MCP · Docker · Postgres · filesystem de skills (import). Isoladas pelo módulo `adapters`.

## 12. Próximos passos

1. Aprovar este PRD (ou rodar `/to-prd` para publicar formal).
2. **Fase 0 — scaffold**: `start.spring.io` (confirmar patch Spring Boot 3.5.x/Java 21) em `backend/`, Next.js 16 em `frontend/`, primeira migration (`company` + auth) e o wrapper deny-by-default.
3. Definir o ADR-0001 (stack & estrutura) em `docs/adr/`.
