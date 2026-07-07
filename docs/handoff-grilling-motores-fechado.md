# Handoff — Grilling "Motores" (seleção de motor por agente) — DECISÕES FECHADAS

> Sessão `grill-me`, 2026-07-02. Fechou as **8 decisões abertas** de `docs/handoff-grilling-motores.md`
> (frente nova **F.7 "Motores"**: escolher o *motor* — adapter + modelo + esforço — de cada Agent usando os
> **planos de assinatura** do operador (Claude + GPT), **sem API key paga**). Duas rodadas de `deep-research`
> aterraram os fatos externos (flags/auth dos CLIs; e a validação do esquema Docker — §D). Lente
> `codebase-design`/regra-de-ouro (ADR-0010) aplicada. **Nenhum código escrito — só decisões.**
> Fonte de entrada: `docs/handoff-grilling-motores.md`. Este doc é a fonte para a sessão de implementação.
>
> **Resultado-chave:** *motor* = **2 colunas novas na `Agent`** (`model`, `effort`, String livre nullable) +
> o **`adapterType` que já existe** vira o eixo que **decide qual plano autentica**. **TEM migration** (≠ 7c):
> `V12` (colunas) + `V13` (catálogo). v1 = **LOCAL** + **dois adapters ricos** em sequência (Claude → Codex).
> Nasceu uma **nova regra de ouro** (handoff de contexto a 50-60% → ADR-0015).

---

## 0. Ponto de partida (fatos ATERRADOS — não re-derivar)

- **Estado:** backend **107/107**, frontend **122/122**; as 5 superfícies do board (F.0→F.6) DONE. Migrations
  em **V11** (`V11__review.sql`); próximas livres **V12/V13**. Último ADR **0013**; próximo **0014**.
- **Correção de numeração do handoff de entrada:** o híbrido de Session é o **ADR-0002**
  (`0002-persistencia-session-adapter.md`), **não** "ADR-0003". O contrato `execute()` é o **ADR-0004**.
- **Não existe campo de modelo.** `Agent` = `adapterType` (String, default `"process"` — só `ProcessAgentAdapter`
  implementado), `runtimeCommand` (String, **não volta na resposta**), `executionTarget` (`LOCAL|DOCKER`).
  Evid.: `org/agent/Agent.java`; `AgentController.java:48` (`CreateAgentRequest`), `:52` (`AgentResponse` sem
  `runtimeCommand`).
- **O seam já existe.** `AgentAdapter.Context` já carrega `command, env, target, image, volumeName, priorSession`
  (`execution/adapter/AgentAdapter.java:24`). Hoje `AdapterRunExecutor` injeta `env=Map.of()`, `image="alpine:3"`,
  `priorSession=null` (`AdapterRunExecutor.java:91`). Adicionar eixos de motor é **extensão do Context** — o
  ADR-0004 promete *"zero mudança no core"*.
- **String livre validada em runtime** é o padrão da casa: `AdapterRegistry.forType()` lança se desconhecido;
  `ExecutionTargets.parse()` faz fallback `LOCAL`. Coluna nova na `agent` é barata (V4 provou).
- **`CostEvent` já tem `provider`/`model`/`tokensInput`/`tokensOutput`** (`governance/budget/CostEvent.java`),
  alimentado por `POST /cost-events` — hoje desacoplado do agente (o adapter não emite).
- **Continuidade de arquivos já existe:** `WorkspaceResolver` dá volume nomeado durável por
  `(company, agent, issue)` entre Runs. **Continuidade de contexto (Session, ADR-0002) NÃO** — `priorSession`
  é sempre `null`.
- **Externos aterrados por `deep-research` (rodada 1, fontes primárias code.claude.com / developers.openai.com):**
  - `claude`: modelo por `--model <alias|id>` (`opus|sonnet|haiku|fable` ou `claude-opus-4-8`, …); esforço por
    `--effort {low|medium|high|xhigh|max}`; **`ultracode` NÃO é effort** — é setting session-only que manda
    `xhigh` + orquestra workflows, ativado por `"ultracode": true` via `--settings`. Headless: `-p`
    `--output-format stream-json --verbose`. Sessão: `--resume <uuid>`. Assinatura headless funciona hoje
    (`claude setup-token`), **mas** `--bare` (pula OAuth/keychain, exige API key) **vira default de `-p` no
    futuro** → risco de prazo.
  - `codex`: headless `codex exec --json` (JSONL: `thread.started`/`turn.completed`/`item.*`); modelo `--model`;
    esforço `model_reasoning_effort` (ex.: `xhigh`); **profiles nomeados** nativos (`--profile`); sessão
    `codex exec resume <SESSION_ID>`; `codex exec` reusa o login salvo (plano ChatGPT) por padrão.

