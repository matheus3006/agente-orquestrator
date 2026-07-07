# ADR-0023: Preview processo/porta — servidor vivo sob demanda (F.10)

**Status:** Aceito (2026-07-05, sessão grilling `docs/handoff-grilling-processo-porta-fechado.md`)

## Contexto
O v1 do preview (ADR-0021) transporta ARQUIVO: app-in-a-file capturado no settle, o browser do board
executa. O próprio ADR-0021 nomeou o débito: processo/porta ("portas, rede, caps, TTL, teardown,
supervisão, servidor controlado por agente") era fatia própria. Esta é a fatia. O v2 transporta
PORTA: um servidor vivo executando o app que o agente construiu, o board vê a app **rodando** —
inclusive feature de backend, o furo assumido do v1 (§1: "feature de backend não ganha preview").
O v2 é **ADIÇÃO** explícita: o v1 (foto imutável, review embutida) permanece como está.

## Decisão

### 1. O servidor roda num 4º container da família — nunca no container do run
run, leitor, escritor → **servidor**: container dedicado que o **pipeline** cria, montando o volume
da Issue, **sem `credentialMounts`**, executando o comando do app. O agente autora o *conteúdo* (o
app + o comando declarado); o pipeline é dono do *processo* (criação, porta, caps, morte). O
container do run é desqualificado por construção: monta credencial do plano (um servidor exposto
com `orq_claude_creds` montado furaria a montagem seletiva do ADR-0016) e a morte dele **é** o
settle — mantê-lo vivo quebraria a semântica run/settle/review do pipeline inteiro.

### 2. Anúncio = manifest no contrato de lugar, lido no settle e persistido por run
`/workspace/.orq/preview/server.json` — `{"command": "node server.js", "port": 3000}` (mínimo
irredutível; parse estrito; porta 1-65535). Deny-by-default intacto: o agente anuncia **escrevendo
arquivo** (ADR-0001; mesmo padrão do v1). O pipeline lê no settle pelo leitor que já existe
(`readWorkspaceFile`, cap pequeno ~4KB) e **persiste por run** (V21): o board mostra "tem app
rodável" sem tocar o Docker, o review gate ganha o registro "run X declarou comando Y porta Z", e
subir o servidor usa o comando **PERSISTIDO** — o que foi declarado no settle é o que executa
(coerência com a foto do v1). Registro do manifest é best-effort legível como a captura (observação,
não participante da máquina de estados). O comando é input do agente executado **dentro do container
hermético sem credencial** — mesma classe de risco do run (a fronteira é o container, ADR-0016).

### 3. Ciclo de vida: sob demanda, TTL por inatividade, teardown por eventos
- **Sobe:** o backend, por ação do operador no board (tela dedicada, Issue ou card de Review) —
  endpoint board-only. O servidor só existe quando há um humano olhando (a review pode vir horas
  após o settle; auto-start no settle = TTL insolúvel: curto expira antes da review, longo deixa
  processo de agente rodando sem consumidor). **Um servidor por Issue, no máximo** (volume por
  Issue; runs serializados pelo lock); start idempotente (vivo = reusa).
- **Morre:** (a) **TTL de 15 min por INATIVIDADE** — renovado por heartbeat do board (~60s)
  enquanto a tela com o frame estiver aberta; reaper agendado mata sem heartbeat; (b) **checkout de
  run novo na mesma Issue derruba** (o workspace vai mutar; teardown best-effort com log legível);
  (c) **stop manual** na tela dedicada.
- **Sobrevive a restart do backend:** nome determinístico (`orq_preview_<company>_<agent>_<issue>`)
  + labels; o reaper varre `docker ps --filter label=`; heartbeats vivem em memória — pós-restart,
  container sem heartbeat conhecido ganha grace de 15 min (keepalives do board repovoam sozinhos).

### 4. Rede/porta: porta efêmera no loopback + rede sem saída + caps
- **Ingress:** `-p 127.0.0.1::<porta-do-manifest>` — o Docker aloca porta livre do host (o backend
  lê de volta via `docker port`). **Zero gestão de colisão** (o SO resolve; sem faixa reservada,
  sem registro) e **nunca exposto à LAN** (bind explícito no loopback).
- **Egress:** rede dedicada **`orq-preview`** criada uma vez com
  `com.docker.network.bridge.enable_ip_masquerade=false`: porta publicada funciona (host↔container
  é subnet local, dispensa NAT), internet **não** — o meio-termo real entre `--network none` (não
  publica porta) e bridge aberta (beacon-ável por minutos). Fallback honesto se frágil no Docker
  Desktop/macOS: bridge com a ressalva do ADR-0021 (o canal de exfiltração dominante já é o run).
- **Caps:** `--memory`/`--cpus`/`--pids-limit` modestos por property — o débito "caps" do
  ADR-0021, resolvido com flags no argv puro (testável sem daemon, padrão da casa).

### 5. Acesso do board: porta direta no iframe — origin próprio, sem proxy
Contraste estrutural com o v1: lá o perigo era HTML de agente ganhando **o origin do board** (por
isso srcdoc + sandbox sem `allow-same-origin`). No v2 o app vivo tem **origin próprio**
(`127.0.0.1:<porta>`) — o browser isola de graça.
- `PreviewFrame` ganha **modo `src`**: `<iframe src="http://127.0.0.1:<porta>"
  sandbox="allow-scripts allow-same-origin allow-forms">`. Aqui `allow-same-origin` **não é** a
  combinação letal do v1: o origin concedido é o da porta do app, não o do board — e é
  **necessário** (sem ele o origin opaco quebra o `fetch` do app à própria API, exatamente o que a
  fatia existe para entregar). Segue sem popups/top-navigation.
- **JS do app agindo como o board — não se reconstrói:** cross-origin por construção; a API exige
  `X-Board-User` (header custom → preflight CORS → bloqueado).
- **Inatividade = heartbeat do board, não tráfego HTTP:** frame montado → keepalive renova; sem
  proxy observando requests. Abrir a porta numa aba deixa de ser proibido (não é o origin do
  board); o board segue sendo a moldura canônica.

### 6. O servidor vê o workspace `:ro` + tmpfs em `/tmp` — o que roda é o que o run liquidou
Runs serializados + volume read-only ⇒ o estado servido é exatamente o que o run deixou — a
garantia da foto do v1, agora para processo. O "aprovar-vendo" (ADR-0012) aprova o trabalho do run,
não um alvo móvel; e o servidor não contamina o run seguinte. App que precisa de escrita usa
`/tmp` (tmpfs — morre com o container); instrução no conteúdo da skill (banco em `/tmp` ou
memória). O card de Review ganha "Rodar app" quando o **run sob revisão** declarou servidor
(manifest por-run + `review.run_id`); caso raro de workspace já ter avançado além do run revisado →
aviso "workspace avançou desde este run" (comparação de run_id, sem mecanismo novo).

### 7. UI: tela dedicada "Servidores" + botões contextuais + falha legível
(1) **Tela dedicada** (rota própria): servidores vivos (Issue, agente, comando, porta, TTL
restante) com matar/abrir frame; Issues com manifest (candidatas) com iniciar — a superfície de
supervisão do operador. (2) **Botões onde o fluxo vive:** "Rodar app" inline na seção Preview da
Issue e no card de Review (o reviewer não sai do card no momento aprovar-vendo). (3) **Mission
Control intocado** (cards leves, ADR-0013). (4) **Falha legível na subida:** o start espera a porta
responder (poll TCP ~10s); processo morto ou porta muda → "servidor morreu ao subir" + últimas
linhas do `docker logs` (espírito do `CredentialFailureSignal`).

### 8. Seam e estado: declaração no banco, runtime no Docker, TTL na memória
- **`ExecutionTarget` ganha o par start/stop de servidor** — mesmo eixo LOCAL×DOCKER do run, do
  leitor e do escritor (um eixo, um seam — 3ª aplicação da regra). DOCKER: `docker run -d` com
  nome determinístico, labels, rede `orq-preview`, `:ro`+tmpfs, caps, porta efêmera — argv puro
  testável sem daemon. LOCAL: spawn de processo no workDir (dev-only, mesma classe de risco do run
  LOCAL) — **dois adapters reais no dia 1**; smoke sem crédito: `process` + printf do manifest +
  `python3 -m http.server`.
- **Migration V21 `preview_server_manifest`** append-only por run: `company_id · issue_id ·
  agent_id · run_id · command · port · created_at`. Sem dedup (linhas minúsculas; o vigente é o
  último da Issue). Banco guarda só a DECLARAÇÃO.
- **Runtime mora no Docker:** vivo/porta/idade saem de `docker ps`/`inspect` por label. Tabela de
  servidores vivos = estado especular (banco dizendo "vivo" de container morto — a mentira que o
  residual (c) do ADR-0016 rejeitou).
- **Módulo profundo `preview.server`** por cima do seam: manifest + start idempotente + poll TCP +
  TTL/heartbeat + reaper + teardown-no-checkout + tail de log, atrás de interface pequena
  (`startFor/stopFor/heartbeat/listLive`). O executor toca um ponto (registrar manifest no settle;
  derrubar no checkout).

## Alternativas rejeitadas
- **Manter o container do run vivo** — credencial montada ao lado de porta exposta + a morte do
  run É o settle; quebraria a semântica que o pipeline inteiro assume.
- **Servidor estático fixo do pipeline** (`npx serve` sem comando do agente) — seguro, mas é v1
  com outro transporte: não entrega processo (Express, API, SSR) — o que a fatia existe pra entregar.
- **Manifest lido a quente no start** — o que executa poderia divergir do que o run declarou; cada
  start pagaria leitura hermética extra.
- **Convenção sem manifest** (detectar package.json → npm start) — heurística no lugar de intenção;
  rejeição herdada do anúncio do v1 (ADR-0021: mtime/dir da skill).
- **Auto-start no settle** — TTL insolúvel (curto: morto quando o reviewer chega; longo: processo
  de agente rodando horas sem ninguém olhando).
- **Sem TTL (morte só manual)** — servidor esquecido = processo de agente perpetuamente exposto;
  supervisão era exatamente o débito nomeado pelo ADR-0021.
- **Faixa de portas fixa gerida pelo backend** — registro + colisão + limpeza; o SO entrega isso
  de graça na porta efêmera.
- **Bridge aberta** (egress pleno) — servidor vivo por minutos é mais beacon-ável que run de 3min;
  NAT-off entrega porta publicada sem egress pelo mesmo mecanismo.
- **Proxy pelo backend** — serviria HTML navegável do origin do control-plane (fura o inegociável
  #2 do ADR-0021) e exigiria proxy fiel de websocket/HMR/SSE; o único ganho real (idle por
  tráfego) tem substituto mais barato (heartbeat).
- **Volume `:rw`** — o reviewer veria estado que o run não produziu; o servidor contaminaria o run
  seguinte; morre a garantia "aprovo o que o run fez".
- **Tabela de servidores vivos no banco** — estado especular; reconciliação permanente.
- **Seam paralelo (`PreviewServerLauncher` próprio)** — duplicaria o dispatch LOCAL×DOCKER;
  rejeitado pela 3ª vez (ADR-0021, ADR-0022).

## Recorte explícito (fora do v1-da-fatia)
1. **Multi-serviço/compose/banco provisionado** — 1 processo, 1 porta, 1 container (app usa SQLite
   em `/tmp` ou memória, via skill).
2. **HTTPS/domínio/exposição além do loopback** — premissa single-node explícita (ver ressalvas).
3. **Install em serve-time** — deps entram no workspace durante o RUN (a rede sem saída força por
   construção).
4. **HMR/watch como contrato** — pode funcionar, não é prometido.
5. **Idle por tráfego real / métricas de request** — o mecanismo é o heartbeat do board.
6. **Logs em streaming no board** — só o tail na falha de subida.
7. **Multi-porta por app · persistência do estado do app entre sessões de preview** (tmpfs morre
   por design).
8. **GC/retenção de manifests · edição do manifest no board** (espírito do recorte D do v1).

**DoD além do código** (espelho da F.9b): **skill nova no catálogo** carregando o contrato do v2 —
app com servidor, deps instaladas no run, escrita só em `/tmp`, publica `server.json`. Sem ela o
manifest nunca nasce em uso real (a lição do F.9d). Codex ganha de graça quando o login existir (o
contrato é do workspace, não do motor).

## Ressalvas honestas
- **Single-node:** board acessado de OUTRA máquina não enxerga `127.0.0.1` do host do operador —
  o preview vivo pressupõe operador e backend na mesma máquina (verdade do self-hosted atual).
- **Agente-para-core via gateway/`host.docker.internal` é buraco PRÉ-EXISTENTE** (o container do
  run tem rede e o `X-Board-User` não é segredo). O v2 não o cria nem o amplia (rede sem NAT), mas
  fica registrado: a defesa real é auth de verdade no core se o modelo de deploy mudar.
- **`enable_ip_masquerade=false` no Docker Desktop/macOS** — comportamento a validar no smoke;
  fallback consciente para bridge (decidido, não improvisado). **VALIDADO no smoke F.10d
  (2026-07-05): no Docker Desktop/macOS o egress NÃO é bloqueado** (fetch a 1.1.1.1 e a hosts
  externos respondeu de dentro do container — a pilha de rede do Desktop faz NAT próprio, por
  fora do iptables da bridge). A opção fica na rede (em Linux nativo, alvo do self-hosted real,
  ela vale); no macOS o comportamento degrada para o fallback bridge com a ressalva herdada do
  ADR-0021: o canal de exfiltração dominante já é o run, que tem rede plena por design.
- **LOCAL executa o comando do manifest no host** — mesma classe de risco do run LOCAL (dev-only,
  ADR-0016); registro em memória + destroy no shutdown, sem reaper de PID órfão.

## Relaciona
ADR-0001 (deny-by-default → manifest é arquivo, não API) · ADR-0004 (executor intocado; settle
registra, checkout derruba) · ADR-0010 (módulo `preview.server` profundo; argv puro) · ADR-0012 (o
card de Review é o consumidor; `review.run_id` liga o manifest) · ADR-0013 (fetcher/board-only;
Mission Control leve) · ADR-0016 (fronteira = container; 4º container SEM credencial; residual (c)
reaplicado ao estado de runtime) · ADR-0021 (o v1 que permanece; débito processo/porta quitado
aqui; inegociável #2 preservado pela recusa do proxy) · ADR-0022 (escritor — o irmão do seam).
