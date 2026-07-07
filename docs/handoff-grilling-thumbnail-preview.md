# Handoff — GRILLING: thumbnail do preview v2 (fatia FECHADA, pronta para execução)

> Origem: sessão de PLANEJAMENTO (grilling) 2026-07-05, logo após a F.12 (GC/retenção — ADR-0026).
> Fatia escolhida pelo operador no menu da Fase 2 (era o DEFAULT proposto, com o argumento objetivo
> que a F.12 acabou de tornar visível). Plano FECHADO pelo grill — a sessão de execução **NÃO
> re-planeja e NÃO reabre ADRs 0001–0026**. Repo SEM git em
> `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/` — NÃO commitar.
> Piso backend: **357/357** (o teto que a F.12 deixou; nunca baixar). **Front RECEBE adição** (ao
> contrário da F.12): a seção Preview da Issue e a tela Servidores ganham um `<img>`; o Mission
> Control fica **intocado** (cards leves, ADR-0013 — a linha sustentada pela 3ª vez).
> Mudança em seam provado (`ExecutionTarget`) é **ADITIVA** (método default + TDD; molde ADR-0025/0026).
> Evidência: mapeamento do código por 2 agentes (2026-07-05, §E) — **definitivo: zero renderizador
> na casa** — + o reenquadramento da rejeição explícita da ADR-0021.

## §A — O que é a fatia

**O 5º container da família: o shot — o primeiro RENDERIZADOR da casa.** Hoje o preview v2
(PreviewServer, ADR-0023) é um servidor VIVO e efêmero: o board vê a app rodando, mas **nada visual
persiste** (confirmado no mapeamento — o único registro durável do v2 é o processo). A F.12 tornou o
buraco AGUDO: agora que o `WorkspaceReaper` coleta o workspace após 7 dias do terminal, a "app viva"
some **sem deixar imagem** — a foto v1 (`preview_artifact`, HTML, eterna) só existe para o transporte
de ARQUIVO (srcdoc); o transporte de PORTA não tem foto.

A fatia: capturar um **PNG durável do app v2 enquanto o servidor está vivo** (janela pré-GC),
renderizando num **container dedicado com Chromium** (o board não pode — o sandbox/cross-origin que
protege o preview proíbe a captura), guardá-lo em `bytea` no banco (sobrevive ao GC, como a foto v1),
e mostrá-lo na Issue e na tela Servidores — inclusive **no lugar do "Rodar app" morto** depois que o
GC levou o workspace. Escopo **só v2** (o v1 já tem registro durável; seu "thumbnail" é apresentação,
recorte §H). Nenhum outro artefato é tocado; o agente **não declara nada novo** (deny-by-default
intacto — o shot é observação pura do pipeline sobre o servidor que o pipeline mesmo subiu).

## §B — DECISÕES-ÁTOMO (racional + alternativas rejeitadas)

### B1. Natureza: aceitar o browser — mas como CONTAINER, não como dependência do JVM
Um raster exige um renderizador, e a ADR-0021 **matou o thumbnail exatamente aqui** (linha 102:
"Thumbnail/screenshot no badge — headless browser como dependência do backend"). O contexto mudou: o
v2 existe (ADR-0023) e o GC coleta (ADR-0026), então há um argumento objetivo de durabilidade que
antes não existia. O renderizador entra como um **5º container da família** (run, leitor, escritor,
servidor → **shot**), hermético e sem credencial — a rejeição da 0021 continua de pé para o **JVM**
(o backend segue transporte de bytes, nunca renderizador), mas cai para um container irmão.
**Racional**: a filosofia "o browser do board executa" (0021 §1) protege o board de rodar HTML de
agente; ela não proíbe um container isolado de renderizar. O shot é a extensão natural da família de
containers que a 0016/0023 já estabeleceram.
**Rejeitadas**: **captura pelo board** (canvas/screenshot do iframe) — o sandbox que torna o preview
seguro PROÍBE ler os pixels nos dois modos: v1 `srcdoc` é `allow-scripts` sem `allow-same-origin` →
origin opaco, o pai não lê `contentDocument`; v2 `src` é `127.0.0.1:porta` → cross-origin, o pai não
lê os pixels; a única forma seria renderizar o HTML no origin do board = o ato letal que a 0021
proíbe. **Renderizador no JVM** (a rejeição original da 0021 — fica de pé). **Skill publica snapshot
estático** (o v2 passaria a escrever um `index.html` junto do `server.json`) — o "registro" seria um
arquivo falso (shell sem o servidor vivo por trás; a API que ele chama morreu com o workspace),
contradizendo o motivo de o v2 existir (ADR-0023 §Contexto: o app é um servidor, não um arquivo).

