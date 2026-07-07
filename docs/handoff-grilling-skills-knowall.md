# Handoff — Grilling Skills/KnowAll (pré-Fatia 6) — TODOS OS 6 PONTOS FECHADOS

> Sessão `grill-with-docs` (domain-modeling), 2026-06-30. Fechou as 6 ambiguidades da Fatia 5→6/7.
> Duas pesquisas `deep-research` embasaram os Pontos 4 e 5 (fontes citadas abaixo).
> **Nada de código de feature foi escrito** — só decisões. Este doc é a fonte para a sessão original
> aplicar: emendas de ADR, ADRs novos, mudanças de CONTEXT.md e a lista ordenada de migrations.

---

## A) Decisões fechadas (resumo por ponto)

**Ponto 1 — Escopo de `skill` → KnowAll é NÍVEL-OPERADOR (global).**
KnowAll existe acima das companies (no nível do dono) e acumula conhecimento de skills entre todos os
produtos. Modelo de **2 camadas**:
- **Camada 1 — Catálogo global** (no banco, sem `company_id`): identidade + **savelog** (histórico de
  versões: `content_hash` + `change_description` + origem). É a fonte do original.
- **Camada 2 — Biblioteca da company** (`company_skill`/`company_skill_version`, company-scoped):
  conteúdo instalado/usável; referencia a identidade global; install **copia a base global**
  (customização de uma company não vaza para outra).
- Regras de versão: install default = **base**; KnowAll conhece as mudanças (savelog); promover ajuste a
  base = ação **explícita** ("publicar", fatia futura); descrição da mudança auto nas mudanças automáticas
  (re-import), à mão nas deliberadas.

