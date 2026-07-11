# Roadmap interno — agente-orquestrator (INVENTÁRIO de controle)

> **Fonte única de CONTROLE** — "o que fizemos, o que falta, o que aguarda decisão".
> **NÃO duplica conteúdo:** cada linha **aponta** pro handoff/ADR/memória onde o detalhe vive.
> O roadmap é o **mapa**; o território são os `docs/handoff-*.md` + `docs/adr/*` + memórias.
>
> **Protocolo de atualização (após cada grill OU execução):**
> 1. mova a linha pro novo **estágio**; 2. atualize o **ponteiro** (handoff/ADR); 3. atualize a
> **memória** (`~/.claude/.../memory`); 4. se sobraram decisões pro dono, some-as em **§ Vetos pendentes**.
>
> **Reescrito como INVENTÁRIO verificado em 2026-07-07** — auditoria de implementação dos **29 ADRs
> (0001–0029) + 40 handoffs** contra o código (backend Java + migrations + frontend), com **evidência
> `file:line`**. Método: 18 auditores em paralelo + **refutação adversarial** de cada divergência
> (48 candidatas → 25 confirmadas, 23 refutadas). Cobertura: 100% dos ADRs e handoffs. Ver
> **§ Divergências** (o descompasso doc×código) e **§ Histórico**.

## Estágios (o ciclo de uma fatia)

`IDEIA` → `GRILLANDO` → `PLANEJADO` (handoff fechado) → `EM EXECUÇÃO` (TDD) → `VENDO/FECHADO` → `ADR registrado`

**Dono da próxima ação:** **H** = decisão do Matheus · **A** = execução do agente.

**Verdict de auditoria** (eixo ortogonal ao estágio): `IMPLEMENTADA` (capacidade no código, com testes) ·
`PARCIAL` (parte no código / residual aberto / sem testes) · `AUSENTE` (não há código). Uma fatia
`PLANEJADO` é `AUSENTE`-no-código **de propósito** — não é lacuna.

## Panorama (auditado 2026-07-07)

A fundação é **muito maior** que os ~13 itens do rascunho anterior. Dos 29 ADRs: **28 = IMPLEMENTADA /
VENDO-FECHADO** (0001–0014, 0016–0029, incluindo o eixo motor **duplo** claude+codex e os **3 reapers**);
**1 = PARCIAL** — a ADR-0015 (regra de ouro do handoff) é **princípio praticado**, com só a *medição de
tokens* no código; o loop automático é a Fatia 1. A camada de **orquestração v1 do CEO** são 2 fatias
`PLANEJADO` (design fechado, código **intencionalmente ausente**). O **método de memória** (2º cérebro) é
`PARCIAL`. As 25 divergências que sobreviveram à refutação são **1 alta · 5 média · 19 baixa** — a maioria
é envelhecimento de handoff já consumido; o sinal real está na **alta** e nas **médias** (ver § Divergências).

---

## Frente A — Fundação implementada (VENDO/FECHADO)

> Onde vivem os **28 ADRs implementados**. Cada linha = uma capacidade, com **1 evidência-âncora**
> `file:line` (o resto da prova mora no ADR/handoff apontado). Todas com espelho de teste.

### A.1 — Stack, estrutura, invariantes & regra de ouro dos nomes

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Stack + estrutura + invariantes (multi-tenant, checkout atômico, deny-by-default) | ✅ IMPLEMENTADA · VENDO | `backend/pom.xml:9-10,21` (Spring Boot 3.5.0 + Java 21) · `frontend/package.json:22-24` (Next 16.2.9/React 19.2.4) · checkout `work/issue/IssueRepository.java:48-64` · escopo `common/security/CompanyAccessGuard.java:25-41` | ADR-0001 ⚠ *(3 divergências: jOOQ, SKIP LOCKED, wrapper — § Div)* |
| Nomes que carregam o fluxo + módulos profundos (regra de ouro) | ✅ IMPLEMENTADA · VENDO | `skills/LineCursor.java:17-48` (3 métodos, seam package-private) | ADR-0010 · `handoff-limpeza-codigo-raso-linecursor` |

### A.2 — Skills & KnowAll

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| SkillSet estático × aumento dinâmico (base pinada + anexo por-Issue) | ✅ IMPLEMENTADA · VENDO | `skills/AgentSkillSelection.java:34` + `V9__agent_skill_selection.sql:11` (pin base) | ADR-0003 |
| Modelo de dados de Skills (catálogo global + versão + savelog) | ✅ IMPLEMENTADA · VENDO | `V7__skills_global_catalog.sql:10-37` (skill global, `skill_version` savelog) | ADR-0007 |
| KnowAll nível-operador (Camada 1 global × Camada 2 por-company) | ✅ IMPLEMENTADA · VENDO | `V7__skills_global_catalog.sql:10-11,22-37` · `skills/CompanyLibraryInstaller.java:30-38` (install copia base) · `skills/SkillMaterializer.java:34,59` (skills-in-DB) | ADR-0008 |

### A.3 — Motores, adapters & session (o eixo motor-agnóstico)

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Persistência híbrida da Session do adapter | ✅ IMPLEMENTADA · VENDO | `V16__conversation.sql:42-51` (`agent_session` por agent+conversation, `external_session_id`) | ADR-0002 |
| Contrato `AgentAdapter.execute()` (o seam do motor) | ✅ IMPLEMENTADA · VENDO | `execution/adapter/AgentAdapter.java:19,47-50` (`execute(Context, StepSink)`) | ADR-0004 ⚠ *(§ Div: `producedArtifacts`)* |
| Motor por agente (adapter + model + effort) | ✅ IMPLEMENTADA · VENDO | `V12__agent_engine.sql:4-5` + `org/agent/Agent.java:41-45,159-165` (`configureEngine`) | ADR-0014 |
| **Segundo** motor vivo (codex) — prova do eixo | ✅ IMPLEMENTADA · VENDO | `execution/adapter/CodexAdapter.java:14,28` (auto-registra no `AdapterRegistry:16-17`) | ADR-0024 |
| Sandbox por **postura** do ExecutionTarget | ✅ IMPLEMENTADA · VENDO | `execution/runtime/ExecutionTarget.java:22-24,141-143` (`posture()` HOST default + `IsolationPosture`) | ADR-0025 |

