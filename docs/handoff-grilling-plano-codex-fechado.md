# Handoff FECHADO — Grilling "Plano Codex" (F.11 · segundo motor vivo)

> **Entregável** da sessão de grilling 2026-07-05 (mesma sessão da consolidação pós-F.10).
> Formato: **A** decisões/porquê · **B** diffs de ADR/CONTEXT · **C** plano de execução ·
> **D** recorte + critérios de aceite.
> **Q1–Q6 fechadas 6/6, todas na recomendação.** Zero código nesta sessão.
> ADR novo: **ADR-0024** (criado e Aceito). CONTEXT.md **sem diff** (nenhum termo novo; só
> ganharia linha se o fallback AGENTS.md for acionado na execução).
> Nenhuma decisão dos ADRs 0002/0014/0015/0016/0021/0022/0023 reaberta — todas consumidas.
> Piso herdado da consolidação: backend **328/328** · V21 é a última · jar novo no :8080 ·
> smoke do import seletivo OK (company descartável a4e899b1, teams-demo intocada).

---

## A — DECISÕES + PORQUÊ (resumo; racional completo no ADR-0024)

### A.1 (Q1) — A próxima fatia é o **plano Codex** (escolhido do menu de recortes abertos)
Leftover consciente mais antigo (F.8) e o único item do menu que prova tese estrutural: o
contrato inteiro de workspace (escritor → skills → `.orq/preview/*`) foi desenhado agnóstico de
motor, mas nunca exercitado com um segundo motor. Menu preterido (continua aberto): multi-arquivo
v1 (enfraquecido — v2 serve multi-arquivo via servidor estático) · GC/retenção · thumbnail ·
redação de segredos · edição no board · recortes do v2.

### A.2 (Q2) — Pré-requisito duro CONFIRMADO: conta Codex existe; host JÁ logado
Operador tem conta e o host roda `codex-cli 0.142.5` — **mesma versão do pin da imagem** (zero
deriva). Login do host NÃO vale para o container (ADR-0002: refresh token rotaciona; login nasce
no container) — mas destrava o alvo LOCAL de graça (herda o env do host, ADR-0016).

### A.3 — Descoberta da exploração: a fatia é PROVA, não construção
JÁ EXISTE da F.8: `CodexAdapter`+`CodexArgv`+`CodexStreamParser` testados (fixtures) ·
imagem `orq-codex:0.142.5` construída · `setup-codex.sh` pronto (device-auth, credencial-em-
arquivo via `config.toml`, volume `orq_codex_creds`) · montagem seletiva provada unit.
FALTA: volume `orq_codex_creds` (login nunca rodou) · qualquer run real (parser nunca viu o
stream do CLI de verdade — risco confesso no javadoc) · prova de skills/preview com codex.
Retomada de sessão: simétrica e ausente nos DOIS motores (capturada, não retomada) — sem gap
específico do codex, nada a decidir.

### A.4 (Q3) — DoD = nível **(c)**: a pilha inteira
CLI real × parser (StepTrace legível, thread_id, custo `provider=openai`) + credencial seletiva
viva + skill com prompt neutro → preview **v1 foto** aprovado VENDO → DONE + **v2 servidor**
(`criar-app-servidor` → `server.json` → manifest → start → app viva no board) aprovado VENDO →
DONE. Degradação graciosa se o CLI derrapar cedo: fechar em (b) ou (a) com o achado documentado —
mas a mira é (c).

### A.5 (Q4) — Skills chegam ao codex por **PROMPT**; fallback AGENTS.md PRÉ-decidido
`.claude/skills/` é contrato do workspace (ADR-0022), não config de motor — não renomeia. O
prompt neutro canônico já nomeia o caminho; codex sabe ler arquivos. Se o smoke provar que o
codex ignora as skills: `SkillMaterializer` escreve também `AGENTS.md` na raiz —
**incondicional** (não machuca o claude; materializer condicionado a motor = vazamento de seam).
Fallback só mediante prova; aplicado sem reabrir grilling.

### A.6 (Q5) — Roteiro LOCAL-first, DOIS cobaias, wakeup por API
Ato 1: smoke LOCAL (cobaia `codex+LOCAL`, login do host, valida parser/skills/v1 barato, debug
direto). Ato 2: `setup-codex.sh` interativo (operador presente) — só após o ato 1 passar.
Atos 3/4: smokes DOCKER v1 e v2 (cobaia `codex+DOCKER` definitivo). Dois cobaias porque trocar
target por SQL é mutação invisível. Wakeup por API porque a conversa CEO é pipeline, não motor.

### A.7 (Q6) — ADR-0024 curto e honesto; recorte explícito
A maioria das decisões estruturais mora em 0014/0016/0021/0022/0023 — o ADR referencia em vez de
repetir. Rejeitadas registradas: renomear skills-path · AGENTS.md antecipado · cobaia único
mutado · provar conversa CEO · upgrade preventivo do pin · copiar login do host (reafirmação).

---

## B — DIFFS DE ADR / CONTEXT.md (APLICADOS nesta sessão)