### B2. Momento: captura no SERVER-UP, 1×/run, obrigatoriamente na janela pré-GC
O shot fotografa **enquanto o servidor está vivo**, uma vez por run. Amarra dura herdada da F.12:
depois do GC (7 dias do terminal) o `startFor` já responde "workspace coletado" (ADR-0026 §B4) —
**não há servidor para fotografar**; a captura é obrigatoriamente um ato da janela pré-GC.
**Racional**: reusa o único momento em que a app está de pé (o operador clicou "Rodar app"), sem
custo de subir nada extra; captura o estado que o operador de fato viu; a chave por-run espelha o
`preview_server_manifest` (por-run, ADR-0023 §2).
**Rejeitadas**: **captura no settle** — todo run v2 SUCCEEDED subiria o servidor headless
(start→shot→kill) para garantir o registro arquival no dia 0; garante mais, mas paga o start
**completo** do servidor + browser a cada settle (hoje o settle do v1 é um `cat`; isso seria subir a
app inteira toda vez) e re-introduz o "subir o app no settle" que a 0023 recusou por design (auto-start
= TTL insolúvel). **Botão explícito** ("capturar thumbnail" manual) — manual demais para ser um
registro durável confiável; o operador esquece e o GC leva.
**Recorte consciente aceito**: app que o operador **nunca subir** dentro da janela de 7d não ganha
thumbnail. Defensável: o registro durável espelha o que foi de fato trazido à vida.

### B3. Imagem-shot: Chromium single-binary, PINADO, num Dockerfile fino da casa
`infra/` ganha uma imagem dedicada (não dá para reusar adapter — nenhum tem browser, §E): `FROM`
uma base de chromium-headless **pinada por tag** (defesa contra `--bare`, como o EngineContainer da
0016) + um comando único `chromium --headless --screenshot --virtual-time-budget=<ms>
--window-size=1280,800 --hide-scrollbars <url>`. Sem runtime node, sem árvore de deps npm.
**Racional**: mais leve e mais consistente com o padrão pinado da casa; o `--virtual-time-budget`
cobre o "esperar a SPA renderizar" (avança o relógio virtual até a rede/timers quietarem, dentro do
teto) sem precisar de Playwright. A base pinada é a mesma disciplina das imagens de adapter.
**Rejeitadas**: **node + puppeteer/playwright** (`waitUntil: networkidle`, viewport, retries — captura
de SPA mais robusta, MAS runtime node + deps npm = superfície de supply-chain maior e imagem gorda;
registrado como upgrade FUTURO se o `virtual-time-budget` provar insuficiente, §H); **imagem pública
batteries-included** (browserless/mcr-playwright — menos manutenção, MAS dep externa gorda e opaca,
mais difícil de auditar que um Dockerfile nosso `FROM` base pinada); **reusar a imagem do adapter**
(zero browser).

### B4. Seam: método ADITIVO no `ExecutionTarget` (`screenshotServer`), 2 adapters reais
O shot é uma operação **aditiva** no `ExecutionTarget` — `screenshotServer(...)` → `ThumbnailCapture
{CAPTURED(bytes) | UNREACHABLE | RENDER_FAILED | CAP_EXCEEDED}` — com DOCKER e LOCAL no dia 1, argv
puro testável sem daemon (molde idêntico ao `removeIssueWorkspace` da F.12 e ao par start/stop de
servidor da 0023).
**Racional AFIADO (a rede, não a "regra 3×")**: o shot **varia de verdade** no eixo LOCAL×DOCKER por
*reachability*, não por simetria decorativa. Um servidor **LOCAL** faz bind em `127.0.0.1` (loopback,
ADR-0023 §4) — **inalcançável de dentro de um container** — então exige o **Chromium do host**. Um
servidor **DOCKER** é alcançável por um container irmão na rede `orq-preview`, pelo **nome
determinístico do container** na **porta do manifest** (`http://orq_preview_<c8>_<a8>_<i8>:<port>/`),
sem tocar o host-port efêmero. DOCKER: shot-container hermético (`docker run --rm --network
orq-preview <imagem-shot> sh -c 'chromium … --screenshot=/tmp/s.png … && cat /tmp/s.png'` — captura
os bytes pelo stdout, exatamente como o leitor da 0021). LOCAL: spawn de Chromium no host contra
`127.0.0.1:<hostport>` (dev-only, mesma classe de risco do run/servidor LOCAL — 0016).
**Rejeitadas**: **seam paralelo** (`Screenshotter` próprio) — seria a **4ª recusa** do mesmo padrão
(0021, 0022, 0023 já recusaram duplicar o dispatch LOCAL×DOCKER); **single-mechanism always-container**
(um shot-container serviria os dois) — NÃO alcança o servidor LOCAL (bind em loopback, fora do
alcance de um container); **always-host-chromium** (Chromium do host serve os dois via porta
publicada) — furaria o isolamento do DOCKER (renderizar HTML de agente no host, sem container) e
imporia Chromium no host como dependência de produção.
**Ressalva honesta**: o adapter LOCAL exige Chromium instalado no host do dev; ausente → `RENDER_FAILED`
best-effort (o smoke da 0023 já é dev-only). O caminho canônico é o DOCKER; o LOCAL é o exercício
barato do seam.

