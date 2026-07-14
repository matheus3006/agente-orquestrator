# ADR-0014: Motor por agente (adapter + modelo + esforço)

**Status:** Aceito (2026-07-02, sessão grilling `docs/handoff-grilling-motores-fechado.md`)

## Contexto
Um `Agent` não tinha campo de modelo; `provider`/`model` só viviam no `CostEvent`, desacoplados da execução. O
operador quer escolher o "motor" de cada agente (ex.: Opus 4.8/ultracode no Arquiteto; GPT-5.5/xhigh num executor)
usando os **planos de assinatura** que já tem no PC (Claude + GPT), **sem API key paga**. O `adapterType` existia
(default `process`, só o `ProcessAgentAdapter` implementado) mas não carregava modelo nem esforço; a spec visual
congelada (P.1) é silenciosa sobre seleção de motor.

## Decisão
O motor são **3 eixos** — **`adapterType`** (já existe; decide **qual plano de assinatura autentica**) + **`model`**
+ **`effort`** (2 colunas novas na `Agent`, `TEXT` **nullable**, `null` = usa o default do plano). O adapter
encapsula a **gramática do seu CLI** (regra de ouro ADR-0010) e traduz `model`/`effort` em flags:

- **`claude-code`:** `claude -p "<prompt>" --output-format stream-json --verbose --model <model>` + esforço:
  `{low|medium|high|xhigh|max}` → `--effort <effort>`; **`effort="ultracode"`** → `--settings '{"ultracode":true}'`
  (é setting session-only que manda `xhigh` + orquestra workflows — **`ultracode` NÃO é `--effort`**); `null` → omite.
  Sessão (fatia futura): `--resume <uuid>`.
- **`codex`:** `codex exec --json --model <model> -c model_reasoning_effort=<effort> "<prompt>"`; `null` → omite.
- `model`/`effort` `null` = default do plano. Recusa do CLI vira o erro do Run (fail-soft; a tipagem
  `model_refusal`/`transient_upstream` do ADR-0004 é fatia futura — hoje `RunOutcome` é `(succeeded, error)`).

**Validação em runtime, não por enum (Q2):** `model`/`effort` são String livre; a borda só valida não-branco. Quem
sabe se um id (`gpt-5.5`, `claude-opus-4-8`, aliases rotativos) é válido é o **CLI**, não o Postgres — enum no
schema exigiria migration a cada release. O **catálogo** (abaixo) **sugere**, não **restringe**.

**Catálogo (`engine_catalog`, V13):** tabela curada (seed), global (sem `company_id`, como o catálogo de Skills —
Camada 1), servida por `GET /api/companies/{id}/engines`. Cada linha = `{adapter_type, model, effort,
context_window, label, enabled}`; a UI mostra o `label` legível (`Claude · Opus 4.8 · ultra`) que preenche os 3
eixos. Modelo novo = `INSERT` (sem migration nem redeploy). `context_window` alimenta o handoff a 50-60% (ADR-0015).

**Seam (ADR-0004, "zero mudança no core"):** o `AgentAdapter.Context` ganha `model`/`effort`; o `AdapterRunExecutor`
só passa a injetá-los e a rotear os `CostEvent` que os adapters ricos emitem (com tokens reais e `costCents=0` —
ADR-0006/Q7). `ClaudeCodeAdapter`/`CodexAdapter` são novas impls; `process` segue como fallback/demo.

**v1 = LOCAL + dois adapters em sequência (Claude → Codex).** O motor roda `LocalExecutionTarget`, herdando o login
do host (o `claude`/`codex` logados respondem). **v1 NÃO isola** (assume explícito — badge/aviso na UI): agentes
compartilham disco, credenciais, rede e recursos do host. Isolamento de plano por container (Docker) é fatia futura
**F.8 / ADR-0016**.

## Consequências
- Positivas: um agente pode rodar Opus (plano Claude) e outro GPT (plano ChatGPT) na mesma company, sem gestão de
  segredo, provando o diferencial de heterogeneidade de motores. Catálogo evolui por `INSERT`. Tokens
  instrumentados dão observabilidade + insumo do handoff (ADR-0015).
- Negativas / riscos aceitos: (a) **sem isolamento** no v1 (sacrifica temporariamente o diferencial #3 — adiado,
  não esquecido); (b) **risco `--bare`**: vira default de `claude -p` no futuro e **exige API key** → **fixar a
  versão do CLI `claude`** e monitorar o changelog (a tese "sem API key" depende disso do lado Claude);
  (c) **rate-limits de plano** (limites semanais Claude / limites Codex) não são gerenciados no v1 — rodar muitos
  agentes 24/7 bate o teto (throttle, não proibição).

## Alternativas rejeitadas
- **Esforço dentro do modelo (string empacotada `"opus-4.8-ultra"`):** quebra porque `ultracode` ≠ `--effort` e cada
  CLI põe esforço em lugar diferente; forçaria todo consumidor a parsear e ramificar.
- **"Engine profile" opaco (1 eixo):** criaria entidade + CRUD + UI e exigiria migrar/aposentar o `adapterType`
  existente — overkill.
- **Enum de modelo no schema:** migration a cada release; falso-negativo se o CLI aceitaria o modelo.
- **Probe/discovery de modelos:** frágil na assinatura (sem `--list-models` confiável, varia por máquina).
- **Estimar custo em dólares (tokens × tabela):** ficção que pausaria agentes por dinheiro inexistente (assinatura).

## Relaciona
ADR-0002 (session — `--resume` bruto vira secundário ao handoff), ADR-0004 (contrato `execute()` ganha
`model`/`effort` + `costEvents`), ADR-0006 (budget — hard-stop de dólares inócuo na assinatura, acumula tokens),
ADR-0010 (o adapter encapsula a gramática do CLI), ADR-0015 (handoff a 50-60% consome a instrumentação de tokens),
ADR-0016 (Docker/isolamento — reservado, fatia futura F.8).

## Nota de reconciliação (2026-07-14 — fecho documental; achados R4-C, R6 e F-AT2)

- **A defesa do pino foi CONFIRMADA pela doc oficial** (achado do laudo motor-nativo, R4-C,
  2026-07-11): a documentação do claude declara que **`--bare` "vai virar default do `-p`"** —
  o risco (b) das Consequências desta ADR é real, não hipotético. O pino de versão
  (`EngineContainerPinTest`, ADR-0016 §1) segue sendo a defesa; bump é sempre decisão
  consciente com racional no PR.
- **Candidato de bump REGISTRADO, não decidido — item da lista H** (`docs/lista-h-fecho-v1.md`):
  o host roda claude **2.1.206** enquanto o pino da imagem é **2.1.201** (achado da rodada 6).
  A F-N3 (ADR-0039) MANTEVE o pino deliberadamente: dois cintos no backend
  (`requireWellFormed` na ida + malformado→WARN+fallback regex na volta) cobrem a gotcha do
  `--json-schema` silencioso <2.1.205. A decisão de bump é do dono.
- **Gotcha codex `--model` explícito** (achado do /verify da F-AT2, 2026-07-13): quando o
  `config.toml` do host está pinado num modelo que o CLI 0.142.5 não aceita, o `codex exec` é
  REJEITADO — runs de codex em prova/VENDO devem passar `--model` explícito em vez de herdar o
  default do host. Registrado aqui porque é gramática de CLI (o território desta ADR); vale
  para roteiros de verificação e para o operador.
