# ADR-0040: Interrupção mid-run + transporte SSE — o botão "parar" do v1

**Status:** Aceito (2026-07-12, execução da faixa R8-A da rodada 8 —
`docs/handoff-grilling-motor-nativo.md` §1 D4, §3 F-N4; `docs/laudo-sondas-nativas.md` §Sonda 2)
**EMENDADA 2026-07-20 (ADR-0050): o `interrupt` ganha um 2º chamador — a escalação do estouro de
prazo** (zero mudança no caminho manual) — ver §Revisão 2026-07-20 ao final.

## Contexto
O grill do laudo motor-nativo (R5-A, D4) decidiu o desenho macro: o botão "parar" do v1 mata o
processo do motor (a sessão sobrevive no JSONL, ADR-0038 garante) e o dono digita a correção —
um turno novo resume a MESMA sessão nativa. Granularidade de turno, não processo-vivo/steering
fino (`claude --input-format stream-json` · codex `app-server`), que fica **candidata v1.1**
gateada na estabilização do `app-server` (ainda experimental no tag pinado — apostar fatia
estrutural nele contradiria a defesa do pino, ADR-0014).

A fatia abriu com uma sonda empírica (R6-B, `docs/laudo-sondas-nativas.md` §Sonda 2) que o laudo
deixara sem prova: kill no meio de uma tool call, nos dois motores. Vereditos que fecharam o
desenho:
- **`--resume`/`resume` é SÃO nas duas pontas** (exit 0, sem erro no `tool_use`/`function_call`
  órfão) — D4 se sustenta sem checkpoint próprio.
- **Assimetria de granularidade** (não muda o desenho, só informa o prompt de retomada): claude
  DESCARTA o turno interrompido (resume do último completo); codex PRESERVA o turno interrompido
  em contexto (soube dizer o comando exato que rodava ao ser morto).
- **Requisito NOVO e duro**: nos DOIS motores o processo-filho de uma tool call (o `perl`/`cat`
  do sonda, um stand-in para build/subprocesso real) **sobrevive ÓRFÃO** a um SIGKILL só-do-pai —
  reparented, seguindo vivo. Sem tratar isso, o "parar" vazaria a tool em andamento.
- **Gotcha de plumbing**: `codex exec` bloqueia lendo stdin se o pipe não for fechado
  (`< /dev/null` obrigatório) — já resolvido na base: `LocalExecutionTarget.java:376` redireciona
  de `/dev/null` para TODO motor desde o achado E3 (F.11/ADR-0025), e o `DockerExecutionTarget`
  nunca anexa `-i` ao `docker run` do próprio motor (`buildArgs`) — o stdin do container já chega
  fechado por construção. Nenhuma mudança nova exigida por este achado.

## Decisão

### D4.1 — Kill do PROCESS GROUP, não só o processo raiz
O requisito duro da sonda vira contrato do `ExecutionTarget`: `interrupt(UUID runId)`, default
`false` (aditivo — molde de `screenshotServer`/`listIssueWorkspaces`, fakes de teste intactos).
Cada alvo resolve por um mecanismo PRÓPRIO, sem um "kill de process group" genérico:
- **LOCAL**: `RunProcessRegistry` (`execution/runtime/RunProcessRegistry.java`) — colaborador
  interno do `LocalExecutionTarget` (molde do campo `previewServers` da MESMA classe: mapa
  privado, não um bean Spring — ninguém mais precisa injetá-lo). `run()` registra o `Process` ao
  nascer, desregistra no `finally`; `interrupt()` mata `process.descendants()` PRIMEIRO (a árvore
  ainda existe com o pai vivo) e só depois o processo raiz — o MESMO molde que
  `LocalExecutionTarget.stopPreviewServer` já usa para servidores de preview.
- **DOCKER**: nome DETERMINÍSTICO do container por runId (`DockerExecutionTarget.containerName`,
  `"orq_run_" + runId`, linha 46), carimbado em TODO `docker run` do motor via `--name`
  (`buildArgs`, linha 53). `interrupt()` (linha 554) é `docker rm -f <nome>` — molde EXATO de
  `buildServerStopArgs`/`stopPreviewServer` (preview server). Não precisa rastrear processo do
  HOST nem enumerar descendentes: o motor é o PID 1 do container, e a namespace de PID inteira
  morre com ele — os filhos da tool call somem juntos, de graça (o cliente `docker run` do host
  IGNORA SIGKILL — não repassa ao container; só o nome alcança o container de verdade, por isso
  o rastro de processo do host nunca serviria aqui).

`ExecutionTarget.run()` ganhou o parâmetro `runId` (era `run(Spec, Consumer<String>)`) para que
LOCAL/DOCKER saibam qual chave registrar — mudança mecânica nos 3 adapters
(`ProcessAgentAdapter`/`ClaudeCodeAdapter`/`CodexAdapter`, só o call-site: `context.runId()` já
existia no `Context`) e nos 5 fakes de teste que implementavam a interface.

