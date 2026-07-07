# Handoff — F.8: Isolamento de plano por container (ADR-0016 ACEITO)

> Sessão 2026-07-04/05. Backend-first TDD (RED confirmado em toda peça — inclusive nos Dockerfiles,
> via teste de pino). Sem migration (V19 segue a última) e sem tela. Repo SEM git — nada commitado.
> **Estado: F.8 COMPLETA E PROVADA — smoke estágio 1 (process+DOCKER) E estágio 2 (claude real com
> credencial do volume) VIVOS na teams-demo. Falta só o plano Codex (setup-codex.sh, quando quiser).**

## Piso (verificado)
- Backend **256/256** (`cd backend && mvn -o test`) — eram 237; +19 de F.8.
- **Imagens construídas**: `orq-claude-code:2.1.201` (979MB) e `orq-codex:0.142.5` (1.07GB)
  (`infra/engines/build.sh`, 1 comando; base node:22-bookworm-slim, USER node).
- Jar F.8 no `:8080` (log `/tmp/orq-f8.log`; Flyway ok no Postgres real, V19).
- **Smoke estágio 1 VIVO** na teams-demo: agente `Smoke F8 Docker` (process, executionTarget=DOCKER,
  id `31b4798e-0e10-4c94-9316-49542b11a494`) → run `35c11b86-…` **SUCCEEDED** com StepTrace
  `STDOUT: Linux 72d80c17d479 6.12.76-linuxkit … aarch64` — rodou DENTRO do container pelo pipeline
  normal (wakeup → dispatcher → docker run → steps → settle).
- Front intocado (fatia sem tela; piso T.3-FE segue).

## O que existe agora (mapa de código)
- **`execution/adapter/EngineContainer`** (o módulo profundo da fatia; ADR-0010): imagem pinada +
  volume do plano montado no HOME inteiro (`orq_claude_creds|orq_codex_creds → /home/node` — cobre
  `~/.claude` E `~/.claude.json`) + hint de credencial quebrada. Factories `claudeCode()/codex()/
  plainShell()`; constantes de versão travadas ao ARG dos Dockerfiles pelo **`EngineContainerPinTest`**.
- **`ExecutionTarget.Spec` += `credentialMounts`** (`VolumeMount`); `DockerExecutionTarget.buildArgs`
  traduz em `-v` (puro, sem daemon); LOCAL ignora (upgrade aditivo — agentes LOCAL intocados).
- **`AgentAdapter.Context` PERDEU `image`**; cada adapter monta o próprio Spec com seu
  EngineContainer. `AdapterRunExecutor` sem `alpine:3` — o executor não conhece imagem nem credencial.
- **`ClaudeCodeAdapter`** ganhou construtor com property `orquestrador.engines.claude.oauth-token`
  (→ env `CLAUDE_CODE_OAUTH_TOKEN`; alternativa setup-token ~1 ano, sem browser).
- **`CredentialFailureSignal`** + `credentialFailure()` nos DOIS parsers: fareja auth quebrada SÓ em
  linha fora da gramática JSON (anti-falso-positivo: prosa do modelo vive em JSON) — no Codex também
  na message do evento `error`. Só ENRIQUECE erro de run já falho; remédio por-target (DOCKER → volume
  + setup script; LOCAL → login do host).
- **`infra/engines/`**: 2 Dockerfiles pinados (`DISABLE_AUTOUPDATER=1` no claude; codex com
  `cli_auth_credentials_store="file"` semeado; `/workspace` chown node) + `build.sh` +
  `setup-claude.sh`/`setup-codex.sh` (login nasce no container — ADR-0002; rotação de refresh token).

## Decisões desta sessão (residuais do ADR — não reabrir)
(a) imagem = **constante do adapter** (coesão adapter↔imagem↔parser; catálogo segue só sugestão) ·
(b) refresh v1 = **re-login manual** (CLI auto-refresca em uso; falha legível aponta o script) ·
(c) credencial ausente = **RunOutcome.failure legível** (preflight custaria um docker-probe por run).
Ressalvas honestas no ADR: isolamento por-diretório-de-config (não fronteira rígida); token env
inline no argv (docker inspect no host do operador — aceito no self-hosted).

## SMOKE ESTÁGIO 2 — PROVADO (2026-07-05, credencial real)
- **Login nasceu no container** (setup-claude.sh via Terminal aberto por osascript; device-code;
  ~2min40s até `.credentials.json` no volume `orq_claude_creds`).
- Agente `Smoke F8 Claude` (claude-code+DOCKER, id `6fcaff70-f3d1-4b0c-88df-d50c3eeb849e`):
  **2 runs SUCCEEDED** (`8fb6c839-…`, `631586a4-…`, ~10s cada). StepTrace SEMÂNTICO do CLI real:
  `SYSTEM init → RATE_LIMIT_EVENT → SYSTEM thinking_tokens → ASSISTANT → RESULT "isolamento F8
  vivo em /workspace"`. O `RATE_LIMIT_EVENT` é evento NOVO do CLI 2.1.201 absorvido fail-soft
  (tipo nativo em UPPER) — o parser real aguentou a gramática real na primeira vez.
- **Ledger**: `cost_event` do run = `anthropic | claude-opus-4-8 | 20169 in | 69 out | 0 cents`
  (model REAL resolvido do stream = default do plano; tokens reais; dólar inócuo na assinatura).
- **A PROVA do isolamento (container VIVO do run, `docker inspect` durante execução)**:
  `orq_142f1ac5_6fcaff70 → /workspace` + `orq_claude_creds → /home/node` e **NENHUM
  `orq_codex_creds`** — a montagem seletiva na prática, além do unit test do argv.
- Pegadinha da captura: `docker ps --filter ancestor=` também pega o container do SETUP
  interativo (1 mount só) — filtrar pelo mount `/workspace` para achar o container DO RUN.

## O que segue aberto (consciente)
- **Codex**: `infra/engines/setup-codex.sh` quando o segundo plano for necessário (imagem pronta,
  adapter pronto, volume nasce no setup).
- O agente de smoke process (`31b4798e`) e o claude (`6fcaff70`) ficaram na teams-demo como
  demonstração viva de DOCKER target (limpar se virarem ruído).

## Gotchas (sangue desta sessão)
- `claude`/`codex` NÃO existem no host (nem npm global) — o Atlas sempre foi `process`+printf. As
  versões pinadas vieram do registry npm (2.1.201 / 0.142.5): a 1ª execução real do parser contra o
  CLI de verdade É o smoke estágio 2 (risco anotado no CodexStreamParser desde D→G).
- Volume novo herda ownership do mountpoint da IMAGEM → `/workspace` tem que nascer chown node:node
  no Dockerfile (senão root-owned e o USER node não escreve).
- `status` é read-only no zsh de teste — usar outro nome em loops de poll.
- O smoke process+DOCKER prova que o jar (spawnado do shell) enxerga o daemon.

## NORTE
Isolamento é o diferencial #3 — **F.8 fecha o buraco de verdade** (a rede T.4 continua por cima:
defesa em profundidade). Depois do estágio 2, o "Definition of Demoable" ganha preview real
(§A.6-#2 do teams-fechado). Recorte que segue fora: rate-limit awareness, credencial run-scoped,
comunicação lateral, --resume nativo, SaaS multi-tenant (PROIBIDO por ToS sem API key).
