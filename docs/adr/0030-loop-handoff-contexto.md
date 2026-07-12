# ADR-0030: o LOOP de handoff de contexto do CEO — a "fatia futura" da ADR-0015, construída

**Status:** Aceito (2026-07-07, execução da Fatia 1 do v1 do CEO — `docs/handoff-grilling-loop-handoff-contexto-fechado.md`)

## Contexto
A ADR-0015 fixou a regra de ouro (nenhum agente passa de ~50-60% da janela; ao cruzar, DESTILA um
handoff e uma sessão NOVA retoma lendo o bilhete) e construiu no v1 apenas a **medição**: o adapter
rico instrumenta os tokens (`ClaudeStreamParser`/`CodexStreamParser` acumulam o `usage`) e emite
`CostEvent`. A própria ADR-0015 (linhas 22-23) marcou como "fatia futura, não construída no v1" o
**loop completo**: detectar o limite → disparar a destilação → iniciar um turno novo lendo o bilhete →
retomar o plano. Antes desta ADR, `grep context-threshold|shouldTriggerHandoff|handoff.` no backend
era vazio — a fiação existia (tokens medidos), o loop não. Esta ADR constrói o loop, **só para o CEO
conversacional** (o 1º cliente da disciplina), como a **Fatia 1** do v1 do CEO.

A MEDIÇÃO já provava um fato barato: com `--resume` desligado (ADR-0002) a janela só enche
**por-invocação** (não acumula entre turnos), e a projeção do estado vivo é capada
(`LiveStateProjector` ISSUES_CAP=30/ACTIVITIES_CAP=10) — então um turno que estoura a janela é um
turno LONGO em si, medível PÓS-run pelo seu próprio `CostEvent`. É esse o gatilho.

## Decisão
Um LOOP pós-settle: quando um turno do CEO termina com sucesso, o backend calcula o % da janela
consumido (tokens de entrada do run ÷ `context_window` do `engine_catalog`); se cruzou o limiar,
**GARANTE** um bilhete destilado (`.orq/handoff/<conversationId>.md`) e enfileira **um** turno-
continuação FRESCO — um `conversation_turn` de sistema cuja mensagem-sentinela retoma lendo o
bilhete. Imperceptível na conversa do board, rastreável na trilha técnica, com `--resume` desligado
(exceto uma reabertura cirúrgica terminal) e trava anti-loop.

### D1 — Gatilho PÓS-SETTLE, não mid-run
O run roda até o fim; o app lê o `CostEvent`, calcula o % e, se cruzou, dispara. O "~50-60%" é
**auto-limite comportamental** do agente (a instrução no prompt, M5) + **% pós-settle = a rede que
não depende da boa vontade do agente**. **Rejeitada:** interrupção mid-run — exige streaming ao vivo
do `usage` + um canal "encerre agora" para o `claude -p` (inexistente no fire-and-parse); o único jeito
de parar seria MATAR o processo (perde trabalho). ADIADO (Frente D).

### D2 — Gatilho OBJETIVO, sem canal do agente
`(% cruzou o teto)` → o backend garante o arquivo → continua. Não se inventou um canal "não-terminei":
seria um Approval-fantasma. Dois fatos que o backend checa sozinho: o % (do `CostEvent`) e a
existência do arquivo (`ExecutionTarget.readWorkspaceFile`, já usado pelo preview).

### D3 — Self-distill via SKILL de destilação (template fixo)
O conteúdo do bilhete (plano/intenção) só existe na cabeça de quem viveu o turno — então o próprio
agente destila, guiado por uma skill `distillation` cujo CORPO é o CONTRATO do bilhete (o esqueleto do
`/nova-sessao`: Onde estou · O que fiz · Próximo passo · Decisões travadas · Ponteiros, com refs
CONCRETAS). **Divergência de mecanismo consciente:** o v1 **não tem** tabela de "skill de sistema" nem
semeadura (o `SkillMaterializer` só recebe uma `Collection<CompanySkillVersion>` e reescreve
`.claude/skills` a cada Run). Realização ZERO-migração: injetar uma `CompanySkillVersion` **sintética**
(transiente, nunca persistida) no set do CEO **a cada run** — re-aplicada sempre, portanto nunca
"apagada na re-materialização"; invisível à biblioteca da company (correto: é capacidade de
PLATAFORMA, não skill que o dono gerencia). **Rejeitada:** persistir a skill em DB (exigiria migração +
semeadura por-company + seleção por-CEO — desproporcional a um template fixo).