**Por que não polui contexto (diferencial #1 preservado):** o agente roda em **container Docker isolado**
e só recebe, materializado no seu workspace, o **SkillSet dele**. Funil de estreitamento:
`PC (todas) → banco → biblioteca da company → SkillSet do agente → container/.claude/skills`.
A pasta global do PC é só **fonte de import** — nenhum agente a acessa.

**Ponto 2 — `issue_skill_attachment` (anexo efêmero).**
- **2.0 Install-on-attach:** anexar uma skill do catálogo global ainda não instalada **instala** na
  biblioteca da company (copiando da base global) **e** anexa — uma ação, **dois eventos** no log
  (`SKILL_INSTALLED` + `SKILL_ATTACHED`). Quando o **agente** dispara um install novo, passa por
  **Approval** (invariante #4); o **board** instala direto. Agente anexa livre o que já está instalado.
- **2a Aponta versão PINADA** (`company_skill_version`), não a corrente.
- **2b Registro IMUTÁVEL** (nunca deleta). "Expira com o Run" foi redefinido: **o efeito (materialização
  em disco) expira; o registro persiste** para auditoria.
- **2c Escopo por-ISSUE, não por-Run** (a tarefa = Issue; "por-Run" do ADR-0003 era impreciso). Tabela
  `issue_skill_attachment` (chave `issue_id`). Materializa no início de **cada Run da Issue**; o tool do
  KnowAll devolve o **body inline** pro agente usar na hora (sem injeção no container vivo). "Expira"
  quando a Issue fecha. *(O `workDir` já é por-(company,agent,issue) — a infra já casa.)*
- **2d Anexam:** board **e** agente (tool).

**Ponto 3 — Fonte de import.**
- Raiz global única permanente no v1 (é o catálogo-mestre do operador; **sem** subdir por-company).
- `source` agora tem duas pernas: `skill_version.origin = LOCAL_PATH` (PC → catálogo global) e
  `company_skill.source = CATALOG` (catálogo global → biblioteca da company). **`CATALOG` deixa de ser
  "futuro" e é real no v1.** `GITHUB`/`URL` adiados.

**Ponto 4 — Activity Log (embasado em deep-research, 26 fontes / 25 claims verificados).**
- Criar módulo `activitylog` **agora** (invariante #5; attach/install precisam).
- Schema **híbrido**: colunas tipadas atuais + **`detail jsonb`** (nullable) — `detail` só carrega
  **IDs/enums/números**, nunca body/segredo/PII (mascarar na ingestão).
- Ação delegada = **UM evento** (ator = aprovador; `detail.requested_by_agent`, `detail.approval_id`),
  não dois. (CADF/CloudTrail; hoop.dev: decisão de aprovação gravada contra o registro da ação.)
- Imutabilidade **no banco**: role da app só `INSERT` + trigger `BEFORE UPDATE OR DELETE` que erra
  (guard Postgres-only, H2 não força — mesmo padrão do índice único parcial que vocês já usam).
- **Hash-chaining adiado** (over-engineering p/ solo single-node; reservar p/ requisito de compliance).
- Writer = **service explícito** `ActivityLogService.record(...)` + enum de ações — **não** Hibernate
  Envers (Envers versiona entidade, não eventos semânticos).
- Fontes: [CADF](https://docs.openstack.org/mitaka/config-reference/identity/auditing.html),
  [CloudTrail](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-event-reference-user-identity.html),
  [hoop.dev](https://hoop.dev/blog/audit-logs-with-real-time-approval-workflows-stopping-incidents-before-they-happen),
  [immutability](https://www.designgurus.io/answers/detail/how-do-you-enforce-immutability-and-appendonly-audit-trails),
  [PII/GDPR](https://databunker.org/use-case/gdpr-compliant-logging/). **O FUD anti-JSONB (2000x, TOAST
  tax, no-stats) foi REFUTADO na verificação adversarial** → JSONB híbrido é seguro na nossa escala.

**Ponto 5 — Materialização (Fatia 6) (embasado em deep-research, 22 fontes / 25 claims verificados).**
- **5a Layout** `workDir/.claude/skills/<skill_key>/SKILL.md` = **canônico** (Anthropic/Claude Code docs).
  O materializador engata no seam já comentado em `AdapterRunExecutor` (após `workspaces.resolve`).
- **5b** Materializador recebe **um conjunto de `company_skill_version` (pinadas)**. Fatia 6 alimenta só o
  **set base** (`agent_skill_selection`); Fatia 7 **soma** os `issue_skill_attachment` resolvidos ao mesmo
  conjunto. Assinatura antecipa a união (não reescrever depois).
- **5c Assets adiados → v1 suporta só skill de UM arquivo (SKILL.md).** O padrão do ecossistema é
  materializar o **pacote inteiro** (Claude Code faz `git clone`; OpenAI usa Manifest) porque o
  progressive disclosure lê os arquivos do disco sob demanda; sem eles a skill quebra. A V6 só guarda
  `body`. Multi-arquivo (`scripts/`/`references/`/`assets/`) = fatia futura.
  **⚠ PRÉ-REQUISITO DO PREVIEW (diferencial #5): `criar-prototipo`/`melhorar-prototipo` têm arquivos
  extras — o preview NÃO funciona no v1 até multi-arquivo existir.** Se o preview for priorizado, puxar
  multi-arquivo pra antes dele.
- **5d Segredo:** materializa **só o body redigido**. Regra do projeto: skill **nunca** embute segredo no
  texto — referencia por **nome-de-env** (`$FOO`); valor entra por **env efêmero** do ExecutionTarget
  (hoje `Map.of()`, injeção **não construída** — ok pq skills do v1 são instruções). Nunca logar o env;
  não deixar `.env` no workspace (o agente lê o próprio env). Empírico: **10,9% das skills publicadas têm
  segredo hardcoded** — redação validada. Fontes:
  [Anthropic Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills),
  [Claude Code Skills](https://code.claude.com/docs/en/skills),
  [OWASP LLM07](https://genai.owasp.org/llmrisk/llm072025-system-prompt-leakage/),
  [Snyk/ClawHub](https://snyk.io/blog/toxicskills-malicious-ai-agent-skills-clawhub/).
- **Idempotência:** a cada Run, **reescreve a pasta inteira** (overwrite, não append) → skill desanexada
  some. (Refutado fazer via "limpar cache e reinstalar".)

**Ponto 6 — SkillSearch (Fatia 7).**
- **6a** Busca o **catálogo GLOBAL** (Camada 1); resultado **anotado com estado de install** por-company
  ("já instalada" vs "existe, precisa instalar" → dispara install+Approval).
- **6b** Campos: `name` + `description` + `triggers` (metadados redigidos; **não** o body).
- **6c** Abstração `SkillSearch`: impl **Postgres `pg_trgm`** (ranking por similaridade, tolera typo,
  top-N) + impl **simples H2** (substring case-insensitive, ordem determinística). Os testes asseguram o
  **CONTRATO** (skill que contém o termo aparece; que não contém, não; respeita top-N; case-insensitive),
  **não** o ranking do pg_trgm — este é validado no **ambiente Docker** (padrão Postgres-only de vocês),
  sem Testcontainers.

---

## B) Diffs de doc a aplicar (ready-to-apply)

### ADR-0003 (emendar — seção "Revisão 2026-06-30 (grilling)")
- Escopo da anexação: **por-Issue**, não por-Run (tarefa = Issue; "por-Run" era impreciso). Materializada
  no início de cada Run da Issue; KnowAll tool devolve body inline pra uso imediato.
- Releitura de "expira com o Run": **o efeito (disco) expira; o registro é imutável e persiste** (audit).
- **Install-on-attach + Approval:** anexar skill não-instalada instala (copiando da base global) + anexa,
  1 ação / 2 eventos. Install novo disparado por agente → passa por Approval (invariante #4); board direto.

### ADR-0007 (emendar — seção "Revisão 2026-06-30 (grilling)")
- `skill` = identidade **GLOBAL** (sem `company_id`, `unique(skill_key)`); não mais per-company.
- NOVA `skill_version` (global) = **savelog** (versões da base: `content_hash` + `change_description` +
  `origin`). `company_skill_version` ganha `change_description` + `source_global_version_id`.
- `company_skill.source = CATALOG` (v1); `skill_version.origin = LOCAL_PATH` (v1); GITHUB/URL depois.
- `SkillSearch` busca o **catálogo global**, sobre name+description+triggers (metadados, não body),
  resultado anotado com estado de install.
- NOVA `issue_skill_attachment` (escopo por-Issue, versão pinada, imutável).
- **Assets não materializados no v1 → só skill de 1 arquivo**; multi-arquivo = fatia futura (pré-req do preview).

### ADR-0008 (NOVO) — KnowAll é nível-operador: catálogo global vs biblioteca por-company
> KnowAll opera acima das companies e mantém um **catálogo global** (Camada 1, sem `company_id`) de
> identidades de skill + savelog de versões; as companies instalam cópias na sua **biblioteca** (Camada 2,
> company-scoped). **Descoberta é global; instalação/conteúdo é per-company**; instalar copia a base global,
> nunca o fork de outra company. **Alternativa rejeitada:** KnowAll per-company (o que ADR-0007 implicava) —
> perde o conhecimento acumulado entre produtos, que é a razão de existir do KnowAll. **Isolamento
> (invariante #3) preservado** porque o agente roda em container isolado e só recebe o SkillSet
> materializado (funil PC→banco→company→agent→container). Trade-off: conhecimento global vs isolamento
> estrito, reconciliado pela fronteira descoberta/conteúdo.

### ADR-0009 (NOVO) — Modelo do Activity Log
> `activity_log` é **híbrido**: colunas tipadas (actor/action/entity) + `detail jsonb` (só IDs/enums,
> nunca PII/segredo, mascarar na ingestão). **Ação delegada** (agente pede, board aprova) = **um** evento
> (ator = aprovador; `detail.requested_by_agent`), não dois. Imutabilidade **no banco** (INSERT-only +
> trigger que bloqueia UPDATE/DELETE, guard Postgres-only). Writer = **service explícito**, não Hibernate
> Envers. **Hash-chaining adiado** (over-engineering p/ solo single-node; reservar p/ compliance).
> Baseado em pesquisa (CADF/CloudTrail; hoop.dev; OWASP LLM07; o FUD anti-JSONB foi refutado na verificação).

### CONTEXT.md (glossário — termos a atualizar/adicionar)
- **KnowAll** (redefinir): capability **nível-operador** (acima das companies) que mantém o **catálogo
  global** de skills (identidades + savelog) e responde "temos skill pra X?". Tool (agente) + persona (humano).
- **Catálogo global de Skills** (NOVO, Camada 1): identidades + versões originais (savelog) que o KnowAll
  conhece; vive no banco, sem `company_id`. _Avoid_: "catálogo da company".
- **Savelog de Skill** (NOVO): histórico de versões de uma skill (hash + descrição da mudança).
- **Biblioteca da Company** (Camada 2; refinar CompanySkillLibrary): skills instaladas numa company;
  referencia a identidade global; install copia da base global (install ≠ attach).
- **Skill anexada por-Issue** (renomear de "por-Run"): skill pinada anexada ao trabalho de uma Issue;
  efeito efêmero (materializa a cada Run da Issue, some quando a Issue fecha), registro imutável.
- **SkillSet** (refinar): base pinado por-agente (`agent_skill_selection`) + anexações por-Issue.
- **Skill** (refinar): identidade global (Camada 1) vs instalação por-company (Camada 2) vs versão.

---

## C) Impactos de schema/migration — ORDENADOS

### ANTES da Fatia 6
**Migration `V7__skills_global_catalog.sql`:**
1. `skill`: `drop company_id`; `unique(company_id, skill_key)` → `unique(skill_key)`; ajustar índices;
   add ponteiro p/ versão-base corrente + metadados denormalizados (name/description/triggers) p/ busca;
   **GIN trigram index** em (name, description, triggers) [Postgres-only].
2. NOVA `skill_version` (global/savelog): `id, skill_id→skill, version int, content_hash, name,
   description, triggers, body(redigido), change_description, origin (LOCAL_PATH|GITHUB|URL), source_ref,
   created_at`; `unique(skill_id, version)`, `unique(skill_id, content_hash)`.
3. `company_skill`: `skill_id` → identidade global; `source` = CATALOG no v1.
4. `company_skill_version`: add `change_description`; add `source_global_version_id`.
5. Entidades JPA: **declarar as uniques/colunas novas** (H2 força — lição da Fatia 5).

**Migration `V8__activity_log_detail.sql`:**
6. `activity_log`: add `detail jsonb` (nullable). (Sem GIN por ora.)
7. (Postgres-only, produção) REVOKE UPDATE/DELETE do role da app + trigger `BEFORE UPDATE OR DELETE`.

**Refactor de código (Fatia 5 → antes da 6):**
8. `SkillIndexer` em **2 etapas**: (a) upsert identidade global `skill` + nova `skill_version`;
   (b) se instalando, `company_skill` + `company_skill_version` copiando da versão global.
9. Novo **"catalog sync"** (nível-operador): popula a Camada 1 a partir da raiz do PC **sem** instalar em
   company (KnowAll conhece skills baixadas-mas-não-instaladas).
10. Novo módulo **`activitylog`** (`ActivityLogService.record` + enum de ações).

### Fatia 6 (materialização)
11. NOVA `agent_skill_selection` (SkillSet base; pina `company_skill_version`).
12. `SkillMaterializer` no seam do `AdapterRunExecutor`: recebe **conjunto de `company_skill_version`**,
    escreve `workDir/.claude/skills/<skill_key>/SKILL.md`, **idempotente** (reescreve a pasta inteira).
    Só body redigido; **só skills de 1 arquivo**. Env fica `Map.of()`.

### Fatia 7 (KnowAll search + attach)
13. NOVA `issue_skill_attachment`: `id, company_id, issue_id, agent_id, company_skill_version_id (pinada),
    attached_by_actor, created_in_run_id (audit), created_at`; append-only/imutável.
14. `SkillSearch` (iface + impl pg_trgm + impl H2 simples); busca catálogo global, anota estado de install.
15. Install-on-attach + Approval gate p/ install novo disparado por agente. Materializer soma as
    anexações da Issue ao conjunto base (fecha o seam do item 12).
16. KnowAll REST (tool + persona).

### Fatia futura (fora do v1)
- Skills **multi-arquivo** (armazenar + materializar `scripts/`/`references/`/`assets/`) — **pré-req do
  preview (diferencial #5)**.
- Fluxo **"publicar pro KnowAll"** (promover ajuste de company a nova base global).
- **Injeção de env efêmero** de segredos no container (hoje `Map.of()`).
- Hash-chaining do activity_log; GITHUB/URL como `origin`; pgvector/RAG no KnowAll.

---

## D) Constraints/limitações do v1 a lembrar
- Skills = **1 arquivo (SKILL.md) só**; nada de scripts/assets anexos.
- Skills **nunca** embutem segredo no texto → sempre por nome-de-env.
- Injeção de env de segredo **não construída** (ok: skills do v1 são instruções puras).
- Imutabilidade do activity_log e GIN/pg_trgm são **guards Postgres-only** (não forçados no H2).
- **Preview (diferencial #5) bloqueado** até skills multi-arquivo.