---

## A) Decisões fechadas (por pergunta)

### Q1 — Eixos do motor — **CLOSED = 3 eixos, 2 colunas novas**
`adapterType` (`claude-code | codex | process`) **decide qual PLANO autentica**; nascem **`model`** e **`effort`**
como colunas na `Agent`. A UI apresenta o trio como um único "motor". **Porquê (vai pro ADR):** os CLIs colocam
esforço em **lugares diferentes** — `claude` usa `--effort` (ou `--settings` p/ `ultracode`), `codex` usa
`model_reasoning_effort`; uma **string empacotada** (`"opus-4.8-ultra"`) forçaria todo consumidor a parsear e
ainda ramificar. Um **"engine profile" opaco** (1 eixo) criaria entidade + CRUD + UI e exigiria migrar/aposentar
o `adapterType` existente — overkill. Três eixos, dois campos, seguem o padrão de `adapterType`/`executionTarget`.

### Q2 — Tipo/validação de `model`/`effort` — **CLOSED = String livre nullable, validada no adapter**
`model`/`effort` = `TEXT` **nullable** (`null` = não passa a flag → usa o **default do plano**). Borda valida só
não-branco. **Validação real = adapter → CLI**; recusa vira o **erro tipado `model_refusal`** do ADR-0004.
**Porquê:** a doc lista **9 IDs válidos que mudam com o tempo** + aliases rotativos (`opus`→Opus 4.8 hoje) — quem
sabe se `gpt-5.5` é válido é o **CLI**, não o Postgres. Enum no schema = migration a cada release. **Sem** validar
contra o catálogo (Q5): o catálogo **sugere**, não **restringe** — bloquear no banco um modelo que o CLI aceitaria
é falso-negativo.

### Q3 — Sessão/continuidade — **CLOSED = fresh no v1 + instrumenta tokens; handoff é a continuidade real**
v1 roda **fresh** (`priorSession=null` continua); o adapter **captura** o handle nativo (→ `Result.session`) mas
**não persiste** (fiação pronta, feature desligada). Continuidade de **arquivos** já existe (Workspace).
**Nova regra de ouro (→ ADR-0015):** nunca usar mais que **50-60% de uma sessão**; ao cruzar isso (ou numa task
que estourou) o agente escreve um **handoff** (bilhete de passagem de turno) e uma **sessão nova** assume lendo o
bilhete. Isso torna o **`--resume` bruto indesejável** (ele deixa a janela **crescer** até degradar — o "bug
esquisito" que a regra evita). **v1 só INSTRUMENTA tokens** (o adapter lê `usage` do stream) — serve ao custo (Q7)
**e** à futura detecção dos 50-60%. O **loop de handoff** (detectar limite → mandar destilar → iniciar turno novo)
= **fatia futura**. **Porquê:** continuidade de contexto é **ortogonal** a "escolher o motor"; puxar o ADR-0002
inteiro (self-healing, matching de cwd/prompt) incharia a fatia.

### Q4 — Auth × execução × isolamento — **CLOSED = v1 LOCAL-only (não isola, explícito); Docker = fatia futura**
O motor roda **LOCAL**, herdando o login do host (`LocalExecutionTarget` herda o env; no Mac, `claude`/`codex`
logados no host respondem). Prova o conceito (Opus no Lyra via plano Claude; GPT no Rex via plano ChatGPT) **sem
gestão de segredo**. O v1 **assume explicitamente que NÃO isola** (badge/aviso na UI + registrado aqui).
**Porquê / o que foi aterrado:**
- **LOCAL sacrifica o diferencial #3 (isolamento):** os agentes compartilham disco, credenciais do host, rede e
  recursos da máquina. Aceitável para provar o motor; inaceitável como estado final.
- **O Keychain do Mac NÃO é bloqueio.** Docker Desktop no Mac já roda uma **VM Linux**; o problema do "Claude não
  montável" é sobre **onde o login nasce**: login no `claude` nativo do macOS → Keychain (fora da VM, não
  montável); login **dentro** do container Linux → `~/.claude/.credentials.json` (arquivo) → **volume nomeado,
  montável e isolável**. Logo Docker é **viável**; é **escopo** (obra de credencial), não bloqueio.