### B5. Disparo: SWEEP agendado sobre servidores VIVOS (world-driven, best-effort)
`PreviewThumbnailCapturer` — módulo profundo, par do `PreviewServerSupervisor`: `captureOnce()`
público testável, agendado no `HeartbeatScheduler` (irmão do `reapOnce`/`reapWorkspaces`).
**World-driven**: 1×/ciclo (~60s) lista os servidores **VIVOS** (`docker ps --filter label=` — a
mesma listagem que o reaper da 0023 já faz); para cada um **deriva o `run_id`** do **último manifest
da Issue** (`preview_server_manifest`, V21 — o mesmo que o `startFor` usa para escolher o comando) +
a porta; **pula se já existe linha de thumbnail para aquele `run_id`** (guarda de idempotência
por-run — mais limpa que dedup por hash, que rasters quase nunca satisfazem); senão chama
`screenshotServer(...)`, recebe os bytes, persiste.
**Best-effort de verdade**: falha de um servidor **nunca** aborta o sweep (segue para os demais);
falha → evento `THUMBNAIL_CAPTURE_FAILED` no `activity_log` (detail só-IDs — ADR-0009) com ator
**`ActorType.SYSTEM`** (reusa o ator que a F.12 criou — o shot é ato do PIPELINE, não do board/agente);
retenta a cada ciclo enquanto o servidor viver (o **TTL de 15min** da 0023 limita a insistência
sozinho). O invariante mais importante da fatia: **o shot NÃO atrasa nem quebra a subida** — o
operador clicou para VER; a captura é observação, não participante da máquina de estados (0021 §6,
0023 §2).
**Rejeitadas**: **inline-async após o `startFor`** (retorna o servidor e dispara em background) —
acopla o shot ao request HTTP, exige executor async novo no supervisor, e a falha não tem retry
natural (o run perde a janela até o próximo server-up manual); **inline-bloqueante no start** (o start
espera ~5s o shot antes de responder) — atrasa o operador ver o app (UX ruim) e vincula a subida ao
sucesso do shot, furando o invariante.

### B6. Storage: tabela nova `preview_server_thumbnail` (V22), `bytea`, por-run
Migration **V22** append-only: `id · company_id · issue_id · agent_id · run_id · content` **`bytea`**
`· content_hash · size_bytes · created_at`. Por-run; o vigente para exibição é o **último da Issue**
(espelha o `preview_server_manifest` V21). Cap **2MB** com falha legível (reusa a constante/semântica
`CAP_EXCEEDED` da foto v1). `content_hash` guardado para integridade/cache (a idempotência real é a
guarda por-`run_id` do §B5, não o hash).
**Racional**: precedente FORTE — o v2 **já ganhou tabela própria** para o manifest (V21), separada da
foto v1 (V20); um PNG binário nascido de servidor vivo por-run não pertence à mesma linha que um HTML
por-Issue. `bytea` é o tipo nativo do Postgres para binário. Guardar no banco (não no filesystem) é a
escolha provada da casa (0021 recusou `docker cp → cache filesystem`: backend stateful, backup manual,
path-traversal); a foto v1 já mora no Postgres — o thumbnail segue.
**Rejeitadas**: **estender `preview_artifact`** (coluna `bytea` nullable + discriminador `kind`) —
mistura dois artefatos muito diferentes (TEXT/HTML por-Issue vs PNG/binário por-run) numa linha, e o
versionamento por-Issue da foto não alinha 1:1 com a captura por-run; **base64 em coluna TEXT** —
+33% de tamanho, desperdiça o cap, mentira de tipo (binário como texto).

### B7. Serving + UI: PNG inerte → serving simples; casa na Issue e Servidores, cards intocados
- **Serving MAIS simples que a foto v1**: um PNG é **inerte** (não executa). Endpoint board-only
  `GET /api/companies/{companyId}/preview-server-thumbnails/{id}/content` → `Content-Type: image/png`
  + `X-Content-Type-Options: nosniff` + `Cache-Control: private, max-age=…, immutable` (imutável por
  construção). O front busca os bytes pelo **mesmo fetcher autenticado** (o `<img src>` não manda
  header custom → fetch autenticado → blob URL → `<img>`), **sem sandbox, sem iframe** (o que a foto
  HTML exige e o PNG dispensa). Metadados (lista de versões por Issue, sem os bytes) num endpoint
  separado, como o `PreviewArtifactController`.
