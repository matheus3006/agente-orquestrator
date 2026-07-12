# ADR-0042: Hook PreToolUse mínimo — skills read-only (D6) + cinto de sessão (D7)

**Status:** Aceito (2026-07-12) · grill motor-nativo fechado em 2026-07-11 (`docs/handoff-grilling-motor-nativo.md`, D6/D7); fatia F-N6 DEVOLVIDA da R6-A ("não coube como carona": pede subsistema de materialização de hooks próprio + gotcha de trust do codex) e executada dedicada na rodada 9 (R9-A).

## Contexto
Zero hooks no backend inteiro antes desta fatia (`grep` em `backend/src/main/java`: nada — laudo motor-nativo §1.5). O grill fechou D6 (enforcement mínimo é REQUISITO EMBUTIDO nas fatias que ampliam autonomia, não fatia separada — mas a parte "skills read-only por hook `PreToolUse`" ficou explicitamente para esta carona/follow-up) e D7 (o home-volume compartilhado é ACEITO no v1, reforçado por um cinto: negar leitura dos diretórios de sessão dos motores fora do próprio workspace — defesa contra acesso acidental e contra o caminho fácil de prompt injection via web da delegation).

## Decisão

### 1. O seam: `HookMaterializer` atrás de `WorkspaceConvention` (irmão do `SkillMaterializer`)
Mesmo molde da ADR-0032: `WorkspaceConvention` ganha dois campos DADOS — `hooksFile` (arquivo de configuração nativo: `.claude/settings.json` · `.codex/hooks.json`) e `hooksScript` (`.claude/hooks/pretooluse-guard.js` · `.codex/hooks/pretooluse-guard.js`). O `AdapterRunExecutor` chama `HookMaterializer.materialize(workDir, convention)` no MESMO ponto em que chama o `SkillMaterializer` — o adapter continua só declarando dados, quem escreve é o materializer.

### 2. Mecânica empírica confirmada contra os CLIs reais (claude 2.1.206 / codex 0.142.5)
Sondada ao vivo nesta faixa (não estava no laudo original, que parou em "hooks existem, exit 2 bloqueia"):
- **A forma de configuração é IDÊNTICA nos dois motores**: `{"hooks":{"PreToolUse":[{"matcher":"...","hooks":[{"type":"command","command":"..."}]}]}}`. Só o ARQUIVO muda — claude lê o bloco `hooks` de dentro do `settings.json` do workspace; o codex é dono do `hooks.json` inteiro (chave de evento em **PascalCase**, `"PreToolUse"` — não `snake_case`, apesar do config.toml do codex usar snake_case em outros lugares).
- `command` é resolvido **relativo ao `cwd`** do run nos dois motores (`node .claude/hooks/pretooluse-guard.js`) — portável entre LOCAL (workDir do host) e DOCKER (`/workspace`), o mesmo motivo pelo qual a carta/skills usam caminho relativo.
- O payload do stdin também converge: `{cwd, hook_event_name: "PreToolUse", tool_name, tool_input, ...}`. `tool_name` no codex usa os MESMOS nomes do claude onde faz sentido (`"Bash"` pro shell) — mas o mutador de arquivo é `"apply_patch"` (não há `Write`/`Edit` distintos no codex): o path da mutação vive DENTRO do texto do patch (`tool_input.command`, linhas `*** Add File: <path>`), não num campo `file_path` estruturado como no claude.
- **Um script Node único (`HookMaterializer.GUARD_SCRIPT`) serve os dois motores** — Node é garantido no PATH nas duas imagens (`node:22-bookworm-slim`, `infra/engines/*/Dockerfile`) e no host de qualquer dev (os 2 CLIs são pacotes npm). Zero if/else por engine no conteúdo do script; só o arquivo de config e o path do script mudam, e isso já vem de `WorkspaceConvention`.
- **Gotcha de trust do codex** (motivo da devolução da R6-A): hooks não-managed exigem `--dangerously-bypass-hook-trust` (`CodexArgv`, confirmado no `--help` do binário pinado rust-v0.142.5) — sem a flag, o codex roda mas os hooks silenciosamente NÃO disparam. Como hooks materializam em TODO run do codex, a flag é incondicional (`hooksEnabled=true` sempre no `CodexAdapter`).
- **Achado novo, registrado como ressalva**: se `hooks.json` falha ao PARSEAR, o codex loga um `item.completed type=error` mas **continua o run sem enforcement** (fail-open no parse, não só no payload) — comportamento nativo do CLI, fora do nosso controle; a defesa é o `HookMaterializerTest` travar o formato escrito.

