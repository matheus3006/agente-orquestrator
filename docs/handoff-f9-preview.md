# Handoff — F.9: Preview real (diferencial #5, ADR-0021 IMPLEMENTADO)

> Sessão 2026-07-05. Backend-first TDD (RED confirmado peça a peça) + review adversarial
> multi-agente com os achados corrigidos na própria sessão. Repo SEM git — nada commitado.
> **Estado: F.9 COMPLETA E PROVADA — a/b/c/d. A demo "contratar → conversar → aprovar → VER"
> aconteceu de verdade: run real do Claude criou um protótipo, a fila de Review o EXECUTOU
> embutido (sandbox) e a aprovação foi decidida VENDO.**

## Piso (verificado)

- Backend **280/280** (`cd backend && mvn -o test`) — eram 256; +24 da F.9.
- Front **vitest 185/185** (eram 174; +11) · `tsc --noEmit` limpo.
- **Flyway V20 aplicada no Postgres real** (`preview_artifact`, append-only).
- Jar F.9 no `:8080` (log `/tmp/orq-f9.log`).

## Os smokes vivos (teams-demo `142f1ac5`)

- **Sem crédito** (process+DOCKER, agente `31b4798e`): issue `7f2667a3` → run `9ced431c`
  SUCCEEDED → `preview_artifact` v1 (120 B) → endpoint devolve `text/plain;charset=UTF-8` +
  `nosniff` + `Cache-Control: immutable`; sem ator → 403.
