# Handoff — GRILLING: o LOOP DE HANDOFF DE CONTEXTO DO CEO (fatia FECHADA, pronta para execução)

> **Data:** 2026-07-07 · **Tipo:** plano fechado de grill (não é código). Fruto de uma sessão de
> `/grilling` + `/codebase-design` + `/human-architect-mindset`, com **validação adversarial do
> código real** (Workflow de 18 agentes Explore que tentaram REFUTAR cada claim + síntese).
> **Constrói:** a **"fatia futura"** que a **ADR-0015** mediu mas não construiu (detectar 50-60% →
> destilar → turno novo retoma). A MEDIÇÃO (tokens/turno vs janela do `engine_catalog` V13, via
> `CostEvent`) já existe no v1; este épico constrói o **LOOP**.
> **Rótulo:** o loop de handoff (após F.13 thumbnail, F.14 cap+graduação rows, F.15 retenção rastro).
> **Executar numa sessão NOVA** com `/codebase-design` `/tdd` `/verify` (padrão `/nova-sessao`).
> **É a FATIA 1 de 2 do v1:** esta é a **FUNDAÇÃO**. A **FATIA 2** (separar o CEO em 3 personas —
> Questionador/Planejador/Executor) é o **1º cliente** deste loop e tem **grill próprio** (proposta
> capturada; ver §G).

---

## §A — O que é a fatia (uma frase)

Quando um turno do CEO cruza ~50-60% da janela de contexto (medido **PÓS-run** pelo `CostEvent`
contra a `context_window` do `engine_catalog`), o backend **GARANTE** um bilhete destilado
(`.orq/handoff/<conversationId>.md`, escrito pelo próprio agente via skill de destilação; se faltar,
forçado por **reabertura cirúrgica** da sessão) e enfileira um **turno-continuação FRESCO** (um
`conversation_turn` de sistema, com **mensagem-sentinela**) que retoma lendo o bilhete —
**imperceptível na conversa** do board, **rastreável na trilha técnica**, com `--resume` desligado
(exceto a reabertura cirúrgica terminal) e **trava anti-loop**.

---

## §B — DECISÕES-ÁTOMO (arrancadas no grill; dono **H** = Matheus)

