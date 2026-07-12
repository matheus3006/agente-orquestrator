# ADR-0039: Saída estruturada nativa — os blocos tipados e a resposta de conversa deixam a regex, viram JSON validado pelo motor

**Status:** Aceito (2026-07-12, execução da faixa R7-A da rodada 7 — `docs/handoff-grilling-motor-nativo.md`, D2; fatia F-N3, 2 etapas)

## Contexto
O laudo motor-nativo (R4-C, `docs/laudo-motor-nativo.md`, §1.8/§2-R2) provou que **todos os contratos
tipados do produto eram blocos cercados parseados por regex sobre prosa livre**: ```` ```proposta ````
(`ProposalScanner`), ```` ```handoff ````/```` ```needs-grilling ````/```` ```correcao ````/
```` ```absorcao ```` (`PersonaHandoffScanner`). Duas famílias de bug de produção nasceram disso:

1. **Shape de bloco (caso C, ADR-0036):** o Questionador inventou um shape de `proposta` e o gate
   descartou **em silêncio** — a resposta foi MAIS maquinário anti-silêncio (`ProposalDescarteNotice`,
   cinto de emissor), não schema.
2. **Last-wins do reply codex:** a narração de progresso empilhava no reply; o fix (`CodexStreamParser`)
   promoveu o último `agent_message` a RESULT — um remendo de extração, não um contrato.

O grill (R5-A, D2) fechou o destino: **migrar os 5 blocos E as respostas de conversa para saída
estruturada nativa**, validada pelo próprio motor. O racional que desfez a indecisão A×B: **o backend
não sabe de antemão qual turno carrega entregável** (o caso C nasceu num turno "de conversa"), então
schema em TODOS os turnos de persona mata as duas famílias na fonte — formato errado vira
**IMPOSSÍVEL ou barulhento, nunca descarte silencioso**.

As flags existem nas versões pinadas e foram **provadas ao vivo** antes do código (sondas no host
claude 2.1.206 / codex 0.142.5):
- **claude** `-p --output-format stream-json --verbose --json-schema '<inline>'`: o JSON validado
  volta no campo `result` do evento `result` (NÃO há campo `structured_output` separado). O
  `ClaudeStreamParser` **já** captura `result` como step RESULT → `RunFinalOutput.text()` vira o JSON.
  **Zero regressão de StepTrace; sem trocar para `--output-format json`.**
- **codex** `exec --json --output-schema <ARQUIVO>`: o JSON validado volta no último `agent_message.text`
  → o `CodexStreamParser` já o promove a RESULT (last-wins). Codex exige um ARQUIVO (não inline) e
  **força strict mode do OpenAI** (`additionalProperties:false` em todo objeto + todo campo em
  `required`; objeto solto REJEITADO com 400). `anyOf` + discriminador `tipo` const + `alvo` aninhado
  estrito = **aceito** (provado).

Snapshot base: main `a62c4e6` (piso 547 backend / 270 front). Novo piso: **568 backend** (front intocado).

## Decisão
A saída de um turno de persona vira um objeto JSON `{"resposta": string, "blocos": []}` **preso por um
JSON Schema por-persona** que o motor valida na fonte. O reply da thread passa a ser o campo `resposta`
(mata o last-wins); os scanners consomem `blocos[]` já validado (sem regex). Implantado em **2 etapas
dentro da fatia**, gradadas por RISCO.

### D2.1 — Schema por-persona (o caso C morre na fonte)
`TurnOutputSchema.forPersona(persona)` constrói `{resposta, blocos:[anyOf(blocos-da-persona)]}`, onde
a união `anyOf` contém **só os blocos que aquela persona emite**:
- **Questionador:** `handoff` · `needs-grilling` · `correcao`.
- **Planejador:** `handoff` · `needs-grilling` · `absorcao`.
- **Executor:** `proposta`(HIRE_AGENT) · `proposta`(CREATE_TEAM) · `needs-grilling`.

Consequência forte: o **caso C vira estruturalmente impossível** — o schema do Questionador nem contém
`proposta`, então o motor não permite emiti-la. O strict do codex mora inteiro no builder (todo objeto
com `additionalProperties:false` + `required` completo).

### D2.2 — Os dois cintos do backend (a gotcha carimbada)
**Gotcha:** claude **<2.1.205 ignora um schema INVÁLIDO em silêncio**, e o pino da imagem é **2.1.201**
(`EngineContainer.CLAUDE_CODE_CLI_VERSION` — o host tem 2.1.206, que dá erro duro; não reproduz o
silêncio). Por isso, dois cintos obrigatórios:
1. **Antes de enviar** — `TurnOutputSchema.requireWellFormed` valida o schema construído (JSON válido +
   objeto strict com o contrato `{resposta, blocos[]}`); um bug do builder falha ALTO no backend, nunca
   vira schema mudo no CLI. `forPersona` auto-valida a própria saída.
2. **Na volta** — `TurnOutputSchema.parse` valida o shape do retorno. Desvio (JSON inválido, campo
   faltando: schema ignorado pelo claude<2.1.205 OU motor cuspindo prosa) é MARCADO `malformado`, a
   `resposta` cai no texto CRU (o dono vê algo) e o settle faz **WARN alto + fallback ao caminho regex**
   sobre o texto cru — o cinto que já existe (D2: barulhento, nunca silencioso).

### D2.3 — Transporte das flags nos 2 motores
- `ClaudeArgv.build(..., outputSchema)` → `--json-schema '<inline>'` (convive com `stream-json` e com
  `--resume`). `ClaudeCodeAdapter` repassa `context.outputSchema()`.
- `CodexArgv.build(..., outputSchemaFile)` → `--output-schema <FILE>` (flag global, convive com o
  subcomando `resume`). `CodexAdapter` grava o schema em `.orq/turn-schema.json` do workspace (o cwd do
  processo no LOCAL) e passa o caminho.
- `AgentAdapter.Context` ganha `outputSchema` (+ construtor secundário de 12-arg = null, para as redes
  e o process archetype). O executor fia `turno.outputSchema()` no Context (main e fresh-start).

### D2.4 — Consumo estruturado (sem regex)
`ProposalScanner.scanStructured(blocos)` e `PersonaHandoffScanner.scanStructured(blocos)` despacham por
`tipo`, reusando a MESMA validação de domínio dos parsers do regex (persona conhecida, #N ≥ 1, campos
presentes, ação no enum) — só o transporte mudou. O `ConversationTurnCoordinator.settleAnswered`, para
persona migrada, extrai `resposta` (thread) e roteia `blocos[]`. `entregarPropostasAoGate` passou a
receber um `Scan` pronto (regex ou estruturado); `PersonaBatonRouter` ganhou
`afterCeoTurnAnsweredStructured`.

### D2.5 — As 2 etapas + o recuo informado
- **Etapa 1 (risco baixo):** Planejador e Executor (back-office, `resposta` = narração, sem prosa ao
  dono). Sempre ligada — valida a mecânica nos 2 motores.
- **Etapa 2 (VENDO):** Questionador (`resposta` = a fala ao dono, a prosa que o VENDO julga). Gateada
  pelo flag `orquestrador.saida-estruturada.conversa-nativa` (default `true`). **Recuo informado do D2:**
  se a prosa degradar de forma intolerável ao VENDO com o dono, o flag desliga (`false`) — a etapa 1
  continua de pé (o recuo é cirúrgico, provado por `ConversaEstruturadaRetreatTest`), decisão com
  evidência registrada, não falha da fatia. Persona nula (CEO legado) fica no regex (fora de escopo).

### D2.6 — O prompt vira schema-aware sem reescrever a semântica
`ConversationTurnCoordinator` prepende um **contrato de saída autoritativo** (`blocoContratoSaida`) que
explica `{resposta, blocos}` e **remapeia** toda instrução "emita o bloco ```` ```X ```` " das personas
para um item de `blocos` com `tipo:"X"` — sem tocar na prosa SEMÂNTICA afinada em muitos grills (os
exemplos com cerca viram doc dos CAMPOS). A disciplina de coluna-0/indentação (anti-eco do regex) é
explicitamente neutralizada (não há cercas). A dieta do resume (ADR-0038) convive: o schema governa a
SAÍDA, a dieta a ENTRADA.

