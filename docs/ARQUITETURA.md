# Arquitetura — agente-orquestrator

> Control-plane de orquestração de agentes de IA, derivado da análise do Paperclip
> (`/Users/matheussouza/Pessoal/orquestrador-agentes/analisys-paperclip/`).
> Documento vivo. Status: **rascunho pré-grilling** (a ser endurecido via `grill-with-docs` → `to-prd`).

## 1. Visão & framing

Um deployment self-hosted que roda uma ou mais "empresas" de agentes de IA. Cada empresa é um **org chart** de **agentes** que acordam por **heartbeat**, pegam **issues** via **checkout atômico**, rastreiam trabalho até **goals**, gastam contra **budgets** e agem sob **governança/aprovação** — tudo auditado. É **control plane, não execution plane**: não roda o modelo, orquestra runtimes externos.

**Usuário v1:** o próprio dono (solo). **Escopo:** paridade multi-company — e **requisito real**, não só insurance: o dono opera **vários produtos distintos**, cada um como uma *company* isolada. Isso reforça o isolamento desde o v1 e dá peso ao catálogo de templates (criar a company de um novo produto a partir de um template).

**Os 6 diferenciais vs. Paperclip** (o que justifica reconstruir):
1. **Skills por-funcionário** — cada agente carrega só seu set; resolve poluição de contexto.
2. **KnowAll** — agente/serviço que conhece todas as skills e responde "temos skill para X?".
3. **Isolamento de agente mais forte** — execução Docker-first.
4. **Visibilidade de planejamento e steps** — plano e passos de cada funcionário observáveis.
5. **Preview ao vivo** — emulação de tela (estilo preview do Claude) para tarefas criativas.
6. **Nicho + templates prontos** — teams catalog.

## 2. Constituição (regras imutáveis — SDD)

### 2.1 Stack pinada
| Camada | Escolha | Observação |
|---|---|---|
| Linguagem backend | **Java 21 LTS** | (alt: Java 25 LTS) |
| Framework | **Spring Boot 3.5.x** | confirmar patch exato em start.spring.io no scaffold; 4.0 é caminho futuro |
| Persistência | **PostgreSQL 15+** + JPA/Hibernate (jOOQ p/ queries críticas de concorrência) | `SKIP LOCKED`, `pg_trgm`; `pgvector` plugável p/ KnowAll RAG |
| Auth | Spring Security (sessão board + API keys de agente + run-JWT por-empresa) | **deny-by-default** |
| Realtime | **SSE** (streaming de plano/steps) | WebSocket só se bidirecional (ex: chat c/ agente) |
| ORM | **JPA/Hibernate** (CRUD) + **jOOQ** (queries de corrida) | checkout atômico e claim de heartbeat em SQL preciso |
| Fila/worker | **Postgres-as-queue** (`SELECT … FOR UPDATE SKIP LOCKED`) + worker agendado | sem broker externo no v1; lease+reaper cobre recovery |
| Imagens runtime | base comum + **camadas por-adapter** | reuso + isolamento por runtime |
| Execução agente | **Docker-first** (`ExecutionTarget` plugável; driver `local` p/ dev) | imprescindível rodar em Docker |
| Frontend | **Next.js 16 + React 19 + TS** | padrão NutriApp: `@almanutri/tokens`, prototype-first |
| Build | backend: Maven/Gradle · frontend: pnpm | repo separado em `backend/` + `frontend/` |

### 2.2 Estrutura de repositório
```
agente-orquestrator/
├── backend/    # Spring Boot (módulos por bounded context)
├── frontend/   # Next.js (app do painel)
└── docs/       # arquitetura + ADRs
```

### 2.3 Invariantes (nenhuma task pode violar)
1. **Single-assignee + checkout atômico** — `UPDATE` condicional; 0 linhas → `409`; nunca repetir 409.
2. **Budget hard-stop com auto-pause** — ledger de custo = verdade; estourou → pausa escopo + cancela runs em voo + fila.
3. **Company-scoping** — toda entidade carrega `company_id`; isolamento no edge + serviço + dado.
4. **Gates de aprovação** — ações governadas exigem aprovação server-side; mudanças de config são revisionadas/rollback.
5. **Activity log** — toda ação mutante registra ator + evento.
6. **Deny-by-default** — wrapper de rota que falha fechado por padrão (melhoria sobre o Paperclip).
7. **Sem PII/segredos em logs** (LGPD); redação no import de skills e nos transcripts.

## 3. Modelo de domínio

