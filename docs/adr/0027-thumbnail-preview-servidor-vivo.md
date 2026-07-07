# ADR-0027: Thumbnail do preview v2 — o shot do servidor vivo (F.13)

**Status:** Aceito (2026-07-05, execução F.13 — `docs/handoff-grilling-thumbnail-preview.md`)

## Contexto
O preview v2 (`PreviewServer`, ADR-0023) é um servidor VIVO e efêmero: o board vê a app **rodando**,
mas **nada visual persiste** — o único registro durável do v2 é a DECLARAÇÃO (`preview_server_manifest`,
V21); o vivo mora no Docker e morre por TTL. A F.12 (ADR-0026) tornou o buraco AGUDO: agora que o
`WorkspaceReaper` coleta o workspace após 7 dias do terminal, a "app viva" some **sem deixar imagem** —
a foto v1 (`preview_artifact`, HTML, eterna) só existe para o transporte de ARQUIVO; o transporte de
PORTA não tem foto. A própria F.12 anotou a lacuna como argumento pró-fatia thumbnail (ADR-0026 §B4/§H).

A fatia: capturar um **PNG durável do app v2 enquanto o servidor está vivo** (janela pré-GC),
renderizando num **container dedicado com Chromium** (o board não pode — o sandbox/cross-origin que
protege o preview proíbe ler os pixels), guardá-lo em `bytea` no banco (sobrevive ao GC, como a foto
v1), e mostrá-lo na Issue e na tela Servidores — inclusive **no lugar do "Rodar app" morto** depois
que o GC levou o workspace. Escopo **só v2**. O agente **não declara nada novo** (deny-by-default
intacto — o shot é observação pura do pipeline sobre o servidor que o pipeline mesmo subiu).

## Decisão

