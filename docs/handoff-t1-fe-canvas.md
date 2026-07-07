# Handoff de ENTRADA — Teams T.1-FE (canvas de times + biblioteca de moldes) → implementar FE

> Documento de ENTRADA da sub-fatia **T.1-FE** (o frontend de T.1). **Contrato canônico:**
> `docs/adr/0017-teams-roles-presets-composicao.md` (seções "Time = entidade + arquiteto obrigatório",
> "Cadeia de comando" e **"Decisão-produto (UI): canvas + biblioteca de moldes"**) +
> `docs/handoff-teams-t1.md` **§6**. NÃO re-derivar o design — está fechado.
> Método: FE prototype-first (modelo cadillac NutriApp), **reconvocar as 4 skills de design**, verificar
> VIVO no Preview MCP (`preview_start frontend`), ADR-0015 (destilar handoff a ~50-60%). **Backend T.1
> (a+b) JÁ ESTÁ VERDE** — esta fatia só consome os endpoints e espelha invariantes; não toca backend.

## PROGRESSO desta sub-fatia — ✅ T.1-FE COMPLETA (2026-07-04, verificada viva)
- ✅ **Camada 1 (encanamento):** `src/lib/api.ts` estendido (tipos+funções `listRoleTemplates`/
  `createRoleTemplate`/`addRoleTemplateVersion`/`matchKnowledge`; `HireAgentInput.roleTemplateVersionId?`;
  `AgentSummary.pinnedTemplateVersionId`); `src/lib/role-templates.ts` (helpers puros: `TIPOS_FUNCAO`,
  `tipoFuncaoLabel`, `isTipoReservado`, `versionMotorLabel`) + `role-templates.test.ts`; `query-keys.ts`
  += `roleTemplates`.
- ✅ **Camada 2 (hire-a-partir-de-molde):** `role-template-select.tsx` + `hire-agent-dialog.tsx` com seletor
  de molde (Motor vira override). Verificado vivo: hire de `architect` copia motor+skillset+pin.
- ✅ **Camada 3 — BIBLIOTECA DE MOLDES** (`src/app/moldes/page.tsx`, rota GLOBAL fora do company-switcher,
  residual 2 → rota própria): master-detail com grupos por `tipoFuncao`, **selo "sistema"** nos 4 semeados
  (diferencial #6 em 1ª classe), seleção **deep-linked** (`?molde=`), painel do preset corrente (motor via
  `/engines` + chips do skillset + `reports_to_default`) e **savelog de versões**. Dialogs `molde-novo-dialog`
  / `molde-versao-dialog` (este pré-preenche do preset corrente) sobre `molde-preset-form` compartilhado;
  **compositor KnowAll** (`knowall-compositor.tsx`): conhecimento-por-linha → `POST /knowall/match` → chips
  com install-state (INSTALLED fósforo "instalada" / AVAILABLE ice "importável") + **gaps** (âmbar tracejado)
  + skill key manual; `motor-select.tsx` ganhou clear opcional ("sem motor"); `tipo-funcao-select.tsx` novo
  (reservados marcados). Sidebar ganhou grupo "Catálogo → Moldes (global)" (Link cru = 4ª exceção marcada).
  **Verificado vivo:** criar `auditor-de-seguranca` (tipo Execução, Sonnet 5 high, tdd casado "instalada" +
  gaps visíveis + codebase-design manual) → toast, auto-seleção, preset+savelog v1; anexar v2 (Opus xhigh,
  reporta a Arquiteto) → **v2 CORRENTE sobre v1** no savelog; molde de fora da sessão → nota honesta.