```
Company (tenant)
  └─ Agent (reports_to tree)
       ├─ SkillSet (AgentSkillSelection)         ← diferencial 1
       ├─ Issue → Goal (ancestry "porquê")
       ├─ Plan + StepTrace                         ← diferencial 4
       └─ Run (heartbeat) → CostEvent · ActivityLog
KnowAll (busca no SkillCatalog)                    ← diferencial 2
PreviewArtifact (render ao vivo)                   ← diferencial 5
Budget · Approval · Board · Routine · Team(template)
```

## 4. Módulos / bounded contexts (monólito modular)

| # | Módulo | Responsabilidade |
|---|---|---|
| 1 | `identity-access` | Auth, company-scope, API keys, run-JWT, deny-by-default |
| 2 | `org-agents` | Agentes, papéis, árvore reports_to |
| 3 | `work` | Issues, checkout atômico, goals, projects, blockers |
| 4 | `execution` | Fila heartbeat, Run lifecycle, recovery, `ExecutionTarget` (docker/local) |
| 5 | `governance` | Approvals, Budgets, Costs, ActivityLog |
| 6 | `skills-knowledge` ⭐ | Catálogo (DB), biblioteca por empresa, seleção por-agente, KnowAll search, indexador filesystem→DB |
| 7 | `preview-artifacts` ⭐ | Render ao vivo (criar/melhorar-protótipo + Preview MCP), PreviewArtifact |
| 8 | `adapters` | Integração com runtimes (Claude Code, Codex, …) — interface `AgentAdapter.execute()` |
| 9 | `realtime` | WS/SSE para plano + steps |

Monólito modular (não microserviços) — adequado a solo/single-node; fronteiras de módulo preparam extração futura.

## 5. Mapa Paperclip (TS) → agente-orquestrator (Java)

| Paperclip | Aqui |
|---|---|
| Express + rotas | Spring MVC controllers + `@RestControllerAdvice` (erros 400/401/403/404/409/422/500) |
| Drizzle + Postgres | JPA/Hibernate; jOOQ nas queries de checkout/heartbeat |
| Checkout atômico (UPDATE guardado) | `UPDATE … WHERE status IN(...) AND assignee…` via jOOQ; rowsAffected==0 → `409` |
| Heartbeat = DB-as-queue + FOR UPDATE | `SELECT … FOR UPDATE SKIP LOCKED` + worker pool (`@Scheduled`/Quartz) |
| Budget hard-stop (recompute ledger) | serviço transacional recomputa `SUM(cost_cents)`; pausa + cancela |
| Adapter `execute()` (1 call site) | `interface AgentAdapter { Result execute(RunContext ctx); }` |
| `desiredSkills` + materialização | `AgentSkillSelection` + `SkillMaterializer` escreve set em disco no Run |
| better-auth | Spring Security + tabelas equivalentes |
| run JWT por-empresa (HMAC por companyId) | `JwtService` com chave derivada por `companyId` |

## 6. Modelo de execução (Docker-first)

`ExecutionTarget` plugável:
- **`docker` (canônico):** cada Run roda em container isolado; cwd/worktree montado; segredos injetados via env efêmero; logs/custos capturados via stdout/stream. Imagens por-adapter (runtime do Claude Code/Codex).
- **`local` (dev):** processo local spawnado pelo backend; mesma interface, sem isolamento — só p/ acelerar testes iniciais.
- Recovery: Run carrega `lease_owner` + `lease_expires_at` (melhoria sobre Paperclip, que é process-local) → reaper distribuído-ready.

## 7. Skills & KnowAll (banco = fonte de verdade)

Fluxo:
1. **Import/indexação:** `SkillIndexer` lê `.claude/skills/*/SKILL.md` (frontmatter: name, description, triggers), calcula `content-hash`, redige segredos, e grava em `skill` + `skill_version` (conteúdo + assets como blob/object-storage).
2. **Biblioteca por empresa:** `company_skill` (instalada/importada/forkada) — install ≠ attach.
3. **Seleção por-agente:** `agent_skill_selection` define o set de cada funcionário.
4. **Materialização runtime:** no Run, só as skills selecionadas são escritas no disco do container.
5. **KnowAll:** serviço `SkillSearch` (keyword/`pg_trgm` no v1; `pgvector`/embeddings plugável) exposto como **tool** que qualquer agente — e a persona "KnowAll" — chama: "temos skill para X?" → retorna candidatas + how-to-apply.

Benefício: conhecimento durável e portável (não depende do PC local); backup/versionamento nativos.

