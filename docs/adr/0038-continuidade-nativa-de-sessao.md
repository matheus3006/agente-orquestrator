# ADR-0038: Continuidade nativa de sessão por Conversa e por Issue — a regressão-mãe do laudo motor-nativo, corrigida

**Status:** Aceito (2026-07-11, execução da faixa R6-A da rodada 6 — `docs/handoff-grilling-motor-nativo.md`, D1/D3/D6; fatias F-N1 + F-N2)

## Contexto
O laudo motor-nativo (R4-C, `docs/laudo-motor-nativo.md`) provou, com file:line, que os dois CLIs
eram tratados como **funções puras `prompt → texto`, re-executadas do zero a cada turno**: o handle
nativo de sessão era capturado e persistido a cada Run (`AgentSessionStore.record`, `agent_session`
V16) e **nunca lido** no caminho normal — `AdapterRunExecutor` passava `priorSession=null` literal.
Todo o resto (memória entre turnos) foi reimplementado por fora (prompt recomposto + carta + dossiê).
O comentário da V16 já cravava o desenho: *"FIAÇÃO PRONTA, FEATURE DESLIGADA: o --resume por-
Conversation religa lendo daqui"*. Esta é a **regressão-mãe R1** do laudo — o raciocínio do turno
morre no fim do Run e o modelo herda só o destilado (sintoma vivo: o chute *"encaminho pro Feynman"*
do caso B, `ConversationTurnCoordinator.java`).

