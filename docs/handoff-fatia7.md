# Handoff — retomar na Fatia 7 (skills: anexo por-Issue + KnowAll search)

> Ponto de entrada para continuar o agente-orquestrator numa sessão nova. **NÃO re-derivar** — este doc
> aponta as fontes e dá a especificação da próxima fatia. Data: 2026-07-01. Idioma: PT-BR.

## 1. Estado atual (tudo verde e validado)
- **Backend** Java 21 + Spring Boot 3.5.0 (Maven), Postgres + JPA/Hibernate. Repo:
  `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/` (`backend/` + `frontend/`).
- **Testes: 72/72 green** offline. `mvn -o -f backend/pom.xml test` (H2 em modo PostgreSQL).
- **Fatia 7c (install-on-attach, operador-only) + §7 (recovery park-for-human) DONE (2026-07-01)** — ver
  `docs/handoff-grilling-7c-fechado.md` + ADR-0011. Fold do attach (input = `skillId` global); recovery de Run
  FAILED via `IssueService.settleAfterRun`→`BLOCKED` + `unblock {REOPEN|CANCEL}`. **ZERO migration nova.**
- **Migrations V1→V10** aplicadas e **validadas em Postgres 16.14 real** (Flyway + Hibernate `validate` PASS,
  incl. **V10 incremental V9→V10**) — zero drift. Docker daemon disponível (volume `pgdata` mantido no down).
- **Fase 0 + Fase 1 (slices 1–4)** prontas; **Fase 2**: skills-in-DB 2-camadas (catálogo GLOBAL + biblioteca
  por-company), `activitylog`, **Fatia 6 (materialização)**, **Fatia 7a (anexo por-Issue + união)** e
  **Fatia 7b (KnowAll SkillSearch)** prontas.

> **Fatia 7b DONE (2026-07-01):** seam `SkillSearch` (2 impls por `@Profile`: `PgTrgmSkillSearch` prod /
> `SubstringSkillSearch` H2) + `KnowAllService` (busca catálogo GLOBAL por metadados, anota install por-company)
> + `GET .../knowall/search?q=` (qualquer membro). pg_trgm validado em Postgres real (83 skills; EXPLAIN usa o
> GIN nas 3 colunas). **PRÓXIMA: 7c (§4) — TEM 2 decisões abertas a resolver ANTES de codar.**

> **Fatia 7a DONE (2026-07-01):** `V10__issue_skill_attachment` (por-Issue, imutável, versão pinada) +
> `IssueSkillController` (`POST/GET .../issues/{id}/skills`, BOARD-only; corpo `{companySkillId, agentId}`) +
> `SkillSetService.attachToIssue` (pina a corrente, idempotente; corrida concorrente → 409) +
> `SkillSetService.resolveForRun(company, agent, issue)` (base ∪ anexos, **ANEXO-VENCE**, exclui Issue fechada) +
> `SKILL_ATTACHED`. Seam do `AdapterRunExecutor` já chama `resolveForRun`. **PRÓXIMA: 7b (§4).**

## 2. Onde ler (fontes de verdade — ler antes de codar)
- **Memória (log vivo por-slice, autoritativo):** `agente-orquestrator-stack.md` — tem o estado de CADA slice,
  as decisões e os pontos em aberto. É o primeiro a ler.
- **ADRs:** `docs/adr/0001..0010`. Ênfase p/ Fatia 7: **0003** (SkillSet × KnowAll: base estático + aumento
  por-Issue), **0007** (modelo de dados skills, revisado), **0008** (KnowAll nível-operador: catálogo global
  vs biblioteca por-company), **0009** (activity log), **0010** (regra de ouro de nomes + módulos profundos).
- **Glossário:** `CONTEXT.md`. **Handoffs anteriores:** `handoff-grilling-skills-knowall.md` (fecha o design
  de skills/KnowAll — leia o §C, roadmap ordenado) e `handoff-limpeza-codigo-raso-linecursor.md` (o
  `LineCursor` e a regra de ouro).

## 3. Regra de ouro vigente (ADR-0010 — aplicar em TODO código novo)
(a) **Nomes carregam o fluxo** — zero genéricos (`i`/`x`/`tmp`/`ctx`/`req`, lambdas de 1 letra, abreviações).
(b) **Mecanismo raso vira módulo profundo atrás de um seam pequeno** — extrair, não só renomear; o que varia
entre callers vira parâmetro do seam; testar pela interface. Exemplar: `com.orquestrador.skills.LineCursor`.
**Se for escrever parsing linha-a-linha (ex.: skill multi-arquivo), REUSE o `LineCursor`.**

## 4. Próximo — Fatia 7, quebrada em fatias finas
A Fatia 6 já preparou o seam: `SkillMaterializer.materialize(workDir, Collection<CompanySkillVersion>)`
recebe o CONJUNTO já resolvido; `AdapterRunExecutor` materializa `skillSets.resolveForAgent(...)` ANTES do
checkout (numa `try/catch`). A Fatia 7 SOMA os anexos por-Issue a esse conjunto.

