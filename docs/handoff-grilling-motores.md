# Handoff de ENTRADA — Grilling "Motores" (seleção de motor por agente) → rodar `/grill-me`

> Documento de ENTRADA de uma sessão de grilling (formato igual a `handoff-grilling-approval.md`).
> A sessão deve rodar a skill **`/grill-me`** (grelhar as decisões abertas até fechar), e produzir
> um `handoff-grilling-motores-fechado.md` (formato 7c: A decisões/porquê · B diffs ADR/CONTEXT ·
> C mudanças ORDENADas · D limites v1). **Zero código nesta frente — só decisões.**
> Contexto macro do produto: memória `agente-orquestrator-stack.md`, `docs/ARQUITETURA.md`, ADRs.

## 0. Por que esta frente existe (o pedido do usuário)

O usuário quer **escolher o "motor" de cada agente** — literalmente: *"Opus 4.8 (ultracode) no Arquiteto,
um executor GPT-5.5 (extra high)"* — usando **os planos de assinatura que ele já tem no PC** (plano
Claude + plano GPT), **sem** gerar/usar uma API key paga da Anthropic. Precisa de uma **interface**
para selecionar esses modelos. Isso **NÃO existe hoje** e **NÃO está na spec visual congelada (P.1)** →
é frente nova (candidata a fatia **F.7 "Motores"**), e por tocar backend + execução + UI, precisa de
grilling antes de qualquer código.

## 1. Fatos ATERRADOS (mapeamento com evidência — NÃO re-derivar)

Levantado por um workflow de 4 readers sobre backend/API/frontend/protótipo:

- **Não existe campo de modelo em lugar nenhum.** Um `Agent` é: `adapterType` (String, default `"process"`
  — **só o `ProcessAgentAdapter` está implementado**; "Claude Code/Codex" são só comentário TODO),
  `runtimeCommand` (String shell livre, **não volta na API de resposta**), `executionTarget` (`LOCAL`|`DOCKER`).
  Evidência: `backend/.../org/agent/Agent.java`, `AdapterRunExecutor.java` (escolhe adapter SÓ por
  `agent.getAdapterType()`; `env=Map.of()` vazio; `image` hardcoded `alpine:3`).
- **`provider`/`model` existem SÓ no `CostEvent`** (ledger de custo, `governance/budget`), alimentados
  pelo cliente no POST `/cost-events` — **desacoplados do agente** (não é fonte de execução).
- **As `api-keys` mintadas na F.6 NÃO são chaves de modelo.** Formato `ag_<hex>`, só o **hash SHA-256**
  é persistido; é a **IDENTIDADE do agente no control plane** (`ActorResolutionFilter` resolve o Bearer
  → `Actor.agent`). A auth do MODELO é outra camada: herdada do **CLI** (`claude`/`codex`) na máquina
  onde o `ExecutionTarget` roda — em `LOCAL` herda o env do host; em `DOCKER` precisaria montar as credenciais.
- **A spec congelada P.1 é SILENCIOSA** sobre seleção de motor. O mais perto é o dado do agente Lyra no
  protótipo (`runtime: "claude-code · DOCKER"`) — mas é **rótulo estático**, não um seletor. Logo, UI de
  seleção = **divergência/adição nova** (reconvocar as 4 skills de design se virar UI nova).
- **Atalho que JÁ funciona hoje, zero código:** como o `ProcessAgentAdapter` roda `sh -c <runtimeCommand>`
  e o `LocalExecutionTarget` herda o env do host, dá pra pôr `runtimeCommand: claude --model …` num agente
  e `codex …` noutro, ambos `executionTarget: LOCAL` — cada um autentica com o plano respectivo do PC.
  É cru (sem seletor, saída é stdout linha-a-linha, sem sessão rica), mas prova o conceito.

## 2. O enquadramento: "motor" são (pelo menos) 3 eixos

O que o usuário chama de "motor" empacota três escolhas distintas — **a 1ª pergunta do grilling é se são 3, 2 ou 1**:

1. **Runtime/adapter** (`claude` CLI vs `codex`/`gpt` CLI) → **é ISTO que decide qual PLANO autentica.**
   Campo existe (`adapterType`), mas só `process` funciona.