### A.4 — Execução: heartbeat, isolamento & os 3 reapers

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Modelo de heartbeat / execução (state machine + wakeups) | ✅ IMPLEMENTADA · VENDO | `V3__heartbeat.sql:26` (`uq_run_active_agent`, 1-Run-ativo-por-Agent) + `heartbeat_run_event` append-only + `LeaseRenewer` (pulso 30s→lease cheio) | ADR-0005 ✅ (Div ALTA do lease FECHADA 2026-07-10 — renovação implementada) |
| Isolamento de plano por container (Docker) | ✅ IMPLEMENTADA · VENDO | `execution/adapter/EngineContainer.java:27-46` (imagem pinada + só o volume do plano) | ADR-0016 · `handoff-f8-isolamento` |
| **1º→2º reaper** — WorkspaceReaper (GC de workspace em disco) | ✅ IMPLEMENTADA · VENDO | `execution/runtime/WorkspaceReaper.java:74-171` (sweep + `WORKSPACE_COLLECTED` SYSTEM) | ADR-0026 |
| **3º reaper** — cap+graduação de rows (PURE-DB, F.14) | ✅ IMPLEMENTADA · VENDO | `RowRetentionReaper.java:53-107` + `RowRetentionPolicy.java:78-103` (vigente/StepTrace/fixada-por-review sobrevivem) | ADR-0028 |
| Retenção do rastro de conversa (2ª passada, F.15) | ✅ IMPLEMENTADA · VENDO | `HeartbeatRunRepository.java:41-48` (`findConversationTurnRunIdsExpired`) + `RowRetentionReaper.java:169-175,224-226` | ADR-0029 |

### A.5 — Governança: budget, review, recovery & approval

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Enforcement de governança em runtime (budget + revisão) | ✅ IMPLEMENTADA · VENDO | preflight real `execution/HeartbeatDispatcher.java:53` (`budgets.invocationBlocked` cancela QUEUED) | ADR-0006 ⚠ *(§ Div: corpo vs T.4)* |
| Recovery de Issue travada por Run FAILED (park-for-human) | ✅ IMPLEMENTADA · VENDO | `work/issue/IssueService.java:108` (`tryBlock` guardado por runId + `ISSUE_BLOCKED`) | ADR-0011 |
| Review gate pós-hoc por risco (runtime-enforced) | ✅ IMPLEMENTADA · VENDO | `V11__review.sql:4` (`risk_level` + tabela `review` + `uq_review_pending`) | ADR-0012 · `handoff-grilling-approval(-fechado)` |

### A.6 — Teams & orquestração do CEO

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Teams — roles, presets/moldes, composição, cadeia de comando, contenção | ✅ IMPLEMENTADA · VENDO | enum fixo `org/roletemplate/TipoFuncao.java:16-25` · `org/team/TeamService.java:53-109` · `execution/lineage/SpawnLineageGuard.java:24,47-73` (MAX_DEPTH=5) | ADR-0017 · 9 handoffs `teams-*` |
| Orquestração — CEO mediado por proposta | ✅ IMPLEMENTADA · VENDO | `governance/approval/ProposalScanner.java:28-66` (detector ` ```proposta ` fail-soft, deny-by-default) | ADR-0018 |

### A.7 — Memória do CEO (átomo de memória + racional)

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Decision-log — a decisão como átomo | ✅ IMPLEMENTADA · VENDO | `V18__company_memory_decision.sql:19-41` (tabela `decision`, racional NOT NULL, `supersedes_id`) | ADR-0019 ⚠ *(§ Div: review→Decision; supersedes sem writer)* |
| Racional arrancado por grill no gate | ✅ IMPLEMENTADA · VENDO | `governance/approval/RacionalPolicy.java:48-70` (`grillProbe`/`maieuticProbe`/`deriveTipo`; lógico bloqueia, intuição commita) | ADR-0020 |

### A.8 — Activity Log

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Modelo do Activity Log (schema híbrido: colunas tipadas + detail JSON) | ✅ IMPLEMENTADA · VENDO | `V1__init.sql:54-63` (`actor_type`/`action`/`entity_*`) + `activitylog/ActivityLogServiceTest.java:35-63` | ADR-0009 |

### A.9 — Preview real (F.9 · F.9e · F.10 · F.13)

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Preview real — captura no settle, render sandboxed | ✅ IMPLEMENTADA · VENDO | `V20__preview_artifact.sql:8-22` (append-only, `unique(issue_id,version)`) | ADR-0021 |
| Escritor de workspace — árvore materializada pelo mesmo seam | ✅ IMPLEMENTADA · VENDO | `execution/runtime/ExecutionTarget.java:37-48` (`writeWorkspaceFiles`, replace por subtree, host-vence) | ADR-0022 |
| Preview processo/porta — servidor vivo sob demanda | ✅ IMPLEMENTADA · VENDO | `V21__preview_server_manifest.sql:8-19` + `preview/server/PreviewServerSupervisor.java` | ADR-0023 |
| Thumbnail do servidor vivo (v2) | ✅ IMPLEMENTADA · VENDO | `V22__preview_server_thumbnail.sql:12-24` (bytea por-run) + `preview/server/PreviewThumbnailCapturer.java:50-60` (alpine+chromium) | ADR-0027 |

### A.10 — Frontend board + fase de UI/UX

| Capacidade | Verdict · Estágio | Evidência-âncora | Ponteiro |
|------------|-------------------|------------------|----------|
| Arquitetura do frontend (board do operador) | ✅ IMPLEMENTADA · VENDO | `frontend/src/lib/api.ts:45,35-40` (fetcher único `X-Board-User`) + 20 suites / 204 casos vitest | ADR-0013 ⚠ *(§ Div: "5 superfícies")* |
| Fase de UI/UX P0–P2C (#12 ocioso · #11a tooltips · #11b glossário) | ✅ IMPLEMENTADA · VENDO | `frontend/src/lib/mission-control.ts:136-157` (`estadoOcioso`) · `frontend/src/components/glossario-dialog.tsx` · guard E1 `board-shell.tsx:44` | `levantamento-ui-ux.md` · `plano-ui-ux-p2c.md` **(sem ADR — § Div)** |

---

## Frente B — v1 do CEO (a camada de ORQUESTRAÇÃO) — a construir

Sequência: **Fatia 1 (fundação do transporte)** e **Fatia 2** são **PARALELAS** (o grill fechado da Fatia 2
tornou o bilhete conversation-scoped — ver § Div). **Fatia 1 IMPLEMENTADA (ADR-0030, 2026-07-07);** Fatia 2
segue `PLANEJADO` (código intencionalmente ausente, NÃO é lacuna — auditoria confirmou a ausência limpa).
**Priorização CONFIRMADA pelo dono (grill 2026-07-09):** fatia AGENTS.md (G3) **antes** da Fatia 2 → G5
(ID sequencial) → caso B — as skills das personas não chegam ao codex sem G3. **G5 ANTECIPADA em
paralelo ao fecho da G3** (decisão do dono, 2026-07-10 — colisão zero arquivo a arquivo, ledger
`controle-paralelismo.md`); grill do G5 FECHADO em 2026-07-10 → PLANEJADO. **Grill do CASO B
FECHADO em 2026-07-10 (faixa R3-C)** → **EXECUTADA + VENDO PASS (2026-07-10, branch
`feat/caso-b-adr0034`)**: `handoff-grilling-caso-b.md` (D1–D8/M1–M7 + §RESULTADO;
bloco ```` ```correcao ```` do Questionador → corrente #N→time→arquiteto determinística → corretiva
`corrige_issue_id` (V26) com verbatim do dono copiado do banco → 1ª Decision origem-AGENTE;
**ADR-0034 + V26 = reservas consumidas**; divergência assumida da letra do B2 registrada e
declarada na ADR; novo piso proposto: backend **489** · front **248**).