### D4.2 — CAS RUNNING→INTERRUPTED ANTES de tocar o processo (a garantia de não-corrida)
Novo valor `RunStatus.INTERRUPTED` — `status` é `text` sem CHECK constraint
(`V3__heartbeat.sql`), então É literalmente um novo valor de enum, ZERO migração (V29
permanece RESERVADA, não consumida). `HeartbeatRunStore.interrupt(runId, owner, reason)`
(linha 88) reusa o MESMO `finalizeRun` guardado-por-dono que `finishSuccess`/`finishFailure` já
usam — nenhuma query nova no repositório.

A ORDEM é o design: `RunInterruptService.interrupt()` faz o CAS **primeiro** (linha 48), só
DEPOIS pede ao `ExecutionTarget` para matar o processo. Por quê: o `HeartbeatDispatcher` está
bloqueado dentro do `run()` síncrono enquanto o processo vive — ele só alcança
`store.finishFailure()` (o exit 137 do kill vira uma falha comum no adapter) DEPOIS que o
processo morre de verdade. Fazendo o CAS antes de tocar o processo, ele **já terá vencido por
CONSTRUÇÃO** quando o `finishFailure` natural chegar — que cai no vazio sob o MESMO guard que já
protege a corrida node-A×node-B do `LeaseRenewalTest` (nenhuma query nova, nenhum estado extra:
o mecanismo de "perdedor cai em silêncio" já existia). Prova disso é o próprio
`RunInterruptTest.finishFailureNaturalPosInterruptEhNoOp` — sem essa ordem, o resultado final
dependeria de timing (quem escreve primeiro no banco), não de desenho.

O kill do processo em si é BEST-EFFORT e vem depois: falhar em matar (infra fora, processo já
tinha morrido sozinho) não desfaz a transição — o intent do dono já está gravado, e o pior caso é
um processo órfão que a supervisão trataria de qualquer jeito (mesma classe de risco de qualquer
kill, não um risco novo desta fatia).