| ID | Decisão | Racional (arrancado + validado no código) | Alternativa rejeitada |
|----|---------|--------------------------------------------|-----------------------|
| **D1** | **Gatilho PÓS-SETTLE** (Arquitetura A): o turno roda até o fim; o app lê o `CostEvent`, calcula o % vs a janela; se cruzou → GARANTE o bilhete → enfileira continuação. O "50-60%" = **auto-limite comportamental** do agente + **% pós-settle = gatilho/rede**. | O `CostEvent` só existe **pós-run** (`ClaudeStreamParser` acumula no estado local, emite no `Result`; `recordCosts` roda depois — `AdapterRunExecutor.java:208`, ANTES do settle `:213-221`). `--resume` OFF ⟹ a janela **só enche por-invocação** (não acumula entre turnos); projeção capada (`LiveStateProjector` ISSUES_CAP=30/ACTIVITIES_CAP=10) ⟹ nem conversa longa enche. Honra "destilação > recarga bruta". | **(B) Interrupção mid-run**: exige streaming ao vivo do `usage` + canal de "encerre agora" pro `claude -p` (inexistente no fire-and-parse); único jeito de parar = **matar** o processo (perde trabalho). **ADIADO.** |
| **D1′** | **Gatilho OBJETIVO, sem canal do agente:** `(% cruzou o teto)` → o backend **garante o arquivo** `.orq/handoff/<conv>.md` (usa o que há, ou força) → continua. | **REFUTED** que exista qualquer canal "não-terminei" (nem `StepSink`, nem proposta, nem reply-parse). Inventar um viraria **Approval-fantasma** (`proposalScanner.scan`, `ConversationTurnCoordinator.java:71`). Dois fatos que o backend checa sozinho (`ExecutionTarget.readWorkspaceFile` já existe). | Depender do agente **sinalizar** por um canal (proposta/reply). |
| **D2** | **Self-distill via SKILL de destilação** (template fixo), registrada como **`CompanySkillVersion` de SISTEMA** no set do CEO e materializada por-run. | ADR-0015 já diz "mandar o agente escrever o handoff"; o conteúdo (plano/intenção) só existe na cabeça de quem viveu o turno. `SkillMaterializer` só aceita **entidade DB** (skill transiente seria apagada na re-materialização). Template fixo (`## Onde estou · O que fiz · Próximo passo · Decisões travadas · IDs/arquivos`) + refs CONCRETAS (não prosa) = acionável. | **Faxineiro-de-fora** como caminho principal (reconstrói sem viver o turno) → vira só última-rede (ver D8). **Skill transiente** ad-hoc. |
| **D3** | **Bilhete no Workspace `_agent`, namespaced `.orq/handoff/<conversationId>.md`,** lido pelo **próprio turno-continuação**; `agent_session` intocado; **nenhuma tabela nova**; **ZERO migração** (via SENTINEL). | Workspace `_agent` (issueId=null) é ESTÁVEL e sobrevive entre Runs (`WorkspaceResolver.java:51`); o `WorkspaceReaper` **não** o apaga (só órfãos de Issue, 3-seg); `.orq/*` **não** entra em `materializedSubtrees` (`AdapterRunExecutor.java:169-171`) ⟹ bilhete sobrevive à re-materialização. `owner_message` é **NOT NULL** (V16 + `ConversationTurn.java:37,59`) ⟹ o turno-continuação nasce com **mensagem-sentinela** (`[continuação — leia .orq/handoff/<conv>.md]`), que `composePrompt:97` já injeta no prompt (instrui a ler) e o board mostra legível. | **`owner_message` nullable + coluna `is_continuation`** (migração desnecessária no v1). **Conteúdo do bilhete no DB** (não target-agnostic: sob Docker o backend não vê o volume). |
| **D4** | **Retomada = carta (`CLAUDE.md`) + estado vivo (`LiveStateProjector`) + BILHETE-no-prompt.** `--resume` **OFF pra continuar**. | Religar `--resume` recarrega a conversa inteira ⟹ janela CRESCE — exatamente o que o handoff combate (auto-sabotagem). "Destilação > recarga bruta" (ADR-0002/0015/0018). | Religar `--resume` **para continuar o trabalho**. |
| **D4′** | **EXCEÇÃO cirúrgica ao D4:** `--resume` **LIGADO** SÓ pra **destilar-e-encerrar** (a rede da camada 3, ver D8). | Reabertura **única, terminal**, prompt mínimo, sessão **descartada** em seguida. A janela vai a ~62% e MORRE — **zero crescimento contínuo** ⟹ não fere o espírito da regra de ouro. Aproveita o handle `external_session_id` **já capturado** (write-only). | `--resume` para continuar (proibido, D4). |
| **D5** | **Board: INVISÍVEL na conversa / VISÍVEL na trilha.** O dono vê 1 pergunta → 1 resposta coerente; a espera é coberta pelo indicador "processando" existente. Runs/steps/run_events guardam tudo. Distinção do turno-continuação = **sentinel-prefix** (v1, zero migração). | ADR-0015 pede "imperceptível para o board". O sentinel é NOSSO (não input do usuário) ⟹ detectar pelo prefixo é seguro no v1. | **Transparência com selo** ("CEO seguiu em janela nova") — contraria "imperceptível". **Coluna `is_continuation`** (adiada; só se a UI pedir robustez). |
| **D6** | **Escopo do v1 em 2 FATIAS:** FATIA 1 = este loop (fundação, agora); FATIA 2 = os 3 CEOs (1º cliente, grill próprio). O **seam do skill-set da continuação** vira **v1-EXPLÍCITO** (parâmetro pronto; a lógica de QUANDO trocar de persona é da Fatia 2). | Os 3 CEOs são **v1 do produto** (Matheus), mas empilhar handoff + 3 personas + 3 skills novas num empurrão **infla** e trai a própria regra de ouro que estamos codificando. Fatiar honra a disciplina. | **Monólito único** (tudo junto). |
| **D7** | **Trava anti-loop (BLOQUEANTE):** máx. **N=5** continuações seguidas por conversa. Ao bater, o CEO **NÃO some** — emite um **turno normal e visível**: "parei aqui, precisa de um empurrão". + **idempotência** do disparo. | Sem cap, A→B→C… infinito (o `SpawnLineageGuard.MAX_DEPTH=5` rege **spawn**, NÃO turnos de conversa `issueId=null`). N=5 espelha esse teto. Notificar (não CANCELLED silencioso). | Sem cap (loop infinito). |
| **D8** | **Garantia do bilhete em 3 camadas ("nunca esquecer"):** (1) self-distill feliz; (2) backend verifica o arquivo pós-run; (3) **REDE = resume-cirúrgico da MESMA sessão** só pra escrever o handoff. | Depender da boa vontade do agente é frágil. A rede usa o **contexto VIVO** (a ~60% a sumarização é confiável, mesmo que criar plano detalhado não seja) ⟹ handoff de qualidade, **não** reconstrução de fora. | **Faxineiro-de-fora** como rede principal (menos fiel) → vira última-rede rara. |
| **D9** | **Fail-OPEN:** engine sem `context_window` catalogado (nullable) ⟹ **NÃO dispara** o handoff + loga **WARN**. O cálculo do gatilho é **total-safe** e **NUNCA** aborta o settle. | `contextWindow` é nullable (`EngineCatalog.java:33`); `model`/`effort` do Agent também. Fail-closed quebraria turnos legítimos. `recordCosts` e `settle*` não estão sob try/catch conjunto (riscos #5, #7). | Fail-closed / divisão por zero. |

---

## §C — MECANISMOS a construir (dono **A** = agente; veto humano no review). Cada um com o **ponto de extensão** (file:line)

- **M1 — Resolver engine→janela em runtime.** Não existe (`EngineCatalogRepository.java:8-12` só tem
  `findByEnabledTrueOrderByLabelAsc`). Adicionar `Optional<EngineCatalog> findByAdapterTypeAndModelAndEffort(...)`;
  o `adapterType/model/effort` já estão em mãos no executor (`agent.get…()`, usados em `AdapterRunExecutor.java:186`).
- **M2 — Cálculo do % + decisão do gatilho.** Computar dentro de `AdapterRunExecutor.recordCosts` (`:208`,
  já itera `result.costEvents()` em `:231`) somando `emission.tokensInput()` contra `contextWindow`;
  **NÃO** reler o ledger (`CostEventRepository.findByRunId` fica de reserva). Guardar `>= threshold`.
- **M3 — Dial de threshold.** `@Value("${orquestrador.handoff.context-threshold-percent:55}")` num bean,
  declarado em `application.yml` + `application-test.yml` (espelha `RowRetentionReaper.java:78-80`).
- **M4 — Skill de destilação (D2) + o FORMATO do bilhete (o contrato).** Registrar como `CompanySkillVersion`
  de sistema (seed/DB) e incluir no set do CEO via `resolveForRun` (`AdapterRunExecutor.java:116`). Definir o
  **template** do bilhete (era UNKNOWN na validação — é o contrato que faz a rede funcionar).
- **M5 — Instrução ESTÁTICA de handoff no prompt.** Append fixo na seção "## Instruções do turno" de
  `composePrompt` (`ConversationTurnCoordinator.java:98-101`) — a instrução não depende do custo (que só existe
  pós-run), então **não** quebra a assinatura de `claimFor`.
- **M6 — Guard-rail do bilhete (risco #3, dependência OCULTA).** `.orq/handoff/` **JAMAIS** em
  `materializedSubtrees` (`AdapterRunExecutor.java:169-171`), senão a semântica REPLACE do `writeWorkspaceFiles`
  apaga bilhetes. **Teste anti-regressão** que FALHA se alguém adicionar.
- **M7 — `postContinuationTurn(...)` com sentinel.** Espelha `postMessage` (`ConversationService.java:65-71`):
  cria `conversation_turn` PENDING com `ownerMessage` sentinela → `wakeups.enqueue("conversation-turn:…")`.
  `claimOldestPending` reivindica normalmente (o WHERE **não** exige `owner_message` — `ConversationTurnRepository.java:23-30`).
- **M8 — Fiação do gatilho → continuação (ordem obrigatória).** `verifica-arquivo → (se faltou) resume-cirúrgico
  gera → cria-continuação → drainQueue/wakeup`. Nunca acordar antes do bilhete existir. O 1-Run-ativo é garantido
  por `WakeupService.java:59` (`findByIdForUpdate`, lock pessimista).
- **M9 — Rede resume-cirúrgico (D8/D4′).** Ler `agent_session.external_session_id` (hoje write-only) e passar
  como `priorSession` num run terminal com prompt mínimo; **descartar** a sessão depois. É o **único** ponto que
  liga o `--resume` — escopado a destilar-e-encerrar.
- **M10 — Trava anti-loop + idempotência (D7).** Contar continuações consecutivas da conversa (marca/`parent_turn_id`);
  parar em N=5 com turno-notificação; guard antes de criar (evita continuação dobrada se o settle reexecutar, risco #10).

---

## §D — DoD (VENDO, nível teams-demo — para a sessão de execução)

Backend `:8080` é infra COMPARTILHADA. DB container **`agente-orquestrador-db-1`**. A prova precisa de um CEO com
motor que RODE (teams-demo tem, provado nos VENDO da F.13/F.14).

1. **Seed:** numa company de teste, uma conversa com o CEO. **Baixar o dial** `orquestrador.handoff.context-threshold-percent`
   para um valor minúsculo (ex.: `1`) — o análogo do `retention-days=0` da F.14 — pra QUALQUER turno "cruzar".
2. **Caminho feliz (D2/D8-1):** um turno cruza o teto E o agente **deixou** `.orq/handoff/<conv>.md` ⟹
   **um (e só um)** turno-continuação é enfileirado, **DEPOIS** do bilhete existir; a continuação **retoma lendo o bilhete**.
3. **Rede (D8-3 / D4′):** um turno cruza o teto e **NÃO** deixou bilhete ⟹ o backend **reabre a mesma sessão**
   (`--resume`), o bilhete **aparece** com qualidade, e SÓ então a continuação nasce. Provar que a sessão foi **descartada**
   (a continuação é fresca, `priorSession=null` nela).
4. **Reset da janela:** a continuação roda **abaixo** do threshold real (prova que a janela "resetou" — não herdou os 60%).
5. **`--resume` OFF na continuação (D4):** `agent_session` da continuação sem `priorSession`; o handle NÃO foi religado
   pra continuar (só o run terminal da rede usou `--resume`).
6. **Board imperceptível (D5):** na thread da conversa o dono vê **pergunta → resposta coerente** (sem o sentinel cru como
   "mensagem dele"); na trilha (runs/steps) as continuações **aparecem**.
7. **Nada dispara indevidamente:** turno **abaixo** do threshold ⟹ **nenhuma** continuação.
8. **Trava anti-loop (D7):** forçar N+1 continuações ⟹ a (N+1) **não** nasce; um turno **ANSWERED** avisa o dono do corte.
9. **Fail-open (D9):** engine sem `context_window` ⟹ **nenhuma** continuação + **WARN** logado; o turno faz settle normal.
10. **Guard-rail (M6):** o teste anti-regressão de `materializedSubtrees` está verde.
11. **Gates backend:** `mvn -o test` verde (+ testes novos por M1–M10). Repo **é git** — nada commitado sem o Matheus pedir.

---

## §E — Evidência (código real, file:line — base `backend/src/main/java/com/orquestrador/`)

- **Tokens pós-run (D1):** `execution/AdapterRunExecutor.java:205` (comentário já diz *"instrumentam o handoff a
  50-60% (ADR-0015)"*), `:208` (`recordCosts`, antes do settle `:213-221`), `:231` (itera `costEvents`);
  `execution/adapter/ClaudeStreamParser.java:105-122` (acumula `usage`); `governance/budget/BudgetService.java:56`
  (`recordCost`); `governance/budget/CostEventRepository.java:42` (`findByRunId`, sem chamador de produção — write-only).
- **Janela (D1/D9):** `org/engine/EngineCatalog.java:33-34` (`contextWindow` nullable + getter); seed em
  `resources/db/migration/V13__engine_catalog.sql`; `org/engine/EngineCatalogRepository.java:8-12` (SÓ
  `findByEnabledTrueOrderByLabelAsc` — falta o lookup por engine).
- **Sinal objetivo (D1′):** não há canal — `conversation/ConversationTurnCoordinator.java:71` (`proposalScanner.scan`
  criaria Approval-fantasma se usássemos proposta). Leitura de arquivo: `ExecutionTarget.readWorkspaceFile` (usado pelo preview).
- **Skill (D2):** `skills/SkillMaterializer.java:34-64` (só aceita `Collection<CompanySkillVersion>`); `resources/db/migration/V6__skills.sql`;
  set do run em `execution/AdapterRunExecutor.java:116` (`skillSets.resolveForRun`).
- **Prompt/instruções (M5):** `conversation/ConversationTurnCoordinator.java:94-102` (`composePrompt`, seção "## Instruções do turno"; injeta `ownerMessage` em `:97`).
- **Escrita livre no workspace (D2):** `execution/adapter/ClaudeArgv.java:38-39` (`--permission-mode bypassPermissions` incondicional); smoke `docs/handoff-f9-preview.md:80-83`.
- **Workspace estável + Reaper (D3):** `execution/runtime/WorkspaceResolver.java:37-52` (`_agent` p/ issueId=null), `:82` (`isIssueVolumeName`, 3-seg);
  `execution/runtime/WorkspaceReaper.java` (só órfãos de Issue). `.orq/` fora de `materializedSubtrees`: `execution/AdapterRunExecutor.java:169-171`.
  Namespace `.orq/` já em uso: `…/PreviewArtifactCapturer.java:42` (`.orq/preview/index.html`).
- **owner_message NOT NULL (D3):** `resources/db/migration/V16__conversation.sql:23`; `conversation/ConversationTurn.java:37` (campo), `:59` (construtor exige `message`).
- **Enfileirar turno (M7):** `conversation/ConversationService.java:65-71` (`postMessage`: cria PENDING → `wakeups.enqueue`);
  `conversation/ConversationTurnRepository.java:23-30` (`claimOldestPending`: WHERE sem `owner_message`).
- **1-Run-ativo / concorrência (REFUTA o medo de colisão no `_agent`):** `execution/WakeupService.java:59` (`findByIdForUpdate`, lock pessimista), `:82-109` (coalescing).
- **Settle + drenagem (M8):** `conversation/ConversationTurnCoordinator.java:63-92` (`settleAnswered`/`settleFailed`→`drainQueue`, `:87-91`).
- **`--resume` desligado hoje (D4/M9):** `execution/AdapterRunExecutor.java:212` (priorSession null); `execution/AgentSessionStore.java` (write-only); handle capturado em `agent_session.external_session_id`.
- **Cap reusável parcial (D7):** `…/SpawnLineageGuard.java` (`MAX_DEPTH=5`, mas rege spawn, não turnos).
- **Dial @Value (M3):** `execution/runtime/RowRetentionReaper.java:78-80`; `IssueService` (`orquestrador.review.risk-threshold:5`).

> Relatório completo da validação (18 investigadores): `scratchpad/validacao-relatorio.md` (nesta sessão).

---

## §F — Gotchas / ambiente

- **Repo AGORA é git** (privado `matheus3006/agente-orquestrator`, `main`). **Commit/push só quando o Matheus pedir;
  se for mexer, criar branch ANTES de tocar a `main`.** (A regra antiga "repo sem git" dos handoffs F.13–F.15 CADUCOU.)
- **Backend `:8080` compartilhado** — não reiniciar à toa; rebuild = `cd backend && mvn -o -DskipTests package`, conferir
  pid (`lsof -iTCP:8080`). **O HARNESS BARRA `kill` do `:8080`** (pid que o agente não criou) — o **operador reinicia**;
  o agente faz só verificação read-only + cleanup (memória `ambiente-restart-8080-compartilhado`).
- **DB:** `docker exec -i agente-orquestrador-db-1 psql -U orquestrador -d orquestrador` (user/db com 'd'; `-i` p/ heredoc).
- **macOS Darwin** (`gtimeout`, não `timeout`). **`DatabaseClock`, nunca `Instant.now()` cru** (o bug de 3h da F.14).
- **Seed do VENDO:** header `X-Board-User: b0a7d000-0000-4000-8000-000000000001`, company `teams-demo`, runs reais 2-4 min
  (fatos em `docs/handoff-grilling-cap-graduacao-rows-fechado.md` §G). Semear conversa: `POST /api/companies/{id}/conversations`
  + `.../messages` (`ConversationController`).

---

## §G — Fora do escopo (explícito)

- **(B) Interrupção mid-run** (streaming ao vivo + kill) — ADIADO (D1).
- **`--resume` pra CONTINUAR** o trabalho — PROIBIDO (D4); só a reabertura cirúrgica terminal (D4′/M9) o liga.
- **Coluna `is_continuation`** — só se a UI pedir robustez (D5); v1 usa sentinel-prefix.
- **Persistir o corpo do bilhete no DB** — só se a durabilidade sob Docker doer (risco #4); v1 confia no `_agent` estável.
- **Levar o loop pros EXECUTORES** (que rodam Issues, não turnos de conversa) — outro caminho; v1 = só o CEO conversacional.
- **FATIA 2 — separar o CEO em 3 personas** (Questionador/Planejador/Executor): **épico próprio, grill próprio.**
  Proposta durável em `docs/proposta-3-ceos-personas.md` (T1 trai a ADR-0018: Planejador=Arquiteto+skills CEO; T2 camada de
  orquestração ACIMA dos funcionários; T3 gate mantido — Executor só PROPÕE). Inclui instalar `teach` + versões exatas
  (cs-ceo-advisor, superpowers/brainstorming, grill-me/grill-with-docs) e **escrever** `writing-doc-plans`, `being-ceo`,
  `delegation` (via `write-a-skill`). **Este loop deixa pronto o seam do skill-set da continuação (D6) pra essa fatia plugar.**
- **A LÓGICA de quando trocar de persona** na continuação — Fatia 2 (o seam fica aberto, não a lógica).

---

## §H — Ordem de execução sugerida (TDD)

1. **M1+M2+D9 — medir o % (fail-open).** Teste: dado run com N tokens e engine de janela W, `percent=N/W`; janela/model null ⇒ sem gatilho + WARN. `findByAdapterTypeAndModelAndEffort` + cálculo em `recordCosts`. *Fatia pura.*
2. **M3 — dial de threshold.** Teste: default 55, override por `application-test.yml`. `shouldTriggerHandoff(percent)`.
3. **M5 — instrução estática no prompt.** Teste: `composePrompt` contém a linha de handoff.
4. **M4 — skill de destilação + template do bilhete** no set do CEO. Teste: `resolveForRun` do CEO inclui `distillation`; materializada em `.claude/skills/distillation/SKILL.md`.
5. **M7 — `postContinuationTurn` com sentinel.** Teste: cria PENDING com sentinel, dispara wakeup; `claimOldestPending` reivindica (sem exigir owner_message). Sem migração.
6. **M6 — guard-rail anti-regressão** de `materializedSubtrees` (protege o risco #3).
7. **M8 — fiação gatilho→continuação (caminho feliz).** Teste integração: cruzou + bilhete existe ⇒ 1 continuação, DEPOIS do bilhete; abaixo ⇒ nenhuma. Ordem verifica→cria→drena.
8. **M9 — rede resume-cirúrgico (D8/D4′).** Teste: cruzou SEM bilhete ⇒ reabre a sessão (`--resume`), gera o bilhete, descarta a sessão, então continua fresca.
9. **M10 — trava anti-loop + idempotência (D7).** Teste: N+1 continuações ⇒ corte + turno-notificação; settle reexecutado ⇒ não dobra.
10. **Registro:** **ADR-0030** (preferência sinalizada — ver abaixo). **VENDO** (§D) em teams-demo; reverter seeds/config; backend intocado; nada commitado sem pedir. **§RESULTADO** neste handoff + memória de fechamento.

---

## Registro da decisão — preferência: **ADR-0030 nova** (não adendo à 0015)

Espelha como a F.15 fez a **0029 nova** (não adendo à 0028) porque **divergia** conscientemente. Aqui é mais forte: este
épico (a) **constrói** a fatia futura que a 0015 só previu, (b) toma **decisões novas substanciais** (gatilho objetivo,
sentinel zero-migração, trava anti-loop, board imperceptível), e (c) **abre uma exceção escopada à ADR-0002** (`--resume`
cirúrgico pra destilar-e-encerrar). Merece registro próprio. Adicionar **forward-refs** na 0015 (fatia futura → construída
na 0030) e na 0002 (a exceção do `--resume`).

---

## Mensagem `/nova-sessao` (copiar para iniciar a EXECUÇÃO numa sessão nova)

> Execute a **FATIA 1 do v1: o loop de handoff de contexto do CEO** (a "fatia futura" da ADR-0015).
> **Fonte da verdade:** `docs/handoff-grilling-loop-handoff-contexto-fechado.md` (seguir, não replanejar — o grill
> já fechou D1–D9 e os mecanismos M1–M10, validados contra o código por um fan-out de 18 agentes). Skills:
> `/codebase-design` (o turno-continuação e a rede como módulos profundos atrás de seams pequenos — inclusive o
> **seam do skill-set da continuação**, que fica pronto pra Fatia 2) → `/tdd` (teste-primeiro, pela interface) →
> `/verify` (VENDO teams-demo, §D). **Espinha:** medir % pós-settle (fail-open) → garantir o bilhete (self-distill;
> rede = resume-cirúrgico da mesma sessão) → turno-continuação FRESCO com sentinel → board invisível → trava anti-loop N=5.
> **Gotchas:** repo **é git** (branch antes de tocar `main`; commit/push só quando o Matheus pedir); backend `:8080`
> compartilhado (`mvn -o -DskipTests package`; o **operador** reinicia — o harness barra `kill`); DB
> `agente-orquestrador-db-1`; `DatabaseClock`, não `Instant.now()`. **Uma migração no máximo** (nenhuma no caminho do
> sentinel). Ao fim: **ADR-0030**, gates `mvn -o test` verdes, e **§RESULTADO** neste handoff.

## Skills a usar (execução): `/codebase-design` · `/tdd` · `/verify`.

## Pendências de veto humano (sinalizadas; NÃO decididas sozinho na execução)
- **Formato/template do bilhete** (M4) — é o contrato que faz a rede funcionar; era UNKNOWN na validação. Propor e validar com o Matheus.
- **N=5 da trava anti-loop** (D7) — número espelha `MAX_DEPTH`; veto livre.
- **Nome do dial** `orquestrador.handoff.context-threshold-percent` e o **default 55** — cosmético/ajustável.
- **ADR-0030 nova** vs adendo à 0015 — preferência sinalizada (nova); confirmar.

---

## §RESULTADO — execução (2026-07-07)

**Status:** código FECHADO e verde por integração; **VENDO live em teams-demo + merge = PENDENTE** (precisa
do operador reiniciar o `:8080` compartilhado com o build novo). Landado como **PR #1**
(`feat/loop-handoff-contexto-adr0030`), **não** merged.

**M1–M10 + D5 implementados** (pacote novo `backend/.../handoff/`, 6 classes + 7 testes):
- M1 `EngineCatalogRepository.findFirstByAdapterTypeAndModelAndEffort` · M2/M3/D9
  `HandoffThresholdDetector` (% pós-run, dial `:55` em `application.yml`, fail-open com WARN).
- M4 `DistillationSkill` (skill sintética de plataforma, corpo = contrato do bilhete) injetada em
  `AdapterRunExecutor` só para `role='ceo'`.
- M5 `ConversationTurnCoordinator.composePrompt` (instrução estática + caminho do bilhete).
- M6 guard-rail `.orq/*` fora de `materializedSubtrees` (`WorkspaceWriteOnRunTest`).
- M7/M10 `ContinuationTurnPoster` (sentinela + trava N=5 + idempotência).
- M8 `ContextHandoffLoop.afterCeoTurn` (detectar→garantir→continuar), chamado pós-`agentSessions.record`.
- M9 `AgentSessionStore.currentHandle` (1º leitor) + `ContextHandoffLoop.surgicalDistill` + `--resume`
  em `ClaudeArgv`/`CodexArgv`.
- D5 `ConversationService.thread` esconde continuações (visíveis na trilha).

**Vetos — defaults tomados (não divergi ⟹ não travei):** dial 55 · N=5 · template = esqueleto `/nova-sessao`
(em `DistillationSkill.BODY`) — sinalizados no PR/ADR para veto no review.

**Divergências de mecanismo conscientes (na ADR-0030):** (1) skill de destilação **sintética** — não há
tabela de skill-de-sistema no v1; (2) `--resume` do **codex** best-effort (claude-first; fail-open cobre);
(3) aviso de corte da trava aparece como turno com `owner_message` de sistema (refino de FE é follow-up).

**Gates:** `mvn -o test` = **423 verdes** (piso ~403; +20). `HandoffLoopTest` prova o loop pelo executor real.
**ADR-0030** escrita; forward-refs na 0015 e 0002; roadmap Frente B → Fatia 1 VENDO(integração).

**PARA A PRÓXIMA SESSÃO (fresca — regra de ouro aplicada aqui):**
1. **VENDO live (§D)** em teams-demo: baixar o dial `orquestrador.handoff.context-threshold-percent` para `1`,
   um turno do CEO cruza → bilhete `.orq/handoff/<conv>.md` → **1** continuação; rede (sem bilhete → resume);
   `--resume` OFF na continuação; board invisível; abaixo→nada; trava N=5; fail-open. **O operador** rebuilda
   (`cd backend && mvn -o -DskipTests package`) e reinicia o `:8080` — o agente faz verificação read-only.
2. Reverter o dial/seed do VENDO. 3. **Merge** do PR #1. 4. Memória de fechamento (ver
   `f-loop-handoff-contexto` / `roadmap-inventario-auditado`).