### B1. Natureza: aceitar o browser — mas como CONTAINER, não como dependência do JVM
Um raster exige um renderizador, e a ADR-0021 **matou o thumbnail exatamente aqui** ("headless browser
como dependência do backend"). O contexto mudou: o v2 existe (0023) e o GC coleta (0026), então há um
argumento objetivo de durabilidade que antes não existia. O renderizador entra como um **5º container
da família** (run, leitor, escritor, servidor → **shot**), hermético e sem credencial — a rejeição da
0021 continua de pé para o **JVM** (o backend segue transporte de bytes, nunca renderizador), mas cai
para um container irmão. A filosofia "o browser do board executa" (0021 §1) protege o board de rodar
HTML de agente; ela não proíbe um container isolado de renderizar.
**Rejeitadas**: **captura pelo board** (o sandbox que torna o preview seguro PROÍBE ler os pixels nos
dois modos — v1 `srcdoc` origin opaco, v2 `src` cross-origin); **renderizador no JVM** (a rejeição da
0021, mantida); **skill publica snapshot estático** (seria um arquivo falso — shell sem o servidor vivo
por trás, contradizendo o motivo de o v2 existir).

### B2. Momento: captura no SERVER-UP, 1×/run, obrigatoriamente na janela pré-GC
O shot fotografa **enquanto o servidor está vivo**, 1×/run. Amarra dura herdada da F.12: depois do GC
o `startFor` já responde "workspace coletado" (ADR-0026 §B4) — não há servidor para fotografar; a
captura é obrigatoriamente um ato da janela pré-GC. Reusa o único momento em que a app está de pé (o
operador clicou "Rodar app"), sem custo de subir nada extra; captura o estado que o operador de fato
viu; a chave por-run espelha o manifest (por-run, 0023 §2).
**Rejeitadas**: **captura no settle** (todo run SUCCEEDED subiria o servidor headless start→shot→kill —
paga o start completo a cada settle e re-introduz o "subir o app no settle" que a 0023 recusou por
design); **botão manual** (o operador esquece e o GC leva). **Recorte aceito**: app que o operador
**nunca subir** dentro da janela de 7d não ganha thumbnail — o registro durável espelha o que foi de
fato trazido à vida.

### B3. Imagem-shot: Alpine + Chromium COMPLETO, PINADO, num Dockerfile fino da casa
`infra/preview-shot/` ganha uma imagem dedicada (nenhum adapter tem browser, §E): `FROM
alpine:<release>` + `apk add chromium` (Chromium COMPLETO do Alpine) + fonte + symlink. Comando único
no argv do seam: `chromium --headless --no-sandbox --disable-gpu --disable-dev-shm-usage
--hide-scrollbars --window-size=1280,800 --virtual-time-budget=<ms> --screenshot=/tmp/s.png <url> &&
cat /tmp/s.png` — sem runtime node, sem árvore de deps npm. O `--virtual-time-budget` cobre "esperar a
SPA renderizar" sem Playwright. A base é PINADA ao release do Alpine (defesa `--bare`); o chromium
FLUTUA dentro do release (security-update do Alpine — CVE patching desejável, o OPOSTO do risco de
`--bare`). O `PreviewShotImagePinTest` trava Dockerfile ↔ constante Java.
**A deep-research recomendou `chromedp/headless-shell` (§E) — o smoke do argv (§C.2, obrigatório)
REPROVOU essa escolha e forçou a correção**: o binário `headless_shell` é **CDP-only** (só fala o
DevTools protocol) — NÃO implementa o modo one-shot de linha de comando `--screenshot`/`--dump-dom`
que o seam usa (trava sem capturar nem sair; `--help` sem menção a screenshot; confirmado no smoke). O
one-shot exige o **Chromium COMPLETO**. Alpine+`chromium` entrega isso: CURRENT (Chromium 136+, não
congelado como o `zenika/alpine-chrome` da pesquisa, preso no 124), MANTIDO (security do Alpine), pull
pequeno — provado no smoke (PNG 1280×800 válido, exit 0). O Dockerfile absorve 2 pegadinhas (senão o
`chromium … && cat` falha): (1) o binário do Alpine é `chromium-browser` → symlink `chromium` no PATH;
(2) a base traz zero fontes → `font-liberation` (Latina; texto vira tofu sem ela). Os flags
`--no-sandbox`/`--disable-dev-shm-usage`/`--disable-gpu` são do RUN-COMMAND (moram no argv do seam),
não intrínsecos da imagem.
**Rejeitadas**: **`chromedp/headless-shell`** (a #1 da pesquisa — CDP-only, sem o one-shot `--screenshot`;
usá-la exigiria um cliente CDP em node, furando o "sem node"); **`zenika/alpine-chrome`** (a #2 da
pesquisa — Chromium COMPLETO com `--screenshot` funcionando, MAS órfã e congelada no Chromium 124, ~2
anos de CVE atrás; Alpine+`chromium` self-built é a mesma família SEM o débito de manutenção, e
current); **node + puppeteer/playwright** (captura de SPA mais robusta, MAS runtime node + deps npm =
superfície de supply-chain maior; upgrade FUTURO se o `virtual-time-budget` provar insuficiente, §H);
**imagem pública batteries-included** (dep externa gorda e opaca).

### B4. Seam: método ADITIVO no `ExecutionTarget` (`screenshotServer`), 2 adapters reais
O shot é uma operação **aditiva** no `ExecutionTarget` — `screenshotServer(ScreenshotSpec)` →
`ThumbnailCapture {CAPTURED(bytes) | UNREACHABLE | RENDER_FAILED | CAP_EXCEEDED}` — com DOCKER e LOCAL
no dia 1, argv puro testável sem daemon (molde idêntico ao `removeIssueWorkspace` da F.12 e ao par
start/stop de servidor da 0023). **O shot VARIA de verdade no eixo LOCAL×DOCKER por *reachability*,
não por simetria decorativa**: um servidor **LOCAL** faz bind em `127.0.0.1` (loopback, 0023 §4) —
**inalcançável de dentro de um container** — então exige o **Chromium do host**; um servidor **DOCKER**
é alcançável por um container irmão na rede `orq-preview`, pelo **nome determinístico do container** na
**porta do manifest** (`http://orq_preview_<c8>_<a8>_<i8>:<port>/`), sem tocar o host-port efêmero.
DOCKER: shot-container hermético (`docker run --rm --network orq-preview <imagem-shot> sh -c 'chromium
… --screenshot=/tmp/s.png … && cat /tmp/s.png'` — captura os bytes pelo stdout, exatamente como o
leitor da 0021). LOCAL: spawn de Chromium no host contra `127.0.0.1:<hostport>` (dev-only, mesma classe
de risco do run/servidor LOCAL — 0016).
**Rejeitadas**: **seam paralelo** (`Screenshotter` próprio) — seria a **4ª recusa** do mesmo padrão
(0021, 0022, 0023 já recusaram duplicar o dispatch); **single-mechanism always-container** (NÃO alcança
o servidor LOCAL bindado em loopback); **always-host-chromium** (furaria o isolamento do DOCKER e imporia
Chromium no host como dependência de produção).
**Ressalva honesta**: o adapter LOCAL exige Chromium/Chrome no host do dev (auto-detect nos apps do
macOS + PATH; escape-hatch `-Dorquestrador.preview.thumbnail.local-browser`); ausente → `RENDER_FAILED`
best-effort. O caminho canônico é o DOCKER; o LOCAL é o exercício barato do seam. A classificação crua
(`ThumbnailCapture.classify`) é compartilhada pelos dois adapters — a fiação muda, o veredicto não.

### B5. Disparo: SWEEP agendado sobre servidores VIVOS (world-driven, best-effort)
`PreviewThumbnailCapturer` — módulo profundo, par do `PreviewServerSupervisor`: `captureOnce()`
público agendado no `HeartbeatScheduler` (irmão do `reapOnce`/`reapWorkspaces`); `sweepTarget` é o
núcleo profundo testável. **World-driven**: 1×/ciclo (~60s) lista os servidores **VIVOS**
(`listPreviewServers` — a mesma listagem que o reaper da 0023 já faz); para cada um **deriva o `run_id`**
do **último manifest da Issue** (V21 — o mesmo que o `startFor` usa) + a porta; **pula se já existe
linha de thumbnail para aquele `run_id`** (guarda de idempotência por-run — mais limpa que dedup por
hash, que rasters quase nunca satisfazem); senão chama `screenshotServer(...)`, recebe os bytes,
persiste. **Best-effort de verdade**: a falha de um servidor **nunca** aborta o sweep (segue para os
demais); falha → evento `THUMBNAIL_CAPTURE_FAILED` no `activity_log` (detail só-IDs/enum — 0009) com
ator **`ActorType.SYSTEM`** (reusa o ator que a F.12 criou — o shot é ato do PIPELINE); retenta a cada
ciclo enquanto o servidor viver (o TTL de 15min da 0023 limita a insistência sozinho). O invariante
mais importante: **o shot NÃO atrasa nem quebra a subida** — a captura é observação, não participante
da máquina de estados (0021 §6, 0023 §2). **Evento SÓ na falha**: a ROW é o registro do sucesso (como
a foto v1) — sem um "capturado" redundante.
**Rejeitadas**: **inline-async após o `startFor`** (acopla o shot ao request HTTP, exige executor
async novo, falha sem retry natural); **inline-bloqueante no start** (atrasa o operador ver o app e
vincula a subida ao sucesso do shot, furando o invariante).

### B6. Storage: tabela nova `preview_server_thumbnail` (V22), `bytea`, por-run
Migration **V22** append-only: `id · company_id · issue_id · agent_id · run_id · content` **`bytea`**
`· content_hash · size_bytes · created_at`. Por-run; o vigente para exibição é o **último da Issue**
(espelha o manifest V21). Cap **2MB** com falha legível (`CAP_EXCEEDED`, nunca trunca). `content_hash`
guardado para integridade/cache (a idempotência real é a guarda por-`run_id` do §B5). Precedente FORTE:
o v2 **já ganhou tabela própria** para o manifest (V21), separada da foto v1 (V20); um PNG binário
nascido de servidor vivo por-run não pertence à mesma linha que um HTML por-Issue. `bytea` é o tipo
nativo do Postgres para binário (mapeado por `byte[]` sem `@Lob`; `columnDefinition="bytea"` vale
também no H2 em modo PostgreSQL dos testes). Guardar no banco (não no filesystem) é a escolha provada
da casa (0021 recusou `docker cp → cache filesystem`).
**Rejeitadas**: **estender `preview_artifact`** (mistura TEXT/HTML por-Issue com PNG/binário por-run;
o versionamento por-Issue não alinha 1:1 com a captura por-run); **base64 em coluna TEXT** (+33% de
tamanho, desperdiça o cap, mentira de tipo).

### B7. Serving + UI: PNG inerte → serving simples; casa na Issue e Servidores, cards intocados
- **Serving MAIS simples que a foto v1**: um PNG é **inerte** (não executa). Endpoint board-only
  `GET /api/companies/{companyId}/preview-server-thumbnails/{id}/content` → `Content-Type: image/png`
  + `X-Content-Type-Options: nosniff` + `Cache-Control: private, max-age=…, immutable`. O front busca
  os bytes pelo **mesmo fetcher autenticado** (o `<img src>` não manda header custom → fetch
  autenticado → blob URL → `<img>`), **sem sandbox, sem iframe** (o que a foto HTML exige e o PNG
  dispensa). Metadados (versões por Issue, sem os bytes) num endpoint separado, como o
  `PreviewArtifactController`.
- **UI**: o PNG aparece na **seção Preview da Issue** (primário) e na **tela Servidores**. O momento
  matador: depois do GC, quando o "Rodar app" já responde "workspace coletado" (0026 §B4), **o
  thumbnail é o que aparece no lugar do botão morto** — a F.12 fechada VENDO ao contrário. **Mission
  Control INTOCADO** (cards leves, 0013 — a linha que a 0021 §5 e a 0023 §7 já sustentaram; esta é a 3ª).
**Rejeitadas**: **thumbnail no card do Mission Control** (fura "cards leves" + peso na grid de 2s);
**raster uniforme do v1** (sem argumento de durabilidade — a foto v1 é eterna); **sandbox/srcdoc para
o PNG** (desnecessário — imagem inerte). Risco residual aceito: CVE de decoder de imagem do browser —
mesma classe de risco de ver qualquer output de agente.

### B8. Deny-by-default: o agente não declara NADA novo
O agente já declara o `server.json` (0023 §2); o shot é **observação pura do pipeline** sobre o
servidor que o próprio pipeline subiu. Zero contrato de lugar novo, zero canal agente→core (a foto v1
também é observada, não declarada — 0021 §3). A superfície de deny-by-default não muda.

### B9. Registro
Este ADR. O switch exaustivo de `ActorType` (`CompanyAccessGuard`) já contempla `SYSTEM` (F.12/0026
§B6 — `case SYSTEM -> false`, fail-safe deny); o novo `THUMBNAIL_CAPTURE_FAILED` reusa esse ator.

## Riscos → estratégias
- **Tocar um seam provado (`ExecutionTarget`)** — mudança ADITIVA: método DEFAULT na interface (os 4
  fakes de teste compilam intactos), Local/Docker sobrescrevem; TDD red→green; piso da suíte **≥357
  verde**. Zero toque em `.claude/skills`, `.orq/preview/*`, parsers, posture (ADR-0025).
- **Shot em branco / meio-renderizado** — o `--screenshot` cru não espera network-idle/fontes/JS
  (deep-research §E); o `--virtual-time-budget` só falseia o relógio. Mitigação: budget generoso
  (5000ms) acima do load real; se persistir, `--run-all-compositor-stages-before-draw` ou upgrade pra
  puppeteer (`networkidle`) é evolução registrada (§H), não v1. Best-effort: um shot ruim é falha
  legível, nunca quebra a subida.
- **Chromium crashar no `/dev/shm` de 64MB do Docker** (`BUS_ADRERR`) — `--disable-dev-shm-usage`
  (escreve em `/tmp`) no argv, suficiente para um shot único não-paralelo (deep-research §E).
- **`--no-sandbox` como root** — o Chrome recusa subir como root sem ele; aceito porque o container é
  efêmero, hermético (rede `orq-preview` sem NAT), e renderiza só um irmão confiável — ele mesmo é o
  sandbox. Renderer desatualizado atrás do `--no-sandbox`: MITIGADO pela escolha de base (B3) — o
  chromium do Alpine é security-updated DENTRO do release pinado (rebuild da imagem pega o patch), em
  vez de um pino de patch congelado; o `rm` + rede hermética limitam o raio.
- **O shot atrasar/quebrar a subida** — invariante duro: best-effort NÃO-abortante (try/catch
  por-servidor), evento só na falha, a porta vem do handle do servidor vivo (não de re-derivação).

## Recorte explícito (fora desta fatia — §H do handoff)
Raster do v1 / thumbnail no Mission Control (cards leves, 3× sustentado) · viewport mobile · captura
animada/scroll-stitch · upgrade pra puppeteer/`networkidle` · WebGL (`--enable-unsafe-swiftshader`) ·
fontes CJK/emoji · **GC/retenção do próprio thumbnail** (as rows **FICAM** — é o registro que
sobrevive; quando o volume doer, é cap+graduação, outra fatia; o `WorkspaceReaper` coleta workspaces,
**nunca** rows) · app nunca subido na janela não ganha thumbnail (B2) · run sem Issue não captura
(igual à foto v1). Não reabrir ADRs 0001–0026; parser e contratos de lugar não mudam.

## §E — Evidência
- **Mapeamento do código (2 agentes, 2026-07-05)**: **zero renderizador na casa** (`pom.xml` =
  Spring/JPA/Flyway; `package.json` = React/Next/TanStack; as imagens de adapter são Node slim + CLI,
  sem Chromium) — um raster exige um browser NOVO, o fato que a 0021 usou para matar o thumbnail e que
  esta fatia reenquadra (browser como CONTAINER, não JVM). **O board não pode capturar** (v1 `srcdoc`
  origin opaco; v2 `src` cross-origin). **v2 sem registro visual durável** (só a declaração V21).
- **Deep-research da base-image (7 agentes, verificação adversarial, 2026-07-05)**: 44/45 claims
  confirmadas; recomendou `chromedp/headless-shell` (ativo, Chrome 150/151, ~143MB) sobre
  `zenika/alpine-chrome` (órfão, congelado no Chromium 124). **Correção pelo smoke (§C.2, obrigatório)**:
  a pesquisa confundiu `chrome --headless --screenshot` (Chrome COMPLETO, developer.chrome.com) com o
  binário `headless_shell` — que é CDP-only e NÃO implementa o one-shot `--screenshot` (trava sem
  capturar nem sair; provado no smoke). A pesquisa acertou o eixo (Chromium COMPLETO, mantido, current,
  sem node) e a rejeição do zenika (stale); errou o candidato #1. A síntese: **Alpine + `chromium`
  self-built** — Chromium COMPLETO (one-shot `--screenshot` funciona), current (136+), mantido, pull
  pequeno — a mesma família do zenika SEM o débito de manutenção. Lição registrada: o smoke do argv não
  é cerimônia — foi ele que pegou a premissa falsa que a pesquisa (mesmo adversarial) deixou passar.

## Relaciona
ADR-0016 (seam `ExecutionTarget`; `EngineContainer` pinado; LOCAL dev-only) · ADR-0021 (a foto v1 que
PERMANECE; a rejeição do thumbnail que esta fatia reenquadra; captura best-effort não-bloqueante) ·
ADR-0023 (PreviewServer vivo — a causa do buraco e o alvo do shot; `orq-preview`, nome determinístico,
manifest por-run) · ADR-0025/0026 (padrão de método ADITIVO no seam; `ActorType.SYSTEM`; segundo
reaper) · ADR-0009 (activity_log, detail só-IDs) · ADR-0013 (cards leves) · ADR-0010 (módulo profundo —
`PreviewThumbnailCapturer` atrás de `captureOnce`).
