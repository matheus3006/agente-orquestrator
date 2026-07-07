# Handoff de ENTRADA — Teams T.1 (roles + moldes) → implementar backend-first TDD

> Documento de ENTRADA da fatia **T.1**. **Contrato canônico:** `docs/adr/0017-teams-roles-presets-composicao.md`
> + `docs/handoff-grilling-teams-fechado.md` (seção **C-T.1** + A.3). NÃO re-derivar o design — está fechado.
> Método: backend-first TDD (teste antes, RED confirmado), ADR-0010 (nomes + módulos profundos), validação
> Postgres real ao fim, ADR-0015 (destilar handoff a ~50-60%). **T.1 NÃO toca o frontier do agente-como-tool**
> (CEO/memória/Approval = T.3) — é construível já.

## 0. Estado aterrado (não re-derivar)
- **Piso backend 166/166** offline (`cd backend && mvn -o test`), **frontend 139/139** + build. **Migrations até
  V14** (aplicada; próxima = **V15**). PG 16 disponível; jar atual em :8080; db up (`pgdata`, V14).
- **Os 4 ADRs (0017-0020) + emendas (0002/0004/0009/0012/0016) + CONTEXT.md JÁ estão escritos** (sessão anterior).
  ADR-0017 é o contrato de T.1.
- **Modelo atual relevante:** `Agent` (`org.agent`) tem `role` **String livre**, `reportsToId UUID` (nullable,
  **sem uso** — T.2 liga), `adapterType/model/effort` (motor F.7), `pinnedTemplateVersionId` **a criar**. O padrão
  a CLONAR: `engine_catalog` (V13, global, `GET /api/companies/{id}/engines` board-only, `EngineCatalog`/repo/
  controller em `org.engine`) e o versionamento de `company_skill_version` (savelog imutável + hash). `AgentController`
  faz o hire (`CreateAgentRequest{name, role, model, effort, adapterType, executionTarget}`).

## 1. O que T.1 É / NÃO é
- **É:** roles como **tipo-de-função (enum FIXO 6) × role-específica (catálogo ABERTO)** + **moldes globais
  versionados com pin-por-agente** + **hire-com-role (copia snapshot do preset)** + o **canvas de times** (frontend).
- **NÃO é:** times/arquiteto-obrigatório (=T.2), CEO/memória/Approval (=T.3, cruza ADR-0016), spawn_lineage (=T.4).

## 2. Schema — migration V14 (`V14__role_template.sql`)
Espelha o savelog de skills (`company_skill_version`) + o catálogo global (`engine_catalog`):
- **`role_template`** (id uuid pk, `tipo_funcao` text NOT NULL [enum app-level, ver §3], `role_especifica` text
  NOT NULL, `company_id` uuid **NULL = global** [convenção do catálogo global], `current_version_id` uuid,
  `created_at`). Índice por `company_id`. Unique sugerida: `(company_id, role_especifica)` — confirmar na impl.
- **`role_template_version`** (id uuid pk, `role_template_id` fk, `version` int, **`motor_ref`** [ref ao
  `engine_catalog`: guardar o trio `adapter_type/model/effort` OU um fk — decidir na impl, rec.: o trio, desacopla],
  **`skillset`** jsonb [lista de refs de skill do catálogo GLOBAL — `skill_key`s], **`reports_to_default`** text
  nullable [tipo/role sugerido, NÃO um agent id], `change_type` text, `change_description` text, `proposed_by`/
  `approved_by` [ator], `status` text [DRAFT/APPROVED — governança de molde reusa a Review], `created_at`).
  Append-only (imutável); a entidade DECLARA os uniques (lição das fatias: H2 força). Unique `(role_template_id,
  version)`.
- **`agent`** += **`pinned_template_version_id`** uuid nullable (V14 `alter table agent add column`).
- **Enum `tipo_funcao`:** guardar como **text** + `TipoFuncao` Java enum `@Enumerated(STRING)` (padrão da casa —
  `IssueStatus`/`RunStatus` são text; H2 não faz pg-enum). Valores: `LIDERANCA, GESTAO_PO, PLANEJAMENTO_ARQUITETO,
  EXECUCAO, REVISAO_QA, SUPORTE`. **REVISAO_QA e SUPORTE = reservados** (aceitos no enum, mas sem agente próprio v1).
- **Seed (opcional):** moldes de sistema (architect, executor, po, líder) via `gen_random_uuid()` no V14 (como o
  seed do `engine_catalog` V13; NÃO roda no H2 → testes semeiam via repo). Decidir se v1 já traz seed ou fica vazio.

## 3. Backend (TDD — teste ANTES, RED confirmado)
Novo pacote `org.roletemplate` (ou dentro de `org.agent` — seguir a convenção flat da casa; ver `org.engine`).
- **`TipoFuncao`** enum (6 valores; marcar os 2 reservados no javadoc).
- **`RoleTemplate`** entidade + **`RoleTemplateVersion`** (declara uniques) + repos **company-led** (deny-by-default:
  queries lideram por `company_id`, com o ramo `company_id IS NULL` pro global — cuidado com o `IS NULL` no JPQL).
- **`RoleTemplateService`** (módulo profundo, ADR-0010):
  - `createTemplate(tipoFuncao, roleEspecifica, presetV1)` → cria template GLOBAL (`company_id=null`) + versão 1;
    **gate por alcance** (catálogo global = board-only; ADR-0017/0018).
  - `newVersion(templateId, preset, changeDescription)` → **append** nova versão (savelog; `max(version)+1`, olhar
    o padrão anti-corrida de `company_skill_version`), atualiza `current_version_id`. Editar ≠ mexer em quem pinou.
  - `pinForAgent(agentId, versionId)` → seta `agent.pinned_template_version_id` (re-pin explícito, reversível).
  - `resolvePreset(versionId)` → devolve o preset (motor + skillset + reports_to_default) — o snapshot que o hire copia.