### D4 — Bilhete no Workspace `_agent`, namespaced `.orq/handoff/<conversationId>.md`, ZERO migração
O Workspace `_agent` (issueId=null) é estável entre Runs (`WorkspaceResolver`), o `WorkspaceReaper` não
o toca (só órfãos de Issue), e `.orq/*` fica FORA de `materializedSubtrees` — então o bilhete sobrevive
à re-materialização. O bilhete é **por-CONVERSA** (conversation-scoped, paralelo à Fatia 2). Espelha o
`.orq/preview/index.html`. **Rejeitada:** persistir o corpo do bilhete no DB — não é target-agnóstico
(sob Docker o backend não vê o volume) e pede schema.

### D5 — Retomada = carta + estado vivo + BILHETE-no-prompt; `--resume` OFF para continuar
Religar o `--resume` recarregaria a conversa inteira ⟹ a janela CRESCE — exatamente o que o handoff
combate (auto-sabotagem). A continuação nasce FRESCA e retoma pelo bilhete. "Destilação > recarga
bruta" (ADR-0002/0015/0018).

### D6 — EXCEÇÃO cirúrgica ao D5: `--resume` LIGADO só para destilar-e-encerrar
A rede (D8) reabre a MESMA sessão uma única vez, terminal, com prompt mínimo, e DESCARTA a sessão. A
janela vai a ~60% e MORRE — zero crescimento contínuo ⟹ não fere o espírito da regra de ouro. Aproveita
o handle `external_session_id` já capturado (write-only até aqui). É o **1º leitor de produção** do
`agent_session` (ADR-0002: "religar = ler daqui"). Abre uma exceção escopada à ADR-0002.

### D7 — Board INVISÍVEL na conversa / VISÍVEL na trilha
O dono vê 1 pergunta → 1 resposta coerente; a espera é coberta pelo indicador "processando". A
distinção do turno-continuação é a **sentinela** no `owner_message` (`[continuação — leia …]`) — NOSSA,
nunca input do dono, então detectar por prefixo é seguro no v1. A thread do board ESCONDE os turnos-
continuação; runs/steps guardam tudo. **Rejeitada:** coluna `is_continuation` (migração desnecessária;
só se a UI pedir robustez).

### D8 — Garantia do bilhete em 3 camadas ("nunca esquecer")
(1) self-distill feliz (o agente deixou o bilhete); (2) o backend verifica o arquivo pós-run; (3) REDE
= resume-cirúrgico da MESMA sessão só para escrever o handoff (D6). A rede usa o contexto VIVO (a ~60% a
sumarização é confiável) ⟹ handoff de qualidade, não reconstrução de fora. Se nem a rede produzir o
bilhete ⟹ sem continuação (fail-open — o trabalho segue na resposta normal do turno).

