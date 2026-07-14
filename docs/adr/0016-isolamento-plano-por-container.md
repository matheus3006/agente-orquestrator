# ADR-0016: Isolamento de plano por container (Docker)

**Status:** Aceito (2026-07-04, fatia F.8 — era Reservado desde o grilling de motores)

## Contexto
O v1 do motor (ADR-0014) rodava **LOCAL** e **não isolava**: agentes compartilham disco, credenciais, rede e
recursos do host (o `LocalExecutionTarget` herda o env — é assim que o `claude`/`codex` logados respondem).
Isolamento é o **diferencial #3** do produto — adiado, não esquecido. O upgrade LOCAL→DOCKER era **aditivo**
por construção: `executionTarget` já era coluna, o `DockerExecutionTarget` já montava o `docker run`; faltavam
a **injeção de credencial** e a **imagem por-adapter** (o executor injetava `alpine:3` hardcoded no Context).
Esquema validado por deep-research em 2026-07-02 (19 claims confirmadas 3-0; §D e anexo de
`docs/handoff-grilling-motores-fechado.md`).

## Decisão — as 4 peças

### 1. Imagens por-adapter com CLI PINADO
`infra/engines/{claude-code,codex}/Dockerfile` (base `node:22-bookworm-slim`, user não-root `node`):
`npm i -g @anthropic-ai/claude-code@2.1.201` / `@openai/codex@0.142.5`. A **tag da imagem é a versão do CLI**
(`orq-claude-code:2.1.201`, `orq-codex:0.142.5`); `infra/engines/build.sh` constrói as duas em 1 comando lendo
a versão do ARG do Dockerfile. O pino é a defesa contra o risco `--bare` do ADR-0014 (virar default de
`claude -p` e exigir API key); `DISABLE_AUTOUPDATER=1` impede o CLI de se atualizar por baixo do pino dentro
do volume-home, e o teste **`EngineContainerPinTest`** trava Dockerfile ↔ constante Java (dessincronizou,
quebrou o build). `/workspace` nasce `chown node:node` na imagem — volume novo herda o ownership do mountpoint.

### 2. Onboarding de credencial: o login NASCE no container
`infra/engines/setup-claude.sh` / `setup-codex.sh` rodam o login interativo (device-code) **uma vez**, dentro
do container, gravando nos volumes nomeados **`orq_claude_creds`** / **`orq_codex_creds`**. **Nunca copiar do
host** (ADR-0002): o refresh token OAuth **rotaciona ao usar** — cópias dessincronizam e quebram os dois lados
(confirmado 3-0; vale até onde a OpenAI documenta `docker cp auth.json`). O volume monta o **HOME inteiro**
(`/home/node`), não só `~/.claude/`: o onboarding-state do Claude vive em **`~/.claude.json`**, FORA do dir
(refinamento obrigatório do deep-research) — o home-como-volume carrega os dois de uma vez. O Codex força
credencial **em arquivo** (`cli_auth_credentials_store = "file"` semeado no `config.toml` da imagem E garantido
no setup) — keyring não viaja em volume. **Alternativa de automação (só Claude):** o token do
`claude setup-token` (~1 ano) entra pela property `orquestrador.engines.claude.oauth-token` e vira o env
`CLAUDE_CODE_OAUTH_TOKEN` do container — dispensa o browser.

### 3. Montagem seletiva por-adapter — é AQUI que o isolamento acontece
O módulo profundo **`EngineContainer`** (`execution.adapter`; ADR-0010) esconde imagem pinada + volume do
plano + mountpoint + o remédio de credencial quebrada, atrás de 3 factories (`claudeCode()`, `codex()`,
`plainShell()`). Cada adapter monta o **próprio** `Spec` com eles; o `ExecutionTarget.Spec` ganhou
**`credentialMounts`** (`VolumeMount`) que o `DockerExecutionTarget.buildArgs` traduz em `-v` (puro, testável
sem daemon) e o LOCAL ignora (agentes LOCAL intocados — upgrade aditivo de verdade). O **`Context` perdeu o
campo `image`** e o `AdapterRunExecutor` não conhece imagem nem path de credencial algum. O container do
agente Claude monta **só** `orq_claude_creds`; o do Codex **só** `orq_codex_creds` — a prova é dupla e unit:
interseção de volumes vazia (`EngineContainerTest`) e o argv do `docker run` sem o volume do outro plano
(`DockerExecutionTargetArgsTest`).

### 4. Expiração/refresh: falha LEGÍVEL, nunca silêncio
Enquanto os agentes rodam, o próprio CLI refresca o token **dentro do volume** (a rotação acontece no lugar
certo). Quando a credencial falta/expira, o run falha com remédio no erro: **`CredentialFailureSignal`**
fareja termos de auth **só em linhas fora da gramática JSON** do stream (a prosa do modelo vive dentro de
JSON — sem falso-positivo; no Codex também na `message` do evento `error`) e o adapter antepõe o hint do
`EngineContainer` (volume + script de setup; no LOCAL, o login do host). O sinal só **enriquece** erro de run
já falho (exit != 0) — jamais derruba run saudável. A Issue vai a BLOCKED com o remédio visível no board.

## Residuais decididos na implementação (não reabrir)
- **(a) Onde vive a imagem = constante do adapter** (via `EngineContainer`), não `engine_catalog`: a imagem
  precisa casar com a gramática que o parser do adapter fala (coesão adapter↔imagem↔parser); o catálogo
  "sugere, não restringe" (ADR-0014) e virar config de runtime mudaria sua natureza — além de exigir V20.
