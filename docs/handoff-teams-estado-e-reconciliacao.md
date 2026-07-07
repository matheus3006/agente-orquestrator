# Handoff de ENTRADA — Teams: estado atual + backlog de reconciliação (2026-07-04)

> **Documento único de orientação** para uma sessão NOVA (substitui a confusão de threads da sessão anterior:
> T.1 impl + protótipo do CEO + auditoria de absorção + deep-research de consistência, tudo misturado).
> Leia ISTO primeiro. Método da casa: TDD backend-first · ADR-0010 (nomes + módulos profundos) · ADR-0015
> (destilar handoff a ~50-60% do contexto) · validação Postgres real ao fim · repo SEM git (não commitar).

---

## 1. ESTADO ATUAL (o que é fato, verificado)

**✅ Verde e verificado:**
- **T.1.a — roles + moldes (backend):** pacote `org.roletemplate` (`TipoFuncao` enum 6 c/ 2 reservados ·
  `RoleTemplate` + `RoleTemplateVersion` savelog · `RoleTemplateService` · `RoleTemplateController` board-only) ·
  `Agent.pinnedTemplateVersionId` · hire-com-role em `AgentHireService` (copia snapshot: motor+skillset+pin) ·
  migration **V14** (motor=TRIO, skillset jsonb, índice parcial global, seed de 4 moldes de sistema).
- **T.1.b — KnowAll conhecimento→skill:** `KnowAllService.matchKnowledge` + `POST /knowall/match` (casa top-1 +
  install-state + gaps). Sem migration.
- **T.1-FE camadas 1–2:** `api.ts` (tipos+funções role-templates + `matchKnowledge`) · `role-templates.ts`
  (helpers puros) · `role-template-select.tsx` + hire dialog com seletor de molde. **Provado VIVO no Preview**
  (hire de `architect` → motor+pin+skillset copiados).
- **Piso:** backend **166/166** (`cd backend && mvn -o test`; +1 = teste 403 da R1) · frontend **139/139**
  vitest + `next build` + tsc · **Postgres V14** (Flyway V13→V14 + `ddl-validate` PASS + guards PG-only vivos).
- **Reconciliação R1–R13 CONCLUÍDA (2026-07-04):** ver banner da SEÇÃO 2.

**✅ Design aprovado, NÃO implementado:**
- **Tela de conversa do CEO** (T.3-FE): protótipo `frontend/prototipos/ceo-conversa-mockup.html` + spec `.md`
  (7 regras, DESIGN APROVADO pelo dono 2026-07-04). É a referência visual canônica de T.3-FE.

**⬜ Aberto / não construído:**
- **T.1-FE resto:** biblioteca de moldes master-detail + org-chart READ-ONLY React Flow (dep nova `reactflow`).
  Entrada: `docs/handoff-t1-fe-canvas.md`.
- **T.2:** entidade `team` + arquiteto obrigatório no dispatch + ligar `reports_to` (é aí que o canvas vira editável).
- **T.3:** CEO conversacional + `conversation`/`company_memory`/`approval`/`decision` (o grande; cruza ADR-0016).
- **T.4:** contenção (`spawn_lineage`/fan-out/ledger).

---

## 2. BACKLOG DE RECONCILIAÇÃO (deep-research interno, 2026-07-04)

> ✅ **RECONCILIADO (2026-07-04, sessão de reconciliação) — R1–R13 TODAS aplicadas.** R1 no código
> (`assertBoardMember` em `AgentController.create` + teste "agente Bearer → hire = 403", TDD RED→GREEN,
> piso **166/166**, jar rebuildado e **provado vivo no Postgres**: board 201 × agente 403). R2–R13 em
> docs: ADR-0017 (citação-fantasma removida, motor=TRIO, seed V14, decisão-produto canvas, cross-refs A.2),
> ADR-0018 (PO para na feature, `model='opus'`, cascata de tetos, executor sem mãos, Definition of Demoable,
> cross-refs), CONTEXT.md (Approval nasce na Teams, Team desambiguado 2×), fechado + TipoFuncao.java
> (espelhos R2/R9), pesquisa memoria-de-agentes reconciliada com segundo-cerebro (SSGM rebaixado; âncora do
> Write Validation Gate → SMSR/TMA-NM), números de piso atualizados nos handoffs T.1/FE-canvas.
> O texto abaixo fica como REGISTRO do que foi achado/corrigido.

