# Handoff — Execução F.10 (sessão 2026-07-05) — FATIA COMPLETA

> Destilado ao cruzar ~metade do contexto (ADR-0015) e FINALIZADO ao fim da sessão. Fonte da
> verdade das decisões: ADR-0023 (Aceito; ressalva do masquerade ATUALIZADA com o achado do
> smoke). Plano: docs/handoff-grilling-processo-porta-fechado.md §C/§D. Repo SEM git.
> **F.10a, F.10b, F.10c e F.10d COMPLETAS — critérios D.1-D.10 verificados.**

> **CONSOLIDAÇÃO (2026-07-05, sessão pós-merge):** suíte backend **328/328** (piso novo, inclui
> o import seletivo da sessão paralela) · jar novo no :8080 (pid 13741, /tmp/orq-f10.log,
> Flyway up to date/V21) · smoke do import seletivo OK em company descartável
> `a4e899b1-f64f-45cc-8266-bd825bed61c4` (board user aaaa0000-…d15c): `{"skillKeys":
> ["criar-app-servidor"]}` → created=1, catálogo=1; `{"skillKeys": ["nao-existe"]}` →
> skipped legível, nada instalado. teams-demo intocada (81 skills FICAM, decisão do operador).

## RESULTADO FINAL (F.10c/F.10d — completadas após o corte parcial abaixo)

