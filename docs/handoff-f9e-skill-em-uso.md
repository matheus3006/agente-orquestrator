# Handoff — F.9e: Skill em uso REAL (escritor validado em produção)

> Sessão 2026-07-05 (madrugada, pós-escritor). Esta sessão NÃO escreveu código de produção:
> validou a fatia do escritor (ADR-0022) e fechou o ciclo que faltava — **skill consumida em uso
> real por run DOCKER com prompt NEUTRO**. Repo SEM git — nada commitado.
> **Estado: F.9e COMPLETA E PROVADA em produção — v1 por skill, v2 por melhorar-prototipo na
> mesma Issue, dedup nos DOIS lados, seletor v1/v2 vivo no board.**

## Piso (verificado)

- Backend **288/288** (`cd backend && mvn -o test`) — piso novo da fatia do escritor; a suíte
  rodou DUAS vezes verde nesta sessão (a 1ª deu 287 porque a sessão paralela ainda escrevia).
- Front intocado (fora de escopo; vitest não rodado de propósito).
- **Jar F.9e no `:8080`** (log `/tmp/orq-f9e.log`; Flyway "up to date", V20 é a última).
  O jar CONTÉM o fix `COPYFILE_DISABLE` (conferido por `unzip -p … | strings` na classe).

## A PROVA que faltava (smokes desta sessão, teams-demo `142f1ac5`)

**1. Skill em uso real — prompt NEUTRO gera preview (ADR-0021 §3 provado em produção):**
- Cobaia claude (`6fcaff70`, claude-code+DOCKER) com `runtime_command` trocado por SQL para:
  *"Você tem skills instaladas em .claude/skills/ — use a que se aplicar. Crie um protótipo da
  tela de agenda semanal de uma clínica."* — **zero menção a `.orq/preview` ou contrato**.
- Issue `c408ecc2` (risk 5) → wakeup via API → run `8177a15e` SUCCEEDED (~3min20s) →
  **`preview_artifact` v1 (37.210 bytes)**. Quem carregou o contrato foi a SKILL.
- **Volume conferido DURANTE o run** (container efêmero `--network none … :ro`):
  `/workspace/.claude/skills/criar-prototipo/SKILL.md` e `melhorar-prototipo` PRESENTES —
  o escritor atravessou a árvore. **0 arquivos `._*`** (fix AppleDouble vivo). StepTrace mostra
  `→ Skill` e "vou publicar no contrato de lugar `.orq/preview/index.html`".

**2. v2 na MESMA Issue (loop do produto inteiro):**
- REJECT da review v1 (reason = feedback "quero tema escuro") → BLOCKED → unblock REOPEN →
  prompt neutro de melhoria (*"Deixe o protótipo com tema escuro."*) → run `70722ee2` SUCCEEDED →
  **v2 (37.271 bytes, hash ≠)** na mesma Issue; **v1 permanece** (append-only) → review nova
  APROVADA vendo o v2 → Issue DONE. StepTrace do v2: skill invocada, tokens `:root` reescritos
  para dark, republicado no contrato.

**3. Dedup por hash nos DOIS lados (produção):**
- *Mudado versiona*: v1→v2 acima (hashes `a9baa72b…` → `f4c13912…`).
- *Igual NÃO versiona*: issue `aa11f9e0` (risk 5) no agente process (`31b4798e`, printf fixo):
  run 1 → v1 (120 B) → REJECT → REOPEN → run 2 **idêntico** SUCCEEDED → `count=1, max_v=1`
  (nada versionou; o settle seguiu normal → PENDING_REVIEW de novo → aprovada, DONE).

**4. Seletor v1/v2 no board (dev server `:3000`, board user `b0a7d000-…0001`):**
- Página da Issue `c408ecc2`: pills **v2/v1** (mais nova aberta), meta por versão
  ("36.4 KB · run 70722ee2" ↔ "36.3 KB · run 8177a15e"), clique troca o iframe
  (`title="Preview v1/v2 do protótipo"`). Sandbox intacto no DOM vivo: `sandbox="allow-scripts"`,
  sem `src`, com `srcdoc`. Screenshot: agenda semanal EM TEMA ESCURO rodando na moldura.

## Gotchas (sangue DESTA sessão)

- **Wakeup por INSERT direto em `agent_wakeup_request` NÃO despacha nada**: o dispatcher varre
  `heartbeat_run` QUEUED; quem cria o run é `WakeupService.enqueue`. Use
  `POST /api/companies/{c}/agents/{a}/wakeups` (header `X-Board-User`).
- **`docker exec` sem `-i` engole heredoc** — o SQL "roda" sem erro e sem efeito. Sempre
  `docker exec -i … psql … <<'SQL'`.
- **zsh NÃO faz word-splitting de variável** (`H='-H a -H b'; curl $H` passa UM argumento):
  com `curl -s` a falha é silenciosa. Headers sempre literais.
- **`DONE` não reabre**: REOPEN só existe de BLOCKED (`unblock`). O caminho para v2 na mesma
  Issue é `review REJECT → unblock REOPEN → re-run → review APPROVE` — e é o loop do produto.
- `docker run -v nome:…` **auto-cria volume vazio**: conferir o volume ANTES do 1º run do agente
  cria casca vazia (inócua — o escritor faz replace — mas polui o `docker volume ls`).
- Runs reais do claude nesta máquina: ~2-4 min; o poll de smoke precisa de folga (o 1º poll de
  60×5s estourou com o run ainda RUNNING).

## Estado vivo ao fim (ambiente)

- db up (`agente-orquestrator-db-1`, V20) · jar F.9e no `:8080` (`/tmp/orq-f9e.log`) · dev server
  do board no `:3000` · imagens `orq-claude-code:2.1.201`/`orq-codex:0.142.5` · credencial viva
  em `orq_claude_creds`.
- Issues novas de smoke (vivas como demonstração): `c408ecc2` (claude, DONE, **v1+v2**) e
  `aa11f9e0` (process, DONE, dedup). As antigas seguem: `7f2667a3`, `935ce5d9`.
- `runtime_command` do cobaia `6fcaff70` ficou *"Deixe o protótipo com tema escuro."* (prompt do
  smoke v2) — trocar por SQL (`$cmd$…$cmd$`) quando for reusar.
- Volumes de smoke: `orq_142f1ac5_6fcaff70_c408ecc2` e `orq_142f1ac5_31b4798e_aa11f9e0`.

## O que segue aberto (consciente — recorte D da F.9 intacto)

- Processo/porta (v2 do preview) · multi-arquivo · re-captura manual · thumbnail · GC/retenção ·
  redação de segredos · edição no board · plano Codex (`setup-codex.sh`, leftover F.8).
- Limpar os agentes/issues de smoke da teams-demo quando virarem ruído.

## NORTE

O ciclo do diferencial #5 fechou de ponta a ponta SEM gambiarra de prompt: **contratar →
conversar → skill entra no container (escritor) → agente publica pelo contrato (skill) →
capturar (leitor) → aprovar VENDO → pedir melhoria → v2 na mesma Issue**. Próximo nível natural:
preview processo/porta (v2 explícito) ou limpeza/GC dos artefatos de smoke.
