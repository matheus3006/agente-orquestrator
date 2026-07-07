# Handoff FECHADO — Grilling "Preview real" (F.9 · diferencial #5)

> **Entregável** da sessão de grilling 2026-07-05 sobre o kickoff da F.9 (preview real pós-F.8).
> Formato: **A** decisões/porquê · **B** diffs de ADR/CONTEXT · **C** mudanças ordenadas · **D** limites v1.
> **Q1–Q7 fechadas 7/7 na recomendação**, racional registrado por decisão (ADR-0020: "porque quis" rejeitado).
> **Zero código nesta sessão.** ADR novo: **ADR-0021** (criado e Aceito). CONTEXT.md atualizado.
> Piso intocado: backend 256/256 · V19 → a F.9 abre **V20** · front vitest 174/174.

---

## A — DECISÕES + PORQUÊ

### A.1 (Q1) — "Demoável" v1 = **app-in-a-file** (1 arquivo HTML standalone, o browser executa)
O binário do kickoff ("protótipo estático × app rodando") era falso: **o protótipo cadillac RODA** —
React 18 UMD + Babel num arquivo só, app interativo cujo **runtime é o browser do board**. "Estático"
descreve o *transporte* (bytes), não a *experiência*. A pergunta real é **quem executa**: browser
(transporte de arquivo) × processo servidor no container (transporte de porta). O v1 fica no browser:
- É o diferencial #5 **como concebido** (PRD F5: `criar-prototipo`/`melhorar-prototipo` → device-frame).
- O penhasco do Mundo B (alocação/registro de portas, `--network` pensado — hoje o `docker run` nem
  flag tem —, caps de recurso, supervisão/TTL/teardown, servidor **controlado por agente** exposto no
  host) é uma fatia inteira à parte: "dev-environment-as-a-service". Vira **v2 explícita**.
- **Custo honesto aceito:** feature de backend segue sem preview no v1 — o momento-demo dela é
  StepTrace + report + testes verdes. §A.6-#2 ("roda local com 1 comando") continua satisfeito na mão
  do operador pós-aprovação.
- Sub-recorte: **single-file** (diretório com assets abriria transporte-de-árvore + path-traversal).

### A.2 (Q2) — Transporte = **captura única no settle → conteúdo no Postgres** (foto imutável por run)
O fato duro da F.8: ler o volume nomeado **exige passar por container**. Mecânica escolhida:
**1 container efêmero de leitura por run-com-artefato** — `docker run --rm --network none
-v <vol>:/workspace:ro <imagem-do-adapter> cat <contrato>` — bytes gravados em `preview_artifact`.
- **Foto × janela:** o PRD dizia "ao vivo", mas intra-run o arquivo está meio-escrito (iframe de HTML
  truncado é lixo); o "ao vivo" da criação **já é o StepTrace**; o loop real do produto é **entre**
  runs (conversar → `melhorar-prototipo` → nova versão). E a foto casa com o review gate (ADR-0012):
  **o que se aprova é exatamente o que foi capturado**, não um arquivo que outro run pode mudar depois.
- **Custo por-captura, não por-view** (leitura ao vivo por request = 1 container por refresh, sem
  imutabilidade — rejeitada).
- **Servidor persistente ro+porta — morto pela mecânica:** container não ganha mount a quente; cada
  agente novo (volume novo) exigiria recriar o servidor. **`docker cp`/cache filesystem — colapsa** na
  captura com destino pior (backend stateful, backup manual, path-traversal na serving).
- **Imagem do leitor = a do próprio adapter** (`orq-claude-code:2.1.201` é debian-slim, tem `cat`, e
  está garantidamente na máquina — o run acabou de rodar nela). **Zero dependência de imagem nova.**
- **Cap 2MB** com falha legível (não trunca — HTML truncado é quebrado). Postgres/TOAST aguenta o
  cadillac típico (≤300KB); destino-banco segue o padrão da casa (skills no banco: durável, backup,
  company-scoped, independente do PC).
- Refinamento futuro **sem migration**: botão "re-capturar agora" contra run em voo (mesmo capturador,
  outro gatilho).

### A.3 (Q3) — Anúncio = **contrato de lugar**: `/workspace/.orq/preview/index.html`
Como o pipeline sabe que existe artefato (deny-by-default: o agente NÃO chama API — morto por ADR-0001):
- **Marcador no stream — frágil nos CLIs reais:** no `stream-json`, o texto do modelo vive **dentro**
  do JSON (`ASSISTANT`/`RESULT`) e stdout de Bash tool vai pro `tool_result` do transcript, não pra
  linha crua no stdout do container. Detector viraria grep de payload acoplado à gramática de cada CLI
  (e quebraria no próximo evento novo, tipo o `RATE_LIMIT_EVENT`). Path vindo do agente ainda exigiria
  sanitização dura no LOCAL.