- **Nenhuma porta fecha:** `executionTarget` já é coluna, `DockerExecutionTarget` já monta `docker run` (`-v`,
  `-e`, imagem). Falta só a **injeção de credencial**. Upgrade LOCAL→DOCKER é **aditivo**.
- **RISCO registrado:** `--bare` vira default de `claude -p` no futuro e **exige API key** → **fixar a versão do
  CLI** e monitorar. Rate-limits de plano (limites semanais do Claude; limites do Codex) **não** são gerenciados
  no v1 → §D.

### Q5 — Catálogo de motores — **CLOSED = tabela curada (seed), servida por endpoint**
Tabela **`engine_catalog`** semeada; cada linha = `{adapter, model, effort, contextWindow, label, enabled}`.
Servida por **`GET /api/engines`** (a UI consome). Modelo novo = **`INSERT`** (sem migration nem redeploy). É
**sugestão** pra UI, **não constraint** (adapter valida — Q2). **Hospeda a janela de contexto** (denominador dos
50-60% do handoff). **Porquê:** probe/discovery é frágil (sem `--list-models` confiável na assinatura, varia por
máquina); hardcoded recompila a cada release. Análogo ao **KnowAll** (catálogo servido).

### Q6 — UI do seletor — **CLOSED = dropdown único de combinações curadas (hire + detail)**
Um campo **"Motor"**; itens = linhas do `engine_catalog`, cada uma já um label legível
(`Claude · Opus 4.8 · ultra`, `GPT · 5.5 · xhigh`) que **preenche os 3 campos por baixo**. Entra no **hire dialog**
(contratar) **e** no **detail/kv-panel** (promover depois). A linha `runtime` do roster
(hoje `adapterType · executionTarget`) passa a mostrar o **label do motor + target**. **Porquê:** casa com o modelo
mental ("motor = uma escolha"), evita combinações inválidas, usa o catálogo como fonte. **Divergência justificada
da P.1** (spec congelada é silenciosa → adição nova): as **4 skills de design** (`/bencium-impact-designer`,
`/bencium-innovative-ux-designer`, `/emil-design-eng`, `/ui-ux-pro-max`) são **reconvocadas na construção da UI**,
não nesta sessão.

### Q7 — Custo/budget — **CLOSED = CostEvent com provider/model/tokens, `costCents=0`; budget-dólares inócuo**
O adapter emite `CostEvent` por Run com `provider`+`model`+`tokensInput`+`tokensOutput` **reais** e **`costCents=0`**.
**NÃO** ativa Costs UI nem acúmulo de dólares (mantém o grilling-frontend). **Porquê:** assinatura = **custo
marginal ~$0** → o hard-stop de dólares do ADR-0006 **nunca dispara** (`0 ≥ limite`). Registrar tokens dá
**observabilidade** (consumo por agente/modelo) + insumo do handoff + fiação pronta **se** um dia entrar API paga
(aí `costCents>0` e o hard-stop volta a valer). **A tese "budget-dólares é o governador" NÃO se aplica à
assinatura** — o governador real é **rate-limit + contexto** (fatia futura). Estimar dólares por `tokens×tabela`
foi **rejeitado**: é ficção que pausaria agentes por dinheiro inexistente.

### Q8 — Sequenciamento & v1 mínimo — **CLOSED = backend-first; 2 adapters em sequência; F.7 antes da consistência**
**Backend-first** (colunas, Context estendido, adapters, catálogo/endpoint, instrumentação de tokens precedem o
dropdown). **Dois adapters no v1, em sequência: `ClaudeCodeAdapter` → `CodexAdapter`** — o primeiro estabelece o
padrão de adapter rico (parsing de stream, tokens, StepTrace semântico, erro tipado), o segundo reusa. **F.7 antes
da passada de consistência** (pós-F.6). **Porquê:** o cenário-alvo (Opus no Lyra + GPT no Rex) **exige os dois
planos coexistindo** — um só adapter não prova; sequência controla o risco de território novo; consistência depois
evita varrer as telas duas vezes. `process` continua como fallback/demo.

---

## B) Diffs de ADR/CONTEXT a aplicar (ready-to-apply)