- **F.10c**: `preview-server-frame.tsx` (modo src: `sandbox="allow-scripts allow-same-origin
  allow-forms"`, keepalive 60s com frame montado via `useServerKeepalive`) ·
  `preview-server-section.tsx` (IssueServerSection + ReviewServerEmbed com aviso "workspace
  avançou" por runId) · tela `/servers` (vivos: TTL countdown/abrir/matar; candidatas: iniciar)
  · sidebar+shell com superfície "servers" · lib `preview-server.ts` (fmtTtl/ttlRemainingNow/
  workspaceAvancou/splitStartFailure) · CSS no bloco "Servidor de preview" do globals.css.
  vitest 194/194 (9 testes novos) · tsc limpo · lint: só erros PRÉ-EXISTENTES em
  ceo-conversa.tsx. Verificado NO BROWSER: iniciar pela tela subiu servidor real; Issue mostrou
  frame vivo; stop derrubou; v1 (srcdoc, sandbox="allow-scripts" puro) INTOCADO no DOM.
- **F.10d (smoke DOCKER real)**: higiene do cobaia feita (2x: prompt do smoke → restaurado ao
  neutro canônico da F.9e ao final). Issue risk 5 `e5946ef0` → run `f2745da6` (claude+DOCKER,
  ~75s) → **skill criar-app-servidor usada em uso real com prompt NEUTRO**: publicou
  `{"command": "node server.js", "port": 3000}` → manifest por run no settle → start subiu o 4º
  container `orq_preview_142f1ac5_6fcaff70_e5946ef0` na porta efêmera **127.0.0.1:49862** →
  app Lembretes VIVA no card de Review (UI fazendo fetch na própria /api/lembretes — screenshot
  tirado; o furo "feature de backend não ganha preview" do ADR-0021 §1 FECHOU) → **APPROVED
  vendo → DONE**. Container verificado: SEM credencial (só workspace `:ro`), tmpfs `/tmp`, rede
  orq-preview, caps 512MB/1cpu/256pids, porta SÓ loopback. Restart do jar: container sobreviveu
  e foi RE-ADOTADO (ttl 887s de grace) → stop manual derrubou (0 containers).
- **Achado do smoke (registrado no ADR-0023, ressalvas)**: no Docker Desktop/macOS o
  `enable_ip_masquerade=false` NÃO bloqueia egress (fetch externo respondeu de dentro do
  container). Fallback DECIDIDO aplicado: bridge + ressalva (canal dominante já é o run). Em
  Linux nativo a opção vale — ela permanece na criação da rede.
- **Decisão do operador (2026-07-05): os 81 installs de company_skill na teams-demo FICAM.**
  O POST /skills/import instalou o catálogo pessoal inteiro como efeito colateral, mas o
  operador as instalaria de qualquer forma quando o projeto estivesse pronto — estado desejado,
  não poluição. NÃO limpar; nenhuma reversão pendente.
- Ambiente ao fim: jar F.10 no :8080 (pid 12216, /tmp/orq-f10.log) · dev server :3000 ·
  smoke LOCAL vivo como demo (agente 215a276b, issue 5f207cc0) · issue DOCKER e5946ef0 DONE.

---

(segue o corte parcial da metade da sessão, mantido como histórico)

## Estado do backend (F.10a COMPLETA — suíte 324/324 verde, piso era 288)

- **V21 `preview_server_manifest`** aplicada no Postgres REAL (Flyway ok, jar F.10 no ar).
  Entidade/repo em `preview/server/PreviewServerManifest*.java` (append-only, vigente = último).
- **Registrador no settle**: `preview/server/PreviewServerManifestRegistrar.java` — contrato
  `.orq/preview/server.json`, cap 4KB, parse ESTRITO (exatamente {"command","port"}, porta
  1-65535, campo extra/typo recusa com INVALID_MANIFEST). Hook no `AdapterRunExecutor` ao lado
  do capturador v1 (após sucesso, antes do settle). Ações novas no `ActivityAction`:
  PREVIEW_SERVER_DECLARED/DECLARATION_FAILED/STARTED/START_FAILED/STOPPED.
- **Seam**: `ExecutionTarget` ganhou `startPreviewServer/stopPreviewServer/listPreviewServers/
  previewServerLogTail` + records `PreviewServerSpec/Start/Stop/Live` (SEM credentialMounts por
  construção). DOCKER: argv puro testado (`DockerExecutionTargetArgsTest` — 4º container `-d`,
  nome determinístico, labels orq.preview/company/agent/issue, rede orq-preview, `:ro`+tmpfs,
  caps por spec, `-p 127.0.0.1::porta`, `docker port` read-back, `docker rm -f`, `docker logs
  --tail`, `docker ps --filter label` + parsers puros; rede criada idempotente com
  `enable_ip_masquerade=false`, corrida "already exists" = ok). LOCAL: spawn `sh -c` no workDir,
  registro em memória, tail por arquivo temp, `@PreDestroy` derruba tudo
  (`LocalExecutionTargetServerTest`). 4 fakes de teste ganharam stubs.
- **Módulo profundo**: `preview/server/PreviewServerSupervisor.java` —
  `startFor/stopFor/heartbeat/listLive/reapOnce/teardownOnCheckout`. Start idempotente (1/Issue,
  vivo=reusa), poll TCP (~10s, morte precoce abrevia), falha legível "servidor morreu ao subir"
  + tail, TTL por inatividade (property `orquestrador.preview.server.ttl-seconds:900`), reaper
  com ADOÇÃO pós-restart (putIfAbsent = grace de 1 TTL), caps por property
  (`…memory:512m/…cpus:1/…pids-limit:256`), poll `…start-poll-ms:10000`, reap agendado no
  `HeartbeatScheduler` (`…reap-ms:60000`). Teardown no checkout: 2º ponto do executor (depois
  do checkout acquired), best-effort.
- **Endpoints board-only** (`preview/server/PreviewServerController.java`):
  POST `/issues/{i}/preview-server/start|stop|heartbeat` · GET `/issues/{i}/preview-server`
  (status: manifest vigente + live c/ ttlRemainingSeconds — front compara `manifest.runId` com
  `review.runId` p/ aviso "workspace avançou") · GET `/preview-servers` (tela: live[] +
  candidates[]). 404 p/ issue fora da company; guard.assertBoardMember.
- **Testes novos** (36): PreviewServerManifestPersistenceTest(2) · RegistrarTest(6) ·
  DeclareFlowTest(3) · DockerArgs +6 · LocalServerTest(4) · SupervisorTest(7) · ReaperTest(3,
  ttl=2s) · TeardownOnCheckoutTest(1) · HttpTest(4).

## Smoke LOCAL (critério D.8) — PASSOU ponta a ponta
Agente `smoke-f10-local` 215a276b (process+LOCAL, teams-demo) · issue 5f207cc0 · run publicou
server.json no run → manifest registrado no settle (runId confere) → POST start subiu
`python3 -m http.server 4173` → curl na porta devolveu o app → heartbeat live:true → tela
Servidores listou vivo+candidata → stop derrubou (porta morta) → manifest persiste.

## F.10b COMPLETA — skill `criar-app-servidor`
- `~/.claude/skills/criar-app-servidor/SKILL.md` (contrato v2: deps NO RUN, `/tmp` p/ escrita,
  bind 0.0.0.0, NÃO deixar servidor vivo no run, publicar server.json por último, subida <10s).
- Importada (skill_key `criar-app-servidor` v1) e ANEXADA ao cobaia claude
  `6fcaff70-f3d1-4b0c-88df-d50c3eeb849e` (companySkillId 95f98f47, pinada c84f6fb8).
- **Efeito colateral do POST /skills/import**: criou 81 installs de company_skill na
  teams-demo (antes ~6) — o import varre o root inteiro, não há import seletivo por skill.
  **RESOLVIDO: o operador decidiu MANTER** (ele as instalaria de qualquer forma). Débito de
  produto anotado: import seletivo por skill evitaria o all-or-nothing.

## Ambiente vivo
- Jar F.10 no :8080 (`/tmp/orq-f10.log`, pid da sessão; V21 aplicada) · db
  `agente-orquestrator-db-1` · board user `b0a7d000-0000-4000-8000-000000000001` · company
  teams-demo `142f1ac5-16d0-4e75-acde-fbad93a666c3`.
- Higiene F.10d AINDA PENDENTE: `runtime_command` do cobaia 6fcaff70 segue "Deixe o protótipo
  com tema escuro." — restaurar por SQL dollar-quoted ($cmd$…$cmd$, `docker exec -i`) para o
  prompt neutro de skills ANTES do smoke DOCKER.

## F.10c (em andamento) — mapa do front
- `preview-frame.tsx`: modo srcdoc v1 (sandbox="allow-scripts" PURO — não tocar). Ganha modo
  **src**: `sandbox="allow-scripts allow-same-origin allow-forms"` (origin da PORTA, seguro e
  necessário — ADR-0023 §5).
- `preview-section.tsx`: IssuePreviewSection (Issue) + ReviewPreviewEmbed (card usa
  `artifactForRun(runId)`); reviews/page.tsx linha ~250 embute.
- `api.ts`: apiFetch central (X-Board-User via NEXT_PUBLIC_DEV_BOARD_USER); adicionar fetchers
  do preview-server. `query-keys.ts`: adicionar chaves. `sidebar.tsx`: SURFACES — adicionar
  rota "Servidores" (`/servers`).
- Plano: lib/preview-server.ts (helpers puros + testes vitest) · hook keepalive ~60s com frame
  montado (POST heartbeat) · tela `[companySlug]/servers/page.tsx` (vivos: matar/abrir;
  candidatas: iniciar) · botão "Rodar app" na Issue (seção Preview) e no card de Review
  (manifest.runId == review.runId; senão aviso "workspace avançou") · reconvocar
  /bencium-impact-designer /bencium-innovative-ux-designer /emil-design-eng /ui-ux-pro-max.
- vitest: `cd frontend && npx vitest run` (verde nas telas tocadas é critério D.9).

## F.10d (pendente — roteiro)
0. Higiene: restaurar runtime_command do cobaia (acima). 1. Issue risk 5 "criar app COM
SERVIDOR de X" no cobaia claude+DOCKER → wakeup por API → run real (~2-4min; poll com folga) →
manifest registrado. 2. Board: "Rodar app" no card de Review → app viva no iframe (fetch à
própria API). 3. Verificar: sem credencial no container, `:ro`+tmpfs, egress bloqueado
(masquerade=false a validar no Docker Desktop/macOS; fallback DECIDIDO: bridge + ressalva no
ADR-0023), porta só loopback, caps. 4. Aprovar VENDO → TTL/stop derruba. Gotchas herdados:
wakeup por API (nunca INSERT), `docker exec -i`, headers curl literais, DONE não reabre.