A fiação existia INTEIRA nas duas pontas: `agent_session`/V16 (chave `(agent_id, conversation_id)`)
+ `AgentAdapter.Context.priorSession` + o argv pronto dos dois motores (`ClaudeArgv`/`CodexArgv`
emitem `--resume`/`resume <id>` quando `priorSession` vem preenchido; a gramática do codex foi
corrigida pelo PR #15 — pré-requisito satisfeito). A mudança central é **ler** o handle onde antes
ia `null`.

O grill (R5-A) fechou o desenho macro (D1–D7); esta ADR executa **F-N1** (D1 — CEO/personas, turno
de conversa) + **F-N2** (D3 — executores, run de Issue/heartbeat) + os tetos **D6** embutidos como
requisito de aceite. Snapshot base: main `470b697` (piso 526 backend). Novo piso: **547**.

## Decisão
Religar a continuidade nativa (`--resume`) **no caminho normal** dos Runs, escopada à unidade de
trabalho correta, com o prompt **em dieta** para não pagar contexto em dobro, e com tetos de
subagente embarcados. O loop ADR-0030 **não morre**: é **rebaixado a rede de segurança** de quando a
janela enche.

### F-N1 — Continuidade por CONVERSA (D1)

**D1.1 — Ler o handle no turno de conversa.** `ConversationTurnCoordinator.claimFor` lê
`AgentSessionStore.currentHandle(agentId, conversationId)` UMA vez (sob o claim) e o entrega no
`TurnAssignment.priorSession`; o executor o fia no `Context` (era `null` literal). **Escopo por
conversa:** a chave `(agent_id, conversation_id)` do `agent_session` já é conversa-scoped — assunto
novo = `conversation_id` novo = sem handle = sessão fresca. Fechou a conversa → a sessão morre
naturalmente (ninguém mais lê aquele handle).

**D1.2 — Dieta do `composePrompt` (inseparável, condição carimbada do D1).** Turno 2+ (com handle,
`resuming=true`) roda numa sessão nativa que JÁ carrega o protocolo da persona (visto no turno 1).
Re-injetar os ~75 linhas de blocos estáticos (conversa-com-o-dono, protocolo da persona,
regra-de-ouro, blocos-tipados) seria o "contexto em dobro" (a sessão lembra E o prompt repete). O
prompt dietado (`composePromptDietado`) carrega só o que a sessão NÃO tem: o **estado vivo
ATUALIZADO** (dinâmico — o board mudou), a **mensagem nova** do dono e um **marcador curto** de
continuidade. O recorte fino do estado vivo (delta por linha) fica como otimização futura — a
repetição estática é o grosso do custo dobrado, e o estado vivo precisa estar corrente de todo jeito.

**D1.3 — ADR-0030 rebaixado a rede; o dial da compactação nativa (decisão dentro da fatia).** Com a
continuidade + a dieta, a memória entre turnos é NATIVA e a janela enche DEVAGAR (cada turno soma só
mensagem+delta à sessão contínua, em vez de re-injetar tudo). O detector externo
(`HandoffThresholdDetector`, 55%) + o restart destila-e-recomeça (ADR-0030) passam a ser a **rede de
segurança de quando a janela realmente enche** — não o carregador da memória a cada turno. **Decisão
sobre o dial nativo (`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` · `model_auto_compact_token_limit`):
avaliado, NÃO baixado cego.** Baixar o dial nativo para 55% empilharia DOIS mecanismos no mesmo
limiar (compactação nativa in-place × restart externo) — brigariam. O knob do claude foi **fiado**
(config `orquestrador.engines.claude.autocompact-pct-override`, default VAZIO = default da CLI),
para o operador ligar após a prova empírica da semântica (o laudo §5 adia essa prova a uma sonda —
ligar cego arriscaria mis-tunar a compactação). O dial token-based do codex (precisa da janela
catalogada fiada no adapter) fica como follow-up explícito; a auto-compactação do codex é nativa e
ligada por default no core (laudo [X-2]), então o codex já compacta — só a sintonia ao golden-rule é
que fica adiada. O bilhete `.orq/handoff/` mantém o papel que sempre foi de melhor nele: **registro
durável e auditável** que a continuação fresca lê, não o único fio de vida.

**D1.4 — Semântica de retry do handle (provada com teste, aberto §4 do handoff).** Handle salvo = o
último gravado **com sucesso**. `AgentSessionStore.record` é no-op quando `externalSessionId` é
nulo/branco, e `Result.fail`/`failSessionLost` carregam `session=null` — então um Run que falha
**antes de gravar** NÃO sobrescreve o handle. O retry (requeue do reaper com o mesmo runId, ou o
próximo turno) re-lê o handle ANTERIOR e resume: **perde só o turno falhado, nunca a conversa**. O
CLI rotaciona o `session-id` a cada uso (`AgentSession.touch`: "o handle mais novo VENCE") — sobre um
Run bem-sucedido; sobre um falho, o anterior sobrevive. Provado em
`NativeSessionContinuityTest.turnoQueFalhaAntesDeGravarPreservaOHandle_*`.

### F-N2 — Continuidade por ISSUE (D3)

**D2.1 — Mesma plumbing no run de Issue/heartbeat.** O executor lê
`currentHandle(agentId, issue.conversation_id)` como `priorSession` e **grava** o handle pós-Run (o
executor só gravava em turno; agora grava em Issue também) — run N+1 do heartbeat resume o
raciocínio do run N. Escopo por Issue: fechou a Issue → a sessão morre.

**D2.2 — Escopo reusa `(agent_id, conversation_id)` via `issue.conversation_id` (V27), SEM migração
(aberto §4 confirmado no código).** A coluna `agent_session.conversation_id` é **NOT NULL + FK para
`conversation(id)`** — logo NÃO se pode enfiar um `issue_id` ali. `issue.conversation_id` (V27) é
**nullable**: só issues ABSORVIDAS (ADR-0037) têm conversa. Confirmação da fatia: o resume por Issue
vale para issues COM conversa; issue SEM conversa (gate/corretiva/legado) segue **fresh-start** — o
baseline de hoje, **zero regressão** (a verdade-fonte do executor é o WORKSPACE, durável e
reconstruível — princípio D3). **V28 NÃO consumida** (o "toda issue nasce com conversa" que
destravaria o resume universal é a fatia futura §G.4 do handoff da ADR-0037; forçar isso agora
exigiria migração — fora do escopo desta fatia). O null-safe `IssueService.conversationIdOf` nunca
aborta um Run por causa da leitura do handle.

**D2.3 — Fallback BARULHENTO obrigatório (D3 #7).** Resume falhou (handle inválido/sessão sumida) →
evento visível `RUN_SESSION_LOST` (espírito do `RUN_LEASE_LOST` do PR #9) + **fresh start** do
workspace (re-execução SEM `priorSession`), NUNCA silêncio, NUNCA a Issue travada por sessão perdida.
Mecânica: `SessionLostSignal` (irmã do `CredentialFailureSignal`) fareja o aviso "não achei a sessão"
nos parsers (`sessionLost()`); o adapter marca `Result.failSessionLost` só quando exit≠0 **E** houve
resume (guarda dupla — sem `priorSession` o sinal não vale); o executor emite o evento e re-executa
**uma vez** fresco (o fresh não resume ⟹ não re-cai). Custo do fallback = re-derivar do workspace,
barato (D3). Provado em `NativeSessionContinuityTest.resumeDeSessaoSumidaEmiteRunSessionLostEReiniciaFresco`.

**D2.4 — Bilhete restrito a troca de agente (D3 #8).** O executor NÃO ganha pipeline de bilhete para
continuidade do MESMO agente (resume resolve). O `ContextHandoffLoop.afterCeoTurn` só roda em turno
(nunca em run de Issue) — satisfeito por construção. O bilhete `.orq/handoff/` fica para troca de
AGENTE (executor A→B, claude→codex — sessão nativa não atravessa agentes) e para a rede do CEO (D1.3).

### D6 — Tetos de subagente como REQUISITO DE ACEITE (embarcados, não fatia nova)
Regra carimbada: *fatia que amplia autonomia não shippa sem o teto correspondente.* Aplicados em
TODO Run dos adapters ricos (CEO/F-N1 e executores/F-N2).
- **codex (teto numérico NATIVO, ativo por default):** `-c agents.max_threads=<n>` +
  `-c agents.max_depth=<m>` (config `orquestrador.ceiling.codex.max-threads`/`.max-depth`, default
  6/3). O multi-agente do codex é estável e ligado por default (laudo [X-3]) — sem teto de produto,
  só o default da CLI o bounda; aqui vira decisão explícita.
- **claude (cap NATIVO + seam de deny):** o aninhamento de subagente do claude já é capado
  NATIVAMENTE em profundidade 5 (laudo [C-3]) — esse é o teto ativo de nesting, intacto. Sobre ele,
  o seam `--disallowedTools` (config `orquestrador.ceiling.claude.disallowed-tools`, default VAZIO) é
  o lever de PRODUTO para negar tools por nome (`Agent(...)`/`Task(...)`), pronto para o F-N6 (skills
  read-only) e para tuning do operador sem tocar código. A assimetria (codex numérico × claude
  deny+native-depth) é o achado do laudo (§1.3), não um gap.

## Alternativas consideradas
- **Manter `--resume` desligado + destilar sempre (status quo ADR-0030 como memória):** é a
  regressão-mãe. Rejeitada pelo norte do dono ("estamos regredindo a qualidade nativa").
- **Baixar o dial de compactação nativa a 55% junto com o detector a 55%:** dois mecanismos no mesmo
  limiar brigam. Rejeitada em favor de "nativo faz a memória; ADR-0030 é a rede quando enche".
- **Migração V28 (dar conversa a toda issue) para resume universal de executor:** desproporcional a
  esta fatia; a coluna é NOT NULL FK e a evidência (workspace = verdade-fonte do executor) diz que o
  fresh-start é fallback barato. Deferida à fatia §G.4 do ADR-0037.
- **Fallback silencioso (marcar FAILED e pronto quando o resume falha):** viola o anti-silêncio.
  Rejeitada em favor de `RUN_SESSION_LOST` + fresh start.

## Consequências
- A memória entre turnos passa a ser NATIVA (o modelo referencia detalhe do turno 1 no turno 3 sem
  re-injeção); o prompt encolhe no turno 2+; a janela enche devagar; o restart ADR-0030 fica raro.
- `AgentAdapter.Result` ganha `sessionLost` (5º componente; factories preservadas — só o construtor
  canônico, usado apenas pelas factories, mudou de aridade).
- Emenda as ADR-0002 (o `--resume` deixa de ser "desligado no v1" — vira ligado no caminho normal,
  com a exceção resume-cirúrgico do ADR-0030 absorvida como caso particular da rede), ADR-0018 (a
  continuidade do CEO deixa de ser "só carta + recorte do log": é carta + sessão nativa + estado vivo
  dietado) e ADR-0030 (D5 "`--resume` OFF para continuar" → **rebaixado**: o loop é a rede de janela
  cheia, não o substituto da memória).
- **Aberto / follow-up explícito:** o dial de compactação do codex (janela catalogada no adapter); a
  prova empírica da semântica do `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` (sonda, laudo §5); o resume
  universal de executor (migração V28, §G.4 do ADR-0037); o F-N6 (hook PreToolUse — skills read-only
  + cinto de dir de sessão) devolvido como follow-up (subsistema de materialização de hooks próprio,
  com o gotcha de trust do codex — merece fatia dedicada).
- **Forward-ref: ADR-0040 (F-N4, R8-A) consome esta continuidade.** A interrupção mid-run (o botão
  "parar" do board) mata o processo do motor e resume o MESMO turno depois — só é barata (zero
  checkpoint próprio) porque D1.4 já garante que um run que falha (kill incluso) nunca sobrescreve
  o handle salvo (`AgentSessionStore.record` no-op em sessão nula). Nenhum código deste ADR mudou
  para viabilizar aquela fatia — a garantia já estava aqui.

## Prova (TDD; piso 526 → 547)
`NativeSessionContinuityTest` (9): turno-2 resume · conversa nova fresca · dieta · retry preserva
handle · issue-com-conversa resume+grava · issue-sem-conversa fresca+não-grava · fallback barulhento.
`ClaudeArgvTest`/`CodexArgvTest`/`ClaudeCodeAdapterTest`/`CodexAdapterTest` (tetos D6 + sessionLost).
`SessionLostSignalTest`. VENDO (skill `verify`, instância descartável): as 4 provas do grill —
memória viva 3+ turnos, teto de subagente, retry de handle, fallback barulhento.