- **REAL** (claude-code+DOCKER, agente `6fcaff70`): issue `935ce5d9` (`risk_level=5`) → run
  `9fd85ec5` SUCCEEDED → **19.357 bytes de HTML criado pelo Claude** ("Clínica Vida — Fila de
  Atendimento": relógio vivo, senha em atendimento, fila que anda) → capturado ANTES da review
  nascer → card da fila de Review com o PreviewFrame embutido NA VERSÃO EXATA do run → aprovado
  pela UI com reason na tabela review. Houve um 1º ciclo REJEITADO (run `696533d8`, ver gotcha
  do permission-mode) — o loop reject→REOPEN→re-run→approve foi exercitado inteiro.

## O que existe agora (mapa de código)

**Backend**
- **`preview/`** (bounded context nº 7): `PreviewArtifact` (entidade V20, unique
  `(issue_id, version)` declarada p/ valer no H2) · `PreviewArtifactRepository` (projeção
  dinâmica: listas pedem `PreviewArtifactMetadata` — o content de até 2MB NUNCA viaja em lista;
  só o teste pede a entidade) · **`PreviewArtifactCapturer`** (o módulo profundo: contrato
  `.orq/preview/index.html`, cap 2MB, decode UTF-8 **estrito** + rejeição de NUL, sha-256, dedup
  vs última versão, version+1 com retry-once no conflito de ordinal, falha = evento
  `PREVIEW_CAPTURE_FAILED` com reason POR FASE: `CAP_EXCEEDED`/`READ_FAILED`/`INVALID_CONTENT`/
  `PERSIST_FAILED` — a auditoria nunca mente sobre a fase) · `PreviewArtifactController`
  (versões = metadados com 404 de Issue à la IssueRunLedger; conteúdo por id = texto+nosniff+
  immutable, NUNCA `text/html`).
- **Seam**: `ExecutionTarget.readWorkspaceFile(Spec, relativePath, maxBytes)` →
  `WorkspaceFileRead` (found/ABSENT/failure/**capExceeded**). DOCKER: `buildReadArgs` puro
  (`docker run --rm --network none -v vol:/workspace:ro <imagem-do-adapter> cat …` — SEM
  credenciais mesmo que o Spec as carregue; stderr separado distingue ausência de falha).
  LOCAL: real-path anti-symlink + **guard de arquivo regular (FIFO travaria settle+lock)** + cap.
- **`AgentAdapter` ganhou `engineContainer()`** — o capturador pergunta a imagem ao registry;
  o executor segue sem conhecer imagem/credencial.
- **Hook**: `AdapterRunExecutor.execute` — `captureIfPublished` APÓS sucesso, ANTES do
  `settleAfterRun`, fora de tx (teste prova: quando a review nasce, o artefato já existe).
- **Mission Control**: `AgentCard` ganhou `lastPreview` (só metadados no poll de 2s).
- **`ClaudeArgv` ganhou `--permission-mode bypassPermissions`** (ver gotcha).

**Frontend**
- **`PreviewFrame`** (componente único): fetch pelo fetcher autenticado (o iframe não manda
  header → `srcdoc`, nunca `src=`), cache imutável por id (`staleTime: Infinity`), iframe
  `sandbox="allow-scripts"` E NADA MAIS (verificado no DOM vivo: origin opaco, sem `src`,
  `referrerPolicy=no-referrer`), moldura desktop/mobile (mobile com notch).
- `preview-section.tsx`: `IssuePreviewSection` (pills de versão, mais nova aberta, meta
  capturada/tamanho/run) + `ReviewPreviewEmbed` (SÓ a versão do `review.runId`; sem match →
  nota; issue sem captura → nada). Issue page invalida `issuePreviews` junto do settle.
- Badge `preview vN` no card do MC (`.novo` = capturado < 1h), clique → Issue (stopPropagation).
- Helpers puros `src/lib/preview.ts` testados; CSS em `globals.css` (bloco F.9, tokens da casa).

**Skills (banco)**: `criar-prototipo` e `melhorar-prototipo` importadas no catálogo global +
instaladas na teams-demo + set base do cobaia claude. Conteúdo: app-in-a-file standalone (React
UMD + Babel via CDN) com o PASSO OBRIGATÓRIO de publicar em `.orq/preview/index.html`. Staging
de import: `~/.orquestrador/skills-import-f9/` (jar com `--orquestrador.skills.import-root=…`
só na hora de importar).

## Review adversarial (6 confirmados → corrigidos · 2 refutados)

Corrigidos: não-UTF8 transmutava a foto (decode estrito) · listas hidratavam content de 2MB
(projeção) · FIFO travava settle+lock (isRegularFile) · 404 de Issue no versions() · catch-all
mentia a fase (reasons por fase) · conflito de ordinal do zumbi multi-nó (retry-once).
Refutados (registrados, sem ação): hardlink no LOCAL (sem capability nova — LOCAL roda como o
operador) · TOCTOU size→read no LOCAL (não-acionável no modelo v1).

## Gotchas (sangue DESTA sessão)

- **`claude -p` headless NÃO escreve sem `--permission-mode bypassPermissions`**: o 1º run real
  preparou o protótipo inteiro e ficou preso em 3 Writes esperando aprovação interativa que não
  existe em print-mode (RESULT pedia "aprove a permissão"). O run SUCCEEDED mesmo assim (o CLI
  sai 0) — sem artefato. A fronteira de segurança é o CONTAINER (F.8), não o prompt do CLI.
- **Skills materializadas NÃO chegam ao workspace DOCKER**: `SkillMaterializer`/
  `CompanyMemoryMaterializer` escrevem no diretório do HOST (`WorkspaceResolver.resolve`), mas o
  DOCKER monta o VOLUME (`volumeName`) — `/workspace/.claude/` fica vazio no container. A F.9
  criou o LEITOR de volume; falta o **ESCRITOR** (fatia própria; chip de task já aberto). O
  smoke F.9d funcionou pelo fallback previsto: prompt auto-especificado com o contrato.
- O run de Issue usa `agent.runtimeCommand` como prompt (a Issue NÃO entra no prompt no v1) —
  para smokes, o prompt se configura via SQL no agente (dollar-quoting `$cmd$…$cmd$`).
- Teste com FIFO: quem esquecer o guard vai ver o surefire PENDURAR (o RED desse teste é um
  hang, não uma falha) — `pkill -f surefire` e siga.
- H2 compartilhado entre métodos: asserts globais (`count()`) precisam de before/after.

## O que segue aberto (consciente)

- **Escritor de workspace DOCKER** (skills/carta no volume) — chip `task_91945b50`.
- Recorte D do fechado intacto: processo/porta · multi-arquivo · re-captura manual · âncora na
  thread do CEO · thumbnail · GC/retenção · redação de segredos · edição no board.
- Os agentes de smoke seguem na teams-demo como demonstração viva (limpar se virarem ruído);
  o `runtime_command` de ambos aponta para os prompts/comandos do smoke F.9.

## NORTE

O diferencial #5 está VIVO: o dono contrata, conversa, e **aprova vendo a coisa rodar** — o
review gate (ADR-0012) agora tem o momento-demo embutido. Próximo nível natural: o escritor de
workspace (destrava skills em DOCKER de verdade) e, depois, o v2 explícito processo/porta.