| Item | Verdict · Estágio | Evidência de ausência-limpa / fundação | Ponteiro · Próxima ação (dono) |
|------|-------------------|----------------------------------------|--------------------------------|
| **Fatia 1** — loop de handoff de contexto (50-60%) | ✅ IMPLEMENTADA · VENDO (integração) | M1–M10 verdes (423 testes): `handoff/HandoffThresholdDetector.java:45-64` (% pós-run + fail-open + dial 55) · `handoff/ContextHandoffLoop.java:44-77` (detectar→garantir bilhete→continuar + rede resume-cirúrgico) · `handoff/ContinuationTurnPoster.java:52-73` (sentinela + trava N=5 + idempotência) · `handoff/DistillationSkill.java:60-96` (skill sintética = template) · `conversation/ConversationTurnCoordinator.java:98-112` (M5) · `execution/AgentSessionStore.java:37-45` (1º leitor `--resume` cirúrgico) · `application.yml:30-34` (dial). Prova ponta-a-ponta: `HandoffLoopTest` (cruza+bilhete→1 continuação · sem bilhete→rede→continuação · abaixo→nada). **/verify live teams-demo = confirmação final** | **ADR-0030** · `handoff-grilling-loop-handoff-contexto-fechado` |
| **Fatia AGENTS.md (G3)** — o adapter declara a convenção de workspace (codex recebe carta + skills) | ✅ VENDO (provada live 2026-07-10) | M1–M8 verdes (suíte **443** = piso 429 + 14): `execution/adapter/WorkspaceConvention.java` (record `claude()`/`codex()` + `KNOWN`) · `AgentAdapter.java` (default `claude()`; só `CodexAdapter` sobrescreve) · materializers paramétricos no caminho (layout claude **bit-idêntico** — asserções intocadas) · `AdapterRunExecutor` (fail-fast pré-checkout D4 + faxina `KNOWN−ativa` D6 + união no escritor P2) · prova: `CodexWorkspaceConventionOnRunTest` (6 casos, inclui o simétrico codex→claude) | **ADR-0032** · **PR #4 MERGED** · **§VENDO live PASS: 5/5 provas** (carta md5-idêntica + `.claude/` ausente + skill-sonda ponta a ponta + ruído no baseline + volume Docker) — §RESULTADO no `handoff-grilling-agents-md-codex` |
| **Fatia 2** — CEO em 3 personas (Q/P/E) | ✅ IMPLEMENTADA · **VENDO (live PASS 2026-07-10)** | M1–M9 + adendo A1–A5 verdes (suíte **469** = piso 448 + 21; front **246** = 242 + 4): `V24__agent_persona.sql` + `V25__ceo_persona_moldes.sql` (3 moldes `ceo-*` + backfill: canônico→questionador, P/E copiam o motor) · `org/agent/CeoPersona.java` + `CeoPersonaSeeder.java` (auto-seed mold-driven fail-soft, D9) · resolvedor por-persona (`ConversationService` + fallback legado, D2) · `composePrompt` por-persona (M7; G1/G2 preservados) · workspace de conversa compartilhado (`WorkspaceResolver.resolveConversation` + peek pré-claim, M4/D4) · `conversation/persona/` (scanner col-0 irmão do ProposalScanner + roteador + poster com M9 prossiga→CORTE na reincidência) · gate: motor do alvo + `EngineOverride` do dono no decide (A4) + racional no card (A3) · prova ponta-a-ponta: `PersonaBatonFlowTest` (6 casos: handoff invisível→P · kickback visível→Q · teto→prossiga · reincidência→corte · proposta→T3 intocado · sem bloco→idle) | **ADR-0031** (commitada) + forward-refs 0017/0018 · branch `feat/ceo-tres-personas-adr0031` · **§D provado LIVE na teams-demo (2026-07-10): V24/V25 no boot · backfill (Atlas=questionador, motor codex intocado) · grill→dossiê apendado→"sim"→cascata Q→P→E autônoma→27 propostas PENDING com racional/motor por-agente · thread uma-voz (2 visíveis/4 na trilha) · estratégia com Premissas+teto 27<30; residual: D10 (web/Task in-run) não exercitado — motor codex, registrar** · vetos H TODOS confirmados · 3 SKILL.md = R1-D (⚠ SYNC-FATIA-2 destravado: formato pinado em `PersonaHandoffScannerTest`) |
| **Fatia G5** — ID sequencial de Issue (`#N` por company) | ✅ IMPLEMENTADA · VENDO | M1–M9 verdes (suíte **448** = piso 443 + 5; front **242**, asserts endurecidos): `V23__issue_seq.sql` (coluna + backfill retroativo + `uq_issue_company_seq`) · `IssueService.create` aloca sob `CompanyRepository.findByIdForUpdate` (o lock da company É o serializador do MAX+1; unique = backstop) · prova: `IssueSeqTest` (T-corrida: 8 criadores concorrentes → 1..8 denso, zero exceção; sem o lock quebrava) · `#N` em TODA superfície (lista ordenada por seq/detalhe/feed/card/review/servers) + estado vivo do CEO (`LiveStateProjector.java`) | **ADR-0033** · `handoff-grilling-id-sequencial-issue` · **§VENDO live (backfill V23 no Postgres real + issue nova MAX+1 + CEO cita `#2`) = confirmação final do OPERADOR** |
| **Caso C direto (R4-A)** — contratação pedida ao balcão + anti-silêncio do canal de proposta | ✅ IMPLEMENTADA (2026-07-11) · pende VENDO | Suíte **503** = piso 497 + 6: trilho do caso C no `blocoQuestionador` (NUNCA `proposta`; dossiê + handoff ao planejador NO MESMO turno — pedido explícito JÁ É o "sim") + carona do caso B (prosa não nomeia destinatário) · cinto de emissor (só Executor/CEO legado protocolam — `ConversationTurnCoordinator.entregarPropostasAoGate`) · `ProposalScanner` coluna 0 simétrica + `Scan(propostas, descartadas)` com WARN · `ProposalDescarteNotice` (aviso ANSWERED visível sem Run, idempotente `[gatilho]`; régua M9 pula o aviso) · prova: replay da Prova R3-A em `PersonaBatonFlowTest` (shape inventado → gate vazio MAS aviso visível) | **ADR-0036** + forward-refs 0018/0031 · branch `feat/caso-c-hire-direto` |
| Autoria dos 3 SKILL.md (`writing-doc-plans` · `being-ceo` · `delegation`) | ✅ FECHADA — vetadas e REGISTRADAS no DB (faixa R3-A, 2026-07-10) | 11 vetos H decididos um a um (dois modos do grill DENTRO do `being-ceo`; wrappers `grill-me`/`grill-with-docs` NÃO importados; `grilling` importada com emenda de 1 linha; `adaptive-communication` EN verbatim; `cs-ceo-advisor` descartada; seções/tetos 30-10/off-book confirmados) · sync Fatia 2 = skills APONTAM para o contrato inline do `composePrompt` (nunca duplicam) · 5 skills registradas nas 2 demos com corpo==draft por md5 · anexadas às 6 personas (Q=3 · P=2 · E=1) · prova viva em Demo Fixtures: **A3 expresso PASS** (racional legível citando o dossiê ANTES do menu; grill uma-pergunta-por-vez com recomendação; dossiê apendado por turno; `.codex/skills/` md5==banco, `.claude/` ausente) · **achado devolvido à sessão de controle:** caso C direto → Questionador emite `proposta` com shape inventado (o formato canônico só viaja no blocoExecutor) → scanner descarta, gate vazio | drafts finais + registro dos vetos em `docs/skills-ceo/` (`sourcing.md`) |