2. **Modelo** (Opus 4.8 vs GPT-5.5) → **sem campo.**
3. **Esforço/modo** ("ultracode" / "extra high") → **sem campo.**

O dropdown que o usuário imagina é, na prática, um **combo (adapter + modelo + esforço)**. A auth do plano
**não** é escolhida pelo modelo — é escolhida por **qual CLI roda**.

## 3. Decisões ABERTAS a fechar no grilling (as perguntas)

1. **Eixos do motor:** são 3 (adapter+modelo+esforço), 2 (adapter+modelo) ou 1 (um "engine profile" opaco)?
   "Esforço" é first-class ou vive dentro do "modelo"? Campo estruturado (enum) vs string livre?
2. **Modelagem de dados:** a escolha vira coluna(s) na `Agent` (`model`, `effort`) seguindo o padrão de
   `adapterType`/`executionTarget`? Ou uma tabela própria (`engine_profile`/`model_config`) reusável e
   nomeável? Como evoluir quando sai um modelo novo sem migration a cada lançamento?
3. **Adapters reais (o grosso do backend):** o v1 constrói `ClaudeCodeAdapter` **e** `CodexAdapter`, ou
   só um? Contrato: como o adapter recebe `model`+`effort` e vira flags do CLI? Como fica o StepTrace
   (parsing do stdout desses CLIs ricos vs o echo do `process`)? Sessão/continuidade casa com o híbrido
   do ADR-0003 (handle em DB + estado no volume)?
4. **Auth dos planos × execução × isolamento:** `LOCAL` herda o env do host (funciona); `DOCKER` exige
   montar credenciais (`~/.claude`, config do codex) — é v1 ou fica **LOCAL-only no v1**? Como isolar
   planos por agente (Arquiteto=Claude, executor=GPT) sem vazar credencial entre containers — isso fura
   o **diferencial #3 (isolamento)**? (Cruzar com ADR-0004/execução.)
5. **Catálogo de motores disponíveis:** de onde a UI sabe QUAIS modelos o usuário tem? Lista fixa
   hardcoded? Detecção/probe dos CLIs instalados? Config manual do operador? (Análogo ao KnowAll para skills.)
6. **UI (divergência da P.1):** o seletor entra no **hire dialog** (escolhe ao contratar), no **detail**
   (kv-panel, troca depois) ou ambos? Um dropdown combinado ou 3 controles? A linha `runtime` do roster
   (hoje `adapterType · executionTarget`) vira o quê? Reconvocar as 4 skills de design (vocabulário novo)?
7. **Custo/budget:** o `CostEvent` já tem `provider`/`model`. Com motor por agente, o budget hard-stop
   (ADR-0006) passa a atribuir custo real por modelo — OU, como o usuário usa **planos de assinatura**
   (não API paga), o ledger segue sem acumular (decisão do grilling-frontend: "sem Costs UI v1, ledger
   nunca acumula")? Isso muda a tese?
8. **Sequenciamento & v1 mínimo:** backend-first (adapters + campos) antes da UI — confirmar. Qual o
   corte v1: só `ClaudeCodeAdapter` + `model`, deixando Codex/GPT pra depois? Ou os DOIS planos no v1
   porque o usuário tem os dois? Onde F.7 entra na ordem (antes/depois da passada de consistência)?

## 4. Restrições que continuam valendo (não reabrir no grilling)

- Invariantes do control plane (single-assignee, checkout atômico, budget hard-stop, company-scoping,
  deny-by-default, activity log só-IDs/sem-PII — ADR-0009).
- Docker-first é a tese de execução ("imprescindível rodar em Docker") — mas o v1 tem driver `local`.
- Skills-in-DB + materialização por-Run continuam como estão (a skill do architect é *conteúdo do plano*,
  não confundir com "motor").
- Protótipo-como-spec para as 5 superfícies existentes; a UI de motor é adição nova e justificada.

## 5. Estado do resto (para a sessão não se perder)

Backend **107/107**, frontend **122/122**. As 5 superfícies do board v1 estão DONE (F.1→F.6). Review gate
(ADR-0012) e recovery (§7/ADR-0011) completos. Repo: `agente-orquestrator/` (backend + frontend), **sem git**.