- **UI**: o PNG aparece na **seção Preview da Issue** (primário) e na **tela Servidores** (candidatas
  / vivos). O momento matador: depois do GC, quando o "Rodar app" já responde "workspace coletado"
  (ADR-0026 §B4), **o thumbnail é o que aparece no lugar do botão morto** — a F.12 fechada VENDO ao
  contrário (o registro durável sobrevive à coleta). **Mission Control INTOCADO** (cards leves,
  ADR-0013 — a linha que a 0021 §5 "só badge" e a 0023 §7 "intocado" já sustentaram; esta é a 3ª).
- **v1 sem raster** (recorte §H): a foto v1 é eterna; seu "thumbnail" é apresentação (escalar a foto),
  sem argumento de durabilidade e com custo de N iframes cadillac vivos na grid de polling.
**Rejeitadas**: **thumbnail no card do Mission Control** (fura "cards leves" 3× sustentada + peso na
grid de 2s); **raster uniforme do v1** (shot no settle do file:// — feature completa e consistente,
MAS sem argumento de durabilidade e inflando a fatia); **sandbox/srcdoc para o PNG** (desnecessário —
imagem inerte). Risco residual aceito: CVE de decoder de imagem do browser — mesma classe de risco de
ver qualquer output de agente, aceito como o CDN da 0021.

### B8. Deny-by-default: o agente não declara NADA novo
O agente já declara o `server.json` (0023 §2); o shot é **observação pura do pipeline** sobre o
servidor que o próprio pipeline subiu. Zero contrato de lugar novo, zero canal agente→core (a foto v1
também é observada, não declarada — 0021 §3). A superfície de deny-by-default não muda.

### B9. Registro: ADR-0027
`docs/adr/0027-thumbnail-preview-servidor-vivo.md`, status **Aceito**, com B1–B8, riscos, recortes
(§H) e a evidência (§E). O switch exaustivo de `ActorType` já contempla `SYSTEM` (F.12/ADR-0026 §B6) —
conferir os pontos de switch ao emitir o novo `THUMBNAIL_CAPTURE_FAILED`. Relaciona: ADR-0016 (seam
`ExecutionTarget`, EngineContainer pinado), 0021 (a foto v1 que permanece; a rejeição do thumbnail que
esta fatia reenquadra), 0023 (PreviewServer vivo — a causa do buraco e o alvo do shot; `orq-preview`,
nome determinístico, manifest por-run), 0025/0026 (padrão de método ADITIVO no seam), 0009 (detail
só-IDs), 0013 (cards leves).

## §C — DoD (verificável, nível VENDO)

1. **ADR-0027** escrito (Aceito).
2. **Imagem-shot** no `infra/` (`FROM` base chromium-headless pinada), com um smoke manual do argv
   (renderiza um HTML/porta trivial → PNG não-vazio).
3. **TDD red→green**:
   - `DockerExecutionTargetArgsTest` +casos: o argv do `screenshotServer` (rede `orq-preview`, imagem
     pinada, URL `http://orq_preview_<c>_<a>_<i>:<port>/`, flags de viewport/virtual-time), classify
     de resultado (`CAPTURED`/`UNREACHABLE`/`RENDER_FAILED`/`CAP_EXCEEDED`) — PUROS, sem daemon.
   - `PreviewThumbnailCapturerTest` (molde do `WorkspaceReaperTest`, fakes em memória): captura
     servidor vivo cujo run não tem thumbnail; **NÃO** captura: run que já tem linha (idempotência
     por-run), servidor não-vivo; deriva `run_id` do último manifest; falha de um servidor →
     `THUMBNAIL_CAPTURE_FAILED` ator SYSTEM + sweep segue nos demais (best-effort não-abortante); cap
     excedido → não persiste, evento legível.
   - Persistência: `preview_server_thumbnail` (bytea) round-trip; último-da-Issue vence.
4. Suíte backend **≥ 357 + novos, verde** (`cd backend && mvn -o test`); contratos provados intocados
   (`.claude/skills`, `.orq/preview/*`, `CodexStreamParser`, posture ADR-0025; o método novo no seam é
   aditivo — os 4 fakes de teste compilam intactos).