- **ADR-0024 "Segundo motor vivo — prova do eixo motor-agnóstico" — CRIADO, Aceito**
  (`docs/adr/0024-segundo-motor-vivo-prova-eixo-motor.md`): 4 decisões, 6 alternativas
  rejeitadas, 5 recortes, 3 ressalvas honestas.
- **CONTEXT.md — SEM diff**: nenhum termo novo (Adapter/EngineContainer/Motor já cobrem). Se o
  fallback AGENTS.md entrar na execução, aí registrar no verbete do escritor/workspace.
- **Sem emenda a ADRs existentes.**

---

## C — PLANO DE EXECUÇÃO (sessão nova; kickoff via /nova-sessao)

**F.11a — Smoke LOCAL (sem login novo):**
1. Criar cobaia `smoke-codex-local` na teams-demo (`codex` + LOCAL, model/effort null = default
   do plano), prompt neutro canônico, skill `criar-prototipo` anexada.
2. Issue risk 5 → wakeup por API → run real (~2-4min; poll com folga).
3. Verificar: SUCCEEDED · StepTrace legível (parser × stream real — O risco da fatia) ·
   `thread_id` capturado · custo `provider=openai` com tokens · preview v1 capturado.
4. Se o parser derrapar: bugfix com /tdd (fixture NOVA colhida do stream real) e re-rodar.
5. Se o codex ignorar a skill: acionar fallback AGENTS.md (decisão A.5, sem re-grilling) —
   materializer escreve `AGENTS.md` incondicional, teste no `SkillMaterializerTest`.

**F.11b — Login do container (operador presente):**
`infra/engines/setup-codex.sh` — device-auth no browser, uma vez; verificação do `auth.json`
no volume `orq_codex_creds` é parte do script. NUNCA copiar do host (ADR-0002).

**F.11c — Smoke DOCKER v1 (foto):**
Cobaia definitivo `codex` + DOCKER na teams-demo, prompt neutro, `criar-prototipo` → issue risk 5
→ wakeup → run DOCKER real → preview v1 pelo contrato → **aprovar VENDO → DONE**. Conferir no
container: monta SÓ `orq_codex_creds` (montagem seletiva viva).

**F.11d — Smoke DOCKER v2 (servidor):**
Mesma cobaia, issue nova, `criar-app-servidor` → `server.json` → manifest no settle → start pela
board → **app viva** (fetch à própria API) → aprovar VENDO → DONE. Verificações herdadas: 4º
container SEM credencial, `:ro`+tmpfs, porta só loopback, caps. TTL/stop derruba.

**F.11e — Docs:** atualizar handoff da execução + marcar promessa "Codex ganha de graça" como
FATO (ou bug conhecido) nos achados; kickoff da próxima fatia se a sessão render.

---

## D — CRITÉRIOS DE ACEITE DA FATIA

1. Run LOCAL codex SUCCEEDED com StepTrace legível e custo registrado (parser validado contra o
   CLI real — fixture nova colhida se houver deriva).
2. `orq_codex_creds` vivo com `auth.json` (login nascido no container).
3. Run DOCKER codex real: container monta SÓ `orq_codex_creds`; run SUCCEEDED.
4. Skill em uso com prompt neutro no codex: preview v1 publicado pelo contrato, capturado,
   **aprovado VENDO → DONE**.
5. v2 com codex: manifest registrado, servidor sobe, app viva no board, aprovado VENDO → DONE;
   4º container sem credencial, `:ro`+tmpfs, loopback, caps.
6. Suíte backend verde (piso 328 + eventuais novos de bugfix/fallback); front intocado (nada da
   fatia toca UI).
7. Zero mudança em contrato provado (skills-path, `.orq/preview/*`, seam ExecutionTarget) — a
   fatia prova, não muda.

## Higiene e gotchas herdados (para a sessão de execução)
- Cobaia claude `6fcaff70` NÃO se toca (runtime_command já está no prompt neutro canônico —
  copiar dele o texto para os cobaias codex).
- teams-demo `142f1ac5`: 81 skills FICAM (decisão do operador) · smokes vivos como demo
  (agente 215a276b, issues 5f207cc0, e5946ef0, c408ecc2, aa11f9e0) — NÃO limpar.
- Company descartável do smoke de import `a4e899b1` pode ser ignorada (inócua).
- Gotchas: wakeup por API (nunca INSERT) · `docker exec -i` para heredoc · headers curl literais
  (zsh não faz word-splitting) · DONE não reabre (REJECT → unblock REOPEN) · runs reais ~2-4min
  (poll com folga) · dollar-quoting `$cmd$…$cmd$` para runtime_command via SQL.
- Ambiente: jar 328 no :8080 (pid 13741, `/tmp/orq-f10.log`, V21) · db
  `agente-orquestrator-db-1` · board user `b0a7d000-0000-4000-8000-000000000001` · dev server
  :3000 · imagens `orq-claude-code:2.1.201` / `orq-codex:0.142.5` · Repo SEM git — não commitar.

## PRÓXIMO PASSO
Sessão de execução **F.11a → F.11b → F.11c → F.11d → F.11e**, kickoff gerado por /nova-sessao ao
fim desta sessão. F.11b exige o OPERADOR presente (login interativo) — agendar o momento com ele.
ADR-0015: ao cruzar ~50-60% do contexto, destilar handoff parcial e recomeçar.