- ✅ **Camada 3 — ORG-CHART read-only:** `src/lib/team-canvas.ts` (layout tidy-tree PURO e determinístico:
  irmãos/raízes por nome, pai centrado, órfão vira raiz, ciclo não derruba; +test) + `src/components/
  team-canvas.tsx` como **visão "organograma" da página Agents** (toggle filter-tabs roster/organograma —
  sem 5ª entrada de nav). Nós com identidade do board (AgentDot, nome Chakra, role mono, LED do agregado
  de 2s, motor, badge "molde" quando pinado); arestas smoothstep por `reportsToId`; painel "nenhum
  reports_to — a cadeia liga em T.2" quando plano; caption read-only. **Verificado vivo** na `teams-demo`
  (cadeia Lyra ← Echo/Nix criada via hire API + 2 raízes soltas): posições batem com o layout puro, console
  **zero warnings**.
- **Piso final: vitest 157/157 · tsc limpo · next build ✓** (rota `/moldes` na tabela). Skills de design
  reconvocadas (ui-ux-pro-max guidelines + emil + bencium-impact; ui-typography dispensada — tipografia
  congelada nos tokens = divergência justificada).

### Decisões/divergências da implementação (T.1-FE)
1. **DEP: `@xyflow/react` v12 (12.11.x), NÃO `reactflow` v11** — v11 é deprecado e spama o warning 002 a
   cada render sob React 19 (dupla-invocação de useMemo no StrictMode); v12 é o sucessor mantido, mesmos
   classnames CSS, e é a fundação certa pro canvas editável de T.2. Overrides do tema do RF vivem FORA de
   `@layer` no `globals.css` (CSS sem layer do RF vence layer — aprendido vivo).
2. **Preset/savelog legíveis — RESIDUAL #1 FECHADO (2026-07-04):** o backend ganhou
   `GET .../role-templates/{id}/versions` (board-only, desc por version, `skillKeys` desserializado do
   jsonb + `reportsToDefault`; o POST /versions também devolve o preset inteiro). O espelho `SEED_V14` e o
   savelog client-side de sessão SAÍRAM (`role-templates.ts` guarda só a identidade dos 4 moldes de sistema
   pro selo); `queryKeys.roleTemplateSavelog(companyId, templateId)` virou query real SOB o prefixo do
   catálogo — a invalidação em bloco dos dialogs (`queryKey[2] === "role-templates"`) refaz catálogo e
   savelogs juntos. Molde versionado fora da sessão agora é legível por inteiro (verificado vivo no
   `auditor-de-seguranca` v1/v2). Backend 186 testes ✓ · vitest 151 ✓ · tsc ✓ · build ✓.
3. **"Lente" da biblioteca:** a rota é global mas os endpoints são company-scoped (gate); a página usa a
   última company visitada (`orq.lastCompanySlug`, fallback primeira) e MOSTRA a lente no cabeçalho — o
   install-state do KnowAll é relativo a ela.
4. **`reports_to_default` v1 = dropdown do enum** (residual 4): texto livre exibe (rótulo cru) mas não edita.
5. **Cache do Turbopack:** rodar `next build` antes de `next dev` envenenou o CSS servido (bloco novo
   ausente) — `rm -rf .next` antes do dev resolve; sintoma = classes novas sem efeito com build verde.
6. Dados de demo criados vivos (ficam): molde `auditor-de-seguranca` v1/v2 no catálogo global; agentes
   `Nix`/`Echo` reportando à `Lyra` na teams-demo (a cadeia que o canvas mostra).

## 0. Estado aterrado (não re-derivar)
- **Reconciliação R1–R13 APLICADA (2026-07-04):** base de docs limpa (`docs/handoff-teams-estado-e-reconciliacao.md`
  §2 ✅). Relevante pra cá: `POST /agents` (hire) virou **BOARD-only** (R1, segurança — a UI já manda
  `X-Board-User`, sem impacto) e o jar no :8080 já contém o fix.