5. **Prova VIVA na sujeira real** (rebuild jar → `:8080`):
   - Subir uma app v2 **FRESCA** (as 3 antigas — 5f207cc0, e5946ef0, 96c1d2ea — tiveram o workspace
     COLETADO na prova da F.12; estão mortas). Caminho garantido: o smoke da 0023 (`process` + printf
     do `server.json` + `python3 -m http.server`) ou a skill v2 do catálogo, se presente.
   - Clicar "Rodar app" → servidor sobe → em ≤1 ciclo do sweep, **1 linha em
     `preview_server_thumbnail`** (bytea não-vazio, `run_id` correto);
   - **VENDO no board**: o **PNG aparece** na seção Preview da Issue e na tela Servidores;
   - forçar `retention-days=0` TEMPORÁRIO → o `WorkspaceReaper` coleta o workspace → "Rodar app"
     responde "workspace coletado" **E o thumbnail CONTINUA aparecendo** (registro durável sobrevive
     ao GC — o coração da fatia);
   - falha de captura sintética (app que morre / porta que não responde) → `THUMBNAIL_CAPTURE_FAILED`
     ator SYSTEM no feed, subida do servidor **intocada**;
   - **restaurar `retention-days=7`** + rebuild/restart final (estado de repouso = default).
6. **Front**: a seção Preview da Issue e a tela Servidores renderizam o `<img>` pelo fetcher
   autenticado (blob URL); Mission Control **inalterado** (diff mostra cards intocados).
7. §RESULTADO neste handoff; **ADR-0015** respeitado (cruzou ~50-60% → destilar e recomeçar).

## §D — Threads vivos herdados (pendências do OPERADOR, não tarefa de agente)

- **Volume docker anônimo `6bea9500b758a24d31250ba6ab91926df97bae68eef79f3600dfdb025c0b3bea`**
  (49MB, links=0) — herdado da F.12: é um **diretório de dados Postgres** (montado `:ro` e inspecionado
  na F.12; `PG_VERSION`/`base/`/`pg_wal`, de 01/07). Deixado intacto por ser irreversível apagar.
  **Decisão do operador**: remover (`docker volume rm 6bea9500…`) só se confirmar que não é dado que
  ele quer, ou manter. NÃO é tarefa da execução desta fatia.
- **Aprovação `da239e6d` do Atlas** — segue **PENDING** na fila do operador (F.12 §RESULTADO). Não
  decidir por ele.

## §E — Evidência (mapeamento do código, 2 agentes, 2026-07-05) + gap confesso

- **Definitivo: zero renderizador na casa.** `backend/pom.xml` = Spring/JPA/Flyway (nenhuma lib de
  imagem/browser); `frontend/package.json` = React/Next/TanStack (nada de canvas/screenshot); as duas
  imagens de adapter (`infra/.../claude-code`, `infra/.../codex`) são Node slim + CLI, **sem Chromium**.
  Um raster exige um browser **novo** — o fato que a ADR-0021 (linha 102) usou para matar o thumbnail e
  que esta fatia reenquadra (browser como CONTAINER, não JVM).
- **O board não pode capturar** — confirmado nos dois modos do `PreviewFrame`: v1 `srcdoc`
  (`allow-scripts` sem `allow-same-origin`, origin opaco) e v2 `src` (`127.0.0.1:porta`, cross-origin).
  A isolação que protege proíbe a leitura de pixels.
- **v2 sem registro visual durável** — confirmado: `PreviewServerSupervisor` guarda só a DECLARAÇÃO
  (`preview_server_manifest`, V21); o vivo mora no Docker e morre por TTL. A foto (V20) é só do v1.
- **Superfície de exibição**: `AgentCard` mostra badge de texto ("preview v{n}"), sem imagem; a Issue
  (`IssuePreviewSection`) e a tela Servidores são os lugares naturais do `<img>`.
- **Gap confesso (decisão nossa, não pesquisa)**: **NÃO** rodei deep-research de mercado nesta sessão
  (o design foi forçado por restrições DURAS do código — sem renderizador, cross-origin, loopback —
  não por evidência de mercado). Prior-art consistente mas **não verificada formalmente**:
  deploy-preview screenshotters (Vercel/Netlify og-image via headless chromium) usam exatamente o
  padrão "browser num container hitando a porta". Se o operador quiser o mesmo rigor da F.12, uma
  deep-research curta antes de B3 é o ponto de entrada — mas nenhum átomo depende dela.

## §G — Ambiente (fatos) + gotchas de sangue

- Backend jar no `:8080` (rebuild = pid NOVO, conferir `lsof -iTCP:8080`; `cd backend && mvn -o
  -DskipTests package`). Front: launch.json "frontend" (`:3000`).
- DB: container **`agente-orquestrator-db-1`** (sem 'd'); `docker exec -i agente-orquestrator-db-1
  psql -U orquestrador -d orquestrador` (user/db COM 'd'; `-i` obrigatório para heredoc).
- Migrations em `backend/src/main/resources/db/migration/`, esquema `V<n>__…sql`; **maior hoje = V21**
  → esta fatia é **V22**. ADRs em `docs/adr/`; **maior hoje = 0026** → esta é **0027**.