## 8. Preview ao vivo (diferencial 5)

- Integra os skills **`criar-prototipo`** (motor cadillac: React18 UMD + Babel, device-frame, showcase, servido em `prototipos_html/`) e **`melhorar-prototipo`**.
- `preview-artifacts` serve o artefato e o frontend embute num `<iframe>` com device-frame (emulação de tela), espelhando o **Preview MCP** (`preview_console_logs`, screenshot) para validar sem erro de console.
- Casos: tarefas criativas/imagens onde o dono acompanha a criação ao vivo.

## 9. Visibilidade de plano & steps (diferencial 4)

- `Plan` (documento revisável por issue) + `StepTrace` (passos do Run: tool calls, decisões) transmitidos por `realtime` (WS/SSE).
- UI: timeline por funcionário ("o que está planejando / em que passo está"), além do board estilo Linear.
- **v1 (ADR-0013):** a "timeline por funcionário" materializa como **Mission Control** — grid de cards ao
  vivo, 1 por agente (status derivado + mini-terminal do StepTrace + feed). Timeline horizontal estilo
  Gantt = evolução. Realtime da UI: **polling** (2s agregado/transcript incremental); WS adiado —
  invalidation-first, entra sem tocar as telas.

## 10. Systems thinking — deps externas & falhas

Deps voláteis isoladas por `adapters`: CLIs (Claude Code/Codex), APIs de LLM, Preview MCP, Postgres, filesystem de skills. Modos de falha tratados: runs órfãos (lease + reaper), corrida de budget (enforcement de 2 fases), bloat de contexto (skill-set por-agente é a mitigação central).

## 11. Decisões resolvidas (grilling 2026-06-30)

| Tema | Decisão | Razão |
|---|---|---|
| **Estado do agente entre heartbeats** | **Volume nomeado por workspace** (git worktree montado no container a cada Run) | espelha o contrato de cwd único do Paperclip; estado durável sem peso de snapshot |
| **Multi-company no v1** | **Isolamento real desde já** | o dono roda vários produtos distintos = várias companies reais |
| **KnowAll** | **Tool de busca (`pg_trgm`) + persona "KnowAll"**, keyword no v1 | cobre uso por agente (tool) e por humano (persona); `pgvector`/RAG plugável depois |
| **ORM** | JPA/Hibernate + jOOQ nas queries de corrida | produtividade no CRUD, precisão onde a corrida importa |
| **Fila/worker** | Postgres-as-queue (`SKIP LOCKED`) + worker agendado | menos infra; lease+reaper cobre recovery |
| **Realtime** | SSE (plano/steps); WS só bidirecional | streaming uma-via é mais simples e reconecta fácil |
| **Imagens runtime** | base comum + camadas por-adapter | reuso + isolamento por runtime |
| **Session do adapter** | híbrido: handle no banco (`agent_session`) + estado no volume | ADR-0002 |
| **Fonte do StepTrace** | parser de stdout (universal) + canal estruturado opcional | preserva "bring your own agent"; ADR-0004 |
| **Materialização de skills** | banco → pasta adapter-aware no container, só o SkillSet do Agent, por Run | isolamento de contexto |
| **SkillSet × KnowAll** | base estático por Agent + aumento dinâmico por-tarefa (escopo do Run) | contexto enxuto por padrão; ADR-0003 |
| **Contrato `execute()`** | handle in → handle out; workspace montado; `eventSink` p/ StepTrace | ADR-0004 |

### Em aberto (não-bloqueantes, decidir no caminho)
- Quando introduzir broker externo (se a fila Postgres saturar com muitos agentes).
- Estratégia de embeddings do KnowAll quando ligar RAG (modelo local vs. API).
- Como o "aumento dinâmico" do SkillSet é disparado na UI (botão do operador vs. o Agent pedindo ao KnowAll).

## 12. Build order (rascunho)

- **Fase 0 — Esqueleto:** repo, Spring Boot + Next, `company` + auth + memberships + deny-by-default wrapper, migrations.
- **Fase 1 — MVP loop:** agents + org chart, issues + checkout atômico, heartbeat (docker target) + 1 adapter, budget hard-stop, aprovação, activity log, board UI fino.
- **Fase 2 — Diferenciais:** skills-knowledge (import→DB→materialização) + KnowAll, visibilidade plano/steps (realtime), preview ao vivo.
- **Fase 3 — Ecossistema:** teams catalog/templates, routines, recovery distribuído, RAG no KnowAll.