- **O contrato é um LUGAR, não uma chamada:** o agente anuncia fazendo o que já faz nativamente —
  **escrever arquivo**. Nome fixo, 1 arquivo (casa com Q1). Existe no settle → captura; não existe →
  run normal. Zero heurística de "qual arquivo é o entregável". O padrão já é da casa: o
  `SkillMaterializer` escreve `.claude/` no workspace.
- **Quem carrega o contrato: a skill.** `criar-prototipo`/`melhorar-prototipo` (versões no catálogo do
  banco) ganham o passo final "publique o standalone em `.orq/preview/index.html`". O contrato evolui
  como **conteúdo versionado**, não como código — e a publicação aparece de graça no StepTrace (o `cp`
  é tool call visível): intenção explícita e auditável, no espírito do canal-de-SAÍDA do ADR-0018.
- **Anti-duplicata sem furar o `:ro`:** o workspace persiste entre runs → dedup por **`content_hash`**
  (padrão das skills) contra o último artefato **da mesma Issue**. Captura sempre que existe; só
  registra linha nova se o hash mudou. (Apagar após capturar exigiria `:rw` — rejeitado.)
- **Run sem Issue não captura** (turno de conversa do CEO: delegador puro não produz conteúdo, ADR-0018).
- Alternativa sobrevivente rejeitada: olhar `prototipos_html/*.html` com `mtime > started_at` — zero
  mudança na skill, mas heurística de relógio no lugar de intenção + regra de escolha para N arquivos
  + artefato fantasma quando um run toca protótipo antigo sem querer.

### A.4 (Q4) — Segurança: **4 inegociáveis + CDN aceito**
Modelo de ameaça: HTML sai de um LLM injetável e roda no browser do operador **dentro do control-plane**.
O ataque que não pode existir: JS do artefato agindo como o board (ler storage, chamar API como operador,
aprovar reviews, contratar agentes).
1. **Nunca no origin do board:** `<iframe sandbox="allow-scripts">` e nada mais — sem
   `allow-same-origin` (combinação letal com scripts), sem `allow-popups`/`allow-top-navigation`/
   `allow-forms`. Origin opaco: não lê cookie/storage, não alcança a API.
2. **O endpoint nunca serve HTML navegável:** iframe **não manda header custom** → o `X-Board-User` do
   fetcher central (ADR-0013) não viaja em `src=`. Saída limpa: o board **busca o conteúdo pelo fetcher
   autenticado** (endpoint devolve texto + `X-Content-Type-Options: nosniff`, jamais `text/html`
   navegável) e renderiza via **`srcdoc`/blob** dentro do sandbox. Zero endpoint público, zero
   primitiva de auth nova (URL assinada rejeitada por isso), deny-by-default intacto — "abrir a URL
   numa aba" não existe como documento.
3. **Captura hermética:** leitor `--network none` + volume `:ro` (A.2); no **LOCAL**, resolver
   real-path e **rejeitar o que escapar do workspace** (anti-symlink — no DOCKER o symlink-escape é
   inócuo por construção: o container só vê o volume).
4. **Cap 2MB com falha legível** (protege banco e srcdoc).
- **CDN permitido no iframe** (cadillac carrega React/Babel de CDN; Babel inline pesaria ~2,5MB e
  exigiria reescrever o motor): com sandbox sem same-origin o JS só "vê" a si mesmo, e o canal de
  exfiltração dominante **já é o run** (o container do agente tem rede pra falar com a Anthropic).
  Risco residual = supply-chain do CDN — **aceito no self-hosted pessoal** com protótipo descartável.

### A.5 (Q5) — UI: **Issue (âncora primária) + Review (momento-demo) + badge no Mission Control**
- Linha nasce **por-Run** (auditoria), apresenta **por-Issue** (a Issue é "a unidade de trabalho e de
  comunicação"). Seção "Preview" na página da Issue: device-frame + seletor de versões (v1, v2… uma
  por run que mudou o hash; `melhorar-prototipo` = versão nova na mesma Issue).
- **Fila de Review = onde o loop fecha:** `review.run_id NOT NULL` (ADR-0012) → o card embute
  **exatamente a versão capturada pelo run sob revisão**. "Aprovar → VER" literal — o §A.6-#2
  materializado no gate que já existe.
- **Mission Control: só badge** "preview novo" no card do agente (clique → Issue). Cards seguem leves
  (ADR-0013); zero iframe no grid.