## Frente C — Método de memória do CEO (2º cérebro) — PARCIAL

Estágio real **resolvido** (o rascunho pedia CONFIRMAR): **PARCIAL · EM EXECUÇÃO.** O *átomo* está no código;
a *assinatura do método* (anti-inchaço) ainda não.

| Sub-parte | Verdict · Estágio | Evidência | Ponteiro |
|-----------|-------------------|-----------|----------|
| Decisão-como-átomo + racional por grill + carta (projeção determinística zero-LLM) | ✅ IMPLEMENTADA · VENDO | `memory/CompanyMemoryConsolidator.java:32-55` (carta = projeção de Decisões-de-direção vigentes) | ADR-0019/0020 |
| Cap-de-linha + **graduação** episódico→durável + tier Playbook | ⚪ AUSENTE · 🟡 PLANEJADO | `MemoryKind` só tem `CARTA`; sem ritual de graduação (residual 2 da ADR-0019, consciente) | `handoff-grilling-memoria-ceo-segundo-cerebro` |
| Índice enxuto sempre-carregado + navegação JIT | ⚪ AUSENTE · 🟡 PLANEJADO | método/prosa; única camada always-on é a carta (`CLAUDE.md`) | idem |

## Frente D — Backlog / ADIADOS (saem de grills futuros)

| Item | Origem | Estágio |
|------|--------|---------|
| Contabilidade de produto sobre a pesquisa off-book do `delegation` | Fatia 2 §G | ⚪ ADIADO |
| Auto-kickback amplo entre personas (além do `needs-grilling` cirúrgico) | Fatia 2 §G | ⚪ ADIADO |
| Interrupção mid-run (streaming ao vivo + kill) | Fatia 1 §G | ⚪ ADIADO |
| Levar o loop de handoff pros EXECUTORES (não só o CEO) | Fatia 1 §G | ⚪ ADIADO |
| Agente-como-tool (attach por agente + KnowAll body inline) | ADR-0003 rev · 7c | ⚪ ADIADO (v1 = board-only) |
| Propose-then-commit de molde (CEO cria molde; drift-badge) | Teams T.1-FE residual 3 | ⚪ ADIADO |
| _(próximo grill — a definir)_ | — | 💡 IDEIA |

---

## § Rastreabilidade de handoffs (todos os 40 + a proposta)

> Todo handoff mapeado a um **estágio verificado**. O detalhe mora no arquivo; aqui só o estado + âncora.
> Salvo nota, todos são **VENDO/FECHADO** e a evidência-âncora está na Frente A correspondente.

