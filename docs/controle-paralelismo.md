# Controle de paralelismo — ledger da sessão de controle

> **O que é:** registro durável da orquestração de faixas paralelas da Frente B+ (v1 do CEO).
> A sessão de controle pode morrer; este ledger não. Toda rodada nova começa RELENDO este
> arquivo + `git status` + `git log` + última migration, e recalculando a matriz de colisão.
> **Criado:** 2026-07-10 (rodada 1).

## Contrato do handoff de volta (o que cada execução devolve à sessão de controle)

- Branch e se já virou PR / merge
- Lista exata dos arquivos tocados (`git diff --name-only` + untracked)
- Qual `Vxx` foi consumido, se algum
- Contagem de testes (`cd backend && mvn -o test`) e se subiu o piso
- O que ficou por fazer e por quê
- Decisões novas que apareceram no caminho (com racional)

## Reservas (numeração é por decisão, não por ordem de merge)

| Recurso | Reserva | Status |
|---------|---------|--------|
| Migration **V23** | G5 (ID sequencial de Issue) | **CONSUMIDA** (`V23__issue_seq.sql`, PR #5 merged 2026-07-10) |
| Migrations **V24 + V25** | Fatia 2 (coluna `persona` + 3 moldes de CEO) | **CONSUMIDAS** (`V24__agent_persona.sql` + `V25__ceo_persona_moldes.sql`, **PR #8 MERGED 2026-07-10** — aplicadas no :8080 vivo) |
| **ADR-0031** | Fatia 2 (3 personas) | **CONSUMIDA** (`0031-ceo-tres-personas.md` + forward-refs 0017/0018, **PR #8 MERGED**) |
| **ADR-0032** | G3 (AGENTS.md/codex) | **CONSUMIDA** (PR #4 merged) |
| **ADR-0033** | G5 (ID sequencial de Issue) | **CONSUMIDA** (`0033-identidade-sequencial-issue.md`, PR #5 merged) |
| **ADR-0034** | caso B (rodada 3) | **CONSUMIDA** (`0034-correcao-task-entregue-caso-b.md`, **PR #10 MERGED 2026-07-10**) |
| **ADR-0035** | aprovação em lote (rodada 3) | **CONSUMIDA** (`0035-aprovacao-em-lote-kickback-recusa.md` + menção cruzada D13 na 0031, **PR #12 MERGED 2026-07-11**) |
| Migration **V26** | caso B (link task corretiva → `#N`) | **CONSUMIDA** (`V26__issue_correcao.sql`, PR #10 — **aplicada no :8080 vivo**, topo do flyway history) |
| Migration **V27** | absorção (issue = conversa direcionada) | **CONSUMIDA** (`V27__issue_conversa.sql`, **PR #14 MERGED 2026-07-11** — **aplicação no :8080 pende o restart do operador**) |
| **ADR-0036** | caso C direto (rodada 4) | **CONSUMIDA** (`0036-caso-c-contratacao-direta-anti-silencio.md` + forward-refs 0018/0031, **PR #13 MERGED 2026-07-11**) |
| **ADR-0037** | absorção de issues (rodada 4) | **CONSUMIDA** (`0037-absorcao-issues-board-pelo-ceo.md` + forward-refs 0017/0018/0031/0033/0034 §I, **PR #14 MERGED 2026-07-11**) |
| **ADR-0038** | F-N1+F-N2 (continuidade nativa por conversa + resume por Issue, rodada 6) | **CONSUMIDA** (`0038-continuidade-nativa-de-sessao.md` + emendas 0002/0018/0030, **PR #16 MERGED 2026-07-11**, main `a62c4e6`) |
| Migration **V28** | ~~F-N2~~ | **LIBERADA sem consumo** — `agent_session.conversation_id` (NOT NULL, FK) bastou via `issue.conversation_id`/V27; "resume universal de executor" = §G.4 futura |
| **ADR-0039** | F-N3 (saída estruturada nativa, D2 — rodada 7) | **CONSUMIDA** (`0039-saida-estruturada-nativa.md` + forward-refs 0018/0031/0036/0038, **PR #17 MERGED 2026-07-12** — push+PR+merge executados pela sessão de controle com OK explícito do dono) |
| **ADR-0040** | F-N4 (interrupção mid-run + SSE — rodada 8) | **CONSUMIDA** (`0040-interrupcao-mid-run-sse.md`, **PR #19 MERGED 2026-07-12**) |
| **ADR-0041** | F-N5 (contabilidade off-book — rodada 8) | **CONSUMIDA como NÚMERO** (PR #18 MERGED 2026-07-12) — ⚠️ **o arquivo `docs/adr/0041-*.md` NUNCA foi commitado** (achado da validação da rodada 9; `git log --all` vazio para o path; o PR #18 levou código+V28+laudo-sondas, sem ADR): escrever a ADR-0041 no FECHO DOCUMENTAL (material no handoff durável da F-N5); NÃO renumerar |
| Migration **V28** | F-N5 (rótulo off-book) | **CONSUMIDA** (`V28__cost_event_off_book.sql`, PR #18 — **aplicação no :8080 pende o restart do operador**, junto com a V27) |
| Migration **V29** | ~~F-N4~~ | **LIBERADA sem consumo** (INTERRUPTED é text, sem migração) |
| **ADR-0042** | R9-A (F-N6 — hook PreToolUse mínimo) | **CONSUMIDA** (`0042-hook-pretooluse-materializacao.md`, **PR #20 MERGED 2026-07-12** — inclui a mecânica empírica descoberta na faixa) |
| **ADR-0043** | ~~R9-B (auto-kickback amplo)~~ | **LIBERADA sem consumo** — o mecanismo ficou dentro de "campo validado → rota" → ADR-0031 emendada (forward-ref 2026-07-12); precedente carimbado: fatia futura no mesmo canal só abre ADR nova se a decisão for além de "campo → rota" |
| Migration **V29** | R15-A / F-C1 (`decision.seq` clone do V23 + `decision.grau` not null default 'DECISAO' — UMA migração, DUAS colunas; a ÚNICA da Frente C inteira) | **CONSUMIDA** (`V29__decision_seq_grau.sql`, **PR #25 MERGED 2026-07-14** — alocação do #N no chokepoint `DecisionLog.record` sob o lock da company; backfill provado em Postgres real) |
| Migration **V30** | fatia do bug #1 (endereço da estratégia) | **CONSUMIDA** (`V30__conversation_bastao_path.sql`, **PR #34 MERGED 2026-07-16**, main `4c1b35f`) |
| Migration **V31** · **ADR-0043** | **Frente E — baseline Spring AI 2** (branch `feat/backend-spring-ai-2`, NÃO mergeada) | **CONSUMIDAS NA BRANCH** — `V31__run_context_manifest_and_assessment.sql` + `docs/adr/0043-baseline-backend-spring-ai-2.md` ("Aceito 2026-07-16 · decisão do dono") existem SÓ na branch; a numeração fica CLAMADA para evitar colisão (a própria frente numerou V31 já prevendo a V30 da main). Entram na main quando a integração landar (R23-A) |
| Migration **V32+** · **ADR-0044+** | próximos livres | LIVRES — 0044 é a candidata natural do ACTIVE da Frente E (registrado por ela); emenda > ADR nova segue o precedente |
| Piso de testes | `main` = **769** backend · **294** front (pós-PR #35, rodada 23) | **re-medido na main mergeada `fef6073` pela SESSÃO DE CONTROLE #3 (2026-07-17: 769/769 `./mvnw test` · 294/294 `vitest run`, zero falha/erro/skip — régua própria do controle, não alegação de faixa)** — 769 = 757 da Frente E + 12 do #34; a NOTA da branch (757) consumou-se no merge do PR #35. Histórico: 750/294 na `4c1b35f` (2026-07-16, satélite R22-A: 750/750 · 294/294; +12 do conserto do bug #1) · 738/294 na `83c5fe9` (medido 2026-07-16; +2 do fix do loop) · 736/294 na `fd3be27` (medido 2026-07-15; projeção 723+7+6 exata — zero sobreposição R18-B×R19-A) · 723/294 na `05d9b6d` · 721/294 na `a00815a` · 702/294 na `3a42e92` · 702/294 na `124fcfa` · **691/290** na `60ac302` · 672/288 na `3d02801` · 651/288 na `0f10ddb` · 640/286 na `3fdda05` · 635/280 na `999a310` (soma ingênua por-branch daria 636 — a MEDIÇÃO é a régua, mesmo off-by-one da rodada 8) · 605/280 na `a04ed6b` · 568/270 na `837e365` · 547/270 na `a62c4e6` · 526/270 na `470b697` · 523/270 na `713a3f7` · 497/263 na `745a1b7` · 488/248 na `ebc399d` · 473/246 na `7c08855` |

## Decisões estruturais (não re-discutir)

- Cada faixa paralela nasce em **branch própria a partir de `main`** — NUNCA de
  `feat/agents-md-codex-adr0032` (isolamento de arquivos exige branches disjuntas).
- Landing por **PR** (push + `gh pr create` + merge), não fast-forward.
- `docs/roadmap-interno.md` é tocado por toda fatia — **conflito esperado e trivial**,
  resolve-se no rebase de cada PR; NÃO é motivo para serializar.
- Investigação do lease (ADR-0005) é **READ-ONLY** nesta rodada: produz laudo, não patch.

## Estado verificado (2026-07-10, rodada 1)

- `main` = `179fac2` (PR #3 merged, hotfix G1+G2); piso **429** verdes.
- Branch `feat/agents-md-codex-adr0032`: commit `f13b9d0` = **M1–M8 da G3 prontos** (suíte 443).
  Working tree: `docs/roadmap-interno.md` modificado + untracked `docs/adr/0032-…`,
  `docs/anexos/`, `docs/handoff-grilling-agents-md-codex.md`.
  **Estado mais avançado que o snapshot do kickoff da sessão de controle** (que dizia "nada
  commitado" e dava M6/M7/ADR como faltantes — todos já no commit).
- Falta na G3: commit dos docs · script M9 do operador · §VENDO live (Atlas + Docker) · PR.

## Matriz de colisão (herdada do levantamento validado — verificada arquivo a arquivo)

A G3 toca: `execution/AdapterRunExecutor.java` · `execution/adapter/AgentAdapter.java` ·
`execution/adapter/CodexAdapter.java` · `execution/adapter/WorkspaceConvention.java` (novo) ·
`skills/SkillMaterializer.java` · `memory/CompanyMemoryMaterializer.java` · testes espelho ·
`docs/roadmap-interno.md`.

| Frente | Arquivos em comum com a G3 | Veredito |
|--------|----------------------------|----------|
| G5 — ID sequencial de Issue (#N) | 0 | ✅ paralelizável |
| Investigação do lease (ADR-0005, read-only) | 0 | ✅ paralelizável |
| Autoria dos 3 SKILL.md | 0 (só .md novos) | ✅ paralelizável |
| Reconciliação doc-only das médias | 0 (só docs/adr/ + docs/ARQUITETURA.md) | ✅ paralelizável |
| Frente C — cap+graduação de memória | 2 (`CompanyMemoryMaterializer` + teste) | ✅ **DESTRAVADA em 2026-07-10** (G3 merged, PR #4) |
| Fatia 2 — CEO em 3 personas | 1 dura (`SkillMaterializer`) + premissa `.claude/` que a G3 remove (M3) + `AdapterRunExecutor` é caller do `WorkspaceResolver` que o M4 dela muda | ✅ **DESTRAVADA em 2026-07-10** (G3 merged + G5 landado → `SkillMaterializer` e `api.ts` livres) |

**Armadilhas registradas para rodadas futuras:**
- **G5 × Fatia 2 colidem entre si:** ambos escrevem `frontend/src/lib/api.ts` (G5 no
  `IssueSummary`, Fatia 2 no campo `persona`) e ambos criam migrations. G5 landa ANTES
  (leva V23; Fatia 2 herda V24/V25 limpas).
- **ADR-0005 tem DUAS divergências independentes:** SKIP LOCKED (média, reconciliação doc
  na faixa R1-E) e lease sem renovação (ALTA, aguarda laudo da R1-C + decisão H). A faixa
  R1-E só toca o trecho do claim/SKIP LOCKED — o trecho de renovação de lease fica intocado.
- **Pós-merge da G3:** Frente C e Fatia 2 destravam; piso da main sobe para 443.

## Perguntas ao Matheus — rodada 1 (2026-07-10) — RESPONDIDAS

| # | Pergunta | Resposta dele |
|---|----------|---------------|
| 1 | Rodar G5 agora, em paralelo, antecipando-o na frente da Fatia 2? | **SIM** — G5 em paralelo agora |
| 2 | G5 passa por grill antes do kickoff de execução? | **SIM, mas em sessão própria** — pediu um `/nova-sessao` para realizar o grill (não nesta sessão de controle) |
| 3 | Quem termina a G3 (VENDO+M9+docs+PR)? | **Kickoff novo** para o fecho (código pesado já commitado) |
| 4 | Divergência ALTA do lease: implementar renovação ou reconciliar ADR? | **Aguardar o laudo read-only**; decisão dele na rodada 2, com evidência |

## Rodada 1 — faixas emitidas (2026-07-10)

| ID | Faixa | Branch | Arquivos previstos | Vxx | Status |
|----|-------|--------|--------------------|-----|--------|
| **R1-A** | Fecho da G3: VENDO live + M9 + docs + PR | `feat/agents-md-codex-adr0032` (JÁ EXISTE — única faixa que não nasce de main) | docs da fatia + script de operação no scratchpad; zero código de produto novo | — | **CONCLUÍDA** (2026-07-10) — ver §Faixas concluídas |
| **R1-B** | Grill do G5 (sessão de /grilling com o Matheus) | nenhuma (grill não commita) | `docs/handoff-grilling-id-sequencial-issue.md` (novo) + linha do roadmap | — (V23 consumida na execução) | **CONCLUÍDA** (2026-07-10) — grill fechado E a execução pós-grill já landou (PR #5, ADR-0033); ver §Faixas concluídas |
| **R1-C** | Laudo read-only do lease (ADR-0005 ALTA) | nenhuma (não commita) | `docs/laudo-lease-adr0005.md` (novo, untracked) | — | **CONCLUÍDA** (2026-07-10) — ver §Faixas concluídas |
| **R1-D** | Autoria dos 3 SKILL.md (drafts p/ veto) | nenhuma nesta fase (drafts untracked) | `docs/skills-ceo/{writing-doc-plans,being-ceo,delegation}/SKILL.md` + dossiê de sourcing das externas | — | **SUBSTITUÍDA pela R1-D v2** (rodada 2 — blocos tipados viram placeholder; ver §Rodada 2) |
| **R1-E** | Reconciliação doc-only das 5 médias | `docs/reconciliacao-adrs-medias` (de `main`) | `docs/adr/0001*` · `0004*` · `0005*` (só trecho SKIP LOCKED) · `0019*` · `docs/ARQUITETURA.md` · roadmap §Divergências | — | EMITIDA · **EM ESPERA** — idem R1-D |

Verificação de não-colisão intra-rodada: R1-A (docs da G3 + roadmap) · R1-B (handoff novo +
roadmap) · R1-C (laudo novo) · R1-D (docs/skills-ceo/ novos) · R1-E (ADRs 0001/0004/0005/0019 +
ARQUITETURA + roadmap). Único arquivo compartilhado = `docs/roadmap-interno.md` (exceção
decidida: conflito trivial no rebase).

## Faixas concluídas

- **Execução do G5 — ID sequencial de Issue (#N), 2026-07-10 (kickoff pós-R1-B).** Handoff de volta:
  - **Branch/PR:** `feat/issue-seq-adr0033` (de `main`) → **PR #5 MERGED** (main = `5aeb02b`).
  - **Vxx consumido:** **V23** (`V23__issue_seq.sql` — coluna + backfill retroativo + `uq_issue_company_seq`).
  - **Piso de testes:** backend **443 → 448** (+5: `IssueSeqTest` ×4 com T-corrida + T-response HTTP);
    front **242** (mesma contagem, asserts endurecidos com `#N`); tsc/lint limpos.
  - **Arquivos:** backend `V23__issue_seq.sql` (novo) · `Issue.java` · `IssueRepository.java` ·
    `IssueService.java` · `CompanyRepository.java` (`findByIdForUpdate` novo) · `IssueController.java`
    (`IssueResponse.seq`) · `MissionControlService.java` (`ActiveRun.issueSeq`) ·
    `LiveStateProjector.java` (`#N` + ordena por seq) · `preview/server/PreviewServerController.java`
    (entries com `issueSeq`) · testes (`IssueSeqTest` novo; ApprovalGate/MissionControl/CeoTurn/
    PreviewServer/ReviewThresholdGuard ajustados) · front `api.ts` · `issues.ts` · `feed.ts` ·
    `mission-control.ts` · `reviews.ts` · `agents.ts` · páginas issues/detalhe/reviews/servers ·
    `agent-card.tsx` · `approval-thread-card.tsx` · testes vitest · docs ADR-0033 + handoff do grill
    + roadmap (linha G5 → IMPLEMENTADA·VENDO).
  - **Ficou por fazer:** §VENDO oficial do OPERADOR no `:8080` compartilhado (o harness barra restart
    de pid alheio): `g5-vendo-operacao.sh` (rebuild + restart cirúrgico; V23 aplica no boot) +
    `g5-vendo-provas.sh` (SQL do backfill + roteiro UI/CEO) no scratchpad da sessão de execução.
    O backfill JÁ FOI PROVADO em Postgres real descartável (V22+seed → V23: denso por company,
    desempate por UUID em timestamp idêntico; issue nova via UI nasceu MAX+1; rajada de 8 POSTs
    concorrentes → densos, zero erro; turno de CEO echo recebeu `- #2 [OPEN] …` no estado vivo).
  - **Decisões novas no caminho (com racional):** (1) `LiveStateProjector` passou a ordenar o recorte
    por `seq` desc, não `createdAt` desc — achado do /verify: empate EXATO de timestamp embaralhava
    #2/#3 e quebrava o "createdAt desc ≡ seq desc" da D3; ordenar por seq torna a equivalência exata.
    (2) Tela Servers: `LiveEntry`/`CandidateEntry` ganharam `issueSeq` no join do controller (id→Issue)
    — o M5 não os listava, mas a D3 é regra única sem exceções. (3) Via HTTP, company fantasma dá
    **403** (guard de membership, deny-by-default) — o 404 limpo do M4 vale para callers internos que
    passam o guard; comportamento correto em camadas, registrado para não parecer divergência.
  - **Destrava:** Fatia 2 (colisão `api.ts` resolvida — G5 landou antes; V24/V25 seguem livres).

- **R1-C — Laudo do lease (ADR-0005), 2026-07-10.** Handoff de volta:
  - **Branch/PR:** nenhuma (faixa não commita). **Vxx:** nenhum. **Piso de testes:** inalterado
    (a sonda JUnit descartável rodou isolada — 1 teste PASS em H2 — e foi deletada; suíte não tocada).
  - **Arquivos:** só `docs/laudo-lease-adr0005.md` (untracked, NÃO commitar) + esta atualização do ledger.
    Zero produção, zero ADR, zero UPDATE/DELETE no banco (só SELECTs), :8080 intocado.
  - **Evidência:** queries §4 no DB vivo — **predição CONFIRMADA** (70 runs na história, `attempt>1`=0
    em todos, zero runs com 2+ `RUN_STARTED`; run de 12min58s = 6,5× o lease terminou intocado como
    `attempt=1`). Sonda §5 PASS (worker-vivo reclamado: 2º claim na mesma linha, finalize do original
    cai no vazio em silêncio). Colateral registrado: 2 linhas `RUNNING` zumbis de seed (`attempt=0`,
    lease NULL — invisíveis ao reaper), candidatas a faxina de seed.
  - **DECISÃO DO MATHEUS: (a) implementar renovação de lease** (não reconciliar-e-declarar-dívida).
  - **Constraints para a faixa de execução da renovação (rodada 2):** (1) o trecho de lease da
    ADR-0005 só se edita DEPOIS da R1-E landar — mesma ADR, faixa do SKIP LOCKED; (2) a fatia toca o
    núcleo de `execution/` (HeartbeatDispatcher/Store/LeaseReaper/Scheduler + AdapterRunExecutor como
    caller) — recalcular matriz de colisão antes de emitir; (3) decisões de desenho em aberto que o
    laudo NÃO fechou: cadência (~30s?), semântica de parar-de-renovar (morte presumida — e o processo
    vivo que estourou?), topologia do renovador (timer por run × varredura única), teste do caso
    worker-vivo vira permanente (o roteiro §5 do laudo é o seed) — **candidata a grill curto antes do
    kickoff, no padrão do G5**.

- **R1-A — Fecho da G3 (VENDO live + M9 + docs), 2026-07-10.** Handoff de volta:
  - **Branch/PR:** o **PR #4 já estava MERGED desde 00:29** — criado pela própria sessão de execução
    junto do commit `c82ea39`; o snapshot deste ledger ("falta PR") estava desatualizado. A branch
    local foi apagada na limpeza pós-merge. Os docs do fecho (§RESULTADO + roadmap) landaram pelo
    **PR docs #6 — MERGED com OK do dono (2026-07-10, `c9ee3e8`)**; branch docs deletada.
  - **Vxx consumido:** nenhum. **Piso de testes:** 443 na branch (pré-merge); **448 na main G3+G5
    confirmados live neste fecho** (o gatilho abaixo previa 443 — o G5 somou 5).
  - **Arquivos:** `docs/handoff-grilling-agents-md-codex.md` (§RESULTADO novo) · `docs/roadmap-interno.md`
    (linha G3 → ✅ VENDO provada; log; _próximo_ → Fatia 2) · scripts M9 no scratchpad. Zero produção.
  - **VENDO: PASS 5/5** — M9 (pg_dump 2,5 MB · faxina DB **24→3 companies** · wipe workspaces ·
    restart) · carta (Atlas: `AGENTS.md`==carta v4 **por md5** + `.claude/` ausente + resposta
    "dental + 3 motivos") · skill (sonda import→set→`.codex/skills`→**ABACATE-ROXO-93**; listou
    `distillation`) · ruído (**1 `ITEM.COMPLETED|error`/run** = baseline G4 → resíduo registrado,
    não-gate) · Docker (CEO codex/DOCKER em Demo Fixtures; volume `orq_b92cb312_73676f52` com
    `AGENTS.md` md5==banco e `.claude/` ausente). Detalhe no §RESULTADO do handoff da fatia.
  - **Decisões novas no caminho (com racional):** (1) `activity_log` é **append-only por trigger**
    (`trg_activity_log_immutable`) — faxina de company exige `DISABLE TRIGGER` na janela transacional
    (o script do operador morreu nesse degrau; scripts futuros de faxina nascem com isso).
    (2) Demo Fixtures ficou com artefatos VIVOS da prova (CEO `Sonda Docker Codex` + carta-marcador
    v1 `GIRASSOL-CINZA-58` + volume) — evidência de propósito; remover se atrapalhar demos.
    (3) Achado de ambiente: **rebuild do JAR sob o processo vivo quebra o classloader lazy** (rotas
    frias penduram — `/steps` HTTP 000, `NoClassDefFoundError`); regra operacional: todo rebuild do
    `target/` compartilhado pede restart, e rodadas paralelas avisam antes de rebuildar.
  - **Ficou por fazer:** nada da fatia (resíduo G4 é métrica registrada).
  - **Destrava:** Fatia 2 e Frente C (G3 merged + G5 landado → `api.ts` e `SkillMaterializer` livres).

- **R2-A — Execução da Fatia 2 (CEO em 3 personas, ADR-0031), 2026-07-10.** Handoff de volta:
  - **Branch/PR:** `feat/ceo-tres-personas-adr0031` (de `main` `c9ee3e8`), commits locais **NÃO
    pusheados** (`22ae1d2` código + commit de docs) — **PR só com OK do Matheus** (default dele = PR,
    não push direto).
  - **Vxx consumidos:** **V24** (`V24__agent_persona.sql`) + **V25** (`V25__ceo_persona_moldes.sql`
    — moldes `ceo-*` + backfill). **ADR-0031 consumida** (+ forward-refs em 0017/0018). ADR-0034 segue livre.
  - **Piso de testes:** backend **448 → 469** (+21: seed 2 · inbound 1 · prompt/persona 3 · workspace 1
    · scanner 5 · fluxo do bastão 6 · projeção 1 · gate A4 2); front **242 → 246** (+4); tsc/lint limpos.
  - **Arquivos:** V24/V25 (novas) · `org/agent/{Agent,AgentRepository,AgentController,CeoPersona*,
    CeoPersonaSeeder*}` · `identity/company/CompanyService` · `conversation/{ConversationService,
    ConversationTurnCoordinator,ConversationTurnRepository,LiveStateProjector}` ·
    `conversation/persona/*` (novo: ConversationBaton · PersonaHandoffScanner · PersonaBatonRouter ·
    PersonaContinuationPoster · PersonaContinuationSentinel · KickbackTurn) ·
    `execution/{AdapterRunExecutor,runtime/WorkspaceResolver}` · `governance/approval/{ApprovalService,
    ApprovalController}` (A4) · testes novos/estendidos · front `api.ts`/`page.tsx`/`ceo-conversa.ts`/
    `approval-thread-card.tsx` + testes · docs ADR-0031 + refs 0017/0018 + roadmap + §RESULTADO no handoff.
    **`handoff/` da Fatia 1: zero-diff** (o M6 nasceu espelho em `conversation/persona/`, não emenda).
  - **Formato dos blocos tipados ESCOLHIDO (dona do contrato — VETO PENDENTE do Matheus):** JSON em
    fence **coluna 0**, irmão do ```` ```proposta ````: `handoff{para,bastao,resumo}` ·
    `needs-grilling{lacuna}`; `origem` derivada do EMISSOR (não viaja no bloco); exemplo indentado
    no prompt nunca roteia. Pinado em `PersonaHandoffScannerTest`. **→ avisar R1-D: ⚠ SYNC-FATIA-2
    destravado** (drafts podem sincronizar com este formato).
  - **Ficou por fazer:** VENDO oficial §D na teams-demo = OPERADOR (restart do `:8080` aplica
    V24/V25; scripts `f2-vendo-operacao.sh` + `f2-vendo-provas.sh` em
    `orquestrador-agentes/scratchpad/`). Bancada JÁ PROVADA em 2ª instância `:18080` + pg
    descartável (cascata Q→P→E→gate com UMA mensagem · thread uma-voz · kickback visível · M9
    prossiga→corte ao vivo · A4 override · backfill V25 em pg real); só D5-semântico e D10
    (delegation em run claude real) dependem do live do operador.
  - **Decisões novas (com racional):** (1) **M9 ganhou CORTE na reincidência pós-prossiga** ("Bastão
    parado", turno ANSWERED sem run novo) — o TDD provou que só redirecionar girava para sempre;
    (2) kickback é turno VISÍVEL (o dono precisa LER a re-grelha; precedente = aviso de corte
    ADR-0030); (3) auto-seed dirigido pelos moldes e fail-soft (banco sem V25 não semeia — protege
    o parque de testes H2); (4) skills EXTERNAS fora dos moldes v1 (sourcing é veto pendente; entram
    por v2 de molde); (5) backfill copia o motor do CANÔNICO para P/E (na teams-demo = codex provado).
  - **Vetos do Matheus: TODOS CONFIRMADOS (2026-07-10, "OK" na sessão de execução)** — formato
    dos blocos ✔ (→ R1-D pode sincronizar o `⚠ SYNC-FATIA-2`) · N=5 do M9 ✔ · off-book do
    delegation v1 ✔ · ADR-0031 como nova ✔ · **PR #8 MERGED (2026-07-10, main `1129b61`)** após
    **VENDO oficial §D LIVE na teams-demo = PASS**: V24/V25 no boot do operador · backfill vivo
    (Atlas=questionador, motor codex intocado) · grill em voz própria → dossiê APENDADO no
    workspace da conversa → "sim" → cascata autônoma Q→P→E → **27 propostas HIRE_AGENT PENDING**
    com racional/motor por-agente · thread uma-voz (2 visíveis / 4 na trilha por persona) ·
    estratégia real com Premissas + teto 27<30 + fronteira ADR-0017. Residual registrado (não-gate):
    D10 (web/Task in-run) não exercitado — run codex sem pesquisa. Achado que confirma o A4: o CEO
    codex propôs os 27 motores como "GPT-5.4" (o viés de motor previsto). Resíduo de VENDO na
    teams-demo: conversa "VENDO F2 — cafeteria" + 27 approvals PENDING (decisão do dono:
    rejeitar/limpar/manter como evidência). Gotcha corrigido: `f2-vendo-operacao.sh` com cd absoluto.
  - **Colisões observadas:** trabalho paralelo (R2-B?) editou `docs/roadmap-interno.md` na MESMA
    working tree durante a execução (hunks do lease) — deixados FORA dos commits desta faixa;
    conflito trivial previsto. Ledger e laudo seguem untracked (contrato respeitado).

## Próxima rodada (gatilhos)

- **Handoff da R1-A (G3 merged): ✔ FECHADO (2026-07-10)** — VENDO PASS 5/5; Fatia 2 e Frente C
  destravadas; **piso da main = 448** (não 443 — G5 somou); recalcular matriz com main G3+G5.
- **Handoff da R1-B: ✔ FECHADO (2026-07-10)** — grill fechado e execução do G5 landada (PR #5,
  ADR-0033, V23 consumida); ver §Faixas concluídas.
- **Handoff da R1-C (laudo): ✔ FECHADO (2026-07-10)** — decisão tomada: **(a) renovação de lease**.
  Rodada 2 emite a faixa de execução respeitando as constraints registradas em §Faixas concluídas
  (ADR-0005 depois da R1-E; recalcular colisão em `execution/`; grill curto recomendado antes do kickoff).
- **Handoff da R1-D: ✔ FECHADO (2026-07-10)** — 4 drafts prontos e validados pela sessão de
  controle (⚠ VETO H ×11 · ⚠ SYNC-FATIA-2 ×9); veto do Matheus concentrado em
  `docs/skills-ceo/sourcing.md`; registro no DB + SYNC na esteira da Fatia 2 (pós-veto do formato).
- **Handoff da R1-E: ✔ FECHADO (2026-07-10)** — **PR #7 MERGED com OK do Matheus** (main =
  `c8b1693`); lease da 0005 intocado **CONFERIDO** (diff append-only, +24 linhas no fim; bullets
  da Decisão byte-idênticos). **AMBOS os gates da execução da renovação de lease estão abertos**
  (gate 1 = este merge · gate 2 = colisão vazia carimbada) — a faixa pode ser emitida a qualquer
  momento (kickoff pronto, ver R2-B). Nota p/ o rebase da Fatia 2: a main agora carrega os hunks
  da R1-E no roadmap (§Divergências/§Vetos/§Histórico) — conflito trivial previsto.
- **Fatia 2** só entra em rodada quando: G3 merged **e** (idealmente) G5 landado (`api.ts` limpo).
  **✔ AMBAS as condições satisfeitas em 2026-07-10** — pendente só a composição da rodada 2
  (sequência vs. R1-D, dona do formato dos blocos tipados).

## Validação da rodada 1 (sessão de controle, 2026-07-10)

Auditoria independente do ledger contra repo + ambiente — **VÁLIDO**; correções de estado
aplicadas acima (R1-B → concluída; ADR-0033 → consumida; piso → 448/242) e 4 achados:

- **Confirmado por git/gh:** PRs #4 (G3), #5 (G5) e #6 (docs do VENDO) MERGED; main = `c9ee3e8`;
  `V23__issue_seq.sql` + ADR-0032/0033 na main; working tree só com este ledger + o laudo
  (untracked, como o contrato mandava); §RESULTADO presente no handoff da G3; linhas G3/G5 do
  roadmap movidas para VENDO.
- **Confirmado por execução (não só alegado):** backend **448/448** verdes (26s) · front
  **242/242** (20 suítes) — suítes re-executadas pela sessão de controle.
- **Confirmado no DB vivo (read-only):** faxina M9 real (exatamente 3 companies: Demo Fixtures ·
  Teams Demo · Thumbnail Demo); **V23 JÁ APLICADA no :8080 compartilhado** (topo do flyway
  history) com backfill completo — 21/21 issues com `seq` (1..9). O restart do fecho da G3
  aplicou a V23 de carona.
- **Achados:** (1) do VENDO do operador do G5 pende só o roteiro interativo (issue nova MAX+1 na
  UI + CEO citando `#N`) — a infra (V23 no boot + backfill vivo) já está provada; (2) resta
  **1 zumbi `RUNNING`** de seed no banco (attempt=0, lease NULL, invisível ao reaper — o laudo
  achou 2, a faxina M9 levou 1); candidata a faxina de seed; (3) **nome real do container do DB
  é `agente-orquestrator-db-1`** — docs/handoffs citam "orquestrador-db-1"; kickoffs futuros
  devem usar o nome real; (4) branch local `feat/issue-seq-adr0033` remanescente pós-merge
  (pode podar).

## Rodada 2 — faixas emitidas (2026-07-10, pós-validação)

**Decisões do Matheus (rodada 2):** (1) **Fatia 2 AGORA**, em paralelo com R1-D/R1-E — com a
regra de coordenação: **a Fatia 2 é a DONA do formato dos blocos tipados** (o scanner dela pina
o contrato em teste); o R1-D foi REEMITIDO emendado (drafts referenciam o formato como
placeholder `⚠ SYNC-FATIA-2`). (2) **Grill curto da renovação de lease emitido agora**; a
EXECUÇÃO da renovação segue condicionada: R1-E landada (mesma ADR-0005) + recálculo de colisão
com a Fatia 2 em `execution/`.

| ID | Faixa | Branch | Arquivos previstos | Vxx / ADR | Status |
|----|-------|--------|--------------------|-----------|--------|
| **R2-A** | Execução da Fatia 2 — CEO em 3 personas (M1–M9 + adendo A1–A5) | `feat/ceo-tres-personas-adr0031` (de `main`) | V24+V25 (novas) · `conversation/*` (+ pacote novo `conversation/persona/`) · `org/agent/*` · `execution/{AdapterRunExecutor,runtime/WorkspaceResolver}` · `governance/approval/*` (A4) · front `api.ts`/`page.tsx`/`ceo-conversa.ts` · ADR-0031 + forward-refs 0017/0018 · roadmap | **V24+V25 · ADR-0031** | **CONCLUÍDA E MERGEADA** — **PR #8 MERGED** (main `1129b61`) após **VENDO §D LIVE PASS na teams-demo**; **vetos H TODOS confirmados pelo Matheus** (formato dos blocos JSON · N=5 · off-book D10 · ADR-0031 nova · PR); SYNC-FATIA-2 destravado |
| **R2-C** | Execução da renovação de lease (kickoff pós-R2-B, emitido com zero gates) | `feat/lease-renewal-adr0005` (de `main` `1129b61`) | `execution/{HeartbeatDispatcher, HeartbeatRunRepository, HeartbeatRunStore, LeaseRenewer✚}` · `LeaseRenewalTest✚` · ADR-0005 (emenda Recovery) · roadmap (🔴 ALTA fechada; commitou os hunks soltos do grill) · handoff do grill commitado c/ §RESULTADO · `.claude/skills/verify/` (tooling) — **`AdapterRunExecutor` INTOCADO (D1)** | — (zero migration; ADR = emenda na 0005) | **CONCLUÍDA E MERGEADA** — **PR #9 MERGED** (17:23Z, main = `7c08855`); /verify em instância descartável PASS; ver §Fechamento |
| **R2-B** | Grill curto — renovação de lease (pós-laudo) | nenhuma (grill não commita) | `docs/handoff-grilling-renovacao-lease.md` (novo) + roadmap | — (execução: sem migração prevista; registro = emenda na ADR-0005, 0034 livre) | **CONCLUÍDA** (2026-07-10) — D1–D6 fechadas; ver §Faixas concluídas |
| **R1-D v2** | Autoria dos 3 SKILL.md (EMENDADA: blocos = placeholder) | nenhuma (drafts untracked, sem commit) | `docs/skills-ceo/*` | — | **CONCLUÍDA** (2026-07-10) — 4 drafts com ⚠ VETO H ×11 e ⚠ SYNC-FATIA-2 ×9, validados pela sessão de controle; veto = `sourcing.md` (balcão único) |
| **R1-E** | Reconciliação doc-only das médias (INALTERADA da rodada 1) | `docs/reconciliacao-adrs-medias` (de `main`) | ADRs 0001/0004/0005/0019 + `docs/ARQUITETURA.md` + roadmap | — | **CONCLUÍDA** (2026-07-10) — **PR #7 MERGED com OK do Matheus** (15:01Z, main = `c8b1693`; confirmado por gh+git pela sessão de controle) — **gate 1 do lease ABERTO** |

Não-colisão intra-rodada verificada: ADRs disjuntos (R2-A: 0017/0018/0031 × R1-E:
0001/0004/0005/0019); R2-B e R1-D só criam arquivos novos; único compartilhado =
`docs/roadmap-interno.md` (exceção trivial decidida na rodada 1).

**Armadilha (RESOLVIDA em 2026-07-10):** execução da RENOVAÇÃO DE LEASE × Fatia 2 — o gate 2
(recálculo contra o diff REAL) foi **CARIMBADO PELA SESSÃO DE CONTROLE**: interseção **VAZIA**
entre os 43 arquivos de `git diff main...feat/ceo-tres-personas-adr0031` e os arquivos do lease
(`Heartbeat*` · `application.yml` · `docs/adr/0005*`). A D1 do grill (lease NÃO toca
`AdapterRunExecutor`) eliminou a única colisão possível. **A execução do lease fica bloqueada
SÓ pelo gate 1: R1-E landada** (mesma ADR-0005).

- **R2-B — Grill da renovação de lease, 2026-07-10.** Handoff de volta (CONCLUÍDA):
  - **Branch/PR:** nenhuma. **Vxx:** nenhum. **Piso:** intocado (448/242; zero código).
  - **Arquivos:** `docs/handoff-grilling-renovacao-lease.md` (novo, untracked — a execução
    commita) + `docs/roadmap-interno.md` (hunks do lease: divergência 🔴 anotada como respondida
    + pergunta H riscada — NÃO commitados, ficaram na working tree sobre a branch da Fatia 2).
  - **D1–D6:** timer por run no `HeartbeatDispatcher` (nasce no claim, morre no finally;
    `AdapterRunExecutor` INTOCADO) · pulso 30s (`orquestrador.heartbeat.lease-renew-ms:30000`)
    esticando para now+2min · posse perdida (0 linhas) = `RUN_LEASE_LOST` + timer para, SEM kill;
    exceção transiente insiste · `LeaseRenewalTest` permanente com 4 provas (seed = §5 do laudo),
    piso 448→~452 · zumbi de seed = 1 UPDATE de OPERADOR no VENDO (CANCELLED, não DELETE);
    **correção ao laudo: o zumbi NÃO é inofensivo — engole wakeups por coalescing** · emenda no
    trecho Recovery da PRÓPRIA ADR-0005 é M obrigatório da fatia (pós-R1-E por construção).
  - **Kickoff de execução PRONTO** (entregue pela sessão do grill, gates explícitos no topo):
    gate 1 = R1-E merged **✅ SATISFEITO (PR #7 merged 2026-07-10)** · gate 2 = recálculo de
    colisão ✅ CARIMBADO acima · branch de main (ex.: `feat/lease-renewal-adr0005`) · sem
    migração · /tdd + /verify. **Zero gates abertos — faixa emitível.**

- **R1-E — Reconciliação doc-only das 5 médias, 2026-07-10.** Handoff de volta (**CONCLUÍDA —
  PR #7 MERGED com OK do Matheus**, main = `c8b1693`):
  - **Branch/PR:** `docs/reconciliacao-adrs-medias` (de `main` `c9ee3e8`, criada em **worktree
    separado** — o HEAD compartilhado da working tree, na branch da Fatia 2, não foi tocado) →
    **PR #7 MERGED (2026-07-10)** com OK do dono; branches local e remota podadas.
  - **Vxx consumido:** nenhum. **Piso de testes:** 448/242 inalterado **por construção** — diff
    100% `docs/` (6 arquivos, +98/−7; suíte não re-executada porque zero código foi tocado).
  - **Arquivos:** `docs/adr/0001` · `0004` · `0005` · `0019` (notas de reconciliação datadas
    2026-07-10, em APÊNDICE — corpo original dos 4 ADRs intocado) + `docs/ARQUITETURA.md` (as 5
    menções a jOOQ → Spring Data `@Query`; resta 1 ponteiro histórico deliberado na tabela §7)
    + `docs/roadmap-interno.md` (§Divergências: 5 linhas carimbadas "reconciliada em doc";
    header MÉDIA; veto das médias em §Vetos atualizado; linha nova no §Histórico).
  - **Prova do critério crítico:** o diff da ADR-0005 é **append-only** (+24 linhas no fim);
    os bullets da Decisão — incluindo o Recovery/renovação de lease — estão byte-idênticos.
    A nota abre declarando o escopo (só o claim) e aponta a divergência ALTA para a fatia própria.
  - **Ficou por fazer:** nada da faixa. O PR da Fatia 2, quando aberto, rebaseia por cima dos
    hunks da R1-E no roadmap (conflito trivial previsto).
  - **Decisões novas no caminho (com racional):** nenhuma decisão H tomada (contrato respeitado —
    D5/D11 registrados como RESIDUAL ABERTO, não decididos). Um reforço de evidência entrou na
    nota D11: o `DecisionController` é **GET-only** (o `supersedesId` sai na resposta, nunca
    entra) — fecha a última porta que poderia ser um "writer" não mapeado pela auditoria.
  - **Destrava:** gate 1 da execução da renovação de lease — **ABERTO com o merge** (gate 2 já
    estava carimbado → a faixa do lease está emitível).

- **R1-D v2 — Autoria dos 3 SKILL.md (drafts p/ veto), 2026-07-10.** Handoff de volta (CONCLUÍDA):
  - **Branch/PR:** nenhuma — drafts untracked, sem commit (atravessam troca de branch sem
    conflito). **Vxx:** nenhum. **Piso:** intocado por construção (zero código; suíte não rodada).
  - **Arquivos (VALIDADOS pela sessão de controle):** exatamente 4 novos em `docs/skills-ceo/` —
    `writing-doc-plans/SKILL.md` · `being-ceo/SKILL.md` · `delegation/SKILL.md` · `sourcing.md`;
    marcadores conferidos no disco: ⚠ VETO H ×11 · ⚠ SYNC-FATIA-2 ×9.
  - **Decisões novas (com racional):** (1) o contrato dos blocos tipados viaja INLINE no
    `composePrompt` da Fatia 2 — os drafts APONTAM para esse contrato, nunca o duplicam (fonte
    única; reforça a emenda do placeholder); (2) `grill-me` upstream é um wrapper de 7 linhas
    sobre /grilling — importar o MOTOR, não o wrapper; (3) nota de staging no topo de cada draft
    (removível no registro) com o skillset-alvo sugerido + tabela consolidada persona→skills no
    `sourcing.md` — o veto acontece num lugar só.
  - **Ficou por fazer:** veto do Matheus (11 pontos, balcão = `sourcing.md`) · registro no DB +
    resolução dos ⚠ SYNC-FATIA-2 na esteira da Fatia 2 (pós-veto do formato dos blocos) ·
    conferir licenças de `mattpocock/skills` e `superpowers` no import.

**Gotcha operacional da working tree compartilhada:** o HEAD local está na branch da Fatia 2
com hunks NÃO-commitados do roadmap (edições do grill do lease) por cima. Quem for commitá-los
é a EXECUÇÃO do lease (junto com o handoff do grill) — ao criar a branch de main, stash/carry
desses hunks com cuidado; e o PR da Fatia 2, quando aberto, não deve levá-los. **A `main` LOCAL
foi fast-forwarded pela sessão de controle para `c8b1693` (pós-PR #7)** — a branch do lease
nasce dela já com a R1-E dentro.

**Disponível mas NÃO emitida:** Frente C (cap+graduação de memória) — destravada pela G3 e sem
colisão com a rodada 2 (a Fatia 2 não toca `CompanyMemoryMaterializer`); aguarda a palavra do
Matheus.

**Pendência de operador (fora de faixa):** roteiro interativo do VENDO do G5. Os handoffs
formais das sessões R1-C e G5 foram recebidos e reconciliados (2026-07-10) — zero contradição
com este ledger; cópias duráveis + scripts preservados em
`/Users/matheussouza/Pessoal/orquestrador-agentes/scratchpad/` (os originais viviam em
scratchpads de sessão voláteis). **O passo 1 do VENDO (`g5-vendo-operacao.sh`, rebuild+restart
p/ aplicar a V23) JÁ ESTÁ SATISFEITO** — a validação da rodada 1 provou V23 no topo do flyway
history do `:8080` vivo com backfill 21/21 (o restart do fecho da G3 aplicou de carona). Resta
só `g5-vendo-provas.sh` + roteiro manual: issue nova pela UI nasce MAX+1 · lista/detalhe/feed
com `#N` · CEO da teams-demo responde "como está a #2?" citando a task certa.

~~**Insumo para o recálculo de colisão da fatia de renovação de lease (do handoff R1-C).**~~
**CADUCADO** — recálculo feito, fatia executada e mergeada (PR #9); o desenho final não tocou
`AdapterRunExecutor` nem consumiu a ADR-0034 (emenda na 0005).

## Rodada 2 — FECHAMENTO (validação da sessão de controle, 2026-07-10)

Todas as faixas das rodadas 1 e 2 + a execução do lease (R2-C) estão **FECHADAS E MERGEADAS**.
Os 4 handoffs formais (R2-B · R2-A · R1-E · R2-C) foram recebidos e reconciliados; cópias
duráveis no `scratchpad/` da raiz do workspace. Validação independente desta sessão:

- **git/gh:** PRs **#7** (R1-E, 14:55Z) · **#8** (Fatia 2, 15:06Z) · **#9** (lease, 17:23Z)
  MERGED; main local = `7c08855`, em dia com origin; working tree LIMPA com exatamente os 3
  untracked de contrato (ledger · laudo · `docs/skills-ceo/`); os hunks soltos do roadmap foram
  commitados pelo PR #9, como o gotcha mandava — gotcha da working tree ENCERRADO.
- **Suítes re-executadas na main:** backend **473/473** verdes · front **246/246** (20 suítes).
  Contagem canônica registrada na tabela de Reservas (448+21+4 = 473 confere).
- **Código/docs conferidos:** `LeaseRenewer.java` na main · ADR-0005 com a "Nota de
  implementação" no Recovery · roadmap com "🔴 ALTA (0 abertas · ambas FECHADAS em 2026-07-10)"
  · `.claude/skills/verify/` novo (tooling de instância descartável).
- **DB vivo (read-only):** zumbi D5 = exatamente 1 linha restante
  (`b2b2b2b2-0000-4000-8000-000000000001`, RUNNING/attempt=0) — morre no VENDO do operador.

**Pendências consolidadas (nenhuma faixa de agente em voo — tudo é OPERADOR ou decisão H).
Progresso do operador em 2026-07-10 ~14:40, validado read-only pela sessão de controle:**
1. ✅ **VENDO DO LEASE: PASS (2026-07-10, ~15:45).**
   - **Restart FEITO pelo operador** (pid 81038, up 14:47, JAR ≡ main `7c08855` — classes
     `LeaseRenewer`+`CeoPersonaSeeder` conferidas por unzip; boot 2,7s limpo). O gotcha do
     classloader (JAR de 14:18 trocado sob o pid 75920) morreu no restart.
   - **Pulso PROVADO AO VIVO no `:8080`** (observador da sessão de controle, SELECTs read-only,
     run de conversa `62eae6d2` do Atlas/teams-demo): claim 18:43:32Z → lease 18:45:32Z (+2min);
     **pulso aos +30s esticou para 18:46:02Z (+30s exatos)**; final SUCCEEDED/attempt=1;
     **RUN_LEASE_LOST = 0**. Confirmação multi-pulso (run >2min) sai de carona no run do Rex
     (issue #10) — observador rearmado.
   - ✅ **Zumbi D5 FEITO E VALIDADO:** `b2b2b2b2-…` → CANCELLED (UPDATE do operador via
     `lease-vendo.sh`; SELECT de confirmação re-executado pela sessão de controle).
2. Resíduo do VENDO da Fatia 2 na teams-demo: conversa "VENDO F2 — cafeteria" + **27 approvals
   HIRE_AGENT PENDING** — decisão H: limpar/rejeitar/manter como evidência (precedente G3 = manter).
3. Roteiro do VENDO do G5 — **quase fechado:**
   - ✅ Partes automatizadas (`g5-vendo-provas.sh`): backfill denso 21 rows 1..N por company
     espelhando `(created_at, id)`; `seq NOT NULL` + `uq_issue_company_seq` no lugar.
   - ✅ **Passo 4 PASS (2026-07-10 20:08Z, run `29116da0`, Demo Fixtures):** dono perguntou
     "como está a #2?" → Questionador respondeu *"A #2 está aberta: 'Deploy do servico de
     billing', com Nix como responsável"* + status do estado vivo, zero delegação, troca
     apendada no dossiê (A1).
   - ✅ **Passo 3 PASS (2026-07-10 20:37Z):** issue criada pela UI nasceu **`#10`** (MAX+1) em
     Demo Fixtures, vista pelo dono na lista.
   - ✅ **Ciclo de execução da #10 PASS (20:44Z):** a UI não tem "empurrar issue para agente"
     (checkout é agent-driven, `IssueController:58`) — a sessão de controle disparou
     `POST /wakeups {issueId}` como os smokes: run `ec994211` QUEUED→STARTED→SUCCEEDED,
     attempt=1, zero RUN_LEASE_LOST, **issue #10 → DONE com assignee Rex**.
   - Passo 5 (card "executando #N"): não observável ao vivo (run instantâneo); coberto por
     teste de front + evidência live da F2 (`ActiveRun.issueSeq`). **G5 VENDO: FECHADO.**
   - Nota: a confirmação multi-pulso do lease não se materializou (todos os runs do dia < 1
     pulso de sobra) — dispensável: a renovação de +30s exatos já foi fotografada ao vivo no
     run `62eae6d2` e a prova implícita vale (run >lease com attempt=1 e zero LEASE_LOST só
     existe com renovação).

**★ VENDOs DE OPERADOR: TODOS FECHADOS (2026-07-10 ~17:45 local).** Restam apenas DECISÕES do
Matheus: cascata parada da Demo Fixtures (estacionar ou responder adapterType/effort → +7
approvals) · 27 approvals da cafeteria · veto do `sourcing.md` · composição da rodada 3.

**Bônus — VENDO ESPONTÂNEO da cascata da Fatia 2 (2026-07-10, 20:08–20:24Z, Demo Fixtures):**
o dono disparou o caso A ao vivo e o rastro prova: D5 confirma-antes ("pode seguir" → dossiê
carimbado) · D6 bloco ```` ```handoff ```` JSON ao planejador · D12 estratégia de orquestração
escrita no workspace da conversa · A2/D7 ```` ```needs-grilling ```` do Executor (recusou chutar
`adapterType/effort` dos 7 agentes; kickback ao Questionador) · A1 dossiê apendado por turno ·
palavras do dono registradas VERBATIM. **Cascata está PARADA aguardando resposta do dono**
(adapterType/model/effort dos 7) — responder gera +7 approvals PENDING; estacionar é inócuo
(fail-open).

**Descobertas de produto do VENDO espontâneo (para grill futuro — registradas na conversa
pelo próprio dono):**
- **Aprovação em LOTE no gate:** "deveria existir uma fila/tela de contratações pendentes, em
  lote, com `aprovar todos` e `recusar todos`" (palavras dele, 20:22–20:23Z). Dor real: 27
  approvals da cafeteria + 7 vindo. → candidata a fatia nova (grill).
- **Gap do A3 observado ao vivo:** o próprio CEO se autocriticou ("deveria ter te entregue uma
  proposta legível no chat antes do menu") — o racional-antes-do-menu (A3 do adendo) não se
  expressou. Causa provável: **os 3 SKILL.md ainda não estão registrados no DB** (as personas
  rodam só com o cabeçalho de prompt; o comportamento fino vive nos drafts da R1-D). → reforça
  a prioridade da fatia SYNC+registro das skills na rodada 3.
4. **Veto do sourcing das skills** (11 pontos ⚠ VETO H em `docs/skills-ceo/sourcing.md`) —
   destrava a fatia de SYNC dos drafts (formato dos blocos JÁ CONFIRMADO: JSON fence coluna 0)
   + registro no DB.
5. Residual D10 (não-gate): `delegation` com web/Task in-run não exercitado — prova plena pede
   run claude com pesquisa real; oportunidade natural junto de um VENDO futuro ou do caso B.

**DECISÃO DO MATHEUS (2026-07-10, pós-fechamento): rodada 3 NÃO emitida ainda** — primeiro os
VENDOs de operador (lease urgente → G5 → resíduo teams-demo). As 3 candidatas abaixo ficam
ESTACIONADAS; ao receber os resultados dos VENDOs, a sessão de controle valida (read-only) e
compõe a rodada 3.

**RODADA 3 COMPOSTA (2026-07-10, pós-VENDOs — decisão do Matheus: as 3 candidatas aprovadas):**

| ID | Faixa | Tipo | Arquivos previstos | Reservas | Status |
|----|-------|------|--------------------|----------|--------|
| **R3-A** | SYNC + registro das 3 skills das personas (começa pelo veto do sourcing com o Matheus) | dados via API + docs (zero código; se exigir código → volta ao controle) | `docs/skills-ceo/*` (sync + PR docs ao final) · DB via API do produto | — | **CONCLUÍDA** — **PR #11 MERGED (2026-07-11T00:06Z)**; 11 vetos decididos · 0 marcadores ⚠ · 5 skills/12 anexos no DB · prova viva A3 PASS; ver §Rodada 3 — fechamento |
| **R3-B** | Grill — aprovação em LOTE no gate (feature pedida pelo dono ao vivo) | grill → execução na MESMA faixa (`feat/aprovacao-em-lote`) | `docs/handoff-grilling-aprovacao-em-lote.md` (agora VERSIONADO na main) + ADR-0035 + `governance/approval/*` + front | **ADR-0035 CONSUMIDA** (V27 NÃO consumida) | **CONCLUÍDA** — grill fechado E execução landada (**PR #12 MERGED 2026-07-11**, main `745a1b7`); ver §Rodada 3 — fechamento TOTAL |
| **R3-C** | Grill — caso B (melhoria de task falhada → Arquiteto, verbatim + task corretiva ligada à `#N`) | grill (não commita) | `docs/handoff-grilling-caso-b.md` (novo) + roadmap | ADR-0034 + V26 condicionais | **CONCLUÍDA** — grill fechado E execução landada (**PR #10 MERGED 2026-07-10**; ADR-0034 + V26 **consumidas**); ver §Rodada 3 — fechamento |

**REGRA NOVA DO MATHEUS (2026-07-10, vale para todas as rodadas): máximo 2 faixas EM VOO por
vez** — "4 está ficando difícil de manter". As demais aprovadas ficam EM FILA no ledger com o
kickoff pronto; a próxima sai quando ele reportar a conclusão de uma das 2. (Memória
persistente gravada: `maximo-2-faixas-em-voo`.)

Colisão intra-rodada: zero (R3-A = skills-ceo/+DB-data; grills = handoffs novos; roadmap =
exceção trivial). Execuções FUTURAS de B e C são disjuntas entre si (governance/approval+front
× conversation/persona+work/issue) — reconferir no dia com os diffs reais.

**Ações soltas do Matheus (sem pressa, fora de faixa):** cascata parada da Demo Fixtures
(estacionar ou responder → +7 PENDING) · 27 approvals da cafeteria (sugestão: manter como massa
de teste da fatia de lote).

**NOVA CANDIDATA (rodada 4) — Absorção de issues do board pelo CEO.** Palavras do dono
(2026-07-10, ao vivo, durante o VENDO do G5 — VERBATIM): *"Não aparece a opção de eu poder
atribuí-la a ninguém e sejamos honestos isso não é problema meu — quem deve delegar é o CEO.
A ISSUE deveria ser um menu para eu observar quem está cuidando do quê. Eu posso até criar uma
issue lá, ou atribuir manualmente a algum, mas quem deveria de fato fazer isso é o CEO. Caso eu
criasse uma issue lá, o CEO deveria pegar essa issue e absorver."* Leitura: (1) a superfície de
Issues é primariamente OBSERVAÇÃO; (2) criação pelo dono no board = entrada válida, com
atribuição manual como exceção, não fluxo; (3) issue órfã criada pelo dono → o CEO ABSORVE
(triagem pelas personas → time/Arquiteto/hire). Isso SUPERSEDE o achado anterior "falta botão
de empurrar issue" — o fix não é botão, é absorção. Mecanismo provável: gatilho pós-criação →
turno do Questionador (irmão do scanner pós-settle) — mas o desenho é de GRILL futuro, não
desta nota. Toca ADR-0017/0018 e as personas da 0031.

**Candidatas anteriores (superseded pela composição acima):**
- **Grill do caso B** (melhoria de task que falhou → roteamento verbatim ao Arquiteto + task
  corretiva ligada à #N) — contrato B1/B2 já fechado; falta só o mecanismo. Era o ÚLTIMO passo
  da ordem confirmada G3 → Fatia 2 → G5 → caso B; o `#N` (pré-requisito) está vivo.
- **Frente C** (cap+graduação de memória do CEO) — destravada desde a G3, colisão zero.
- **Fatia SYNC+registro das skills** (pós-veto do sourcing): resolver os ⚠ SYNC-FATIA-2 com o
  formato confirmado + registrar os 3 SKILL.md como skill-in-DB + skillsets das personas.

## Rodada 3 — FECHAMENTO das faixas A e C (validação da sessão de controle, 2026-07-11)

Handoffs de volta da R3-A (skills das personas) e da R3-C→execução (caso B) recebidos e
reconciliados; cópia durável do caso B em
`orquestrador-agentes/scratchpad/handoff-volta-caso-b-adr0034.md`. Validação independente:

- **git/gh:** **PR #10 (caso B) MERGED 2026-07-10 23:31Z** · **PR #11 (docs skills-ceo)
  MERGED 2026-07-11T00:06Z — o Matheus revisou e mergeou APÓS o handoff da R3-A** (que ainda
  reportava "aberto aguardando review") → R3-A também 100% fechada. main local
  fast-forwarded `cb8f145` → **`ebc399d`**; branch local `docs/skills-ceo-registro` podada;
  working tree limpa com exatamente os 2 untracked de contrato (ledger · laudo). O "sumiço"
  do `docs/skills-ceo/` flagado pela R3-C está explicado: virou conteúdo TRACKED pelo merge
  do PR #11 — não foi perda.
- **Suítes re-executadas na main `ebc399d`:** backend **488/488** verdes · front **248/248**
  (20 suítes) — piso canônico carimbado na tabela de Reservas (o 489 do handoff era o
  por-surefire conhecido).
- **DB vivo (read-only):** flyway topo = **V26 aplicada** (`issue correcao`, success=t) ·
  Demo Fixtures com 5 `company_skill` (o `created:5` do handoff confere) · anexos por persona
  conferem os 12 do handoff: Fixtures Q("Sonda Docker Codex")=3 · P=2 · E=1; Teams Atlas(Q)=4
  (3 novos + `sonda-vendo-adr0032` pré-existente intocada) · P=2 · E=1.
- **:8080 vivo = main com lease + caso B** (o relançamento pós-incidente write-freeze aplicou
  a V26 no boot; pulso de lease observado vivo pela R3-A — attempt=1, `lease_expires_at`
  avançando). Nota: o "lease VENDO pendente" no handoff da R3-A era informação DEFASADA — o
  VENDO do lease já estava PASS desde 15:45 de 2026-07-10; a observação nova vale como
  reconfirmação grátis no processo novo.

**Achados registrados:**
1. 🔴 **CASO C DIRETO FURA O TRIO (candidata rodada 4, devolvida pela R3-A):** na prova viva,
   o Questionador emitiu a ```` ```proposta ```` ELE MESMO com shape inventado
   (`{"tipo":"contratacao","papel":…}`) — o formato canônico HIRE_AGENT só viaja no
   `blocoExecutor` do composePrompt, que o Q nunca vê → `ProposalScanner` descartou → **gate
   vazio (0 approvals)**. Exige código/prompt = fatia própria (fora da regra dura da R3-A,
   corretamente devolvida). Caminhos mapeados: (a) formato no blocoQuestionador ·
   (b) forçar rota Q→P→E no caso C · (c) instrução "hire → handoff ao planejador" no bloco do Q.
2. ⚠️ **Gotcha de ambiente NOVO (gravado na memória persistente pela R3-A): zumbi
   write-freeze do :8080** — JAR rebuildado sob processo vivo deixou SÓ GET respondendo
   (POST/PUT aceitos e nunca respondidos; produto inteiro sem escrita via API). SIGTERM NÃO
   mata (shutdown gracioso também quebra no classloader) → `kill -9` + relançamento pelo
   operador. Assinatura: POST em rota inexistente sem 404 + `NoClassDefFoundError` logback no
   log + threads exec ociosas. **Regra de rodada nova: faixa que builda o JAR compartilhado
   avisa o operador do restart NA HORA — senão congela as outras faixas.**
3. ℹ️ Artefatos vivos da prova R3-A em Demo Fixtures (padrão G3): conversa "Prova R3-A"
   (`f0c49f32`) + dossiê no volume `orq_b92cb312_f0c49f32`. Licenças conferidas no sourcing
   (mattpocock MIT · superpowers MIT · bencium SEM licença — risco anotado e aceito).
4. Residual D10 segue aberto — a carona do caso B registrou: run claude direto, zero
   sub-agentes/web no rastro. Continua não-gate; oportunidade em VENDO futuro.
5. Ajuste fino de prompt (achado sem ação do caso B): a prosa do opus chutou o destinatário;
   o registro determinístico corrigiu — candidata a fatia futura, não bloqueia nada.

**Rodada 3: R3-A ✔ · R3-C ✔ · R3-B EMITIDA (2026-07-11) — única faixa em voo, 1 slot livre.**
Colisão: R3-B é grill (não commita) — zero colisão possível.

**Candidatas para a rodada 4 (decisão do Matheus — NÃO emitidas):**
- **Caso C direto** (achado 1) — recomendação da sessão de controle: PRIORIZAR (gate furado
  no fluxo principal de hire, evidenciado ao vivo na prova da R3-A).
- **Absorção de issues do board pelo CEO** (palavras verbatim já registradas neste ledger).
- **Frente C** (cap+graduação de memória do CEO) — destravada desde a G3, colisão zero.
- Ajuste fino do prompt de roteamento (achado 5) — menor, pode pegar carona em outra fatia.

**Pendências que continuam com o Matheus (fora de faixa):** cascata parada da Demo Fixtures
(responder → +7 approvals, ou estacionar) · 27 approvals da cafeteria (sugestão de pé: massa
de teste da fatia de lote — o grill R3-B confirma com ele antes de qualquer limpeza).

## Rodada 3 — FECHAMENTO TOTAL (validação da sessão de controle, 2026-07-11)

Handoff de volta da R3-B recebido e reconciliado. A faixa saiu como GRILL e voltou como
**grill + execução na mesma faixa** (precedente R1-B): **PR #12 MERGED com OK do Matheus**
(2026-07-11T03:00Z). Validação independente:

- **git/gh:** PR #12 (`feat/aprovacao-em-lote`) MERGED; main local fast-forwarded `ebc399d` →
  **`745a1b7`**; branch local podada; working tree limpa com exatamente os 2 untracked de
  contrato (ledger · laudo). `docs/adr/0035-aprovacao-em-lote-kickback-recusa.md` na main ·
  `docs/handoff-grilling-aprovacao-em-lote.md` agora VERSIONADO (fonte da verdade da fatia) ·
  V26 segue a última migração no disco (**V27 NÃO consumida** — `decisionReason`, campo
  morto, virou o registro do motivo).
- **Suítes re-executadas na main `745a1b7`:** backend **497/497** verdes · front **263/263**
  (21 suítes) — novo piso canônico carimbado na tabela de Reservas (488+9 · 248+15).
- **DB vivo (read-only):** os **27 HIRE_AGENT PENDING da Teams Demo estão INTACTOS** — a
  massa da estreia do VENDO do operador foi preservada, como combinado.

**Decisões de execução registradas (fechadas com racional — NÃO reabrir):**
- Endpoint `POST /api/companies/{companyId}/approvals/batch-decision` (D10 confirmada).
- **D4 plano B (debounce do `CompanyMemoryConsolidator.refresh`) DESCARTADO POR MEDIÇÃO:**
  lote de 20 approvals de direção com refresh em série = 0,13s; 27 hires = 0,17s. Não
  reabrir sem medição nova que mostre lentidão real.
- Grill no lote NÃO devolve 422: item grelhado é PULADO (rodada contada, segue PENDING,
  resolvível na thread); o 422-com-probe segue contrato SÓ do endpoint individual.
- Kickback endereçado por `requestedByAgentId` (vale para persona E e CEO legado), turno
  PENDING+wakeup na conversa de origem; **ZERA a régua M9** por ser ação do dono
  (deliberado, registrado na ADR-0035).
- `ApprovalBatchService` deliberadamente SEM tx própria — tx por item, deny-by-default por
  item. `BadRequestException` (400) entrou no vocabulário de erros da casa.
- /verify PASS ponta a ponta na descartável (18081+pg efêmero): replay dos 27 num turno →
  lote aprovado em 0,17s com 27 agentes criados de verdade; recusa em lote cruzando 2
  conversas → UM turno síntese por conversa com motivo verbatim + observação por item,
  consumido pelo CEO; item já decidido no meio do lote pulado; probes 400/403 seguraram.

**Achados registrados (não bloqueiam):**
1. `.btn:disabled` sem estado visual no design system (botão desabilitado parece clicável;
   inerte de fato) — cosmético, candidata a fatia de UI.
2. Gotchas de verificação REGISTRADOS na skill do repo (`.claude/skills/verify/SKILL.md`, na
   main): company nova auto-semeia 3 personas → falar com CEO em instância de verify dispara
   LLM REAL (conversa determinística = update do `runtime_command` do Questionador no pg
   efêmero) · UI contra descartável = git worktree + `cp -Rc` do node_modules (symlink quebra
   o Turbopack). Contexto do gotcha: outra sessão trocou a branch do checkout compartilhado no
   meio da execução — resolvido com worktree, checkout intocado.

**★ RODADA 3 COMPLETA: R3-A ✔ (PR #11) · R3-B ✔ (PR #12) · R3-C ✔ (PR #10). ZERO faixas em
voo — os 2 slots estão LIVRES.** PRs #1–#12 todos MERGED; piso 497/263.

**Pendência ÚNICA de operador:** o `:8080` compartilhado e o front compartilhado ainda rodam
código SEM a fatia do lote — restart cirúrgico do operador (regra da casa; atenção ao gotcha
write-freeze: JAR novo sob processo vivo exige restart imediato, SIGTERM não basta →
`kill -9`). **Estreia natural pós-restart = a dor original:** os 27 HIRE_AGENT PENDING reais
da cafeteria na tela nova `/teams-demo/approvals` — filtro "contratações" → selecionar tudo →
decidir em lote.

## PLANO DE FECHO DO v1 (racionalização 2026-07-11 — PROPOSTA da sessão de controle, aguarda o Matheus)

**Diagnóstico do "andar em círculos":** o progresso é linear (PRs #1–#12 MERGED em 4 dias;
Frente A = 28 ADRs VENDO; Frente B = 100% das fatias planejadas FECHADAS; piso 375→497), mas
a lista de candidatas cresce na mesma velocidade que queima — cada VENDO pare 2-4 achados
novos e nenhuma definição de PRONTO existe. O círculo é de GERAÇÃO de escopo, não de execução.

**Definição de PRONTO proposta (v1 FECHADO) = a prova final ponta a ponta passa:** numa demo,
o dono (1) cria uma issue órfã no board → CEO ABSORVE, (2) conversa → grill → estratégia →
propostas, (3) decide contratações EM LOTE no gate, (4) agentes executam as #N, (5) dono
reclama de uma entrega → corretiva via ```` ```correcao ```` — sem furo em nenhum elo, com
handoff/memória automáticos por trás. Hoje faltam exatamente DOIS elos: o caso C direto
(bug: hire pelo Q com shape inventado → gate vazio) e a absorção de issue órfã (feature de
visão, palavras verbatim do dono já registradas).

**Ordem proposta:**
- **Fase 0 (operador + decisões, 1 sentada, sem faixa):** VENDO do lote (restart + estreia
  com os 27 — já decide os 27 de vez) · cascata parada: RESPONDER (os +7 caem na tela nova =
  2ª prova real do lote) · decisão H dos seams D5/D11 da 0019: ACEITAR COMO ESTÁ no v1.
- **Rodada 4 (2 em voo):** R4-A = caso C direto (grill curto + execução na mesma faixa,
  precedente R3-B; carona: ajuste fino do prompt de roteamento — mesmo território) ·
  R4-B = GRILL da absorção de issues (só grill — colisão zero por construção; a execução
  tocaria os mesmos arquivos de persona que o caso C).
- **Rodada 5 (fecho):** R5-A = execução da absorção · R5-B = fecho documental (roadmap
  atualizado: linha ADR-0035, piso 497/263, históricos, §Vetos respondidos, D5/D11
  carimbados) + **PROVA FINAL ponta a ponta** (o cenário acima, ao vivo, com motor claude
  numa das pernas → exercita o residual D10 de carona) + declaração v1 FECHADO no roadmap.
- **FORA do v1 (congelado):** Frente C (graduação + índice JIT — problema de escala, não de
  lançamento; D11 se resolve lá se ela entrar) · Frente D (6 itens ADIADOS) · cosméticos
  (`.btn:disabled`).

**Regra anti-círculo (vigente a partir da aprovação):** achado novo durante o fecho só vira
fatia do v1 se QUEBRAR a prova final; senão entra na Frente D sem grill e sem discussão.

**Composição da rodada 4 = decisão do Matheus (candidatas registradas, por recomendação de
prioridade da sessão de controle):**
1. **Caso C direto** (achado da R3-A): Questionador emite ```` ```proposta ```` com shape
   inventado → `ProposalScanner` descarta → gate vazio. Recomendação: PRIORIZAR — é gate
   furado no fluxo principal de hire, evidenciado ao vivo. Caminhos mapeados: (a) formato no
   blocoQuestionador · (b) forçar rota Q→P→E · (c) instrução "hire → handoff ao planejador".
2. **Absorção de issues do board pelo CEO** (palavras verbatim dele registradas acima) —
   grill próprio; toca ADR-0017/0018 + personas 0031.
3. **Frente C** (cap+graduação de memória do CEO) — destravada desde a G3, colisão zero.
4. Menores/carona: ajuste fino do prompt de roteamento (prosa chutou destinatário) ·
   `.btn:disabled` (cosmético UI).

## v1 TOTAL — decisão do Matheus (2026-07-11) + Rodada 4 emitida

**DECISÃO DO MATHEUS: v1 = TUDO que está registrado.** "Eu quero colocar tudo que temos por
enquanto em um v1, pois são coisas que sinto falta quando pego pra testar." Na pergunta da
fronteira, marcou **TODOS os itens da Frente D** (interrupção mid-run+streaming · handoff pros
executores · auto-kickback amplo · agente-como-tool · propose-then-commit de molde ·
contabilidade off-book). Congelamento vigente: a lista fecha AGORA; achado novo → v1.1, salvo
se quebrar a prova final.

**NORTE DO v1 — palavras do dono VERBATIM (2026-07-11, resposta à sessão de controle):**
> "Para mim o que deve ser o core de funcionamento do orquestrador é: CEO discutir com a
> gente a ideia de negocio ou o que é o negocio que estamos pedindo para que ele nos ajude a
> administrar (isso como primeira mensagem), ceo fazer um racional de quais cargos deveriam
> ocupar essas posicoes e entao a partir dai gerar o pedido de contratacao desses
> funcionarios (que nada mais sao do que agentes especializados), termos de fato uma
> estrutura organizacional onde cada um faz a sua parte do problema, com a unica adicao sendo
> um arquiteto como mente de cada grupo para planejar as tasks. E sendo honesto, sinto que
> nao estamos fazendo isso bem... Tanto o cloude code quanto o codex GPT 5.5 possui otimas
> ferramentas para controle de arquivo e sessao, e nao parece que estamos usando o que esses
> agentes nos proporcionam nativamente, pelo contrario sinto que estamos regredindo a
> qualidade deles. Lembre-se um agente especializado nada mais é do que um agente que tem o
> contexto daquele ponto, ou produto e possui skills que lidam com aquilo, nao deveria ser
> tao dificil."

**FATIA NOVA DE PRIMEIRA CLASSE nascida das palavras acima — AUDITORIA MOTOR-NATIVO (R4-C):**
laudo read-only (precedente R1-C do lease): inventariar o que claude code e codex oferecem
NATIVAMENTE (sessões/`--resume`, subagentes/Task, skills, hooks/MCP, controle de
arquivo/sessão) × o que o orquestrador USA, SUPRIME ou REIMPLEMENTA (AdapterRunExecutor,
adapters, AgentSessionStore, WorkspaceResolver, prompts) → lista de REGRESSÕES com evidência,
para grill com o dono. **Este laudo GATEIA o desenho das fatias da Frente D** — interrupção/
streaming, handoff-executores (pode ser SUPERSEDIDO por resume nativo) e auto-kickback se
desenham DEPOIS dele, senão construímos mais maquinário wrapper e aprofundamos a regressão
que o dono apontou. Critério permanente de todo design novo do v1: **não regredir a qualidade
nativa dos motores; a solução mais simples que fecha o furo vence.**

**Rodada 4 (emitida 2026-07-11, aprovação explícita dele):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R4-A** | Caso C direto (grill curto + execução na MESMA faixa, precedente R3-B; carona: ajuste fino do prompt de roteamento) | grill+código | **ADR-0036 CONSUMIDA** | **CONCLUÍDA** — **PR #13 MERGED (2026-07-11 18:45Z)**; trilho (c) no blocoQuestionador + cinto de emissor + cerca coluna-0 + anti-silêncio `ProposalDescarteNotice`; carona do caso B entregue; ver §Rodada 4 — fechamento |
| **R4-B** | Grill da absorção de issues → **virou grill + EXECUÇÃO na mesma faixa** (gate de colisão respeitado na prática: PR #13 landou ANTES; `blocoQuestionador` intocado pela absorção) | grill+código | **ADR-0037 + V27 CONSUMIDAS** | **CONCLUÍDA** — **PR #14 MERGED (2026-07-11 20:24Z)**, VENDO 10/10 na descartável; ver §Rodada 4 — fechamento |
| **R4-C** | Auditoria motor-nativo (laudo read-only, não commita) | laudo | — | **CONCLUÍDA (2026-07-11)** — `docs/laudo-motor-nativo.md` (521 linhas, untracked) entregue; contrato read-only cumprido; ver §Rodada 4 — fechamento da R4-C. **EVIDÊNCIA JÁ LEVANTADA** por auditoria externa — quem pegar a faixa COMEÇA por `docs/handoff-auditoria-doc-codigo-2026-07-11.md` §"Para a R4-C": sessão nativa NUNCA continuada no caminho normal (`AdapterRunExecutor.java:252-255` passa `priorSession=null`; `ClaudeArgv`/`CodexArgv` só emitem `--resume` se preenchido; único consumidor do handle = resume-cirúrgico `ContextHandoffLoop.java:83-106`; plumbing `agent_session`/V16 existe e funciona); contra-exemplo positivo (não-regressão) = `WorkspaceConvention` ADR-0032; múltiplos ADRs marcam o religamento como "fatia futura" deliberada. Complemento: rodar passada suplementar do diff `2ab8eac..main` (PRs #11/#12 fora do snapshot auditado) |

Colisão intra-rodada: zero (R4-A toca prompts/scanner; R4-B e R4-C não commitam). A colisão
R4-A × execução-da-absorção está tratada por GATE no kickoff que a R4-B vai produzir.

**Ordem geral do fecho do v1 (registrada; recalculada a cada handoff de volta):**
Fase 0 operador (VENDO lote + cascata) → **R4** (caso C · grill absorção; fila: auditoria) →
**R5** (exec absorção · auditoria motor-nativo) → **R6+** fatias da Frente D NA ORDEM QUE O
LAUDO MANDAR (interrupção/streaming primeiro por dor de teste; handoff-executores e
auto-kickback condicionados ao laudo; os 3 de fundo por último) → **Frente C** (graduação +
índice JIT + writers D5/D11 — os seams mortos da 0019 se resolvem AQUI, não por aceite) →
**fecho documental** (roadmap: ADR-0035+, piso, §Vetos stale) → **PROVA FINAL ponta a ponta**
(cria issue órfã→absorve→grill→estratégia→hire em LOTE→executa #N→reclama→corretiva; perna
com motor claude exercita o D10) → **v1 FECHADO**. Estimativa honesta no ritmo atual:
**~10–14 fatias ≈ 2–3 semanas** (o corte enxuto seria 2-3 dias; a decisão do escopo total é
dele e está registrada).

## Insumo externo — auditoria doc×código 2026-07-11 (recebido e TRIADO, 2026-07-11)

Handoff: `docs/handoff-auditoria-doc-codigo-2026-07-11.md` (sessão separada, fora do ledger:
63 auditores + verificação cética dupla por achado; snapshot `2ab8eac` = main+ADR-0034,
ANTERIOR aos PRs #11/#12). Artifact completo com as 63 fichas:
https://claude.ai/code/artifact/999b266f-208a-4502-a499-e841a0b17e27
**Headline: ZERO divergência ALTA** — 26 MÉDIA (12 confirmadas + 14 incertas) + 94 BAIXA
cosméticas. Vereditos já fecharam por refutação adversarial — NÃO re-verificar. Triagem da
sessão de controle:

1. **Evidência da R4-C RELAYADA** — anotada no card da R4-C (tabela da rodada 4, acima). O
   padrão central da "regressão de qualidade nativa" já está levantado com file:line; quem
   pegar a faixa parte dele, não do zero.
2. **Os 26 MÉDIA entram no FECHO DOCUMENTAL como estão** (decisão de triagem — nenhum quebra
   a prova final, nenhum pede faixa própria). O passo "fecho documental" da ordem geral passa
   a incluir EXPLICITAMENTE: a lista MÉDIA do handoff (ADR-0004 ×4 · 0006 · 0009 ×2 · 0011 ·
   0013 · 0017 ×2 · 0018 · 0024+grill·plano-codex (superseded pela 0032, emendar texto) ·
   0026 · grills approval/loop-handoff/7c/motores/skills-knowall · §Vetos stale do roadmap ·
   `ActorContext` sem menção nominal) + **ADR-0020/D12 = só reconciliação de texto** apontando
   para a Frente C (nota: a premissa "Frente C fora do v1" do handoff ficou DEFASADA pela
   decisão v1 TOTAL — ela está DENTRO, tier tardio; a conclusão não muda: é texto, não
   pergunta) + **passada suplementar do diff `2ab8eac..main`** (PRs #11/#12 não auditados —
   recomendação do próprio handoff).
3. **EXCEÇÃO que sobe para candidata PRÓPRIA — os 3 tetos de escalação** (grill·teams-fechado,
   o mais substantivo dos incertos): só o teto de gasto existe
   (`BudgetPolicy.orchestrationLimitCents`); contador de riscos-do-plano e circuit-breaker de
   erro repetido NÃO existem no backend. É lacuna de GOVERNANÇA real, não drift de texto.
   Chegou junto do congelamento do v1 e não quebra a prova final → pela regra anti-círculo é
   **candidata v1.1, SALVO palavra do Matheus em contrário** (registrada aqui para a decisão
   dele, não decidida).
4. **Achados de memória → anexar ao grill da Frente C quando ela voar:** D5 (review não
   fabrica Decision — já mapeado na Frente C pelo plano) + **D8 novo** (RacionalPolicy aplica
   o MESMO regime de commit a Lógico e Intuição; a distinção ficou só na mensagem do probe —
   `RacionalPolicy.java:56-70`).
5. **Decisões genuinamente em aberto do handoff NÃO decididas agora** (CEO criar molde
   ADR-0017 · re-pin de molde sem chamador · feedback do revisor→Issue ADR-0011): serão
   apresentadas ao Matheus como lista H curta no fecho documental, com recomendação.

## Rodada 4 — FECHAMENTO das faixas A e B (validação da sessão de controle, 2026-07-11)

Handoffs de volta da R4-A (caso C) e R4-B (absorção) recebidos e reconciliados. Cópias
duráveis: `orquestrador-agentes/scratchpad/handoff-r4a-caso-c-adr0036.md` +
`handoff-volta-absorcao-adr0037.md`; §RESULTADO no `handoff-grilling-absorcao-issues.md`
(commitado no PR #14). A R4-B saiu como grill e voltou como **grill + execução na mesma
faixa** (precedentes R1-B/R3-B) — o gate de colisão foi respeitado na prática (PR #13 às
18:45Z, PR #14 às 20:24Z; `blocoQuestionador` intocado pela absorção). Validação independente:

- **git/gh:** PRs **#13** e **#14** MERGED; main local fast-forwarded → **`713a3f7`**;
  **8 branches locais residuais podadas** (todas mergeadas — inclui sobras antigas desde a
  ADR-0030); `0036-…` + `0037-…` + `V27__issue_conversa.sql` na main; working tree limpa com
  exatamente os 3 untracked de contrato (ledger · laudo do lease · handoff da auditoria).
- **Suítes re-executadas na main `713a3f7`:** backend **523/523** · front **270/270**
  (21 suítes) — novo piso canônico na tabela de Reservas (497+6+20 · 263+7; o pré-fatia
  503/263 re-medido pela R4-B bateu exato).
- **DB vivo:** intocado por estas faixas (VENDOs 100% na descartável; **V27 ainda NÃO
  aplicada no :8080** — aplica no boot do restart do operador).

**Decisões registradas (fechadas com racional nos handoffs — NÃO reabrir):**
- **R4-A:** trilho do caso C no blocoQuestionador (caminho (c); pedido explícito JÁ É o "sim"
  do D5; rotas (a)/(b) rejeitadas no grill) · cinto de emissor (proposta→Approval só
  Executor/CEO legado; espelho do `correcao`) · "cerca só em COLUNA 0" nos DOIS scanners
  (furo latente real fechado: o exemplo do blocoExecutor é JSON válido; residual aceito:
  proposta indentada invisível) · anti-silêncio `ProposalDescarteNotice` (ADR-0036 = emenda
  anti-silêncio à 0018; régua M9 PULA o aviso — senão girava) · carona do caso B (prosa do Q
  não nomeia destinatário).
- **R4-B:** bloco ```` ```absorcao ```` = 4º irmão (emissor SÓ Planejador; dominio/foco
  separados; racional OBRIGATÓRIO → vira evidencia da Decision AGENTE) · issue do dono =
  conversa direcionada "#N — <título>" (V27) + gatilho sentinela + wakeup do Planejador ·
  `tryAbsorver` guardado (issue segue OPEN na mesa do arquiteto, SEM wakeup dele) · 2ª porta
  fechada na origem (CREATE_ISSUE nasce com dono; 409 acionável sem time) · gate ganhou
  **CREATE_TEAM atômico (4ª ação)** com retoma automática pós-approve · divergências mínimas
  registradas: prompt do EXECUTOR ganhou o formato CREATE_TEAM (lição do caso C), avisos M6
  colapsados num único, vetos mergeados como propostos (reversíveis) · achado vivo → fix
  `3c5f686` (mapeamento `#### <dominio>/<foco>` no prompt; `splitReply` descasca os 4 irmãos
  — o do caso B vazava cru desde a 0034).

**Achados / pendências da rodada (triados):**
1. **Sync do corpo da skill `being-ceo` no DB (2 demos)** com o trilho do caso C — operação de
   DADO, não bloqueia (contrato inline cobre); **carona da próxima faixa que tocar
   skills/dados**.
2. **Veto de redação** dos textos novos (aviso/trilho do caso C · M6 da absorção · assunto
   "#N — <título>" · card CREATE_TEAM) — decisão H dele, tudo reversível, sem pressa.
3. **GRILL DO ARQUITETO — candidata NOVA** (semente verbatim intacta no §G.1 do
   `handoff-grilling-absorcao-issues.md`: invariante de nascimento na porta manual ·
   `setArquiteto(null)` · multi-arquiteto · divisão em 2 personas · clone por ociosidade
   >30min). Chegou PÓS-congelamento → pela regra anti-círculo = **v1.1 SALVO palavra do
   Matheus** — mas toca o núcleo do norte dele ("arquiteto como mente de cada grupo"), então a
   decisão é explicitamente DELE, não do default.
4. Observação de campo (dado, não bug): pós-absorção, as personas claude CONTINUARAM o fluxo
   autônomo (Executor propôs 2 HIRE_AGENT) — comportamento ADR-0031 esperado; candidato a
   grill de calibração de autonomia SE incomodar o dono.
5. **Operador CONSOLIDADO (1 sentada):** o :8080 está **fora do ar** (estava desligado na
   R4-A; sem write-freeze) e o JAR vivo anterior não tem lote/caso C/absorção. **Religar com a
   main `713a3f7` ativa TUDO de uma vez** (V27 aplica no boot). Estreia natural: (a) tela
   `/teams-demo/approvals` com os 27 PENDING em lote · (b) cascata parada (+7 no lote) ·
   (c) criar issue órfã em Demo Fixtures e VER a absorção viva · (d) faxina M12 da company
   ilegal `thumb-demo-9368` (`scratchpad/absorcao-faxina-m12-auditoria.sh`, DELETE comentado,
   gotcha do trigger append-only).

**Rodada 4: A ✔ · B ✔ · C ✔ — RODADA 4 COMPLETA.**

## Rodada 4 — FECHAMENTO da R4-C + decisões de fila + Rodada 5 (2026-07-11)

Handoff de volta da R4-C recebido e reconciliado (cópia durável:
`orquestrador-agentes/scratchpad/handoff-r4c-laudo-motor-nativo.md`). Validação da sessão de
controle: laudo presente com a estrutura prometida (§0 sumário · §1 inventário 10 áreas × 2
motores · §2 seis regressões · §3 implicações Frente D · §4 grill G1–G6 · §5 fora-do-laudo ·
apêndices A/B); working tree = 3 untracked de contrato + o laudo novo; âncora do R6 conferida
no código (`CodexArgv.java:43-48` emite flag `--resume`; o comentário admite "best-effort").

**Conclusão do laudo (uma frase):** o sentimento do dono está CONFIRMADO e é DESIGN, não
descuido — os CLIs rodam como funções puras prompt→texto re-executadas do zero a cada turno;
memória/continuidade/estrutura/roteamento foram reimplementados por fora, enquanto as versões
pinadas (claude 2.1.201 · codex 0.142.5, source-verified no tag) já dão quase tudo nativo.

**Achados que mudam decisões:**
1. **BUG LATENTE R6:** `CodexArgv` emite flag `--resume`, mas a 0.142.5 só aceita o
   subcomando `codex exec resume <id>` → o resume-cirúrgico do handoff (ADR-0030) DEVE falhar
   em CEO codex e o fail-open engole em silêncio. Evidência: `CodexArgv.java:43-48` vs
   `codex-rs/exec/src/cli.rs` @ rust-v0.142.5.
2. **SEGURANÇA (vale hoje):** JSONLs de sessão de TODOS os tenants no mesmo home-volume do
   motor, legíveis pelo modelo via Bash (laudo §1.1-d; consequência do home-como-volume,
   ADR-0016). Risco real só em multi-tenant de verdade (hoje = só o dono) — vira G7 do grill.
3. As flags que ENDURECEM o caso C já existem nas versões pinadas: codex `--output-schema`
   (tag-verified) · claude `--json-schema` (gotcha: schema inválido ignorado em silêncio antes
   da 2.1.205 — validar no backend). Alimenta o G2.
4. Defesa do pino de versão confirmada pela doc oficial (`--bare` "vai virar default do -p" —
   risco da ADR-0014 é real). → nota no fecho documental.

**Gate da Frente D DESTRAVADO (laudo §3):** interrupção = transporte de stream que JÁ é vivo +
kill+resume nativo (steering: claude `--input-format stream-json` · codex app-server
turn/steer+interrupt, experimental) · handoff-executores = candidata a SUPERSEDIDA por resume
nativo · auto-kickback e propose-then-commit = depois da saída estruturada (G2) ·
agente-como-tool = codex já dá pronto (`codex mcp-server`, continuidade por threadId) ·
contabilidade off-book = começa pela sonda G5 (usage inclui subagentes?).

**DECISÕES DE FILA da sessão de controle (2026-07-11):**
1. **Laudo fica UNTRACKED até o grill** (consistente com o contrato das faixas-laudo; o
   precedente real do laudo do lease é ficar untracked — a emenda/execução derivada é quem
   carrega a essência para docs versionados). O fecho documental ou a 1ª fatia derivada o
   commita se fizer sentido lá.
2. **Grill do laudo ENFILEIRADO E EMITIDO → R5-A** (G1–G6 do laudo + G7 segurança
   home-volume). Produto = decisões Dn; a EMISSÃO das fatias derivadas volta para a sessão de
   controle (múltiplas fatias sairão — a fila é daqui).
3. **R6 = FATIA PRÓPRIA PEQUENA → R5-B, emitida JÁ em paralelo** — é bug shippado (ADR-0030)
   e pré-requisito de G1/G3 no codex; zero colisão com o grill (que não commita). Não espera
   decisão do grill: a gramática correta é necessária em qualquer cenário.
4. **Segurança home-volume registrada** → G7 no grill (aceitar-com-nota × fatia de
   isolamento) + nota na ADR-0016 no fecho documental.

**Rodada 5 (emitida 2026-07-11):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R5-A** | Grill do laudo motor-nativo (G1–G7, com o Matheus) — decisões que REORDENAM a Frente D | grill (não commita) | — | **CONCLUÍDA (2026-07-11)** — D1–D7 FECHADAS; produto = `docs/handoff-grilling-motor-nativo.md` (untracked; 1ª execução derivada commita); ver §Rodada 5 — fechamento |
| **R5-B** | Fix R6 — gramática real do resume codex (`codex exec resume <id>`) + anti-silêncio WARN + nota de implementação na ADR-0030 | código cirúrgico | — (sem migração; emenda 0030) | **CONCLUÍDA** — **PR #15 MERGED (2026-07-11, main `470b697`)**; gramática pinada por source do tag + smoke no binário 0.142.5 do host; ver §Rodada 5 — fechamento |

Colisão intra-rodada: zero (R5-A não commita; R5-B toca `CodexArgv` + teste + ADR-0030 —
nenhum outro trabalho em voo). Frente C (grill) e grill do Arquiteto seguem aguardando slot +
palavra do Matheus. 2-em-voo: 2/2 ocupados.

## Rodada 5 — FECHAMENTO (validação da sessão de controle, 2026-07-11) + Rodada 6 emitida

Handoffs de volta da R5-A (grill do laudo) e R5-B (fix R6) recebidos e reconciliados. Cópias
duráveis no `orquestrador-agentes/scratchpad/` (`handoff-r5a-grill-motor-nativo.md` ·
`handoff-volta-r5b-codex-resume.md`). Validação independente:

- **git/gh:** **PR #15 MERGED** (`fix/codex-resume-gramatica` → main **`470b697`**); main
  local fast-forwarded; branch podada. Working tree = os 4 untracked de contrato +
  `docs/handoff-grilling-motor-nativo.md` (produto da R5-A, untracked como o contrato manda).
- **Suítes re-executadas na main `470b697`:** backend **526/526** · front **270/270** — piso
  canônico carimbado (523+3).
- **R5-B acima do pedido:** gramática pinada por SOURCE do tag rust-v0.142.5 (Command::Resume
  é subcomando, `-s` não é global → flags ANTES do subcomando) + **smoke live no binário
  0.142.5 do HOST** (forma antiga rejeitada pelo clap; forma nova atravessa o parser). O
  silêncio real estava no `surgicalDistill` descartando o `Result.fail` — agora WARN,
  fail-open intocado. Residual registrado na nota da ADR-0030: prova e2e do loop com CEO
  codex real fica de carona para uso real.
- **R5-A contrato cumprido:** zero código, zero commit; G1–G7 → **D1–D7 fechadas com o
  Matheus** (racional completo no handoff do grill — fonte da verdade; NÃO re-discutir).

**D1–D7 em uma linha cada (registro; racional no handoff):** D1 continuidade nativa POR
CONVERSA + dieta do composePrompt na MESMA fatia (ADR-0030 vira rede) · D2 schema nativo em
TODOS os turnos, 2 etapas dentro da fatia (gotcha claude <2.1.205 validado no backend) · D3
resume por ISSUE nos executores — fatia "handoff pros executores" MORTA supersedida; bilhete
só p/ troca de agente; fallback barulhento · D4 interrupção = kill+resume + SSE; sonda
kill-mid-tool-call ABRE a fatia; steering/processo-vivo = v1.1 · D5 sonda usage×subagentes
ANTES de desenhar off-book · D6 enforcement mínimo EMBUTIDO como requisito de aceite ("fatia
que amplia autonomia não shippa sem o teto") · D7 home-volume ACEITO no v1 com nota-GATE
BLOQUEANTE na ADR-0016 (nenhum tenant externo antes da fatia de isolamento) + cinto PreToolUse
de carona.

**Frente D reordenada (handoff §2 — carimbado):** handoff-pros-executores **MORTA** (D3) ·
interrupção/streaming e off-book **MUDAM de desenho** (D4/D5) · auto-kickback e
propose-then-commit **resequenciadas pós-F-N3** (D2 primeiro) · agente-como-tool **segue com
nota** (grill próprio começa por subagentes nativos × `codex mcp-server`).

**Notas para o FECHO DOCUMENTAL (acumuladas):** nota-GATE na ADR-0016 (D7, bloqueante) ·
defesa do pino/`--bare` (R4-C) · candidatas v1.1 novas do §5 do grill: ID sequencial de
conversa (fluxo verbatim do dono no §0) · processo-vivo/steering (gate: app-server sair de
experimental) · enforcement amplo · isolamento do home-volume (com rascunho de desenho no D7).

**Rodada 6 (emitida 2026-07-11):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R6-A** | **F-N1 + F-N2 na MESMA faixa** (recomendação do grill): continuidade nativa por conversa (D1) + dieta do composePrompt + resume por Issue nos executores (D3) + tetos D6 embutidos + fallback barulhento; **1ª execução derivada → COMMITA o handoff do grill** (e avalia commitar o laudo); F-N6 (hook PreToolUse) = carona SE couber | código (/tdd + /verify) | **ADR-0038 CONSUMIDA** · V28 liberada sem consumo | **CONCLUÍDA** — **PR #16 MERGED (2026-07-11, main `a62c4e6`)** após VENDO live 4/4 com CLIs REAIS; dever especial cumprido (handoff do grill + laudo motor-nativo COMMITADOS); **F-N6 DEVOLVIDA como fatia dedicada**; ver §Rodada 6 — fechamento |
| **R6-B** | **Sondas nativas** (F-N5 usage×subagentes + sonda kill-mid-tool-call que abre a F-N4 — o grill sancionou adiantá-la em paralelo) | sondas read-only (laudo curto, não commita) | — | **CONCLUÍDA (2026-07-11)** — `docs/laudo-sondas-nativas.md` (160 linhas, untracked; 1ª fatia derivada DAS SONDAS commita — F-N4 ou F-N5); vereditos D5/D4 empíricos; ver §Rodada 6 — fechamento |

Colisão intra-rodada: **zero por construção** — R6-B dirige os CLIs DIRETAMENTE (host tem
codex 0.142.5; molde S2 da G3), sem tocar produto; instruída a NÃO usar a porta 18081 (o
/verify da R6-A usa) — se precisar de instância, 18082 + pg efêmero próprio. Sequência
restante (da matriz §3): F-N3 (pós R6-A) → F-N4 (pós F-N3; sonda já adiantada) → F-N6 se não
couber de carona → resequenciadas da Frente D → Frente C → fecho documental → prova final.
Frente C e grill do Arquiteto: seguem aguardando slot + palavra do Matheus. 2-em-voo: 2/2.

## Rodada 6 — FECHAMENTO (validação da sessão de controle, 2026-07-11) + Rodada 7

Handoffs de volta da R6-A (F-N1+F-N2) e R6-B (sondas) recebidos e reconciliados. Validação:

- **git/gh:** **PR #16 MERGED** (o Matheus revisou e mergeou APÓS o handoff — precedente PR
  #11); main local fast-forwarded → **`a62c4e6`**; branch podada. **Dever especial CUMPRIDO:**
  `docs/adr/0038-continuidade-nativa-de-sessao.md` + `docs/handoff-grilling-motor-nativo.md`
  + `docs/laudo-motor-nativo.md` agora TRACKED na main. Working tree = 4 untracked de
  contrato (ledger · laudo-lease · handoff-auditoria · **laudo-sondas** — este último entra
  no commit da 1ª fatia derivada das sondas: F-N4 ou F-N5).
- **Suítes re-executadas na main `a62c4e6`:** backend **547/547** · front **270/270** — piso
  canônico carimbado (526+21). **V28 liberada sem consumo** (V27 bastou).
- **VENDO da R6-A (4/4, CLIs reais claude 2.1.206 + codex 0.142.5):** memória viva em 3
  turnos com prompts dietados **60% menores** · teto codex `agents.max_threads/max_depth`
  ACEITO (6/3) · retry preservou handle · fallback `SessionLostSignal` casou o erro real
  "No conversation found" ponta a ponta. **A regressão-mãe R1 do laudo está MORTA.**

**Registros da R6-A (decisões no caminho):** dial de compactação nativa AVALIADO e não
baixado cego (empilharia 2 mecanismos a 55%) → knob fiado **default-off**, pendente de sonda
empírica (aberto pequeno, registrado) · gotcha: só UUID-inexistente dá "No conversation
found" (handle é sempre UUID — coberto) · **F-N6 DEVOLVIDA como fatia dedicada** (hook
PreToolUse = subsistema de materialização de hooks próprio + gotcha trust do codex
`--dangerously-bypass-hook-trust`; não coube como carona).

**Vereditos empíricos da R6-B (carimbados — alimentam os kickoffs de F-N5 e F-N4):**
1. **Sonda 1 (D5): NENHUM motor inclui subagentes no `usage`** — e o caminho de captura é
   ASSIMÉTRICO: **claude** = cada subagente emite `task_notification.usage.total_tokens` JÁ
   no stream atual (provado sem flag) → fatia off-book MÍNIMA: somar no `ClaudeStreamParser`;
   **codex** = subagente é thread com session JSONL próprio; stream do parent não traz token
   dos filhos → seguir `receiver_thread_ids` + ler rollouts em `~/.codex/sessions` (fatia
   MAIOR); OTEL só com collector (fora — D5 sem infra). **Carona D6 confirmada por MEDIÇÃO:**
   run multi-agente codex = 96k input de overhead — teto é requisito real. Bônus: codex
   `multi_agent` = **stable** (`spawn_agent`/`wait`/`close_agent` usáveis) → insumo direto
   para o grill do agente-como-tool.
2. **Sonda 2 (D4): kill+resume SÃO nos DOIS motores** (exit 0; JSONL sobrevive; turnos
   completos preservados) — **D4 se sustenta, sem checkpoint próprio**. **REQUISITO DURO NOVO
   da F-N4:** o interrupt mata o **PROCESS GROUP**, não só o PID (o filho da tool sobreviveu
   ÓRFÃO ao SIGKILL nos 2 motores — o "parar" vazaria build/subprocess). Gotchas: `codex
   exec` TRAVA lendo stdin sem `< /dev/null` (conferir ProcessBuilder) · granularidade
   assimétrica (claude DESCARTA o turno interrompido; codex PRESERVA) — informa o texto do
   prompt de retomada, não muda o desenho.
3. Versões: host claude = **2.1.206** (pino da imagem era 2.1.201) — para F-N3, conferir a
   versão PINADA na imagem do EngineContainer (gotcha do schema silencioso <2.1.205); bump de
   pino, se proposto, vai com racional no PR (defesa ADR-0014).

**Gotcha OPERADOR atualizado:** a R6-A rebuildou o JAR compartilhado a partir da BRANCH para
o VENDO — ao religar o :8080, **rebuildar do checkout na MAIN (`a62c4e6`)** antes (conteúdo
pós-merge é igual, mas a regra limpa é buildar da main).

**Rodada 7 (emitida 2026-07-11):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R7-A** | **F-N3 — saída estruturada nativa (D2), 2 etapas** (schema nos entregáveis → conversa `{resposta, blocos[]}`; etapa 2 com VENDO junto do dono) | código (/tdd + /verify) | **ADR-0039 consumida NA BRANCH** | **EXECUTADA (2026-07-12)** — branch local `feat/saida-estruturada-adr0039` (`7a7d08f`), **NÃO pushada** (contrato "PR só com OK" respeitado); suíte **568/568** re-executada pela sessão de controle na branch; **pende OK do Matheus** (push+PR+merge) + VENDO final da prosa; ver §Rodada 7 |
| *(slot 2)* | LIVRE — F-N4/F-N5/F-N6 colidem com F-N3 (parsers/argv/executor) → sequenciais; candidatas paralelas sem colisão: **grill da Frente C** e **grill do Arquiteto** (ambos aguardam palavra do Matheus) | — | — | — |

Sequência restante: F-N3 → F-N5 (desenho gateado pela sonda 1) e/ou F-N4 (com o requisito
duro novo) → F-N6 (fatia dedicada) → resequenciadas da Frente D (auto-kickback ·
propose-then-commit · agente-como-tool com grill próprio · off-book) → Frente C → fecho
documental (notas acumuladas: gate ADR-0016 · pino/`--bare` · knob de compactação) → prova
final ponta a ponta → v1 FECHADO.

## Rodada 7 — R7-A EXECUTADA, aguardando OK do dono (2026-07-12)

Handoff de volta da R7-A recebido e reconciliado (cópia durável:
`orquestrador-agentes/scratchpad/handoff-volta-f-n3-saida-estruturada.md`). Validação da
sessão de controle: branch `7a7d08f` sobre `a62c4e6`, **zero push** (contrato respeitado); 22
arquivos, +1079/−34; ADR-0039 na branch (forward-refs 0018/0031/0036/0038); **suíte 568/568
re-executada pela sessão de controle NA BRANCH** (547+21); front 270 intocado; os 4 untracked
de contrato intocados; main segue `a62c4e6` — **piso canônico continua 547/270 até o merge**.

**Decisões de implementação registradas (dentro do D2, não re-plano):**
1. **Schema POR-PERSONA** (anyOf só dos blocos que a persona emite) → o caso C vira
   ESTRUTURALMENTE impossível (o schema do Questionador nem contém `proposta`).
2. Claude permaneceu em `stream-json` (o JSON validado volta no `result`; StepTrace zero
   regressão) — não trocou para `--output-format json`.
3. Prompt schema-aware ADITIVO (remapeia "fence → item de `blocos[]`" sem reescrever a prosa
   semântica das personas).
4. Etapa 2 atrás da flag `orquestrador.saida-estruturada.conversa-nativa` (**default ON**) =
   recuo informado MECANIZADO, provado cirúrgico.
5. **Pino claude 2.1.201 MANTIDO** (sem bump): 2 cintos no backend (`requireWellFormed` na
   ida + malformado→WARN+fallback regex na volta) cobrem a gotcha <2.1.205; bump = decisão do
   dono no merge (recomendação da sessão de controle: OK SEM bump — carona no fecho
   documental ou fatia de infra).

**VENDO live (descartável, CLIs reais, modo LOCAL = o caminho do v1):** prosa do Q excelente
nos 2 motores (reply = campo `resposta` limpo) · belt barulhento provado (WARN + fallback,
nunca silêncio) · shape inválido IMPOSSÍVEL. Recomendação da execução: **fechar etapa 2 ON**.

**FOLLOW-UP CARIMBADO (candidata registrada):** codex sob DOCKER — o schema-arquivo
`.orq/turn-schema.json` não chega ao volume do container → falha **LOUD** (não silenciosa);
claude inline funciona nos 2 modos; o v1 roda LOCAL (provado). Fatia pequena de transporte
quando Docker voltar ao foco.

**DESTRAVA (gated no merge desta fatia):** F-N4 (interrupção+SSE, com o requisito duro do
process group) e F-N5 (off-book, desenho da sonda 1). Slot 2 segue livre.

**Pendências do Matheus:** (a) ~~OK para push+PR+merge da F-N3~~ **OK DADO (2026-07-12,
"1ok") → a sessão de controle executou push + PR #17 + merge (main `837e365`); branches local
e remota podadas; sem bump do pino (como recomendado)** · (b) VENDO da prosa da etapa 2 com
ele olhando (pós-restart do :8080; recuo = flag `conversa-nativa=false`) · (c) operador:
**rebuild do JAR da MAIN (`837e365`) ao religar** · (d) Frente C / grill do Arquiteto.

## Rodada 8 — emitida (2026-07-12, pós-merge do PR #17)

O merge da F-N3 destravou as duas fatias que colidiam nos parsers. Emissão dupla com
DEMARCAÇÃO DE ARQUIVOS explícita (colisão residual = `AdapterRunExecutor`, tratada por regra):

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R8-A** | **F-N4 — interrupção mid-run + SSE (D4)**: kill de PROCESS GROUP (requisito duro da sonda 2) → estado INTERRUPTED → retomada com a correção do dono na MESMA sessão nativa; SSE dos steps + front (parar + stream vivo) | código (/tdd + /verify) | **ADR-0040 CONSUMIDA** · V29 liberada | **CONCLUÍDA** — **PR #19 MERGED (2026-07-12)**; rodou em WORKTREE próprio; 2 gaps de VENDO viraram follow-up com o merge dele (claude zero-órfãos ao vivo · resume 3-turnos — rate-limit, risco baixo); ver §Rodada 8 — fechamento |
| **R8-B** | **F-N5 — contabilidade off-book (D5)**: desenho gateado pela sonda 1 — claude = somar `task_notification.usage.total_tokens` no parser (mínima) · codex = side-channel de rollouts dos filhos (`receiver_thread_ids` → `~/.codex/sessions`); **DEVER ESPECIAL: commita `docs/laudo-sondas-nativas.md`** | código (/tdd + /verify) | **ADR-0041 + V28 CONSUMIDAS** | **CONCLUÍDA** — **PR #18 MERGED (2026-07-12)**; dever especial cumprido (laudo-sondas TRACKED); VENDO live 2 motores REAIS (27.557 tokens off-book claude · 70.430/43 codex via side-channel; belt fail-soft provado); ver §Rodada 8 — fechamento |

**Demarcação (regra dura, nos 2 kickoffs):** R8-A NÃO toca
`ClaudeStreamParser`/`CodexStreamParser` (domínio da R8-B); R8-B NÃO toca
`ExecutionTarget`/`EngineContainer`/`RunStepsController`/lifecycle de run/front de controle
(domínio da R8-A). `AdapterRunExecutor`: R8-A pode tocar (caminho do kill); R8-B EVITA — se
inevitável, hunk mínimo + aviso no handoff (conflito trivial no rebase). Precisar do arquivo
do outro → PARAR e voltar ao controle. Sequência restante pós-rodada 8: F-N6 (hooks,
dedicada) → resequenciadas da Frente D (auto-kickback · propose-then-commit ·
agente-como-tool com grill próprio) → Frente C → fecho documental → prova final → v1 FECHADO.

## Rodada 8 — FECHAMENTO (validação da sessão de controle, 2026-07-12)

Handoffs de volta das duas faixas recebidos com PRs ABERTOS pendendo OK — e **o Matheus
mergeou AMBOS na sequência** (#18 às vésperas do #19; precedente #11/#16). Com o merge, a
decisão dos 2 gaps de VENDO da F-N4 ficou tomada: **follow-up, não bloqueio**. Validação:

- **git/gh:** **PR #18 (F-N5) e PR #19 (F-N4) MERGED**; main local fast-forwarded →
  **`a04ed6b`**; branches locais/remotas podadas; **worktree `agente-orquestrator-interrupcao/`
  REMOVIDO** (handoff durável da R8-A copiado antes para
  `orquestrador-agentes/scratchpad/handoff-r8a-fn4-interrupcao-mid-run.md`; o da F-N5 já
  estava: `handoff-volta-f-n5-offbook-adr0041.md`). `docs/laudo-sondas-nativas.md` TRACKED
  (dever especial da F-N5 ✔). Working tree = **3 untracked de contrato** (ledger ·
  laudo-lease · handoff-auditoria).
- **Suítes re-executadas na main `a04ed6b`:** backend **605/605** · front **280/280** — piso
  canônico carimbado.
- **V27 + V28 ainda NÃO aplicadas no :8080** (fora do ar / JAR antigo) — aplicam no boot do
  restart do operador.

**REGRA NOVA DE RODADA (do incidente da R8-A):** quando 2 faixas de CÓDIGO correm em
paralelo, a segunda trabalha em **git worktree próprio** — um `git checkout -b` no checkout
compartilhado quase trocou a branch sob a outra faixa (pego a tempo, zero perda). O worktree
da R8-A provou o modelo; a skill `/verify` do repo já tem a receita de worktree + `cp -Rc` do
node_modules.

**Incidente registrado (inócuo):** V27 aplicada sem querer no Postgres compartilhado durante
setup de VENDO da R8-A — processo morto em ~1min, zero dado escrito, V27 já era parte da main.

**Achados / follow-ups (nenhum bloqueante):**
1. **VENDO-carona da F-N4** (dos 2 gaps): claude zero-órfãos ao vivo + resume de 3 turnos
   (completo→interrompido→correção) — provar no ambiente vivo pós-restart (rate-limit impediu
   na descartável; mecanismo agnóstico de adapter provado com process real + codex real).
2. **F-N5:** exclusão do off-book no `HandoffThresholdDetector` validada só por unitário
   (VENDO cruzando o limiar de verdade fica de carona) · side-channel codex só funciona
   LOCAL (Docker cai no fail-soft) — **mesma família do schema-arquivo da F-N3 → candidata
   ÚNICA "paridade Docker" (transporte de arquivo + visibilidade de volume)**.
3. **`CompanyScopeInterceptor` loga falso-positivo no endpoint SSE** (ThreadLocal × async
   re-dispatch) — proteção real intacta e testada; candidata a fatia se mais endpoints async
   nascerem.

**RODADA 8 COMPLETA. PRs #1–#19 todos MERGED. Zero faixas em voo — 2 slots livres.**

**Rodada 9 — COMPOSIÇÃO RECOMENDADA (a emissão é da próxima sessão de controle, após
validar):** R9-A = **F-N6** (hook PreToolUse mínimo: skills read-only D6 + cinto de sessão
D7; materialização de hooks `settings.json`/`hooks.json`; gotcha trust codex
`--dangerously-bypass-hook-trust`) · R9-B = **auto-kickback amplo** (resequenciada,
DESTRAVADA pela F-N3: "entregável detectado" agora é campo validado de `blocos[]` — kickback
vira trivial). Colisão R9-A×R9-B: baixa (materialização/argv × persona/coordinator) — validar
com demarcação no dia; 2ª faixa de código em WORKTREE (regra nova). Frente C (grill) e grill
do Arquiteto continuam à espera da palavra do Matheus — encaixam quando ele tiver banda de
grill (as duas fatias da R9 são trabalho de agente).

**Sequência restante do v1 (recalculada):** rodada 9 (F-N6 + auto-kickback) →
propose-then-commit de molde (pós-F-N3, destravada) → agente-como-tool (grill próprio;
insumo: `codex mcp-server` + `multi_agent` stable provado) → **Frente C** (memória: graduação
+ índice JIT + writers D5/D11 + D8 do RacionalPolicy) → **fecho documental** (notas
acumuladas: gate ADR-0016/D7 · pino/`--bare` · knob compactação · §Vetos stale · 26 MÉDIA da
auditoria · ADR-0024/plano-codex superseded · sync skill `being-ceo` · veto de redação dos
textos) → **PROVA FINAL ponta a ponta** → **v1 FECHADO**.

## Rodada 9 — emitida (2026-07-12, sessão de controle nova)

**Validação de estado (read-only) antes da emissão:** main local ≡ origin = `a04ed6b` (merge
do PR #19) · zero PRs abertos · working tree com EXATAMENTE os 3 untracked de contrato ·
última migration = `V28__cost_event_off_book.sql` · piso 605/280 — **ZERO divergência** com o
§Rodada 8 — FECHAMENTO. Os kickoffs foram verificados ADVERSARIALMENTE antes da emissão
(workflow de 3 lentes: fidelidade R9-A×fontes · fidelidade R9-B×fontes · demarcação empírica
no repo): 2 citações corrigidas (path real da ADR-0032 = `0032-convencao-workspace-por-
adapter.md`; a rejeição do "auto-kickback solto" vive no handoff da Fatia 2 linha D7, não na
ADR-0031) + 2 lacunas incorporadas (mecânica de hooks do laudo-motor-nativo; as 2 metades da
régua M9) + gotcha de porta do /verify; achado novo fora das faixas registrado abaixo.

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R9-A** | **F-N6 — hook PreToolUse mínimo (D6 skills read-only + D7 cinto de sessão)**: materializador de hooks atrás do seam `WorkspaceConvention` (`settings.json` claude / `hooks.json` codex — codex SÓ handler `command`, exit 2 bloqueia; trust `--dangerously-bypass-hook-trust` no `CodexArgv`); deny (a) Write/Edit nas skills materializadas · (b) leitura de `~/.claude/projects`/`~/.codex/sessions` fora do próprio workspace | código (/tdd + /verify, porta **18081**, CHECKOUT COMPARTILHADO) | **ADR-0042 CONSUMIDA** · zero migração | **CONCLUÍDA** — **PR #20 MERGED (2026-07-12, main `0821498`)**; `HookMaterializer` + script node ÚNICO nos 2 motores; /verify PASS com CLIs reais (deny a · deny b · caminho feliz × claude e codex); ver §Rodada 9 — FECHAMENTO |
| **R9-B** | **Auto-kickback amplo entre personas** (resequenciada da Frente D, destravada pela F-N3): bloco validado de `blocos[]` → rota de devolução do bastão à persona de ORIGEM no `PersonaBatonRouter` (lacuna que outra persona resolve SEM o dono; `needs-grilling` continua o canal do dono); régua M9 nas 2 metades (prossiga-e-sinaliza N=5 + corte na reincidência) cobre as rotas novas (regra D6). **GUARDA: crescer além de "campo validado → rota" → PARAR e devolver ao controle** | código (/tdd + /verify, porta **18082** + pg efêmero próprio, **GIT WORKTREE próprio** — regra da rodada 8) | **ADR-0043 LIBERADA** (emenda na 0031) · zero migração | **CONCLUÍDA** — **PR #21 MERGED pelo Matheus (2026-07-12, main `999a310`)**; 5º bloco ```` ```retrabalho ```` (só Executor emite) + destino por tail-walk `origemDoBastaoNoTail` (achado do /verify) + régua M9 compartilhada; ver §Rodada 9 — FECHAMENTO |

**Matriz de colisão (carimbada arquivo a arquivo; verificação EMPÍRICA por agente):** R9-A
toca `execution/adapter/*` (`WorkspaceConvention` · `CodexArgv` · adapters) + materializador
novo (irmão do `SkillMaterializer`, em `skills/` ou pacote próprio) + `AdapterRunExecutor`
(call-site) + ADR-0042 + roadmap. R9-B toca `conversation/persona/*` +
`ConversationTurnCoordinator` + `TurnOutputSchema` + roadmap. **Interseção de produto =
VAZIA** (verificado no código: o coordinator importa só `AgentSessionStore`/`WakeupService`
de execution; o `AdapterRunExecutor` consome o turno como `TurnAssignment` opaco — o schema
viaja como String; nenhum adapter importa `TurnOutputSchema`). Único compartilhado =
`docs/roadmap-interno.md` (exceção trivial vigente). Regras cruzadas carimbadas nos
kickoffs: R9-A NÃO toca `conversation/**`; R9-B NÃO toca `execution/**`/`skills/**`/
`memory/**`; precisar do território do outro → PARAR e devolver ao controle. **Gotcha de
porta no kickoff da R9-B:** a skill /verify pina 18081 em TODOS os comandos (inclusive o
`pkill` de cleanup — rodá-lo verbatim MATARIA a instância viva da R9-A) → substituir por
18082 em todos + container pg de nome próprio.

**Achado da validação (fora das faixas): ADR-0041 SEM ARQUIVO** — registrado na tabela de
Reservas (escrever no fecho documental; não renumerar; aviso incluído no kickoff da R9-A
para não "corrigir" a numeração).

**Fila / inalterado:** Frente C (grill) e grill do Arquiteto seguem aguardando a palavra do
Matheus (encaixam quando ele tiver banda de grill — não ocupam slot de agente até lá).
Sequência restante idem §Rodada 8 — FECHAMENTO, com a adição da ADR-0041 na lista do fecho
documental. Pendências de OPERADOR seguem as consolidadas (sentada única: rebuild da main
`a04ed6b` + religar :8080 → V27+V28 no boot → VENDOs vivos + faxina M12). **2-em-voo: 2/2
ocupados.**

## Rodada 9 — FECHAMENTO (validação da sessão de controle, 2026-07-12) + Rodada 10

Handoffs de volta das duas faixas recebidos e reconciliados — **e o Matheus mergeou AMBOS**
(precedente #11/#16/#18/#19; com o merge, a decisão do "pende VENDO" da R9-B ficou tomada:
**follow-up, não bloqueio** — mesmo padrão da F-N4 na rodada 8). Cópias duráveis em
`orquestrador-agentes/scratchpad/`: `handoff-r9a-fn6-hooks.md` (da própria execução) ·
`handoff-r9b-auto-kickback-personas.md` (**DESTILADO pela sessão de controle** — a execução
da R9-B não deixou arquivo de handoff; fontes: memória persistente + linha da Frente B no
roadmap + forward-ref na ADR-0031, conferidas contra git/gh). Validação independente:

- **git/gh:** **PR #20 (R9-A/F-N6) MERGED** (main `0821498`) → **PR #21 (R9-B) MERGED**
  (main **`999a310`**, inclui o #20; conflito trivial do roadmap resolvido no merge-commit
  `2ab5aa5` — mesma âncora da tabela Frente B, as duas linhas mantidas). main local ≡
  origin; zero PRs abertos; working tree com EXATAMENTE os 3 untracked de contrato;
  **última migração segue V28** (zero consumo nas 2 faixas, como previsto);
  `docs/adr/0042-hook-pretooluse-materializacao.md` na main; buraco da 0041 INTOCADO
  (conforme instruído); **worktree `agente-orquestrator-kickback/` REMOVIDO** (working
  tree limpa conferida antes; handoff durável destilado antes da remoção — padrão R8-A);
  branches locais `feat/auto-kickback-personas` e `feat/hooks-pretooluse-adr0042` podadas
  + `git fetch --prune`; só `main` local resta.
- **Suítes re-executadas na main `999a310`:** backend **635/635** (33s, zero falhas) ·
  front **280/280** (21 suítes) — **piso canônico carimbado 635/280** na tabela de
  Reservas. A soma ingênua por-branch (624 + 617 sobre base 605) daria 636 — a MEDIÇÃO é
  a régua (mesmo off-by-one da rodada 8); um dos handoffs de volta citava "636 confirmado"
  = soma, não medição.
- **Discrepância STALE pega na validação (não propagada):** um handoff de volta listava
  "F-N4 (interrupção mid-run + SSE)" como restante da fila — **F-N4 foi EXECUTADA na
  rodada 8** (R8-A, ADR-0040, PR #19 MERGED). A fila real restante está na sequência
  recalculada abaixo.

**Registros das faixas (decisões fechadas com racional nos handoffs/ADRs — NÃO reabrir):**
- **R9-A (F-N6, ADR-0042):** mecânica NOVA descoberta na faixa — claude e codex aceitam a
  MESMA forma `{"hooks":{"PreToolUse":[...]}}` (codex em PascalCase, `.codex/hooks.json`;
  `apply_patch` sem `file_path` estruturado — path vive no texto `*** Add File:`); script
  node ÚNICO para os 2 motores; skills verificadas nos DOIS prefixos sempre; **escopo de
  materialização = TODO run que recebe skills** (superconjunto estrito do mínimo D6/D7 —
  racional técnico no handoff: o call-site do `SkillMaterializer` é incondicional);
  `CodexArgv` ganhou `--dangerously-bypass-hook-trust` (`hooksEnabled`, 9º arg); codex é
  fail-open com `hooks.json` malformado (nativo, fora do nosso controle — registrado).
  Follow-up honesto na ADR-0042: Glob/Grep fora do cinto D7 (enforcement amplo, pós-v1).
- **R9-B (emenda ADR-0031):** 5º bloco ```` ```retrabalho ```` `{"lacuna"}` com emissor
  restrito ao EXECUTOR (único que recebe entregável de back-office; defeito no dossiê
  continua D7/needs-grilling); destino NUNCA é campo do bloco — `origemDoBastaoNoTail`
  anda o tail através de retrabalho/prossiga/kickback (**achado do /verify ao vivo:** a
  reincidência pós-prossiga responde à sentinela, não a bastão fresco; derivar do turno
  imediato fazia o corte NUNCA disparar — bug→fix reproduzido na descartável antes do PR);
  régua M9 COMPARTILHADA entre os dois canais (`kickbacksSeguidosNoTail`); /verify PASS na
  18082 + pg próprio (cascata → 5×retrabalho → prossiga → reincidência → corte visível,
  zero run depois). **Regra do worktree da rodada 8: primeira aplicação, zero incidente.**

**Pendências consolidadas do OPERADOR (atualizado — sentada única, agora rebuild da main
`999a310`):** tudo da lista anterior (V27+V28 no boot · 27 approvals em lote · cascata +7 ·
issue órfã · prosa etapa 2 · botão parar/SSE · off-book GET · caronas F-N4 · faxina M12)
**+ VENDO vivo do retrabalho (R9-B)** — e os hooks da F-N6 entram de carona em qualquer run
(conferir `settings.json`/`hooks.json` no workspace se quiser a prova visual; a R9-A não
tem pendência própria: /verify completo com CLIs reais nos 2 motores).

**★ RODADA 9 COMPLETA: R9-A ✔ (PR #20) · R9-B ✔ (PR #21). PRs #1–#21 todos MERGED.**

**Rodada 10 (emitida 2026-07-12):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R10-A** | **Propose-then-commit de molde** (última resequenciada da Frente D antes do agente-como-tool; grill curto + execução na MESMA faixa, precedentes R3-B/R4-A/R4-B). O grill ABSORVE a questão H da auditoria "CEO criar molde (ADR-0017)" — é a mesma decisão — e trata "re-pin de molde sem chamador" como adjacente (absorver ou devolver ao fecho documental) | grill+código (/grilling + /tdd + /verify, porta 18081) | ADR **condicional** (precedente R9-B: só se além de "campo→rota"; ADR-0043+ livre) · migração: improvável SÓ no desenho status-on-version/fila-de-Approval (`role_template_version` já tem `proposed_by`/`approved_by`/`status`, V14); **reuse LITERAL da tabela `review` (V11: FKs NOT NULL `issue_id`+`run_id`) exigiria V29** — fork central do G2, o grill precifica; precisar → PARAR e pedir reserva | **CONCLUÍDA E MERGEADA** — grill D1–D10 + execução na MESMA faixa; **PR #22 MERGED (2026-07-12 19:04Z, main `3fdda05`)**; zero migração, sem ADR nova (emenda 0017 + forward-refs 0018/0039); /verify PASS na descartável; ver §Rodada 10 — FECHAMENTO |
| *(slot 2)* | **LIVRE deliberado** — os próximos itens da fila TODOS pedem grill com o Matheus (agente-como-tool · Frente C · grill do Arquiteto); emitir dois grills simultâneos só empilharia demanda na banda dele sem paralelismo real. O slot preenche quando ele escolher o 2º grill OU quando um trabalho só-de-agente destravar | — | — | — |

**Sequência restante do v1 (recalculada):** R10-A (propose-then-commit) → agente-como-tool
(grill próprio; insumos prontos: `codex mcp-server` + `multi_agent` stable + nota §2 do
grill motor-nativo) → **Frente C** (memória: graduação + índice JIT + writers D5/D11 + D8
RacionalPolicy) → **fecho documental** (notas acumuladas: ADR-0041 a escrever · gate
ADR-0016/D7 · pino/`--bare` · knob compactação · §Vetos stale · 26 MÉDIA · ADR-0024
superseded · sync skill `being-ceo` · veto de redação · lista H curta remanescente:
feedback do revisor→Issue ADR-0011) → **PROVA FINAL ponta a ponta** → **v1 FECHADO**.
2-em-voo: 1/2 ocupado (R10-A aguardando banda de grill).

## Rodada 10 — FECHAMENTO (validação da sessão de controle, 2026-07-12) + Rodada 11

Handoff de volta da R10-A recebido dizendo "PR #22 ABERTO, pende OK" — **e o Matheus
mergeou às 19:04Z**, depois do handoff (precedente #11/#16/#18/#19/#21). Cópia durável:
`orquestrador-agentes/scratchpad/handoff-volta-r10a-propose-then-commit.md`; a fonte da
verdade da fatia (`docs/handoff-grilling-propose-then-commit-molde.md`, D1–D10 com
racional) entrou TRACKED na main pelo próprio PR. Validação independente:

- **git/gh:** **PR #22 MERGED**; main local fast-forwarded `999a310` → **`3fdda05`**;
  32 arquivos (+832/−39); **ZERO migração** (V29+ seguem livres) e **SEM ADR nova**
  (ADR-0043+ livres; buraco da 0041 intocado); ADR-0017 emendada no corpo ("reusa a fila
  de **Approval**", linha 72, com o racional da emenda em §Governança) + forward-refs
  0018/0039; `CREATE_ROLE_TEMPLATE` é a 5ª ação do `ApprovalActionType` (confirmado no
  código com `ApprovalService`/`RacionalPolicy`/coordinator no dispatch); working tree
  com exatamente os 3 untracked de contrato; branch local podada.
- **Suítes re-executadas na main `3fdda05`:** backend **640/640** (36s) · front
  **286/286** (21 suítes) — **piso canônico carimbado 640/286**; a alegação da faixa
  bateu EXATA com a medição (sem off-by-one desta vez).
- **Fio solto inócuo (registrado; vira regra):** a execução deixou o checkout
  compartilhado com HEAD NA BRANCH da fatia — o controle devolveu para `main` e podou.
  **Regra para kickoffs futuros: faixa que usa o checkout compartilhado DEVOLVE o HEAD
  para `main` ao encerrar.**

**Decisões registradas (D1–D10 no handoff do grill, TRACKED — NÃO reabrir):** D1 CEO
propõe pelo gate / dono cria-edita direto (mão soberana) · **D2 fila = APPROVAL, não
Review** (a `review` V11 exige `issue_id`/`run_id` NOT NULL — exatamente o fork que o
kickoff previu; emenda com racional, não silêncio) · D3 versão nasce só no APPROVE,
`proposed_by`=CEO ≠ `approved_by`=dono (o hardcode era stand-in; DRAFT vestigial) ·
D4 drift-badge DERIVADO na tela (zero estado novo; read-join `pinnedTemplateId`) ·
**D5/D6 re-pin COMPLETO e SUBSTITUI** (re-aplica motor+skillset da versão e move o pin;
ajustes manuais descartados; mão soberana LOCAL, sem gate) · D7 teto D6 = o próprio gate
(nada persiste em PENDING) · D8 CEO só CRIA molde novo · D9 emenda sem ADR/migração ·
D10 diff derivado no card. **As DUAS metades da questão H da auditoria (criar molde ·
re-pin sem chamador) ABSORVIDAS** — saem da lista H do fecho documental (resta:
feedback do revisor→Issue ADR-0011).

**Candidata v1.1 nova (regra anti-círculo, decisão dele se quiser antecipar):**
CEO-propõe-EDIÇÃO de molde existente (D8 — puxaria visibilidade dos moldes no contexto
do CEO).

**★ RODADA 10 COMPLETA: R10-A ✔ (PR #22). PRs #1–#22 todos MERGED. Pendências do
OPERADOR inalteradas (sentada única; rebuild agora da main `3fdda05`).**

**Rodada 11 (emitida 2026-07-12):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R11-A** | **Grill do AGENTE-COMO-TOOL** — a última grande da Frente D. GRILL-ONLY (precedente R5-A): a superfície é potencialmente grande (a 7c a adiou por exigir credencial run-scoped + rota agente→core + ator AGENT + gate) e pode derivar mais de uma fatia — a EMISSÃO da(s) execução(ões) volta ao controle. O grill começa CRAVANDO o escopo do item congelado (as fontes divergem de ênfase: ADR-0003 = attach de skill por agente + KnowAll body inline; nota motor-nativo = transporte agente↔agente por MCP/subagentes nativos) e reconcilia o desenho 7c-era com a realidade atual (CEO roda LOCAL, executores LOCAL/DOCKER por config ADR-0016 — a objeção #3 muda POR alvo · Approval madura com 5 ações · off-book F-N5 · hooks F-N6 · tetos D6 da ADR-0038 já ativos · `multi_agent` stable provado na R6-B) | grill (não commita; handoff untracked até a 1ª execução derivada — precedente R5-A/R6-A) | — (zero consumo, contrato cumprido) | **CONCLUÍDA (2026-07-12)** — D1–D6 fechadas com o dono; item **DESCONGELADO E FATIADO** (F-AT1 + F-AT2); produto = `docs/handoff-grilling-agente-como-tool.md` (untracked, 335 linhas; F-AT1 commita); ver §Rodada 11 — FECHAMENTO |
| *(slot 2)* | **LIVRE** — Frente C (grill) e grill do Arquiteto seguem à escolha/banda do Matheus | — | — | — |

Colisão intra-rodada: zero (grill não commita). **Sequência restante:** R11-A grill →
execução(ões) do agente-como-tool → **Frente C** → **fecho documental** (lista idem
acima, com a questão H reduzida a ADR-0011) → **PROVA FINAL ponta a ponta** → **v1
FECHADO**. 2-em-voo: 1/2 (R11-A aguardando banda de grill).

## Rodada 11 — FECHAMENTO (validação da sessão de controle, 2026-07-12) + Rodada 12

Handoff de volta da R11-A recebido e reconciliado (cópia durável:
`orquestrador-agentes/scratchpad/handoff-volta-r11a-grill-agente-como-tool.md`). Validação:
main intocada em `3fdda05` (grill não commita — contrato cumprido: zero tracked, zero
reserva, :8080/HEAD intocados, nenhuma sonda rodada); working tree = 3 untracked de
contrato **+ o produto da faixa** `docs/handoff-grilling-agente-como-tool.md` (untracked,
335 linhas, D1–D6 + §3 fatias + §5 aberturas — **a F-AT1 commita**); piso segue **640/286**
por construção. Zero PRs abertos.

**Resultado (D1–D6 em uma linha cada; racional no handoff — NÃO re-discutir):** D1 escopo
v1 = (i) attach por agente + (ii) KnowAll body inline; (iii) agente-durável-como-tool =
épico v1.1 separado · D2 transporte = ESCOLHER, não inventar: (i) bloco de SAÍDA →
Approval (zero transporte novo, ADR-0039) · (ii) MCP-stdio LOCAL (rejeitados: subagentes
nativos e REST agente→core) · **D3 REVISADA PELO DONO** ("Por que v1.1 e não na v1?"):
(i)+(ii)-LOCAL entram no v1; só (ii)-docker → v1.1 (sequenciada COM o isolamento do D7);
autoridade de escopo dele exercida e registrada · D4 tetos PRÓPRIOS por eixo (os 6/3 são
de subagente): (i) cap-pedidos/run ≈5 + dedup + evento de descarte · (ii) cap-corpos/run +
dedup + recusa-com-motivo (cinto DIRETO do #1, enforcement 1ª classe) · D5 ambas
COST-NEUTRAL (auditoria ADR-0009 na (i); evento leve opcional na (ii)) · D6 DUAS fatias
alvo-EXECUTOR, sequenciais (colidem em `skills/`); personas FORA (seam especulativo);
canal do pedido inclina saída estruturada, scanner só interino-com-nota.

**Candidatas v1.1 registradas (§4 do handoff):** (ii)-DOCKER (com a fatia de isolamento
D7) · (iii) agente-durável-como-tool (grill próprio; contabilidade reusa F-N5;
`multi_agent` stable) · persona-self-request/fetch (só com demanda) · `disallowed-tools`
vazio do claude (eixo-subagente). **Nota pro fecho documental:** mover o item da Frente D
no roadmap (⚪ ADIADO → FATIADO) + registrar as candidatas v1.1.

**Rodada 12 (emitida 2026-07-12 — espelho da rodada 6: código + sonda):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R12-A** | **F-AT1 — pedido de skill pelo EXECUTOR via Approval** (a (i) do grill): canal do pedido (decisão-b saída estruturada inclinada × scanner interino-com-nota — decisão de kickoff) → Approval PENDING → aprovação → attach/install REUSO PURO (`installFromCatalog`/`SkillSetService`) + teto D4 (cap ≈5/dedup/evento) + auditoria `SKILL_INSTALL_REQUESTED`. **DEVER ESPECIAL: commita o handoff do grill** | código (/tdd + /verify, porta **18081**, checkout compartilhado) | base = **emenda ADR-0003 + ADR-0006**; ADR-0043 só se além de "campo→rota" (precedente R9-B/R10-A); **V29 improvável** — precisar → PARAR e pedir reserva | **CONCLUÍDA E MERGEADA** — **PR #23 MERGED (2026-07-13 03:08Z, main `0f10ddb`)**; 6ª ação `SKILL_INSTALL_REQUEST` (`SkillRequestScanner`+`SkillRequestGate`); ADR-0003/0006 EMENDADAS (zero ADR nova/migração); dever especial ✔ (handoff do grill TRACKED); ver §Rodada 12 — FECHAMENTO TOTAL |
| **R12-B** | **Sonda MCP-consume** (gate DURO da F-AT2): claude `-p` e codex `exec` consomem servidor MCP stdio NA INVOCAÇÃO EXATA do produto? (R6-B provou `multi_agent`, NÃO isto). Produto = laudo curto untracked (F-AT2 commita) com veredito por motor + forma exata do config (claude `--mcp-config` × codex `-c mcp_servers…`, pinada contra os binários — disciplina ADR-0014) + gotchas | sonda read-only/descartável (precedente R6-B; não commita) | — (contrato cumprido: zero código/commit/porta) | **CONCLUÍDA (2026-07-12)** — **PASS nos 2 motores; gate da F-AT2 LIBERADO**; laudo `docs/laudo-sonda-mcp-consume.md` (untracked, F-AT2 commita); ver §fechamento parcial abaixo |

**Colisão intra-rodada: ZERO por construção** (R12-B dirige os CLIs direto, não toca
produto; instruída a NÃO usar a 18081 — se precisar de instância, 18082 + pg próprio;
precedente R6-B verbatim). **FILA:** **F-AT2** emite quando F-AT1 landar (colisão em
`skills/` → sequenciais) **E** a sonda der veredito (falhou num motor → recuo informado,
transporte alternativo é grill próprio — NÃO cair no REST rejeitado sem re-grelhar).
Frente C (grill) e grill do Arquiteto: seguem aguardando banda do Matheus (zero colisão
com a rodada 12 — encaixam se ele quiser, mas 2-em-voo está 2/2). **Sequência restante:**
rodada 12 → F-AT2 → Frente C → fecho documental (+ mover item Frente D + candidatas v1.1)
→ PROVA FINAL → v1 FECHADO. 2-em-voo: 2/2.

## Rodada 12 — fechamento PARCIAL: R12-B concluída (validação da sessão de controle, 2026-07-12)

Handoff de volta da R12-B recebido e reconciliado (cópia durável já entregue pela própria
faixa: `orquestrador-agentes/scratchpad/handoff-volta-r12b-mcp-consume.md`). Validação:

- **main INTOCADA em `3fdda05`** (local ≡ origin; zero PRs abertos). O "HEAD avançou para
  `0baf244`" do handoff = o checkout compartilhado está na BRANCH da R12-A
  (`feat/skill-request-executor`), cujo 1º commit `0baf244` é exatamente o **dever
  especial já cumprido** ("docs: commit handoff do grill agente-como-tool"). R12-A segue
  EM VOO, sem push (contrato respeitado). Working tree: 3 untracked de contrato + o
  **laudo novo** `docs/laudo-sonda-mcp-consume.md` (untracked — a F-AT2 commita, padrão
  R6-B); o handoff do grill saiu dos untracked porque foi COMMITADO pela R12-A.
- **Contrato da sonda cumprido:** zero código de produto, zero commit, zero porta usada.

**Veredito (laudo §Veredito): PASS NOS 2 MOTORES — GATE DURO DA F-AT2 LIBERADO.**
claude `-p` e codex `exec` descobrem, chamam e consomem tool de servidor MCP stdio na
invocação exata do produto; custo aparece no usage NORMAL do turno (round-trip, não
subagente — zero implicação off-book).

**Achados que ENTRAM NO KICKOFF da F-AT2 (requisitos de construção, não recuo):**
1. **codex: tools do servidor KnowAll DEVEM declarar `annotations.readOnlyHint:true`** —
   sem isso o gate próprio do codex AUTO-CANCELA a chamada MCP no headless (rastreado no
   source do tag rust-v0.142.5: `destructive_hint.unwrap_or(true)`); fácil de satisfazer
   (as tools são legitimamente read-only).
2. claude insere 1 hop de `ToolSearch` antes da tool MCP — custo/latência a orçar no
   prompt do executor; codex chama direto.
3. Gramática EXATA copy-paste no laudo (claude `--mcp-config` · codex
   `-c mcp_servers.<nome>...` com `< /dev/null`); config 100% ad-hoc por invocação.
4. Isolamento confirmado: nenhum motor precisa de credencial no env do processo pai —
   o mecanismo nativo já satisfaz o #3 (D2 do grill).
5. Limite honesto do laudo: veredito cobre LOCAL/host (o escopo da F-AT2 no v1);
   combinação container/`--dangerously-bypass-approvals-and-sandbox` = inferência de
   código, não prova (irrelevante pro v1 — (ii)-docker é v1.1).

**FILA atualizada:** F-AT2 permanece aguardando SÓ a F-AT1 landar (colisão em `skills/`
→ sequenciais); o kickoff dela nasce do §3 F-AT2 do handoff do grill + os 5 achados
acima + a gramática do laudo. **Slot 2 LIVRE** — único item emitível sem banda do
Matheus não existe (F-AT2 bloqueada por colisão; Frente C/Arquiteto = grill dele).
2-em-voo: 1/2 (R12-A).

## Rodada 12 — FECHAMENTO TOTAL (validação da sessão de controle, 2026-07-13) + Rodada 13

Handoff de volta da R12-A recebido dizendo "PR #23 ABERTO, pende OK" — **e o Matheus
mergeou às 03:08Z** (precedente #11/#16/#18/#19/#21/#22). Cópia durável:
`orquestrador-agentes/scratchpad/handoff-volta-r12a-f-at1-pedido-skill-executor.md` +
memória persistente da faixa. Validação independente:

- **git/gh:** **PR #23 MERGED**; main local fast-forwarded `3fdda05` → **`0f10ddb`**;
  17 arquivos (+994/−11); **ZERO migração** (V29 segue livre) e **ZERO ADR nova**
  (ADR-0043 segue livre) — **ADR-0003 e ADR-0006 EMENDADAS** (encerra o caveat
  "estado-alvo FUTURO" só para a (i); registra o 1º consumidor real da
  Approval-de-ação-de-agente); **dever especial ✔** (`docs/handoff-grilling-agente-como-
  tool.md` TRACKED); HEAD devolvido a main pela execução (regra da rodada 10 ✔); branch
  local podada; working tree = 3 untracked de contrato + `docs/laudo-sonda-mcp-consume.md`
  (dever especial da F-AT2).
- **Suítes re-executadas na main `0f10ddb`:** backend **651/651** · front **288/288** —
  **piso canônico carimbado 651/288** (alegação da faixa bateu exata).
- **Mecanismo landado:** `SkillRequestScanner` + `SkillRequestGate` → 6ª ação
  `SKILL_INSTALL_REQUEST` (mesmo pipeline propose/decide/dispatch; precedente
  CREATE_ROLE_TEMPLATE) → APPROVE chama `SkillSetService.attachToIssue` para quem PEDIU;
  teto D4 provado ao vivo (7 blocos → 5 PENDING + descarte com evento + dedup).

**As 4 aberturas de kickoff decididas (racional na memória/PR — NÃO reabrir):**
(1) canal = **decisão-a, scanner interino** — provado no código que Run de Issue nunca
teve saída "em forma de turno" (outputSchema só existe para turno≠null); decisão-b
reabriria a máquina ADR-0039 para um caminho que ela nunca tocou; padrão IssueLedger
reaplicado · (2) cap = **5**/run, provado vivo · (3) kind = `SKILL_INSTALL_REQUEST` no
MESMO pipeline · (4) attach por AGENT só cria PENDING (dispatch guardado por quem
DECIDIU; 7a intocada).

**Registros do /verify (não-gate):** claude-haiku RECUSOU emitir bloco "literal" sem
motivação técnica (parece injeção — comportamento de modelo, registrado para prompts de
prova futuros) · "próximo run da MESMA Issue materializa" não re-exercitado ao vivo
(Issue DONE não é re-checkout-ável; coberto por `ApprovalGateTest` +
`AttachedSkillMaterializedOnRunTest` pré-existentes).

**★ RODADA 12 COMPLETA: R12-A ✔ (PR #23) · R12-B ✔ (sonda PASS). PRs #1–#23 todos
MERGED. Pendências do OPERADOR inalteradas (sentada única; rebuild agora da main
`0f10ddb`).**

**Rodada 13 (emitida 2026-07-13):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R13-A** | **F-AT2 — KnowAll body inline SÍNCRONO para o executor via MCP-stdio LOCAL** (a (ii) do grill; GATE JÁ SATISFEITO pela sonda R12-B — PASS 2 motores): servidor MCP do KnowAll (`knowall_search`/`knowall_fetch_body`, tools com `readOnlyHint:true` — requisito codex) + argv dos 2 motores (gramática do laudo) + teto D4 da (ii) (cap-corpos/run + dedup + recusa-com-motivo, NÃO fatal) + evento leve `SKILL_BODY_FETCHED`. **DEVER ESPECIAL: commita `docs/laudo-sonda-mcp-consume.md`** | código (/tdd + /verify, porta **18081**, checkout compartilhado — única faixa em voo) | emenda **ADR-0003** (metade (ii) do caveat — a F-AT1 encerrou a (i)); ADR-0043 só se além de "campo→rota"; **V29 improvável** (código novo, zero migração esperada) — precisar → PARAR e pedir | **CONCLUÍDA E MERGEADA** — **PR #24 MERGED (2026-07-13 12:33Z, main `3d02801`)**; `skills/mcp/` (5 classes) + `McpServerSpec` + argv 2 motores; teto server-side **cap=3**+dedup+recusa não-fatal; dever especial ✔ (laudo TRACKED); /verify PASS 2 motores REAIS; ver §Rodada 13 — FECHAMENTO |
| *(slot 2)* | **LIVRE** — Frente C (grill) e grill do Arquiteto seguem à escolha/banda do Matheus; a Frente C é o ÚLTIMO item grande antes do fecho documental | — | — | — |

**Sequência restante:** R13-A (F-AT2) → **Frente C** (grill dele + execução) → **fecho
documental** (lista acumulada + mover item Frente D no roadmap + candidatas v1.1 +
ADR-0041) → **PROVA FINAL ponta a ponta** → **v1 FECHADO**. 2-em-voo: 1/2.

## Rodada 13 — FECHAMENTO (validação da sessão de controle, 2026-07-13) + Rodada 14

Handoff de volta da R13-A recebido JÁ com o merge feito pelo Matheus (**PR #24 MERGED
12:33Z**). Cópia durável: o handoff-satélite `HANDOFF-f-at2.md` (4º untracked, working
note) foi MOVIDO pelo controle para
`orquestrador-agentes/scratchpad/handoff-volta-r13a-f-at2-knowall-mcp.md` — working tree
de volta ao contrato de 3 untracked. Validação independente:

- **git/gh:** **PR #24 MERGED**; main local fast-forwarded `0f10ddb` → **`3d02801`**;
  23 arquivos (+1443/−10); **ZERO migração** (V29 segue livre) e **ZERO ADR nova**
  (ADR-0043 segue livre; **liberada de vez** — as duas metades do agente-como-tool
  couberam em emendas) — **ADR-0003 emendada** (metade (ii) do caveat encerrada);
  **dever especial ✔** (`docs/laudo-sonda-mcp-consume.md` TRACKED); branch local podada;
  módulo novo `skills/mcp/` (Bridge · BridgeController · Materializer · CallbackAddress
  · RunScope) + `McpServerSpec` + argv dos 2 motores.
- **Suítes re-executadas na main `3d02801`:** backend **672/672** · front **288/288** —
  **piso canônico carimbado 672/288** (alegação bateu exata).
- **Decisões de kickoff registradas (não reabrir):** **cap = 3** corpos/run (mais
  apertado que o 5 da F-AT1 — o corpo cai DIRETO no contexto, sem gate) · transporte =
  MCP-stdio **loopback com token efêmero por-run** (`X-Orq-Mcp-Token`; sem credencial
  no env, sem endpoint ator-AGENT — não é o REST que a 7c rejeitou) · shape = script
  Node único materializado por-run em `.orq/mcp/` (precedente `HookMaterializer`) ·
  recusa não-fatal server-side · MCP ligado SÓ em run de EXECUTOR LOCAL.
- **/verify PASS nos 2 motores REAIS** (claude E codex, LOCAL): same-run
  fetch→usa→termina · cap 3+2 refused (run segue) · dedup 2×=1 slot · **codex 0
  cancelamentos** (o `readOnlyHint` provou o gate). Gotcha novo REGISTRADO: codex
  precisa de `--model` explícito (o `config.toml` do host pinado em modelo não aceito
  pelo 0.142.5 é rejeitado).
- **Doc-drift flagrado para o fecho documental:** as linhas F-AT1/F-AT2 da Frente B do
  roadmap dizem "pende OK do Matheus p/ merge" — STALE (ambas merged; as faixas
  escreveram antes do merge). Varredura de status no fecho documental.

**★ RODADA 13 COMPLETA: R13-A ✔ (PR #24). PRs #1–#24 todos MERGED. O AGENTE-COMO-TOOL
(último item grande da Frente D) está 100% ENTREGUE no v1: (i) F-AT1 + (ii)-local
F-AT2.** Pendências do OPERADOR inalteradas (sentada única; rebuild agora da main
`3d02801`).

**Rodada 14 (emitida 2026-07-13):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R14-A** | **GRILL DA FRENTE C — método de memória do CEO (2º cérebro), o ÚLTIMO item grande do v1.** Estado real: PARCIAL (átomo Decisão+carta ✅; a ASSINATURA do método — anti-inchaço — ausente). O grill fecha: graduação episódico→durável + tier Playbook (`MemoryKind` só tem CARTA) · cap-de-linha · índice enxuto sempre-carregado + navegação JIT (insumo NOVO: o padrão MCP-loopback da F-AT2 existe — escolher, não inventar) · writers D5/D11 da ADR-0019 (seams mortos) · D8 RacionalPolicy (Lógico×Intuição, mesmo regime hoje — achado da auditoria) · **triviabilidade/proporcionalidade do grill** (residual 4 do grill original + ADR-0020: "Auto-aprovado por triviabilidade" nunca decidido — achado da verificação do kickoff) · ADR-0020/D12 (reconciliação de texto PENDENTE — não está na ADR; triagem na auditoria com premissa defasada). GRILL-ONLY (precedente R5-A/R11-A): decisões + fatias derivadas; emissão volta ao controle | grill (não commita; handoff untracked, 1ª execução derivada commita) | — (execução derivada: V29 CANDIDATA provável — graduação/tiers podem pedir schema; ADR nova × emenda 0019/0020 = decisão do grill) | **CONCLUÍDA (2026-07-14)** — **C1–C16 fechadas, zero residual**; 3 fatias ESTRITAMENTE SEQUENCIAIS (F-C1→F-C2→F-C3, propriedade do método: endereço→linhagem/cap→JIT); produto = `docs/handoff-grilling-frente-c-memoria.md` (untracked, 239 linhas; F-C1 commita); ver §Rodada 14 — FECHAMENTO |
| *(slot 2)* | **LIVRE** — nada emitível sem banda dele (a fila restante é toda gateada pela Frente C: fecho documental vem DEPOIS dela por decisão registrada; grill do Arquiteto segue à escolha dele) | — | — | — |

**Sequência restante (recalculada):** R14-A (grill Frente C) → execução(ões) da Frente C
→ **fecho documental** (lista acumulada: ADR-0041 · gate ADR-0016/D7 · pino/`--bare` ·
knob compactação · §Vetos stale · 26 MÉDIA · ADR-0024 superseded · sync skill `being-ceo`
· veto de redação · H: ADR-0011 · mover item Frente D no roadmap + status stale
F-AT1/F-AT2 · candidatas v1.1) → **PROVA FINAL ponta a ponta** → **v1 FECHADO**.
2-em-voo: 1/2 (R14-A aguardando banda de grill).

## Rodada 14 — FECHAMENTO (validação da sessão de controle, 2026-07-14) + Rodada 15

Handoff de volta da R14-A recebido e reconciliado. Validação: main INTOCADA em `3d02801`
(grill não commita — contrato cumprido: zero código/commit/reserva; HEAD em main); working
tree = 3 untracked de contrato **+ o produto** `docs/handoff-grilling-frente-c-memoria.md`
(untracked, 239 linhas, **C1–C16 + 3 fatias + matriz de colisão + alternativas
rejeitadas/derrubadas** — a F-C1 commita); zero PRs abertos; piso segue **672/288** por
construção.

**Resultado (achados-chave; racional completo no handoff — NÃO re-litigar, inclusive a
tabela "O que este grill DERRUBOU"):** o achado central é a **perna que faltava:
ENDEREÇAMENTO** — a carta não emite ID nenhum; sem `#N`, D11/JIT/linhagem travavam na
mesma pedra · **C3:** princípio É Decisão num eixo NOVO `grau: DECISAO|PRINCIPIO` (tipo =
eixo do ASSUNTO, derivado; `tipo=PRINCIPIO` seria erro de categoria; UM espaço de
endereços) · **C6:** graduação = **7ª ação do gate** (`GRADUATE_PRINCIPLE`; precedente
literal F-AT1; herda grill/fila/lote/proveniência de graça) · **C7/C8:** cap POR PROJEÇÃO
(carta ≤12; nunca descartar/comprimir — estouro GRADUA ou cai-pra-JIT) · **C10:** JIT =
REÚSO do bridge MCP da F-AT2 (`memory_search` livre + `memory_fetch` cap=3/run; trilho nas
2 pontas, CEO e executor; escopo = durável SELADO, log cru segue board-only) · **C13:**
regime de commit ÚNICO (empírico: LOGICO é 100% do tráfego — o D8 do grill original
quebraria o gate; emenda de TEXTO na 0020, zero código; a maiêutica é ressuscitada pelo
C11: review de artefato criativo → INTUICAO → probe dispara) · **C14:** triviabilidade =
**silêncio-depois-de-perguntado** (perguntar sempre, bloquear nunca; não-gameável: quem
não diz nada não ganha memória) · **C15:** `fabricate(GatePassage)` EXTRAÍDO para
`memory/` (uma fábrica, não duas) · **C16:** 3 fatias ESTRITAMENTE sequenciais — o
paralelismo foi PROCURADO e não existe (endereço → permite → linhagem/cap → torna
necessário → JIT; hipóteses de paralelo testadas e caídas no próprio handoff).

**Decisão de slot da sessão de controle:** o 2º slot fica **LIVRE deliberado**. O único
candidato de outra frente seria um fecho-documental-PARCIAL — **avaliado e REJEITADO**:
a cauda do v1 é dominada pela cadeia sequencial F-C1→F-C2→F-C3 de qualquer forma (zero
ganho de wall-clock real), as F-C1/F-C2 entregam exatamente emendas em 0019/0020 que o
fecho precisaria re-tocar (duas passadas = dobra revisão do dono e re-abre o drift que o
fecho existe para matar), e a lista do fecho continua ACUMULANDO notas a cada fatia da
Frente C. O fecho documental roda INTEIRO, uma vez, depois da F-C3 — como a sequência
registrada sempre disse.

**Judgment call REGISTRADO para o dono (não trava nada; prazo natural = kickoff da
F-C2):** ADR própria para "graduação como ritual do gate" × emenda na 0020. Recomendação
do grill e do controle: **EMENDA** (7ª ação = enum + case de dispatch = "campo→rota", o
critério carimbado; emenda venceu 5× seguidas). A ADR-0043 segue livre se ele discordar.

**Rodada 15 (emitida 2026-07-14):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R15-A** | **F-C1 — "o átomo completo"** (C4·C11·C12·C14·C15): **V29** (`decision.seq` #N + `decision.grau`) · `fabricate(GatePassage)` EXTRAÍDO para `memory/` (a regra "sem substância → sem Decisão" mora dentro) · **writer D5 ACESO** (review fabrica Decisão; área = área da ISSUE, nunca default fixo) · **writer D11 ACESO** (`supersedesId` opcional nas 2 portas; carta se auto-cura) · **triviabilidade C14** (approve em silêncio → pergunta não-bloqueante; sem resposta → sem Decisão) · `render()` emite `#N`. **DEVERES ESPECIAIS: commita o handoff do grill + emendas ADR-0019/0020 + roadmap §Frente C** | código (/tdd + /verify, porta **18081**, checkout compartilhado — única faixa em voo; toca FE: racional na review + probe como prompt) | **V29 CONSUMIDA por esta fatia** · ZERO ADR nova (emendas 0019+0020) · `DecisionGrau` entra JUNTO do consumidor | **CONCLUÍDA E MERGEADA** — **PR #25 MERGED (2026-07-14 16:03Z, main `60ac302`)**; `GateDecisionFactory` única, writers D5/D11 ACESOS, C14 vivo, `#N` na carta com auto-cura, **D10/maiêutica disparou pela 1ª vez**; deveres especiais ✔ (handoff do grill+§RESULTADO TRACKED, emendas 0019/0020); ver §Rodada 15 — FECHAMENTO |
| *(slot 2)* | **LIVRE deliberado** (ver decisão acima — F-C2/F-C3 são estritamente sequenciais; fecho-parcial rejeitado com racional) | — | — | — |

**FILA:** F-C2 (graduação+cap; emite quando F-C1 landar) → F-C3 (índice+JIT; carona:
sync `being-ceo`; teto D6 = cap 3/run) → fecho documental COMPLETO → PROVA FINAL →
**v1 FECHADO**. Números embutidos da frente (provar por teste em cada fatia): graduação
N=3 · carta cap=12 · playbook cap=12 · `memory_fetch` cap=3/run. 2-em-voo: 1/2.

## Rodada 15 — FECHAMENTO (validação da sessão de controle, 2026-07-14) + Rodada 16

Handoff de volta da R15-A recebido JÁ mergeado (**PR #25 MERGED 16:03Z, main `60ac302`**
— precedente contínuo). Validação independente:

- **git/gh:** PR #25 MERGED; main local = `60ac302`; branch podada; **V29 CONSUMIDA**
  (`V29__decision_seq_grau.sql` — #N alocado no chokepoint `DecisionLog.record` sob o
  lock da company; backfill provado em Postgres real); ZERO ADR nova (0043 segue livre);
  emendas 0019 (nota D5/D11 → FECHADA) + 0020 (D8 regime único; triviabilidade →
  FECHADA); deveres especiais ✔ (handoff do grill + §RESULTADO TRACKED; roadmap
  §Frente C).
- **Suítes re-executadas na main `60ac302`:** backend **691/691** · front **290/290** —
  **piso canônico carimbado 691/290** (alegação bateu exata).
- **CONTRATO DE UNTRACKED — exceção temporária REGISTRADA:** a working tree agora tem
  **4 untracked deliberados** = os 3 de contrato + **`HANDOFF-frente-c.md`** (raiz do
  repo — ESTADO VIVO da Frente C para F-C2/F-C3, "untracked de propósito, o fecho
  documental roda inteiro após a F-C3"). Cópia durável em
  `orquestrador-agentes/scratchpad/handoff-volta-r15a-f-c1-atomo-completo.md`. As
  próximas faixas o ATUALIZAM ao encerrar (estado vivo), NÃO o commitam; o destino
  final é do fecho documental.
- **Decisões novas da faixa (registradas, não reabrir):** `DecisionGrau` nasceu SÓ com
  DECISAO (PRINCIPIO entra JUNTO do writer de graduação na F-C2 — regra
  enum-junto-do-consumidor da própria frente) · origem da Decisão de review =
  DONO_NO_GATE nos 2 atos; `racional_arrancado=false` na review (pergunta C14 é
  não-bloqueante, não grill contado) · supersede pré-hoc entra pelo campo do DECIDE
  (dono sela), zero mudança em ProposalScanner/TurnOutputSchema · VENDO da F-C1 por API
  real com personas `process` (nenhum passo dependia de comportamento de modelo —
  judgment call registrado no §RESULTADO).
- **Gotchas de /verify da frente (valem para F-C2/F-C3, registrados no estado-vivo):**
  bastão dirigível com personas `process` + reply-file · só o EXECUTOR protocola
  ```` ```proposta ```` · wakeup exige time com arquiteto · FE de dev contra
  descartável = seed de `app_user`+membership · `.claude/launch.json` untracked
  pré-existente preservado.

**★ RODADA 15 COMPLETA: R15-A ✔ (PR #25). PRs #1–#25 todos MERGED. Pendências do
OPERADOR inalteradas (sentada única; rebuild agora da main `60ac302`).**

**Rodada 16 (emitida 2026-07-14):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R16-A** | **F-C2 — "a assinatura": graduação + cap** (C1·C2·C3·C5·C6·C7·C8): `MemoryKind += PLAYBOOK` + `DecisionGrau += PRINCIPIO` (junto dos consumidores) · **`GRADUATE_PRINCIPLE` = 7ª ação do gate** · detector determinístico N=3/área (query pura, zero-LLM) · **caps 12/12 POR PROJEÇÃO** (hard; estouro = graduar ou cair-pra-JIT, nunca descartar/comprimir) · 2ª projeção (`grau=PRINCIPIO` → PLAYBOOK, gêmea da carta) · nudge-lines determinísticas (candidato + estouro) · FE: card com a frase do princípio EDITÁVEL antes de selar. **PRIMEIRA AÇÃO do kickoff: colher o judgment call do dono (ADR-0043 própria × EMENDA na 0020 — prazo registrado era este kickoff; recomendação = emenda)**. Emendas 0019 (residual 2 → FECHADO) + 0020 (D12: ritual = 7ª ação — inclui a reconciliação de texto da triagem da auditoria, atribuída à F-C2) | código (/tdd + /verify, porta **18081**, checkout compartilhado — única faixa em voo) | **ZERO migração** (grau/kind text sem check) · ADR-0043 × emenda = judgment call do dono NO kickoff | **CONCLUÍDA E MERGEADA** — **PR #26 MERGED (2026-07-14 17:59Z, main `124fcfa`)**; 7ª ação `GRADUATE_PRINCIPLE` (frase EDITÁVEL no card, lote herdado ADR-0035) · `GraduationDetector` N=3 · caps 12/12 · playbook gêmeo · **judgment call COLHIDO: EMENDA venceu (0043 segue LIVRE)**; emendas 0019/0020 (D12 CONCRETIZADO); ver §fechamento parcial abaixo |
| **R16-B** | **FECHO DOCUMENTAL PARCIAL — varredura independente da Frente C** (emitida a pedido do dono; ver §Revisão da decisão de slot abaixo): escrever a **ADR-0041** (buraco da F-N5) · nota-GATE ADR-0016/D7 · nota pino/`--bare`+gotcha `--model` na ADR-0014 · par ADR-0024 + `handoff-grilling-plano-codex-fechado.md` (superseded pela 0032) · **fichas MÉDIA da auditoria** em 0004/0006/0009/0011/0013/0017/0018/0026 + grills + ActorContext (EXCETO 0019/0020/D12 = F-C2; D5/D8 já consumidas pela F-C1) · nota do knob de compactação (apêndice 0038) · roadmap: **4 linhas stale da Frente D** (off-book ✅ F-N5 · interrupção ✅ F-N4 · handoff-executores MORTA/D3 · agente-como-tool ✅ F-AT1+F-AT2) + status F-AT1/F-AT2 + §Vetos (incl. seams D5/D11 → resolvido-por-PR-#25) + candidatas v1.1 (13 itens incl. grill do Arquiteto) · compilar **lista H** (`docs/lista-h-fecho-v1.md`) | docs-only (**GIT WORKTREE próprio** — regra da rodada 8/R8-A/R9-B; 2ª faixa em voo) | — (zero código; piso inalterado por construção) | **CONCLUÍDA E MERGEADA** — **PR #27 MERGED (2026-07-14 18:15Z, main `3a42e92`)**; escopo 100%: **ADR-0041 ESCRITA** (buraco fechado) · 12 ADRs + 6 handoffs de grill emendados · fichas aplicadas (SSE superada por PR #19; 0017×2 por PR #22) · roadmap varrido (4 linhas Frente D · F-AT1/F-AT2→MERGED · §Vetos · 13 candidatas v1.1) · `docs/lista-h-fecho-v1.md` (5 decisões H compiladas, nada decidido); merge LIMPO pós-#26 (conflito do roadmap nem se materializou); ver §Rodada 16 — FECHAMENTO TOTAL |

**REVISÃO da decisão de slot (2026-07-14 — o DONO perguntou "não há outra task em
paralelo?"; re-exame honesto SUPERSEDE a rejeição da rodada 14):** as três pernas do
racional original caíram no re-exame — (a) *"zero ganho de wall-clock"*: FALSO agora —
a cadeia F-C2→F-C3 é longa e sequencial; adiantar o subconjunto independente tira UMA
FAIXA INTEIRA da cauda pós-F-C3; (b) *"re-toca 0019/0020"*: respondido por FRONTEIRA
DURA verificada ficha a ficha (das 26 MÉDIA, só 1 toca 0020/D12 — já da F-C2; zero
tocam 0019); (c) *"a lista continua acumulando"*: respondido declarando o **FECHO
RESIDUAL pós-F-C3** com escopo nomeado: sync `being-ceo` (carona F-C3) · textos
C14/graduação no veto de redação · roadmap §Frente C · destino do `HANDOFF-frente-c.md`
· declaração "v1 FECHADO" · passada suplementar do diff da auditoria (2ab8eac..main —
cresceu para #11–#25; re-escopo é do controle). Colisão R16-A×R16-B: ADRs DISJUNTOS
carimbados; único compartilhado = roadmap em SEÇÕES distintas (exceção trivial).
Gotcha operacional no kickoff: os untracked de contrato são INVISÍVEIS dentro do
worktree → leituras por path ABSOLUTO do checkout principal.

**FILA:** F-C3 (índice+JIT + carona `being-ceo`; emite quando F-C2 landar) → **fecho
RESIDUAL** (escopo nomeado acima) → PROVA FINAL ponta a ponta → **v1 FECHADO**.
2-em-voo: **2/2** (R16-A F-C2 + R16-B fecho parcial).

## Rodada 16 — fechamento PARCIAL: R16-A concluída (2026-07-14) + Rodada 17

Handoff de volta da R16-A recebido dizendo "PR #26 ABERTO, pende OK" — **e o Matheus
mergeou às 17:59Z** (precedente contínuo). Validação independente:

- **git/gh:** **PR #26 MERGED**; main local fast-forwarded `60ac302` → **`124fcfa`**;
  22 arquivos (+833/−73); ZERO migração (V30+ livres); **judgment call COLHIDO no
  kickoff: EMENDA venceu — ADR-0043 segue LIVRE**; emendas 0019 (residual 2 FECHADO +
  Revisão F-C2) e 0020 (**D12 CONCRETIZADO**; reconciliação da triagem da auditoria
  FECHADA) na main; branch podada. `GraduationDetector` novo; `MemoryKind.PLAYBOOK` +
  `DecisionGrau.PRINCIPIO` entraram junto dos consumidores (regra da frente cumprida).
- **Suítes re-executadas na main `124fcfa`:** backend **702/702** · front **294/294** —
  **piso canônico carimbado 702/294** (alegação bateu exata).
- **Decisões de kickoff da faixa (registradas no estado-vivo; não reabrir):** 2ª
  projeção na MESMA tx do refresh · nudge de estouro cita os `#N` · `fabricate()`
  re-projeta em TODA passagem (carona do detector; idempotente por conteúdo) · projeção
  existente re-renderiza até vazia (auto-cura da ÚLTIMA supersedida) · **carta EXCLUI
  `grau=PRINCIPIO`** (superfícies gêmeas, nunca duplicadas) · `areaFor` fallback
  `direcao-geral` · linhagem `graduadaDe` NÃO validada (documentação resolvível) ·
  evidência da porta SOMA com a maiêutica.
- **VENDO notável:** graduação provada de ponta a ponta com motor real + UI viva
  (frase editada pelo dono) + LOTE (a 7ª ação herdou o batch-decision da ADR-0035 sem
  código novo). **Observação registrada (não-bug):** linha de carta de decisão de
  review mistura os DOIS espaços de `#N` (Decisão × Issue) — destino: carona OPCIONAL
  da F-C3 se trivial (fraseado do `oQueMudou`), senão fecho residual.
- Estado-vivo `HANDOFF-frente-c.md` atualizado pela faixa (cabeçalho "pende OK" ficou
  STALE pós-merge — o kickoff da F-C3 herda o aviso). R16-B segue EM VOO (worktree
  `agente-orquestrator-fecho-docs`, docs-only).

**★ R16-A ✔ (PR #26). PRs #1–#26 todos MERGED. Pendências do OPERADOR inalteradas
(sentada única; rebuild agora da main `124fcfa`).**

**Rodada 17 (emitida 2026-07-14):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R17-A** | **F-C3 — índice + navegação JIT (C9·C10·C10.b), a ÚLTIMA fatia de código do v1**: `memory_search` (livre) + `memory_fetch` (**cap=3/run** + dedup + recusa não-fatal + evento — o teto D6 da frente) NO MESMO bridge MCP da F-AT2 (zero canal novo) · escopo = durável SELADO (log cru board-only; fetch sobre log cru DERRUBADO → v1.1) · trilho nas 2 pontas (executor + turno do CEO; `McpRunScope.issueId` nullable) · **split do always-on C9** (CEO = carta+princípios · EXECUTOR = princípios só — o D12 "agentes aplicam" vira real) · **carona: sync `being-ceo`** (dado, pendente desde R4) · carona OPCIONAL: fraseado `oQueMudou` da review (dois espaços de #N) · emenda ADR-0003 + roadmap §Frente C | código (/tdd + /verify, porta **18081**, checkout compartilhado — a R16-B está em worktree) | zero migração esperada (V30+ livres) · emenda 0003 (3ª da série) | **CONCLUÍDA E MERGEADA** — **PR #28 MERGED (2026-07-14 23:23Z, main `a00815a`)**; A FRENTE C FECHOU; piso 721/294; ver §Rodada 17 — FECHAMENTO TOTAL |

Colisão R17-A × R16-B: **carimbada nos dois kickoffs** — territórios disjuntos (código+
0003+§Frente C × docs-only nos ADRs da varredura); único compartilhado = roadmap em
seções distintas (trivial). **FILA:** fecho RESIDUAL (pós F-C3 + R16-B: sync textos
C14/graduação no veto · §Frente C final · destino do HANDOFF-frente-c.md · passada
suplementar do diff · declaração v1) → **PROVA FINAL ponta a ponta** → **v1 FECHADO**.
2-em-voo: 2/2 (R17-A + R16-B).

## Rodada 16 — FECHAMENTO TOTAL: R16-B concluída (validação da sessão de controle, 2026-07-14)

Handoff de volta da R16-B recebido JÁ mergeado (**PR #27 MERGED 18:15Z** — o PR #26 da
R16-A landou ANTES e o merge foi LIMPO: o conflito trivial previsto no roadmap nem se
materializou). Cópia durável:
`orquestrador-agentes/scratchpad/handoff-volta-r16b-fecho-documental-parcial.md`.
Chores pós-merge EXECUTADOS pelo controle: main local fast-forwarded `124fcfa` →
**`3a42e92`**; worktree `agente-orquestrator-fecho-docs/` REMOVIDO (limpo); branch
local E REMOTA `docs/fecho-documental-parcial` podadas; zero PRs abertos. Validação:

- **Conteúdo conferido no ff:** 22 arquivos, +563/−21, 100% docs —
  **`docs/adr/0041-contabilidade-off-book-subagentes.md` EXISTE** (buraco de numeração
  FECHADO, com nota de proveniência) · 12 ADRs emendados (0004/0006/0009/0011/0013/
  0014/0016/0017/0018/0024/0026/0038) · 6 handoffs de grill emendados ·
  `docs/ARQUITETURA.md` (ActorContext) · roadmap varrido · **`docs/lista-h-fecho-v1.md`
  criada (5 decisões H compiladas — NADA decidido, como mandava o kickoff)**.
- **Fichas com desfecho registrado:** SSE superada por PR #19 · as 2 da 0017 superadas
  por PR #22 · 0020/D12 e D5/D8 corretamente EXCLUÍDAS pela fronteira (as duas
  primeiras couberam à F-C2; as outras já tinham sido consumidas pela F-C1).
- **Suítes re-executadas na main `3a42e92`:** 702/702 · 294/294 — piso inalterado por
  construção, confirmado por medição.
- **SOBRAS registradas para o fecho RESIDUAL** (seções não enumeradas no kickoff da
  R16-B — honestidade dela, não furo): linha R10-A da Frente B ainda "pende OK" ·
  §Divergências ainda com "D5/D11 residual H" (mesmo fato já varrido no §Vetos).
  Somam-se ao escopo residual já nomeado.
- **Nota de base para a R17-A/F-C3 (em voo):** o kickoff citou `main 124fcfa`; a base
  real agora é `3a42e92` (só docs por cima) — se a faixa já nasceu de `124fcfa`, o
  rebase é trivial; se ainda não criou a branch, nasce de `3a42e92`.

**★ RODADA 16 COMPLETA: R16-A ✔ (PR #26) · R16-B ✔ (PR #27). PRs #1–#27 todos MERGED.**
Pendências do OPERADOR inalteradas (sentada única; rebuild agora da main `3a42e92`).

**R17-B emitida (2026-07-14, no encerramento desta sessão de controle — a pedido do
dono, "mais uma ação em paralelo"):** **SESSÃO DE DECISÕES DA LISTA H** com o Matheus —
DECISÕES-ONLY (zero código/commit/arquivo; zero colisão com a F-C3 por construção).
Pauta = `docs/lista-h-fecho-v1.md` (TRACKED, compilada pela R16-B): as 5 decisões que
SÓ ele toma (ADR-0011 feedback revisor→Issue · veto de redação dos textos novos incl.
C14/graduação · 3 tetos de escalação, default v1.1 · bump do pino claude · destino dos
artefatos vivos de VENDO). Produto = `docs/handoff-decisoes-lista-h.md` (untracked — o
FECHO RESIDUAL commita e aplica). É o único trabalho restante NÃO-gateado pela F-C3.
**2-em-voo: 2/2 (R17-A/F-C3 código + R17-B/lista-H decisões).**

**TROCA DE SESSÃO DE CONTROLE (2026-07-14):** esta sessão de controle encerra por
contexto (~regra de ouro do handoff); a continuação nasce de kickoff /nova-sessao com
o ledger como fonte única. Handoffs de volta das faixas em voo (R17-A · R17-B) serão
recebidos pela SESSÃO NOVA.

## Sessão de controle — CONTINUAÇÃO assumida (2026-07-14)

Nova sessão de controle (kickoff /nova-sessao) assumiu. Validação read-only na retomada:

- **git/gh:** main local = `3a42e92` ✓ (= origin/main, merge do PR #27) · **zero PRs
  abertos** ✓ · última migration = `V29__decision_seq_grau.sql` ✓ · piso vigente
  **702/294** (medido pela sessão anterior na própria `3a42e92`; sem merge novo desde
  então → sem re-medição, a régua não mudou de commit).
- **R17-A/F-C3 EM CURSO no checkout compartilhado:** HEAD na branch
  `feat/frente-c-indice-jit` apontando EXATAMENTE para `3a42e92` — a faixa nasceu da
  main pós-#27, então a "nota de base" do fechamento da R16-B resolveu-se sozinha (nem
  o rebase trivial precisou). Trabalho em andamento: 2 untracked de código da faixa
  (`backend/src/main/java/com/orquestrador/memory/MemoryQuery.java` + teste espelho).
  NÃO tocar no HEAD/working tree até o handoff de volta (regra: a faixa devolve o HEAD
  em main ao encerrar).
- **Contrato de untracked:** os 4 deliberados presentes ✓ (ledger · laudo-lease ·
  handoff-auditoria · `HANDOFF-frente-c.md`) + os 2 de código acima (da faixa em voo —
  desvio esperado, dono = R17-A).
- **R17-B (lista H):** produto `docs/handoff-decisoes-lista-h.md` ainda NÃO existe no
  disco — a sessão de decisões não rodou/terminou. Pauta tracked
  `docs/lista-h-fecho-v1.md` presente ✓.
- **ACHADO (chore p/ o fechamento da rodada 17): poda REMOTA histórica incompleta** —
  `git ls-remote --heads origin` mostra **20 branches remotas de PRs já MERGED**
  (ex.: `feat/frente-c-graduacao-cap` do #26, `feat/frente-c-atomo-completo` do #25,
  e toda a série antiga desde `feat/loop-handoff-contexto-adr0030`). Só a
  `docs/fecho-documental-parcial` (R16-B) foi de fato podada no remoto — os registros
  "branch podada" das rodadas anteriores valeram só para o LOCAL na maioria dos casos.
  Poda remota em LOTE entra nos chores do próximo fechamento (todas merged → deleção
  segura; commits preservados na main).

**2-em-voo: 2/2 (R17-A código · R17-B decisões). Nada a fechar; sessão de controle em
espera dos handoffs de volta.**

## Rodada 17 — FECHAMENTO TOTAL (validação da sessão de controle, 2026-07-14)

Os DOIS handoffs de volta chegaram juntos. Cópias duráveis:
`orquestrador-agentes/scratchpad/handoff-volta-r17a-f-c3-indice-jit.md` ·
`handoff-volta-r17b-decisoes-lista-h.md` · `handoff-decisoes-lista-h.COPIA.md`
(cópia de segurança do produto untracked da R17-B).

**R17-A (F-C3) — validação independente:**
- **git/gh:** **PR #28 MERGED** (23:23:48Z, merge commit `a00815a` — `gh pr view` conferido);
  merge FF limpo (a árvore da main é idêntica ao commit testado `0d08e7e`, que saiu de
  `3a42e92` — a "nota de base" resolveu-se sozinha); HEAD em main sincronizado; ZERO
  migração (`V29` segue a última — V30+ livres); ZERO ADR nova (0043 segue LIVRE);
  emendas 0003 (tools de memória no bridge) + 0019 (split C9 + memoryFile) na main;
  untracked = 4 de contrato + o produto da R17-B (os 2 de código da faixa foram
  commitados). Branch local já podada pela faixa; **remota podada pelo controle**.
- **Suítes re-executadas na main `a00815a`:** backend **721/721** (40s) · front
  **294/294** — **piso canônico carimbado 721/294** (alegação bateu exata).
- **A FRENTE C FECHOU** (F-C1 → F-C2 → F-C3). Entregas-chave: `MemoryAudience` (split
  C9: CEO = carta+princípios · executor = princípios SÓ) · `MemoryQuery`
  (search por TERMOS + fetch(#N), fronteira C10.b como invariante) · 2 tools novas no
  MESMO bridge MCP da F-AT2 com `McpToolProfile` (executor = KnowAll+memória · CEO = SÓ
  memória) · teto D6 próprio (cap=3/run + dedup + recusa não-fatal) · memoryFile em
  TODO run (judgment call do dono COLHIDO no kickoff — 2 testes-guarda reescritos) ·
  carona `being-ceo` sincronizada nas 2 demos (md5==banco).
- **Lição de /verify registrada (memória persistente `verify-tool-de-agente-com-motor-real`):**
  a busca casava frase inteira; o codex perguntou como humano, não achou nada e citou o
  `#N` errado — corrigida para casamento por TERMOS dentro da fatia. Tool exposta a
  agente se prova com o MOTOR REAL perguntando como humano.
- **Ambiente:** a faixa aplicou **V28+V29 no Postgres compartilhado** (autorizada no
  kickoff, para o import da being-ceo); o processo :8080 NUNCA foi tocado (segue
  desligado) — no próximo boot o Flyway acha tudo aplicado.

**R17-B (decisões da lista H) — validação independente:**
- Regime decisões-only RESPEITADO: zero commit/branch/Vxx; `lista-h-fecho-v1.md` tracked
  com 0 diff; produto = `docs/handoff-decisoes-lista-h.md` (untracked, 25kB — racional
  VERBATIM do dono + evidência file:line; o fecho residual commita e aplica).
- **As 5 decisões (FECHADAS pelo dono — não reabrir):** (1) tetos ADR-0018 →
  circuit-breaker no v1 · contador de riscos v1.1 (candidata #11 fica só com ele) ·
  CASCATA emendada para FORA (escalação direta ao dono) · teto de gasto nunca foi
  promessa do v1; (2) feedback do revisor → v1 pela TERCEIRA VIA (materializar ao
  executor as Decisões ancoradas na issue — PUSH, não pull; NÃO criar endpoint de
  edição de Issue; a premissa da ADR-0011 morreu); (3) pino claude FICA em 2.1.201
  (bump só em fatia de infra própria com 2º motivo — gatilho registrado); (4) os 6
  textos ficam; acentuar as 7 strings de usuário do `RacionalPolicy` (NÃO tocar
  `CONFAB_FORMS`/`CREATIVE_AREA_MARKS` — matcher compara normalizado; arrasta
  `RacionalPolicyTest` + fallback `decision-dialog.tsx:106`); (5) artefatos de VENDO →
  virou o bug abaixo; rejeitar os 27 PENDING SÓ DEPOIS do conserto (a rejeição vira
  prova viva).
- **🔴 BUG DE PRODUTO VIVO EM MAIN (achado da R17-B, fora da pauta):** ADR-0035 (lote,
  PR #12) nunca reconciliada com a F-C1 (writers acesos, PR #25) —
  `ApprovalBatchService.decideBatch` carimba UM racional em CADA `decide()` e cada
  `decide()` fabrica Decisão própria ("sempre nasce") → um ato do dono vira N Decisões
  IDÊNTICAS + **nudge FALSA de graduação na carta** (o detector C5 não filtra tipo;
  3+ em lote na mesma área basta). Atenua: HIRE_AGENT = estrutura-org → OPERACIONAL →
  fora do corpo da carta/playbook; o envenenamento é do LOG e da NUDGE. **DECIDIDO:
  consertar no v1 (1 Decisão por lote, linhagem dos N na `evidencia`).**

**⚠️ ESCOPO DO FECHO DO V1 REVISADO (decisões do dono via R17-B — SUPERSEDE a fila
"fecho residual docs-only → prova"):** o fecho = **3 fatias de código** (A: lote×Decision
🔴 · B: circuit-breaker ADR-0018 · C: feedback→executor, gateada pela F-C3 — agora
DESTRAVADA) **+ fecho documental residual + prova final**. Fonte autoritativa:
`docs/handoff-decisoes-lista-h.md`; resumo na memória `fecho-final-v1-escopo.md`.

**Decisões de fatiamento do controle (racional registrado):**
- **Emenda viaja com a fatia que muda o comportamento** (precedente de TODAS as fatias:
  F-C2→0019/0020, F-C3→0003/0019, R12-A→0003/0006): **0035 → fatia A** · **0018 →
  fatia B** · **0011 → fatia C**. O consolidado da R17-B listava as 3 emendas "no fecho
  residual" — atribuí-las às fatias mantém o residual DOCS-ONLY puro e o padrão provado.
- **Acentuação do `RacionalPolicy` = CARONA da fatia A** (correção trivial e decidida;
  poupa uma passada de código no residual — mesmo padrão da carona being-ceo na F-C3).
- **Rejeição dos 27 PENDING = prova viva do fix A no AMBIENTE VIVO** → entra na sentada
  única do operador/prova final (pós-merge da A + rebuild do :8080), NÃO na faixa A
  (o :8080 está desligado e é território do operador).
- **Sequência: A ∥ B (rodada 18, 2-em-voo) → C (rodada 19) → fecho documental residual
  → prova final → v1 FECHADO.** A recomendação da R17-A ("A+B em paralelo, worktrees")
  confere com a matriz de colisão (ver rodada 18 abaixo).

**Chores executados:** branch remota `feat/frente-c-indice-jit` podada · HEAD em main ·
sem worktree a remover. **Poda das 20 branches remotas HISTÓRICAS (chore registrado na
retomada): BLOQUEADA pelo classificador de permissão** (poda em lote autônoma ≠ chore da
rodada corrente) — todas confirmadas 100% contidas na main (`git branch -r --merged`);
fica como AÇÃO DO DONO (um comando, lista pronta em
`scratchpad/merged-branches.txt` da sessão) ou entra na allowlist.

**Pendência de OPERADOR atualizada:** V27+V28+V29 JÁ APLICADAS no Postgres compartilhado
(V27 desde a rodada 4; V28+V29 pela R17-A, autorizada). O :8080 está DESLIGADO.
Recomendação: adiar a sentada única de rebuild+boot para DEPOIS do merge da fatia A —
o mesmo boot já carrega o fix do lote e a rejeição dos 27 vira a prova viva na própria
sentada (pré-requisito da prova final).

**★ RODADA 17 COMPLETA: R17-A ✔ (PR #28) · R17-B ✔ (decisões-only). PRs #1–#28 todos
MERGED. A Frente C FECHOU. Piso canônico 721/294 na `a00815a`.**

**Rodada 18 (emitida 2026-07-14):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R18-A** | **Fatia A — lote × Decision (1 Decisão por lote)** — conserto do 🔴 bug de produção (ADR-0035 × F-C1): Anchor de lote (o `Anchor` é sealed ANINHADO em `GateDecisionFactory.java:86-92`; `NaApproval` singular) + seam de supressão por-item (convive com o skip existente do GRADUATE_PRINCIPLE, `ApprovalService.java:217-218`) + linhagem dos N na `evidencia` (padrão F-C2) + reprodução ao vivo ANTES do fix + **carona: acentuação das 7 strings de usuário do `RacionalPolicy`** (arrasta GateDecisionFactoryTest:86 · ReviewDecisionServiceTest:211 · ApprovalGateTest:570 · decision-dialog.tsx:106; `CONFAB_FORMS`/`CREATIVE_AREA_MARKS` INTOCADAS) · emenda ADR-0035 | código (/tdd + /verify, porta **18081**, checkout compartilhado); branch `feat/lote-uma-decisao-adr0035` de `a00815a` | zero migração esperada (V30+ livres) · emenda 0035 (+0019/0020 se couber) | **CONCLUÍDA E MERGEADA** — **PR #29 MERGED (2026-07-15 01:57Z, main `05d9b6d`)**; piso 723/294; ver §Rodada 18 — fechamento parcial |
| **R18-B** | **Fatia B — circuit-breaker de erro repetido (ADR-0018)** — mini-grill (limiar, rec=3 · o que "para", rec=não-reenfileirar o par agente×issue · o que destrava) + execução na MESMA faixa (padrão R3-B): chokepoint `HeartbeatRunStore.finishFailure:72` · contador NOVO (attempt não serve) · aviso M6 direto ao dono (molde ProposalDescarteNotice/postaProtocolo em AbsorcaoDeIssueRouter/CorrecaoDeEntregaRouter) · cobre os 2 pontos de enfileiramento (WakeupService.enqueue + promoteIfNeeded) · emenda ADR-0018 (cascata FORA · teto (a) não-promessa · teto (b) → v1.1, candidata #11 reescrita) | mini-grill + código (**GIT WORKTREE próprio** `../agente-orquestrator-circuit-breaker`; /verify porta **18082** + pg próprio — a skill pina 18081 em TUDO, incl. pkill: sobrescrever) | zero migração esperada (persistência nova → PARAR e pedir V30) · emenda 0018 | **CONCLUÍDA E MERGEADA** — **PR #30 MERGED (2026-07-15 12:16Z, main `fd3be27`)**; breaker DERIVADO (zero Vxx); mini-grill: limiar 3 · para o par · destrava só root; ver ★ R18-B ✔ |

- **Colisão R18-A × R18-B CARIMBADA por agente independente (workflow de verificação):**
  interseção = `docs/roadmap-interno.md` APENAS, em seções distintas (fatia A: status
  do fecho · fatia B: candidata #11 ~:210) — exceção trivial conhecida. B não fabrica
  Decisão (M6 = turno ANSWERED, zero GateDecisionFactory/ApprovalService); nenhuma toca
  application.yml (knob = @Value default inline); testes disjuntos (governance/approval+
  memory+review+front × execution/).
- **Riscos condicionais registrados (vigiar nos handoffs):** (a) se o mini-grill da B
  decidir que "o dono agir" roteia pelo GATE, a B entraria em `ApprovalService` — o
  arquivo mais quente da A; GUARDA no kickoff: PARAR e avisar o controle antes de codar
  nesse caso. (b) interação semântica sem colisão de arquivo: o dispatch do gate chama
  `wakeups.enqueue` (`ApprovalService.java:302`) — se o breaker recusar par aberto no
  enqueue, muda comportamento composto pós-merge; o grill da B decide se WAKEUP_AGENT
  aprovado conta como "dono agir".
- **Verificação adversarial dos kickoffs (workflow 3 agentes, 297k tokens):** 2 ERRADOS
  corrigidos (RacionalPolicyTest NÃO pina os textos da carona — quem pina são
  GateDecisionFactoryTest:86/ReviewDecisionServiceTest:211/ApprovalGateTest:570;
  `postaProtocolo` NÃO é do IssueService — mora em AbsorcaoDeIssueRouter:198/
  CorrecaoDeEntregaRouter:168) + 5 âncoras imprecisas corrigidas (fábrica =
  ApprovalService:217-220/:234-249; detector = GraduationDetector:23,:30-45 +
  Consolidator:78; "N idênticas" → "mesmo racional literal e área"; untracked com paths
  explícitos; byte-identidade das listas provada pelo diff, não por teste existente).
  O padrão segue 6/6: TODO kickoff verificado tinha erro real.

**FILA (sequência do fecho do v1, decidida na rodada 17):** fatia C — feedback do
review → executor (rodada 19; +1 dieta no `MemoryAudience` da F-C3, PUSH; emenda
ADR-0011) → **fecho documental RESIDUAL** (docs-only puro: aplicar handoff-decisoes-
lista-h · correção da lista-h tracked §6 · sync textos C14/graduação · roadmap §final ·
linha R10-A "pende OK" + §Divergências D5/D11 (sobras R16-B) · fraseado `oQueMudou` ·
rename `McpBridge` (candidato) · destino do HANDOFF-frente-c.md e do handoff-decisoes ·
passada suplementar do diff da auditoria · declaração v1) → **PROVA FINAL** ponta a
ponta (cenário do ledger + rejeição dos 27 como prova viva do fix A no ambiente vivo)
→ **v1 FECHADO**. 2-em-voo: **2/2 (R18-A + R18-B)**.

## Rodada 18 — fechamento PARCIAL: R18-A concluída (validação da sessão de controle, 2026-07-15)

Handoff de volta da R18-A recebido JÁ mergeado (**PR #29 MERGED 01:57Z pelo Matheus** —
"o OK foi ele mesmo mergeando", precedente contínuo). Handoff completo da faixa:
`HANDOFF-lote-decisao.md` (raiz, novo untracked de contrato — 6º; a faixa o criou no
molde do HANDOFF-frente-c; destino = fecho documental residual). Cópias duráveis:
`orquestrador-agentes/scratchpad/handoff-volta-r18a-lote-uma-decisao.md` +
`HANDOFF-lote-decisao.COPIA.md`. Validação independente:

- **git/gh:** PR #29 MERGED (merge commit `05d9b6d`); **ff da main local
  a00815a→05d9b6d executado pelo controle** (a faixa deliberadamente não puxou — R18-B
  em voo); o diff do ff = exatamente os 12 arquivos alegados (4 produção:
  ApprovalService · ApprovalBatchService · GateDecisionFactory · RacionalPolicy;
  5 testes; 3 docs: 0035 Revisão R18-A + 0019 bullet Anchor.NoLote + roadmap). Branch
  local E remota podadas pelo controle. ZERO migração (V29 última; V30+ livres) · ZERO
  ADR nova (0043 livre). HEAD em main.
- **Suítes re-executadas na main `05d9b6d`:** backend **723/723** (42s) · front
  **294/294** — **piso canônico carimbado 723/294** (alegação bateu exata; +2 = cinto
  novo do RacionalPolicyTest + teste do lote, líquido do ApprovalBatchDecisionTest).
- **/verify da faixa (18081 + pg efêmero, :8080 intocado):** lote de 3 REJECT → 1
  Decisão em estrutura-org com linhagem dos 3 na evidencia, carta SEM nudge; as mesmas
  3 individuais → 3 Decisões + nudge C5 — o detector segue vivo, a diferença é o fix.
- **Decisões novas da faixa (registradas; não reabrir):** (1) **agrupa por ÁREA** — "1
  Decisão por lote" implementado como 1 por (lote × área): tipoFor e o detector C5 são
  por-área; lote homogêneo real (D2 obriga filtro por tipo) = exatamente 1; lote
  multi-área nunca clona N numa área. Refino de implementação SINALIZADO ao dono no
  corpo do PR #29 — e ele mergeou. (2) **`Anchor.NoLote` com `approval_id` NULO** —
  rastreabilidade das N na `evidencia` (texto); precedente = caso B (anchoredToIssue).
  (3) **ADR-0020 NÃO tocada** — o "se couber" não coube (o grill não muda no lote; doc
  na 0035 §3). (4) Achado de teste: o dedupe por fingerprint da propose está VIVO
  (RED inicial reusava target_detail e recebia a MESMA approval — corrigido variando o
  alvo; bug do teste, não do código).
- **Intactos (conferidos pela faixa):** activity_log por-item (D11) · kickback agregado
  (D7) · decisionReason por item (D8) · skip GRADUATE_PRINCIPLE (ortogonal ao flag).
- **Housekeeping dos 27 HIRE_AGENT PENDING: DESTRAVADO** — o fix está na main; segue
  sendo do OPERADOR, na sentada única (rebuild+boot do :8080 da main `05d9b6d`+, com a
  rejeição em lote como prova viva do fix no ambiente real).
- **R18-B no momento da validação:** ainda SEM worktree/branch (mini-grill em curso) —
  o rebase dela por cima do #29 tocará o roadmap em seções distintas (trivial, como
  carimbado).

**★ R18-A ✔ (PR #29). PRs #1–#29 todos MERGED. Piso canônico 723/294 na `05d9b6d`.
Slot aberto → R19-A (fatia C) emitida abaixo. 2-em-voo: 2/2 (R18-B + R19-A).**

**Rodada 19 (emitida 2026-07-15):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R19-A** | **Fatia C — feedback do review → executor (a ÚLTIMA fatia de código do v1)**: +1 dieta na ponta do EXECUTOR — materializar (PUSH) no memoryFile do run de Issue as Decisões ancoradas naquela issue. O gap exato (verificado): `AdapterRunExecutor.java:242` materializa sem passar o `runContext.issueId()` que tem em mãos; `DecisionRepository` não tem finder por issue; o trilho JIT NÃO cobre (reject = grau DECISAO tipo OPERACIONAL/TECNICA, fora do selado C10.b POR DESIGN — e a fronteira NÃO se alarga). Inclui: emenda ADR-0011 (premissa "editar a Issue antes do REOPEN" morta, :52-54) + comentário stale `IssueService.java:221` + roadmap | código (/tdd + /verify, porta **18081**, checkout compartilhado — a R18-B está no worktree); branch `feat/feedback-review-executor-adr0011` de `05d9b6d` | zero migração (issue_id já existe) · emenda 0011 · V30+/0043 livres | **MERGED — PR #31 (2026-07-15T12:15Z, merge `81e2423`; `origin/main fd3be27`)**; /verify live PASS; piso 729/294 (736 projetado na main); ver §handoff abaixo |

- **Colisão R19-A × R18-B CARIMBADA por agente independente:** interseção = roadmap
  APENAS (verdict COLISAO_TRIVIAL_ROADMAP; atenção registrada: ambas podem editar o
  bloco-nota da linha ~168 — conflito de mesma linha possível, resolução trivial no
  rebase de quem landar 2º). `AdapterRunExecutor` NÃO está em caminho plausível da B
  (o breaker age em WakeupService.enqueue/promoteIfNeeded/finishFailure; o
  AdapterRunExecutor nunca grava FAILED nem enfileira). Testes: zero arquivo em comum
  (acoplamento comportamental C↔B só na integração do 2º merge — verde sob breaker,
  falhas de teste abaixo do limiar rec.=3). ADRs disjuntas (0011 × 0018). work/issue:
  C edita SÓ o comentário :221; B lê os routers como molde e cria classe NOVA.
- **Verificação adversarial do kickoff R19-A (workflow 2 agentes, 213k tokens):**
  33 claims CONFIRMADOS · 1 IMPRECISO corrigido (critério de aceite qualificado:
  o recusado no fetch é grau=DECISAO tipo OPERACIONAL/TECNICA — DIRECAO continua
  servida; fronteira C10.b intacta). Scout prévio (Explore) mapeou a superfície F-C3
  com file:line — o achado do scout de que o fetch está FECHADO para o caso do reject
  REFORÇA a decisão PUSH do dono (as duas decisões convivem por construção; kickoff
  proíbe "resolver" a tensão reabrindo qualquer uma).
- **⚠️ KICKOFF DA R18-B FICOU STALE pós-#29 (achado do verificador):** cita base
  `a00815a`/piso 721 (atuais: `05d9b6d`/723) e `ApprovalService.java:302` para o
  `wakeups.enqueue` (agora `:381` — o PR #29 inseriu `fabricaDecisaoDoLote` acima).
  Sem impacto estrutural: o comando de worktree dela usa `main` LOCAL (já em
  `05d9b6d`), então a base corrige-se sozinha. **ADENDO emitido ao dono** para colar
  na sessão da R18-B (base+piso+linha). Nuance menor: "claim do reaper incrementa o
  attempt" → TODO claim incrementa (HeartbeatRunRepository:69).
  **[Formalizado durável 2026-07-15: `scratchpad/adendo-r18b-circuit-breaker-pos-pr29.md`.
  DESFECHO (reconciliado pelo controle): adendo CUMPRIDO — a R18-B honrou base/piso/
  contador (ver ★ R18-B ✔ abaixo); virou histórico com o merge do #30.]**
- **SOBRA nova p/ o fecho residual:** roadmap linha ~166 "R18-A pende OK p/ merge"
  ficou stale pós-#29 (mesma natureza da linha R10-A já registrada).

---

**★ R18-B ✔ (PR #30 **MERGED** na `main` — merge `fd3be27` —
https://github.com/matheus3006/agente-orquestrator/pull/30) — circuit-breaker de erro repetido (fatia B, item 3 lista H):**

- **Branch/PR:** `feat/circuit-breaker-adr0018` de `05d9b6d` (worktree isolado
  `../agente-orquestrator-circuit-breaker`, F-N5/ADR-0041; **base corrigiu-se sozinha** — o
  `git worktree add … main` pegou o LOCAL já em `05d9b6d`, como o adendo previu). Commit
  `f85a876` → **PR #30 MERGED (2026-07-15 12:16Z, merge `fd3be27`)**. Worktree removido
  no encerramento. *(O "NÃO mergeado" original deste bloco era pré-merge — reconciliado.)*
- **Mini-grill R18-B FECHADO (2 params, respostas verbatim do dono via seleção):**
  1. **O limiar:** **"3 falhas (Recomendado)"**.
  2. **O que para:** **"Só o par agente×issue (Recomendado)"** (o agente segue em outras
     issues; a issue segue para outros agentes).
  3. **O que destrava:** **"Só wakeup manual direto (Recomendado)"** — gate FORA → o
     **`ApprovalService` NÃO foi tocado** (a guarda de colisão com a R18-A NÃO foi acionada;
     e a R18-A já mergeou de qualquer forma).
- **Arquivos tocados (12):** MAIN modificados (6): `WakeupOutcome` (+`barrado()`),
  `WakeupService` (check no enqueue), `HeartbeatRunStore` (check no promote),
  `HeartbeatRunRepository` (query `findRecentOutcomesByPair`), `SpawnLineage` (+`isRoot()`),
  `conversation/persona/PersonaContinuationPoster` (filtro). NOVOS (2 main + 2 test):
  `RepeatedFailureBreaker`, `RepeatedFailureNotice`, `RepeatedFailureCircuitBreakerTest`
  (7 testes), `work/issue/IssueConversaTestSupport`. DOCS (2): emenda ADR-0018, roadmap #11.
- **Vxx / ADR:** **NENHUM Vxx** — breaker DERIVADO do histórico (query), zero coluna/tabela
  → **V30 SEGUE LIVRE**. **Emenda 0018** (sem ADR nova → **0043 SEGUE LIVRE**; precedente 5×).
- **Testes:** **730 back** (piso 723 + 7) / **294 front** (intocado). Suíte inteira verde.
- **/verify:** instância descartável **:18082 + pg efêmero próprio** (todos os comandos +
  teardown re-pinados de 18081→18082; o **:8080 E a R19-A/:18081 INTOCADOS**). Promote barra
  o par travado (3 FAILED → 4 runs sem novo, deferido CANCELLED, 0 eventos promote); promove
  o não-travado (1 FAILED → 3 runs com novo, COALESCED, 1 evento); wakeup ROOT via API fura
  (`created:true`); anti-silêncio (WARN do breaker no log). Postgres REAL exercitou a query.
- **Decisões novas da faixa (registradas; não reabrir):**
  1. **Contador CONSECUTIVO por (agente, issue)** derivado de `FAILED`/`SUCCEEDED` (os N mais
     recentes todos FAILED). CANCELLED/INTERRUPTED = abortos administrativos, ignorados (nem
     contam nem quebram); SUCCEEDED zera; `issueId` nulo (conversa do CEO) nunca trava.
  2. **Root fura** = o destravamento (o check só roda quando `!lineage.isRoot()`; sem estado
     "aberto" persistido — recalculado sempre).
  3. **Promote barrado descarta os deferidos como `WakeupStatus.CANCELLED`** (reuso do enum;
     não se perdem em loop; o dono retoma pelo manual). Sinalizado.
  4. **Aviso M6 registrado no `donoDeVoltaNaConversa`** (PersonaContinuationPoster) —
     correção de fluxo: sem isso o aviso do breaker seria lido como "dono voltou" e quebraria
     o roteamento de bastão de personas (a "régua M9" que o ProposalDescarteNotice sinaliza).
  5. **Fronteira aceita (v1):** run que morre com lease expirado nunca vira FAILED (reaper
     requeue RUNNING→QUEUED) — o contador não vê morte silenciosa. Anotada na emenda 0018.
- **Escopo do /verify (honesto):** o barramento **non-root** foi provado pelo **promote** (o
  caminho motor-puro) em Postgres real; o **enqueue** non-root exigiria o gate (setup pesado) e
  está coberto pelos 7 testes de integração. O enqueue **root** (destravamento) foi provado via API.
- **Colisão com R19-A (em voo):** só **roadmap** — a #11 (linha 213) × bloco-nota ~166-168 da
  R19-A; trivial no rebase de quem landar 2º, como carimbado. `AdapterRunExecutor` não tocado;
  routers `work/issue` lidos como molde M6, **não editados** (classe `RepeatedFailureNotice` NOVA).

**★ R18-B ✔ (PR #30 MERGED, `fd3be27`). Piso 730/294 na branch (736 projetado na main combinada com a R19-A). A R19-A tambem mergeou (#31, `81e2423`) → as 3 fatias de codigo do v1 landaram.**

**FILA restante:** R18-B **✔ MERGED (#30)** + R19-A **✔ MERGED (#31)** → **as 3 fatias de código
do v1 landaram (main `fd3be27`)** → fecho documental RESIDUAL (docs-only) → PROVA FINAL → **v1
FECHADO**. (Este rodapé e o da seção R19-A abaixo ficaram redundantes — a principal consolida.)

---

**★ R19-A ✔ (PR #31 — https://github.com/matheus3006/agente-orquestrator/pull/31 —
**MERGED 2026-07-15 12:15Z, merge `81e2423`**) — feedback do review → executor (fatia C,
item 1 lista H — a ÚLTIMA fatia de código do v1):**

- **Branch/PR:** `feat/feedback-review-executor-adr0011` de `05d9b6d` (checkout COMPARTILHADO —
  a R18-B estava no worktree isolado; zero interferência). Commit `5acab40` → **PR #31 MERGED**
  (o "NÃO mergeado" original era pré-merge — reconciliado).
  **HEAD devolvido em `main` no encerramento.**
- **A entrega (PUSH, não pull — decisão do dono, R17-B):** quando o run tem `issueId`, o
  `CompanyMemoryMaterializer` materializa no `memoryFile` do run as Decisões ancoradas NAQUELA
  issue (o motivo do REJECT `anchoredToReview` + caso B `anchoredToIssue`), com `#N` + racional.
  O executor RECEBE o feedback no re-run sem depender de escolher buscar. **O gap exato fechado:**
  o `issueId` que o `AdapterRunExecutor` já tinha em mãos não fluía para a dieta.
- **Arquivos tocados (7):** MAIN (4): `memory/DecisionRepository` (finder novo
  `findByCompanyIdAndIssueIdOrderBySeqAsc`), `memory/CompanyMemoryMaterializer` (issueId 5-arg +
  overload 4-arg=null + a seção do feedback), `execution/AdapterRunExecutor` (passa
  `runContext.issueId()` na materialização — SÓ a linha :242 + comentário; **arquivo do mesmo
  pacote da R18-B mas DIFERENTE — R18-B não o tocou, confirmado no handoff dela**),
  `work/issue/IssueService` (SÓ o comentário stale :221). TEST (1):
  `memory/CompanyMemoryMaterializationTest` (+6 testes, +2 autowires). DOCS (2): emenda ADR-0011
  (Emenda R19-A) + roadmap (linha nova R19-A + nota das 3 fatias). **Nenhum arquivo do território
  da R18-B tocado.**
- **Vxx / ADR:** **NENHUM Vxx** — `issue_id` já existe na `decision` → **V30 SEGUE LIVRE**.
  **Emenda 0011** (sem ADR nova → **0043 SEGUE LIVRE**; precedente 6×). **Fronteira C10.b do
  `MemoryQuery` INTOCADA** (os testes de MemoryQuery seguem verdes SEM edição).
- **Testes:** **729 back** (piso 723 + 6) / **294 front** (intocado). Suíte inteira verde (42s).
  Os 6: tracer do feedback · parity issue-sem-decisão **byte-idêntica** ao durável · N decisões
  por `seq` + isolamento entre issues · CEO sem seção · **convivência R19-A × fetch-selado** (o
  mesmo `#N` entregue é recusado no `memory_fetch`) · run REAL `dispatchOnce` com feedback literal.
- **/verify:** instância descartável **:18081 + pg efêmero `fb-review-pg`** (`:8080`/`:5432` e a
  R18-B/`:18082` INTOCADOS; teardown feito). Cenário real: issue de alto risco → run#1 sucesso →
  `PENDING_REVIEW` → **REJECT com motivo real** → `BLOCKED` → **REOPEN** → re-run → o
  `.claude/CLAUDE.md` do run continha o feedback **LITERAL** com `#N` (mtime casando o re-run;
  run#1, pré-reject, não pôde tê-lo escrito). Vantagem-de-agente (lição F-C3): o próprio `process`
  fez `cat` do seu `memoryFile` e o StepTrace mostrou o feedback → `RUN_SUCCEEDED`. Probe: 2º
  reject → a seção passou a listar `#1` e `#2` ordenados por `seq`. Decisão do reject confirmada
  no pg: grau=DECISAO, tipo=OPERACIONAL, `issue_id` preenchido → fora do selado, como o desenho manda.
- **Decisões novas da faixa (registradas; não reabrir):**
  1. **Overload 4-arg `materialize(...)`=`materialize(..., null)`** — o run durável (turno do CEO,
     run sem issue) não tem conceito de issue; o overload preserva os testes de split da F-C3
     byte-a-byte e mantém o `AdapterRunExecutor` como único caller 5-arg. Não é seam raso: "sem
     contexto de Issue" é caso real.
  2. **A seção acende por `issueId != null`, NÃO por `audience==EXECUTOR`** — no motor,
     `CEO ⟹ issueId nulo` e `issueId != null ⟹ EXECUTOR` (invariante do `AdapterRunExecutor`),
     então os dois gates são equivalentes; `issueId != null` é a expressão direta ("entregue o
     feedback SE há issue"), e a ponta do CEO fica inalterada por construção (sem issue → sem seção).
  3. **A seção RENDERIZA o racional** (o feedback) — de propósito: este caminho ENTREGA (o agente
     selado não tem canal inbound), diferente do `memory_fetch` onde o log cru é board-only. É por
     isso que convive com a fronteira sem alargá-la.
  4. **Observação do /verify (não é decisão):** sem time no assignee, `areaDaIssue` cai para
     "operacao" → `tipoFor("operacao")`=OPERACIONAL. Confirma que o reject fica fora do selado
     (grau=DECISAO + tipo OPERACIONAL) — exatamente o caso que a fatia entrega por PUSH.
- **Colisão com R18-B:** só **roadmap** (bloco-nota ~168 + a linha nova R19-A × a #11 da R18-B),
  trivial no rebase de quem landar 2º — como carimbado. **Zero colisão de código** (a R18-B não
  tocou `AdapterRunExecutor`; eu não toquei nenhum arquivo do território dela). ADRs disjuntas
  (0011 × 0018). Testes: zero arquivo em comum.
- **Piso combinado quando #30 e #31 mergearem:** 723 + 7 (R18-B) + 6 (R19-A) = **736** (sem
  sobreposição de arquivo de teste — R18-B = Heartbeat/breaker, R19-A = CompanyMemoryMaterializer).
- **Ficou por fazer:** NADA da fatia. Com a R19-A a **última fatia de código do v1 fecha** — resta
  o **fecho documental RESIDUAL** (docs-only) + a **PROVA FINAL** → **v1 FECHADO**.

**★ R19-A ✔ MERGED (PR #31, 2026-07-15T12:15Z, merge `81e2423`). Logo depois #30 (R18-B) MERGED
(`fd3be27` = origin/main). AS 3 FATIAS DE CÓDIGO DO V1 LANDARAM (#29 · #31 · #30). Piso 729/294
na branch R19-A; 736 projetado na main (ff do checkout compartilhado + re-medição = passo do
controle; `main` local ainda `05d9b6d`). ZERO fatia de código de v1 restante — só documental +
prova final.**

## Rodadas 18/19 — FECHAMENTO TOTAL (validação da sessão de controle, 2026-07-15)

Este bloco CONSOLIDA os rodapés "FILA restante" pré-merge das seções R18-B e R19-A acima
(escritos pelas faixas antes dos merges — redundância sinalizada pela própria R18-B).
Handoffs de volta recebidos: R18-A 2ª volta (delta, confirmação) · R18-B · satélite do
adendo · R19-A. Cópias duráveis no `orquestrador-agentes/scratchpad/`
(`handoff-volta-r18b-circuit-breaker.md` · `handoff-volta-r19a-feedback-review-executor.md`
+ `HANDOFF-circuit-breaker.COPIA.md` · `HANDOFF-feedback-review-executor.COPIA.md`).
Validação independente:

- **git/gh:** **PR #31 MERGED 12:15:25Z (merge `81e2423`) · PR #30 MERGED 12:16:02Z
  (merge `fd3be27` = origin/main)** — `gh pr view` conferido nos dois. **ff da main
  local `05d9b6d` → `fd3be27` executado pelo controle**; o diff combinado do ff bate com
  os 2 handoffs (18 arquivos: breaker+notice+2 tests novos · materializer+finder+
  executor:242+IssueService:221 · emendas 0011/0018 · roadmap). Branches locais E
  remotas das 2 faixas PODADAS pelo controle. Worktree do breaker já removido pela
  faixa (git worktree list = só o principal). HEAD em main. ZERO migração (V29 última;
  **V30+ LIVRES**) · ZERO ADR nova (**0043 LIVRE**; emendas 0011+0018 nas fatias,
  precedente 7×). Untracked = 8 (6 de contrato + HANDOFF-circuit-breaker.md +
  HANDOFF-feedback-review-executor.md — os 2 novos entram no contrato: ler sempre,
  commitar nunca; destino = fecho residual).
- **Suítes re-executadas na main `fd3be27`:** backend **736/736** · front **294/294** —
  **piso canônico carimbado 736/294** (projeção 723+7+6 exata; zero sobreposição de
  teste entre as faixas, como previsto na matriz).
- **Mini-grill R18-B (decisões do dono, verbatim por seleção — FECHADAS):** limiar =
  **3 falhas** · para = **só o par agente×issue** · destrava = **só wakeup manual
  direto (root)** — gate FORA, `ApprovalService` intocado (guarda de colisão não
  acionada). Decisões novas das 2 faixas registradas nos blocos ★ acima (breaker
  DERIVADO zero-estado · root-fura no call-site · CANCELLED p/ deferidos barrados ·
  aviso M6 no filtro donoDeVoltaNaConversa · fronteira lease-expirado aceita ·
  overload 4-arg do materializer · seção por issueId!=null · seção RENDERIZA o
  racional — convive com C10.b sem alargá-la).
- **Colisão real observada: ZERO** — os merges #31→#30 foram limpos; nenhum arquivo de
  código em comum (como carimbado); roadmap resolvido no landing.
- **Adendo R18-B: CUMPRIDO e histórico** (a faixa honrou base/piso/contador; satélite
  fechada sem código/git — resíduo L1951 reconciliado em linha).

**★ RODADAS 18 E 19 COMPLETAS: R18-A ✔ (#29) · R18-B ✔ (#30) · R19-A ✔ (#31).
PRs #1–#31 todos MERGED. AS 3 FATIAS DE CÓDIGO DO FECHO DO V1 LANDARAM.
Piso canônico 736/294 na `fd3be27`. ZERO fatia de código restante.**

**Pendência de OPERADOR (atualizada):** sentada única — rebuild+boot do :8080 da main
`fd3be27` (V27+V28+V29 já aplicadas no pg; o boot carrega lote-fix + breaker +
feedback→executor) → VENDOs vivos + **rejeitar os 27 HIRE_AGENT PENDING em lote**
(prova viva do fix #29 no ambiente real). Pré-requisito da prova final.

**Rodada 20 (emitida 2026-07-15):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R20-A** | **FECHO DOCUMENTAL RESIDUAL do v1** (escopo 9 itens, verificado): commit+aplicação do produto da R17-B (lista-h §6 como notas datadas) · varredura de ESTADO do roadmap (R10-A `:163` · R18-A `:166` · R19-A `:168` · **F-C2 `:182`** · blockquote `:170` + linha própria p/ R18-B · D5/D11 `:303/:322-332` · **§Vetos `:418-419`** · **§Rastreabilidade `:246/:261-262`** · **coerência `:128-129`/`:31-34`/`:377-378`** · candidatas v1.1 #14 oQueMudou + #15 rename McpBridge · nota de estado final + **grep de segurança** `pende OK\|PLANEJADO\|em voo\|EM EXECUÇÃO\|PARCIAL`) · gatilho do pino (item 4 lista H) · **4 HANDOFF-*.md movidos p/ `docs/handoffs/`** com proveniência + desfechos · passada suplementar da auditoria RE-ESCOPADA (checklist dirigido i–iii; anti-círculo) | docs-only (checkout compartilhado, única em voo); branch `docs/fecho-residual-v1` de `fd3be27` | — (zero código; piso 736/294 inalterado por construção; zero ADR nova) | **EM VOO** (kickoff emitido 2026-07-15, verificado adversarialmente) |

- **Verificação adversarial do kickoff (workflow 2 agentes, 265k tokens):** claims —
  4 IMPRECISOS corrigidos (R18-B sem linha própria na tabela → criar; item 7 era
  quase no-op → desfecho vai na nota final, timeline `:448` não se toca; §6 nota 5 sem
  alvo literal → nota de contexto sem strike; +linha F-C2 `:182` na enumeração).
  **Crítico de completude — verdict FALTAM_ITENS, 4 itens REAIS adicionados ao escopo**
  (F-C2 `:182` · §Vetos `:418-419` stale pós-R19-A · §Rastreabilidade com 3 estágios
  stale · intro Frente B `:128-129` contradizendo a própria tabela). O padrão segue:
  TODO kickoff verificado tinha erro/lacuna real (8/8). Mitigação estrutural adotada:
  o item 3 ganhou GREP DE SEGURANÇA que generaliza a enumeração.
- **Decisões de fatiamento do controle (registradas):** oQueMudou + rename McpBridge →
  **v1.1** (anti-círculo: cosméticos, não-promessa de ADR, não bloqueiam a prova; se o
  dono discordar no review → micro-fatia) · 4 HANDOFFs → COMMITAR em `docs/handoffs/`
  (norte "v1 = TUDO registrado"; o dono sela no PR) · passada da auditoria re-escopada
  para checklist dirigido (racional: re-auditar tudo re-auditaria o que o ledger já
  registra — 7 PRs nascidos de kickoff verificado com file:line).

**FILA FINAL:** R20-A volta+merge → **PROVA FINAL ponta a ponta** (cenário do ledger:
dono cria issue órfã → CEO absorve → grill → estratégia → hire em LOTE → executa #N →
reclama → corretiva; uma perna claude exercita o D10; + rejeição dos 27 = prova viva do
fix #29; pré-requisito: sentada do operador) → **declaração v1 FECHADO**.
2-em-voo: **1/2 (R20-A)**.

## Rodada 20 — FECHAMENTO TOTAL (validação da sessão de controle, 2026-07-15)

Handoff de volta da R20-A recebido JÁ mergeado (**PR #32 MERGED pelo Matheus,
`mergedBy=matheus3006`, 14:12:42Z, merge `9d71211`** — `gh pr view` conferido; a faixa
não se auto-mergeou). Handoff durável escrito pela própria faixa em
`orquestrador-agentes/scratchpad/handoff-volta-r20a-fecho-residual.md` (contrato dos 3
untracked respeitado — o handoff-back não virou arquivo no repo). Validação independente:

- **git:** ff da main local `fd3be27` → `9d71211` executado pelo controle; diff
  **100% docs** (grep de código no range = 0 hits; 8 arquivos: roadmap +93/−21 ·
  lista-h +33 · ADR-0014 +6 · handoff-decisoes commitado · 4 HANDOFFs em
  `docs/handoffs/`). Branch `docs/fecho-residual-v1` podada (local+remota).
  **Working tree = exatamente os 3 untracked de contrato** (ledger · laudo-lease ·
  auditoria) — o contrato de 8 encolheu para 3 COMO PLANEJADO (5 commitados pela faixa).
- **Suítes re-executadas na main `9d71211`:** backend **736/736** · front **294/294** —
  piso inalterado por construção, **confirmado por medição** (precedente R16-B).
- **Escopo 9/9 entregue** (conferido no diff do ff): lista-h §6 como notas datadas ·
  roadmap varrido 3a–3i + grep de segurança (zero stale de estado) · gatilho do pino na
  ADR-0014 · 4 HANDOFFs movidos com proveniência+desfechos · passada suplementar =
  **ZERO divergência nova até fd3be27** (3 claims amostrados verdadeiros: ADR-0006 ·
  0026 · 0013) · v1.1 #14/#15 só registradas.
- **Decisões novas da faixa (docs-only, via grep de segurança — registradas):** header
  "Frente B — a construir" → **"CONSTRUÍDA"** · nota **"ZERO veto pendente"** no §Vetos
  · placements (pino → ADR-0014 apêndice; estado final → nota consolidada pós-blockquote).
- **Ambiente conferido pelo controle (read-only) para a prova final:** flyway do pg
  compartilhado no topo = **V29/V28/V27 success** · :8080 DESLIGADO (sentada do
  operador pendente) · **27 HIRE_AGENT PENDING confirmados no banco** (+1 CREATE_ISSUE
  PENDING pré-existente da cascata parada — pela lista H item 5, FICA).

**★ RODADA 20 COMPLETA: R20-A ✔ (PR #32). PRs #1–#32 todos MERGED. O FECHO DOCUMENTAL
DO V1 LANDOU. Falta só a PROVA FINAL → declaração v1 FECHADO.**

**Rodada 21 (emitida 2026-07-15) — a ÚLTIMA do v1:**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R21-A** | **PROVA FINAL ponta a ponta do v1** — sentada única com o dono+operador: GATE = rebuild+boot do :8080 da main `9d71211` (migrations JÁ aplicadas; gotcha classloader: rebuild ⟹ restart) · **P1** rejeição dos 27 em LOTE pelo dono = prova viva do fix #29 (1 Decisão, linhagem dos 27, carta SEM nudge) · **P2** cenário do ledger em COMPANY NOVA (norte verbatim: CEO discute→racional→contrata→org): issue órfã → absorção → grill → estratégia → HIRE em lote → executa #N → reclama → corretiva · **P3** perna com motor CLAUDE real exercitando o D10 (delegation/pesquisa in-run) · **P4** colateral não-gate (feedback→executor se review ocorrer naturalmente; breaker NÃO se força) | sessão de prova (observação + roteiro; escritas no vivo SÓ pelo dono via UI; exceção precedentada = POST /wakeups, padrão G5; SQL read-only; ZERO commit) | — (não é faixa de código; declaração v1 NÃO é dela — o controle fecha após validar) | **ENCERRADA (2026-07-16)** — GATE 0 ✔ · P1 ✔ (APPROVE) · fix do loop → PR #33 ✔ · **P2 pernas (a)–(f) PASS** (incl. DOBRA do P1 via REJECT+kickback e plano A/D12) · **(g)/(h)/(i) e P3/D10 BLOQUEADOS pelo 🔴 bug #1** (agente contratado pelo CEO nasce mudo → DONE fantasma) · **v1 DECLARADO FECHADO PELO DONO** (decisão dele, supersede a recomendação técnica da faixa); ver §Rodada 21 — FECHAMENTO |

**Pós-prova:** handoff de volta → controle valida → **declaração "v1 FECHADO"** (carimbo
final no roadmap = micro-PR docs com OK do dono) + fechamento do ledger do v1.
2-em-voo: **1/2 (R21-A)**.

- **Verificação adversarial do kickoff R21-A (workflow 2 agentes, 282k tokens; 29
  claims: 25 CONFIRMADOS, 4 IMPRECISOS corrigidos; cobertura: FALTAM_PERNAS → todas
  incorporadas):** (1) garantia real do fix #29 = 1 Decisão POR ÁREA do lote →
  critério e snapshot de áreas/baseline adicionados; nudge-FAIL condicionado ao ANTES
  (B(área)+1≥3 = nudge LEGÍTIMA); (2) **o lote NÃO carrega override de motor (D12)** →
  plano A (pedir claude no grill) + plano B (1 decisão individual com A4, contagem
  ajustada); (3) company nova sem princípio → playbook inexistente e memoryFile
  DELETADO no 1º run (correto por construção) → a prova da R19-A vai na CORRETIVA
  (Decisão do caso B ancorada); (4) **perna CREATE_TEAM obrigatória em company nova**
  (ADR-0037 §5: zero times → recomendação → "sim" na thread → 4ª ação → APPROVE cria
  time+arquiteto e RETOMA) — estava fora do roteiro; (5) efeito colateral do P1
  roteirizado (RecusaKickback agregado = run LLM real na Teams Demo; onda nova de
  propostas FICA, fail-open); (6) empurrão de wakeup é SEMPRE (0037 §3, sem wakeup
  automático); (7) planos B de não-determinismo (blocos tipados em prosa → re-prompt
  do dono; D10 → 2 re-tentativas com pesquisa indispensável, senão FAIL PARCIAL
  decidido na hora); (8) o +1 CREATE_ISSUE PENDING está na Teams Demo mas a lista H
  situava a cascata parada na Demo Fixtures → reconciliar a origem no GATE 0 (SELECT),
  sem tocá-lo. O padrão fecha em **9/9: TODO kickoff verificado tinha erro/lacuna real.**

## Rodada 21 — handoff PARCIAL da R21-A (recebido e VALIDADO pela sessão de controle, 2026-07-16)

Handoff entregue pelo dono direto nesta sessão (a janela de tasks paralelas caiu). **Validado
independentemente** — git/gh + suíte re-executada + DB vivo — não apenas aceito.

- **Branch/PR:** `fix/persona-kickback-park-for-human` (fix `a80d2bf`) → **PR #33 MERGED**
  (main `83c5fe9`). A faixa era **"ZERO commit" por contrato** — quebrou por um **bug de v1 achado
  ao vivo**. Decisão correta: o achado é exatamente o que a prova existia para pegar.
- **Arquivos (5, +158/−2):** `handoff/ConversationContinuationGuard.java` (NOVO) ·
  `handoff/ContinuationTurnPoster.java` · `conversation/persona/PersonaContinuationPoster.java` ·
  `handoff/ContinuationTurnPosterTest.java` · `docs/adr/0030-loop-handoff-contexto.md` (emenda).
- **Vxx/ADR:** **NENHUM consumido** — zero migração, zero ADR nova. **V30+ e ADR-0043+ LIVRES.**
- **Piso:** 736 → **738** backend (+2) · front **294** inalterado — **MEDIDO por esta sessão** na
  `83c5fe9` (`mvn package`: 738/738, BUILD SUCCESS).
- **Ficou por fazer:** **P2 · P3 · P4** + handoff final → declaração v1.

### O achado (o que a prova comprou)
Loop de persona **kickback↔continuação**: o Questionador re-grelha o dono; o run que entrega a
pergunta cruza o limiar de contexto → a rede ADR-0030 retoma o plano → re-bate a MESMA lacuna →
outro kickback → outra continuação. **As DUAS travas de corte contam só o próprio tipo de turno
consecutivo** (`consecutiveContinuationsAtTail=5` · `kickbacksSeguidosNoTail=5`) — **a alternância
zera as duas**: nada corta, o run queima e o dono nunca recebe a vez. Visto **2× ao vivo** (incl.
sem restart mid-flight). **O teste unitário passava** — o loop só aparecia ao vivo, através de
muitos runs. Re-carimba o precedente `verify-tool-de-agente-com-motor-real`.

**Fix = park-for-human** (emenda ADR-0030): suprime a continuação quando há kickback no tail sem
fala real do dono depois. Interface no `handoff/`, impl na persona (sem ciclo). Provado: 2 testes
novos **provados a pegar o bug** (desligar o park → `expected 0 but was 1`) · suíte 738/738 ·
**/verify AO VIVO** A/B (kickback pendente → 0 continuações; dono responde → 5 + cap corta).

### Decisões novas (com racional)
1. **Ambiente da prova = host `java -jar`, NÃO container** (achado OPS, não bug de produto): o
   container não acessa `docker` → motores inviáveis. Imagens vivem no host
   (`orq-claude-code:2.1.201` · `orq-codex:0.142.5`); auth claude por `CLAUDE_CODE_OAUTH_TOKEN`
   **ou** volume `~/.claude` — branco = vale o login do volume, **boot não exige credencial**.
2. **P1 provado via APPROVE, não REJECT** — o dono aprovou os 27 em lote (mesma
   `fabricaDecisaoDoLote` do reject). **A via REJECT+RecusaKickback foi DOBRADA no P2.**
3. **Fix do loop = park-for-human**, sem mexer nas contagens M9/N=5 (seguem válidas para loops de
   um-tipo-só).
4. Incidente colateral **neutralizado** (conversa `bedc10b0`, Teams Demo): run QUEUED→CANCELLED +
   turno PENDING→FAILED. **Não reabrir a Teams Demo na prova.**
5. `da239e6d` (CREATE_ISSUE PENDING, "Definir nicho") **FICA** — reconciliado na Teams Demo.

### Validação independente desta sessão (2026-07-16)
- **git/gh:** PR #33 MERGED; `main` local fast-forwarded `9d71211`→**`83c5fe9`**.
- **Suíte re-executada:** **738/738 BUILD SUCCESS** na `83c5fe9`.
- **DB vivo (read-only) — P1 CONFIRMADO NO DADO:** flyway topo **V29** · **HIRE_AGENT = 27
  APPROVED** · **CREATE_ISSUE = 1 PENDING** (`da239e6d`) · **`decision` seq 1..9 na Teams Demo:
  seq 1–2 com `approval_id` (as 2 de antes) + seq 3–9 com `approval_id NULL`** = exatamente **7
  novas, 1 por área, via `Anchor.NoLote`** — **NÃO 27 clones. O fix #29 provado no dado.**
- **Zero pendências vivas:** `heartbeat_run` 0 QUEUED/RUNNING (3 CANCELLED · 10 FAILED · 109
  SUCCEEDED) · `conversation_turn` 0 PENDING (79 ANSWERED · 4 FAILED) — neutralização confirmada.

### Ambiente RESTAURADO por esta sessão (o bloqueio do handoff está RESOLVIDO)
- **`:8080` UP** — PID 46677, `java -jar orq-app.jar` da raiz do workspace, jar rebuildado da
  `83c5fe9`; `unzip -l` confirma `ConversationContinuationGuard` + `RepeatedFailureBreaker` +
  `CompanyMemoryMaterializer` **dentro do jar**; boot 7,5s limpo; health UP.
  Log `/tmp/orquestrador-8080.log`.
- **`:3000` UP** — PID 46806, `next dev`, proxy pro `:8080` provado (HTTP 200 em `/api/companies`).
  Log `/tmp/orquestrador-3000.log`. DB `agente-orquestrator-db-1` healthy (13 dias).
- **NÃO usei o `-Dorquestrador.handoff.context-threshold-percent=99`** do `g5-vendo-operacao.sh`
  (tuning do G5): a prova precisa do **default 55** real — o loop de persona nasce justamente no
  cruzamento do limiar.

### ⚠️ Achados de ESTADO desta validação (levar no micro-PR da declaração v1)
1. **O roadmap ficou STALE — o fecho documental (R20-A/PR #32) carimbou o estado final e o PR #33
   landou CÓDIGO depois dele.** `docs/roadmap-interno.md:184` ainda declara "(`main fd3be27`; piso
   canônico **736**/294). **ZERO fatia de código restante**" e **não existe linha do #33 / do
   park-for-human de persona**. A emenda da ADR-0030 entrou no PR (conferida no diff) — o buraco é
   só no roadmap. Levar: main `83c5fe9` · piso **738**/294 · linha do #33 · reler a nota de estado
   *(Reconciliação da PRINCIPAL, 2026-07-16: handoff da satélite recebido; PR #33
   re-conferido via gh — MERGED pelo Matheus 02:58Z; branch `fix/persona-kickback-park-
   for-human` PODADA local+remota pela principal — chore que faltava; cópia durável em
   `scratchpad/handoff-volta-satelite-absorcao-r21a.md`. Próxima ação = DONO cria a
   issue órfã em /prova-v1/issues → prova retoma P2 b→i.)*

## Rodada 21 — FECHAMENTO: v1 DECLARADO FECHADO PELO DONO · FASE NOVA aberta (2026-07-16)

Handoff FINAL da R21-A recebido e validado. Fonte completa (bug, conserto grelhado,
achados, método da fase, erros da faixa): **`HANDOFF-prova-final-v1.md`** (raiz,
untracked — agora o 4º de contrato; destino = precedente docs/handoffs/, na carona da
fatia do bug #1). Validação da principal: main `83c5fe9` INTOCADA (**zero commit
honrado**), working tree = 4 untracked de contrato, V29 última migration, PRs #1–#33.

- **P2 pernas (a)–(f): PASS** com motor claude REAL e UI real (o dono WAIVED a regra
  "escritas só pelo dono" e mandou a faixa tocar — registrado): **(b) correção de
  roteiro:** a absorção é ATÔMICA com a criação da issue (ADR-0037 §1) — o roteiro
  anterior mandava `POST /wakeups` citando o §3, que fala do ARQUITETO; o wakeup teria
  criado run CONCORRENTE · **(c)** CREATE_TEAM 4ª ação com time+arquiteto na mesma tx e
  RETOMA automática · **(d)** grill 3 rodadas com racional de cargos ao vivo (Q derrubou
  7 agentes → 2) · **(e)** plano A/D12 FUNCIONOU (o motor viaja no CARD, não no lote —
  `claude-code` atravessou Q→P→E→gate) · **(f) DOBRA DO P1 FECHADA:** REJECT+
  RecusaKickback = mesma `fabricaDecisaoDoLote` (Anchor.NoLote, `approval_id` NULL),
  e o motivo chegou VERBATIM ao CEO e mudou o comportamento dele. **R18-A provada nos
  DOIS lados.**
- **🔴 BUG GRAVE = item #1 do roadmap de ajustes:** o agente contratado pelo CEO nasce
  MUDO (`ApprovalService:348` contrata com `runtimeCommand=null`; preset sem o campo;
  `AgentHireService:73` passa cru) → adapter com comando vazio = no-op VERDE
  (Claude:69/Codex:77/Process:38) → `settleAfterRun:172-179` → **DONE fantasma**
  (run medido: 58,6ms, zero motor). Causa-forma: **assimetria** — o turno tem compositor
  (`composePrompt`), a ISSUE não tem; `runtime_command` estático era o tapa-buraco.
  Sobreviveu porque TODO smoke digitava a tarefa à mão (a issue era enfeite); esta prova
  foi a 1ª vez que um agente contratado PELO CEO executou. Cascata: a ESTRATÉGIA não
  chega em quem trabalha (workspace da conversa ≠ workspace agente+issue; endereço só no
  texto do sentinela). **Pernas (g)/(h)/(i) e P3/D10 BLOQUEADOS por ele.**
- **CONSERTO GRELHADO (4 decisões do dono, 2026-07-16 — não reabrir):** (1) escopo = os
  DOIS problemas independentes: a issue passa a instruir (compositor) E run que não fez
  nada nunca fecha issue (trava); (2) `runtime_command` = SÓ comando de script
  (`process`) — o null do gate vira CORRETO; (3) o compositor carrega `#N`+título+
  descrição+ESTRATÉGIA (recusadas: só-issue; via memória R19-A — reabriria C10.b);
  (4) **V30**: o sistema ANOTA o endereço da estratégia que o Planejador declara no
  bloco `handoff` (recusadas: convenção ditada — falha silenciosa; estratégia no banco —
  contraria índice-não-conteúdo/0019). Forma: içar a composição ACIMA do galho
  turno/issue no `AdapterRunExecutor` (seam já existe: `acceptsPromptAsCommand`+
  `PROMPT_ENV`, :264-269) — completar uma simetria da qual o código já tem metade.
  Âncora de domínio = verbatim do dono na ADR-0037 §1 ("issue é conversa DIRECIONADA").
- **DECISÃO DO DONO: v1 FECHADO (2026-07-16, verbatim)** — supersede a recomendação
  técnica da faixa ("não declarar"); registrada como DELE. O bug vira roadmap de
  ajustes, não trava de declaração.
- **FASE NOVA (dono, verbatim): DESCOBERTA DE BUGS** — as ADRs viram a ESPECIFICAÇÃO;
  cada funcionalidade descrita é testada AO VIVO (motor real, UI real) e classificada
  funciona/não-funciona → roadmap de ajustes. Justificativa: a prova de UMA fatia achou
  1 bug grave + 4 achados em ~4h que o piso 738/294 VERDE não viu. **4 lições de caça
  (§10 — método da fase):** (1) a ADR afirma, o DADO decide (invariante declarado =
  hipótese testável); (2) bug mora no CRUZAMENTO entre ADRs, não dentro da peça
  (assinatura 3×: #33 · §7(8)×0030 · bug #1); (3) testar pelo PRODUTOR REAL do insumo,
  nunca por fixture (o caminho que o fixture não pisa é onde o bug vive); (4) não julgar
  sistema EM VOO (esperar 0 runs ativos E 0 turnos PENDING).
- **Achados v1.1 (§6 — nenhum quebra-prova):** (1) 🔴 vão **ADR-0037 §7(8) × ADR-0030**:
  "freio humano por construção" DESMENTIDO pelo dado (a continuação retoma sem o humano
  e refez a triagem; parkedForHuman do #33 só pega KICKBACK — para-e-avisa não é
  kickback); (2) `HIRE_AGENT` não carrega `teamId` (executor nasce FORA do time que o
  gate desenhou; só organograma manual conserta); (3) `FILTRO_TIPOS` stale (3 de 7
  ações — a fila lista card em que não age; não-bloqueador, o ThreadCard decide);
  (4) "você" chumbado em turno do SISTEMA (`issue-conversa-section.tsx:44`, com UUID
  cru); (5) cosmético ".."; (+) herdada do P1: lote sem feedback na UI; (+) config
  `frontend-prova` no launch.json.
- **Erros da faixa REGISTRADOS (§9)** — lição de controle: laudo de subagente sobre
  premissa minha NÃO é evidência; o dado é. Não julgar snapshot em voo.
- **Sujeira da fixture `prova-v1` (registrada; NÃO neutralizar):** a #1 está DONE
  INCORRETAMENTE · Pesquisador sem time · `18db8641` REJECTED · sistema em repouso
  (0 run ativo, 0 turno pendente). Vira massa de teste do conserto (a #1 é o caso real
  para reabrir/re-executar) e da descoberta.
- **Drift herdado p/ a carona da fatia #1:** roadmap:184 "ZERO fatia de código
  restante" agora DUPLAMENTE falso (#33 + a fatia do #1) · roadmap:92 cita
  `IssueService:108` mas o tryBlock mora na `:182` · "park-for-human" sobrecarregado
  (ADR-0011 × #33) · falta linha do #33 · declaração v1 FECHADO pelo dono a carimbar +
  abrir seção "roadmap de ajustes".

**★ RODADA 21 ENCERRADA. v1 DECLARADO FECHADO PELO DONO (2026-07-16). PRs #1–#33.
Piso 738/294 na `83c5fe9`. A prova se pagou: o método da fase nova nasce provado.**

**Rodada 22 (emitida 2026-07-16) — a 1ª da fase de descoberta/ajustes:**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R22-A** | **Conserto do bug #1 do roadmap de ajustes** (desenho GRELHADO pelo dono, 4 decisões — handoff da prova §4): (a) compositor do lado da ISSUE (içar acima do galho turno/issue; `#N`+título+descrição+ESTRATÉGIA; seam `acceptsPromptAsCommand`+`PROMPT_ENV` já existe) · (b) trava: run que não fez nada NUNCA fecha issue · `runtime_command` = só script/`process` (null do gate vira correto; gêmeo do CREATE_TEAM `:405` idem) · **V30**: endereço da estratégia anotado do bloco `handoff` (nome VARIA por etapa — last-wins) · /verify reproduz o bug ANTES + fecha as pernas (g)(h)(i) da prova na descartável (motor real ≥1 perna; D10 de carona se sair) · **carona docs**: drift do roadmap (:184/:92/park-for-human/linha #33) + carimbo "v1 FECHADO pelo dono" + abrir seção Roadmap de ajustes (achados §6) + mover HANDOFF-prova-final-v1.md p/ docs/handoffs/ | código (/tdd + /verify :18081 descartável; ⚠️ :8080/:3000/pg do dono VIVOS e intocáveis); branch `fix/issue-compositor-adr0037` de `83c5fe9`; checkout compartilhado | **V30 CONSUMIDA** · emendas junto do código (0037 §2 + 0011 + 0004) · 0043 NÃO precisou (consumida em seguida pela frente Spring AI) | **✅ FECHADA — MERGED (PR #34, main `4c1b35f`, OK do dono 2026-07-16); piso canônico 750/294 RE-MEDIDO na main mergeada** — handoff de volta abaixo |
| **R22-B** | **PLANO DE CAÇA da descoberta de bugs** (método do dono §10 + 4 lições): inventário de TODAS as afirmações testáveis das 42 ADRs (+emendas, âncora ADR:linha) · classificação 4-estados (já-provada-com-evidência-registrada / testável-agora / bloqueada-pelo-#1 / não-testável) · priorização por FRONTEIRA entre ADRs + produtor real · Bateria 1 roteirizada (executável sem o fix #1) · esqueleto do roadmap de ajustes. Produto = `docs/plano-descoberta-bugs.md` (untracked novo, entra no contrato) | planejamento (ZERO código/commit/branch; vivo = somente leitura; leituras + 1 arquivo novo) | — | **EM VOO** (kickoff emitido 2026-07-16, verificado adversarialmente) |

- **Colisão R22-A × R22-B: zero por construção** (B escreve só 1 arquivo novo untracked;
  atenção única registrada: B lê o handoff da prova na raiz OU já em docs/handoffs/ —
  a A o move em voo; o kickoff B manda procurar nos dois).
- **Verificação adversarial (workflow 3 agentes, 244k tokens):** R22-A 20 claims
  (16 confirmados; 3 imprecisos corrigidos: âncora do verbatim = ADR-0037 Contexto
  :12-13/§2 · range CorrecaoDeEntregaRouter :131-147 · path do roadmap na carona;
  bônus: o GÊMEO do null no CREATE_TEAM :405) + **completude FALTAM_ITENS, 5 lacunas
  incorporadas** (fixture #1 fora da fatia · nome da estratégia varia por etapa →
  condiciona a V30 · report explícito = ajuste de prompt permitido · timing do wakeup
  vs absorção atômica · /verify 100% API, gotchas de UI declarados fora). R22-B 13
  claims (8 confirmados; 5 imprecisos corrigidos: exemplos da lição 1 realinhados ao
  §10 · dois desvios de produtor na lição 3 · "perdeu horas" sem fonte → reescrito ·
  espelho do item #1 condicionado · âncoras C1-C16/D1-D7 + conta dos achados).
  **O padrão fecha em 10/10: TODO kickoff verificado tinha erro ou lacuna real.**

**FILA da fase:** R22-A merge → (destrava) baterias com caminho de execução; R22-B
volta → controle emite a Bateria 1 (testável-agora) — as baterias são faixas próprias.
Re-execução viva da #1 da `prova-v1` = massa da 1ª bateria pós-fix. 2-em-voo: **2/2**.

### Handoff de volta — R22-A (2026-07-16, faixa encerrada; EM PR)

- **Branch/PR:** `fix/issue-compositor-adr0037` (1 commit `f821a5d` de `83c5fe9`) → **PR #34
  MERGED pelo dono (2026-07-16) — main `4c1b35f`; piso 750/294 re-medido na main; branch local
  deletada**. HEAD do checkout em `main`.
- **Arquivos (código):** `execution/IssuePromptComposer` (NOVO — o compositor da issue, par do
  `composePrompt` de turno) · `AdapterRunExecutor` (composição içada acima do galho turno/issue;
  traduz no-op do adapter em `RunOutcome.noop` só com Issue; `issueConversationId` agora vem da
  Issue do checkout — 1 query a menos) · `RunOutcome` (+`noop`) · `AgentAdapter.Result`
  (+`noop`/`okNoop` — os 3 adapters sinalizam comando vazio) · `IssueService.settleAfterRun`
  (guard: sucesso && !noop; noop → BLOCKED, nunca DONE nem review) · `Conversation`
  (+`bastaoPath`/`anotarBastao`) · `PersonaContinuationPoster.postBaton` (anota last-wins, só
  quando o bastão POSTOU — dedup não move o endereço). Testes: 4 classes novas (12 casos) +
  2 lifecycle ajustados (dependiam do DONE fantasma — pinavam o bug sem querer).
- **Migração:** **V30 CONSUMIDA** (`V30__conversation_bastao_path.sql` — coluna nullable, SEM
  backfill: texto histórico de sentinela não é fonte de parse confiável; conversa velha anota no
  próximo bastão). Aplicada por Flyway em banco POPULADO no /verify.
- **Emendas (junto do código, zero ADR nova — 8º precedente):** ADR-0037 §2 (compositor/decisões
  2-4 + recusadas do grill) · ADR-0011 (trava no settle: noop→BLOCKED; interação com o breaker
  R18-B registrada) · ADR-0004 (Result.noop + command da issue por-run, espelho da Revisão
  2026-07-04 do turno).
- **Piso:** **750 back** (738+12) / **294 front** (intocado; tsc/lint N/A — zero arquivo de front).
- **/verify (descartável :18081 + `r22a-pg`, vivo intocado — health :8080 200 antes/depois):**
  reproduzido ANTES na main `83c5fe9` (fluxo real: cascata Q→P→E process → gate APPROVE →
  `runtime_command` NULL → wakeup → run `7e35406c` **59,0ms/3 eventos/zero motor → #1 DONE
  fantasma**; clone do `e67ff7dd`) · **(g)** claude REAL (22,7s) citou `PRC-7391-XY` — código que
  só existia na `.orq/conversa/estrategia.md` do workspace DA CONVERSA (produtor real: run do
  Planejador gravou; V30 anotou ao vivo) · **(h)** ```correcao``` → corretiva #3 ligada à #2,
  Decisão ancorada com verbatim · **(i)** memoryFile da corretiva materializou a Decisão (R19-A)
  e o claude REAL a citou no report · **trava**: process sem comando + issue → run FAILED
  legível + issue BLOCKED (lock liberado) → REOPEN + conserto → DONE legítimo.
- **O que ficou / decisões novas (com racional):** (1) **noop = falha do run** (FAILED com erro
  legível) — a trilha de runs não finge verde; consequência: o breaker R18-B conta e trava o par
  reincidente (desejado — destrava = wakeup manual, como lá). (2) **Cinto no settle além do
  executor** — mesmo outcome "verde" com noop não completa (o invariante mora onde o DONE é
  cunhado). (3) **Anotação V30 só quando postBaton posta** (dedup não move o endereço — anotar
  sem postar mentiria o fluxo). (4) **Estratégia ilegível → briefing sinaliza a lacuna**
  (anti-silêncio) em vez de falhar o run ou calar. (5) **D10 NÃO fechou de carona** — o claude
  real não abriu web/Task nos 2 runs (as issues pediam sem ferramentas); não forçado (plano B da
  R21-A) — segue pendência da descoberta. (6) Fixture `prova-v1` do vivo INTOCADA (contrato).
- **Pós-merge (2026-07-16):** ✅ piso re-medido na main `4c1b35f` (**750/294**, ambos verdes) ·
  ✅ branch local deletada · RESTA: re-execução viva da #1 da `prova-v1` (massa da 1ª bateria,
  com o dono) · carimbo do item 1 do "Roadmap de ajustes" (roadmap:224, "EM PR" → "MERGED
  PR #34") fica para a próxima passada docs — o arquivo está com WIP não-commitado da frente
  Spring AI/ADR-0043 (Frente E) e um commit agora arrastaria trabalho alheio · R22-B nota: o
  handoff da prova agora mora em `docs/handoffs/`.
- **Observação de ambiente (fecho):** durante o fecho havia WIP docs-only de OUTRA sessão na
  main (CONTEXT.md · ARQUITETURA.md · adr-0001 · roadmap "Frente E" + untracked ADR-0043/
  plano-spring-ai) — preservado via stash→ff→pop (auto-merge limpo do roadmap). 2-em-voo: 1/2
  (R22-B segue; a frente Spring AI aparenta ser faixa nova do controle).
   final.
   - Colateral: **"park-for-human" está sobrecarregado** — `roadmap:92` já usa o termo para
     "Recovery de Issue travada por Run FAILED" (ADR-0011), que é OUTRO mecanismo. Desambiguar.
   - **A citação do `roadmap:92` está STALE** (achada na validação final desta sessão): aponta
     `work/issue/IssueService.java:108`, mas o `tryBlock` guardado por runId + `ISSUE_BLOCKED` mora
     hoje na **`IssueService:182`** (doc do park-for-human em `:164`) — a `:108` é o meio do método
     da **corretiva (caso B)**. O código andou e a citação não. Corrigir no micro-PR.
2. **O GATE da R21-A mudou de base:** o kickoff dizia "rebuild+boot da main `9d71211`" — agora é
   **`83c5fe9`**. Refeito por esta sessão.
3. **Armadilha do `.claude/launch.json` (tracked, `57bf8a3`/R8-A):** a config `frontend` força
   `BACKEND_ORIGIN=http://localhost:18081` — par casado com `backend-vendo` (instância descartável
   do /verify, **morta**). **`preview_start {name:"frontend"}` levaria o dono a uma tela cega.** O
   front defaulta pro `:8080` com o env NÃO setado (`next.config.ts:7`) — foi assim que subi. Não
   toquei o arquivo (é contrato do /verify). Candidata v1.1: config `frontend-prova` sem override.
4. **CANDIDATA v1.1 — UX real que o P1 achou e a narrativa quase enterrou: a aprovação em LOTE não
   dá feedback nenhum na UI.** O dono aprovou os 27 e a tela "não fez nada"; o ato **commitou** (7
   Decisões no banco, confirmadas por SQL), mas sem sinal de retorno. Um dono real re-clicaria ou
   daria o lote por perdido. **Defeito de UX do gate da ADR-0035** — não do fix #29, que está
   correto no dado. Vale ficha própria na varredura de v1.1.

**FILA:** **P2** (company NOVA; via REJECT dobrada; CREATE_TEAM obrigatória; caso B/R19-A na
corretiva) → **P3** (claude real/D10) → **P4** (colaterais, não forçar) → handoff → **declaração
"v1 FECHADO"** (micro-PR docs do controle, com OK do dono, levando os achados de estado acima).
2-em-voo: **1/2 (R21-A retomável — ambiente PRONTO).**
*(NOTA DA PRINCIPAL, 2026-07-17: o bloco FILA acima é REMANESCENTE fora de ordem da
satélite da R21-A — SUPERADO: a prova encerrou, o v1 foi declarado e as rodadas 22+
correram; vale o que segue abaixo.)*

## Rodada 22 — R22-B PARCIAL · FRENTE E (decisão de sequenciamento) · Rodada 23 (2026-07-17)

Três handoffs recebidos numa leva pela principal. Validação read-only: main local =
origin = `4c1b35f` ✓ · zero PRs abertos ✓ · branch `feat/backend-spring-ai-2` HEAD
`3ee016e`, 12 commits, merge-base `83c5fe9` (pré-#34) ✓ · :8080 vivo AINDA no jar
pré-fix (PID 46677, bootado da 83c5fe9) · untracked = 3 de contrato + 2 HANDOFFs de
faixa + `docs/plano-descoberta-bugs.materiais/` (48 arquivos, staging da R22-B).
**HEAD do checkout compartilhado DEVOLVIDO à main pela principal** (a Frente E encerrou
sem devolver — árvore limpa, troca segura).

**R22-B — handoff PARCIAL (faixa pausada, NÃO fechada):** pesquisa completa e
persistida (inventário 42 ADRs → **1067 afirmações em 6 estados**, verificação
adversarial em 39/42 — 0021/0031/0035 estouraram 64k de output ×2 · dossiê de **82
evidências vivas** · mapa de **10 fronteiras** ranqueadas · crítica de completude:
zero fragmento faltando; 1 inconsistência de contagem N/C em 0014/0015 a resolver na
montagem). Produto final `docs/plano-descoberta-bugs.md` PENDENTE. **⚠️ Achado
crítico da faixa (honesto, bem registrado):** o PR #34 mergeou NO MEIO do voo dela →
(a) **134 afirmações ⛔BUG#1 precisam de reclassificação** (a premissa "bloqueado"
morreu); (b) **4 fragmentos extraídos PRÉ-emenda** (0037/0011/0004 confirmados por
diff — faltam 39/23/14 linhas; 0001 com 4 linhas a conferir) porque o checkout estava
na branch da Frente E (pré-#34). 8 pendências ordenadas no
`HANDOFF-r22b-plano-descoberta-bugs.md`. **ADENDO DE RETOMADA emitido** (reconciliar
os 4 fragmentos contra `main` via `git show` · reclassificar as 134 · montar o produto
· staging apagado ao final). Lição de controle REGISTRADA: pesquisa longa em
background num checkout COMPARTILHADO herda troca de branch alheia — kickoffs futuros
de pesquisa devem pinar leituras em `git show main:<path>` desde o início.

**FRENTE E (Spring AI 2) — validação e DECISÃO:** branch `feat/backend-spring-ai-2`
(Java 25 · Spring Boot 4.1 · Jackson 3 · Spring AI 2.0 SÓ starter Ollama, OFF por
default; F-PLAT0+F-SAI0+F-SAI1; **757 testes verdes NA BRANCH**; engines claude/codex
REAIS provados LOCAL+DOCKER; shadow validado com fallback; **ADR-0043 "Aceito ·
decisão do dono" NA BRANCH** · **V31 na branch** convive com a V30 da main — numeração
correta por precaução da própria frente; MCP dentro do Docker = NÃO atendido,
fora do escopo original; ACTIVE/F-SAI2+ não autorizados). Sem PR/push/rebase.
**DECISÃO DO CONTROLE (delegada pelo dono no handoff da frente): OPÇÃO 2 — INTEGRAÇÃO
AUTORIZADA como faixa própria (R23-A), com PR e MERGE SÓ COM OK DO DONO.** Racional:
(a) a DIREÇÃO já é decisão do dono (ADR-0043 Aceito: *"migrar enquanto a aplicação
ainda está em construção, evitando criar código novo sobre uma linha que seria
substituída"* — o mesmo racional manda integrar CEDO, antes de o roadmap de ajustes
produzir código novo na linha velha); (b) parquear apodrece: cada fatia nova na main
aumenta o rebase (o #34 já criou interseção real); (c) em WORKTREE, não bloqueia o
checkout, o vivo nem a R22-B; (d) o gate do merge segue intacto (dono).
**Interseção de conflito COMPUTADA pela principal (22 arquivos do #34 × 101 da
frente): exatamente 3** — `AdapterRunExecutor.java` · `ClaudeCodeAdapter.java` ·
`docs/roadmap-interno.md`. Os 2 primeiros são ONDE O COMPOSITOR DO #34 VIVE → o
/verify da integração re-prova a perna (g) obrigatoriamente.
**Sequência do vivo (decisão da principal):** a 1ª bateria da descoberta roda no v1
DECLARADO — sentada do operador reboota o :8080 da `4c1b35f` (pré-migração, pós-fix);
a integração terá o próprio reboot quando mergear. Findings mapeiam limpo ao v1.

**Rodada 23 (emitida 2026-07-17):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R23-A** | **Integração da Frente E na main** — rebase de `feat/backend-spring-ai-2` (12 commits, base `83c5fe9`) sobre `4c1b35f`: 3 conflitos previstos (AdapterRunExecutor · ClaudeCodeAdapter · roadmap — os 2 primeiros carregam o compositor do #34: preservar OS DOIS lados) · suítes verdes na branch rebasada (esperado ≥757 + os 12 do #34 que não colidirem — a MEDIÇÃO decide) · /verify na descartável re-prova a perna (g) (prompt composto vivo pós-rebase, motor real) + smoke Spring AI OFF (default preservado) · **carona: carimbo do item 1 do "Roadmap de ajustes" (roadmap:233 hoje; mirar por conteúdo — único "EM PR" do arquivo) "EM PR"→"MERGED PR #34"** (destravada — o WIP da E está todo commitado; a citação ":224" da memória estava defasada 9 linhas, corrigida pela verificação) · PR ao final; nota: branch é LOCAL-ONLY (primeiro push) | código (**GIT WORKTREE próprio** `../agente-orquestrator-spring-ai`; /tdd + /codebase-design; /resolving-merge-conflicts SÓ em conflito real; /verify :18081 descartável — nada mais roda lá) | V31+0043 já consumidas na branch (entram na main pelo merge) · V32+/0044+ livres · ACTIVE/F-SAI2+/MCP-Docker FORA (não autorizados) | **CONCLUÍDA E MERGEADA — PR #35 MERGED pelo dono (2026-07-17 13:55Z, main `fef6073`, 14 commits)**; 1 conflito real de 3 previstos; perna (g) re-provada claude+codex REAIS; hash `8d558ff6…` idêntico; ver §Fechamento das rodadas 23/24 |

2-em-voo: **2/2 (R22-B retomada por adendo + R23-A)**. FILA da fase: R22-B monta o
plano → Bateria 1 (pré-requisito: sentada do operador, reboot da `4c1b35f`) →
re-execução viva da #1 da `prova-v1` → roadmap de ajustes.

**NORTE DA FRENTE E RECEBIDO DO DONO (2026-07-17, pós-emissão da R23-A):** racional
completo e estagiado do Spring AI, verbatim — camada de inteligência AUXILIAR,
substituível e controlada sobre o CONTEXTO (contrato `ContextIntelligence`); engines
e compilador determinístico seguem donos do que já fazem; **OFF default → SHADOW
(atual; coleta de evidência: precisão/FP-FN/latência/fallback/custo/egress) → ACTIVE
(NÃO autorizado; gates: métricas SHADOW + provider + política de egress + ADR própria
+ rollback)**; proibições arquiteturais explícitas (sem tools, sem chat-memory, sem
pull automático, sem tx aberta em chamada, falha de provider nunca bloqueia run, nunca
substitui o contexto determinístico); usos futuros só via F-SAI2+ com contrato/testes
próprios. **Registrado durável:** memória `frente-e-spring-ai-racional` + verbatim em
`orquestrador-agentes/scratchpad/racional-spring-ai-verbatim-dono.md`. **Consistência
conferida pela principal: NADA no racional contradiz o kickoff da R23-A em voo**
(OFF preservado ✓ ACTIVE/F-SAI2+ fora ✓ só-Ollama como 1º adaptador ✓ limites = §3 da
ADR-0043 ✓). É a fonte canônica para o gate do ACTIVE e para as fatias F-SAI2+.

**R24-A EMITIDA EM FILA (2026-07-17, /nova-sessao do dono): PESQUISA "como melhorar o
uso do Spring AI"** — read-only + web + 1 doc untracked
(`docs/pesquisa-spring-ai-melhorias.md`); fluxo: deep-research sobre capacidades REAIS
do Spring AI 2.0 × limites do norte → mapa permitido/proibido/condicionado → candidatas
F-SAI2+ ranqueadas com contrato proposto + evidência SHADOW necessária → desenho da
coleta de métricas SHADOW (a régua do ACTIVE) → matriz de providers SEM decidir (gate
do dono) → grill do dono → fatias. Zero colisão com R22-B/R23-A (lê a branch por `git
show`). **FILA por máx-2-em-voo** (R22-B + R23-A ocupam os slots) — kickoff entregue ao
dono, não expira; ele decide quando colar (a regra é dele).

## Fechamento das rodadas 23/24 + R22-B — ⚠️ PROVISÓRIO (2026-07-17)

> **CARIMBO DO DONO (2026-07-17):** este fechamento foi escrito pela sessão de controle
> com a janela de contexto JÁ MUITO LONGA e o dono **NÃO o aceita como validado**. A
> sessão de controle NOVA tem como PRIMEIRA OBRIGAÇÃO re-processar os 3 handoffs
> (R23-A · R24-A · R22-B) e re-validar tudo abaixo antes de confiar. O que é FATO DE
> GIT (chores executados) é conferível barato; o que é JUÍZO (decisões a/b/c, aceite
> da R22-B, leituras dos handoffs) deve ser re-derivado das fontes. **A re-medição do
> piso na `fef6073` NÃO foi concluída** (o front deu 294/294; o backend ficou em voo
> quando o dono interrompeu — 769 é ALEGAÇÃO da faixa, não medição do controle).
>
> ✅ **RE-VALIDADO 2026-07-17 pela SESSÃO DE CONTROLE #3** — ver §"Re-validação do
> fechamento provisório" no fim do ledger. Substância SUSTENTADA (fatos de git/gh
> 100% conferidos; piso **769/294 MEDIDO** pela sessão #3); correções aplicadas:
> 2 typos na tabela §3 do plano de caça (pós-reboot agora **146**) · decisão (b)
> executada (0012-A4 rebaixada) · **premissa de ambiente corrigida: V30/V31 NÃO
> aplicadas no pg compartilhado (flyway para na V29)**.

TRÊS handoffs de volta numa leva. Validação: **PR #35 MERGED pelo dono** (13:55:22Z,
merge `fef6073`, gh conferido) · chores executados pela principal: **ff da main
`4c1b35f`→`fef6073`** (o diff traz a Frente E inteira: pacote `context/` +
`context/intelligence/` + V31 + mvnw + ADR-0043 + plano) · **worktree
`../agente-orquestrator-spring-ai` REMOVIDO** · branches podadas:
`feat/backend-spring-ai-2` (local+remota) · `parked/adr0043-racional-verbatim` ·
`backup/pre-v31-fold`. Untracked = 3 de contrato + 4 HANDOFFs de faixa + 2 produtos
(pesquisa + plano) + materiais/.

**R23-A ✔ (PR #35)** — 1 conflito real de 3 previstos (`AdapterRunExecutor`: o prompt
composto do #34 virou insumo do compilador da E, voltando pelo `ContextPackage`;
**armadilha evitada**: o `if (turno != null)` do lado E teria devolvido o bug #1 EM
SILÊNCIO com suíte verde) · perna (g) re-provada com claude E codex REAIS · trava
no-op FAILED 54ms + BLOCKED · Spring AI OFF (0 assessments) · hash `8d558ff6…`
idêntico ao pré-rebase · Flyway 31/31/0. **Lições de controle REGISTRADAS:** (1)
**worktree próprio protege o CHECKOUT, não a BRANCH** — commit alheio (`8f12a73`,
cherry-pick do dono) pousou na branch durante o rebase; decisão do dono: manter+
consertar (o apêndice cortava LIMITES ARQUITETURAIS alegando cobertura pelo §3 — o §3
NÃO cobre; restaurado verbatim); (2) **a suíte H2 NÃO exercita Flyway** — duplicata de
V30 passou por 769 verdes e só caiu no boot contra pg (eco do "piso verde não vê");
consertada na história, a CEGUEIRA FICA (candidata: teste de migração contra pg).
Sobras pequenas: skill verify manda `mvn` (a frente pinou `./mvnw`) — 1 linha, fecho
da fase; remota histórica do `fix/issue-compositor-adr0037` ainda no origin.

**R24-A ✔ (pesquisa Spring AI)** — produto `docs/pesquisa-spring-ai-melhorias.md`
(untracked): capacidades com fonte+confiança · mapa capacidade×norte · candidatas
F-SAI2+ ranqueadas (revisadas por human-architect-mindset) · desenho de métricas
SHADOW · matriz de providers SEM decisão · fluxo de validação. **Achado: o
`ToolCallingAdvisor` do Spring AI 2.0 é AUTO-REGISTRADO por default mesmo sem tools —
hardening barato PENDENTE** (não implementado; pauta do grill). Zero código tocado.
**PENDENTE: grill do dono** (ordem das candidatas · pré-aprovação ou não do
experimento cross-provider 1b · hardening) — SEM isso nenhuma fatia F-SAI2+ nasce.

**R22-B ✔ FECHADA (aceite do controle DADO)** — produto
`docs/plano-descoberta-bugs.md` (545 linhas, untracked): **1085 afirmações / 42 ADRs;
⛔BUG#1 EXTINTO** (134 julgadas UMA A UMA → 125 🎯 pós-reboot · 7 🚫 DOCKER · 1 🎯 ·
1 ❓); **145 🎯 pós-reboot no total** — uma sentada do operador destrava 13% do
inventário. Contaminação da Frente E FECHADA (exatamente {0001,0004,0011,0037}; as
outras 38 byte-idênticas). **Bateria 1 PRONTA E NÃO ESPERA NADA** (provado que o #34
não toca frontend/ApprovalService/busca-skill/activity_log/hooks). **Achados novos
com âncora conferida:** N1 — a ESTRATÉGIA só chega na issue nascida no BOARD
(`ancorarConversa` tem 1 caller, a absorção; a porta do gate e a corretiva degradam em
silêncio; roadmap #11) · N3 — o agente do gate falha honesto mas TALVEZ não trabalhe
(depende de `adapterType` na proposta; pergunta central; B1b.1 responde; roadmap #12).
N7–N13 = hipótese-com-âncora (ressalva de método §8.11; 3 de 13 achados de subagente
MORRERAM na conferência — lição "conferir a âncora do subagente" REGISTRADA na
memória). **DECISÕES DO CONTROLE (veto do dono aberto no grill):** (a) DOCKER = item
de roadmap #15 (hire não carrega executionTarget, irmã da :348), NÃO bateria — o
inventário fica com a inconsistência ANOTADA; (b) 0012-A4 REBAIXADA (a evidência
documentava o BUG — pós-#34 o cenário dá BLOCKED; re-testar na 1b); (c) 0021/0031/
0035 = bateria de revisão, não bloqueia a caça. **`docs/plano-descoberta-bugs.
materiais/` NÃO se apaga** (aceito a recomendação: as baterias citam IDs 0037-A46/
0012-A4 que só existem lá; staging vira ANEXO do produto até o fecho da fase).

**Pendência do OPERADOR (atualizada; agora com decisão de baseline no grill):** a
decisão vigente é rebootar o :8080 da `4c1b35f` (v1 DECLARADO — findings mapeiam
limpo) e a integração ter o próprio reboot depois; MAS com o #35 na main existe a
alternativa de UM reboot só (direto na `fef6073`, plataforma nova) — trade-off vai à
pauta do grill (a sentada é dele). O :8080 segue no jar PRÉ-migração (pid 46677).

## TROCA DE SESSÃO DE CONTROLE #3 (2026-07-17, ordem do dono)

A sessão de controle atual encerra por contexto (janela muito longa — regra de ouro do
handoff). Estado NO MOMENTO DA TROCA:

- **FATOS DE GIT (executados pela principal, conferíveis barato — `git`/`gh`):**
  PR #35 MERGED pelo dono (13:55:22Z, merge `fef6073`) · main local = origin =
  `fef6073` (ff feito) · worktree `../agente-orquestrator-spring-ai` removido ·
  branches podadas: `feat/backend-spring-ai-2` (local+remota) ·
  `parked/adr0043-racional-verbatim` · `backup/pre-v31-fold` (locais) · HEAD em main.
- **Untracked (10):** 3 de contrato (ledger · laudo-lease · handoff-auditoria) +
  4 HANDOFFs de faixa (r22a · r22b · r23a · r24a) + 2 produtos
  (`docs/pesquisa-spring-ai-melhorias.md` · `docs/plano-descoberta-bugs.md`) +
  `docs/plano-descoberta-bugs.materiais/` (48 arquivos, staging citado pelas baterias).
- **PROVISÓRIO (re-validar):** todo o §"Fechamento das rodadas 23/24 + R22-B" acima —
  incluindo as decisões do controle (a) DOCKER=roadmap#15, (b) 0012-A4 rebaixada,
  (c) 0021/0031/0035=bateria de revisão, o aceite da R22-B, a manutenção do
  materiais/ e a pauta do grill. **Piso: última medição CANÔNICA = 750/294 na
  `4c1b35f`**; na `fef6073`: front 294/294 confirmado; backend **769/769** (a medição
  terminou DEPOIS da interrupção do dono — coincide com a alegação da R23-A, mas foi
  colhida em contexto longo: a sessão nova decide se re-mede para régua própria;
  comando: `./mvnw test`, NUNCA `mvn` — a skill verify está defasada nisso).
- **2-em-voo: 0/2.** FILA esperada (sujeita à re-validação): Bateria 1 (roteiro no
  produto §6 — roda HOJE no jar pré-fix) · grill do dono (pesquisa Spring AI:
  candidatas/cross-provider-1b/hardening ToolCallingAdvisor + decisões provisórias
  a/b/c + baseline do reboot) · roteiro da sentada do operador.
- **PRIMEIRA OBRIGAÇÃO DA SESSÃO NOVA (ordem do dono):** re-processar os 3 handoffs
  de volta (arquivos na raiz) e RE-EMITIR as 3 mensagens de continuação do paralelo.
  Handoffs de volta futuros chegam à SESSÃO NOVA.

### Handoff de volta — R23-A (2026-07-17, faixa encerrada; **PR #35 MERGED pelo dono**)

- **Branch/PR:** `feat/backend-spring-ai-2` rebasada sobre `4c1b35f` → **PR #35 MERGED pelo dono
  2026-07-17 13:55Z — main `fef6073`** (https://github.com/matheus3006/agente-orquestrator/pull/35;
  14 commits, +3792/−337). O merge saiu DEPOIS do fecho da faixa (a sessão entregou o PR aberto);
  conferido no fecho: `origin/main = fef6073`, branch ancestral, conserto da ADR-0043 e carimbo do
  roadmap na main. **1º push da branch** (era LOCAL-only, sem ref remota — ninguém a sobrescrever,
  como previsto).
- **⚠️ CHORES ABERTOS (controle):** a **`main` do checkout compartilhado ainda está em `4c1b35f`**
  (atrás de `origin/main` — a satélite não tocou o HEAD dele porque a R22-B está lá): falta o
  **ff**, o **piso re-medido na main mergeada** (proposto **769/294**), remover o worktree
  `../agente-orquestrator-spring-ai` e apagar as branches `feat/backend-spring-ai-2` (local+remota),
  `parked/adr0043-racional-verbatim` e `backup/pre-v31-fold`.
- **Conflitos: 3 previstos, 1 real.** (1) **`AdapterRunExecutor` — o único conflito de fato, 2
  hunks, DOIS LADOS preservados:** o prompt composto do #34 vira **insumo do compilador da
  Frente E** (`RunContextCompiler.Request.prompt`) e volta pelo `ContextPackage`, que passa a
  ser o portador vivo de prompt/schema. **⚠️ A ARMADILHA (registrar):** o lado da Frente E
  reatribuía `command`/`env` sob `if (turno != null)` — exatamente o gate que o #34 REMOVEU.
  Mantê-lo devolveria o bug #1 **em silêncio, com a suíte verde** (run de issue sem prompt).
  Agora são incondicionais, derivados de `contextPackage.prompt()`. (2) **`ClaudeCodeAdapter`:
  SEM conflito textual** — a E só reescreveu o import (Jackson 3), o #34 mexeu no `execute()`;
  `okNoop` intacto. (3) **roadmap:** trivial. Imports/campos/construtor auto-mergearam certo
  (`issueComposer` + `contextCompiler`/`contextIntelligence` convivem).
- **Decisão nova (minha, registrada — não é squash):** o rebase simples deixava **4 commits
  intermediários com DOIS arquivos na versão Flyway V30** (o V30 da main + o da frente pré-rename)
  → Flyway rejeita versão duplicada → esses commits não sobem, quebrando o *"rollback = commit
  anterior verde"* da **ADR-0043 §2**. Defeito **CRIADO PELO REBASE** (a branch original era verde
  em todo commit). Consertado dobrando o rename no commit que introduz o arquivo (nasce V31) e
  dropando o `fix: reserve Flyway V31…` (redundante — seu propósito ERA reservar V31 p/ esta
  integração). **Árvore final idêntica** ao rebase simples (verificado por diff). 12 → **11
  commits** (+3 docs: o alheio, minha correção e a carona) = 14 no PR.
- **⚠️ COMMIT ALHEIO NA BRANCH (o controle precisa saber):** `8f12a73 docs: preserve owner Spring
  AI rationale` apareceu **durante o meu rebase** (10:10, autor Matheus, cherry-pick que esta
  sessão NÃO rodou, aplicado sobre um commit que o meu rebase tinha acabado de criar; o commit de
  origem não é alcançável por ref nenhuma). Preservado em `parked/adr0043-racional-verbatim`.
  **Perguntei ao dono; ele mandou MANTER e CONSERTAR.** O apêndice dizia "verbatim" mas **cortava
  o parágrafo `LIMITES ARQUITETURAIS`** alegando incorporação no §3 — **o §3 não cobre**: faltavam
  segredos/PII sem política, log de prompts/respostas sensíveis, executar tools, pull automático
  de modelos, transação aberta durante chamada e "falha de provider não bloqueia o run". Como a
  ADR é a **fonte canônica do gate do ACTIVE**, o corte apagava justamente o que o gate precisa.
  Restaurado verbatim (`6ca7ec8`); apêndice agora byte-idêntico à fonte (73/73, conferido).
  **Lição de controle:** worktree próprio NÃO protege a BRANCH de escrita alheia — só o checkout.
- **Testes:** **backend 769** (0 falhas/erros/skips; 757 da branch + 12 do #34) medido em árvore
  **limpa**; **front 294** (21 arquivos, byte-idêntico à main). Os 4 do #34 vivos e verdes:
  `IssuePromptComposerOnRunTest`(5) · `AgentRunNoopTest`(2) · `IssueNoopNuncaFechaTest`(3) ·
  `ConversationBastaoAnotadoTest`(2). **Piso proposto pós-merge: 769/294** (o controle re-mede).
- **/verify (descartável :18081 + pg efêmero; :8080/pg do dono e o `orq-sai-pg` da frente
  intocados):** **perna (g) RE-PROVADA** — agente contratado (`runtime_command` **NULL**) executou
  a issue e o **claude REAL citou o sentinela `R23A-SENT-9F42-KQ`** que só existia na estratégia do
  workspace DA CONVERSA (V27→V30→`readWorkspaceFile`); run de **19,7s** com report + artefato (não
  o relâmpago de 59ms). **Codex REAL** idem (45,1s). **Briefing composto capturado LITERAL** pelo
  canal `ORQ_PROMPT` (`#N`+título+descrição+**estratégia**+report) → os **2 canais** do seam T.3
  provados. **Trava no-op:** `process` mudo → run **FAILED 54ms** legível + issue **BLOCKED**
  (nunca DONE); `RUN_CONTEXT_USAGE actual_input_tokens=0` corrobora por instrumentação da própria
  E. **OFF:** `context_assessment` **0 linhas**. **Hash determinístico `8d558ff6…b2b2b2 / 2471 B /
  618 tok` IDÊNTICO ao registrado na ADR-0043 pré-rebase** (o prompt não entra no hash — a
  reconciliação não o perturbou). **Flyway:** 31 migrations / 31 versões / 0 falhas, V29→V30→V31.
  **Probe:** estratégia anotada-mas-ilegível → briefing **SINALIZA a lacuna** (anti-silêncio vivo).
- **Achados (não consertei — fora de escopo):** (a) **a suíte H2 não exercita Flyway** — o V30
  duplicado passou verde por 769 testes e só caiu ao BOOTAR contra pg; se o piso é a única guarda,
  colisão de migration chega em runtime (eco da lição "o piso verde não vê"). (b) `mvn -o package`
  da skill `verify` está defasado — a frente pinou o **Maven Wrapper** (ADR-0043 §1); usei
  `./mvnw` (não editei a skill: o comando não me steerou errado, só é velho). (c) codex CLI
  0.142.5 loga `ERROR codex_models_manager::cache: failed to load models cache: missing field
  supports_reasoning_summaries` e ainda assim conclui — ruído do CLI, não do backend (vizinho do
  caveat de modelo já registrado na ADR). (d) inserir `conversation` direto no pg **acorda as
  personas CEO** (5 runs de Planejador reais na fila) — a sonda ficou QUEUED atrás delas; custo,
  não bug.
- **FORA, como mandado:** `ACTIVE` · F-SAI2/3/4 · métricas SHADOW · provider/egress · ADR-0044 ·
  **MCP dentro do Docker** (segue NÃO atendido, registrado como está — não "consertado").

## Re-validação do fechamento provisório — SESSÃO DE CONTROLE #3 (2026-07-17)

**Ordem do dono CUMPRIDA:** os 3 handoffs re-processados do zero e o §"Fechamento das rodadas
23/24 + R22-B" re-validado contra git/gh/arquivos/ambiente — workflow adversarial de 6
verificadores paralelos (~40 claims, cada um com evidência file:line/hash/saída de comando) +
re-medição própria das suítes. **Veredito global: a SUBSTÂNCIA do fechamento provisório
sustenta-se** (fatos de git/gh 100% conferidos), com **2 typos reais** corrigidos no produto da
R22-B e **1 premissa de ambiente FALSA** corrigida (a lição alegação≠medição pagou de novo).

- **Piso CANÔNICO: 769/294 na `fef6073`, MEDIDO pela sessão #3** (backend `./mvnw test` 769/769
  em 39,8s; front `vitest run` 294/294 em 21 arquivos; zero falha/erro/skip). Deixou de ser
  alegação de faixa — tabela de Reservas atualizada.
- **R23-A ✔ RE-VALIDADA (fatos):** PR #35 MERGED `2026-07-17T13:55:22Z` exato · merge `fef6073` ·
  14 commits · +3792/−337 · head `feat/backend-spring-ai-2` · `8f12a73` autor "Matheus de Souza
  <matheussouza18@live.com>" (mensagem verbatim) · `6ca7ec8`/`034e62b` presentes · apêndice da
  ADR-0043 **byte-idêntico** ao verbatim do dono (md5 igual dos 2 lados; LIMITES ARQUITETURAIS
  inteiro; precisão: o arquivo fonte tem 80 linhas TOTAIS — 73 é o corpo verbatim comparado) ·
  migrações V1..V31 únicas e contíguas (V30+V31 presentes) · carimbo do roadmap item 1 na linha
  233 e zero "EM PR" no arquivo · 43 ADRs sem buraco (0041 EXISTE, escrita no R16-B) · a
  armadilha do `if (turno != null)` está MORTA na main: command/env incondicionais em
  `AdapterRunExecutor:235-240`, derivados de `contextPackage.prompt()`; `issueComposer` +
  `contextCompiler`/`contextIntelligence` convivem (:65-66,:79).
- **R24-A ✔ RE-VALIDADA (o verificável local):** produto completo (6 seções) · config da main
  confere com a pesquisa (`application.yml:29-31` log-prompt/log-completion/include-error-logging
  false · `:34` pull-model-strategy never) · hardening `tool-calling.enabled` DE FATO pendente
  (git grep tracked = 0). **Nuance NOVA para o grill:** o builder já faz opt-out EXPLÍCITO
  (`SpringAiContextIntelligence.java:90-91` — `.defaultAdvisors(List.of())` +
  `.defaultToolCallbacks(List.of())`) e a main tem `spring.ai.chat.client.enabled: false` global
  (`application.yml:27`) + `ChatMemoryAutoConfiguration` excluída — o vetor NÃO está escancarado;
  o hardening proposto vira cinto-e-suspensório (e o assert de suíte sobre a chain segue
  inexistente). Claims WEB da pesquisa não re-verificadas (fora do conferível barato).
- **R22-B ✔ ACEITE RE-DERIVADO E CONFIRMADO, com correções EXECUTADAS no produto:** (1) typo
  tabela §3 0037×pós-reboot **12→13** (o fragmento `adr-0037.md:125` declara e contém 13); (2)
  typo TOTAL×🔴 **16→17** (as 42 linhas somam 17; handoff registra "19→17"); (3) decisão (b)
  EXECUTADA — `0012-A4` rebaixada ✅→🎯 pós-reboot no fragmento E na tabela (N12: a evidência
  única documentava o próprio bug). **Totais corrigidos: 1085 = 301 ✅ + 17 🔴 + 398 🎯 +
  146 🎯 pós-reboot + 215 🚫 + 8 ❓ — a alavanca do reboot agora é 146.** Materiais/ íntegro:
  48 arquivos · 8/8 IDs de bateria conferidos (só existem lá — preservação RE-CONFIRMADA até a
  última bateria fechar) · dossiê 82 itens, zero entrada pós-fix (nuance: a grafia "no-op"
  escapa ao grep alegado — 3 ocorrências, todas pré-fix) · adr-0021 com 14/14 reclassificadas
  uma a uma com motivo.
- **Decisões (a)/(c) RE-DERIVADAS E MANTIDAS (veto do dono aberto no grill):** (a) DOCKER =
  roadmap #15, NÃO bateria — N6 re-conferido nos DOIS commits (`git grep '"DOCKER"' --
  backend/src/main` = 0 em `4c1b35f` E `fef6073`; default LOCAL `Agent.java:42`; UI chumba LOCAL
  `hire-agent-dialog.tsx:69`; gate passa null nas 2 portas `ApprovalService:348`/`:405`); (c)
  0021/0031/0035 = bateria de revisão em fila, não bloqueia a caça (mitigação re-conferida:
  14/14 âncoras da 0021 ok). Anotações [DECIDIDO/EXECUTADO pelo controle 2026-07-17] gravadas
  no §8 do produto.
- **🔴 DIVERGÊNCIA DE AMBIENTE (corrige a premissa "V27-V31 já aplicadas" do kickoff da troca):**
  o `flyway_schema_history` do pg compartilhado PARA NA **V29** (installed_rank 29, 2026-07-14).
  **V30 e V31 NÃO estão aplicadas** — V27/V28/V29 sim. O plano de caça estava CERTO ("V30 só
  aplica no próximo boot"). Consequência p/ a sentada: reboot da `4c1b35f` aplica V30; reboot da
  `fef6073` aplica V30+V31. Config Flyway mínima e idêntica nos 2 commits (`enabled: true` +
  `baseline-on-migrate: false`; sem validate/ignore/out-of-order explícitos). Sistema em REPOUSO
  na medição (heartbeat_run: 0 QUEUED/RUNNING; conversation_turn: 0 PENDING). :8080 UP (pid
  46677, ~25h), :3000 responde 200, pg `agente-orquestrator-db-1` Up 2 weeks healthy,
  launch.json ainda com a armadilha :18081 (`.claude/launch.json:10`).
- **Âncoras que MOVERAM `4c1b35f`→`fef6073` (aviso operacional das baterias):** SÓ as do
  `AdapterRunExecutor` — gate do skillRequestGate **446→372** · handle **447-448→373-374** ·
  check no-op **458→384** · seleção de prompt **237→206** (em fef6073 ganhou o braço
  `issueComposer.compose` em :209; command/env do `contextPackage` em :235-240). TODAS as demais
  âncoras dos achados N1–N7 idênticas nos 2 commits. Precisão de path: `IssuePromptComposer`
  mora em `execution/`, NÃO em `work/issue/`. Colateral N7 re-confirmado (idêntico nos 2
  commits, não é regressão da R23-A): run no-op de issue ainda alimenta o `skillRequestGate`
  (okNoop tem succeeded=true) antes do `RunOutcome.noop`.
- **Chores executados pela sessão #3:** remota histórica `fix/issue-compositor-adr0037` PODADA
  do origin (mergeada via #34) · cópia durável dos 3 handoffs no scratchpad do projeto ·
  memória: entrada R24-A criada (faltava) + stack corrigida (Java 25/Boot 4.1 pós-#35) +
  carimbos PROVISÓRIO→validado nas entradas r23a/r22b.
- **Sobras/housekeeping NOVAS (p/ grill e sentada):** origin ainda guarda **~20 branches
  feat/fix/docs antigas já mergeadas** (poda em massa = decisão do dono, vai à pauta) ·
  container **`orq-sai-pg` (Frente E) segue RODANDO** (12h, 127.0.0.1:55433) e
  **`agente-orquestrator-backend-1`** Exited há 43h — remoção = sentada do operador · pom.xml da
  main = Spring Boot 4.1.0 (a memória de stack dizia 3.5 — corrigida).

**TRÍADE RE-EMITIDA (2026-07-17, sessão #3), verificada adversarialmente antes da entrega:**
(1) **kickoff da R25-A — BATERIA 1** (faixa própria; fonte = produto §6; roda HOJE no jar
pré-fix; parte A autônoma B1.1/B1.2/B1.4/B1.5 + parte B com [DONO] B1.3/B1.6) · (2) **PAUTA DO
GRILL** do dono (pesquisa Spring AI: candidatas/1b/hardening-com-nuance-nova · decisões
provisórias a/b/c re-derivadas · aceite R22-B/materiais · **baseline do reboot com o fato novo
da V29** · housekeeping novo) · (3) **ROTEIRO DA SENTADA do operador** (reboot :8080 na decisão
vigente `4c1b35f` → aplica V30; alternativa um-reboot-só `fef6073` pende o grill). **2-em-voo:
0/2 no momento** — a Bateria 1 ocupa slot quando o dono colar o kickoff; grill e sentada não
são faixas. FILA: Bateria 1 → (grill) → sentada → Bateria 1b → bateria de revisão
0021/0031/0035 → F-SAI2+ (só pós-grill).

**Rodada 25 (emitida 2026-07-17, sessão #3):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R25-A** | **BATERIA 1 da descoberta** — passos B1.1–B1.6 do produto §6 (parte A AUTÔNOMA: B1.1 SQL issues×conversation_id [perna estática de N1] · B1.2 SQL agents [N6+P4] · B1.4 busca de skill em prosa [fronteira 6] · B1.5 activity_log [N8]; parte B com **[DONO]**: B1.3 lote no gate · B1.6 ultracode+D6/D7); passo 0 em-repouso obrigatório; veredito REGISTRADO no fragmento do materiais/ com citação [B1.x] + HANDOFF-r25a-bateria1.md na raiz; jar vivo pré-fix SERVE (o #34 não toca o que a bateria exercita) | bateria (SQL read-only + UI/API de leitura; escrita de estado SÓ pelo dono na UI; ZERO commit; achado se registra, NÃO se conserta) | — | **EMITIDA (kickoff verificado adversarialmente, entregue ao dono; ocupa slot 2-em-voo ao ser colado)** |

- **Verificação adversarial da tríade (workflow, 3 verificadores, 84 checagens): o padrão
  segue vivo — achou erro real.** (1) **§6 do plano: os SQLs de B1.3/B1.5 usavam colunas
  INEXISTENTES** (`type`/`actor`/`approval_id`; e `REVIEW_REQUESTED` não existe no dado — o
  pedido de review é `ISSUE_SUBMITTED_FOR_REVIEW`) → **corrigidos NA FONTE** com nota do
  controle; colunas reais: `action`/`actor_type`/`actor_id`/`entity_type`/`entity_id`/`detail`.
  (2) Rodapé de contagens do fragmento `adr-0012.md` ficara defasado pelo rebaixamento do A4 →
  corrigido (✅7 · pós-reboot 10) + marca SUPERADO no Achado 4. (3) Roteiro ganhou o `cp` do
  caminho ALTERNATIVO fef6073 + nuance `JAVA_HOME` (o mvn do Homebrew roda no JDK 26). Avisos
  incorporados ao kickoff: no banco a company é **"Prova V1"** (não `prova-v1`) — filtrar por
  ilike/id; passo 0: ausência de linha no group by = zero. Confirmações úteis: os 2 endpoints
  do KnowAll existem (`KnowAllController.java:35` GET /search · `:48` POST /match, read-only);
  `effort=ultracode` real (`ClaudeArgv.java:14-22` → `--settings {"ultracode":true}`); o jar
  vivo é comprovadamente pré-#34 (unzip: tem AdapterRunExecutor, ZERO IssuePromptComposer).

## Fechamento da leva 2026-07-17 (tarde) — REBOOT + R25-A (Bateria 1) + GRILL do desenho Spring AI (sessão #3)

Três voltas numa leva, validadas adversarialmente (workflow 3 verificadores; evidência
SQL/file:line; recontagem INTEGRAL dos fragmentos — a lição alegação≠medição pagou de novo, 2×).

**REBOOT DO :8080 ✔ (operador, 13:47) — ⚠️ MAS O PROCESSO ESTÁ MORTO AGORA:**
- Executado pelo dono direto na **`fef6073`** (decisão DELE: um-reboot-só, bundlando a Frente E —
  supersede a recomendação `4c1b35f` do controle; o Bloco C do grill está DECIDIDO por ato).
  Backup `backup-pre-v30-20260717.sql` (3,6 MB, 13:41) ✓ · jar trocado (87,5 MB, 13:47; o antigo
  62 MB era pré-#34) ✓ · **V30+V31 aplicadas 13:47:58 success=t (MEDIDO)** ✓ · containers
  `orq-sai-pg` e `agente-orquestrator-backend-1` removidos ✓ (sobram 2 volumes órfãos
  `orq_62a61fae_*` — decisão do WorkspaceReaper, não mexer). Correção de memória colhida pelo
  dono: o bloqueio de `kill` é do harness da sessão de controle, NÃO universal (memória
  `ambiente-restart-8080-compartilhado` corrigida por ele).
- **⚠️ ESTADO NO FECHAMENTO (medido): NENHUM java vivo; :8080 recusa conexão; :3000 de pé.**
  Timeline: boot 13:47 → app trabalhou (lote do B1.3 às 14:10 · issue-sonda 14:16 · último turno
  SUCCEEDED 14:30:31 local) → **shutdown GRACIOSO às 16:49:12** (log
  `/private/tmp/orquestrador-8080.log` — achado depois via memória do reboot; GracefulShutdown
  limpo via shutdown hook, ZERO erro; processo era FILHO do terminal/sessão do operador que
  fechou). App saudável 13:48→16:49, zero perda de dado.
  **Bateria 1b RE-BLOQUEADA até o dono subir de novo** (1 comando; jar e migrações JÁ certos;
  recomendação: `nohup … & disown` p/ sobreviver ao fechamento do terminal).

**R25-A (BATERIA 1) ✔ FECHADA PARCIAL — B1.6 herdada pela R26-A:**
- Substância CONFIRMADA por evidência de banco independente: B1.1 ✔ (hoje 24 issues, 2 NOT NULL
  ambas Prova V1 — a 2ª é a issue-sonda da própria faixa; `ISSUE_ABSORVIDA` única p/ a #1) ·
  B1.2 ✔ (hoje 58 agentes, 6 DOCKER todos SEM rastro de gate = produtor DESVIADO; team_id NULL
  53/58) · B1.3 ✔ (27 históricas ↔ 27 `APPROVAL_APPROVED` ↔ 7 Decisions por área; AO VIVO 2
  approvals + 1 Decision seq 5 Prova V1 — SEM sub-contagem) · B1.4 ✔ (par A/B: paráfrase natural
  → "gap", jargão literal → match — **fronteira 6 CONFIRMADA quebrada**) · B1.5 ✔ (32/32
  camelCase — **🔴 N8 confirmado** contra ADR-0009:28-29).
- **Correções do controle (a recontagem decide):** eram **6** transições (não 5, não 7):
  0037-A9→✅ · 0009-A10→🔴 · 0009-A11→✅ · 0009-A14→✅ · 0012-A12a→✅ · 0012-A12b→✅.
  **INVENTÁRIO VIGENTE: 1085 = 306✅ · 18🔴 · 396🎯 · 142🎯pós-reboot · 215🚫 · 8❓** (a conta do
  handoff, 305/397, também somava 1085 — erro AUTO-COMPENSADO, só recontagem pega). Tabela §3,
  rodapés dos 3 fragmentos e lixo de tool-call no fim do `adr-0037.md` corrigidos. Imprecisões
  menores anotadas: cabeçalho do handoff cita PID/jar PRÉ-reboot (stale — a parte B rodou já na
  fef6073); "5 exceções TODAS arquitetos" (2 são executores: SmokeBot, Smoke Exec T4);
  enumeração DOCKER omite `Smoke F8 Claude`.
- **Reconciliação com o §6:** os 6 DOCKER legados NÃO reabrem as 6 pernas 🚫 da 0023 — a
  instrução do plano era PRÉ-decisão (a); os 6 são produtor desviado, exatamente o que a (a)
  recusa. Galho DOCKER segue roadmap #15.
- **Achados novos da faixa (registrados, sem ação):** (i) UI `/approvals`/`/agents` não pinta na
  1ª carga (API/DOM ok) — candidata roadmap · (ii) botão de lote disabled com filtro "todos" sem
  explicação (UX) · (iii) **CEO Questionador não reconheceu recomendação da MESMA thread** —
  candidata a **fronteira NOVA** (continuidade de contexto entre personas; 0038×0030×0031) ·
  (iv) comportamento CORRETO validado: CEO recusou 2× repassar instrução embutida sem revisão.
  **Roadmap candidatas novas: #19 = busca de skill por frase-inteira (fronteira 6, CONFIRMADA) ·
  #20 = fronteira 8 `--settings` ultracode (pendente B1.6).**

**GRILL SPRING AI ✔ (desenho dos 2 usos) — validado; 4 achados de FATIAMENTO:**
- `docs/desenho-spring-ai-verificador-auxiliar.md` APROVADO pelo dono (princípio-mãe: local =
  verificador AUXILIAR de peso menor, SOMA, nunca substitui; Invioláveis §3; prova §5
  anti-"disse que fez"). Âncoras 100% conferidas (V14 `role_template_version`+skillset ·
  PgTrgmSkillSearch/SkillRepository:41 · MemoryQuery · TurnOutputSchema F-N3 ·
  CompanyMemoryMaterializer R19-A · ProposalScanner/SkillRequestScanner). **Ollama/qwen AUSENTES
  em qualquer forma** (sem binário, container, imagem ou :11434) — dependência confirmada.
- **Achados da validação (obrigatórios no kickoff da fatia B):** (1) **premissa escondida: o
  plano do arquiteto NÃO nasce F-N3 hoje** — `AdapterRunExecutor:211` só passa outputSchema em
  turno de PERSONA; run de issue sai CRU; a fatia estende o schema ao run de issue do arquiteto
  (plumbing genérico existe; claude `--json-schema` e codex `--output-schema` suportam);
  (2) **tensão de LETRA com a ADR-0043** ("atrás do contrato `ContextIntelligence`") — seam
  próprio exige a emenda/ADR que o §4 do desenho promete; SEM ela a fatia contraria o verbatim;
  (3) gates de promoção: "decisão humana + evidência" < gates do norte p/ influência real — a
  fatia nasce e MORRE em sombra; promoção sombra→somar exigirá os gates completos (registrado);
  (4) exemplo factual do desenho: `diagnosing-bugs` é skill do EXECUTOR, não do arquiteto.
- Decisões do dono REGISTRADAS: implementar OS DOIS usos · Uso A SEM pré-passo de recall ("não
  me importa se vale a pena, coloca") · prioridade = **Uso B** · Uso A supera a F-SAI4 · "pronto"
  = comportamento observado no motor real + evidência citada (memória
  `nao-declarar-implementado-sem-prova`).

**PAUTA RESIDUAL (ainda SEM palavra do dono):** A2 hardening `tool-calling.enabled` (nuance:
opt-out explícito já existe) · A3 pré-aprovação 1b cross-provider (moot enquanto local-only;
formalmente aberto) · A1 ordem das demais candidatas da pesquisa (1a multi-modelo: sem decisão)
· D1 poda em massa das ~20 remotas mergeadas · D3 roadmap p/ "suíte H2 não roda Flyway" ·
D4 skill verify defasada (CONFIRMADO: `.claude/skills/verify/SKILL.md:21` ainda `mvn -o
package`). Bloco B (a/b/c) mantido sem veto; Bloco C decidido POR ATO (fef6073).

**Rodada 26 (emitida 2026-07-17, sessão #3):**

| ID | Faixa | Tipo | Reservas | Status |
|----|-------|------|----------|--------|
| **R26-A** | **Bateria 1b + fecho do B1.6** (sentada COM o dono) — GATE 0: :8080 VIVO na fef6073 (o dono sobe antes; 1 comando) · B1.6 primeiro (estado pronto: agente ultracode 33aaf32b + issue #2 triada — falta a mensagem DITADA pelo dono na aba #2) · depois B1b.1–B1b.6 do produto §6 (B1b.1 = a pergunta central da fase) · armadilhas do §6 respeitadas · vereditos [B1b.x] nos fragmentos + handoff | bateria (produtor real; escrita SÓ pelo dono na UI + exceção precedentada POST /wakeups padrão G5; ZERO commit) | — | **EMITIDA (kickoff verificado adversarialmente)** |
| **R26-B** | **Fatia Uso B — validador de planos** (desenho §4.b + achados da validação): (a) extensão F-N3 ao run de issue do ARQUITETO (pré-requisito descoberto) · (b) checagens no `role_template_version` + entrada no briefing (`IssuePromptComposer`) · (c) validador local (Ollama/qwen; sobe o modelo primeiro) só p/ papel arquiteto, SOMBRA · (d) push do checklist no re-run (padrão R19-A) · (e) emenda ADR-0043 (+0017 se couber) — a letra do verbatim exige · TDD + `/verify` motor real (§5: caso violado E caso limpo, evidência citada) | código (**git worktree próprio**; branch de `fef6073`; PR; **MERGE SÓ COM OK DO DONO**; Invioláveis §3 na íntegra) | **V32 RESERVADA** (checagens no molde) · emenda > ADR nova; **ADR-0044 livre** se precisar · ACTIVE/promoção FORA | **EMITIDA (kickoff verificado adversarialmente)** |

2-em-voo: **0/2 no momento** (cada uma ocupa slot ao ser colada). FILA: R26-A/R26-B → bateria
de revisão 0021/0031/0035 → Fatia Uso A → pauta residual (próximo grill).

**JANELA DE GRILL EMITIDA (2026-07-17, pedido do dono): MCP × Spring AI.** O dono quer validar
"como incorporar o MCP que o Spring AI nos permite utilizar". Kickoff preparado via /nova-sessao
e VERIFICADO adversarialmente (workflow, ~15 grupos de claims — 1 conflação cosmética corrigida:
a linha PROIBIDA da pesquisa §2:247 justifica só pela Constituição #10; "executar tools" é trava
SEPARADA do verbatim ADR-0043:233 — + nuances incorporadas: cap 3 é só do `fetch_body` :37 e do
`memory_fetch` :47, enforcement de 1ª classe no backend; gate completo com as pontas :273-274;
variantes de starter server×client; DOIS "#15" no ecossistema — candidata v1.1 #15 = rename
`KnowAllMcpBridge`→`McpBridge`, relevante ao D1). Estrutura do grill: pergunta de abertura ("o
que o MCP deve resolver?") ANTES das direções · D1 server Spring AI × bridge próprio provado ·
D2 transporte HTTP/SSE p/ MCP dentro do DOCKER (o gap real — ADR-0043:142-144) · D3 client no
modelo local (recomendação: manter PROIBIDO) · D4 conectores externos nos engines (hoje isolado
por `--strict-mcp-config`, decisão de laudo). Distinção-coração: "Spring AI/local executa tools"
(PROIBIDO, não em revisão) × "infraestrutura MCP para os ENGINES" (JÁ EXISTE — F-AT2/F-C3).
Janela de grill NÃO ocupa slot 2-em-voo (precedente: grill do desenho). Produto esperado:
`HANDOFF-grill-mcp-spring-ai.md` (+ `docs/desenho-mcp-spring-ai.md` se fechar rumo); fatias SÓ
via controle depois. Reservas: V33+/ADR-0044+ livres para o que o grill decidir (V32 é da R26-B).