- Preview v2: rede docker **`orq-preview`** (0023); servidor com nome determinístico
  `orq_preview_<company8>_<agent8>_<issue8>` + labels; manifest `preview_server_manifest`
  (`company_id, issue_id, agent_id, run_id, command, port, created_at`).
- Molde de captura existente a espelhar: `AdapterRunExecutor` (settle) → `PreviewArtifactCapturer`
  (best-effort, nunca lança) → `ExecutionTarget.readWorkspaceFile` → `DockerExecutionTarget`
  (`docker run --rm --network none … cat`, stdout = bytes). O shot é o irmão que **executa** em vez de
  `cat`. Serving: molde do `PreviewArtifactController` (board-only, `nosniff`, cache imutável).
- `ActorType.SYSTEM` existe desde a F.12 (actor_id NULL); `CompanyAccessGuard` tem `case SYSTEM ->
  false`. `activity_log` é append-only por trigger (V8) — só INSERT.
- macOS **Darwin**: para o LOCAL, o Chromium do host hita `127.0.0.1:<hostport>`; `host.docker.internal`
  NÃO alcança um servidor LOCAL bindado em loopback (por isso o LOCAL usa host-chromium, não container
  — §B4). `gtimeout` (não `timeout`); probes de CLI no host sempre `</dev/null`. Egress no
  Docker Desktop/macOS vaza (0023 §Ressalvas) — herdado, não ampliado (o shot é efêmero e sem
  credencial; o canal de exfiltração dominante já é o run/servidor).
- Board user `b0a7d000-0000-4000-8000-000000000001` (header `X-Board-User` literal). Companies com
  histórico: teams-demo `142f1ac5-…` e `b92cb312-…`.

## §H — Fora do escopo / recortes conscientes

- **Raster do v1 / cards uniformes** (shot no settle do `file://`) — sem argumento de durabilidade (a
  foto v1 é eterna); fatia própria se a apresentação visual dos cards virar dor.
- **Thumbnail no Mission Control** — cards leves, 3× sustentado.
- **Viewport mobile / múltiplos tamanhos** — um shot canônico 1280×800; a moldura device escala no
  front.
- **Captura animada / scroll-stitch / múltiplas telas** — 1 shot above-the-fold.
- **Robustez de SPA além do `virtual-time-budget`** — se provar insuficiente, upgrade da imagem-shot
  para node+puppeteer (`networkidle`) é evolução registrada (B3), não v1.
- **App nunca subido na janela** não ganha thumbnail (B2) · **run sem Issue** não captura (igual à
  foto v1, `issueId != null`).
- **GC/retenção do próprio thumbnail** — as rows **FICAM** (é exatamente o registro que deve sobreviver
  ao GC de workspace; `preview_server_thumbnail` é o irmão durável da foto v1). Quando o volume de rows
  doer, é **cap+graduação** (mesma conversa da retenção de `preview_artifact`/`run_events` — recorte da
  F.12 §H), outra fatia. O `WorkspaceReaper` coleta workspaces (volumes/dirs), **nunca** rows.
- **Deep-research de mercado** (§E) — não rodada; opcional antes da execução.
- Não reabrir ADRs 0001–0026; parser e contratos de lugar não mudam.

## §I — Ordem de execução sugerida (TDD)

1. **ADR-0027** (B9).
2. **Imagem-shot** no `infra/` (`FROM` chromium-headless pinado; comando `chromium --headless
   --screenshot`); smoke manual do argv (HTML/porta trivial → PNG não-vazio).
3. **Seam**: `screenshotServer` ADITIVO no `ExecutionTarget` — `DockerExecutionTargetArgsTest` (argv
   puro: rede `orq-preview`, URL por nome-do-container:porta-do-manifest, viewport, classify de
   resultado) red→green; adapter LOCAL (host-chromium, ressalva de ausência).
4. **V22** `preview_server_thumbnail` (bytea) + entidade/repo (round-trip, último-da-Issue).
5. **`PreviewThumbnailCapturer.captureOnce()`** red→green (molde `WorkspaceReaper`): lista vivos,
   deriva `run_id` do manifest, guarda por-run (skip se existe), best-effort não-abortante, evento
   `THUMBNAIL_CAPTURE_FAILED` ator **SYSTEM** (conferir exaustão de switch).
6. **Agendamento** no `HeartbeatScheduler` (`@Scheduled`, property de intervalo).
7. **Serving**: controller board-only (`image/png`, `nosniff`, cache imutável) + endpoint de
   metadados; **front**: fetcher→blob→`<img>` na seção Preview da Issue e na tela Servidores (Mission
   Control intocado).
8. `mvn -o test` **≥357+novos** → rebuild → `:8080`.
9. **Prova viva §C.5** (v2 fresca → thumbnail VENDO → `retention-days=0` → GC coleta → thumbnail
   SOBREVIVE VENDO → restaurar 7).