| Handoff | Estágio | Âncora / nota |
|---------|---------|---------------|
| `limpeza-codigo-raso-linecursor` | ✅ VENDO | `skills/LineCursor.java:17-48` (A.1) |
| `passada-consistencia` | ✅ VENDO | superfícies do board existem; **SUPERSEDED** no próprio doc (§ Div baixa) |
| `grilling-skills-knowall` | ✅ VENDO | `CompanyLibraryInstaller.java:30-38` (§ Div baixa: agente-como-tool foldado p/ futuro) |
| `f9e-skill-em-uso` | ✅ VENDO | `SkillMaterializer.java:34,59` montado em `AdapterRunExecutor.java:113-119` |
| `grilling-motores` / `-fechado` | ✅ VENDO | §C mapeia 1:1 (V12/V13, Claude/Codex adapters) |
| `f7-motores-continuacao` | ✅ VENDO | `Agent.java:159` (§ Div baixa: Context `image` movido p/ F.8) |
| `f7-motores-fatias-D-G` | ✅ VENDO | `CodexAdapter.java:32-62` + `EngineCatalogController` |
| `fatia7` (skills anexo por-Issue) | ✅ VENDO | V10 + `resolveForRun` (anexo-vence) + install-on-attach |
| `f8-isolamento` | ✅ VENDO | `EngineContainerTest` (interseção de volumes vazia) |
| `grilling-approval` / `-fechado` | ✅ VENDO | `V11__review.sql:4` (Q1–Q5 fechadas) |
| `grilling-teams` / `-fechado` | ✅ VENDO | 4 ADRs (0017–0020) existem (§ Div baixa: CEO-cria-molde = futuro) |
| `teams-t1` | ✅ VENDO | `RoleTemplateService.java:47-105` |
| `teams-t2` | ✅ VENDO | `TeamService.java:53-109` (arquiteto obrigatório) |
| `teams-t3a` | ✅ VENDO | `conversation/` (9 arquivos, CEO conversacional) |
| `teams-t3fe` | ✅ VENDO | `frontend/src/components/ceo-conversa.tsx` |
| `teams-t4` | ✅ VENDO | `SpawnLineageGuard.java:24,47-73` (lineage + fan-out + ledger) |
| `teams-estado-e-reconciliacao` | ✅ VENDO | `AgentController.java:47` (R1 aplicada) |
| `t1-fe-canvas` | ✅ VENDO | `frontend/src/app/moldes/page.tsx` (§ Div baixa: propose-then-commit futuro) |
| `grilling-memoria-ceo-segundo-cerebro` | 🟡 **PARCIAL** | `CompanyMemoryConsolidator.java:32-55`; graduação/cap = PLANEJADO (Frente C) |
| `f9-preview` | ✅ VENDO | `PreviewArtifactCapturer.java:189-198` |
| `grilling-preview-fechado` | ✅ VENDO | ADR-0021 aceito |
| `f9e-escritor-workspace` | ✅ VENDO | `DockerExecutionTarget.java:81-117` (§ Div baixa: smoke quitado na F.10) |
| `f10-execucao-parcial` | ✅ VENDO | `PreviewServerSupervisor` + `PreviewServerController` |
| `grilling-processo-porta-fechado` | ✅ VENDO | ADR-0023 aceito (8 decisões) |
| `grilling-thumbnail-preview` | ✅ VENDO | `PreviewThumbnailCapturer.java:50-60` |
| `grilling-plano-codex-fechado` | ✅ VENDO | ADR-0024 existe |
| `f11-correcao-adapter-codex` | ✅ VENDO | `CodexArgv.java:45` (§ Div baixa: caveat de parser ainda no código) |
| `grilling-gc-retencao-workspaces` | ✅ VENDO | `WorkspaceReaper.java:74-171` (ADR-0026) |
| `grilling-cap-graduacao-rows-fechado` | ✅ VENDO | `RowRetentionReaper.java:140-183` (F.14) |
| `grilling-retencao-rows-conversa-fechado` | ✅ VENDO | `HeartbeatRunRepository.java:41-48` (F.15) |
| `frontend-f2-f6` | ✅ VENDO | `sidebar.tsx:39-45` (shell + superfícies) |
| `frontend-f5-f6` | ✅ VENDO | `reviews/page.tsx` (§ Div baixa: CSS TODO já resolvido) |
| `grilling-frontend-fechado` | ✅ VENDO | `MissionControlController` (§ Div baixa: GAPs pré-F.0 fechados; WS por decisão) |
| `grilling-loop-handoff-contexto-fechado` | 🟡 **PLANEJADO** | ausência-limpa (Frente B); → ADR-0030 |
| `grilling-3-ceos-personas-fechado` | 🟡 **PLANEJADO** | ausência-limpa (Frente B); → ADR-0031 |
| `proposta-3-ceos-personas` | ⚫ **SUPERSEDIDA** | doc de origem; grill fechado divergiu (§ Div baixa) |
| `grilling-7c` / `-fechado` | ✅ VENDO | `SkillSetService.java:88,96-97` (install-on-attach, Rota A) |

---

## § Divergências (doc × código — sobreviventes da refutação)

> 48 candidatas → **25 confirmadas** (refutação adversarial matou 23 falsos-positivos de busca incompleta).
> **1 alta · 6 média · 19 baixa.** As baixa são, em bloco, envelhecimento de handoff consumido ou residual
> consciente — **não** defeito. O sinal acionável está na ALTA e nas MÉDIAS. (A 6ª média = defeito de
> **runtime** achado no VENDO live do loop de handoff em 2026-07-08 — não da varredura doc×código de 07-07 —
> já **CORRIGIDA** no mesmo dia, ver abaixo.)

### 🔴 ALTA (0 abertas · ambas FECHADAS em 2026-07-10) — merece o olho do Matheus (**H**)

- ~~**NOVA (2026-07-09, achada AO VIVO) · agente codex roda "pelado".**~~ **FECHADA (2026-07-10):**
  fatia G3 executada (branch `feat/agents-md-codex-adr0032`, **ADR-0032**) — o adapter declara a
  `WorkspaceConvention` e o codex recebe carta em `AGENTS.md` + skills em `.codex/skills/` (canais
  nativos, sondas S1/S2); faxina permanente apaga o espelho `.claude/` que ele nunca leu. Era:
  carta e skills SÓ em `.claude/` — o "motor duplo" (ADR-0024) executava mas não lembrava nem sabia
  (Atlas, run B: *"A skill `distillation` não está disponível"*). Confirmação final = §VENDO live.
