# ADR-0004: Contrato do AgentAdapter.execute()

**Status:** Aceito (2026-06-30)

## Contexto
O Paperclip tem **um único call site** que invoca qualquer runtime de agente, com toda diferença absorvida atrás de `execute()`. Replicamos isso em Java, agora que Session (ADR-0002), StepTrace e materialização de skills estão resolvidos.

## Decisão
Interface única, um único ponto de invocação no módulo `execution`:

```
interface AgentAdapter {
    AdapterDescriptor descriptor();        // tipo, capacidades, detecção de modelo
    RunResult execute(RunContext ctx);     // o ÚNICO call site
}
```

**RunContext (entra):** company, agent, issue + ancestralidade de goal, **Workspace** (caminho montado), **priorSession** (handle do ADR-0002 ou null), **skillSet** (caminhos já materializados do banco), budgetView (preflight), secrets (escopados), executionTarget (`docker`/`local`), **eventSink** (canal de StepTrace).

**RunResult (sai):** **updatedSession** (handle), status, costEvents, **error tipado** (`transient_upstream`+`retryNotBefore` | `model_refusal` | …), producedArtifacts (incl. PreviewArtifact).

**StepTrace:** o adapter alimenta `ctx.eventSink` por **parser de stdout** (universal, default) e/ou **canal estruturado** opcional (agentes cooperativos). Transmitido por SSE.

## Alternativas consideradas
- Múltiplos pontos de invocação por tipo de runtime — rejeitado: foi exatamente o que o "1 call site" do Paperclip evitou.
- Só canal estruturado para StepTrace — rejeitado (quebra "bring your own agent").

## Consequências
- Novo runtime = nova impl de `AgentAdapter` (built-in ou plugin), **zero mudança no core**.
- O contrato carrega o **no-remote-git** implícito: o Workspace montado é a única persistência de arquivos entre Runs; falha de sync = erro de Run.
- Erros tipados habilitam retry com backoff (`retryNotBefore`) vs. parada (`model_refusal`).

## Revisão 2026-07-02 (grilling motores, ADR-0014)
- **`Context` ganha `model` e `effort`** (String, nullable, `null` = default do plano); o adapter os traduz nas
  flags do seu CLI (dicionário no ADR-0014). A "detecção de modelo" prevista no `descriptor()` se concretiza no motor.
- **`Result.costEvents`** passa a ser **emitido pelos adapters ricos** com `provider`+`model`+tokens reais e
  `costCents=0` (ADR-0006/0014, Q7). A fatia B adicionou a factory **`fail(error, costEvents)`**: os tokens gastos
  ANTES de falhar contam (o `AdapterRunExecutor` roteia no sucesso E na falha).
- **StepTrace:** o parser de stdout dos adapters ricos (a) traduz eventos JSON do CLI em steps **semânticos**
  (`type` = evento nativo em UPPER; linha não-JSON → `STDOUT`, fail-soft) e (b) **extrai `usage`/tokens**. São
  módulos PUROS atrás de seams pequenos (`ClaudeStreamParser`/`CodexStreamParser`, regra de ouro ADR-0010),
  testáveis isolados porque o CLI real não roda no teste.
- **"Zero mudança no core" CONFIRMADO:** `ClaudeCodeAdapter`/`CodexAdapter` são novas impls; o `AdapterRunExecutor`
  só passou a injetar `model`/`effort` e a rotear `costEvents` (dependência one-way executor→`BudgetService`, sem
  ciclo de bean). O erro tipado (`model_refusal`/`transient_upstream`) segue **fatia futura** — no v1 recusa do
  CLI vira `Result.fail(...)` (fail-soft, string).

## Revisão 2026-07-04 (frente Teams — ADR-0018)
- O `command` do `Context` passa a ser **montado por-Run** (estado vivo do `LiveStateProjector` + a mensagem do
  turno) em vez do `runtimeCommand` estático — o CEO conversacional não tem comando fixo.