**Endpoint**: `POST /api/companies/{companyId}/runs/{runId}/interrupt` (`RunInterruptController`)
— board-only (`assertBoardMember`, mesmo espírito de `RunStepsController`: um agente autenticado
não para o próprio run nem o de ninguém). 200 com `{runId, status, processKilled}`; 404 fora da
company; 409 se o run já não está `RUNNING` (a corrida "terminou entre o board carregar a tela e
clicar parar" é tratada, não um bug).

### D4.3 — Resume: ZERO código novo (ADR-0038 já resolve)
A sonda provou e o produto confirma: `AgentAdapter.Result.fail(...)` sempre carrega
`session=null`, e `AgentSessionStore.record()` é no-op em handle nulo/branco (D1.4 da ADR-0038)
— logo o handle salvo da conversa NUNCA é sobrescrito por um run que falhou (kill incluso). A
correção do dono vira uma mensagem de conversa comum → novo turno →
`ConversationTurnCoordinator.claimFor` lê `agentSessions.currentHandle()` (o MESMO handle de
ANTES do turno interrompido, intacto) → resume nativo. `TurnStatus` (PENDING/ANSWERED/FAILED)
também não precisou de um 4º valor: o turno interrompido liquida `FAILED` pelo MESMO
`settleFailed` de qualquer falha — o comentário da própria enum já cravava o princípio ("o turno
não espelha máquina de estados alheia"; quem "é" INTERRUPTED é o Run, fonte única). Nenhuma
mudança em `AdapterRunExecutor`, `ClaudeCodeAdapter`/`CodexAdapter` (além do call-site do D4.1),
`ConversationTurnCoordinator` ou `AgentSessionStore`.

### D4.4 — Transporte SSE do StepTrace, com keep-alive (achado do VENDO)
`GET /api/companies/{companyId}/runs/{runId}/steps/stream` (`RunStepsController.stream`) — um
`SseEmitter` por conexão, poll de fundo a cada 500ms (`ScheduledExecutorService` de threads
daemon, molde de `LeaseRenewer`) reusando a MESMA paginação de `steps()` (o guard roda UMA vez,
na inscrição — o poll de fundo não tem requisição HTTP para reautenticar). Fecha sozinho
(`emitter.complete()`) quando o status vira terminal (`isTerminal`, os 4 valores — INTERRUPTED
incluso).

**Achado do VENDO (não estava no desenho original, descoberto ao vivo)**: uma run sem STDOUT
novo por um tempo deixa a conexão com ZERO BYTES por ciclo de poll — o rewrite dev do Next.js
(e qualquer proxy/LB real com timeout de socket ocioso, ex.: ALB) derruba isso como conexão
morta (`ECONNRESET`, reproduzido consistentemente em tab nova, sem relação com nenhum outro bug
desta fatia). Fix: quando o ciclo não tem step novo, manda um COMENTÁRIO SSE
(`emitter.send(SseEmitter.event().comment("keep-alive"))`) — o cliente ignora o conteúdo, só
mantém o socket vivo. Prática padrão de SSE em produção, não um workaround só do Next.js dev.

O consumo do front é **`fetch()` + `ReadableStream`, não `EventSource` nativo**: o board
autentica por header `X-Board-User` (`api.ts`, "entra AQUI e em nenhum outro lugar" — ADR-0013),
e `EventSource` não manda headers arbitrários — virar querystring vazaria o ator para
logs/histórico. `streamRunSteps()` (`api.ts`) parseia `event:`/`data:` manualmente (multi-linha
por spec, junta por `\n`) e devolve `{close()}` para o `useEffect` desligar. `useRunTranscript`
mantém o catch-up REST intocado (a lógica de páginas/offset já testada); o SSE só liga DEPOIS do
`caughtUp` inicial e enquanto o run não é terminal — vira o transporte do "ao vivo", com o poll
de 2s como FALLBACK (liga de novo se o SSE cair; sem retry automático da conexão em si — o poll
já cobre a continuidade). Achado de implementação (não do desenho): `queryKeys.runSteps()`
devolve um array NOVO a cada chamada — usar isso como dependência do `useEffect` reconectava o
SSE a CADA render (looping de connect/abort, visto ao vivo no Network tab antes do fix); a chave
tem que ser calculada DENTRO do efeito, nunca fora dele.

**Front**: botão "parar run" no `StepTraceDrawer` (molde EXATO de
`preview-server-section.tsx`/`stopPreviewServer`: sem confirm dialog, `useMutation`,
label "parar run" → "parando…" enquanto pendente, ícone `Square`), visível só quando o run não é
`frozen` nem terminal. `RunStatus` ganha `"INTERRUPTED"` (`api.ts`, `transcript.ts`
`isTerminalRunStatus`); `.run-status.INTERRUPTED` reusa o tratamento mudo de `.CANCELLED`
(`globals.css`) — nem fósforo de sucesso, nem coral de erro.

## Alternativas consideradas
- **Processo-vivo/steering fino no v1** (`app-server`/`--input-format stream-json`): rejeitado
  pelo laudo — `app-server` é experimental no tag pinado (o mesmo tag prova churn real,
  `codex proto` removido) e apostar fatia estrutural nele contradiz a defesa do pino. D4 é o
  degrau de BAIXO desse desenho: quando `app-server` estabilizar (gate explícito, candidata
  v1.1), o upgrade troca só o mecanismo de entrega da correção — endpoint, board, SSE ficam.
- **Checkpoint próprio para o turno interrompido**: desnecessário — a sonda provou que a sessão
  nativa (JSONL) já sobrevive ao kill nos dois motores; construir um checkpoint paralelo
  duplicaria o que o motor já garante.
- **Rastrear o processo do HOST para matar o container DOCKER** (em vez do nome determinístico):
  o cliente `docker run` no host ignora SIGKILL — não repassa ao container — então essa rota
  nunca funcionaria; o nome + `docker rm -f` é o único caminho que alcança o container de
  verdade.
- **`EventSource` nativo no front**: não manda headers arbitrários; o `X-Board-User` é o único
  canal de auth do board (ADR-0013) e não pode virar querystring (vazaria o ator para
  logs/histórico do navegador/proxies). `fetch()` + parser manual preserva o mesmo canal de auth
  de todo o resto do app.
- **Nova query/coluna no `HeartbeatRunRepository` para o CAS de interrupt**: desnecessário — o
  `finalizeRun` genérico (já usado por `finishSuccess`/`finishFailure`) cobre qualquer transição
  `from→to` guardada por dono; adicionar uma query dedicada seria duplicar o que já existe.

## Consequências
- O board ganha controle real sobre um run em andamento — supervisão corretiva sem esperar o
  run terminar sozinho, sem perder a sessão nativa (o valor central do ADR-0038 se paga aqui).
- `ExecutionTarget` ganha uma 2ª dimensão de variação por alvo (além de run/read/write/preview):
  cada implementação resolve "matar" do jeito que faz sentido pro seu modelo de isolamento (mapa
  de processos no host × nome de container) — nenhum mecanismo genérico de "kill" imposto de
  cima, ecoando o padrão já estabelecido pelos outros métodos aditivos da interface.
- `RunStepsController` ganha um 2º transporte para o mesmo dado (REST-poll + SSE) — o poll NÃO
  foi removido (fallback + catch-up inicial), então zero regressão pra quem já dependia dele
  (front antigo, scripts, etc.).
- **Achado registrado, não corrigido nesta fatia**: `CompanyScopeInterceptor` (tripwire de
  deny-by-default) loga um FALSO-POSITIVO "DENY-BY-DEFAULT VIOLADO" para `/steps/stream` — o
  `ActorContext` é `ThreadLocal` e o `afterCompletion` do dispatch async de conclusão pode rodar
  num worker thread diferente do que executou o `assertBoardMember`. A proteção REAL independe
  disso (o guard roda síncrono, na mesma thread da requisição, ANTES do `SseEmitter` nascer —
  provado por `RunStepsStreamHttpTest.streamForaDaCompanyOuInexistenteDa404EBoardOnly403`).
  Limitação estrutural do tripwire para QUALQUER endpoint async/SSE futuro (não só este) —
  consertar a INFRA do tripwire (trocar `ThreadLocal` por algo que atravesse o re-dispatch, ou
  pular o check quando `request.isAsyncStarted()`) é trabalho de infra transversal, fora do
  escopo de uma fatia de feature; fica como achado para a próxima vez que um endpoint async
  precisar deste tripwire.
- **Fora do escopo (por decisão do grill, não esquecimento)**: processo-vivo/steering fino
  (candidata v1.1, gate = `app-server` sair de experimental).

## Prova (TDD; piso 568→587 backend, 270→280 front)
Backend (19 testes novos): `RunInterruptTest` (3 — CAS, not-running, corrida com finishFailure
natural) · `RunProcessRegistryTest` (3 — kill de descendente antes do pai, runId desconhecido,
unregister) · `RunInterruptServiceTest` (3 — integração real via `LocalExecutionTarget`
autowired: processo de verdade registrado e morto) · `RunInterruptHttpTest` (3 — 200/409/404+403)
· `RunStepsStreamHttpTest` (5 — isTerminal, backlog+fecha, ao-vivo+fecha, keep-alive, guard) ·
mais 1 teste de integração em `LocalExecutionTargetRunTest` (run()→registro→interrupt()→kill
ponta a ponta) e 1 em `DockerExecutionTargetArgsTest` (buildInterruptArgs). Frontend (10 testes
novos): `interruptRun` (3) · `streamRunSteps` (6, incluindo parsing de evento partido em dois
chunks de rede) · `isTerminalRunStatus` (+1 linha INTERRUPTED). VENDO (skill `verify`, instância
descartável H2 em :18081, NUNCA :8080): run real (`process` adapter, LOCAL) com tool longa
(`cat /dev/zero`) → "parar run" no board → **zero órfãos de processo, provado por `ps` antes/depois
(pai + filho, ambos)** → INTERRUPTED visível no board (drawer e, após refresh, Mission Control:
`IDLE`/`activeRun: null`) → SSE ao vivo confirmado no Network tab (200 sustentado, keep-alive a
cada 500ms). Prova em ambos os motores reais (claude/codex) do resume pós-kill fica para a
verificação de campo do operador (o mecanismo de resume em si é zero-código-novo, D4.3 — a prova
E2E com CLI real é a mesma que já valida o ADR-0038).

## Revisão 2026-07-20 (prazo de subprocesso, ADR-0050)

> Emenda ADITIVA da fatia motor-thread-única. **Zero mudança no caminho manual** — o `interrupt`
> ganha um segundo chamador, não um segundo comportamento.

O `interrupt(runId)` desta ADR foi desenhado como ato do OPERADOR (o botão "parar"). A ADR-0050 o
reusa como **mecanismo de escalação do estouro de prazo**, pela razão que esta própria ADR
estabeleceu: `destroyForcibly` no processo do host **não basta**.

- Em DOCKER, o cliente `docker run` não repassa sinal ao container — é exatamente por isso que o
  `buildInterruptArgs` endereça o container pelo **nome determinístico**.
- Em LOCAL, um SIGKILL só-no-pai deixa o filho da tool call vivo e órfão — o requisito duro medido
  pela sonda R6-B, que motivou o "descendentes primeiro" do `RunProcessRegistry.interrupt`.

Um prazo que apenas devolvesse a thread do chamador, deixando container ou descendente rodando,
trocaria uma **trava visível** por um **vazamento invisível** — trabalho queimando tokens sob um run
já marcado como `FAILED`. Por isso o estouro chama `interrupt(runId)` **antes** do golpe no cliente.

Consequência prática: `interrupt` passa a ter dois chamadores (o `RunInterruptService`, na thread do
Tomcat, e o `ProcessoComPrazo`, na thread de dispatch). Ambos são idempotentes por construção — o
`docker rm -f` trata "no such container" como sucesso e o registry de processos ignora runId ausente.