- ~~**ADR-0005 · lease sem renovação.**~~ **FECHADA (2026-07-10): renovação IMPLEMENTADA** — fatia
  executada conforme o grill (`docs/handoff-grilling-renovacao-lease.md`, D1–D6): `LeaseRenewer`
  (timer por run, 1 thread daemon, nasce no claim e morre no finally do dispatcher) + CAS
  `renewLease` guardado por dono (Store+Repository) + `RUN_LEASE_LOST` anti-silêncio na perda de
  posse (sem kill; o guard do finalize protege o resultado) + `LeaseRenewalTest` 4 provas +
  emenda do trecho Recovery na PRÓPRIA ADR-0005 (nota de implementação). `AdapterRunExecutor`
  intocado (D1); zero migration. Era: O ADR afirma *"o worker renova o lease enquanto o Run vive"* (base do
  objetivo "prevenir double-work"). **No código não há renovação** (`grep renew/extendLease` = vazio): lease
  fixo de 2min (`HeartbeatDispatcher.java:30`), execução **síncrona** de processo de agente real que
  **rotineiramente excede 2min**, e o reaper a cada 5s (`HeartbeatScheduler.java:43`) re-enfileira o `RUNNING`
  vencido. *Mitigante provável:* o índice `uq_run_active_agent` (`V3:26`) barra 2 Runs ativos por agente — então
  o efeito real precisa ser **investigado** (auditoria é read-only, não rodou o cenário). **Ação:** ou implementar
  a renovação de lease, ou reconciliar o ADR-0005 com o desenho lease-fixo+reaper. `direction=doc_afirma_codigo_nao_confirma`.
  **ATUALIZAÇÃO (2026-07-10):** laudo read-only executado (predição confirmada: 0 reexecuções na história;
  sonda provou o buraco worker-vivo por construção) → **DECISÃO do Matheus: implementar renovação** →
  **grill fechado (D1–D6)** em `docs/handoff-grilling-renovacao-lease.md` (timer por run no Dispatcher ·
  30s→lease cheio · `RUN_LEASE_LOST` sem kill · `LeaseRenewalTest` 4 provas · zumbi de seed via operador ·
  emenda na própria ADR-0005). Execução CONDICIONADA: R1-E landada + recálculo de colisão com a Fatia 2.

### 🟠 MÉDIA (6 · 1 CORRIGIDA 2026-07-08 · **5 reconciliadas em doc 2026-07-10** — D5/D11 seguem residual **H**)

- **ADR-0001 · jOOQ nunca adotado.** Stack pinada (e ARQUITETURA.md ×5) cita *"jOOQ nas queries de corrida"*;
  `pom.xml` **sem** jOOQ, zero `import org.jooq` no backend. As queries de corrida existem em **Spring Data**:
  SQL nativo `IssueRepository.java:48-64` (checkout) e JPQL CAS `HeartbeatRunRepository.java:64-72` (claim). O
  invariante de concorrência **é honrado**, por outro mecanismo. **Ação:** atualizar ADR-0001/ARQUITETURA (ou adotar jOOQ).
  ✅ **Reconciliada em doc (2026-07-10):** nota na ADR-0001 + as 5 menções do ARQUITETURA.md corrigidas (faixa R1-E, docs-only).
- **ADR-0005 · sem `SELECT … FOR UPDATE SKIP LOCKED` no claim.** O ADR descreve DB-as-queue com SKIP LOCKED; o
  claim é `findTop50ByStatus…(QUEUED)` (SELECT simples) + CAS `UPDATE … WHERE status=:queued`
  (`HeartbeatRunRepository.java:16,66`). Metade CAS presente, metade SKIP LOCKED ausente (o único `FOR UPDATE` é
  `PESSIMISTIC_WRITE` no Agent, `AgentRepository.java:22`, não no claim do Run).
  ✅ **Reconciliada em doc (2026-07-10):** nota na ADR-0005 descreve o mecanismo real (enumeração sem lock + CAS;
  o invariante anti-double-claim é do CAS) — **SÓ o trecho do claim**; o trecho de renovação de lease (ALTA acima)
  segue **intocado**, tratado pela fatia própria.
- **ADR-0004 · `RunResult.producedArtifacts` inexistente.** O ADR diz que o Result inclui `producedArtifacts`
  (incl. PreviewArtifact); `AgentAdapter.java:67` (Result) **não tem** o campo — o preview é capturado **fora** do
  Result, por `PreviewArtifactCapturer` lendo arquivos publicados (`AdapterRunExecutor.java:88-92`). Contrato do ADR desatualizado.
  ✅ **Reconciliada em doc (2026-07-10):** nota na ADR-0004 — contrato atualizado para a captura lateral no executor
  (pós-sucesso, pré-settle, best-effort; ADR-0021/0023).
- **ADR-0019 · D5: passagem pela review NÃO emite Decision.** Só `ApprovalService.decide→recordGateDecision`
  fabrica Decision; `IssueService.java:158-184` grava `REVIEW_APPROVED/REJECTED` no activity_log mas **não** cria
  Decision. `Decision.anchoredToReview` (`Decision.java:99`) e a coluna `review_id` (`V18:35`) **sem caller** — seam/coluna mortos.
  🟠 **Reconciliada em doc (2026-07-10), RESIDUAL ABERTO:** nota na ADR-0019 registra o seam morto; decisão **H**
  pendente (puxar fatia p/ ligar review→Decision × aceitar como está).
- **ADR-0019 · D11: `supersedes` sem caminho de escrita.** O consolidador **lê** e filtra `superseded`
  (`CompanyMemoryConsolidator.java:36-42`), mas **nenhum writer** seta `supersedesId` (`Decision.java:62-63,150` só
  getter). Existe a fundação (coluna + read-filter), não a revisão-como-nova-Decisão.
  🟠 **Reconciliada em doc (2026-07-10), RESIDUAL ABERTO:** nota na ADR-0019 registra o seam morto (confirmado
  também no `DecisionController`: GET-only, o campo sai na resposta mas nunca entra); decisão **H** pendente
  (puxar fatia da linhagem × aceitar como está).