- **Thread do CEO: fora do v1** (o preview nasce de run de executor com Issue; ancorar na conversa
  exige ligação conversa↔entregável que não existe — nasce com "CEO anuncia entregas").
- **Versões append-only** (padrão savelog; ~300KB/versão é irrelevante; auditoria ganha "a review R
  aprovou a v3" de graça). GC/retenção fora do v1.
- **Componente único `PreviewFrame`**: esconde sandbox + srcdoc/blob + device-frame atrás de props
  mínimas; reusado em Issue e Review (o módulo profundo da tela).

### A.6 (Q6) — LOCAL × DOCKER: **mesmo seam — o `ExecutionTarget` ganha a leitura**
O eixo de variação da leitura ("diretório real" × "volume nomeado") é **o mesmo eixo** que o
`ExecutionTarget` já abstrai pro run — um segundo seam paralelo (WorkspaceReader) duplicaria o dispatch.
- **`ExecutionTarget` ganha uma operação pequena** de ler arquivo do workspace (o `Spec` já carrega os
  dois lados: `workDir` pro LOCAL, `volumeName`+`image` pro DOCKER). Mecânica funda atrás: DOCKER monta
  o leitor hermético **sem `credentialMounts`**; LOCAL faz read com real-path check. **Dois adapters
  reais no dia 1** — seam de verdade pelo critério do `/codebase-design`.
- **Módulo `preview`** (o bounded context nº 7 que a ARQUITETURA §4 reservava) é o módulo profundo por
  cima: contrato de lugar + cap + hash + dedup + persistência + serving. O settle toca um ponto.
- **3 regras de comportamento:** captura **só em SUCCEEDED** (run falho → BLOCKED, trace é o canal;
  re-execução re-publica) · captura **fora de transação, antes do settle** (I/O de ~0,5–1s não roda
  dentro da tx que cria a review; quando a review nasce, o artefato já existe) · **falha de captura é
  legível e não-bloqueante** (Docker fora do ar/cap estourado → a Issue liquida normal; o reviewer vê
  "captura falhou: motivo" no lugar do preview — a captura é observação, não participante da máquina
  de estados).
- **LOCAL de graça viabiliza o smoke barato:** `process` + `printf > .orq/preview/index.html` exercita
  a fatia inteira **sem gastar crédito Claude**.

### A.7 (Q7) — **Migration V20 (`preview_artifact`)** + recorte explícito
`preview_artifact`: `id` uuid PK · `company_id` NOT NULL (queries lideradas por company) · `issue_id`
NOT NULL · `agent_id` NOT NULL (badge) · `run_id` NOT NULL (a Review liga preview↔run) · `version` int
único por Issue ("review aprovou a v3"; ordinal seguro — runs da Issue são serializados pelo lock) ·
`content` TEXT NOT NULL · `content_hash` NOT NULL · `size_bytes` · `created_at`.
**Sem status, sem update, sem soft-delete — append-only imutável.** Índice liderado por
`(company_id, issue_id)`. Endpoints board-only: lista de versões da Issue (metadados) + conteúdo por id
(texto + nosniff; imutável por id → front cacheia infinito); agregado do mission-control ganha o campo
do badge.

### A.8 — Decisões derivadas (consequências de Q3; contestáveis na leitura)
- **Atualizar as skills `criar-prototipo`/`melhorar-prototipo` no catálogo FAZ PARTE do Definition of
  Done da fatia** — sem o passo de publicação, o preview nunca nasce em uso real. É conteúdo de skill
  (banco), não código.
- **`.orq/` fica reservado como namespace de contratos do orquestrador dentro do workspace** (ao lado
  do `.claude/` do SkillMaterializer) — futuros contratos (handoff, manifests) moram ali.
- **Codex ganha preview de graça** quando o login existir — o contrato é do workspace, não do motor.

---

## B — DIFFS DE ADR / CONTEXT.md (APLICADOS nesta sessão)

- **ADR-0021 "Preview real — captura no settle e render sandboxed" — CRIADO, Aceito** (as 7 decisões,
  alternativas rejeitadas com porquê, ressalvas honestas). `docs/adr/0021-preview-real-captura-settle-render-sandboxed.md`
- **CONTEXT.md** — entrada **PreviewArtifact** reescrita (captura no settle, imutável, versão por
  Issue) + termos novos: **Contrato de lugar (`.orq/preview/`)** e **PreviewFrame**.
- **Sem emenda a ADRs existentes:** 0001 (deny-by-default) e 0012 (review) são **consumidos**, não
  alterados; 0016 ganha o preview como primeiro *leitor* de volume — citado no 0021, sem revisão.

---

## C — MUDANÇAS ORDENADAS (fatias de implementação; /tdd backend-first)

**F.9a — Backend (o grosso):** migration **V20** + entidade/repo `preview_artifact` · operação de
leitura no `ExecutionTarget` (RED nos dois adapters: argv do leitor DOCKER puro/testável sem daemon,
espelhando `DockerExecutionTargetArgsTest`; LOCAL com real-path check anti-symlink) · módulo `preview`
(capturador: contrato + cap 2MB + sha-256 + dedup por Issue + persistência) · hook no fluxo do executor
(pós-sucesso, pré-settle, fora de tx, best-effort legível) · endpoints board-only + badge no agregado
do mission-control. **Smoke sem crédito:** agente `process` + printf.

**F.9b — Skills (conteúdo, não código):** atualizar `criar-prototipo`/`melhorar-prototipo` no catálogo
com o passo final de publicação; attach no agente cobaia.

**F.9c — Frontend:** `PreviewFrame` (sandbox `allow-scripts` puro + srcdoc/blob + device-frame
desktop/mobile) · seção Preview na página da Issue (versões) · embed no card da Review (versão do
`review.run_id`) · badge no Mission Control. **Reconvocar as 4 skills de design**
(`/bencium-impact-designer`, `/bencium-innovative-ux-designer`, `/emil-design-eng`, `/ui-ux-pro-max`).

**F.9d — Smoke real (a demo que muda de nível):** `Smoke F8 Claude` (6fcaff70) numa Issue
"criar protótipo de X" com `risk_level=5` → run DOCKER real → captura → **preview na fila de Review**
→ aprovar VENDO a coisa. O fluxo *contratar → conversar → aprovar → VER* inteiro.

---

## D — LIMITES v1 ACEITOS (recorte explícito)

1. **Processo/porta** (Mundo B, dev-server no container) — fatia v2 própria e consciente.
2. **Diretório/multi-arquivo/assets relativos** — contrato é 1 arquivo, nome fixo.
3. **Preview intra-run** ("janela ao vivo") e **re-captura manual** — futuros baratos pelo mesmo seam,
   sem migration.
4. **Âncora na thread do CEO** — nasce com "CEO anuncia entregáveis".
5. **Thumbnail/screenshot** no badge — exigiria headless browser no backend; badge é ícone/texto.
6. **Validação de console (Preview MCP)** — ferramenta do **agente durante a criação** (conteúdo da
   skill), não do orquestrador: o orquestrador serve o publicado, não abre browser.
7. **GC/retenção de versões** e **redação de segredos no artefato** — operador é o único leitor no
   self-hosted (ressalva honesta no ADR-0021).
8. **Editar o protótipo no board** — o loop de iteração é conversar → `melhorar-prototipo` → nova versão.

---

## Desenho do seam (prosa; o bônus do kickoff — nomes a bater no /tdd)

- **`ExecutionTarget.readWorkspaceFile(Spec, relativePath)` → bytes | falha legível.**
  DOCKER: `docker run --rm --network none -v <volumeName>:/workspace:ro <spec.image> cat
  /workspace/<relativePath>` — **sem** `credentialMounts`; argv puro e testável sem daemon.
  LOCAL: resolve `workDir + relativePath` → `toRealPath()` → **exige prefixo do workspace real**
  (anti-symlink) → lê. O path é **constante do pipeline** (contrato de lugar), nunca input do agente.
- **`preview.PreviewArtifactCapturer.captureIfPublished(run)`** (nome ilustrativo): conhece o contrato
  `.orq/preview/index.html`, aplica cap 2MB, calcula sha-256, deduplica contra a última versão da
  Issue, persiste `version+1`. Chamado pelo executor após sucesso, **antes** do `settleAfterRun`, fora
  de tx; qualquer falha → registro legível, jamais exceção pro settle.
- **Serving board-only:** `GET issues/{id}/preview-artifacts` (metadados/versões) ·
  `GET preview-artifacts/{id}/content` (**`text/plain; charset=utf-8` + nosniff — NUNCA `text/html`**).
- **Front:** `PreviewFrame(html, device)` — iframe `sandbox="allow-scripts"` + srcdoc (blob se srcdoc
  pesar), moldura desktop/mobile; conteúdo por id = cache imutável.

---

## PRÓXIMO PASSO
Implementação na ordem **F.9a → F.9b → F.9c → F.9d** (kickoff de sessão nova preparado ao fim desta
sessão — ver mensagem de kickoff). Backend-first com **/tdd** (RED confirmado peça a peça, como na
F.8); as 4 skills de design entram na F.9c; **/codebase-design** é a lente do seam
(`ExecutionTarget.readWorkspaceFile` + módulo `preview`; exemplares: `LineCursor`, `EngineContainer`).