- O **CEO roda com `issueId=null`** (delegador-puro, sem workspace de código; **um turno = 1 Run** por-Conversation).
  O `SkillMaterializer` ganha um irmão, `CompanyMemoryMaterializer` (materializa a carta → `.claude/CLAUDE.md`).

## Nota de reconciliação (2026-07-10 — divergência MÉDIA da auditoria doc×código de 2026-07-07)

**`RunResult.producedArtifacts` não existe.** O `Result` como construído
(`AgentAdapter.Result`) tem 4 campos: `succeeded`, `error`, `session` (o *updatedSession* do
contrato) e `costEvents` — sem campo de artefatos. A intenção ("o Run produz um
PreviewArtifact") é honrada **fora do Result**, por captura lateral no único call site
(`AdapterRunExecutor.execute`): após o sucesso e ANTES do settle, fora de transação,
`PreviewArtifactCapturer.captureIfPublished(runContext)` lê os arquivos que o agente publicou
no workspace por convenção (ADR-0021, best-effort — falha de captura nunca vira exceção); a
declaração de servidor segue o mesmo ponto (`registerIfDeclared`, ADR-0023). O adapter continua
sem saber de preview — o mecanismo é do executor, não do contrato. Direção decidida no
levantamento de paralelismo: **contrato do ADR atualizado para o código** (a captura-por-
convenção substituiu o campo; o texto original acima fica como registro do desenho da época).
Obs.: o "error tipado" do contrato original também segue simplificado (`error` String,
fail-soft) — já registrado na Revisão 2026-07-02 como fatia futura.

## Nota de reconciliação (2026-07-14 — fecho documental; 4 divergências MÉDIA da auditoria doc×código de 2026-07-11, snapshot `2ab8eac`)

Mesmo padrão da nota acima: **texto atualizado para o código; corpo original preservado como
registro do desenho da época.** Verificação re-feita na main `60ac302`:

1. **`descriptor()`/`AdapterDescriptor` não existem** — nunca existiram no repo. A superfície
   real da interface é `type()` + `engineContainer()` + `workspaceConvention()` +
   `acceptsPromptAsCommand()` (+ os métodos de execução), cada um nascido na fatia que precisou
   dele (ADR-0014/0016/0032) — a "detecção de capacidades" prevista no bloco ilustrado se
   materializou como métodos pequenos, não como um objeto descritor.
   Evidência: `AgentAdapter.java:15`.
2. **O `RunContext` ilustrado carrega 5 itens que o `Context` real NÃO carrega** —
   ancestralidade de goal (não viaja), skillSet (materializado ANTES do execute, no workspace),
   budgetView (o preflight vive no `HeartbeatDispatcher`), secrets (ficam no adapter/
   `EngineContainer`) e eventSink (é parâmetro separado, `execute(Context, StepSink)`).
   O espírito ("o adapter recebe tudo pronto e não conhece o core") é honrado — por
   composição no executor, não por um contexto gordo. Evidência: `AgentAdapter.java:68`,
   `AdapterRunExecutor.java:339`.
3. **"Transmitido por SSE" — divergência SUPERADA pelo PR #19** (F-N4/ADR-0040, 2026-07-12).
   No snapshot auditado o transporte era SÓ REST com polling incremental; hoje o StepTrace tem
   os DOIS transportes: `GET /runs/{id}/steps` (poll, catch-up/fallback) **e**
   `GET /runs/{id}/steps/stream` (SSE com keep-alive). O contrato original voltou a ser
   verdade — por fatia posterior, não por acaso. Evidência: `RunStepsController.java:90`.
4. **"o ÚNICO call site" tem uma exceção deliberada** — além do `AdapterRunExecutor`
   (caminho normal + retry fresh), o resume-cirúrgico do `ContextHandoffLoop.surgicalDistill`
   (ADR-0030) invoca `execute()` fora do módulo `execution`. A exceção é decidida em ADR
   própria e é terminal (um turno de destilação, sessão descartada); o invariante que o
   "1 call site" protege — nenhuma rota alternativa de execução de trabalho — segue de pé.
   Evidência: `AdapterRunExecutor.java:339,356` · `ContextHandoffLoop.java:99`.