> Um workflow de 48 agentes (8 investigadores sob lente human-architect-mindset + refutação adversarial) achou
> **38 inconsistências reais** (dedup → ~14 distintas). Ordenadas por severidade. Cada uma tem a MENOR correção.
> **`reports_to` livre, 6 tipos, motor=trio, moldes-globais — as DECISÕES estão certas; o que está errado é a
> DOCUMENTAÇÃO/racional/gate em alguns pontos.** (Lealdade à arquitetura: corrigir sem "trair" a decisão.)

### 🔴 ALTA

**R1 · Regressão de CÓDIGO — hire-com-role fura o deny-by-default.**
`AgentController.create` (`org/agent/AgentController.java:39`) usa só `guard.assertMember(companyId)`. Com
`roleTemplateVersionId`, `AgentHireService.copySkillSet` roda `installFromCatalog` + `select` — operações
**board-only em todo o resto do sistema** (RoleTemplateController, SkillController usam `assertBoardMember`/
`requireBoard`). Um AGENTE autenticado (membro) poderia contratar-com-molde e disparar install de skill,
burlando o gate. **Eu introduzi isso no refactor do hire.** → **Fix:** trocar `assertMember` por
`assertBoardMember` em `AgentController.create`. **TDD:** teste "agente com Bearer key → hire-com-role = 403".

**R2 · Citação-fantasma de pesquisa (vazou pro código).** ADR-0017:22-23 embasa os 6 tipos em "deep-research
Mintzberg/pirâmide/Mercer·Korn Ferry" e :26 diz "research refutou hierarquia C-suite, voto 0-3". **Nenhuma das 2
pesquisas** (`docs/research/2026-07-04-*.md`, ambas só sobre MEMÓRIA) contém isso; o "voto 0-3" pertence a claims
de memória (SSGM etc.). Já vazou pro `TipoFuncao.java:8`. Fere a rastreabilidade — e é confabulação num ADR, o
que o próprio ADR-0020/D7 combate. → **Fix:** remover a atribuição empírica falsa em ADR-0017:22-23 + :26,
espelhar em `handoff-grilling-teams-fechado.md:56-60`, corrigir `TipoFuncao.java:8` (→ "decisão de design do
grilling A.3, não pesquisa empírica"). A decisão (6 tipos + reports_to livre) se sustenta sozinha.

**R3 · CONTEXT.md se auto-contradiz sobre a Approval.** L20 (Teams): "Approval pré-hoc **NASCE** na frente Teams";
L77/L56 (Governança, legado): "**não existe** no v1, adiada (nasce com o agente-como-tool)". Mesma primitiva,
existência oposta. → **Fix:** reescrever as entradas legadas (L77/L56) para "nasce na frente Teams, ADR-0018".

**R4 · Drift doc↔código do MOTOR do molde.** ADR-0017:31,68,75 dizem que o preset carrega "motor (ref
`engine_catalog`)" / `motor_ref`; o **código (V14 + entidade) construiu o TRIO** `adapter_type/model/effort`
desacoplado (foi decisão deliberada, residual §7 — desacopla do catálogo, coerente com ADR-0014). **O código está
certo; o ADR está velho.** → **Fix (só doc):** ADR-0017:31 → "motor = TRIO desacoplado"; :68 listar
`adapter_type,model,effort,version,change_description` (a lista atual omite campos reais); :75 idem.

**R5 · Colisão PO × arquiteto sobre quem é a FONTE das tasks.** ADR-0017:50-51 + CONTEXT:16: "o arquiteto é a
FONTE das tasks". ADR-0018:42 (fase 2): "o PO vira o dossiê em roadmap→features→**tasks**". A mesma palavra "task"
com dois autores. → **Fix:** ADR-0018:42 → "roadmap→**features**" (o PO para em features; o arquiteto decompõe
feature→Issues-task na fase 3). Reconcilia os dois níveis. (Decisão de granularidade — pode querer grelhar.)

**R6 · As 2 pesquisas se contradizem + o handoff cita o paper refutado.** `research/…memoria-de-agentes:50-54`
confirma o paper SSGM (arXiv:2603.11768) como fonte primária [alta]; `research/…segundo-cerebro:85` o **REFUTA**
("não citar como prova"). O `handoff-grilling-memoria-ceo-segundo-cerebro.md:84` ancora o Write Validation Gate
justamente no paper refutado. → **Fix:** trocar a âncora (:84) pelas fontes que sobreviveram (SMSR arXiv:2606.12703,
TMA-NM arXiv:2606.24322); rebaixar SSGM a "intuição-motivadora, refutada como prova". A substância (D5/D6) fica.

### 🟡 MÉDIA