### 3. Lógica do guard (D6 + D7, o MESMO script nos dois motores)
- **(a) Skills read-only (D6):** nega `Write`/`Edit` (claude) e `apply_patch` (codex, path extraído do corpo do patch) cujo alvo caia sob `.claude/skills` OU `.codex/skills` (checa os DOIS prefixos sempre — barato e cobre o caso de espelho residual). `Read` de skill continua permitido: D6 é read-only, não isolamento de leitura.
- **(b) Cinto de sessão (D7):** nega `Read` (claude, `tool_input.file_path`) e heurística de substring em `Bash`/`apply_patch` (`tool_input.command`) que referencie `~/.claude/projects` ou `~/.codex/sessions` — nas formas absoluta, `~/` e `$HOME/`. Os diretórios são resolvidos em RUNTIME via `os.homedir()` dentro do próprio script guarda (herda o `HOME` do processo pai, correto tanto no host quanto no container `node`), não materializados como caminho fixo.
- Payload ilegível ou `tool_name` não rastreado ⟹ **fail-open** (exit 0): o hook eleva a barra, nunca deve travar um run por bug do próprio guarda.

### 4. Escopo de materialização: TODO run que recebe skills (divergência registrada do verbatim)
O §3 do handoff do grill diz literalmente "skills PARA PERSONAS"; o D6 em si é genérico ("fatia que amplia autonomia não shippa sem o teto"). Adotado: materializar o hook em **todo run que recebe skills materializadas** (turno de conversa OU Issue/heartbeat, qualquer agente) — não só personas de CEO. Razão técnica: `SkillMaterializer` já roda incondicionalmente em `AdapterRunExecutor.runToOutcome` antes do branch turno/Issue; gatear o hook a "só personas" exigiria um segundo predicado (checar `CeoPersona.fromDbValue`) só para o hook, divergindo do MESMO ponto de chamada que já materializa skills — mais complexidade por menos cobertura. O mínimo de aceite (D6/D7) são as personas; cobrir todo run é estritamente mais seguro e mais simples de implementar.

### 5. Faxina incondicional, simétrica à de skills
A convenção INATIVA tem `hooksFile`/`hooksScript` apagados no MESMO loop incondicional que já apaga `skillsSubdir` (não gateado por `turno != null`, ao contrário do `memoryFile`) — espelha o fato de que hooks materializam sempre, não só em turno.

## Alternativas rejeitadas
- **`permissions.deny` declarativo no claude (sem hook/script)** — cobriria (a) com glob puro, mas NÃO cobre (b): negar leitura condicionada a "fora do workspace" (comparação de path relativo em runtime) não é uma regra de permissão estática, e o codex não tem equivalente declarativo (só `command`). Um script único nos dois motores é mais simples que dois mecanismos heterogêneos.
- **Script por-engine (claude.js × codex.js)** — rejeitado após a sonda empírica mostrar que a forma de configuração e o payload convergem o suficiente para um script único; manter dois arquivos seria duplicação sem ganho.
- **Restringir a materialização a personas de CEO** — ver §4: mais complexidade (predicado extra) por MENOS cobertura; rejeitado.

## Ressalvas honestas
- A checagem de leitura em `Bash`/`apply_patch` é um **substring heurístico** sobre o texto do comando — não resolve symlink, não entende shell de verdade (encode/indirection escapa: `base64 -d <<< ... | sh`, variável indireta, etc.). É cinto (eleva a barra), não isolamento — coerente com a decisão D7 do grill.
- `Glob`/`Grep` não são cobertos (podem enumerar/ler conteúdo de diretórios negados por outro caminho) — fora do mínimo D6/D7, candidata ao enforcement AMPLO pós-v1.
- O codex falha **fail-open** se `hooks.json` estiver malformado (loga erro, mas roda sem o hook) — comportamento nativo do CLI; a defesa nossa é testar o JSON escrito (`HookMaterializerTest`), não algo que o produto controle em runtime.
- Nenhuma nota-GATE nova na ADR-0016 nesta fatia — a nota bloqueante do D7 (isolamento real, config-dir por company) fica para o fecho documental, já registrada no ledger.

## Relaciona
ADR-0032 (o seam `WorkspaceConvention` — precedente de materialização por-adapter) · ADR-0038 (D6, tetos nativos de subagente — a OUTRA metade do requisito de enforcement embutido) · ADR-0016 (D7, home-volume aceito no v1 — este hook é o cinto de carona) · ADR-0014 (pino dos CLIs — `--dangerously-bypass-hook-trust` confirmado no source do tag pinado rust-v0.142.5, mesmo padrão de defesa da R5-B).