- **Backend T.1.a + T.1.b DONE & verified (2026-07-04):** piso **166/166** (`cd backend && mvn -o test`),
  migrations até **V14** (`role_template` + `role_template_version` savelog + `agent.pinned_template_version_id`;
  seed de 4 moldes de sistema), **Flyway V13→V14 + `ddl-validate` PASS** no Postgres real, jar V14 no :8080.
  Frontend **139/139** (vitest) + build verdes.
- **Stack FE (aterrado):** Next.js 16 + React 19 + TS; **React Query** (`@tanstack/react-query`), **Radix**
  (`react-dialog`/`react-dropdown-menu`), **Tailwind v4** (tokens em `globals.css`), `lucide-react`, CVA;
  vitest. **NÃO há lib de grafo — React Flow é a DEP NOVA desta fatia** (`npm i reactflow`, precisa de rede).
- **O fetcher ÚNICO:** `src/lib/api.ts` (`apiFetch`, auth `X-Board-User` só aqui; tipos de domínio +
  funções-cliente vivem aqui). Query keys em `src/lib/query-keys.ts` (`queryKeys.engines`, `companyScope`).

## 1. O que T.1-FE É / NÃO é (RECORTE CRÍTICO — não estourar pra T.2)
- **É** (tudo coberto pelo backend T.1 já verde):
  1. **Biblioteca de MOLDES master-detail GLOBAL** (fora do company-switcher — o catálogo é global, não
     por-company): listar/criar/versionar moldes; ver o preset (motor + skillset + reports_to_default);
     o **"time pronto de prateleira" (diferencial #6)** = os 4 moldes de sistema semeados, 1ª classe.
  2. **Hire-a-partir-de-molde:** o dialog de contratar (`hire-agent-dialog.tsx`) ganha um **seletor de
     molde**; escolher pina a versão + copia o snapshot (o backend já faz — a UI só manda `roleTemplateVersionId`).
  3. **Composição do skillset por conhecimento (KnowAll):** ao montar/editar um molde, casar conhecimentos
     → skills via `POST /knowall/match` (mostra match + install-state + **gaps** pra importar).
  4. **Org-chart READ-ONLY** (React Flow) do hierárquico ATUAL dos agentes (arestas por `reportsToId`, que
     já existe em `AgentSummary`) — a **fundação** do canvas.
- **NÃO é** (depende de **T.2** — entidade `team` + ligar `reports_to`; NÃO construir aqui):
  entidade `team`/drop-zone TIPADA por tipo-de-função · **arquiteto obrigatório** (trava no DISPATCH) ·
  **arrastar-pra-reparentar** (precisa de `PATCH reports_to` que NÃO existe — hoje o PATCH de `/agents/{id}`
  só promove MOTOR) · agrupar agentes por time. O `reports_to` como cadeia de comando é T.2 por decisão
  (ADR-0017 schema: "(T.2) team ... ; ligar o reports_to existente"). **Canvas v1 = visualiza, não edita a
  árvore.** (Se o dono quiser drag-to-reparent já, é um mini-add de backend — ver §6, residual 1.)

## 2. Endpoints backend PRONTOS que a canvas consome (shapes reais)
- `GET /api/companies/{id}/role-templates` → `[{id, tipoFuncao, roleEspecifica, currentVersionId, createdAt}]`
  **(board-only)**. Catálogo GLOBAL (a rota é company-scoped só p/ gatear). `tipoFuncao` ∈ enum 6
  (`LIDERANCA, GESTAO_PO, PLANEJAMENTO_ARQUITETO, EXECUCAO, REVISAO_QA*, SUPORTE*`; *reservados).
- `POST /api/companies/{id}/role-templates` **(board-only, 201)** body `{tipoFuncao, roleEspecifica,
  adapterType, model, effort, skillKeys:[], reportsToDefault, changeDescription}` → cria molde global + v1.
- `POST /api/companies/{id}/role-templates/{templateId}/versions` **(board-only, 201)** body `{adapterType,
  model, effort, skillKeys:[], reportsToDefault, changeType, changeDescription}` → `{id, roleTemplateId,
  version, adapterType, model, effort, status, createdAt}` (savelog append; v1 fica intacta).
- `POST /api/companies/{id}/knowall/match` **(member)** body `{knowledge:["...","..."]}` → `{matches:[{
  knowledge, matched, skillId, skillKey, name, installState}], gaps:["..."], matchedSkillKeys:["..."]}`.
  Casa top-1 (**sugestão que o autor revisa**); `matchedSkillKeys` = o skillset pronto; `gaps` = importar.
- `POST /api/companies/{id}/agents` (hire, **BOARD-only desde a R1**) — body **ganha `roleTemplateVersionId?`** (opcional); com ele o
  backend copia o snapshot (motor + skillset + pin). Resposta `AgentResponse` agora tem **`pinnedTemplateVersionId`**.
  **Override do operador vence:** se a UI também mandar `model`/`effort`/`adapterType`, eles vencem o preset.
- `GET /api/companies/{id}/engines` → `Engine[]` (já existe; o `label` legível do motor p/ mostrar no molde).
- `GET /api/companies/{id}/agents` → `AgentSummary[]` (tem `reportsToId` — as arestas do org-chart).
- **Falta expor no `api.ts`:** os tipos `RoleTemplate`/`RoleTemplateVersion`/`KnowledgeMatch*` + as funções
  `listRoleTemplates`/`createRoleTemplate`/`addRoleTemplateVersion`/`matchKnowledge`; e `HireAgentInput`
  ganha `roleTemplateVersionId?`, `AgentSummary` ganha `pinnedTemplateVersionId`.

## 3. Peças de UI a construir + os arquivos-PADRÃO a clonar
- **Cliente:** estender `src/lib/api.ts` (novos tipos + funções, seção "role-templates (T.1)"); novo
  `src/lib/role-templates.ts` p/ helpers puros (rótulo do tipo-de-função, resolver motor do preset —
  espelha `src/lib/engines.ts`); `src/lib/query-keys.ts` += `roleTemplates(companyId)`.
- **Seletor de molde no hire:** `src/components/hire-agent-dialog.tsx` — adicionar um `<RoleTemplateSelect>`
  (clona `src/components/motor-select.tsx`, o dropdown Radix do catálogo) ACIMA do Motor; ao escolher molde,
  o Motor pré-preenche do preset e vira override opcional. Passar `roleTemplateVersionId` no `hireAgent`.
- **Biblioteca de moldes (master-detail):** nova rota fora do company-switcher (ex.: `src/app/moldes/…` ou
  um painel global no shell) — clona a estética do catálogo/`engine_catalog`; lista à esquerda (por
  `tipoFuncao`), detalhe à direita (preset + histórico de versões); botões criar/nova-versão (dialogs Radix,
  clonam `new-issue-dialog.tsx`/`hire-agent-dialog.tsx`). O compositor de skillset chama `matchKnowledge` e
  mostra chips **INSTALLED/AVAILABLE** + **gaps** (o mesmo vocabulário do KnowAll já usado no board).
- **Org-chart React Flow (read-only):** novo `src/components/team-canvas.tsx` — nós = agentes (clona o
  visual de `agent-card.tsx`/`agent-dot.tsx`), arestas = `reportsToId`; layout hierárquico. Sem drag-edit v1.

## 4. React Flow (dep nova) — cuidados
- `npm i reactflow` no `frontend/` (precisa de rede). Import do CSS base (`reactflow/dist/style.css`) —
  reconciliar com os tokens do `globals.css` (o canvas herda a paleta do board, não o tema default do RF).
- É **app real** (não Artifact) — sem restrição de self-contained/CSP. Verificar VIVO no Preview MCP.

## 5. As 4 skills de design + referência
- **Reconvocar as 4 skills de design** (P.1 é silenciosa sobre QUAIS — confirmar as mesmas das fatias de
  board FE; fortes candidatas: `ui-ux-pro-max` + o trio `bencium-*` [impact/controlled/innovative] e/ou
  `emil-design-eng`/`ui-typography`). **Divergência justificada** é permitida (como foi o dropdown Motor).
- **Esboço de referência:** `frontend/prototipos/ceo-conversa-mockup.html` + `.md` (spec da tela de CONVERSA do
  CEO = T.3-FE, materializada no repo em 2026-07-04; serve de referência de estética/tokens). O canvas é o
  **diferencial #6** ("time pronto de prateleira") como 1ª classe — o org-chart arrastável é a visão-alvo,
  mas v1 entrega a fundação read-only + a biblioteca de moldes.

## 6. Residuais/decisões da fatia (decidir na impl)
1. **Drag-to-reparent já no v1?** Precisa de um `PATCH /agents/{id}` que aceite `reportsToId` (hoje só
   promove motor). É um mini-add de backend que ANTECIPA T.2 ("ligar reports_to"). **Rec.: NÃO** — manter
   canvas read-only e deixar a edição da árvore pra T.2 (evita meia-implementação da cadeia de comando).
2. **Onde a biblioteca de moldes mora na navegação** (é GLOBAL, fora do company-switcher): rota própria
   `/moldes` no shell raiz vs painel/gaveta global. Rec.: rota própria (master-detail respira melhor).
3. **Governança de molde reusa a fila de Review** (propose-then-commit, diff, drift-badge — ADR-0017): o
   backend v1 cria molde **direto board-only** (status nasce `APPROVED`); o fluxo propose-then-commit +
   drift-badge é **FE/serviço futuro**. Rec.: **fora do escopo T.1-FE** — a biblioteca cria/versiona direto;
   marcar o drift-badge como TODO da governança.
4. **Editar Motor no molde** reusa o `MotorSelect` (o preset carrega o trio); o `reports_to_default` é um
   **campo texto** (tipo/role sugerido — dropdown do enum `TipoFuncao` OU texto livre; NÃO é agent id).

## 7. Verificação + o que NÃO tocar
- **Verificar VIVO:** `preview_start frontend` (:3000, precisa do jar :8080 no ar) → `preview_snapshot`/
  `preview_screenshot` provam biblioteca + hire-from-mold + canvas; `preview_console_logs` limpo. Piso:
  `npm test` (vitest) verde + `next build` verde. Testar os helpers puros novos (espelha `engines.test.ts`).
- **NÃO tocar:** o backend (T.1 fechado) · o frontier agente-como-tool/CEO/memória/Approval (=T.3) · a
  entidade `team`/dispatch/arquiteto-obrigatório/reports_to-wiring (=T.2). Canvas ESPELHA invariantes, não
  os cria (ADR-0017: invariantes vivem no backend).

## 8. Ambiente / repro
- Backend: db up (V14, `pgdata`); jar V14 em :8080 (rebuild só se mexer no backend — NÃO deve, nesta fatia).
- Frontend: `cd frontend && npm run dev` OU `preview_start frontend` (:3000). Auth v1 = `NEXT_PUBLIC_DEV_BOARD_USER`
  (o `X-Board-User` do `api.ts`); board precisa de membership na company (o create-company já dá).
- **Repo sem git — nada commitado.** Após a fatia: piso vitest + build verdes + validação viva no Preview.

## 9. Depois de T.1-FE
**T.2** (backend): entidade `team` (domínio/foco/`arquiteto_agent_id`) + `agent.team_id` + arquiteto
obrigatório no DISPATCH + **ligar `reports_to`** (é aí que o canvas vira editável/arrastável). Depois **T.3**
(CEO + `conversation`/`company_memory`/`approval`/`decision`, cruza ADR-0016) e **T.4** (contenção:
`spawn_lineage`/fan-out/ledger). Ordem canônica: ADR-0017 §"Schema" + `handoff-teams-t1.md` §8.