### ADR-0002 (emendar — "Revisão 2026-07-02 (grilling motores)")
- v1 de motor **não ativa** o `--resume` nativo: `priorSession` segue `null`, cada Run é fresh. O adapter **captura**
  o handle nativo (`Result.session`) mas **não o persiste** ainda.
- A estratégia de continuidade é **reorientada** pela regra de ouro do handoff (ADR-0015): a continuidade preferida
  é **destilação (handoff a 50-60%)**, não recarga bruta de sessão. O "handle em DB + estado no volume" do ADR-0002
  continua válido como forma-alvo, mas é **secundário** ao handoff — e fica para a fatia de sessão dedicada.

### ADR-0004 (emendar — "Revisão 2026-07-02")
- **`RunContext`/`Context` ganha `model` e `effort`** (String, nullable); o adapter os traduz nas flags do seu CLI
  (dicionário no ADR-0014). O `AdapterDescriptor.descriptor()` já previa *"detecção de modelo"* — agora concretiza.
- **`RunResult`/`Result.costEvents`** passa a ser emitido pelos adapters ricos com `provider`+`model`+tokens e
  `costCents=0` (Q7).
- **StepTrace:** o parser de stdout dos adapters ricos passa a (a) traduzir eventos JSON do CLI em steps
  **semânticos** (`type` = evento nativo; linha não-JSON → `STDOUT`, fail-soft) e (b) **extrair `usage`/tokens**.
- Confirma **"zero mudança no core"**: `ClaudeCodeAdapter`/`CodexAdapter` são novas impls; `AdapterRunExecutor` só
  passa a injetar `model`/`effort` e a rotear `costEvents`.

### ADR-0006 (emendar — "Revisão 2026-07-02")
- No **modelo de assinatura**, o hard-stop de **dólares** é **inócuo** (custo marginal ~$0). Mantido como está
  (não acumula); volta a valer só com API paga.
- O `CostEvent` passa a **acumular tokens** (não dólares) para observabilidade. O **governador real** da assinatura
  (**rate-limit do plano** + **uso de contexto/handoff**) é reconhecido como **fatia futura**, não construído aqui.

### NOVO ADR-0014 — Motor por agente (adapter + modelo + esforço)
> **Contexto:** um Agent não tinha campo de modelo; `provider`/`model` só viviam no `CostEvent`, desacoplados da
> execução. O operador quer escolher o "motor" de cada agente (ex.: Opus 4.8/ultracode no Arquiteto; GPT-5.5/xhigh
> num executor) usando **planos de assinatura**, sem API key paga.
> **Decisão:** o motor são **3 eixos** — `adapterType` (já existe; decide **qual plano autentica**) + **`model`** +
> **`effort`** (2 colunas novas na `Agent`, String livre nullable, validadas em runtime pelo adapter, não por enum).
> O adapter encapsula a **gramática do seu CLI** (regra de ouro ADR-0010):
> - **`claude-code`:** `claude -p "<prompt>" --output-format stream-json --verbose --model <model>` + esforço:
>   `{low|medium|high|xhigh|max}` → `--effort <effort>`; **`ultracode`** → `--settings '{"ultracode":true}'`;
>   `null` → omite. Sessão (futuro): `--resume <uuid>`.
> - **`codex`:** `codex exec --json --model <model> -c model_reasoning_effort=<effort> "<prompt>"`; `null` → omite.
> - `model`/`effort` `null` = usa o **default do plano**. Recusa do CLI → `model_refusal` (ADR-0004).
> **Catálogo:** `engine_catalog` (seed) servido por `GET /api/engines` — **sugere** as combinações (com janela de
> contexto e label), **não restringe** o dado. **v1 = LOCAL** (não isola — §Q4) + **dois adapters** (Claude→Codex).
> **Custo:** adapter emite `CostEvent` com tokens e `costCents=0`.
> **Alternativas rejeitadas:** esforço dentro do modelo (string empacotada — quebra: `ultracode`≠`--effort`);
> engine-profile opaco (entidade+CRUD+migrar `adapterType` — overkill); enum de modelo no schema (migration a cada
> release); probe de modelos (frágil na assinatura); estimar custo em dólares (ficção).

