# ADR-0021: Preview real — captura no settle e render sandboxed (F.9)

**Status:** Aceito (2026-07-05, sessão grilling `docs/handoff-grilling-preview-fechado.md`)

## Contexto
O diferencial #5 (preview ao vivo) esperava a F.8: com o isolamento por container (ADR-0016), o
workspace de agente DOCKER é **volume nomeado — invisível ao filesystem do backend**; ler qualquer byte
exige passar por container. Ao mesmo tempo, deny-by-default (ADR-0001) proíbe canal agente→core: o
agente **não** chama API para publicar artefato — o pipeline é quem observa. A F.9 fecha o loop de
valor do produto: o dono **vê** o que o agente produziu (*contratar → conversar → aprovar → VER*),
materializando o "Definition of Demoable" do fluxo de features (§A.6-#2 do grilling de Teams).

## Decisão

### 1. Demoável v1 = app-in-a-file (o browser executa)
O preview v1 é **1 arquivo HTML standalone** (motor cadillac: React UMD + Babel — app interativo cujo
runtime é o **browser do board**). O orquestrador só transporta bytes. "Estático × rodando" era falso
binário — a pergunta real é *quem executa*: browser (transporte de arquivo) × processo no container
(transporte de porta). Processo/porta = fatia v2 explícita (portas, rede, caps, supervisão, teardown,
servidor controlado por agente). **Custo honesto aceito:** feature de backend não ganha preview no v1
(demo = StepTrace + report + testes).

### 2. Transporte = captura única no settle → Postgres (foto imutável por run)
Ao liquidar run **SUCCEEDED**, o pipeline roda **1 container efêmero de leitura** —
`docker run --rm --network none -v <vol>:/workspace:ro <imagem-do-adapter> cat <contrato>` — e grava os
bytes em `preview_artifact` (**V20**). A imagem do leitor é **a do próprio adapter** (garantidamente
presente; zero dependência nova). Cap **2MB** com falha legível (nunca trunca). A foto imutável casa
com o review gate (ADR-0012): o que se aprova é exatamente o que foi capturado. O "ao vivo" intra-run
segue no StepTrace; o loop do produto é entre runs (`melhorar-prototipo` → nova versão). Custo
por-captura, não por-view.

### 3. Anúncio = contrato de lugar: `/workspace/.orq/preview/index.html`
O agente anuncia **escrevendo arquivo** (o ato que já faz nativamente): nome fixo, 1 arquivo. Existe no
settle → captura; não existe → run normal. **Quem carrega o contrato é a skill**
(`criar-prototipo`/`melhorar-prototipo` no catálogo ganham o passo final de publicação — contrato
evolui como conteúdo versionado no banco, e o `cp` aparece de graça no StepTrace: intenção explícita e
auditável, no espírito do canal-de-SAÍDA do ADR-0018). Anti-duplicata **sem furar o `:ro`**: dedup por
**`content_hash`** contra o último artefato da mesma Issue (workspace persiste entre runs). Run sem
Issue não captura (o CEO é delegador puro, ADR-0018). `.orq/` fica **reservado como namespace de
contratos do orquestrador** no workspace (ao lado do `.claude/` do SkillMaterializer).

### 4. Segurança: 4 inegociáveis + CDN aceito
Ameaça: HTML sai de um LLM injetável e roda no browser do operador dentro do **control-plane**; o
ataque a eliminar é JS do artefato agindo como o board.
1. **Nunca no origin do board:** `<iframe sandbox="allow-scripts">` e nada mais — sem
   `allow-same-origin` (letal com scripts), sem popups/forms/top-navigation. Origin opaco.
2. **O endpoint nunca serve HTML navegável:** iframe não manda header custom (o `X-Board-User` do
   fetcher não viaja em `src=`) → o board **busca o conteúdo pelo fetcher autenticado** (resposta
   texto + `nosniff`, jamais `text/html`) e renderiza via **srcdoc/blob** dentro do sandbox. Zero
   endpoint público, zero primitiva de auth nova; deny-by-default intacto.
3. **Captura hermética:** `--network none` + `:ro` + sem `credentialMounts`; no **LOCAL**, real-path
   check que **rejeita symlink escapando do workspace** (no DOCKER o escape é inócuo por construção).
4. **Cap 2MB** com falha legível.
CDN **permitido** no iframe (cadillac carrega React/Babel de CDN; inline pesaria ~2,5MB): com sandbox
sem same-origin o JS só vê a si mesmo, e o canal de exfiltração dominante já é o run (container do
agente tem rede). Risco residual supply-chain aceito no self-hosted pessoal.

### 5. UI: Issue (primária) + Review (momento-demo) + badge no Mission Control
Linha **por-Run** (auditoria), apresentação **por-Issue**: seção Preview com device-frame + versões
(append-only, padrão savelog; uma por run que mudou o hash). **A fila de Review embute a versão exata
do run sob revisão** (`review.run_id`, ADR-0012) — "aprovar → VER" literal. Mission Control ganha só
badge (cards leves, ADR-0013). Thread do CEO fora do v1. Componente único **`PreviewFrame`** esconde
sandbox + srcdoc/blob + moldura (reusado em Issue e Review).

### 6. LOCAL × DOCKER: mesmo seam — o `ExecutionTarget` ganha a leitura
O eixo de variação da leitura ("diretório real" × "volume nomeado") é o mesmo que o `ExecutionTarget`
já abstrai — um `WorkspaceReader` paralelo duplicaria o dispatch. O `ExecutionTarget` ganha **uma
operação de leitura de arquivo do workspace** (o `Spec` já carrega `workDir`/`volumeName`+`image`);
**dois adapters reais no dia 1**. O módulo **`preview`** (bounded context nº 7 da ARQUITETURA §4) é o
módulo profundo por cima: contrato + cap + hash + dedup + persistência + serving. Regras: captura **só
em SUCCEEDED** · **fora de transação, antes do settle** (I/O externo não roda na tx que cria a review;
quando a review nasce, o artefato existe) · **falha de captura legível e não-bloqueante** (a Issue
liquida normal; o reviewer vê o motivo no lugar do preview — captura é observação, não participante da
máquina de estados). LOCAL de graça → smoke com `process`+printf, sem crédito.

### 7. Migration V20: `preview_artifact` append-only
`id` · `company_id` NOT NULL · `issue_id` NOT NULL · `agent_id` NOT NULL · `run_id` NOT NULL ·
`version` (único por Issue; ordinal seguro — runs serializados pelo lock) · `content` TEXT ·
`content_hash` · `size_bytes` · `created_at`. Sem status/update/soft-delete. Índice liderado por
`(company_id, issue_id)`. Endpoints board-only: versões da Issue (metadados) + conteúdo por id
(imutável → cache infinito no front); badge no agregado do mission-control.

## Alternativas rejeitadas
- **Processo/porta no v1** — dev-environment-as-a-service: portas, rede, caps, TTL, teardown, servidor
  controlado por agente no host. Fatia própria futura.
- **Leitura ao vivo por request** — 1 container por refresh, sem imutabilidade (o board veria estado
  que o run não produziu), custo por-view.
- **Servidor persistente com volumes ro + porta** — container não ganha mount a quente: cada agente
  novo (volume novo) exigiria recriar o servidor; mais um serviço vivo pra supervisionar.
- **`docker cp` → cache filesystem do backend** — mesmos passos da captura com destino pior: backend
  stateful, backup manual, path-traversal na serving.
- **Registro explícito via API** — fura ADR-0001 (sem canal agente→core).
- **Marcador `ORQ_PREVIEW:` no stream** — o texto do modelo vive dentro do JSON dos CLIs (tool output
  vai pro transcript, não pro stdout cru); detector viraria grep de payload acoplado à gramática de
  cada CLI; path do agente exigiria sanitização dura no LOCAL.
- **Dir da skill + mtime (`prototipos_html/` + `mtime > started_at`)** — heurística de relógio no
  lugar de intenção; regra de escolha para N arquivos; artefato fantasma.
- **URL assinada para o iframe** — primitiva de auth nova (token/expiração/revogação) e documento
  navegável; srcdoc/blob entrega o mesmo com o fetcher que já existe.
- **Self-contained total (sem CDN)** — Babel inline ~2,5MB, cap maior e reescrita do motor cadillac,
  por ganho marginal dado o sandbox.
- **Thumbnail/screenshot no badge** — headless browser como dependência do backend.
- **Só última versão (sem histórico)** — quebra "o que a review X aprovou"; foge do padrão savelog.
- **Seam paralelo (`WorkspaceReader`)** — dois seams pro mesmo eixo de variação.
- **DOCKER-only no v1** — dev board (agentes LOCAL) não exercitaria a fatia; smoke exigiria credencial.

## Ressalvas honestas
- **Sem redação de segredos no conteúdo capturado** no v1 (operador é o único leitor no self-hosted);
  o artefato não passa pelos logs (ADR-0001 preservado), mas um agente pode colar segredo no HTML.
- **Supply-chain do CDN** dentro do iframe sandboxed — aceito; revisitar se o modelo de deploy mudar.
- **Feature de backend não tem preview** — o momento-demo dela segue StepTrace/report/testes.

## Relaciona
ADR-0001 (deny-by-default → contrato de lugar, não API) · ADR-0004 (executor intocado; a captura é
pós-run) · ADR-0010 (módulo `preview` profundo; `EngineContainer`/`LineCursor` exemplares) · ADR-0012
(a Review embute a versão do run revisado — o consumidor de UI que faltava) · ADR-0013 (fetcher único,
cards leves, polling) · ADR-0016 (o volume nomeado criou o problema; a F.9 cria o primeiro **leitor**
de volume — hermético) · ADR-0018 (anúncio como canal de SAÍDA; CEO não captura).

---

## Emenda R29-A (2026-07-18): o entregável de PESQUISA entra no contrato — `*.md` com tipo persistido

**Contexto.** O achado 🟡 da R27-A (medido ao vivo nas issues #4/#6/#9): agentes de pesquisa publicam
`.orq/preview/*.md` rico e a captura ignora — `preview_artifact` tinha 0 linhas na company inteira; o
entregável ficava invisível nas duas superfícies. Requisito do dono (verbatim, 2026-07-18): "ver na
fila de review E nas issues finalizadas · .md vira HTML estilo Docusaurus · app = ver a tela".
Decisões fechadas em grill com o dono (R29-A, 5 decisões numeradas no
`HANDOFF-r29a-research-preview.md`).

**O contrato de lugar ganha um segundo entregável, com precedência determinística (1 foto por run):**
1. `index.html` presente → captura como hoje (`content_type=html`) — prototipo pronto vence anotação
   solta; caminho do app byte a byte intacto.
2. Senão, `index.md` presente → captura (`content_type=markdown`) — a convenção do entregável de
   pesquisa.
3. Senão, exatamente **um** `*.md` no diretório → captura com o nome livre (a realidade medida na
   R27-A). **2+ `.md` sem `index.md` = falha legível `AMBIGUOUS_MARKDOWN`** (ensina a convenção),
   nunca escolha silenciosa.

**Persistência (V34).** `content_type` (`'html'|'markdown'`, check constraint) e `source_filename`
(proveniência da foto — o fallback captura nome livre; a foto não pode ser anônima). Backfill
verdadeiro por construção: toda linha pré-V34 nasceu do `index.html`. O tipo é **persistido, nunca
inferido na leitura**. Metadados expostos em `PreviewArtifactMetadata`/`VersionSummary`/`lib/api.ts`.

**Renderização (front).** A conversão md→HTML acontece **na apresentação**, dentro do `PreviewFrame`
ao montar o `srcDoc` (`srcDocFor` + `renderMarkdownDoc`): micromark + GFM com `allowDangerousHtml`
DESLIGADO — HTML cru do `.md` é **escapado por construção** (script de agente vira texto), e o iframe
`sandbox="allow-scripts"` de sempre segue como segunda muralha. O banco guarda a foto FIEL do que o
agente escreveu (auditoria; melhorias de estilo não exigem reprocesso). Documento docs-style
autocontido: CSS embutido (zero fetch externo), coluna de leitura, TOC lateral gerado estaticamente
dos h1–h3, claro/escuro via `prefers-color-scheme`. Os 4 inegociáveis da §4 seguem intactos.

**Seam.** O `ExecutionTarget` ganha **`listWorkspaceDir`** (par raso do leitor da §6): basenames de
arquivos regulares, sem recursão, sem symlink; LOCAL = `Files.list` com as guardas do leitor; DOCKER =
container efêmero hermético `find <dir> -maxdepth 1 -type f` (mesmo molde do `cat`). Diretório
ausente = listagem vazia (run normal). Os nomes devolvidos podem ser escolha do agente — quem os
consome num `readWorkspaceFile` está protegido pelas guardas do próprio leitor (real-path check no
LOCAL; argv exec-form + volume-only no DOCKER).

**Ressalva.** A skill de pesquisa no catálogo (conteúdo em DB, fora deste repo) deve ensinar a
convenção `index.md`; até lá o fallback do único `.md` cobre o comportamento observado.