### 7a — anexo por-Issue + união (✅ FEITA em 2026-07-01 — ver nota no §1; 60/60 + Postgres real)
- **Migration `V10__issue_skill_attachment.sql`** + entidade/repo. Tabela `issue_skill_attachment`
  (escopo por-**Issue**, imutável/append-only): `id, company_id, issue_id, agent_id,
  company_skill_version_id` (versão PINADA), `attached_by_actor` (ator), `created_in_run_id` (audit, nullable),
  `created_at`. Índices liderados por `company_id`; unique sugerida `(issue_id, company_skill_version_id)`.
  Declarar uniques/colunas na entidade (H2 força — lição das fatias anteriores).
- **Anexação pelo BOARD via REST** (v1 operador-only): `POST /api/companies/{id}/issues/{issueId}/skills`
  `{companySkillId}` → fixa a versão CORRENTE da skill instalada e grava o anexo. `assertMember` + board-only.
  Anexa só o que JÁ está instalado (install-on-attach é 7c).
- **União com precedência no `SkillSetService`** (o join, ANTES do `materialize`): novo
  `resolveForRun(companyId, agentId, issueId)` = base (`resolveForAgent`) ∪ anexos da Issue, **colapsado por
  skill_key** com **ANEXO-VENCE-A-BASE** (decisão travada: a versão anexada sobrescreve a do set base para os
  Runs daquela Issue). O `AdapterRunExecutor` passa a chamar `resolveForRun(company, agent, issue)` no seam.
  Cobrir com teste "mesma skill em base e anexo → materializa a versão do anexo".
- **Activity log:** evento `SKILL_ATTACHED` (enum já tem) na anexação.
- *Verificar:* anexo materializa a cada Run da Issue; some quando a Issue fecha (efeito efêmero); registro
  persiste (imutável). Cross-tenant e board-only testados via HTTP.

### 7b — SkillSearch (KnowAll persona, read-only) (✅ FEITA em 2026-07-01 — 62/62 + pg_trgm em Postgres real)
- Iface `SkillSearch` (seam): impl **Postgres `pg_trgm`** (ranking por similaridade, top-N; o índice GIN
  `idx_skill_trgm` já existe na V7) + impl **H2 simples** (substring case-insensitive, ordem determinística).
  Busca o **catálogo GLOBAL** (Camada 1) sobre `name`+`description`+`triggers` (metadados, **nunca** o body).
  Resultado **anotado com estado de install** por-company ("instalada" vs "existe, precisa instalar").
- `GET /api/companies/{id}/knowall/search?q=`. Testes asseguram o CONTRATO (contém/não-contém, top-N,
  case-insensitive) no H2; ranking `pg_trgm` validado no Docker.

### 7c — install-on-attach (✅ FEITA em 2026-07-01 — Rota A operador-only; 72/72 + Postgres real)
> Grilling fechou como **Rota A (operador-only)**; agente-como-tool + Approval = fatia futura. Install-on-attach
> via board (input = `skillId` global, fold da 7a). Recovery §7 junto (ADR-0011). Fonte: `handoff-grilling-7c-fechado.md`.
- Anexar skill do catálogo global ainda-NÃO-instalada → **instala** (copia da base global) + anexa
  (1 ação, 2 eventos `SKILL_INSTALLED` + `SKILL_ATTACHED`).
- **Decisão aberta (i):** o v1 inclui o **agente** anexando/instalando (via canal de callback adapter→core,
  autenticado pela chave do run), ou fica **operador-only**? O Q4 inicial escolheu operador-via-REST e adiou
  o agente-como-tool; o grilling depois disse "board **e** agente (tool)". **Conflito a reconciliar.**
- **Decisão aberta (ii):** **primitiva de Approval** não existe (só budget incidents). Só é necessária se o
  agente puder disparar install. Construir um gate genérico mínimo quando (i) for resolvido.

## 5. Pendências conhecidas (não bloqueiam a 7a)
- **Recovery de Issue travada por Run FAILED:** hoje um Run que falha deixa a Issue `IN_PROGRESS` travada e
  **não há mecanismo de destrava** (o reaper só re-enfileira RUNNING com lease expirado). É pré-existente e o
  "travar para recovery" é intencional, mas o unlock não foi construído → candidato a slice própria.
- `SkillFrontmatter.parse` trunca valor em linha em branco no meio de block scalar (raro, equivalente ao
  comportamento antigo — limitação conhecida, não bug).

## 6. Como verificar
- Rápido/offline: `mvn -o -f backend/pom.xml test` → **54/54**.
- Postgres real (ponta a ponta): `docker compose up -d db` → `mvn clean package -DskipTests` (precisa de rede
  p/ plugins de package) → `java -jar backend/target/*.jar` (default aponta p/ `localhost:5432`) → `docker
  compose down`. Boot OK = Flyway V1..Vn + Hibernate `validate` (drift real).