### NOVO ADR-0015 — Regra de ouro: handoff de contexto a 50-60%
> **Contexto:** encher a janela de contexto de uma sessão degrada a qualidade e gera bugs difíceis de reproduzir.
> **Decisão (invariante de qualidade, transversal ao produto):** nenhum agente usa mais que **~50-60%** da janela
> de uma sessão. Ao cruzar o limite (ou numa task que estourou), o agente **destila** o estado num **handoff**
> (documento de passagem de turno, gravado no Workspace que já persiste) e uma **sessão nova** assume lendo o
> handoff — em vez de recarregar a conversa inteira (`--resume` bruto), que só faz a janela crescer.
> **v1:** o adapter rico **instrumenta o uso de tokens** (do stream); os 50-60% se calculam contra a **janela de
> contexto** do `engine_catalog`. **O loop completo** (detectar limite → disparar destilação → iniciar turno novo
> lendo o bilhete → retomar o plano) é **fatia futura**. **Relaciona:** ADR-0014 (instrumentação), ADR-0002
> (torna o `--resume` bruto secundário).

### ADR-0016 — Isolamento de plano por container (Docker) — **RESERVADO (fatia futura F.8)**
A escrever quando a F.8 for construída; esquema em §D, **pendente da validação `deep-research` (§D anexo)**.

### CONTEXT.md (glossário — termos a adicionar/refinar)
- **Motor / Engine** (NOVO): o trio `adapterType` + `model` + `effort` que define o "cérebro" de um Agent. O
  `adapterType` decide **qual plano de assinatura autentica**; `model`/`effort` são **atributos do Agent** (default
  persistente para todas as suas Issues). Apresentado na UI como uma escolha única.
- **Adapter** (refinar a entrada existente): além de expor `execute(ctx)`, **traduz `model`/`effort` nas flags do
  seu CLI**; `claude-code`/`codex` são adapters ricos (parseiam stream, instrumentam tokens). *Evitar:* "o modelo
  escolhe o plano" — **o adapter (qual CLI roda) escolhe o plano**.
- **Session (do adapter)** (refinar): a continuidade preferida é **handoff** (destilação a 50-60%), não recarga
  bruta; o handle nativo é **capturado mas não persistido no v1**.
- **Handoff de contexto** (NOVO): regra de ouro — a ~50-60% de uso da sessão, o agente destila estado num doc e
  uma sessão nova assume. Invariante de qualidade (ADR-0015).
- **Catálogo de motores / `engine_catalog`** (NOVO): tabela curada (seed) servida por endpoint; lista combinações
  adapter+model+effort com janela de contexto; **sugestão** pra UI, não constraint.
- **ExecutionTarget** (refinar): v1 de motor é **LOCAL-only** (não isola); **DOCKER** (isolamento de plano por
  container) é fatia futura.

---

## C) Impactos de schema/migration — ORDENADOS

### ⭐ HEADLINE: **TEM migration** (≠ 7c). Duas, aditivas e baratas.
- **`V12__agent_engine.sql`:** `alter table agent add column model text; alter table agent add column effort text;`
  (ambas nullable — sem default, `null` = default do plano).
- **`V13__engine_catalog.sql`:** `create table engine_catalog (id uuid pk, adapter_type text not null, model text,
  effort text, context_window int, label text not null, enabled boolean not null default true, created_at
  timestamptz default now());` + **seed** das combinações conhecidas (Claude·Opus 4.8·ultra; Claude·Sonnet 5·high;
  Codex·GPT-5.5·xhigh; …). Sem `company_id` (catálogo é global, como o de Skills — Camada 1).

### Código ORDENADO — Backend (fase 1, backend-first)
1. **`Agent.java`:** add `model`/`effort` (String, nullable) + getters; estender `configureRuntime()` (override
   parcial, preserva null como "default do plano").
2. **`AgentController`:** `CreateAgentRequest` (+`model`,`effort` opcionais) e `AgentResponse` (+`model`,`effort`);
   confirmar/expor endpoint de **update** do agente (promover motor no detail — Q6). `runtimeCommand` segue fora da
   resposta.
3. **`AgentAdapter.Context`:** add `model`, `effort`. **`AdapterRunExecutor`:** injetar `agent.getModel()` /
   `agent.getEffort()` no Context (ao lado de `command`/`target`).
