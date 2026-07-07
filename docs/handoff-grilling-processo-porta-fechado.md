# Handoff FECHADO — Grilling "Preview processo/porta" (F.10 · v2 do diferencial #5)

> **Entregável** da sessão de grilling 2026-07-05 sobre o v2 do preview (pós-F.9e provada).
> Formato: **A** decisões/porquê · **B** diffs de ADR/CONTEXT · **C** plano de execução TDD ·
> **D** recorte + critérios de aceite.
> **Q1–Q9 fechadas 9/9** — 8 na recomendação; **Q3 fechada MELHOR que a recomendação pelo
> operador** (tela dedicada de servidores + TTL por INATIVIDADE, não fixo).
> **Zero código nesta sessão.** ADR novo: **ADR-0023** (criado e Aceito). CONTEXT.md atualizado.
> Nenhuma decisão dos ADRs 0001/0012/0016/0021/0022 reaberta — todas consumidas.
> Piso herdado da F.9e: backend 288/288 · V20 é a última → a F.10 abre **V21**.

---

## A — DECISÕES + PORQUÊ (resumo; racional completo no ADR-0023)

### A.1 (Q1) — O servidor roda num **4º container da família** (run, leitor, escritor → servidor)
Pipeline cria, monta o volume da Issue, **SEM `credentialMounts`**, executa o comando do app.
Agente autora o *conteúdo*; pipeline é dono do *processo*. O container do run é desqualificado:
monta credencial do plano (servidor exposto + `orq_claude_creds` = fura ADR-0016) e a morte dele
**é** o settle.

### A.2 (Q2) — Anúncio = **manifest `.orq/preview/server.json`** lido no settle e PERSISTIDO
`{"command": …, "port": …}` — parse estrito, cap ~4KB, porta 1-65535. Lido pelo `readWorkspaceFile`
que já existe, persistido **por run** (V21). Subir usa o comando **persistido** (o que se declara é
o que executa). Registro best-effort legível (observação, como a captura v1). Badge/botão sem tocar
Docker; review ganha "run X declarou comando Y porta Z".

### A.3 (Q3) — Ciclo de vida: **sob demanda + tela dedicada + TTL por inatividade** ⭐ input do operador
Sobe por clique do operador (tela Servidores, Issue ou Review) — servidor só existe com humano
olhando. **1 por Issue** (start idempotente). Morre por: **TTL 15min sem heartbeat** ·
**checkout de run novo na Issue** (workspace vai mutar; best-effort legível) · **stop manual**.
Restart-safe: nome determinístico `orq_preview_<company>_<agent>_<issue>` + labels; reaper varre
`docker ps --filter label=`; heartbeat em memória com grace 15min pós-restart.

### A.4 (Q4) — Rede/porta: **porta efêmera no loopback + rede `orq-preview` sem NAT + caps**
`-p 127.0.0.1::<porta-do-manifest>` (Docker aloca; backend lê via `docker port`; zero gestão de
colisão; nunca LAN). Rede criada 1x com `enable_ip_masquerade=false`: porta publicada funciona,
internet não. Fallback consciente: bridge + ressalva. Caps `--memory/--cpus/--pids-limit` por
property.

### A.5 (Q5) — Acesso: **porta direta no iframe, sem proxy; inatividade = heartbeat do board**
`PreviewFrame` **modo src**: `sandbox="allow-scripts allow-same-origin allow-forms"` — o origin
concedido é o DA PORTA DO APP (não é a combinação letal do v1, e é necessário para o fetch do app
à própria API). JS do app não age como o board: cross-origin + `X-Board-User` (header custom →
preflight → bloqueado). Proxy rejeitado (HTML navegável no origin do control-plane +
websocket/HMR). Keepalive ~60s com o frame montado renova o TTL.

### A.6 (Q6) — **Volume `:ro` + tmpfs `/tmp`** — o que roda é o que o run liquidou
Runs serializados + read-only ⇒ a garantia da foto v1, agora para processo. Escrita só em `/tmp`
(instrução da skill: SQLite em /tmp ou memória). Card de Review: "Rodar app" quando o run sob
revisão declarou (manifest por-run + `review.run_id`); aviso "workspace avançou desde este run" se
o último manifest for de run posterior. **v1 permanece o registro imutável de aprovação; o v2 é o
momento-demo vivo — e o 1º preview real de feature de backend** (furo do ADR-0021 §1 fecha aqui).

