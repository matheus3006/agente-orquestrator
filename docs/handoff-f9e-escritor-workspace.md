# Handoff — F.9e: Escritor de workspace (ADR-0022 ACEITO)

> Sessão 2026-07-05. TDD backend (RED confirmado: 7 testes novos falharam compile antes da
> implementação). Sem migration, sem tela. Repo SEM git — nada commitado.
> **Estado: F.9e COMPLETA — 288/288 (`cd backend && mvn -o test`) + smoke shell com daemon REAL
> provando replace/rm-only/AppleDouble. Falta re-rodar o smoke de PIPELINE do F.9d (skill anexada a
> claude-code+DOCKER aparecendo em `/workspace/.claude/skills`) — requer jar no ar + credencial.**

## O buraco que a fatia fecha
Materializers escrevem no workDir do HOST; agente DOCKER monta o VOLUME nomeado → o container nunca
via skills nem carta (provado no smoke F.9d). A F.9 criou o LEITOR no seam; esta fatia cria o ESCRITOR.

## O que existe agora (mapa de código)
- **`ExecutionTarget.writeWorkspaceFiles(Spec, List<relativePath>)`** + record `WorkspaceFilesWrite`
  (OK | failure legível). Contrato no javadoc: replace por subtree, host VENCE, paths são constantes
  do pipeline.
- **`LocalExecutionTarget`**: identidade (workDir É o workspace).
- **`DockerExecutionTarget`**: `buildWriteArgs` (rm+extract no MESMO sh; `-i` só com payload; sem rede,
  sem credencial, imagem do adapter) + `buildTarArgs` + `hostTar` (ProcessBuilder com
  `COPYFILE_DISABLE=1`) — puros e testáveis sem daemon; `writeWorkspaceFiles` impuro bombeia
  tar-do-host → stdin do container.
- **`AdapterRunExecutor`**: publica após materializers e ANTES do adapter — `.claude/skills` sempre,
  `.claude/CLAUDE.md` só em turno (espelha QUEM materializou). Falha = `RunOutcome.failure` legível
  (Issue→BLOCKED; turno→FAILED). Spec do escritor espelha o do capturador de preview:
  `adapter.engineContainer().image()`, zero credentialMounts.
- **Constantes públicas**: `SkillMaterializer.SKILLS_SUBDIR` e `CompanyMemoryMaterializer.CLAUDE_MD`
  (os contratos de lugar; o executor referencia — nada de string duplicada).
- **Testes**: `DockerExecutionTargetArgsTest` +4 (argv escritor/rm-only/tar/AppleDouble) ·
  `LocalExecutionTargetWriteTest` (identidade) · `WorkspaceWriteOnRunTest` (fiação pelo pipeline REAL
  com registry decorado via `@TestConfiguration @Primary ExecutionTargets` — o decorator é instância
  anônima DENTRO do bean para não duplicar o tipo LOCAL no registry default; 3 testes: Issue publica
  skills com Spec hermético, turno publica skills+carta, falha do escritor → BLOCKED).
- **FakeTargets** dos 3 adapter-tests ganharam o método (OK fixo).

## Decisões (ADR-0022 — não reabrir)
(a) mesmo seam, não `WorkspaceWriter` paralelo · (b) replace por subtree com host-vence (idempotência
dos materializers atravessa) · (c) lista espelha quem materializou — `CLAUDE.md` de Issue escrito pelo
AGENTE nunca é tocado · (d) falha do escritor = falha do RUN (assimetria consciente com o leitor
best-effort) · (e) tar pipe, não `docker cp` nem tar-em-Java.

## Gotchas (sangue desta sessão)
- **bsdtar do macOS empacota AppleDouble** (`._SKILL.md`…) sem `COPYFILE_DISABLE=1` — apareceu no
  smoke real, invisível aos testes de argv; pinado por teste no `hostTar` (env, não argv).
- Bean de teste implementando `ExecutionTarget` explode o registry default (`toMap` com tipo LOCAL
  duplicado) — por isso o decorator anônimo dentro do `@Bean @Primary ExecutionTargets`.
- `mvn -o -q surefire:test` engole o output — usar sem `-q` para ver `Tests run`.
- Volume novo herda ownership do mountpoint da imagem (gotcha F.8 continua): extração roda como USER
  da imagem do adapter; nas `orq-*` é `node` e `/workspace` já nasce dele.

## Próximo passo natural
Smoke de pipeline (o F.9d re-executado): subir o jar, anexar `criar-prototipo` ao agente
claude-code+DOCKER da teams-demo (`6fcaff70-…`), rodar e conferir `/workspace/.claude/skills` no
volume (`docker run --rm -v orq_142f1ac5_6fcaff70:/workspace alpine:3 find /workspace/.claude`).
Depois dele, o preview real fecha o loop *contratar → conversar → aprovar → VER* com skills vivas
no DOCKER.