- **Hire-com-role** (`AgentController`/`AgentService`): `CreateAgentRequest` ganha `roleTemplateVersionId` opcional;
  no hire, se presente, **copia o snapshot** (aplica `configureEngine` com o motor do preset + auto-seleciona o
  SkillSet via `SkillSetService.select` + seta `reports_to` default) e pina a versão. **Override do operador** (campos
  passados à parte) **vencem** e NÃO propagam ao molde. Sem preset → comportamento atual (process default).
- **KnowAll conhecimento→skill** (o "casa" do A.3): quando o CEO/dono cria molde, `SkillSearch`/`KnowAllService`
  casa cada conhecimento requerido → `skill_key` do catálogo global; skill que falta → sinaliza (mão soberana de
  importar fecha o loop). **Pode ser um sub-passo T.1.b** se T.1.a (schema+service+hire) já ficar densa.
- **Endpoints** (board-only, `assertBoardMember`, clonam `EngineCatalogController`): `GET/POST /api/companies/{id}/
  role-templates` (catálogo global; POST cria/versiona) + `POST .../role-templates/{id}/versions` + o pin no
  `PATCH /agents/{agentId}` (já existe o PATCH de motor — estender ou irmão).

## 4. Testes TDD (RED→GREEN; espelham `EngineCatalogHttpTest`/`SkillIndexerTest`/`AgentEngineHttpTest`)
- `RoleTemplateServiceTest`: cria molde global+v1 · edita→v2 savelog (v1 intacta, `current`=v2) · pin+resolvePreset
  devolve o snapshot certo · `max(version)+1` (não count) · reservados aceitos no enum.
- `HireWithRoleTest`: hire com `roleTemplateVersionId` → motor+skillset+reports_to copiados (snapshot) · override do
  operador vence e não propaga · sem preset → default atual.
- `RoleTemplateHttpTest`: board 200 no catálogo global · agente/cross-tenant **403** · POST cria/versiona board-only
  (nonce por-método, H2 sem o seed do V14 — padrão do `EngineCatalogHttpTest`).

## 5. Validação Postgres real (ao fim, como toda fatia)
Boot da jar nova contra o db (`pgdata` em V13) → **Flyway V13→V14 incremental success** + `ddl-auto=validate` PASS
(colunas/uniques batem as entidades) + (se houver seed) presença do seed + `GET /role-templates` real board→200 /
cross-tenant→403. `docker compose down` mantendo o volume.

## 6. Frontend T.1-FE — o CANVAS (sub-fatia própria; precisa dos endpoints do §3 primeiro)
- **Canvas de times** = org-chart **arrastável (React Flow — dep nova)**; já traz o **"time pronto de prateleira"
  (diferencial #6)** como 1ª classe. **Biblioteca de moldes master-detail GLOBAL** (fora do company-switcher, clona
  a UI do `engine_catalog`). **Arquiteto obrigatório por drop-zone TIPADA** (nasce válido; invariante validado no
  **backend**, canvas só espelha). **Governança de molde reusa a fila de Review** (propose-then-commit, diff,
  drift-badge). Reconvocar as **4 skills de design** (P.1 é silenciosa sobre isto; divergência justificada, como o
  dropdown Motor). Esboço de referência: Artifact `ceo-conversa-mockup` (da sessão de brainstorm do #1).
- **É fatia grande o suficiente pra sessão própria** (React Flow + 4 skills). Fazer DEPOIS do backend T.1 verde.

## 7. Residuais de T.1 (decidir na impl)
- **Armazenar `motor_ref`** como trio (`adapter/model/effort`) [rec., desacopla do id do catálogo] vs fk `engine_catalog`.
- **`role_template` per-company** existe no v1 ou é **só global** (`company_id` sempre NULL no v1)? O fechado diz
  `company_id NULL=global`; confirmar se há override por-company já.
- **Unique** de `role_template` (`(company_id, role_especifica)`?) e o comportamento de `IS NULL` no company-led.
- **Seed** de moldes de sistema no V14: sim/não.
- `reports_to` livre × `spawn_lineage` (T.4): a árvore proíbe só ciclo/profundidade, nunca QUEM reporta (residual 5
  do fechado) — não é problema de T.1, mas o `reports_to_default` do preset deve respeitar isso.

## 8. Ordem + o que NÃO tocar
1. V14 + entidades + `RoleTemplateService` + hire-com-role + testes (T.1.a) → piso + Postgres.
2. KnowAll conhecimento→skill (T.1.b, se separou).
3. Canvas (T.1-FE, sessão própria, 4 skills de design).
**NÃO tocar** o agente-como-tool / CEO / `company_memory` / `approval` (=T.3, cruza ADR-0016). Depois de T.1 vem
**T.2** (`team` entidade + arquiteto obrigatório no dispatch + ligar `reports_to`).

## Ambiente / repro
db up (V14, `pgdata`); jar `backend/target/orquestrador-0.0.1-SNAPSHOT.jar` em :8080; rebuild só se mexer no
backend (`mvn -q clean package -DskipTests` [precisa de rede] → `pkill -f orquestrador-0.0.1-SNAPSHOT.jar` →
`java -jar ...`). Frontend: `preview_start frontend` (:3000). **Repo sem git — nada commitado.**