- **(b) Refresh no v1 = re-login manual** (rodar o setup de novo quando a falha legível apontar): o CLI
  auto-refresca em uso; PARK automático seria estado novo sem consumidor.
- **(c) Credencial ausente = `RunOutcome.failure` legível**, não fail-fast no preflight: preflight exigiria
  um docker-probe por run (caro) ou estado especular no banco (mentira potencial). O run falha visível.

## Ressalvas honestas (registrar, não esconder)
- A montagem seletiva isola **por-diretório-de-config** — **não é fronteira de segurança rígida** (containers
  do mesmo agente compartilham workspace r/w; o daemon Docker é do host; quem roda no host vê os volumes).
  Contra vazamento de credencial entre PLANOS e contra o agente ler o disco do host, resolve; contra um
  adversário com acesso ao host, não.
- O env `CLAUDE_CODE_OAUTH_TOKEN` entra inline no argv do `docker run` (visível em `docker inspect` no
  próprio host). Aceito no self-hosted v1 (o segredo é do operador, single-node); mover para pass-through de
  env se o modelo de deploy mudar. O argv nunca é logado pelo produto (ADR-0001: sem segredos em logs).

## Bifurcação de negócio (fonte primária; §5 do fechado de motores)
- **Self-hosted-pessoal (a própria assinatura do operador) = PERMITIDO** ("ordinary, individual usage"). O
  teto real é **rate-limit** (limites semanais Claude; janelas do Codex) — throttle, não proibição.
- **SaaS multi-tenant roteando assinaturas de terceiros = PROIBIDO** → exigiria **API key paga**.
- Política **volátil** (ban→reinstatement→pausa ao longo de 2026) — **re-checar antes de comercializar**.

## Defesa em profundidade
A contenção de T.4 (spawn_lineage, fan-out cap, ledger de orquestração, drena-até-checkpoint) **continua por
cima** do container: F.8 soma isolamento real, não substitui a rede. Um agente containerizado continua preso
à linhagem, ao cap do time e ao orçamento de orquestração.

## Relaciona
ADR-0001 (Docker-first canônico), ADR-0002 (login nasce no container), ADR-0004 (zero mudança no core — o
Context até emagreceu), ADR-0010 (EngineContainer é o módulo profundo da fatia), ADR-0014 (motor v1 LOCAL +
risco --bare), ADR-0015 (handoff), ADR-0018 (o CEO com mãos foi o amplificador da urgência).

## Revisão 2026-07-04 (frente Teams — ADR-0018) — histórico pré-construção
- O **CEO com "mãos"** (propõe hire/task/wakeup) **amplifica** a questão: LOCAL, sem container, ele pode spawnar
  agentes de motores heterogêneos. Seguiu **LOCAL no v1**. A **contenção** enquanto o container (F.8) não existia
  foi (seção C-T.4 do `handoff-grilling-teams-fechado.md`): **`spawn_lineage`** (acordar um ancestral = recusado no
  checkout → mata loop; + cap de profundidade, módulo profundo `execution.lineage`), **fan-out cap por arquiteto**
  no preflight, e contabilizar `AGENT_SPAWN`/`WAKEUP`/`RUN_OPEN` no ledger de budget (V5) com
  `orchestration_limit_cents` ("drena até o checkpoint" no `cancelScopeWork`). A **contratação incremental**
  (ADR-0017) é o freio primário; estes limites são a rede embaixo.
- **CONSTRUÍDA (T.4, 2026-07-04):** a rede inteira está viva — módulo `execution.lineage` (guard 2 faces +
  cap de profundidade 5; wakeup aprovado **herda** a linhagem do run proponente — causalidade, não autoria),
  `team.fan_out_cap`, ledger de orquestração em moeda própria (`cost_event.orchestration_action` +
  `orchestration_limit_cents`; conta cai no CAUSADOR) e a drenagem até o checkpoint no `BudgetService`.
  Migration **V19**; registro canônico: `docs/handoff-teams-t4.md`.

## Nota-GATE — home-volume compartilhado (2026-07-14, fecho documental; decisão D7 do grill motor-nativo, 2026-07-11) — **BLOQUEANTE**

O laudo motor-nativo (R4-C, `docs/laudo-motor-nativo.md` §1.1-d) registrou a consequência de
segurança do desenho "home-como-volume" desta ADR (§2): os **JSONLs de sessão de TODOS os
tenants vivem no MESMO home-volume do motor** (`orq_claude_creds`/`orq_codex_creds` montam
`/home/node` inteiro) e são **legíveis pelo modelo via Bash** — um agente de uma company pode,
em princípio, ler o rastro de sessão de outra. O grill do laudo (R5-A, D7,
`docs/handoff-grilling-motor-nativo.md`) decidiu: **ACEITO no v1** (hoje o único tenant é o
dono — o risco só materializa em multi-tenant de verdade), **com este GATE BLOQUEANTE**:

> **Nenhum tenant externo entra ANTES da fatia de isolamento do home-volume shippada.**

Não é ressalva informativa — é condição de entrada de qualquer 2º tenant (soma-se à bifurcação
de negócio da §Bifurcação: multi-tenant já exigiria API paga). Cinto de carona JÁ shippado:
o hook **PreToolUse** da F-N6 (**ADR-0042**, PR #20) nega leitura de
`~/.claude/projects`/`~/.codex/sessions` fora do próprio workspace nos DOIS motores — mitiga o
caminho trivial, mas é cinto (deny por hook), não fronteira (o isolamento real é a fatia
futura, com rascunho de desenho no D7 do grill; candidata v1.1 "(ii)-docker + isolamento D7").