- **✅ CORRIGIDA (2026-07-08) — Adapter codex · `ceo_reply` pegava o `STDOUT` cru em vez da mensagem final do
  agente** (achado no VENDO live da ADR-0030 — **visível ao dono**, o único desta lista que o cliente via).
  `RunFinalOutput.text()` (`execution/RunFinalOutput.java:45-53`) escolhe o reply por prioridade
  `RESULT → ASSISTANT → STDOUT`; o codex rotulava a mensagem final como `ITEM.COMPLETED` → caía no `STDOUT` cru
  (*"Reading additional input from stdin…"*), descartando a prosa real (ex.: *"Gravei o bilhete…; passo 1:
  contratar Lyra…"*). **Correção — NÃO onde o roadmap supôs:** o defeito mora no PARSER, não no `RunFinalOutput`.
  Reasoning **também** carrega `text`, então só o parser (que ainda tem o `item.type`) distingue a mensagem do
  agente do raciocínio/comando — o `RunFinalOutput` só vê `(type, detail)` achatado. `CodexStreamParser` agora
  promove a mensagem do agente (`item.completed` com `item.type=agent_message` **com texto** — o shape
  REAL do CLI 0.142.5, §A do `handoff-f11`, não o `item_type=assistant_message` sintético das fixtures antigas)
  ao nível compartilhado `RESULT` (**last-wins**: a ÚLTIMA mensagem é a resposta ao dono); o `RunFinalOutput`
  ficou **intocado**. Diferença semântica proposital: os `ASSISTANT` do claude são CHUNKS de uma resposta
  (juntam); cada `agent_message` do codex é uma mensagem COMPLETA e só a última importa — daí `RESULT`, não
  `ASSISTANT` (que despejaria a narração de progresso no reply). Reasoning/comando seguem `ITEM.COMPLETED`, fora
  do reply. TDD (`AgentReplyDerivationTest` — tracer ponta-a-ponta linhas cruas → parser → RunFinalOutput + teste
  de last-wins multi-mensagem) + fixtures corrigidas para o shape real; **428 testes verdes**. Pacote `handoff/`
  intocado. **VENDO live 2026-07-08**: a 1ª volta (nível `ASSISTANT`/junta) rodou no run `68aac0d4` (Atlas) e o
  dono, VENDO os 8 passos de narração empilhados no reply, decidiu **última-vence** — reply = só a última
  mensagem (*"Sou Atlas, o CEO delegador…"*); o `STDOUT` *"Reading additional input…"* segue no StepTrace **mas
  perde**. `direction=runtime_defeito`.

### 🟡 BAIXA (19) — em blocos (sem ação; registrados p/ inventário)

- **Envelhecimento de handoff consumido / ADR pré-refactor (12):** contagens de teste e refs de linha históricas
  (`passada-consistencia` V14→hoje V22; `frontend-f5-f6` 110→204; `frontend-grilling-fechado` GAPs pré-F.0 fechados);
  shapes stale por refactor posterior (`ADR-0004` bloco ilustrado; `f7-motores-continuacao` Context `image`→F.8;
  `ADR-0006` corpo não reflete drena-até-checkpoint da T.4; `ADR-0013` "5 superfícies"→cresceu p/ Servidores/Moldes/Glossário;
  `ADR-0024` caveat de parser ainda no `.java`; `f9e-escritor-workspace` smoke quitado depois; `grilling-skills-knowall`
  agente-como-tool foldado; `7c-fechado` 3º ramo PENDING_REVIEW adicionado pela ADR-0012).
- **Residual consciente, decidido-futuro (3):** `grilling-teams-fechado` (CEO cria molde), `t1-fe-canvas`
  (propose-then-commit + drift-badge), `memoria-ceo` (cap-de-linha/graduação/índice-JIT — Frente C).
- **Design supersedido, documentado (1):** `proposta-3-ceos-personas` (1 agente trocando de chapéu) → o grill
  fechado virou **3 agentes duráveis** `role='ceo'+persona` e tornou a Fatia 2 **paralela** (não "1º cliente").
- **Fundação-only, não-estrutural (1):** `ADR-0001` deny-by-default é `assertMember()` manual por-rota +
  interceptor que **só loga** (`SecurityConfig.java:24` `permitAll`; `CompanyScopeInterceptor.java:34-45`), não um
  wrapper que negue por construção — "esquecer" vaza e vira log. *(Rotas atuais cumprem.)*
- **Código sem ADR, esperado (1):** a fase de **UI/UX inteira (P0–P2C)** não tem ADR; decisões vivem em
  `levantamento-ui-ux.md` + `plano-ui-ux-p2c.md` (D-*/M-*). Registrado — não é gap silencioso.

### § Reconciliação com a memória (memória × código)

- **F.14 — finding pendente `feed.ts:47` (SYSTEM rotulado "board").** A memória marca como *pendente de aval*; o
  handoff `cap-graduacao-rows-fechado` (§RESULTADO:502-510) **já shippou** o fix `feed.ts` SYSTEM→"sistema", e a
  auditoria do Activity Log (C10) teve sua divergência **refutada**. → memória levemente **desatualizada** nesse ponto.
- **Fatia 1 / Fatia 2 = PLANEJADO (código não existe).** Memória **confere** com o código (ausência limpa).
- **Frente C "CONFIRMAR o estágio".** Resolvido pela auditoria: **PARCIAL / EM EXECUÇÃO** (átomo pronto; graduação PLANEJADO).

---

## § Vetos pendentes (decisões que aguardam o Matheus — **H**)

**Fatia 2 (3 personas):**
- Formato dos blocos tipados (```` ```handoff ```` / ```` ```needs-grilling ````) — contrato do roteamento.
- Blocos do `writing-doc-plans` (estratégia + Premissas + validação de cobertura).
- `N` da trava anti-loop de kickback.
- Governança off-book do `delegation` (aceito no v1; freio duro = subsistema).
- Sourcing das skills externas (`cs-ceo-advisor`, `brainstorming`, `teach`, `adaptive-communication`, `grill-me`, `grill-with-docs`).
- ADR-0031 nova (confirmar).

**Fatia 1 (loop de handoff):**
- Template do bilhete destilado.
- `N=5` da trava anti-loop.
- Nome do dial `orquestrador.handoff.context-threshold-percent` + default 55.
- ADR-0030 nova (confirmar).

**Novo (da auditoria 2026-07-07):**
- ~~**Divergência ALTA (ADR-0005 lease):** implementar renovação de lease **ou** reconciliar o ADR? (investigar o efeito real primeiro).~~ **RESPONDIDA (2026-07-10): implementar renovação** — grill fechado, plano em `docs/handoff-grilling-renovacao-lease.md` → **EXECUTADA (2026-07-10):** `LeaseRenewer` + `renewLease` CAS + `RUN_LEASE_LOST` + `LeaseRenewalTest` (4 provas); ADR-0005 emendada (trecho Recovery); pende só o VENDO live do pulso (com operador).
- **Médias doc×código (jOOQ · SKIP LOCKED · `producedArtifacts` · D5 review→Decision · D11 supersedes):**
  ~~atualizar os ADRs para refletir o código, ou puxar fatias?~~ **Reconciliação em doc EXECUTADA (2026-07-10,
  faixa R1-E):** notas datadas nas ADR-0001/0004/0005 (só claim) /0019 + ARQUITETURA.md corrigida. **Segue H:**
  destino dos seams mortos da 0019 (`review_id` / `supersedesId`) — puxar fatia × aceitar como está.

---

## § Nota de estrutura (proposta — decisão do Matheus **H**)

A estrutura **Frentes A–D** foi mantida como esqueleto. Uma leitura alternativa, alinhada ao teu cronograma:
**Parte A = fundação já construída** (esta Frente A, 28 ADRs VENDO — F.0→F.15 + UI/UX) · **Parte B = retomada
do cronograma pausado** (Frente B, o v1 do CEO). É só **sinalização** — a organização final é tua.

---

## § Histórico de grills & auditorias

| Data | Evento | Resultado |
|------|--------|-----------|
| 2026-07-07 | **Auditoria de implementação (este inventário)** | 29 ADRs + 40 handoffs verificados contra o código; 28 ADRs VENDO · 1 PARCIAL · 2 fatias PLANEJADO; **25 divergências** (1 alta/5 média/19 baixa) |
| 2026-07-07 | Loop de handoff de contexto (Fatia 1) | handoff fechado (D1–D9, M1–M10) → PLANEJADO |
| 2026-07-07 | CEO em 3 personas (Fatia 2) | handoff fechado (D1–D12, M1–M9) → PLANEJADO |
| 2026-07-08 | Fix reply do codex (última `agent_message`) | **PR #2 MERGED**; piso 428 |
| 2026-07-09 | Grill insatisfações do dono (G1–G6) + re-grill fluxos A/B | `handoff-grilling-insatisfacoes-funcionamento-ceo` (§B, §B.2) + §ADENDO na Fatia 2 |
| 2026-07-09 | Hotfix prompt do CEO (G1 conversa + G2 bilhete condicional) | **PR #3 MERGED**; re-medição: 31→9 eventos, ~1min→8,5s; piso 429 |
| 2026-07-09 | **Fatia AGENTS.md (G3)** — grill do mecanismo | handoff fechado (D1–D10, M1–M10 + VENDO c/ Docker vivo) → PLANEJADO · **ADR-0032** a criar · divergência 🔴 nova registrada |
| 2026-07-10 | **Fatia AGENTS.md (G3) — EXECUTADA** (/tdd, M1–M8+M10) | **ADR-0032** criado; suíte **443** (piso 429 + 14); divergência 🔴 "codex pelado" FECHADA; layout claude bit-idêntico |
| 2026-07-10 | **Fatia G5 (ID sequencial #N)** — grill fechado em sessão própria (R1-B do ledger) | `handoff-grilling-id-sequencial-issue` (D1–D5: lock-da-company+MAX+1 · backfill retroativo · "#N é o que eu vejo, UUID é auditoria" · #N no estado vivo · **ADR-0033 autorada**) → PLANEJADO; kickoff de execução emitido (V23, branch de main) |
| 2026-07-10 | **Fatia AGENTS.md (G3) — VENDO live PASS (fechada)** | **PR #4 MERGED**; M9 (pg_dump + faxina DB 24→3 companies + wipe workspaces + restart) e 5/5 provas: carta (Atlas, `AGENTS.md`==carta v4 por md5 + `.claude/` ausente + resposta "dental+3 motivos") · skill (sonda via import→set→`.codex/skills`→`ABACATE-ROXO-93`; lista `distillation`) · ruído (1 `ITEM.COMPLETED\|error`/run = baseline G4, resíduo) · Docker (volume `orq_b92cb312_73676f52`: AGENTS.md md5==banco, `.claude/` ausente) · gates 443 na branch / **448 na main pós-G5** |
| 2026-07-10 | **Reconciliação doc-only das 5 médias (faixa R1-E)** | notas datadas em ADR-0001/0004/0005 (só o claim; lease intocado) /0019 + 5 menções jOOQ do ARQUITETURA.md corrigidas; D5/D11 registrados como RESIDUAL ABERTO (**H**) |
| 2026-07-10 | **Renovação de lease (ADR-0005) — EXECUTADA** (/tdd, D1–D6/M1–M5) | `LeaseRenewer` (timer por run, pulso 30s→lease cheio) + `renewLease` CAS por dono + `RUN_LEASE_LOST` anti-silêncio + `LeaseRenewalTest` 4 provas; ADR-0005 emendada (Recovery); divergência 🔴 ALTA do lease FECHADA; suíte **474** (piso 470 + 4); `AdapterRunExecutor` intocado; pende VENDO live do pulso + zumbi D5 (operador) |
| 2026-07-10 | **CASO B (melhoria de task entregue) — grill fechado em sessão própria (R3-C do ledger)** | `handoff-grilling-caso-b.md` (D1–D8: ```` ```correcao ```` Q-direto · corrente quebrada=para-e-avisa · verbatim=cópia do banco · sistema cria/arquiteto é dono (divergência da LETRA do B2, registrada) · `corrige_issue_id` V26 · Decision determinística origem-AGENTE (1º writer) · sem trava, sinal de linhagem · **ADR-0034** a criar) → PLANEJADO; kickoff de execução emitido (branch de main, piso 474/246); seams D5/D11 da 0019 checados = intocados |
| 2026-07-10 | **CASO B (ADR-0034) — EXECUTADA + VENDO PASS na descartável** (/tdd + /verify, D1–D8/M1–M7) | `CorrecaoDeEntregaRouter` (seam de 1 método) + 3º padrão do scanner + V26 `corrige_issue_id` + `createCorretiva` (chokepoint da 0033) + **1ª Decision origem-AGENTE do produto** + confirmação ANSWERED sem run + selo "corrige #N" (lista+detail); suítes **489** backend (piso 474 + 15) · **248** front (246 + 2), tsc/lint limpos; **VENDO §D 10/10 no 18081+pg efêmero** (:8080 intocado) — feliz com **run claude REAL** (opus emitiu o bloco de 1ª; md5 verbatim true; carta intocada) · corrente quebrada/DONE/linhagem/fail-soft PASS · carona D10: zero sub-agentes no rastro (registro no §RESULTADO do handoff); **pendências de veto no PR** (formato do bloco · textos M6 · título) |
| _próximo_ | Fatia 2 (CEO em 3 personas, ADR-0031) — destravada pelo merge da G3; Frente C idem | — |