4. **`ClaudeCodeAdapter` (novo, `type()="claude-code"`):** monta o argv `claude` (dicionário de flags do ADR-0014);
   `ExecutionTarget.Spec` LOCAL; **parseia `--output-format stream-json`** → steps semânticos (`steps.emit(<evento>,
   detalhe)`; não-JSON → `"STDOUT"`, fail-soft) **e extrai `usage`**; captura `session_id` → `Result.session`;
   mapeia erro → `model_refusal` / `transient_upstream`; emite `CostEvent(provider="anthropic", model, tokens,
   costCents=0)`. **Marco E2E do padrão de adapter rico.**
5. **`CodexAdapter` (novo, `type()="codex"`):** idem, gramática `codex exec --json` (eventos `thread.started`/
   `turn.completed`/`item.*`); `provider="openai"`. **Reusa o padrão do passo 4.**
6. **`AdapterRegistry`:** registrar `claude-code` e `codex` (Spring beans, como `process`).
7. **`engine_catalog`:** entidade + repo + `GET /api/companies/{id}/engines` (ou global `/api/engines`) board-only,
   lendo o seed.
8. **`CostEvent`:** adicionar getters faltantes (`runId`/`provider`/`model`/`tokens`) se o adapter/serviço
   precisar; garantir que `recordCost` aceita `costCents=0` (aceita — não cruza limite).
9. **Testes backend:** Context carrega model/effort; ClaudeCodeAdapter monta flags certas (incl. `ultracode`→
   `--settings`; `null`→omite); parser stream-json → steps + tokens; CodexAdapter idem; adapter desconhecido →
   `IllegalStateException` (inalterado); CostEvent com costCents=0 não dispara incidente; catálogo servido.

### Código ORDENADO — Frontend (fase 2, consome a fase 1)
10. **`lib/api.ts`:** `AgentSummary` + `HireAgentInput` ganham `model?`/`effort?`; novo tipo `Engine`
    (`{adapterType, model, effort, contextWindow, label}`) + fetch de `/api/engines`.
11. **`hire-agent-dialog.tsx`:** substituir o hardcode `adapterType:"process"` por **dropdown "Motor"** alimentado
    por `/api/engines`; selecionar item preenche `adapterType`+`model`+`effort`.
12. **`agents/[agentId]/page.tsx`:** motor **editável** no kv-panel (promover); `runtime` (linha 123) passa a
    mostrar o **label do motor** + target.
13. **`agents/page.tsx`:** a `roster-cell` (linha 118) mostra o label do motor + `executionTarget`.
14. **Reconvocar as 4 skills de design** para a UI nova (movimento/estados — diretriz do grilling-frontend).
15. **Testes frontend:** dropdown popula do endpoint; hire envia os 3 campos; detail edita; roster renderiza label.

### Ordem recomendada (fatias finas, TDD + revisão adversarial)
`V12/V13` → `Agent`+controller → **`ClaudeCodeAdapter` E2E** (o padrão) → `CodexAdapter` (reuso) → `engine_catalog`
+ endpoint → **frontend** (api → hire → detail → roster). Backend inteiro **antes** do frontend. **F.7 antes da
passada de consistência** pós-F.6.

---

## D) Constraints / limitações do v1 + fatias futuras

### Limites aceitos no v1 (registrar; não são bugs)
- **v1 NÃO isola** (LOCAL): agentes compartilham disco/credenciais/rede/recursos do host. **Explícito na UI**
  (badge "sem isolamento") **e** aqui. Isolamento é o diferencial #3 — **adiado, não esquecido**.
- **v1 fresh-session:** sem memória de conversa entre Issues; continuidade é só de **arquivos** (Workspace). A
  "memória" real vem por **handoff** (fatia futura).
- **Sem Costs UI; ledger acumula tokens, não dólares;** hard-stop de dólares inócuo na assinatura.
- **RISCO `--bare`:** vira default de `claude -p` no futuro e exige API key → **fixar a versão do CLI `claude`** e
  monitorar o changelog. A tese "sem API key" depende disso do lado Claude.
- **Rate-limits de plano não gerenciados:** rodar vários agentes no mesmo plano pode bater limite semanal
  (Claude) / limite do Codex — **não** há awareness no v1.