## Consequências
- **Positivas:** as duas famílias de bug (shape de bloco / last-wins) morrem na fonte; um contrato só —
  blocos futuros nascem de graça (variante no `anyOf` + entrada no `scanStructured`); os cintos/anti-
  silêncio dos PRs #12–#14 (`ProposalDescarteNotice`, cinto de emissor, cerca coluna-0) ficam como rede
  durante a transição — **a remoção gradual é fatia de FAXINA própria, futura** (D2).
- **Follow-up carimbado — codex sob DOCKER:** o schema-arquivo é gravado no workspace do HOST; no LOCAL
  (v1, `ExecutionTarget` LOCAL = identidade) o processo o lê pelo cwv. Sob DOCKER o workspace é um volume
  que o FS do backend não enxerga — o arquivo precisa chegar ao volume (mount ou subtree materializado).
  O claude (schema inline) já funciona nos dois. Escopo de quando o DOCKER-target virar caminho de CEO.
- **Neutras:** o flag da etapa 2 é o único ponto de política; os scanners regex seguem vivos (dupla via
  durante a transição, por design do D2).
- **Defesa do pino (ADR-0014):** a fatia NÃO propõe bump do pino. A gotcha do 2.1.201 é coberta pelos
  dois cintos do backend (D2.2), então o valor de subir para ≥2.1.205 (erro duro em vez de silêncio) é
  marginal frente ao custo/risco de mexer no pino — **decisão do Matheus no merge** se quiser o bump; a
  fatia entrega o cinto que torna o pino atual seguro.

## Forward-refs
- **ADR-0018** (canal de proposta como saída do CEO): o `proposta` agora é campo validado, não regex —
  esta ADR emenda o transporte, o gate T3 segue intocado.
- **ADR-0031** (3 personas): as viradas de persona (`handoff`/`needs-grilling`) viram `blocos[]`
  validados; o roteamento e as restrições de emissor são idênticos.
- **ADR-0036** (caso C, anti-silêncio): o caso C vira **estruturalmente impossível** (D2.1); o
  `ProposalDescarteNotice` fica como rede da transição (remoção = faxina futura).
- **ADR-0038** (continuidade nativa): o schema (SAÍDA) convive com a dieta (ENTRADA); o schema é
  re-aplicado em todo turno (inclusive resume), a dieta reforça o contrato numa linha.