### A.7 (Q7) — UI: **3 superfícies + falha legível**
(1) Tela dedicada "Servidores": vivos (Issue/agente/comando/porta/TTL) com matar/abrir + candidatas
(manifest declarado) com iniciar. (2) "Rodar app" INLINE na Issue e no card de Review (reviewer não
sai do card). (3) Mission Control intocado. Falha de subida: poll TCP ~10s → morto/porta fechada =
"servidor morreu ao subir" + tail do `docker logs` (espírito CredentialFailureSignal).

### A.8 (Q8) — Seam: **declaração no banco, runtime no Docker, TTL na memória**
`ExecutionTarget` ganha o par start/stop de servidor (mesmo eixo — um eixo, um seam, 3ª aplicação;
argv puro testável sem daemon; **dois adapters reais dia 1**: LOCAL = spawn no workDir, dev-only).
**V21 `preview_server_manifest`** append-only por run (company/issue/agent/run/command/port/created_at;
sem dedup — o vigente é o último da Issue). Vivo/porta = `docker ps`/`inspect` por label (tabela de
vivos = estado especular, rejeitada pelo argumento do ADR-0016(c)). Módulo profundo
**`preview.server`**: manifest + start idempotente + poll TCP + TTL/heartbeat + reaper +
teardown-no-checkout + tail, atrás de `startFor/stopFor/heartbeat/listLive`. Executor toca 2 pontos
(settle registra; checkout derruba).

### A.9 (Q9) — Recorte + DoD
Fora: multi-serviço/compose/banco provisionado · HTTPS/remoto (premissa single-node REGISTRADA) ·
install em serve-time · HMR como contrato · idle por tráfego · log streaming · multi-porta ·
persistência entre sessões de preview · GC/edição de manifest. **DoD inclui skill nova no catálogo**
(carrega o contrato do manifest — a lição do F.9d: sem skill, o contrato nunca nasce em uso real).
Codex ganha de graça quando o login existir.

---

## B — DIFFS DE ADR / CONTEXT.md (APLICADOS nesta sessão)

- **ADR-0023 "Preview processo/porta — servidor vivo sob demanda" — CRIADO, Aceito**
  (`docs/adr/0023-preview-processo-porta-servidor-sob-demanda.md`): 8 decisões, 12 alternativas
  rejeitadas com porquê, recorte, 4 ressalvas honestas (single-node; buraco pré-existente
  gateway/`host.docker.internal` — registrado, não criado nem ampliado pelo v2; masquerade=false a
  validar no smoke com fallback decidido; LOCAL dev-only).
- **CONTEXT.md** — termo novo **PreviewServer**; **Contrato de lugar** agora nomeia os DOIS
  contratos (`index.html` + `server.json`); **PreviewFrame** precisado em DOIS MODOS (srcdoc: nunca
  same-origin; src: same-origin seguro E necessário — origin da porta do app). Correção de conflito
  de glossário: o "nunca allow-same-origin" absoluto era verdade só do modo srcdoc.
- **Sem emenda a ADRs existentes** — 0001/0012/0016/0021/0022 consumidos, não alterados.

---

## C — PLANO DE EXECUÇÃO (fatias TDD backend-first; /tdd com RED confirmado peça a peça)

**F.10a — Backend (o grosso):**
1. Migration **V21** + entidade/repo `preview_server_manifest`.
2. Registro no settle: leitor do manifest (`readWorkspaceFile` cap 4KB) + parse estrito
   (command/port; porta 1-65535) + persistência por run — best-effort legível, espelhando o
   capturador v1 (mesmo ponto do fluxo: pós-sucesso, pré-settle, fora de tx).