**R7 · Cross-ref do ADR-0018.** Cabeçalho (L6) promete "A.0–A.2", mas A.2 (cadeia de comando) vive no ADR-0017.
→ **Fix:** L6 → "A.0–A.1, A.5, A.8".

**R8 · Decisão-produto do canvas fora do ADR.** "A tela de roles É um canvas React Flow arrastável + biblioteca
master-detail (diferencial #6)" só vive nos handoffs; nenhum ADR (nem o 0013 de frontend) a registra — só o
INVARIANTE ("canvas espelha"). → **Fix:** nota curta no ADR-0017 (ou 0013) registrando a decisão-produto.

**R9 · Exemplo do motor do CEO desatualizado.** ADR-0018:47 usa `model='opus-4.8'`; os seeds/testes usam
`model='opus'` (o "4.8" vive só no `label`). → **Fix:** ADR-0018:47 + `fechado:153` → `model='opus'`.

**R10 · Números de piso desatualizados.** `handoff-teams-t1.md` (contrato canônico) ainda diz "152/152" e
"migrations até V13" (hoje **165** e **V14**); `handoff-t1-fe-canvas.md` se auto-contradiz no vitest (139 × 133).
→ **Fix:** atualizar os números nos handoffs.

**R11 · Decisões dropadas do registro durável** (estão só no fechado):
- "tetos sobem em cascata / só te alcança o que ninguém abaixo pôde decidir" (A.2) → colar no ADR-0018.
- "executor nasce sem mãos por ser tipo Execução" (A.6#3, invariante derivado do enum) → colar no ADR-0018/0016.
- "Definition of Demoable = roda local com 1 comando" (A.6#2) → colar no ADR-0018.

**R12 · Seed de moldes fora do §Schema do ADR-0017.** V14 semeia 4 moldes de sistema (base do diferencial #6);
o §Schema do ADR-0017 é silencioso sobre seed. → **Fix:** mencionar o seed no ADR-0017.

**R13 · "Team" definido 2× com sentidos diferentes.** CONTEXT:15 (entidade de runtime: domínio+foco) × CONTEXT:80
(pacote portável/seed de Company). → **Fix:** nota cruzada de desambiguação (padrão "Session" do CONTEXT:42).

> Refutadas (NÃO são inconsistência): 2 de 40 verificadas. 24 achados brutos de baixa prioridade não-verificados
> (cap dos ~50 passos) — provavelmente variações dos acima; re-rodar o workflow se quiser exaustividade.

---

## 3. INVARIANTES (a "constituição" — não quebrar)
deny-by-default (assertMember/**assertBoardMember** antes de tocar dado — ver R1) · company-scoping ·
single-assignee + checkout atômico (409, nunca retry) · budget hard-stop · **savelog append-only imutável**
(role_template_version, skill_version) · activity_log imutável · **sem-vetor** (memória = findability JIT).

## 4. PRÓXIMO PASSO (sequência sugerida)
1. ~~Reconciliação (SEÇÃO 2)~~ ✅ **FEITA** (2026-07-04; ver banner da seção 2).
2. **T.1-FE resto** (biblioteca de moldes + org-chart React Flow) — `docs/handoff-t1-fe-canvas.md`, sessão própria.
3. **T.2 → T.3 → T.4** na ordem canônica (ADR-0017 §Schema).

## 5. AMBIENTE / REPRO
- db up (V14, `pgdata`); jar V14 em :8080 (rebuild só se mexer no backend: `mvn -o clean package -DskipTests` →
  `pkill -f orquestrador-0.0.1-SNAPSHOT.jar` → `java -jar target/*.jar`).
- Frontend: `preview_start frontend` (:3000). Protótipo do CEO: `python3 -m http.server` em `frontend/prototipos/`.
- Dev board-user: `b0a7d000-0000-4000-8000-000000000001`; company de teste: `teams-demo`.
- **Repo SEM git — nada commitado.**

## 6. PONTEIROS (ler; NÃO re-derivar)
- **Contrato T.1:** `docs/adr/0017-…` (+ 0018/0019/0020) · `docs/handoff-grilling-teams-fechado.md` (7c).
- **Método memória:** ADR-0019/0020 · `docs/handoff-grilling-memoria-ceo-segundo-cerebro.md` · `docs/research/*`.
- **FE canvas:** `docs/handoff-t1-fe-canvas.md`. **CEO screen:** `frontend/prototipos/ceo-conversa-mockup.{html,md}`.
- **Memória cross-sessão:** `memory/agente-orquestrator-stack.md` (entradas Teams T.1.a/b/FE + auditorias).