10. **§RESULTADO** neste handoff + mensagem `/nova-sessao` da próxima fatia.

---

## Mensagem /nova-sessao (copiar para iniciar a EXECUÇÃO)

```
/nova-sessao

Executar a fatia THUMBNAIL DO PREVIEW v2 do agente-orquestrator — plano FECHADO no grill em
docs/handoff-grilling-thumbnail-preview.md. Esta é uma sessão de EXECUÇÃO (TDD red→green), NÃO de
replanejamento: NÃO reabrir ADRs 0001–0026 nem os contratos provados (.claude/skills, .orq/preview/*,
CodexStreamParser, posture/ADR-0025, seam ExecutionTarget). Repo SEM git em
/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/ — NÃO commitar.

## Skills a usar
- /tdd — red→green por fatia vertical (é a espinha da execução).
- /codebase-design — o shot como módulo profundo atrás de seam pequeno; método ADITIVO no
  ExecutionTarget (não seam paralelo).
- /nova-sessao — ao cruzar ~50-60% do contexto (ADR-0015): destilar §RESULTADO e recomeçar.

## Leia primeiro (ordem)
1. docs/handoff-grilling-thumbnail-preview.md — o plano fechado (§A o quê, §B decisões-átomo B1–B9
   com racional + rejeitadas, §C DoD VENDO, §G ambiente/gotchas, §H recortes, §I ordem TDD).
2. docs/adr/0021 e 0023 — a foto v1 (que permanece) e o PreviewServer v2 (o alvo do shot).
3. docs/adr/0026 + docs/handoff-grilling-gc-retencao-workspaces.md — o GC que criou o argumento e a
   amarra pré-GC; o ator ActorType.SYSTEM que esta fatia reusa.

## A fatia (o essencial)
O 5º container da família: um shot com Chromium (imagem pinada no infra/) fotografa o app v2 VIVO
(sweep agendado sobre servidores vivos, 1×/run, janela pré-GC — B2/B5), pelo seam ExecutionTarget
(screenshotServer ADITIVO, DOCKER-container/LOCAL-host-chromium — B4), guarda o PNG em
preview_server_thumbnail (V22, bytea, por-run — B6), e serve inerte (image/png+nosniff→<img>) na
Issue e na tela Servidores (B7). Mission Control INTOCADO. O agente não declara nada (deny-by-default,
B8).

## DoD VENDO (o coração)
Subir uma app v2 FRESCA (as 3 antigas foram coletadas na F.12) → sweep captura → PNG aparece na Issue
e em Servidores → forçar retention-days=0 → o WorkspaceReaper coleta o workspace → "Rodar app"
responde "workspace coletado" MAS o thumbnail CONTINUA aparecendo (o registro durável sobrevive ao
GC) → restaurar retention-days=7 + rebuild final. Suíte ≥357+novos verde. Seguir a ordem TDD §I.

## Pendências do OPERADOR (não decidir por ele)
- Volume anônimo 6bea9500… (pgdata órfão, 49MB) — deixado intacto na F.12; ele decide remover/manter.
- Aprovação da239e6d do Atlas — segue PENDING na fila dele.
```

---

## §RESULTADO (execução F.13 — 2026-07-05, FATIA FECHADA, VENDO provado)

**Suíte backend: 375 verde** (piso 357 + 18 novos, 0 falhas). Front: typecheck + lint limpos, 194
testes verdes. Contratos provados INTOCADOS (`.claude/skills`, `.orq/preview/*`, `CodexStreamParser`,
posture/ADR-0025). O método novo no seam é ADITIVO (default `screenshotServer` + TDD; os 4 fakes de
teste compilaram intactos). Mission Control INTOCADO (nenhum arquivo dele no diff).

### O que foi construído (TDD red→green, ordem §I)
- **ADR-0027** (`docs/adr/0027-thumbnail-preview-servidor-vivo.md`, Aceito) — B1–B9, riscos, §H, §E.
- **Seam ADITIVO** `ExecutionTarget.screenshotServer(ScreenshotSpec) → ThumbnailCapture
  {CAPTURED|UNREACHABLE|RENDER_FAILED|CAP_EXCEEDED}` (default UNREACHABLE, fakes intactos). DOCKER
  `buildScreenshotArgs` (argv puro, rede `orq-preview`, nome-do-container:porta-do-manifest) +
  `ThumbnailCapture.classify` (4 estados, compartilhado); LOCAL host-chromium (auto-detect macOS +
  escape-hatch `-Dorquestrador.preview.thumbnail.local-browser`). Testes: `DockerExecutionTargetArgsTest`
  (+2), `LocalExecutionTargetServerTest` (+2, browser falso).