3. Seam: par start/stop no `ExecutionTarget` — RED nos dois adapters. DOCKER: argv puro
   (`docker run -d` + nome determinístico + labels + rede `orq-preview` + `:ro` + tmpfs + caps +
   `-p 127.0.0.1::porta`; SEM credentialMounts — teste espelha `DockerExecutionTargetArgsTest`);
   `docker port` para ler a porta; `docker rm -f` no stop. LOCAL: spawn no workDir + registro em
   memória.
4. Módulo `preview.server`: start idempotente (1/Issue) + poll TCP ~10s + falha legível com tail
   de `docker logs` + heartbeat/TTL 15min + reaper agendado (labels; grace pós-restart) +
   teardown no checkout de run da mesma Issue.
5. Bootstrap idempotente da rede `orq-preview` (masquerade=false; criar se não existe).
6. Endpoints board-only: start/stop/heartbeat/list (tela Servidores) + candidatas (Issues com
   manifest) + status na Issue/Review.
7. **Smoke sem crédito:** agente `process` + printf do `server.json` + `python3 -m http.server`
   (LOCAL) — a fatia inteira sem gastar credencial.

**F.10b — Skill (conteúdo, não código):** skill nova no catálogo — app com servidor, deps
instaladas NO RUN, escrita só em `/tmp`, publicar `.orq/preview/server.json` como passo final;
attach no agente cobaia.

**F.10c — Frontend:** `PreviewFrame` modo src (sandbox próprio do modo) · tela "Servidores"
(rota própria: vivos + candidatas + matar/abrir/iniciar) · botão "Rodar app" inline na Issue e no
card de Review + aviso "workspace avançou" · hook de keepalive (~60s com frame montado).
Reconvocar as skills de design da casa.

**F.10d — Smoke real (a demo que muda de nível):** Issue "criar app COM SERVIDOR de X" (risk 5) →
run DOCKER real → manifest registrado → "Rodar app" no card de Review → **aprovar vendo a app
RODANDO** (processo vivo, API respondendo) → TTL/stop derruba. Validar masquerade=false no Docker
Desktop/macOS aqui (fallback: bridge + ressalva).

---

## D — CRITÉRIOS DE ACEITE DA FATIA

1. Run DOCKER real publica `server.json` via skill → manifest persistido por run, visível na Issue.
2. "Rodar app" no card de Review sobe o container em <~15s ou falha LEGÍVEL (tail de log no board).
3. iframe modo src renderiza o app vivo; fetch do app à própria API funciona (same-origin da porta).
4. Container: SEM credenciais, volume `:ro`, tmpfs `/tmp`, rede sem internet (egress bloqueado
   verificado no smoke), porta só no loopback, caps aplicados.
5. TTL: sem heartbeat por 15min → reaper mata; com tela aberta → vive. Stop manual e teardown no
   checkout de run novo funcionam.
6. Restart do backend não órfã container (reaper por label re-adota/mata).
7. Tela Servidores lista vivos e candidatas; matar/iniciar por ela.
8. Smoke LOCAL sem crédito passa (process + printf + http.server).
9. Suíte backend verde (piso 288 + novos); front vitest verde nas telas tocadas.
10. v1 intocado: foto app-in-a-file continua capturando e renderizando como antes (modo srcdoc).

## Higiene herdada (para a sessão de execução)
- `runtime_command` do cobaia `6fcaff70-f3d1-4b0c-88df-d50c3eeb849e` está *"Deixe o protótipo com
  tema escuro."* — restaurar por SQL com dollar-quoting (`$cmd$…$cmd$`) antes do smoke F.10d
  (gotcha: `docker exec -i` para heredoc).
- Issues/volumes de smoke da teams-demo (c408ecc2, aa11f9e0, volumes `orq_142f1ac5_*`) ficam como
  demonstração viva — só limpar se o operador pedir.

## PRÓXIMO PASSO
Sessão de execução **F.10a → F.10b → F.10c → F.10d**, kickoff gerado por /nova-sessao ao fim desta
sessão. Backend-first com **/tdd**; **/codebase-design** é a lente do seam (par start/stop no
`ExecutionTarget` + módulo `preview.server`; exemplares: `LineCursor`, `EngineContainer`).