### Fatias futuras reservadas (fora do v1)
- **F.8 — Docker / isolamento de plano por container** (vira **ADR-0016**). Esquema **VALIDADO** (deep-research,
  §anexo; só o ponto ToS/rate-limits ficou pendente de verificação adversarial):
  1. **Onboarding de credencial:** container de setup onde se faz `claude login` / `codex login` **uma vez**
     (device-code, "abra a URL e cole o código"), gravando em **volumes nomeados** (`orq_claude_creds`,
     `orq_codex_creds`) — o login nasce **no lado Linux** (contorna o Keychain do Mac).
  2. **Montagem seletiva por-adapter:** o container do Lyra monta só `orq_claude_creds`; o do Rex só
     `orq_codex_creds` — **é aqui que o isolamento de credencial acontece**.
  3. **Imagens por-adapter** com os CLIs (`npm i -g @anthropic-ai/claude-code` / `@openai/codex`).
  4. **Expiração/refresh:** tokens OAuth expiram → volume precisa refrescar/re-logar periodicamente.
- **Loop de handoff a 50-60%** (constrói o ADR-0015): consome a **instrumentação de tokens** do v1 + a **janela de
  contexto** do `engine_catalog`.
- **Rate-limit awareness** (o governador real da assinatura): detectar `rate_limit` no stream, throttle/fila.
- **`--resume` nativo / Session ADR-0002 completa:** só se um dia quiser memória de conversa bruta — a regra do
  handoff (ADR-0015) a torna secundária.

### Anexo — validação `deep-research` do esquema Docker (§F.8) — CONCLUÍDA 2026-07-02
> Pontos 1-4 via `deep-research` (112 claims → 25 verificadas → **19 confirmadas 3-0, 3 refutadas**), inclusive
> **padrão oficial** (devcontainer da Anthropic) e **dois orquestradores reais** (`agentor`, `agent-of-empires`)
> que já fazem exatamente isto. **Ponto 5 (ToS/rate-limits)** fechado depois por **leitura direta das fontes
> primárias** (política oficial Anthropic + rate limits TechCrunch + contexto VentureBeat/OpenAI) — ver §5 abaixo.

| # | Ponto | Veredito | Evidência-chave (fonte primária) |
|---|-------|----------|----------------------------------|
| 1 | **Onboarding headless** | ✅ **VÁLIDO** | Claude: paste-code oficial no prompt `Paste code here if prompted` (WSL2/SSH/containers) **ou** `claude setup-token` → token de **1 ano** injetado como `CLAUDE_CODE_OAUTH_TOKEN` (`code.claude.com/docs/en/authentication`, `/devcontainer`). Codex: `codex login --device-auth` (`developers.openai.com/codex/auth`). Projeto real: `agentor`. |
| 2 | **Persistência em volume** | ✅ **VÁLIDO** (padrão oficial) | Devcontainer da Anthropic monta named volume em `~/.claude` (`source=claude-code-config,target=/home/node/.claude,type=volume`) — sem isso o home some no rebuild. Paths Linux: **`~/.claude/.credentials.json`** (mode 0600; ou `CLAUDE_CONFIG_DIR`) e **`~/.codex/auth.json`** (com `cli_auth_credentials_store="file"` p/ forçar arquivo, não keyring). |
| 3 | **Isolamento por montagem seletiva** | ⚠️ **VÁLIDO-COM-RESSALVA** | `agent-of-empires` filtra mounts por `tool_name` ("só a config da tool ativa é montada; outros agentes não recebem o cred mount"); devcontainer usa `${devcontainerId}` p/ volume por-container. **Ressalva:** isola por-diretório-de-config, **não é fronteira de segurança rígida**; containers do MESMO agente compartilham um sandbox r/w. |
| 4 | **Expiração / refresh** | ⚠️ **VÁLIDO-COM-RESSALVA CRÍTICA** | `setup-token` dura **1 ano**; Codex (ChatGPT) **auto-refresh** antes de expirar. **RESSALVA (decisiva):** *"OAuth refresh tokens rotate on use — sempre logue DENTRO do worker, nunca copie do host, ou as duas cópias dessincronizam e quebram os dois lados"* (`agentor`). → **confirma a decisão da Q4: o login DEVE nascer no container, não ser copiado do Mac.** |
| 5 | **ToS & rate-limits** | ⚠️/❌ **RESOLVIDO** (fonte primária) — ver §5 detalhado abaixo | **Uso pessoal da própria assinatura** self-hosted/headless = **permitido**; **serviço a terceiros roteando assinaturas = PROIBIDO** (exige API key); rate-limits semanais miram exatamente uso 24/7. Política **volátil** (ban→reinstatement→pausa em 2026). |