- **V22** `preview_server_thumbnail` (bytea por-run) + entidade/repo/projeção + `PreviewServerThumbnailPersistenceTest`.
  `byte[]` sem `@Lob` + `columnDefinition="bytea"` (vale no Postgres real E no H2 modo PostgreSQL — validado no start com `ddl-auto: validate`).
- **`PreviewThumbnailCapturer.captureOnce()`** (molde `WorkspaceReaper`): sweep sobre vivos, deriva
  run_id do último manifest, skip por-run, best-effort NÃO-abortante, `THUMBNAIL_CAPTURE_FAILED` ator
  SYSTEM. `PreviewThumbnailCapturerTest` (6, ScriptedTarget). Agendado no `HeartbeatScheduler`
  (`preview.thumbnail.sweep-ms:60000`).
- **Serving** `PreviewServerThumbnailController` (board-only, `image/png`+`nosniff`+cache imutável +
  metadados) + `PreviewServerThumbnailHttpTest` (2). **Front**: `PreviewServerThumbnail` (fetcher
  autenticado → blob → `<img>`) na seção Servidor da Issue (full) e nas linhas da tela Servidores
  (compact); Mission Control intocado.
- **Imagem-shot** `infra/preview-shot/Dockerfile` + `PreviewShotImagePinTest` (2) + `build.sh`.

### ⚠ CORREÇÃO forçada pelo smoke do argv (a única mudança de decisão — a "Decisão em aberto" do plano)
A deep-research (7 agentes, 44/45 claims) recomendou `chromedp/headless-shell`. **O smoke do argv
(§C.2, obrigatório) REPROVOU**: o binário `headless_shell` é **CDP-only — NÃO implementa o one-shot
`--screenshot`/`--dump-dom`** (trava sem capturar nem sair; `--help` sem menção a screenshot). A
pesquisa confundiu `chrome --headless --screenshot` (Chrome COMPLETO) com o binário shell. **Pivot
para `alpine:3.21` + `apk add chromium`** (Chromium COMPLETO, current 136+, mantido, pull pequeno) —
provado no smoke: PNG 1280×800 válido, exit 0. Registrado em ADR-0027 §B3/§E + `PreviewShotImagePinTest`.
Lição: o smoke do argv não é cerimônia — pegou a premissa falsa que a pesquisa adversarial deixou passar.

### Prova VIVA na sujeira real (§C.5 — VENDO)
App v2 FRESCA seedada (company **Thumbnail Demo** `thumb-demo-9368`, agente `builder` process/LOCAL,
issue "app v2 fresca", manifest `python3 -m http.server 8123`, index.html estilizado). "Rodar app" →
servidor VIVO. **1×/ciclo o sweep capturou** (host Chrome): 1 linha bytea (322241 bytes, run_id
correto), evento só na row (sem PREVIEW_CAPTURED). **VENDO no board**: o `<img>` na seção Servidor da
Issue (full + caption) e nas linhas de Servidores (compact). Idempotência: 2º ciclo não duplicou.
**Coração**: `retention-days=0` → `WorkspaceReaper` coletou o workspace (evento `WORKSPACE_COLLECTED`
ator SYSTEM) → "Rodar app" respondeu **"workspace coletado pelo GC… a app viva nao volta"** → **o
thumbnail CONTINUOU renderizando** (status DONE, frame vivo sumiu, o PNG durável ficou). Falha
sintética (`THUMBNAIL_CAPTURE_FAILED` SYSTEM, subida intocada) é UNIT-provada
(`PreviewThumbnailCapturerTest`) — não re-demonstrada viva (poupar o ambiente). **Estado de repouso**:
backend novo no `:8080`, `retention-days=7` restaurado (defaults).

### Pendências / notas para o operador
- **Imagem-shot**: `docker images` tem `orq-preview-shot:alpine3.21` (buildada). Para produção/DOCKER,
  rode `infra/engines/build.sh` (constrói as 3 imagens). O caminho DOCKER foi provado por argv-smoke;
  o caminho LOCAL (host Chrome) foi o exercido na prova viva.
- **Dado de demo deixado**: company `Thumbnail Demo` com o thumbnail sobrevivente (é a prova viva). Se
  quiser, remova depois — nenhum átomo depende dela.
- **Frontend preview** foi subido nesta sessão (`:3000`) para o VENDO; suba/derrube pelo seu fluxo.
- Herdadas: volume anônimo 6bea9500…; aprovação da239e6d do Atlas (PENDING). Intactas.

### Próxima fatia (candidatas, §H — nenhuma reaberta aqui)
Cap+graduação das rows de thumbnail/`preview_artifact`/`run_events` · raster do v1 / thumbnail no
Mission Control · upgrade da imagem-shot para puppeteer se o `virtual-time-budget` faltar em SPA
pesada · WebGL (`--enable-unsafe-swiftshader`).
