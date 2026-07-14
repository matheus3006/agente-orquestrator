# ADR-0041: Contabilidade off-book de subagentes — o gasto invisível vira linha rotulada

**Status:** Aceito (2026-07-12, execução da faixa R8-B da rodada 8 — F-N5;
`docs/handoff-grilling-motor-nativo.md` §1 D5; `docs/laudo-sondas-nativas.md` §Sonda 1)

> **Nota de proveniência (2026-07-14, fecho documental parcial — faixa R16-B):** o NÚMERO desta
> ADR foi alocado e consumido pelo **PR #18** (F-N5, MERGED 2026-07-12), mas o ARQUIVO nunca foi
> commitado — achado da validação da rodada 9 do ledger (`git log --all` vazio para o path; o PR
> #18 levou código + V28 + laudo-sondas, sem a ADR). Este arquivo foi escrito no fecho documental
> a partir do handoff durável da faixa (cópia em `orquestrador-agentes/scratchpad/
> handoff-volta-f-n5-offbook-adr0041.md`) + do código vivo (main `60ac302`) + da entrada
> "Contabilidade off-book de subagentes" do `CONTEXT.md` (essa sim commitada no PR #18).
> **Não renumerar** — a numeração é por decisão, não por ordem de merge (regra do ledger).

## Contexto
A sonda R6-B (Sonda 1, `docs/laudo-sondas-nativas.md`) provou empiricamente o que o grill
motor-nativo (R5-A, D5) suspeitava: **nenhum dos dois motores inclui o gasto de subagentes no
`usage` do run principal**. Medições da sonda: um run claude com 2 subagentes reportou
`output=63` tokens visíveis contra **27.347 reais** gastos pelos filhos; um run multi-agente
codex gastou **96k tokens de input só de overhead** (o que também confirmou por medição que o
teto D6 da ADR-0038 é requisito real, não paranoia).

Duas consequências sem tratamento: (a) o gasto de subagente fica **invisível** no ledger de
custo (ADR-0006/0014 — a observabilidade por tokens mente); (b) pior, se fosse somado
ingenuamente na janela do turno principal, **inflaria o % de contexto** que o
`HandoffThresholdDetector` (ADR-0015/0030) mede e dispararia **handoff falso** — o gasto do
subagente é REAL, mas acontece em **outra janela de contexto**, não na do turno que o causou.

O caminho de captura é **assimétrico por motor** (achado central da sonda — o desenho da fatia
foi GATEADO por ela, decisão D5 do grill: "sonda usage×subagentes ANTES de desenhar off-book"):
- **claude:** cada subagente completado emite `task_notification.usage.total_tokens` **já no
  stream padrão** (provado sem flag nova) → captura mínima, dentro do parser.
- **codex:** o subagente é uma thread com session JSONL próprio; o stream do parent só carrega
  os `receiver_thread_ids` do `collab_tool_call` (`spawn_agent`/`wait`/`close_agent`), nunca o
  usage dos filhos → exige um **side-channel** que leia os rollouts em `~/.codex/sessions`.
- OTEL cobriria os dois, mas só com collector — infra nova, fora do D5.

## Decisão

### D5.1 — Rótulo `off_book` no MESMO ledger, não moeda nova
`cost_event` ganha a coluna **`off_book boolean not null default false`** + índice
`(run_id, off_book)` (**V28**, `V28__cost_event_off_book.sql`). `AgentAdapter.CostEmission`
ganha o campo `offBook` (`AgentAdapter.java:103`; construtor de 4 args preservado por overload,
default `false` — zero call site quebrado). O molde é o precedente EXATO da casa:
`cost_event.orchestration_action` (V19, ADR-0016 T.4) — um **marcador no mesmo ledger
append-only** para "linha que não entra numa soma específica", não uma moeda paralela.
`costCents` continua sempre 0 (modelo de assinatura, ADR-0006/0014); o marcador só serve para
o handoff EXCLUIR a linha da conta da janela.

### D5.2 — claude: soma nativa no parser (fatia mínima)
`ClaudeStreamParser.subagentTokens()` acumula `task_notification.usage.total_tokens` a cada
subagente completado (`ClaudeStreamParser.java:86,148-150`). O canal nativo só expõe o TOTAL
(sem quebra input/output) → **convenção explícita documentada no código**: o total inteiro
entra em `tokensOutput`, `tokensInput=0` (`ClaudeCodeAdapter.java:185-186`) — não é medição
real de output, e nada a jusante depende da divisão (o handoff exclui a linha INTEIRA pelo
rótulo; a superfície de leitura expõe os dois campos crus).

### D5.3 — codex: side-channel de rollouts dos filhos (fatia maior)
`CodexStreamParser.childThreadIds()` coleta os `receiver_thread_ids` dos `collab_tool_call`
(`CodexStreamParser.java:132,186`). O **`CodexSubagentUsageReader`** (novo,
`execution/adapter/`) localiza o rollout de cada thread-filho em `~/.codex/sessions/**` por
**sufixo de nome de arquivo** (`-<threadId>.jsonl` — o nome do rollout É
`rollout-<timestamp>-<threadId>.jsonl`, confirmado ao vivo; localizar por data exigiria saber
o dia em que o filho nasceu, que só está… no nome do arquivo) e soma o **último**
`token_count` (cumulativo; mesma convenção last-wins do parser). Custo aceito: uma varredura
da árvore de sessões por run COM subagente — evento raro e capado pelo teto D6 (≤6 threads).

- **`sessions-dir` configurável** (`orquestrador.engines.codex.sessions-dir`, default
  `~/.codex/sessions` do HOST; 3º parâmetro do construtor do `CodexAdapter`) — cobre o caso
  vivo do v1 (LOCAL) e deixa a porta para o DOCKER-target apontar para onde o volume de sessão
  for visível pelo backend.
- **Belt fail-soft obrigatório:** rollout ausente/ilegível ⟹ **WARN**
  (`CodexSubagentUsageReader.java:59,74`) + contribui 0 para aquele filho — a contabilidade
  NUNCA quebra o run que a causou. É o mesmo belt que cobre a lacuna conhecida do DOCKER
  (volume `orq_codex_creds` não visível pelo backend → WARN + 0/0, run intacto).

### D5.4 — A emissão: segunda `CostEmission` rotulada, roteada pelo executor
Quando há subagente, cada adapter emite uma **2ª `CostEmission` com `offBook=true`**
(`ClaudeCodeAdapter.java:186` · `CodexAdapter.costEventsFrom`), ao lado da emissão normal do
turno. O `AdapterRunExecutor.recordCosts` só passa o marcador adiante
(`AdapterRunExecutor.java:472` — 1 linha, o único conversor `CostEmission`→`CostEvent`).

### D5.5 — Exclusão no handoff (o motivo de tudo)
`HandoffThresholdDetector` **exclui** emissões `offBook` da soma de janela
(`HandoffThresholdDetector.java:58`, `if (!emission.offBook())`). Sem isso, o gasto do
subagente inflaria o % do turno principal e dispararia handoff falso — consequência direta de
"rotular sem contar na janela errada" (achado da própria faixa, lendo o código; não estava no
escopo escrito do kickoff).

### D5.6 — Superfície de leitura
`GET /api/companies/{companyId}/budget/runs/{runId}/cost-events`
(`BudgetController.java:61-64` + `BudgetService.costEventsForRun`) lista as linhas do run com
o marcador — o gasto off-book fica **visível e rotulado**, por run. Ficou no
`governance/budget/` (não no `RunStepsController`, que era domínio da faixa R8-A pela
demarcação da rodada 8).

## Alternativas consideradas
- **OTEL/collector para os dois motores** — infra nova; o D5 do grill mandou capturar pelo
  canal nativo de cada motor, sem proxy próprio. Rejeitada.
- **Moeda/tabela nova para gasto de subagente** — rejeitada: o precedente
  `orchestration_action` (V19) já resolve "linha do ledger fora de uma soma específica" com
  superfície mínima.
- **Inventar divisão input/output no claude** — o canal só dá o total; uma divisão artificial
  seria mentira estruturada. Convenção explícita documentada no código venceu.
- **Localizar rollout do codex por data** — o parent não sabe quando o filho nasceu; o nome do
  arquivo já carrega o `threadId`. Busca por sufixo venceu.
- **Falhar o run quando o rollout não aparece** — contabilidade nunca pode matar o trabalho
  que a causou; WARN + 0 (fail-soft) venceu.

## Consequências
- O gasto de subagente deixa de ser invisível: aparece no ledger como linha própria, rotulada,
  por run — e a régua de handoff (ADR-0015/0030) fica imune a ele.
- `HandoffThresholdDetector` passa a ter uma dependência semântica no rótulo (linha excluída da
  soma) — provado por unitário (`offBookNaoEntraNaSomaDoLimiar`).
- **Lacuna conhecida (não regressão):** sob DOCKER o side-channel do codex não enxerga os
  rollouts (o backend não monta o volume da sessão) → cai no belt (WARN + 0/0). Mesma família
  do schema-arquivo da F-N3 → consolidada na candidata v1.1 **"paridade Docker"** (transporte
  de arquivo + visibilidade de volume). O v1 roda LOCAL (o caminho provado).
- Follow-up não-bloqueante registrado: a exclusão no detector foi validada por unitário; um
  VENDO cruzando o limiar de handoff DE VERDADE exigiria engine catalogado com janela pequena
  (risco baixo — a lógica é um `if` de uma linha).

## Prova (TDD; piso 568 → 587 backend, front 270 intocado)
19 testes novos/estendidos: `ClaudeStreamParserTest` · `CodexStreamParserTest` ·
`ClaudeCodeAdapterTest` · `CodexAdapterTest` · `HandoffThresholdDetectorTest` ·
`AdapterCostRoutingTest` · **`CodexSubagentUsageReaderTest`** (6 casos, `@TempDir`) ·
**`CostEventsForRunTest`**. **VENDO live nos 2 motores REAIS** (descartável 18081 + pg efêmero;
:8080 intocado), prompt forçando 2 subagentes (RED+BLUE) em cada motor:

| Motor | Principal (in/out) | Off-book rotulado | vs. sonda R6-B |
|---|---|---|---|
| claude (haiku) | 31.560 / 64 | **0 / 27.557** (`offBook=true`) | os ~27k invisíveis da sonda agora VISÍVEIS e rotulados |
| codex (default) | 283.632 / 858 | **70.430 / 43** (`offBook=true`) | 2 filhos somados via side-channel real |

Belt provado ao vivo: instância reiniciada com `sessions-dir` apontando para diretório
inexistente + 2 subagentes reais → **2 WARN**, run **SUCCEEDED**, emissão off-book presente
com 0/0 — fail-soft de ponta a ponta.

## Relaciona
ADR-0006/0014 (ledger de custo por tokens, `costCents=0` na assinatura) · ADR-0015/0030 (a
régua de handoff é quem NÃO pode ver este gasto) · ADR-0016 (V19 `orchestration_action` — o
precedente do marcador; T.4 contenção) · ADR-0038 (D6: tetos de subagente — o cap que bounda a
varredura do side-channel) · `docs/laudo-sondas-nativas.md` (a sonda que gateou o desenho) ·
`docs/handoff-grilling-motor-nativo.md` §1 D5 (a decisão de grill que esta fatia executa).