### D9 — Trava anti-loop BLOQUEANTE (N=5) + idempotência
Máx. **N=5** continuações encadeadas por conversa (espelha `SpawnLineageGuard.MAX_DEPTH=5`, que rege
spawn e NÃO alcança turnos de conversa `issueId=null`). Ao bater, o CEO **NÃO some**: emite um turno
ANSWERED visível ("parei aqui, precisa de um empurrão"). Idempotência: uma continuação PENDING já
cobre o disparo — o settle reexecutado não dobra (risco #10).

### D10 — Fail-OPEN
Engine sem `context_window` catalogado (nullable) ⟹ NÃO dispara + WARN. O cálculo é total-safe e NUNCA
lança — não pode abortar o settle de um turno legítimo. **Rejeitada:** fail-closed / divisão por zero.

## Mecânica (M1–M10, `backend/src/main/java/com/orquestrador/`)
- **M1** `org/engine/EngineCatalogRepository.findFirstByAdapterTypeAndModelAndEffort` — resolve
  engine→janela em runtime (antes só `findByEnabledTrueOrderByLabelAsc`).
- **M2+M3+M10-detector** `handoff/HandoffThresholdDetector` — soma `emission.tokensInput()` ÷
  `contextWindow`, aplica o dial `@Value("${orquestrador.handoff.context-threshold-percent:55}")`
  (declarado em `application.yml`), fail-open (D10). Chamado por `AdapterRunExecutor` pós-settle.
- **M4** `handoff/DistillationSkill` — a `CompanySkillVersion` sintética (o template = corpo);
  `AdapterRunExecutor` faz `distillationSkill.augment(resolveForRun(...), agent.getRole())` antes de
  materializar (só para `role='ceo'`).
- **M5** `conversation/ConversationTurnCoordinator.composePrompt` — instrução ESTÁTICA de handoff (com
  o caminho `.orq/handoff/<conv>.md`) na seção "## Instruções do turno" de TODO turno.
- **M6** guard-rail: `.orq/*` JAMAIS em `materializedSubtrees` (a semântica REPLACE apagaria o
  bilhete). Teste `WorkspaceWriteOnRunTest.nenhumSubtreeOrqEMaterializado_*` FALHA se violado.
- **M7+M9-poster+M10** `handoff/ContinuationTurnPoster` — cria o turno-continuação (sentinela) +
  wakeup; idempotência (PENDING já cobre) + trava N=5 com aviso ANSWERED visível.
- **M8** `handoff/ContextHandoffLoop.afterCeoTurn` — orquestra detectar → garantir bilhete → continuar;
  chamado por `AdapterRunExecutor` DEPOIS do `agentSessions.record` (o handle precisa existir p/ a rede).
- **M9** `handoff/ContextHandoffLoop.surgicalDistill` + `execution/AgentSessionStore.currentHandle` +
  `--resume` em `ClaudeArgv`/`CodexArgv` (só quando `priorSession` presente; todo run normal passa null).
- **Sentinela/bilhete** `handoff/ContinuationSentinel` + `handoff/HandoffNote` — o marcador e o contrato
  de lugar. Board invisível: `ConversationService.thread` filtra as continuações (D7).

## Consequências honestas (aceitas)
- **Skill de destilação sintética (não-DB):** invisível à biblioteca da company; muda o template =
  mudar `DistillationSkill.BODY` (não é versionável pelo fluxo de skills). Correto para uma capacidade
  de plataforma; se um dia precisar ser editável pelo dono, vira skill real (migração + semeadura).
- **Resume cirúrgico do codex é best-effort:** a sintaxe exata do `--resume` do `codex exec` não foi
  confirmada contra o CLI (claude-first). Falha do flag ⟹ a rede não grava o bilhete ⟹ sem continuação
  (fail-open); runs normais (priorSession=null) são intocados. Confirmar se um CEO codex precisar da rede.
- **Aviso de corte (D9) aparece como um turno com `owner_message` de sistema:** visível e honesto, mas a
  apresentação "CEO-iniciado" refinada é follow-up de frontend.
- **Continuação sem bilhete = sem continuação:** se as 3 camadas falharem, o loop não nasce (fail-open);
  o trabalho não se perde (segue na resposta do turno original). *(O "best-effort" do codex foi
  provado errado e corrigido — ver a nota de implementação abaixo.)*

## Nota de implementação (2026-07-11 — SÓ a gramática do resume do codex; achado R6 do laudo motor-nativo)

**Escopo desta nota: apenas o bullet "Resume cirúrgico do codex é best-effort" das Consequências.**
O best-effort foi verificado contra o source do CLI pinado e estava **errado**: o `CodexArgv`
emitia `--resume <id>` como flag, mas o codex 0.142.5 **não tem essa flag** — resume é
**SUBCOMANDO** com `SESSION_ID` e `PROMPT` posicionais. Verificado no source do tag
`rust-v0.142.5` (`codex-rs/exec/src/cli.rs`): `Command::Resume` (linha 168), posicionais
`SESSION_ID`/`PROMPT` do `ResumeArgsRaw` (linhas 180-181 e 202-203). A gramática adotada:

```
codex exec --json [--model <m>] [-c model_reasoning_effort=<e>] --skip-git-repo-check \
  (-s workspace-write | --dangerously-bypass-approvals-and-sandbox) resume <SESSION_ID> <PROMPT>
```

- **Flags SEMPRE antes do subcomando:** `-s/--sandbox` NÃO é global (`shared_options.rs:39`; o
  `mark_exec_global_args` de `cli.rs:157-163` só globaliza `model`, `dangerously_bypass…` e
  `bypass_hook_trust`) — depois do `resume` ele seria rejeitado. É a forma que o próprio source
  crava no `override_usage` (`cli.rs:12`): `codex exec [OPTIONS] <COMMAND> [ARGS]`.
- **Anti-silêncio (mesma fatia):** o adapter traduz exit≠0 em `Result.fail` (não exceção), e o
  `surgicalDistill` descartava o retorno — a falha do resume não deixava rastro além do "bilhete
  ausente" genérico. Agora o motivo vira WARN (`handoff: resume-cirúrgico falhou…`), precedente
  `RUN_LEASE_LOST`. A semântica fail-open (D8) segue INTOCADA: sem bilhete ⟹ sem continuação.
- **Prova permanente:** `CodexArgvTest.resumeCirurgicoUsaOSubcomandoResumeComSessionIdEPromptPosicionais`
  (pina a argv exata) + `HandoffLoopTest.redeResumeCirurgicoQueFalhaLogaOMotivoESegueFailOpen`.
  O lado claude (`--resume` é flag REAL lá) segue intocado e provado no `ClaudeArgvTest`.
- **Prova live da GRAMÁTICA (host, codex-cli 0.142.5 exato):** a forma antiga é rejeitada pelo
  clap (`error: unexpected argument '--resume' found`) e a forma nova atravessa o parser com
  todos os flags e morre só no lookup de um id falso (`thread/resume failed: no rollout found`)
  — subcomando, posicionais e posição dos flags confirmados contra o binário real, sem token.
- **Residual:** a prova ponta-a-ponta do LOOP (CEO codex real cruzando o dial de 55% com a rede
  gravando o bilhete) fica de carona para quando acontecer em uso real.

## Fora do escopo
- Interrupção mid-run (streaming + kill) — ADIADO (D1, Frente D).
- `--resume` para CONTINUAR o trabalho — PROIBIDO (D5); só a reabertura cirúrgica terminal (D6).
- Coluna `is_continuation` (D7) / persistir o corpo do bilhete no DB (D4) — só se doer.
- Levar o loop pros EXECUTORES (rodam Issues, não turnos) — v1 = só o CEO conversacional.
- **Fatia 2** (separar o CEO em 3 personas) — épico próprio, ADR-0031; o seam do skill-set da
  continuação fica pronto para ela plugar.

## Relaciona
ADR-0015 (a regra de ouro que esta AUTOMATIZA — a "fatia futura" das linhas 22-23, agora construída) ·
ADR-0002 (`agent_session` write-only; esta abre a exceção cirúrgica do `--resume` e é seu 1º leitor de
produção) · ADR-0014 (a medição de tokens + `context_window` que são o insumo) · ADR-0018 (o CEO
conversacional mediado por proposta — 1 turno = 1 Run) · ADR-0028/0029 (o padrão "ADR nova quando
diverge conscientemente" que esta segue) · ADR-0010 (módulos profundos atrás de seams pequenos — o
detector, o poster e o loop).

## Revisão 2026-07-11 (continuidade nativa — ADR-0038)
D5 ("`--resume` OFF para continuar; retomada = carta + estado vivo + bilhete") é **rebaixado**: com a
continuidade nativa por conversa (F-N1) + a dieta do prompt, a memória entre turnos é NATIVA e a
janela enche DEVAGAR. Este loop deixa de ser o substituto da memória e vira a **REDE DE SEGURANÇA de
quando a janela realmente enche** (destila e recomeça) — o papel que a D1 sempre lhe reservou. O
detector (55%) e o restart passam a disparar raro. O `--resume` cirúrgico (D6/M9) continua válido como
uma das camadas da rede. O bilhete mantém o papel de registro durável/auditável que a continuação lê.
Racional na ADR-0038.
