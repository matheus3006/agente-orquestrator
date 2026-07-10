# Handoff — GRILLING: fatia AGENTS.md (G3) — o adapter declara sua convenção de workspace (2026-07-09)

> **Tipo:** registro de grill FECHADO (nenhuma linha de produção foi escrita nesta sessão).
> **Intenção:** decidida no grill de 2026-07-09 (§B/G3 de `handoff-grilling-insatisfacoes-funcionamento-ceo.md`)
> e **não reaberta** aqui — o agente codex roda "pelado" (carta + skills materializam só em `.claude/`,
> que o codex não lê); o adapter passa a DECLARAR sua convenção de workspace, como já declara o
> `EngineContainer`. **Esta sessão fechou o MECANISMO** (D1–D10 + M1–M10 + VENDO), com cada premissa
> validada contra o código por agente Explore (P1–P8) e por sondas empíricas no codex real (S1–S3).
> **Fora do escopo (intocados):** prompt do turno (template inline G2 fica), pacote `handoff/`
> (ADR-0030), parser do codex (PR #2), Fatia 2 / G5 / caso B, StepTrace (ADR-0010).

---

## §S — Sondas empíricas (o que mudou o desenho)

- **S1 — binário codex 0.142.5 (o pinado nas imagens):** o spec do AGENTS.md está EMBUTIDO nas
  instruções-base do CLI ("Repos often contain AGENTS.md files… scope is the entire directory
  tree"); existe `project_doc_max_bytes` (cap de tamanho do project doc — **mataria skills inline**
  por truncamento silencioso).
- **S2 — sonda viva (`codex exec` em workspace-sonda, sessão `019f49c7…`, 8.865 tokens):**
  (a) **AGENTS.md da raiz é lido** — palavra-secreta plantada (`LARANJA-VERDE-77`) respondida;
  (b) **skills por-projeto em `<workspace>/.codex/skills/<chave>/SKILL.md` são descobertas
  NATIVAMENTE** — o codex listou a skill-sonda, leu o corpo sozinho (progressive disclosure) e
  devolveu a palavra-chave (`BANANA-AZUL-42`); (c) roots de descoberta confirmados:
  `<cwd>/.codex/skills` · `~/.agents/skills` · `~/.codex/skills/.system` · plugins.
  **Mesma forma exata da árvore `.claude/skills/`** — o SkillMaterializer é reusável mudando o prefixo.
- **S3 — workspace real do Atlas (o "pelado" em disco):** `_agent/.claude/CLAUDE.md` +
  `.claude/skills/distillation/SKILL.md` (materializados onde o codex nunca leu) + 4 bilhetes
  `.orq/handoff/*.md` da era pré-G2 (únicos arquivos NÃO-regeneráveis do banco —
  **backup já feito** em `docs/anexos/bilhetes-atlas-pre-g2/`).
- **Gotcha de sonda:** `codex exec` sem TTY pendura esperando stdin — scripts de VENDO devem fechar
  (`</dev/null`). O backend não sofre (o ExecutionTarget não anexa stdin interativo — reconferir na execução).

## §P — Premissas validadas no código (Explore, 2026-07-09)

| # | Premissa | Veredito · evidência |
|---|----------|----------------------|
| P1 | Zero menção a AGENTS.md no backend | ✅ grep vazio em `backend/src` (main+test) |
| P2 | `writeWorkspaceFiles` é replace-por-subtree e aceita ARQUIVO como subtree | ✅ `ExecutionTarget.java:42-43` (contrato) · `DockerExecutionTarget.java:82-88` (`rm -rf` + tar só do existente) · `.claude/CLAUDE.md` já é subtree-arquivo (`AdapterRunExecutor.java:186`) |
| P3 | Adapter pode resolver ANTES da materialização | ✅ `AdapterRegistry.forType` é `Map.get` puro (`AdapterRegistry.java:20-26`), lança em tipo desconhecido — mover ADIANTA o fail-fast p/ antes do checkout |
| P4 | Só o executor consome `SKILLS_SUBDIR`/`CLAUDE_MD` | ✅ `AdapterRunExecutor.java:186-187`; testes pinam as STRINGS literais (não o símbolo) |
| P5 | ClaudeCodeAdapter e ProcessAgentAdapter declaram EngineContainer estático; process sobrescreve `acceptsPromptAsCommand=false` | ✅ — o default-na-interface é precedente estabelecido |
| P6 | Loop de handoff: **continuação fresca passa pelo executor** (re-materializa espelho ✅); **resume-cirúrgico BYPASSA** (`ContextHandoffLoop.java:99` executa o adapter direto, herda o volume já materializado pelo turno disparador → o espelho já está lá) | ✅ nada a mudar no `handoff/` |
| P7 | Layout `.claude/` pinado por ~8 classes de teste | ✅ Skill/CompanyMemory Materializer(-ização)Test · WorkspaceWriteOnRunTest · Local/DockerExecutionTarget(Write/Args)Test · Distillation/AttachedSkill…Test — confirma: mudança de seam com TDD próprio |
| P8 | Frontend não consome os paths (só exibe texto); docs referenciam | ✅ `ceo-conversa.tsx:590` é texto de UI |

---

## §D — Decisões (com o racional arrancado)

| # | Decisão | Racional |
|---|---------|----------|
| **D1** | **Priorização CONFIRMADA pelo dono:** G3 → Fatia 2 (3 personas) → G5 (ID sequencial) → caso B. | As skills das personas da Fatia 2 não chegam ao codex sem G3; persona nascida em motor codex nasceria "pelada" — G3 é fundação. |
| **D2** | **Forma do espelho = NATIVA:** carta → `AGENTS.md` (raiz do workspace, corpo VERBATIM — irmão exato do `.claude/CLAUDE.md`); skills → `.codex/skills/<chave>/SKILL.md`. **Nada de skills inline nem índice no AGENTS.md.** | S2 provou os DOIS canais nativos; S1 (cap `project_doc_max_bytes`) mata inline por truncamento silencioso; a árvore tem a MESMA forma da `.claude/skills/` → materializer reusado mudando o prefixo. A intenção do grill anterior ("AGENTS.md espelhando carta + skills") fica intacta — o mecanismo ficou mais nativo do que se imaginava: skills vão AO LADO, não DENTRO. |
| **D3** | **O seam:** `AgentAdapter.workspaceConvention()` devolve um **record de DADOS** `WorkspaceConvention(memoryFile, skillsSubdir)` com fábricas `claude()` (`".claude/CLAUDE.md"`, `".claude/skills"`) e `codex()` (`"AGENTS.md"`, `".codex/skills"`). **DEFAULT na interface = `claude()`** → ClaudeCodeAdapter e ProcessAgentAdapter não mudam nenhuma linha; só CodexAdapter sobrescreve. Consumidores: `AdapterRunExecutor` (subtrees + ordem) e os 2 materializers (caminho por parâmetro). | Espelho fiel do exemplar `EngineContainer` ("o adapter declara" DADOS; consumidores fazem o trabalho). Objeto-com-comportamento moveria I/O para o pacote adapter sem ganho (a composição é trivial: copiar carta + copiar skills). If/else no executor reabriria o executor no 3º motor (contra ADR-0004). |
| **D4** | **Ordem no executor:** a resolução do adapter MOVE para antes da materialização do SkillSet. | Validado P3: `forType` é lookup puro; de carona, tipo-desconhecido falha ANTES do checkout da Issue (hoje falharia depois, com a Issue já em lock). |
| **D5** | **Contrato de re-materialização = o MESMO de hoje:** idempotente, por-Run, reescreve-ou-remove (sem carta → `AGENTS.md` removido). `AGENTS.md` só em run de **TURNO** (espelho exato do `.claude/CLAUDE.md`); run de Issue = só skills. | O contrato atual é o que garante "nunca servir carta velha"; o espelho não inventa contrato novo. Turno-only preserva o desenho ADR-0018 (carta = memória do CEO em conversa). |
| **D6** | **Faxina permanente (mecanismo):** todo Run escreve a convenção ATIVA e **APAGA os caminhos das INATIVAS** — Docker: subtrees = UNIÃO das convenções conhecidas (replace-por-subtree remove o que não existe no host, de graça — P2); LOCAL: delete explícito. União por tipo de run: Issue = skillsSubdirs; turno = + memoryFiles. `.orq/*` NUNCA entra (M6 da ADR-0030 intacto). | Troca de motor nunca deixa cérebro velho no chão. Caso concreto que JÁ existe: o `.claude/` do Atlas (S3) com carta desatualizada num workspace que o codex explora por busca — divergência silenciosa armada. |
| **D7** | **Faxina única (operação, prep do VENDO), palavra do dono:** wipe de TODOS os `~/.orquestrador/workspaces/*` (regeneram por-Run do banco) **+ limpeza das empresas de teste no BANCO** — preservando **Teams Demo, Thumbnail Demo e Demo Fixtures** — com `pg_dump` antes e lista de deleção exibida antes de rodar. Backup dos 4 bilhetes **já feito** (`docs/anexos/bilhetes-atlas-pre-g2/`). **Divergiu da recomendação** (que deixava o banco para depois — não há delete-de-company no produto; SQL em cascata é manual). | Racional DELE: "aproveitar esse problema e deletar tudo desses workspaces e começar do zero (se alguma só existe lá, faça uma cópia dela aqui)" — as ~21 empresas de teste acumuladas (P, Q, ProvaR*, smokes…) saem junto. É OPERAÇÃO (script do operador), não código de produção. |
| **D8** | **`distillation` entra no espelho — pipe único, zero caso especial.** O `DistillationSkill.augment` já injeta ANTES da materialização; com o materializer paramétrico ela cai em `.codex/skills/distillation/SKILL.md` sozinha. Prompt do turno intocado (template inline G2 segue como contrato primário; a skill segue "apoio OPCIONAL"). | Foi ela que denunciou o buraco ("skill não disponível", run B). Filtrá-la custaria um if para proteger nada; agora ela EXISTE nos 2 motores. |
| **D9** | **VENDO com prova Docker viva (upgrade do dono sobre a recomendação):** ver §VENDO. Ruído `ITEM.COMPLETED\|error` = **métrica, não gate**. | Prova no volume é a mais forte (o smoke F.9d já pegou exatamente a classe de bug "árvore não atravessou o seam"). Ruído é comportamento interno do CLI — não bloqueia a fatia; se persistir, vira investigação G4 própria só se doer. |
| **D10** | **ADR-0032** (a 0031 segue RESERVADA pela Fatia 2; ordem de landing ≠ ordem de número). Roadmap: divergência 🔴 "codex pelado" registrada agora apontando para este plano; ao executar, mover para VENDO. | Numeração é por decisão, não por merge; renumerar a reserva mexeria num plano fechado. |

## §M — Mapa de implementação (TDD, sessão de execução)

| # | Mudança | Onde |
|---|---------|------|
| **M1** | Record `WorkspaceConvention(String memoryFile, String skillsSubdir)` + fábricas `claude()`/`codex()` + `KNOWN` (lista das convenções p/ a união da faxina). Javadoc no molde do `EngineContainer`. | `execution/adapter/WorkspaceConvention.java` (novo, irmão do EngineContainer) |
| **M2** | `workspaceConvention()` na interface com **default `claude()`**; `CodexAdapter` sobrescreve com `codex()`. Claude/Process: **zero diff**. | `AgentAdapter.java` · `CodexAdapter.java` |
| **M3** | `SkillMaterializer` paramétrico no subdir (a constante `SKILLS_SUBDIR` migra p/ `WorkspaceConvention.claude()`). **Critério bit-idêntico: as ASSERÇÕES de layout claude existentes não mudam** (call-sites de teste podem ganhar o parâmetro; se a execução preferir manter os testes 100% intocados, overload default-claude é aceitável). | `skills/SkillMaterializer.java` |
| **M4** | `CompanyMemoryMaterializer` paramétrico no arquivo (`CLAUDE_MD` migra idem); reescreve-ou-remove preservado. | `memory/CompanyMemoryMaterializer.java` |
| **M5** | Executor: (a) `adapters.forType` sobe p/ antes da materialização (fail-fast pré-checkout); (b) materializa pela convenção ativa; (c) faxina host: apaga caminhos de `KNOWN − ativa`; (d) subtrees = união (Issue: skillsSubdirs; turno: + memoryFiles). | `execution/AdapterRunExecutor.java:121-197` |
| **M6** | Guard `.orq` estendido: a UNIÃO nunca contém `.orq/*` (o replace apagaria o bilhete — risco #3 da ADR-0030). | `WorkspaceWriteOnRunTest` |
| **M7** | TDD novo: turno codex → `AGENTS.md`=carta + `.codex/skills/<k>/SKILL.md` (+ `distillation` via pipe único) e `.claude/*` AUSENTE; issue codex → só `.codex/skills`; workspace sujo de claude → faxina apaga; sem carta → `AGENTS.md` removido; argv Docker com a união (rm extra é no-op em volume limpo). | novas classes + `DockerExecutionTargetArgsTest` |
| **M8** | `handoff/`: **NADA muda** (P6) — resume-cirúrgico herda o volume do turno disparador (espelho já materializado); continuação fresca passa pelo executor. Registrar no ADR. | — |
| **M9** | Operação (scripts p/ operador, fora de produção): `pg_dump` → SQL faxina DB (preserva as 3 demos; exibir lista antes) → wipe workspaces → restart cirúrgico `:8080` (molde `scratchpad/f15-vendo.sh`). | scratchpad |
| **M10** | Docs: ADR-0032 · roadmap (linha Frente B → VENDO + divergência 🔴 → fechada) · memória do projeto. | `docs/` |

## §VENDO — plano de prova (aprovado pelo dono, com upgrade Docker)

1. **Preparo (M9):** bilhetes já salvos · pg_dump · faxina DB · wipe workspaces · restart operador.
   Dial `context-threshold-percent=99` PERMANECE (gatilho do loop off — o VENDO é do espelho, não do loop).
2. **Prova da CARTA (Atlas, board teams-demo):** inspecionar `_agent/`: `AGENTS.md` existe com corpo
   == carta corrente do banco E `.claude/` NÃO existe; perguntar ao Atlas algo que SÓ a carta responde
   (antes: conferir o conteúdo da carta corrente; se genérica, atualizar pela via do produto com um marcador).
3. **Prova da SKILL (pipeline inteiro):** criar skill-sonda com palavra-chave na biblioteca da company
   via API → anexar ao set do Atlas → pedir invocação na conversa → resposta com a palavra-chave prova
   DB → materializer → `.codex/skills` → descoberta nativa. De quebra: Atlas lista `distillation`.
4. **Métrica do RUÍDO (não-gate):** repetir o turno trivial; medir `ITEM.COMPLETED | error` por run
   (baseline: 1/run). Persistiu → registrar como resíduo G4.
5. **Prova DOCKER viva (escolha do dono):** agente codex `target=DOCKER` (em Demo Fixtures, p/ não
   poluir o teams-demo); credencial `orq_codex_creds` (faltando → operador roda
   `infra/engines/setup-codex.sh`); turno → inspecionar o VOLUME (`docker run --rm -v <vol>:/w alpine
   ls -la /w` + cat): `AGENTS.md` + `.codex/skills/` lá dentro, `.claude/` ausente.
6. **Gates de código:** `cd backend && mvn -o test` verde ≥ piso **429** + os novos; asserções de
   layout claude imutáveis (critério do bit-idêntico, M3).

## §Kickoff — mensagem para a sessão de execução

> A mensagem de copiar-e-colar vive no fim desta sessão de grill e deve invocar `/tdd` e `/verify`,
> apontar para ESTE doc como fonte da verdade, e executar M1→M10 na ordem (operação M9 só no VENDO).

---

## §RESULTADO — VENDO live PASS 5/5 (2026-07-10, sessão de fecho /verify)

**Contexto do fecho:** M1–M8 já estavam commitados (`f13b9d0` + `c82ea39`) e o **PR #4 já tinha sido
criado e MERGED pela própria sessão de execução (00:29)** — este fecho executou M9 + as provas + M10.
No meio do VENDO a fatia **G5 (ADR-0033) mergeou em paralelo (PR #5)**; as provas 1–4 rodaram no
binário G3-puro e a prova 5 no binário main G3+G5 — a integração das duas fatias saiu provada de carona.

1. **M9 (operação, operador=dono):** `pg_dump` (2,5 MB) → faxina do DB **24→3 companies** (só as
   demos Teams/Thumbnail/Fixtures; lista exibida antes) → wipe `~/.orquestrador/workspaces/*` →
   restart cirúrgico do :8080. **Gotcha real:** `activity_log` é append-only
   (`trg_activity_log_immutable`) e matou o script do operador — resolvido com
   `DISABLE TRIGGER` dentro da janela transacional (ENABLE antes do COMMIT). Dial
   `context-threshold-percent=99` preservado no relaunch.
2. **Prova da CARTA — PASS:** turno com o Atlas (Teams Demo, codex LOCAL);
   `_agent/AGENTS.md` == carta v4 do banco **por md5** (`92caa778…`, 1072 bytes; a carta VERBATIM),
   `.claude/` **ausente**, e a resposta citou o que só a carta diz: nicho **dental** + os 3 motivos
   (dor aguda, ticket alto, concorrência fraca). Run `a74b5206`, ANSWERED.
3. **Prova da SKILL — PASS (pipeline inteiro):** skill-sonda escrita no import-root →
   `POST /skills/import {skillKeys}` (`created:1`) → `POST /agents/{atlas}/skills` (v1 pinada) →
   turno de invocação → resposta com a palavra-código exata **ABACATE-ROXO-93** e a listagem
   **`distillation` + `sonda-vendo-adr0032`** (o "de quebra" do D8 confirmado). No disco:
   `.codex/skills/{distillation,sonda-vendo-adr0032}/SKILL.md`. Run `0fb8d0d7`, ANSWERED.
   Sonda removida do import-root após o import (vive no DB, v1).
4. **Métrica do RUÍDO (não-gate):** exatamente **1 `ITEM.COMPLETED|error` por run** nos 2 runs
   (9 e 12 eventos totais) — o baseline G4 persiste, nem cresceu nem sumiu → fica registrado como
   **resíduo G4** (investigação própria só se doer, D9).
5. **Prova DOCKER viva — PASS:** agente codex `role=ceo` `target=DOCKER` criado via API em Demo
   Fixtures (`73676f52`, gpt-5.5; credencial `orq_codex_creds` já existia) + carta-marcador v1
   inserida no DB (a ORIGEM da carta — consolidator/ADR-0019 — está fora do seam desta fatia).
   Turno ANSWERED com a palavra-código **GIRASSOL-CINZA-58** vinda de dentro do container; volume
   `orq_b92cb312_73676f52`: `AGENTS.md` **md5 idêntico ao banco** (`39fa5325…`),
   `.codex/skills/distillation/SKILL.md` presente, `.claude/` **ausente**.
6. **Gates:** `mvn -o test` = **443 verdes** na branch (piso 429+14, pré-merge) e **448 verdes na
   main pós-merge G3+G5** (novo piso da main). Asserções de layout claude imutáveis (M3 bit-idêntico).

**Artefatos vivos deixados de propósito (evidência):** o agente `Sonda Docker Codex` (CEO codex/DOCKER
de Demo Fixtures) + a carta-marcador v1 + o volume da prova — remover é trivial se incomodar as demos.
**Achado de ambiente (não é bug do produto):** trocar o JAR sob o processo vivo (rebuild da sessão
paralela do G5) quebra o classloader lazy do fat-jar — threads HTTP de rotas frias morrem sem resposta
(`/steps` pendurado, `NoClassDefFoundError: ThrowableProxy`). Remédio: restart após todo rebuild.