**Refinamentos ao esquema F.8 (aplicar quando construir):**
- **Montar `~/.claude.json` (nível home) além de `~/.claude/`:** o onboarding-state do Claude vive em `~/.claude.json`, *fora* do dir — montar só o dir não leva o estado de onboarding (confirmado 3-0).
- **Onboarding via login-no-container é obrigatório (não `docker cp` do host):** a rotação de refresh token quebra cópias. Para Codex há `docker cp auth.json` documentado pela OpenAI (2-0), mas mesmo assim preferir login nascido no container pela rotação.
- **Claudes têm 2 caminhos:** login interativo device-code no container **ou** `CLAUDE_CODE_OAUTH_TOKEN` (setup-token, 1 ano) como env — o segundo é mais simples p/ automação e evita o browser.

**Refutadas (não usar como base):** "Codex só tem device-auth + api-key, sem browser-callback" (1-2 — device-auth+paste-code já cobrem headless); detalhe "refresh se `last_refresh` > ~8 dias" (0-3 — não afirmar o número).

### §5 detalhado — ToS & rate-limits (fonte primária; importa pro MODELO DE NEGÓCIO)

**Fronteira de autorização — Anthropic (fonte primária `code.claude.com/docs/en/legal-and-compliance`, verbatim):**
- ✅ **Uso pessoal da própria assinatura é permitido:** *"OAuth authentication is intended exclusively for
  purchasers of Claude Free, Pro, Max, Team, and Enterprise subscription plans and is designed to support
  **ordinary use of Claude Code and other native Anthropic applications**."* Rodar o `claude` CLI headless com o
  **seu** login é método sancionado.
- ❌ **Serviço a terceiros é proibido:** *"Anthropic does **not permit third-party developers to offer Claude.ai
  login or to route requests through Free, Pro, or Max plan credentials on behalf of their users**."* Quem constrói
  produto/serviço (incl. Agent SDK) *"should use API key authentication"*. E: *"Anthropic reserves the right to
  take measures to enforce these restrictions... **without prior notice**."*
- ⚠️ **A premissa é individual:** *"Advertised usage limits for Pro and Max plans assume **ordinary, individual
  usage**."* Muitos agentes paralelos numa assinatura tensiona "individual".

**Rate-limits (TechCrunch, vigentes desde 28/ago/2025) — o gargalo prático:** limites **semanais** além dos de 5h.
Pro ($20): **40-80h Sonnet/sem**. Max ($100): 140-280h Sonnet + **15-35h Opus/sem**. Max ($200): 240-480h Sonnet +
**24-40h Opus/sem**. Alvo declarado: quem roda *"continuously in the background, 24/7"* e quem compartilha/revende
contas. **Um orquestrador que roda muitos agentes o tempo todo bate o teto semanal — não é proibição, é throttle.**

**Codex/OpenAI (Help Center + Pricing):** limites por **mensagens em janela de 5h** (Plus ~15-80 msgs GPT-5.5/5h;
Pro = 5×), + limites semanais; créditos extras compráveis. OpenAI **recomenda API key** para *"CI/CD jobs,
programmatic Codex CLI workflows, SDK usage, and automation"* — mas **permite** rodar como conta ChatGPT (o
`ci-cd-auth` diz "use only if you specifically need to run as your Codex account"). Menos proibitivo que a Anthropic,
mesma direção.

**⚠️ Política volátil (fixar a data):** abr/2026 Anthropic **baniu** agentes de terceiros sobre assinatura (OpenClaw
etc.) → reinstalou com **crédito Agent SDK mensal separado** ($20/$100/$200, não-acumulável, a API rates) → **em
15/jun/2026 PAUSOU** essa mudança. O terreno muda; **re-checar antes de comercializar**.

**Implicação para o produto (→ decide o ADR-0016):** o CONTEXT diz *"o dono opera várias companies (uma por
produto)"* — modelo de **1 dono, suas assinaturas, self-hosted**. Isso cai no lado **permitido** ("ordinary use" da
própria conta), com o **teto de rate-limit** como a restrição real (dimensionar quantos agentes concorrentes cabem
numa assinatura). O que **não** pode, sem migrar pra **API paga**: transformar o orquestrador em **SaaS multi-tenant
roteando as assinaturas de clientes**. Essa bifurcação (self-hosted-pessoal vs SaaS-com-API-key) deve ser cravada no
**ADR-0016** antes de construir a F.8.
