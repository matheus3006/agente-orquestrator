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

> **▸ Atualização 2026-07-15 (o Panorama acima é o snapshot de 2026-07-07).** Desde então a **orquestração v1
> do CEO FECHOU** — as "2 fatias `PLANEJADO`" landaram (Fatia 1 ADR-0030/PR #1 · Fatia 2 ADR-0031/PR #8), mais
> G3/G5/caso B/absorção/caso C, o redesenho motor-nativo (F-N1–F-N6) e agente-como-tool (F-AT1/F-AT2) — e o
> **método de memória (2º cérebro) COMPLETOU** (Frente C: F-C1/PR #25 · F-C2/PR #26 · F-C3/PR #28). A lista H
> (R17-B) somou mais 3 fatias (lote×Decision/#29 · circuit-breaker/#30 · feedback→executor/#31). Só a **ADR-0015**
> segue como "princípio praticado" (a Fatia 1 do loop landou; a régua a 50-60% roda). **Código do v1 COMPLETO
> (PRs #1–#31)** — resta a **PROVA FINAL**. Estado atual nas Frentes B e C.

> **▸▸ v1 DECLARADO FECHADO PELO DONO (2026-07-16).** A prova final (R21-A) rodou as pernas
> (a)–(f) com motor real, achou o **bug #1** (DONE fantasma) + 4 achados, e o dono decidiu:
> o v1 FECHA; o que sobrou vira **roadmap de ajustes** (seção própria abaixo, antes da Frente D).
> A fase atual é **descoberta de bugs**: as ADRs viram a especificação, cada funcionalidade é
> testada AO VIVO e classificada funciona/não-funciona (plano da caça = R22-B,
> `docs/plano-descoberta-bugs.md`). PRs #1–#33 MERGED · main `83c5fe9` · piso 738/294.

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
| Recovery de Issue travada por Run FAILED (park-for-human **de ISSUE** — não confundir com o park-for-human **de CONVERSA** do fix #33, que estaciona a retomada de contexto enquanto o kickback espera o dono) | ✅ IMPLEMENTADA · VENDO | `work/issue/IssueService.java:185` (`tryBlock` guardado por runId + `ISSUE_BLOCKED`; a citação anterior `:108` era o meio da corretiva do caso B — o código andou) | ADR-0011 (emendada R19-A + **R22-A: run no-op nunca fecha issue** — cai neste mesmo BLOCKED) |
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

## Frente B — v1 do CEO (a camada de ORQUESTRAÇÃO) — **CONSTRUÍDA (v1 FECHADO pelo dono 2026-07-16 — PRs #1–#33)**

Sequência: **Fatia 1 (fundação do transporte)** e **Fatia 2** são **PARALELAS** (o grill fechado da Fatia 2
tornou o bilhete conversation-scoped — ver § Div). **Fatia 1 IMPLEMENTADA (ADR-0030, PR #1); Fatia 2
IMPLEMENTADA (ADR-0031, PR #8) — VENDO live PASS 2026-07-10** (a leitura "`PLANEJADO`/ausência-limpa" era o
snapshot da auditoria 2026-07-07; ambas as fatias landaram desde então — coerente com a tabela abaixo).
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
**Grill da ABSORÇÃO DE ISSUES DO BOARD FECHADO em 2026-07-11 (faixa R4-B)** → **EXECUTADA
(2026-07-11, branch `feat/absorcao-issues-adr0037`)**:
`handoff-grilling-absorcao-issues.md` (D1–D10/M1–M13 + §RESULTADO; issue do dono dispara turno ao
Planejador → ```` ```absorcao ```` aponta o TIME → sistema resolve time→arquiteto e seta assignee;
issue = conversa DIRECIONADA ancorada 1:1 (**V27 consumida**); o gate ganhou `CREATE_TEAM`
(time+arquiteto atômico — alargamento deliberado do enum, com retoma automática pós-approve) e
`CREATE_ISSUE` passou a carregar time e nascer com dono — invariante "nada que o CEO cria nasce sem
dono" fechado nas DUAS origens; **ADR-0037 consumida** + forward-refs aplicadas nas
0017/0018/0031/0033/0034; contrato de intenção do ARQUITETO (todo time nasce com arquiteto ·
possível divisão em 2 personas · clone por ociosidade >30min) registrado VERBATIM no §G.1 do
handoff = SEMENTE de grill próprio, faixa nova a criar; novo piso proposto: backend **523** ·
front **269**).

| Item | Verdict · Estágio | Evidência de ausência-limpa / fundação | Ponteiro · Próxima ação (dono) |
|------|-------------------|----------------------------------------|--------------------------------|
| **Fatia 1** — loop de handoff de contexto (50-60%) | ✅ IMPLEMENTADA · VENDO (integração) | M1–M10 verdes (423 testes): `handoff/HandoffThresholdDetector.java:45-64` (% pós-run + fail-open + dial 55) · `handoff/ContextHandoffLoop.java:44-77` (detectar→garantir bilhete→continuar + rede resume-cirúrgico) · `handoff/ContinuationTurnPoster.java:52-73` (sentinela + trava N=5 + idempotência) · `handoff/DistillationSkill.java:60-96` (skill sintética = template) · `conversation/ConversationTurnCoordinator.java:98-112` (M5) · `execution/AgentSessionStore.java:37-45` (1º leitor `--resume` cirúrgico) · `application.yml:30-34` (dial). Prova ponta-a-ponta: `HandoffLoopTest` (cruza+bilhete→1 continuação · sem bilhete→rede→continuação · abaixo→nada). **/verify live teams-demo = confirmação final** | **ADR-0030** · `handoff-grilling-loop-handoff-contexto-fechado` |
| **Fatia AGENTS.md (G3)** — o adapter declara a convenção de workspace (codex recebe carta + skills) | ✅ VENDO (provada live 2026-07-10) | M1–M8 verdes (suíte **443** = piso 429 + 14): `execution/adapter/WorkspaceConvention.java` (record `claude()`/`codex()` + `KNOWN`) · `AgentAdapter.java` (default `claude()`; só `CodexAdapter` sobrescreve) · materializers paramétricos no caminho (layout claude **bit-idêntico** — asserções intocadas) · `AdapterRunExecutor` (fail-fast pré-checkout D4 + faxina `KNOWN−ativa` D6 + união no escritor P2) · prova: `CodexWorkspaceConventionOnRunTest` (6 casos, inclui o simétrico codex→claude) | **ADR-0032** · **PR #4 MERGED** · **§VENDO live PASS: 5/5 provas** (carta md5-idêntica + `.claude/` ausente + skill-sonda ponta a ponta + ruído no baseline + volume Docker) — §RESULTADO no `handoff-grilling-agents-md-codex` |
| **Fatia 2** — CEO em 3 personas (Q/P/E) | ✅ IMPLEMENTADA · **VENDO (live PASS 2026-07-10)** | M1–M9 + adendo A1–A5 verdes (suíte **469** = piso 448 + 21; front **246** = 242 + 4): `V24__agent_persona.sql` + `V25__ceo_persona_moldes.sql` (3 moldes `ceo-*` + backfill: canônico→questionador, P/E copiam o motor) · `org/agent/CeoPersona.java` + `CeoPersonaSeeder.java` (auto-seed mold-driven fail-soft, D9) · resolvedor por-persona (`ConversationService` + fallback legado, D2) · `composePrompt` por-persona (M7; G1/G2 preservados) · workspace de conversa compartilhado (`WorkspaceResolver.resolveConversation` + peek pré-claim, M4/D4) · `conversation/persona/` (scanner col-0 irmão do ProposalScanner + roteador + poster com M9 prossiga→CORTE na reincidência) · gate: motor do alvo + `EngineOverride` do dono no decide (A4) + racional no card (A3) · prova ponta-a-ponta: `PersonaBatonFlowTest` (6 casos: handoff invisível→P · kickback visível→Q · teto→prossiga · reincidência→corte · proposta→T3 intocado · sem bloco→idle) | **ADR-0031** (commitada) + forward-refs 0017/0018 · branch `feat/ceo-tres-personas-adr0031` · **§D provado LIVE na teams-demo (2026-07-10): V24/V25 no boot · backfill (Atlas=questionador, motor codex intocado) · grill→dossiê apendado→"sim"→cascata Q→P→E autônoma→27 propostas PENDING com racional/motor por-agente · thread uma-voz (2 visíveis/4 na trilha) · estratégia com Premissas+teto 27<30; residual: D10 (web/Task in-run) não exercitado — motor codex, registrar** · vetos H TODOS confirmados · 3 SKILL.md = R1-D (⚠ SYNC-FATIA-2 destravado: formato pinado em `PersonaHandoffScannerTest`) |
| **Fatia G5** — ID sequencial de Issue (`#N` por company) | ✅ IMPLEMENTADA · VENDO | M1–M9 verdes (suíte **448** = piso 443 + 5; front **242**, asserts endurecidos): `V23__issue_seq.sql` (coluna + backfill retroativo + `uq_issue_company_seq`) · `IssueService.create` aloca sob `CompanyRepository.findByIdForUpdate` (o lock da company É o serializador do MAX+1; unique = backstop) · prova: `IssueSeqTest` (T-corrida: 8 criadores concorrentes → 1..8 denso, zero exceção; sem o lock quebrava) · `#N` em TODA superfície (lista ordenada por seq/detalhe/feed/card/review/servers) + estado vivo do CEO (`LiveStateProjector.java`) | **ADR-0033** · `handoff-grilling-id-sequencial-issue` · **§VENDO live (backfill V23 no Postgres real + issue nova MAX+1 + CEO cita `#2`) = confirmação final do OPERADOR** |
| **Caso C direto (R4-A)** — contratação pedida ao balcão + anti-silêncio do canal de proposta | ✅ IMPLEMENTADA (2026-07-11) · pende VENDO | Suíte **503** = piso 497 + 6: trilho do caso C no `blocoQuestionador` (NUNCA `proposta`; dossiê + handoff ao planejador NO MESMO turno — pedido explícito JÁ É o "sim") + carona do caso B (prosa não nomeia destinatário) · cinto de emissor (só Executor/CEO legado protocolam — `ConversationTurnCoordinator.entregarPropostasAoGate`) · `ProposalScanner` coluna 0 simétrica + `Scan(propostas, descartadas)` com WARN · `ProposalDescarteNotice` (aviso ANSWERED visível sem Run, idempotente `[gatilho]`; régua M9 pula o aviso) · prova: replay da Prova R3-A em `PersonaBatonFlowTest` (shape inventado → gate vazio MAS aviso visível) | **ADR-0036** + forward-refs 0018/0031 · branch `feat/caso-c-hire-direto` |
| Autoria dos 3 SKILL.md (`writing-doc-plans` · `being-ceo` · `delegation`) | ✅ FECHADA — vetadas e REGISTRADAS no DB (faixa R3-A, 2026-07-10) | 11 vetos H decididos um a um (dois modos do grill DENTRO do `being-ceo`; wrappers `grill-me`/`grill-with-docs` NÃO importados; `grilling` importada com emenda de 1 linha; `adaptive-communication` EN verbatim; `cs-ceo-advisor` descartada; seções/tetos 30-10/off-book confirmados) · sync Fatia 2 = skills APONTAM para o contrato inline do `composePrompt` (nunca duplicam) · 5 skills registradas nas 2 demos com corpo==draft por md5 · anexadas às 6 personas (Q=3 · P=2 · E=1) · prova viva em Demo Fixtures: **A3 expresso PASS** (racional legível citando o dossiê ANTES do menu; grill uma-pergunta-por-vez com recomendação; dossiê apendado por turno; `.codex/skills/` md5==banco, `.claude/` ausente) · **achado devolvido à sessão de controle:** caso C direto → Questionador emite `proposta` com shape inventado (o formato canônico só viaja no blocoExecutor) → scanner descarta, gate vazio | drafts finais + registro dos vetos em `docs/skills-ceo/` (`sourcing.md`) |
| **Redesenho motor-nativo (F-N1–F-N6)** — continuidade de sessão, saída estruturada, interrupção mid-run, contabilidade off-book e hook PreToolUse mínimo | ✅ F-N1–F-N5 EXECUTADAS (PRs #16–#19) · **F-N6 EXECUTADA (2026-07-12, branch `feat/hooks-pretooluse-adr0042`, R9-A)** | Grill fechado `docs/handoff-grilling-motor-nativo.md` (D1–D7) + `docs/laudo-motor-nativo.md`. F-N6 (D6 skills read-only + D7 cinto de sessão): `HookMaterializer` atrás do seam `WorkspaceConvention` (`hooksFile`/`hooksScript` novos) — script node ÚNICO materializado nos 2 motores (mecânica empírica confirmada: `{"hooks":{"PreToolUse":[...]}}` idêntico em `.claude/settings.json` e `.codex/hooks.json`), `--dangerously-bypass-hook-trust` no `CodexArgv`; suíte backend **624** (piso 605 + 19), front intocado (280) | **ADR-0042** · `docs/handoff-grilling-motor-nativo.md` §3 F-N6 |
| **Auto-kickback amplo entre personas (R9-B)** — devolução automática do bastão entre back-office, sem re-grelhar o dono | ✅ IMPLEMENTADA (2026-07-12) · pende VENDO do operador | Suíte **617** = piso 605 + 12 (front intocado, 280 — a rota produz só turno invisível): 5º bloco tipado ` ```retrabalho ` (`TurnOutputSchema.retrabalhoVariant`, só no `anyOf` do Executor) · `PersonaHandoffScanner.Retrabalho` (regex + `scanStructured`) · `PersonaBatonRouter` (emissor restrito ao Executor) · `PersonaContinuationPoster.postRetrabalho` + `origemDoBastaoNoTail` (deriva o destino andando pra trás no tail através de prossiga/retrabalho — achado do `/verify`: derivar só do turno imediato perdia a reincidência pós-prossiga) · régua M9 COMPARTILHADA (`kickbacksSeguidosNoTail` conta os dois canais) · prova: `PersonaBatonFlowTest` (+6 casos: retrabalho fenced/estruturado → bastão devolvido ao Planejador · só Executor roteia · teto→prossiga · reincidência DIRETA no turno de prossiga→corte) · **`/verify` live PASS** (instância descartável `:18082` + pg `kickback-pg:15433`: cascata Q→P→E→5×retrabalho→prossiga→reincidência→"Bastão parado" visível ao Questionador, zero run após o corte) | **ADR-0031** emendada (forward-ref 2026-07-12, sem ADR-0043 — mecanismo ficou dentro de "campo→rota") · branch `feat/auto-kickback-personas` · sem migração |
| **Propose-then-commit de molde + drift-badge (R10-A)** — o CEO PROPÕE molde global pelo gate (o dono aprova); drift-badge + re-pin que SUBSTITUI | ✅ IMPLEMENTADA (2026-07-12) · **VENDO PASS (instância descartável)** · **MERGED (PR #22)** | Suíte **640** back (piso 635 + 5) / **286** front (piso 280 + 6): 5ª ação `CREATE_ROLE_TEMPLATE` no enum + rota no `ApprovalService.dispatchAction` (proposedBy=CEO/approvedBy=dono; nada persiste enquanto PENDING) · variante `proposta`(CREATE_ROLE_TEMPLATE) no `anyOf` do Executor (1º campo ARRAY em strict: `skillKeys`) + instrução no `blocoExecutor` · `RoleTemplateService.createTemplate` ganhou o split proposer/approver · **re-pin COMPLETO** `AgentHireService.repin` (re-aplica motor+skillset, `SkillSetService.clearForAgent` + `deleteByCompanyIdAndAgentId`; SUBSTITUI) + endpoint `POST /agents/{id}/repin` (board-only) · drift **derivado na tela** (`agenteEmDrift` + `AgentResponse.pinnedTemplateId` read-join) · badge âmbar clicável no canvas · prova: `ApprovalGateTest`/`TurnOutputSchemaTest`/`ProposalScannerTest`/`HireWithRoleTest` (+5) · `role-templates.test.ts`/`ceo-conversa.test.ts` (+6) · **`/verify` live PASS** (`:18081` + pg `r10a-pg:15432`: propose→PENDING (molde 0)→APPROVE→molde global proposed≠approved · REJECT→sem molde · hire v1→edita v2→drift-badge visível→clica→`/repin 200`→opus→sonnet→badge some) | **ADR-0017 emendada** (fila Approval, não Review) + forward-refs 0018/0039 · CONTEXT atualizado · **sem migração, sem ADR nova** (ADR-0043 livre) · branch `feat/propose-then-commit-molde` |
| **Pedido de skill do EXECUTOR via Approval (F-AT1)** — o agente-como-tool DESCONGELADO (R11-A): o EXECUTOR de uma Issue pede skill fora do set pelo canal de saída; o dono aprova pela fila madura | ✅ IMPLEMENTADA · **MERGED (PR #23, 2026-07-13, main `0f10ddb`)** — status varrido no fecho documental 2026-07-14 (a linha foi escrita antes do merge) | Suíte **651** back (piso 640 + 11) / **288** front (piso 286 + 2): `SkillRequestScanner` (irmão do `ProposalScanner`, coluna 0, ` ```pedido-skill `) colhe sobre o texto CRU reconstruído pelo `RunFinalOutput` (D6 nota-de-lealdade: o Run de Issue não tem `outputSchema`/ADR-0039) · `AdapterRunExecutor` agora coleta o `RunFinalOutput` SEMPRE (não só em turno) · `SkillRequestGate` aplica o teto (cap 5 distintos/run + dedup contra já-efetivo via `SkillSetService.resolveForRun` + evento `SKILL_REQUEST_DISCARDED` visível no StepTrace) · 6ª ação `SKILL_INSTALL_REQUEST` no `ApprovalActionType` (MESMO pipeline propose/decide/dispatchAction das 5 anteriores) — `ApprovalService.dispatchAction` chama `SkillSetService.attachToIssue` (reuso PURO do install-on-attach) · auditoria `SKILL_INSTALL_REQUESTED` novo em `ActivityAction` (propose() branca por actionType) · frontend: `ApprovalActionType`/`VERBO_POR_ACAO`/`acaoLegivel` sincronizados (card "Pedir skill") · prova: `SkillRequestScannerTest` + `SkillRequestOnRunTest` (tracer/dedup/cap+evento/já-efetivo) + `ApprovalGateTest` (+3: audita distinto de CEO_PROPOSED · dispatch anexa pro agente que pediu · skillKey desconhecido→409 PENDING) + `ceo-conversa.test.ts` (+2) | **ADR-0003 emendada** (encerra o caveat "estado-alvo FUTURO" só pra (i)) + **ADR-0006 emendada** (1º consumidor real da Approval-de-ação-de-agente) · **sem ADR nova** (ADR-0043 livre) · **sem migração** (V29 livre) · branch `feat/skill-request-executor` · `docs/handoff-grilling-agente-como-tool.md` |
| **KnowAll body inline SÍNCRONO pro EXECUTOR via MCP-stdio LOCAL (F-AT2)** — a (ii)-local do agente-como-tool (R13-A): o EXECUTOR busca o CORPO de uma skill fora do set NA HORA, mid-run, e termina a task no MESMO run | ✅ IMPLEMENTADA · **VENDO PASS (instância descartável, 2 motores)** · **MERGED (PR #24, 2026-07-13, main `3d02801`)** — status varrido no fecho documental 2026-07-14 | Suíte **672** back (piso 651 + 21) / **288** front (intocado): servidor MCP-stdio do KnowAll (`skills/mcp/`) — script Node único materializado em `.orq/mcp/knowall-server.js` (`KnowAllMcpMaterializer`, irmão do `HookMaterializer`), JSON-RPC 2.0 (molde da sonda R12-B), 2 tools read-only `knowall_search` + `knowall_fetch_body` (`readOnlyHint:true` — requisito do gate do codex) que fazem callback **loopback** pro `KnowAllMcpBridgeController` (`/internal/mcp/knowall/*`, header `X-Orq-Mcp-Token`, token efêmero por-run) · **`KnowAllMcpBridge` é o TETO do #1 de PRIMEIRA CLASSE server-side:** cap **3** corpos distintos/run + dedup por skill (2×=1 leitura, não consome o teto) + recusa-com-motivo NÃO-fatal (`SKILL_BODY_FETCH_REFUSED`) + evento leve `SKILL_BODY_FETCHED` (D5) · `KnowAllService.fetchBody` (reuso puro do catálogo Camada 1) · `ClaudeArgv` ganha `--mcp-config`+`--strict-mcp-config` (adapter grava `.orq/mcp/claude-mcp.json`) · `CodexArgv` ganha `-c mcp_servers.<n>.command/.args` (gramática do laudo) · `AdapterRunExecutor` liga o MCP SÓ no run de EXECUTOR LOCAL (`turno==null && issueId!=null`; personas FORA D6, docker v1.1 D7); `McpCallbackAddress` capta a porta loopback ligada · prova: `KnowAllMcpBridgeTest` (cap/dedup/recusa/token) + `KnowAllFetchBodyServiceTest` + `Claude/CodexArgvTest` (+6 gramática pura) + `KnowAllMcpMaterializerTest` (+`node --check`) + `KnowAllMcpBridgeHttpTest` + `McpServerOnExecutorRunTest` (gate D6) · **`/verify` live PASS 2 motores** (`:18081` + pg efêmero, claude E codex REAIS, LOCAL): fetch_body mid-run→corpo→escreve o código **body-only** `ZX-4231-QRT`→termina no MESMO run; cap: 3 fetched (1/3→3/3) + 2 refused, run segue; dedup: `proto-alpha` 2×=1 slot; **codex 0 cancelamentos** (readOnlyHint prova o gate) | **ADR-0003 emendada** (encerra a metade (ii) do caveat "estado-alvo FUTURO") · **sem ADR nova** (ADR-0043 livre) · **sem migração** (V29 livre) · branch `feat/knowall-mcp-executor` · `docs/handoff-grilling-agente-como-tool.md` + `docs/laudo-sonda-mcp-consume.md` |
| **Lote × Decision: 1 Decisão por lote (R18-A)** — reconciliação da ADR-0035 (lote/PR #12) com a F-C1 (writers de Decision acesos/PR #25): um ato do dono com N alvos virava **N Decisões clonadas** (mesmo racional/área) + **nudge C5 falsa na carta** — bug de produção VIVO em `main`, achado pela lista H (R17-B) | ✅ IMPLEMENTADA · **/verify PASS (instância descartável)** · **MERGED (PR #29, main `05d9b6d`)** | Suíte **723** back (piso 721 + 2) / **294** front (intocado): `ApprovalService.decideNoLote` (suprime a fabricação por-item, flag `fabricaDecisaoPorItem`) + `fabricaDecisaoDoLote` (**1 Decisão por ÁREA**, linhagem dos N na `evidencia` — como a graduação, C5) · `GateDecisionFactory.Anchor.NoLote` (3ª âncora; `approval_id` nulo — sem approval singular, precedente no caso B) · `ApprovalBatchService` liga o caminho · o skip GRADUATE_PRINCIPLE-APPROVE segue ortogonal · **carona (item 2 lista H):** as 7 strings de usuário do `RacionalPolicy` acentuadas (`CONFAB_FORMS`/`CREATIVE_AREA_MARKS` **byte-idênticas** — o matcher normaliza sem acento) + cinto no `RacionalPolicyTest` · prova: `ApprovalBatchDecisionTest` (+2: lote→**1** Decisão com os N na evidência · lote NÃO acende a nudge que 3 atos separados acendem) · **`/verify` live PASS** (`:18081` + pg efêmero: lote de 3 REJECT→**1** Decisão `estrutura-org` + carta sem nudge; 3 individuais→**3** Decisões + nudge *"Candidata a graduação … #2,#3,#4"*) | **ADR-0035 emendada** (Revisão R18-A) + **ADR-0019 emendada** (Anchor.NoLote = 3º writer da fábrica) · **sem ADR nova** (ADR-0043 livre) · **sem migração** (V30 livre) · branch `feat/lote-uma-decisao-adr0035` · `docs/handoff-decisoes-lista-h.md` item 5 |

| **Circuit-breaker de erro repetido (R18-B)** — a 2ª fatia de código do fecho do v1 (lista H item 3): o **3º teto de escalação** que a ADR-0018 prometeu (`:26-27`, *"erro se repetindo (circuit breaker)"*) e que **nunca existiu** — o único freio de loop vivo era o teto de **atos** da ADR-0016/T.4 (econômico: corta tarde, caro, sem diagnóstico) | ✅ IMPLEMENTADA · **`/verify` live PASS (`:18082` + pg efêmero próprio)** · **MERGED (PR #30, main `fd3be27`)** | Suíte **730** back (piso 723 + 7) / **294** front (intocado): breaker **DERIVADO do histórico** `execution/RepeatedFailureBreaker` (`avalia(agentId,issueId)` → os N desfechos FAILED/SUCCEEDED mais recentes do par; todos FAILED = travado; limiar `@Value(...consecutive-failures:3)`; `issueId` nulo nunca trava; zero estado "aberto" persistido — recalculado sempre) · check nos **2 pontos de enfileiramento** (`WakeupService.enqueue` + `HeartbeatRunStore.promoteIfNeeded`, só quando `!lineage.isRoot()`) · **root fura = o destravamento** (mão humana direta) · deferidos barrados descartados como `WakeupStatus.CANCELLED` · aviso M6 `execution/RepeatedFailureNotice` (turno ANSWERED, voz Questionador, zero run — espelho do `ProposalDescarteNotice`; registrado no `donoDeVoltaNaConversa` p/ não quebrar o bastão de personas) · mini-grill (verbatim do dono): limiar **3** · para **só o par agente×issue** · destrava **só wakeup manual direto** · prova: `RepeatedFailureCircuitBreakerTest` (7, integração H2 real via motor) | **ADR-0018 emendada** (cascata FORA/escalação direta ao dono · teto (a) não-promessa · teto (b)→v1.1 candidata #11 · teto (c) + fronteira lease-expirado) · **sem ADR nova** (ADR-0043 livre) · **sem migração** (breaker derivado, V30 livre) · branch `feat/circuit-breaker-adr0018` (git worktree, regra F-N5/ADR-0041) · `docs/handoff-decisoes-lista-h.md` item 3 |

| **Feedback do review → executor (R19-A)** — a 3ª e ÚLTIMA fatia de código do fecho do v1 (lista H item 1): o motivo do REJECT já É Decisão (grau=DECISAO, ancorada na review+issue), mas o executor NUNCA o recebia no re-run — faltava ENTREGA, não endpoint | ✅ IMPLEMENTADA · **suíte verde (729)** · **`/verify` live PASS (`:18081` + pg efêmero, cenário real reject→re-run)** · **MERGED (PR #31, main `fd3be27`)** | Suíte **729** back (piso 723 + 6) / **294** front (intocado): `DecisionRepository.findByCompanyIdAndIssueIdOrderBySeqAsc` (finder novo) · `CompanyMemoryMaterializer` ganha o `issueId` (5-arg + overload 4-arg=null) e materializa a dieta DIRECIONADA — as Decisões ancoradas na issue do run (reject `anchoredToReview` + caso B `anchoredToIssue`), renderizadas com `#N` + racional (**PUSH, não pull:** o agente RECEBE, não escolhe buscar — feedback de reject não é opcional) · `AdapterRunExecutor` passa o `runContext.issueId()` (o gap exato: o issueId que ele já tinha não fluía pra dieta) · **fronteira C10.b do `MemoryQuery` INTOCADA** — a Decisão do reject (grau=DECISAO, tipo OPERACIONAL/TECNICA) segue board-only no `memory_fetch`; a entrega é caminho NOVO e DIRECIONADO, não alargamento do trilho JIT (convivem por construção) · ponta do CEO inalterada (issueId nulo → sem seção) · prova: `CompanyMemoryMaterializationTest` (+6: tracer feedback · parity issue-sem-decisão byte-idêntica · N decisões por seq + isolamento entre issues · CEO sem seção · convivência R19-A×fetch-selado · run REAL `dispatchOnce` com feedback literal) | **ADR-0011 emendada** (Emenda R19-A: a premissa "editar Issue antes do REOPEN" morta → entrega materializada) + comentário `IssueService.java:221` atualizado · **sem ADR nova** (ADR-0043 livre) · **sem migração** (V30 livre) · branch `feat/feedback-review-executor-adr0011` · `docs/handoff-decisoes-lista-h.md` item 1 |

| **Fix do loop kickback↔continuação (PR #33)** — achado da PROVA FINAL (R21-A): as 2 travas de loop contavam só o próprio tipo consecutivo e a ALTERNÂNCIA kickback↔continuação zerava as duas — a conversa girava sem o dono | ✅ MERGED (PR #33, main `83c5fe9`, 2026-07-16) | `PersonaContinuationPoster.parkedForHuman`/`awaitingOwnerGrilling` (park-for-human **de CONVERSA**: kickback no tail sem fala real do dono depois → a retomada por contexto NÃO re-posta; uma fala real destrava) · consultado pelo `ContinuationTurnPoster` (seam `ConversationContinuationGuard`) · piso **738** back / **294** front | **ADR-0030 emendada** (emenda 2026-07-16) · branch `fix/persona-kickback-park-for-human` · `docs/handoffs/HANDOFF-prova-final-v1.md` |

> **A lista H (R17-B, 2026-07-14) destravou 3 fatias de v1 novas** ("o v1 cresceu") — a F-C3 era a última fatia PLANEJADA, não a última do v1. Ordem: **(1) lote×Decision [R18-A ✅ MERGED PR #29] · (2) circuit-breaker de erro repetido [R18-B ✅ MERGED PR #30] · (3) feedback do review→executor [R19-A ✅ MERGED PR #31]**. Depois delas landaram ainda o **#32** (docs do fecho residual, R20-A) e o **#33** (fix do loop de persona, achado da prova final — linha acima). Racional e recorte em `docs/handoff-decisoes-lista-h.md`.

> **▸ Estado final do v1 — ATUALIZADO 2026-07-16 (R22-A).** **v1 DECLARADO FECHADO PELO DONO
> (2026-07-16)** — decisão registrada em `docs/handoffs/HANDOFF-prova-final-v1.md` §1. **PRs #1–#33
> todos MERGED** (`main 83c5fe9`; piso canônico **738** back / **294** front). A versão anterior
> deste bloco (2026-07-15, "PRs #1–#31 · ZERO fatia de código restante") ficou **duplamente
> falsa**: o **#33** landou CÓDIGO depois do carimbo (fix do loop de persona, achado da prova) e a
> fatia do **bug #1 do roadmap de ajustes reabre código** (R22-A, em voo). Desfechos das 5 decisões
> da lista H (R17-B): **(1)** feedback→executor **feito** (R19-A/#31) · **(3c)** circuit-breaker
> **feito** (R18-B/#30) · **(5)** lote×Decision **feito** (R18-A/#29) · **(4)** **pino 2.1.201
> MANTIDO** por decisão do dono — gatilho registrado na ADR-0014 (bump só em fatia de infra
> própria, com um 2º motivo, racional no PR; gotcha <2.1.205 já cintada — ADR-0039 ~:111) ·
> **(2)** **veto de redação: os 6 textos MANTIDOS** como shippados (decisão do dono), a única
> correção sendo a **acentuação das 7 strings de usuário do `RacionalPolicy`** — aplicada de
> carona na R18-A/#29. A **PROVA FINAL rodou (R21-A, 2026-07-16)**: pernas (a)–(f) PASS com motor
> real; achou o **bug #1** (DONE fantasma — o run de issue não tinha compositor) + 4 achados; as
> pernas (g)/(h)/(i) fecham no /verify da fatia do conserto. **Fase atual = descoberta de bugs /
> roadmap de ajustes (ver a seção "Roadmap de ajustes" abaixo).**

## Frente C — Método de memória do CEO (2º cérebro) — **COMPLETA (F-C1 → F-C2 → F-C3)**

As 3 fatias sequenciais LANDARAM (o paralelismo foi procurado e não existe — cada fatia fabrica a razão de
existir da próxima: **endereço `#N` → permite → linhagem + cap → torna necessário → JIT**, C16). O *átomo*,
a *assinatura* (anti-inchaço) e o *índice + navegação JIT* estão no código.

| Sub-parte | Verdict · Estágio | Evidência | Ponteiro |
|-----------|-------------------|-----------|----------|
| Decisão-como-átomo + racional por grill + carta (projeção determinística zero-LLM) | ✅ IMPLEMENTADA · VENDO | `memory/CompanyMemoryConsolidator.java:32-55` (carta = projeção de Decisões-de-direção vigentes) | ADR-0019/0020 |
| **F-C1 "o átomo completo"** (R15-A, 2026-07-14): endereço `#N` + `grau` (V29) · fábrica ÚNICA `GateDecisionFactory` (C15) · **writers D5/D11 ACESOS** (review fabrica Decisão com área do ARTEFATO; `supersedesSeq` nas 2 portas → carta se auto-cura) · triviabilidade C14 (silêncio-após-perguntado; probe da política no FE) · `render()` emite `#N` | ✅ IMPLEMENTADA · VENDO na descartável | `memory/{GateDecisionFactory, DecisionGrau}` · `V29__decision_seq_grau.sql` · `IssueService.review` (review_id acordou) · suíte 691 back / 290 front | ADR-0019+0020 **emendadas** (nota D5/D11 + triviabilidade FECHADAS) · `handoff-grilling-frente-c-memoria.md` §RESULTADO |
| **F-C2 "a assinatura: graduação + cap"** (R16-A, 2026-07-14): `MemoryKind.PLAYBOOK` (tier durável ÚNICO — residual 2 da ADR-0019 FECHADO) · **`GRADUATE_PRINCIPLE` = 7ª ação do gate** (CEO propõe a FRASE, dono sela com frase EDITÁVEL no card — writer-of-record; nasce `grau=PRINCIPIO` com `approval_id`; linhagem C5 em `evidencia`) · `GraduationDetector` determinístico (N=3/área desde o último princípio, carona no commit do gate) · **caps 12/12 por projeção** (hard, provados por teste) · 2ª projeção gêmea (`grau=PRINCIPIO`→PLAYBOOK, mesma tx) · nudges determinísticas (candidata + estouro citando os `#N`) | ✅ IMPLEMENTADA · VENDO na descartável (`:18081` + `fc2-pg`) · **MERGED (PR #26, main `124fcfa`)** | `memory/{GraduationDetector, CompanyMemoryConsolidator(caps), MemoryKind, DecisionGrau}` · `ApprovalService.dispatchAction(GRADUATE_PRINCIPLE)` · FE card com frase editável · suíte 702 back / 294 front · **zero migração** (grau/kind text sem check) | ADR-0019 (residual 2 FECHADO + cap/projeções) + ADR-0020 (**D12 concretizado**: ritual = 7ª ação; judgment call do dono: emenda venceu ADR-0043) **emendadas** · branch `feat/frente-c-graduacao-cap` |
| **F-C3 "índice + navegação JIT"** (R17-A, 2026-07-14 — **ÚLTIMA fatia de código do v1**): **split do sempre-on (C9)** — CEO = carta+princípios · **EXECUTOR = princípios SÓ** (`MemoryAudience`; até aqui o executor não recebia memória nenhuma → D12 era letra morta + cegueira-de-retrieval) · **`memory_search` (livre, casa por TERMOS) + `memory_fetch` (`#N` → racional/evidência/linhagem/cluster)** no **MESMO bridge MCP da F-AT2** (zero canal novo, C10) · **teto D6 da frente: cap=3 corpos distintos/run + dedup + recusa-com-motivo não-fatal** (espelha o `CAP_BODIES_POR_RUN`, teto PRÓPRIO) · **fronteira C10.b:** só o durável selado — o log cru `OPERACIONAL`/`TECNICA` segue **board-only, inalcançável nem com o `#N` em mãos** · trilho nas **2 pontas** (`McpToolProfile`: executor vê KnowAll+memória; **CEO só memória** — F-AT2/D6 "personas FORA" preservado) | ✅ IMPLEMENTADA · **/verify PASS nos 2 motores REAIS** (`:18081` + `fc3-pg`): executor busca mid-run → usa → **termina no MESMO run** (claude E codex) · 3 servidos + 4º/5º **recusados com motivo** (run segue) · `#N` repetido = 1 slot · **turno do CEO cita a evidência LITERAL** do princípio (string que só existe no corpo) · `#N` do log cru **recusado** e o racional sigiloso não vazou pro workspace · split: executor com `AGENTS.md`/`CLAUDE.md` = **playbook sem carta**; CEO = carta+playbook | `memory/{MemoryQuery ✚, MemoryAudience ✚, CompanyMemoryMaterializer}` · `skills/mcp/{KnowAllMcpBridge(+memory tools, cap), KnowAllMcpBridgeController(/internal/mcp/memory/*), KnowAllMcpMaterializer(tools por perfil), McpToolProfile ✚, McpRunScope}` · `execution/AdapterRunExecutor` (2 pontas + memoryFile em todo run) · suíte **721 back / 294 front** · **zero migração** | ADR-0003 **emendada** (o bridge ganha as tools de memória) + ADR-0019 **emendada** (split C9 + memoryFile = território do produto, judgment call do dono) · branch `feat/frente-c-indice-jit` · `handoff-grilling-frente-c-memoria.md` §F-C3 |

## Roadmap de ajustes — fase de DESCOBERTA DE BUGS (aberta 2026-07-16, com o v1 FECHADO pelo dono)

> **Método (decisão do dono, verbatim no `docs/handoffs/HANDOFF-prova-final-v1.md` §10):** as ADRs
> viram a ESPECIFICAÇÃO; cada funcionalidade descrita nelas é testada **ao vivo** (motor real) e
> classificada **funciona / não funciona**; o resultado alimenta esta seção. Plano da caça =
> **R22-B** (`docs/plano-descoberta-bugs.md`, untracked de contrato). Lições de como caçar
> (§10 do handoff): a ADR afirma/o dado decide · o bug mora no CRUZAMENTO entre ADRs · testar
> pelo produtor REAL, nunca por fixture · não julgar sistema em voo.

| # | Item | Origem | Estágio |
|---|------|--------|---------|
| 1 | **A organização contratada não consegue trabalhar — e declara DONE** (run de issue sem compositor de prompt + no-op verde + settle completa; medido: run `e67ff7dd`, 58ms, zero motor, #1→DONE) | prova final §2 (R21-A) | ✅ **EM PR — R22-A** (`fix/issue-compositor-adr0037`): compositor da issue (#N+título+descrição+ESTRATÉGIA) · trava "run no-op nunca fecha issue" · `runtime_command` = só script `process` · **V30** (`conversation.bastao_path` anotado do bloco `handoff`, last-wins) · ADRs 0037/0011/0004 emendadas — 4 decisões GRELHADAS pelo dono 2026-07-16 |
| 2 | **Vão ADR-0037 §7(8) × ADR-0030** — "anti-loop = freio humano por construção" é FALSO na prática: a continuação de contexto RETOMA o para-e-avisa sem o dono (re-triagem + aviso duplicado; run real queimado). Mesma assinatura do #33 — o `parkedForHuman` só estaciona em kickback, e para-e-avisa não é kickback | prova final §6.1 (o mais importante) | 💡 A GRELHAR |
| 3 | **`HIRE_AGENT` não carrega time** — o gate cria o time, contrata o executor desenhado para ele, e o executor nasce FORA (`team_id` NULL; `assignAgent` só no arquiteto do CREATE_TEAM e na porta manual) | prova final §6.2 | 💡 A GRELHAR |
| 4 | **`FILTRO_TIPOS` stale no front** (`frontend/src/lib/approvals.ts:12-22`: 3 tipos; o gate tem 7 ações) — a fila lista card em que não consegue agir; não-bloqueador (o `ApprovalThreadCard` decide individualmente) | prova final §6.3 | 💡 A GRELHAR |
| 5 | **"você" em turno que o SISTEMA escreveu** (`issue-conversa-section.tsx:44` chumba `<span>você</span>` em todo `ownerMessage` — o dono lê "você: Absorção da #1 … [gatilho <uuid>]" sobre algo que nunca escreveu) | prova final §6.4 | 💡 A GRELHAR |
| 6 | Cosmético: `..` duplicado no texto do para-e-avisa ("verificador de fontes.. Nada foi atribuído") | prova final §6.5 | 💡 A GRELHAR |
| 7 | **Aprovação em LOTE sem feedback na UI** (o dono aprovou 27 e a tela "não fez nada" — o ato commitou; sem sinal de retorno, um dono real re-clicaria) — herdada do P1 da prova | ledger R21-A · candidata herdada | 💡 A GRELHAR |
| 8 | **`launch.json` sem config `frontend-prova`** (a config `frontend` tracked força `BACKEND_ORIGIN=:18081` morto — `preview_start {name:"frontend"}` leva o dono a tela cega) | ledger R21-A §3 | 💡 A GRELHAR |
| 9 | Fraseado do `oQueMudou` da review (mistura os 2 espaços de `#N`) — candidata v1.1 #14 absorvida por esta fase | candidatas v1.1 #14 | 💡 A GRELHAR |
| 10 | Rename mecânico `KnowAllMcpBridge` → `McpBridge` (pós-F-C3 o bridge serve 2 famílias; rename puro) — candidata v1.1 #15 absorvida por esta fase | candidatas v1.1 #15 | 💡 A GRELHAR |

> **Massa de teste pós-merge do item 1:** a reabertura/re-execução da **#1 da `prova-v1`** (DONE
> incorreta no VIVO) é da 1ª bateria da descoberta, com o dono — fora da fatia R22-A (decisão do
> controle: o vivo é intocável na fatia).

## Frente D — Backlog / ADIADOS (saem de grills futuros)

| Item | Origem | Estágio |
|------|--------|---------|
| ~~Contabilidade de produto sobre a pesquisa off-book do `delegation`~~ | Fatia 2 §G | ✅ **F-N5/PR #18** (ADR-0041 — 2ª `CostEmission` rotulada `cost_event.off_book`/V28, excluída da régua de handoff; varrida no fecho documental 2026-07-14) |
| ~~Auto-kickback amplo entre personas (além do `needs-grilling` cirúrgico)~~ | Fatia 2 §G | ✅ IMPLEMENTADA (R9-B, ver Frente B) |
| ~~Interrupção mid-run (streaming ao vivo + kill)~~ | Fatia 1 §G | ✅ **F-N4/PR #19** (ADR-0040 — kill de process group + `INTERRUPTED` + SSE do StepTrace; varrida no fecho documental 2026-07-14) |
| ~~Levar o loop de handoff pros EXECUTORES (não só o CEO)~~ | Fatia 1 §G | ⚫ **MORTA — SUPERSEDIDA** pelo D3 do grill motor-nativo: o executor continua por **resume nativo por Issue** (F-N2/PR #16, ADR-0038 §F-N2); o bilhete ficou restrito a troca de AGENTE (varrida no fecho documental 2026-07-14) |
| ~~Agente-como-tool (attach por agente + KnowAll body inline)~~ | ADR-0003 rev · 7c | ✅ **F-AT1/PR #23 + F-AT2/PR #24** (grill R11-A descongelou e fatiou; emendas ADR-0003/0006; a metade (ii)-docker ficou candidata v1.1 — varrida no fecho documental 2026-07-14) |
| ~~Propose-then-commit de molde (CEO cria molde; drift-badge)~~ | Teams T.1-FE residual 3 | ✅ IMPLEMENTADA (R10-A, ver Frente B) |
| _(próximo grill — a definir)_ | — | 💡 IDEIA |

### Candidatas v1.1 acumuladas (registro do fecho documental parcial, 2026-07-14)

> Regra anti-círculo do congelamento do v1: chegou depois do congelamento e não quebra a prova
> final → **v1.1, SALVO palavra do dono**. Este é o REGISTRO consolidado (as fontes têm o
> racional); antecipar qualquer uma é decisão H.

| # | Candidata | Fonte registrada |
|---|-----------|------------------|
| 1 | **(ii)-docker do KnowAll body inline + fatia de isolamento do home-volume (D7)** — sequenciadas JUNTAS (o canal MCP-no-container é a mesma obra) | grill agente-como-tool D3/§4 · grill motor-nativo D7 + nota-GATE na ADR-0016 (bloqueante p/ tenant externo) |
| 2 | **(iii) agente-durável-como-tool** — épico próprio com grill próprio (contabilidade reusa F-N5; `multi_agent` do codex stable, provado na R6-B) | grill agente-como-tool D1/§4 |
| 3 | **persona-self-request/fetch** — personas pedirem skill/corpo como o executor; só com demanda real | grill agente-como-tool D6/§4 |
| 4 | **`disallowed-tools` do claude** (default vazio) — teto de PRODUTO no eixo-subagente, sobre o cap nativo de profundidade | grill agente-como-tool §4 · seam já fiado na ADR-0038 D6 |
| 5 | **CEO-propõe-EDIÇÃO de molde existente** (D8 da R10-A; puxa visibilidade dos moldes no contexto do CEO) | ADR-0017 forward-ref R10-A · ledger rodada 10 |
| 6 | **Steering/processo-vivo** (correção mid-turn sem kill) — gate explícito: codex `app-server` sair de experimental | grill motor-nativo D4 · ADR-0040 §Alternativas |
| 7 | **Enforcement amplo dos hooks** (Glob/Grep fora do cinto D7; hoje o deny cobre Read/Write/Edit/apply_patch) | follow-up honesto da ADR-0042 (F-N6) |
| 8 | **ID sequencial de CONVERSA** (fluxo verbatim do dono no §0 do grill motor-nativo) | grill motor-nativo §5 |
| 9 | **Promoção por "N usos" na graduação de princípios** (exige contar cada fetch JIT — máquina nova) | grill Frente C, C5 (`handoff-grilling-frente-c-memoria.md`) |
| 10 | **`memory_fetch` sobre o log CRU** (vazaria racional `OPERACIONAL`/`TECNICA` board-only pra contexto de agente — grill próprio) | grill Frente C, C10.b |
| 11 | **Teto (b) da ADR-0018 — contador de riscos-do-plano** (o CEO te chama quando o plano acumula riscos): falta o artefato para o agente DECLARAR os riscos que viu — o `TurnOutputSchema` só conhece `handoff`/`absorcao`/`proposta[…]` → bloco novo por-persona + parser + limiar + escalação. Os outros dois tetos SAÍRAM daqui: (a) gasto/token a própria ADR dispensa no v1; (c) **circuit-breaker de erro repetido entrou no v1** (R18-B). ⚠️ Corrige a frase refutada: NÃO é "só o teto de gasto existe — `BudgetPolicy.orchestrationLimitCents`" — esse campo é o teto de **atos** da ADR-0016/T.4, outro mecanismo | lista H (R17-B) item 3 · ADR-0018 emenda R18-B |
| 12 | **Paridade Docker** (transporte de arquivo + visibilidade de volume: o schema-arquivo da F-N3 e o side-channel da F-N5 caem em fail-soft/loud sob DOCKER) | fechamento da rodada 8 do ledger · ADR-0041 §Consequências |
| 13 | **Grill do ARQUITETO** (invariante de nascimento na porta manual · `setArquiteto(null)` · multi-arquiteto · divisão em 2 personas · clone por ociosidade >30min) — decisão explicitamente do DONO (toca o norte "arquiteto como mente de cada grupo") | semente verbatim §G.1 de `handoff-grilling-absorcao-issues.md` |
| 14 | ~~**Fraseado do `oQueMudou` da review**~~ → **movida p/ o Roadmap de ajustes (item 9)** em 2026-07-16 | `docs/handoffs/HANDOFF-frente-c.md` §Pendências 3 |
| 15 | ~~**Rename mecânico `KnowAllMcpBridge` → `McpBridge`**~~ → **movida p/ o Roadmap de ajustes (item 10)** em 2026-07-16 | `docs/handoffs/HANDOFF-frente-c.md` §Pendências 4 |

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
| `grilling-memoria-ceo-segundo-cerebro` | ✅ **IMPLEMENTADA** | `CompanyMemoryConsolidator.java:32-55`; **Frente C COMPLETA** (átomo + graduação/cap + índice JIT — PRs #25/#26/#28) |
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
| `grilling-loop-handoff-contexto-fechado` | ✅ **IMPLEMENTADA** | Fatia 1 (Frente B) — **PR #1 / ADR-0030** |
| `grilling-3-ceos-personas-fechado` | ✅ **IMPLEMENTADA** | Fatia 2 (Frente B) — **PR #8 / ADR-0031** |
| `proposta-3-ceos-personas` | ⚫ **SUPERSEDIDA** | doc de origem; grill fechado divergiu (§ Div baixa) |
| `grilling-7c` / `-fechado` | ✅ VENDO | `SkillSetService.java:88,96-97` (install-on-attach, Rota A) |

---

## § Divergências (doc × código — sobreviventes da refutação)

> 48 candidatas → **25 confirmadas** (refutação adversarial matou 23 falsos-positivos de busca incompleta).
> **1 alta · 6 média · 19 baixa.** As baixa são, em bloco, envelhecimento de handoff consumido ou residual
> consciente — **não** defeito. O sinal acionável está na ALTA e nas MÉDIAS. (A 6ª média = defeito de
> **runtime** achado no VENDO live do loop de handoff em 2026-07-08 — não da varredura doc×código de 07-07 —
> já **CORRIGIDA** no mesmo dia, ver abaixo.)

> **▸ Passada suplementar da auditoria (fecho residual R20-A, 2026-07-15) — até `main fd3be27`.** Checklist
> dirigido (racional anti-círculo: todo PR desde o #25 nasceu de kickoff verificado adversarialmente com
> file:line — re-auditar tudo re-auditaria o que o ledger já registra): **(i)** as divergências desta seção
> estão **100% carimbadas** — as 2 ALTAS FECHADAS (2026-07-10), as 5 MÉDIAS reconciliadas e **D5/D11 agora
> RESOLVIDAS** (F-C1/PR #25); **zero linha aberta**. **(ii)** índice de ADRs **0001–0042 coerente** (42
> arquivos, sem buraco); as emendadas nas rodadas 17–19 têm emenda **presente e datada** — 0003 (F-AT1/F-AT2) ·
> 0011 (Emenda R19-A) · 0018 (Emenda R18-B) · 0019 (Revisões F-C1/F-C2/F-C3 + `Anchor.NoLote`) · 0035 (Revisão
> R18-A). **(iii)** 3 afirmações código-apontadas do `handoff-auditoria-doc-codigo-2026-07-11.md` re-conferidas
> na `fd3be27` e **seguem verdadeiras**: ADR-0006 (drena até o checkpoint — cancela só QUEUED, o RUNNING liquida
> no settle; `BudgetService.java:26,140-150`) · ADR-0026 (start em workspace coletado → **200**
> `StartResponse{started=false, error}`, sem 409; `PreviewServerController.java:49-54`) · ADR-0013 (`notFound()`
> de layout sobe ao not-found da **RAIZ**; `[companySlug]/not-found.tsx`). **Resultado: ZERO divergência nova até
> `fd3be27`.**

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

### 🟠 MÉDIA (6 · 1 CORRIGIDA 2026-07-08 · **5 reconciliadas em doc 2026-07-10** — **D5/D11 RESOLVIDAS pela F-C1/PR #25, 2026-07-14**)

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
  🟠 **Reconciliada em doc (2026-07-10):** nota na ADR-0019 registrou o seam morto; decisão **H** pendia
  (puxar fatia p/ ligar review→Decision × aceitar como está).
  ✅ **RESOLVIDA (F-C1/PR #25, 2026-07-14):** os writers foram **ACESOS** — a review pós-hoc **FABRICA Decisão**
  (área do artefato) e o `review_id`/`anchoredToReview` acordaram; ADR-0019 emendada (Revisão F-C1). Ver §Vetos.
- **ADR-0019 · D11: `supersedes` sem caminho de escrita.** O consolidador **lê** e filtra `superseded`
  (`CompanyMemoryConsolidator.java:36-42`), mas **nenhum writer** seta `supersedesId` (`Decision.java:62-63,150` só
  getter). Existe a fundação (coluna + read-filter), não a revisão-como-nova-Decisão.
  🟠 **Reconciliada em doc (2026-07-10):** nota na ADR-0019 registrou o seam morto (confirmado também no
  `DecisionController`: GET-only, o campo sai na resposta mas nunca entra); decisão **H** pendia
  (puxar fatia da linhagem × aceitar como está).
  ✅ **RESOLVIDA (F-C1/PR #25, 2026-07-14):** o `supersedesSeq` passou a ser **escrito pelas 2 portas do gate**
  (a carta se auto-cura); a revisão-como-nova-Decisão da linhagem existe. ADR-0019 emendada (Revisão F-C1). Ver §Vetos.
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
- **Fatia 1 / Fatia 2 = ✅ IMPLEMENTADAS** (PR #1/ADR-0030 · PR #8/ADR-0031). *(A nota "PLANEJADO/ausência-limpa" era o snapshot de 2026-07-07 — ambas landaram; memória e código conferem no estado atual.)*
- **Frente C = ✅ COMPLETA** (F-C1/PR #25 · F-C2/PR #26 · F-C3/PR #28). *(A leitura "PARCIAL / EM EXECUÇÃO" da auditoria era snapshot; as 3 fatias sequenciais landaram — átomo + graduação/cap + índice JIT.)*

---

## § Vetos pendentes (decisões que aguardam o Matheus — **H**)

> **▸ Status (fecho residual, 2026-07-15): ZERO veto pendente.** Todas as decisões desta seção estão
> **RESOLVIDAS** (abaixo, riscadas) — incluindo a última H de auditoria (feedback-do-revisor→Issue/ADR-0011,
> fechada pela R17-B → **R19-A/PR #31**). As 5 decisões da **lista H** (as que só o dono tomava) foram colhidas
> na R17-B e landaram (PRs #29/#30/#31 + a carona de texto no #29). **Nenhuma decisão aguarda o Matheus para o
> fecho de CÓDIGO do v1** — resta a PROVA FINAL.

**Fatia 2 (3 personas): ✅ TODOS RESOLVIDOS** (varrido no fecho documental 2026-07-14):
- ~~Formato dos blocos tipados~~ — **confirmado pelo dono (2026-07-10, PR #8)**: JSON em fence
  coluna 0, pinado em `PersonaHandoffScannerTest`.
- ~~Blocos do `writing-doc-plans`~~ — **vetado na R3-A (PR #11)**: 11 vetos H decididos um a um;
  prova viva na estratégia real (Premissas + teto 27<30, VENDO §D).
- ~~`N` da trava anti-loop de kickback~~ — **N=5 confirmado (PR #8)**; régua M9 depois
  compartilhada com o retrabalho (R9-B).
- ~~Governança off-book do `delegation`~~ — **aceito no v1 (PR #8)**; a CONTABILIDADE do gasto
  veio na F-N5/PR #18 (ADR-0041).
- ~~Sourcing das skills externas~~ — **vetado na R3-A (PR #11)**: balcão único
  `docs/skills-ceo/sourcing.md`; 5 skills registradas no DB, corpo==draft por md5.
- ~~ADR-0031 nova~~ — **confirmada (PR #8)**.

**Fatia 1 (loop de handoff): ✅ TODOS RESOLVIDOS** (varrido no fecho documental 2026-07-14 —
o merge do **PR #1** com OK do dono selou os 4: a ADR-0030 aceita carrega o template do
bilhete, o N=5, o dial `orquestrador.handoff.context-threshold-percent` default 55 e a
própria ADR como nova):
- ~~Template do bilhete destilado~~ · ~~`N=5` da trava anti-loop~~ · ~~Nome do dial + default
  55~~ · ~~ADR-0030 nova~~.

**Novo (da auditoria 2026-07-07):**
- ~~**Divergência ALTA (ADR-0005 lease):** implementar renovação de lease **ou** reconciliar o ADR? (investigar o efeito real primeiro).~~ **RESPONDIDA (2026-07-10): implementar renovação** — grill fechado, plano em `docs/handoff-grilling-renovacao-lease.md` → **EXECUTADA (2026-07-10):** `LeaseRenewer` + `renewLease` CAS + `RUN_LEASE_LOST` + `LeaseRenewalTest` (4 provas); ADR-0005 emendada (trecho Recovery); pende só o VENDO live do pulso (com operador).
- **Médias doc×código (jOOQ · SKIP LOCKED · `producedArtifacts` · D5 review→Decision · D11 supersedes):**
  ~~atualizar os ADRs para refletir o código, ou puxar fatias?~~ **Reconciliação em doc EXECUTADA (2026-07-10,
  faixa R1-E):** notas datadas nas ADR-0001/0004/0005 (só claim) /0019 + ARQUITETURA.md corrigida. ~~**Segue H:**
  destino dos seams mortos da 0019 (`review_id` / `supersedesId`) — puxar fatia × aceitar como está.~~
  **RESOLVIDO POR PR #25 (F-C1, MERGED 2026-07-14; varrido no fecho documental):** os dois seams foram
  ACESOS — a review pós-hoc FABRICA Decisão (writer D5, área do artefato) e o `supersedesSeq` entra pelas
  2 portas do gate (writer D11, carta se auto-cura); emenda correspondente na ADR-0019 fechou a nota.
- **26 fichas MÉDIA da auditoria doc×código de 2026-07-11:** **APLICADAS no fecho documental parcial
  (2026-07-14, faixa R16-B)** — notas de reconciliação datadas em ADR-0004 (×4, SSE superada por PR #19) ·
  0006 · 0009 (×2) · 0011 · 0013 · 0017 (×2 superadas por PR #22) · 0018 · 0024 · 0026 + handoffs de grill
  (approval · loop-handoff · 7c · motores · skills-knowall · plano-codex) + `ActorContext` nominal na
  ARQUITETURA.md + ADR-0041 escrita (buraco de numeração fechado). EXCLUÍDAS por fronteira (à época): ADR-0020/D12
  (F-C2, então em voo — **concretizada pela F-C2/PR #26**: o ritual da graduação virou a 7ª ação do gate) ·
  fichas D5/D8 de memória (consumidas pela Frente C — PR #25/emendas). ~~O único MÉDIA que segue decisão H é o
  feedback-do-revisor→Issue (ADR-0011) — na lista H.~~ ✅ **RESOLVIDO (2026-07-15, fecho residual):** essa decisão
  H foi tomada na **R17-B** (item 1 — terceira via **PUSH**, não endpoint de edição de Issue) e a fatia landou
  como **R19-A/PR #31**; ADR-0011 emendada. **Zero MÉDIA da auditoria segue decisão H.**

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
