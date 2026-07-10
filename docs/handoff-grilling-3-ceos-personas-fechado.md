# Handoff — GRILLING: o CEO em 3 PERSONAS (Questionador / Planejador / Executor) — FATIA 2 do v1 (FECHADA)

> **Data:** 2026-07-07 · **Tipo:** plano fechado de grill (não é código). Fruto de uma sessão de
> `/grilling` + `/codebase-design` + `/human-architect-mindset`, **ancorada no código real** (4 fan-outs
> de agentes Explore que mapearam skills-in-DB, roster/gate/fan-out, o raio-de-impacto de 3 CEOs, e as
> ferramentas/rede do run).
> **É a FATIA 2 de 2 do v1.** A FATIA 1 (o loop de handoff de contexto — `docs/handoff-grilling-loop-handoff-contexto-fechado.md`)
> é a **fundação**; esta é o **1º USUÁRIO** dela — mas, ao virar **3 agentes** (não 1), a Fatia 2 acabou
> **PARALELA** da Fatia 1 (mesmo transporte — bilhete + turno-continuação — em escopo de conversa, não de
> agente), não cliente apertado (ver **D4**).
> **Executar numa sessão NOVA** com `/codebase-design` `/tdd` `/verify` (padrão `/nova-sessao`). Repo **é git** —
> nada commitado sem o Matheus pedir.
> **DESIGN fechado aqui; escrever os 3 SKILL.md + o código das personas = fatias seguintes (§G).**

---

## §A — O que é a fatia (uma frase)

O "CEO" do produto deixa de ser 1 agente e passa a ser **3 personas de CEO, cada uma um `agent` durável
`role='ceo'` distinto por um discriminador `persona`** (questionador / planejador / executor), **nascidas do
sistema na criação da company**, operando **dentro de uma única conversa** (workspace de conversa
compartilhado): o **Questionador** é o único que fala com o dono; ele passa o **bastão** (dossiê → estratégia)
de forma **autônoma** para o Planejador e o Executor via **detecção de entregável** (scanner irmão do
`ProposalScanner`, sem approval entre personas); o único **gate humano duro** continua sendo o hire (T3); e
qualquer persona back-office que topar uma lacuna grande demais pra chutar faz **kickback cirúrgico** ao
Questionador pra re-grelhar o dono.

---

## §B — DECISÕES-ÁTOMO (arrancadas no grill; dono **H** = Matheus)

| ID | Decisão | Racional (arrancado + ancorado no código) | Alternativa rejeitada |
|----|---------|---------------------------------------------|-----------------------|
| **D1** | **3 CEOs DURÁVEIS**, não 1 trocando de chapéu. 3 linhas `agent` `role='ceo'` + discriminador **`persona`**. Motivo do Matheus: **AUDITORIA** (ver 3 instituições distintas, inspecionáveis/substituíveis). Metáfora: trias politica — independentes e harmônicas. | O modelo de dados **já tolera** múltiplos `role='ceo'`: `AgentRepository.java:26` comenta *"o mais antigo é o canônico se houver mais de um"*. **Zero** constraint única em `(company_id, role)`; `role` é `text` livre (`V1__init.sql:30-40`). Blast-radius **CONTIDO** (~5-6 sites, todos aditivos/triviais). Skills "sempre carregadas" saem **de graça** (cada persona-agente usa o próprio `agent_skill_selection`). | **(a) 1 agente + persona-por-turno** (mais barato, reuso apertado da Fatia 1, mas "uma mente 3 modos", não instituições auditáveis). **(b) "3 contratações"** = empregados pelo gate (seriam execução, não orquestração). |
| **D2** | **Board fala SÓ com o Questionador.** Planejador e Executor são **back-office**: recebem bastão, **nunca** a mensagem do dono. | Um só resolvedor singular: `ConversationService.java:61` (`findFirst...role='ceo'`) → redireciona pra `(role='ceo', persona='questionador')`. As aprovações do dono (gate T3) são canal **separado** (`ApprovalService.decide`), não mensagem de conversa. | O dono fala com a persona **corrente** (quebra "1 conversa" e o balcão único). |
| **D3** | **Bastão Q→P→E é AUTÔNOMO** — nunca passa pelo approval do dono entre personas. Único gate duro = **hire (T3)**. | Honra "1 conversa" + ADR-0018 *"entre marcos o CEO tem autonomia"*. Não alarga o enum FECHADO `ApprovalActionType` (`ApprovalActionType.java:8-12`, "enum fechado é o deny-by-default"). | Swap gated por approval (alargaria o enum fechado, diluindo o deny-by-default). |
| **D4** | **Tudo em 1 SÓ conversa:** mesmo `conversationId`, **workspace de conversa COMPARTILHADO** (keyed por `conversationId`, não `agentId`), `company_memory`/CARTA compartilhada. Invisível na thread (D5 da Fatia 1), **visível na trilha** (auditoria por persona). | Com 3 `agentId`, o bastão **cruza fronteira de agente** → o bilhete tem que ser **conversation-scoped** (senão o Planejador não acha o que o Questionador deixou). Contexto compartilhado vem de `company_memory` (fonte única, materializada por-run) + o workspace de conversa. **Consequência:** Fatia 2 é **PARALELA** da Fatia 1, não cliente. | Bilhete agent-scoped (só serve pro auto-handoff da mesma persona, quebra no cross-persona). |
| **D5** | **Q→P = CONFIRMA-ANTES.** O Questionador, quando sente que colheu o suficiente, **pergunta** ao dono *"levantamos tudo? posso seguir com a geração de estratégia?"* e espera. O **"sim"** (mensagem comum, **não** approval) precede o sinal de virada, emitido no **turno seguinte**. | Mantém o dono no controle da virada mais importante (fechar a grelha) **sem** gate formal; é o ritmo natural da conversa. O backend nunca interpreta a prosa do "sim" — a **persona** decide e emite o bloco. | Questionador fecha a grelha **100% sozinho** (risco de fechar cedo demais). |
| **D6** | **Gatilho da virada = DETECÇÃO DE ENTREGÁVEL** (padrão D1′/D8 da Fatia 1): a persona emite um **bloco tipado** + escreve o entregável; um **scanner-irmão** do `ProposalScanner` detecta pós-settle e roteia. **P→E é 100% autônoma; E→time é o gate T3.** | Não é canal-fantasma (D1′ da Fatia 1) — o entregável (dossiê, estratégia) é **real**; a existência dele **é** o sinal. Reusa a forma de `ProposalScanner.scan` (`ProposalScanner.java:34`) + o settle (`ConversationTurnCoordinator.java:71-73`). | Inventar um canal "terminei" solto; ou alargar o enum de approval. |
| **D7** | **KICKBACK CIRÚRGICO UNIFORME.** Qualquer persona back-office (Planejador **OU** Executor) que topar uma **lacuna de intenção-do-usuário grande demais pra chutar** emite ```` ```needs-grilling ```` → continuação do **Questionador** (que re-grelha o dono só naquela lacuna) → devolve o bastão **pra origem**. | "Nunca chuta grande sozinho"; respeita "dono fala só com o Questionador". A calibração ("pequeno → bloco *Premissas* e segue; grande → devolve") é **regra de prompt** do `writing-doc-plans`, não código. | **Linear-cego** (persona chuta); **auto-kickback solto** (loop entre personas sem motivo). |
| **D8** | **DOIS gatilhos ORTOGONAIS convivem:** **reset-de-contexto** (Fatia 1, MESMA persona, dispara a ~55%) vs **virada-de-persona** (Fatia 2, PRÓXIMA persona, dispara no sinal). Ambos usam o transporte de turno-continuação; difere só **quem reivindica** o próximo turno. | São **notas distintas** no mesmo workspace de conversa: uma **nota-de-trabalho** por persona (reset) vs os **bastões-entregável** (dossiê, estratégia). | Unificar os dois numa nota só (mistura estado-de-trabalho com entregável). |
| **D9** | **As 3 personas NASCEM DO SISTEMA na criação da company** (auto-seed), **não** contratadas pelo gate. **3 moldes-de-sistema de CEO** (espelham os moldes V14). | Metáfora do Matheus: as 3 pré-existem "como a Constituição institui os poderes antes do governo". Reusa `copySkillSet` (`AgentHireService.java:91-102`) → `agent_skill_selection` (V9) → materializado por-run pelo `SkillMaterializer` (`:34-64`). Hoje **não há** auto-seed de CEO (é hire do operador) → bootstrap net-new pequeno. | Operador "institui o CEO" numa ação (adiado — auto-seed venceu). |
| **D10** | **`delegation` usa as FERRAMENTAS DO RUN (web + Task), sem subsistema.** `MAX 30/10` = **teto de PROMPT soft** (tamanho de time / profundidade de pesquisa), não cap de spawn. | **Verificado:** o run do CEO é **LOCAL** (host, rede do host, login `claude` do host — `LocalExecutionTarget.java:366-370`); `claude -p` com `--permission-mode bypassPermissions` e **sem** `--allowedTools/--disallowedTools` (`ClaudeArgv.java:38-39`) → WebSearch/WebFetch **e** o Task (sub-agentes) **ungated**. Sub-agentes internos são **off-book** (invisíveis a `SpawnLineageGuard`/`TeamFanOutGate`/budget, que só contam `HeartbeatRun`) → o produto **não consegue** cravar o cap como duro. | Construir um **subsistema** de fan-out/pesquisa (a proposta assumia; não existe — `TeamFanOutGate` é o oposto, rejeita runs). |
| **D11** | **Skills = SKILL.md (prompt), não código.** O grill fecha o **SEAM** + o **mecanismo**; **autorar** os 3 SKILL.md (guiado por `writing-great-skills`, mattpocock, **já instalado**) = fatia seguinte. | Skill-in-DB = o **texto** do SKILL.md em `company_skill_version.body`, materializado em `.claude/skills/<key>/SKILL.md`. O que é código: o **scanner**, a **projeção** de funcionários, a coluna `persona`, o resolvedor. O que é prompt: os blocos, a calibração, o `MAX`. | Desenhar as skills como "lógica"/contrato de I/O rígido no grill. |
| **D12** | **ADR-0031 NOVA.** (a) o **Planejador PRODUZ a estratégia** — escopado a conteúdo de **orquestração** (o plano/escopo), **não** de execução; (b) a cadeia PO→Arquiteto→Executor da 0017 **vira personas na orquestração**, mas o **arquiteto-de-domínio** (por-time, FONTE das Issues-task) **SOBREVIVE como empregado**. | T1 trai o núcleo *"o CEO não produz conteúdo"* da 0018 → merece endereço próprio (espelha a Fatia 1, que fez a 0030-nova por divergir). O CEO é **facilitador**: delega **escopo**; quem quebra escopo→feature→task é o time abaixo (senão inflaria o CEO e trairia a 0017). O enum `PLANEJAMENTO_ARQUITETO` (`TipoFuncao.java`) **continua valendo pra empregados**. | Revisar a 0018 in-place; ou absorver o arquiteto-de-domínio na persona. |

---

## §C — MECANISMOS a construir (dono **A** = agente; veto humano no review). Cada um com o ponto de extensão (file:line)

- **M1 — Coluna `persona` no `agent`.** `alter table agent add column persona text` — padrão já precedente (`V4/V12/V14/V15` alteram `agent` assim). Sem constraint a brigar.
- **M2 — Resolvedor por-persona.** `ConversationService.java:61` (+ constante `:27`) redireciona o inbound do dono pra `persona='questionador'`; `AgentRepository.java:27` ganha o predicado `persona`. **Frontend:** `page.tsx:114-115` filtra o `.find` do roster por persona; `api.ts:117,248` (+ DTO do backend) ganham `persona` (aditivo).
- **M3 — 3 moldes-de-sistema de CEO + auto-seed.** Nova migração espelhando `V14__role_template.sql:50-65` (moldes de sistema, `company_id NULL`), cada um com `skillset` de `skill_key`. **Bootstrap:** a criação de company cria os 3 agentes-persona via `copySkillSet` (`AgentHireService.java:91-102`). **Backfill** das companies existentes (a `teams-demo` do VENDO).
- **M4 — Workspace de conversa COMPARTILHADO pro CEO.** `WorkspaceResolver` passa a chavear por `conversationId` (não `agentId`) pros runs de CEO; o bastão vive em `.orq/conversa/<conv>/…` (fora de `materializedSubtrees`, como o `.orq/handoff` da Fatia 1). O `SkillMaterializer` apaga+reescreve `.claude/skills` por-run → skills trocam limpo mesmo com workspace compartilhado.
- **M5 — Scanner-roteador (irmão do `ProposalScanner`).** Detecta pós-settle (`ConversationTurnCoordinator.java:71-73`) os blocos tipados e roteia: ```` ```handoff ```` → continuação da **próxima** persona (forward); ```` ```needs-grilling ```` → continuação do **Questionador** (guarda a origem); ```` ```proposta ```` → **approval T3** (caminho existente, intocado). Módulo profundo (interface = "emita um bloco tipado"; roteamento escondido).
- **M6 — `postPersonaContinuationTurn(...)`.** Espelha o `postContinuationTurn` (M7 da Fatia 1) + **persona-alvo** + o bastão. **Reusa o seam D6 da Fatia 1** ("parâmetro do skill-set da continuação pronto; a lógica de quando trocar é da Fatia 2") — aqui a lógica é: qual persona reivindica o próximo turno.
- **M7 — `composePrompt` por-persona.** `ConversationTurnCoordinator.java:94-101` (hoje bloco constante *"Você é o CEO — delegador PURO"*) ganha um **cabeçalho por-persona**; o grosso da identidade vem do **skill-set materializado** de cada persona.
- **M8 — Projeção determinística funcionários-por-área (`being-ceo`).** Estende `LiveStateProjector.java:60-85` (o `### Equipe (N)` que já cospe) **agrupando por** `team.dominio/foco` (`V15`) / `tipo_funcao`. **Zero LLM** no caminho de escrita (ADR-0019). *(NÃO é um doc que o agente escreve à mão.)*
- **M9 — Trava anti-loop de kickback + idempotência.** Contar kickbacks consecutivos por conversa (estilo D7 da Fatia 1, `MAX_DEPTH=5`); ao bater, o Planejador **procede com a melhor premissa e SINALIZA alto** (o dono pega no review/gate) em vez de travar.
- **NÃO precisa:** dimensão-persona nova no `resolveForRun`. Com **3 agentes distintos**, `resolveForRun(companyId, agentId, issueId)` (`SkillSetService.java:143`) **já resolve por-persona** (cada agente tem seu `agent_skill_selection`). É o dividendo do D1.

---

## §D — DoD (VENDO, teams-demo — para as sessões de EXECUÇÃO)

Backend `:8080` COMPARTILHADO; DB `agente-orquestrador-db-1`. Precisa de um CEO com motor que RODE (teams-demo tem).

1. **Auto-seed / backfill:** a company de teste tem **3 linhas `agent` `role='ceo'`** com `persona` distinto; cada uma com seu skill-set materializado.
2. **Balcão único (D2):** mensagem do dono cai **só** no Questionador; P e E nunca recebem inbound do dono.
3. **Q→P confirma-antes (D5):** o Questionador pergunta "posso seguir?"; o **"sim"** do dono dispara o turno que escreve o dossiê e emite ```` ```handoff ```` → **1** continuação do Planejador, com o bastão.
4. **P→E autônoma (D6):** o Planejador solta a estratégia → continuação do Executor, **sem** você no meio.
5. **E→time = T3 (D3):** o Executor emite ```` ```proposta ```` do time → **approval PENDING** (gate humano); nada é contratado sem APPROVE.
6. **Kickback cirúrgico (D7):** força uma lacuna grande no Planejador → ```` ```needs-grilling ```` → continuação do **Questionador** re-grelha → responde → **retoma no Planejador**.
7. **1 conversa / auditoria (D4):** na thread o dono vê **uma voz, uma conversa**; nos runs/steps as **3 personas aparecem atribuídas**.
8. **`delegation` com ferramenta-do-run (D10):** provar que o run do Executor **navega** e **spawna sub-agentes** dentro do turno (sub-agentes off-book).
9. **Anti-loop (M9):** N+1 kickbacks → o Planejador procede-e-sinaliza; não trava.
10. **Gates:** `mvn -o test` verde (+ testes novos por M1–M9); frontend afetado verde. Repo **é git** — nada commitado sem o Matheus pedir.

---

## §E — Evidência (código real, file:line — base `backend/src/main/java/com/orquestrador/` salvo indicado)

- **Resolver singular / tolera múltiplos (D1/D2):** `conversation/ConversationService.java:27` (`CEO_ROLE`), `:61-63` (resolver + 409); `org/agent/AgentRepository.java:26-27` (comentário "o mais antigo é canônico se houver mais de um" + `findFirst`). Frontend: `frontend/src/app/[companySlug]/page.tsx:114-115`, `frontend/src/lib/api.ts:117,248`.
- **`agent` sem constraint de role (D1):** `resources/db/migration/V1__init.sql:30-40` (`role text not null`, sem unique); alter-table precedentes `V4`/`V12`/`V14__role_template.sql:45`/`V15__team.sql:23`.
- **Skill-set / materialização (D9/M9):** `skills/SkillSetService.java:128-171` (`resolveForAgent`/`resolveForRun`); `resources/db/migration/V9__agent_skill_selection.sql:5-13`; `skills/SkillMaterializer.java:34-64` (apaga+reescreve `.claude/skills`).
- **Moldes de sistema / copySkillSet (D9/M3):** `resources/db/migration/V14__role_template.sql:47-65` ("MOLDES DE SISTEMA", `company_id NULL`); `org/agent/AgentHireService.java:91-102` (`copySkillSet`, skill-key ausente é pulada). **Não há molde `ceo` hoje** (V14 semeia architect/executor/product-owner/leader).
- **Gate / approval / enum fechado (D3/D6):** `governance/approval/ProposalScanner.java:34,49,64` (scan fail-soft); `conversation/ConversationTurnCoordinator.java:71-73` (`approvals.propose`); `governance/approval/ApprovalActionType.java:8-12` (enum FECHADO); `governance/approval/ApprovalService.java` (`propose`/`decide`/`dispatchAction`); hire direto = 403 do operador (`org/agent/AgentController.java:45-54`).
- **Prompt / instruções (M7):** `conversation/ConversationTurnCoordinator.java:94-101` (`composePrompt`, bloco constante do CEO).
- **Roster / Equipe / área (D2/M8):** `conversation/LiveStateProjector.java:60-85` (`### Equipe`, lista todos os agents); `resources/db/migration/V15__team.sql:8-16` (`dominio/foco`); `org/roletemplate/TipoFuncao.java:16-25` (enum das áreas). Sem doc `funcionarios` (grep vazio).
- **Memória / CARTA compartilhada (D4):** `resources/db/migration/V18__company_memory_decision.sql:7-15`; `memory/CompanyMemoryMaterializer.java` (→ `.claude/CLAUDE.md` por-run).
- **Ferramentas / rede do run (D10):** `execution/adapter/ClaudeArgv.java:38-39` (`bypassPermissions`, sem allow/deny; comentário `:35-37` "a fronteira é o CONTAINER, não o CLI"); `execution/runtime/LocalExecutionTarget.java:366-370` (subprocesso host, herda env/rede/login do host); `execution/lineage/SpawnLineageGuard.java` (conta `HeartbeatRun`, não sub-agente do `claude -p`); `execution/TeamFanOutGate.java` (teto de concorrência de runs, rejeita 409).
- **Workspace / `.orq` sobrevive (D4/M4):** `execution/runtime/WorkspaceResolver.java` (hoje keyed por `agentId`); `.orq/` fora de `materializedSubtrees` (padrão da Fatia 1 / `AdapterRunExecutor.java:169-171`).

> Relatórios completos dos 4 fan-outs: transcrições dos agentes desta sessão (skills-in-DB · roster/gate/fan-out · blast-radius · ferramentas/rede).

---

## §F — Gotchas / ambiente

- **Repo é git** (privado `matheus3006/agente-orquestrator`, `main`). **Commit/push só quando o Matheus pedir; criar branch ANTES de tocar a `main`.** (Este handoff foi escrito como arquivo **não-commitado**.)
- **Backend `:8080` compartilhado** — não reiniciar à toa (`mvn -o -DskipTests package`; conferir `lsof -iTCP:8080`). **O harness barra `kill` do `:8080`** — o **operador reinicia**; o agente faz só verificação read-only + cleanup.
- **DB:** `docker exec -i agente-orquestrador-db-1 psql -U orquestrador -d orquestrador`.
- **macOS** (`gtimeout`). **`DatabaseClock`, nunca `Instant.now()` cru.** Skills-in-DB = `SkillMaterializer` + moldes de sistema (V14).
- **Seed VENDO:** header `X-Board-User`, company `teams-demo`, runs reais 2-4 min. **Backfill das 3 personas** na `teams-demo` antes da prova (M3).
- **Suposição de ambiente (D10):** a capacidade de WebSearch/Task depende do **plano/rede do `claude` do host**, não do nosso código.

---

## §G — Fora do escopo (explícito — fatias seguintes)

1. **Autorar os 3 SKILL.md** (`writing-doc-plans`, `being-ceo`, `delegation`) — guiado por `writing-great-skills` (mattpocock, instalado) — e **importar/registrar** as externas como skill-in-DB. Isso inclui a **calibração** do `writing-doc-plans` (Premissas vs `needs-grilling`), o **formato dos blocos**, e o `MAX 30/10` como teto de prompt.
2. **O código das personas** (M1–M9): coluna `persona`, resolvedor por-persona, workspace de conversa compartilhado, scanner-roteador, bootstrap na criação de company, projeção de funcionários.
3. **Contabilidade de produto sobre a pesquisa off-book do `delegation`** (governança/budget/lineage sobre o web+Task do Executor) — só se o freio duro doer (é subsistema).
4. **Auto-kickback além do `needs-grilling` cirúrgico** (iteração automática ampla entre personas).
5. **O loop de handoff em si** (Fatia 1 — já FECHADA, outro handoff).

---

## §H — Ordem de execução sugerida (TDD)

1. **M1 + M3 — persona + moldes + auto-seed.** Teste: criar company semeia 3 `role='ceo'` distintos por `persona`, cada um com skill-set. Backfill teams-demo.
2. **M2 — resolvedor por-persona** (back + front). Teste: inbound do dono cai no Questionador; DTO expõe `persona`.
3. **M7 — `composePrompt` por-persona.** Teste: cada persona recebe seu cabeçalho.
4. **M4 — workspace de conversa compartilhado.** Teste: bastão escrito por uma persona é lido pela próxima (cross-agente).
5. **M5 + M6 — scanner-roteador + `postPersonaContinuationTurn`.** Teste: ```` ```handoff ````→próxima persona; ```` ```needs-grilling ````→Questionador (com origem); ```` ```proposta ````→approval T3.
6. **D5 — confirma-antes no Q→P.** Teste: "posso seguir?" fica idle; "sim" → dossiê + virada.
7. **M8 — projeção funcionários-por-área.** Teste: `### Equipe` agrupada por área, determinística.
8. **M9 — anti-loop de kickback.** Teste: N+1 → procede-e-sinaliza.
9. **Registro:** **ADR-0031** (Planejador produz estratégia de orquestração; arquiteto-de-domínio sobrevive) + **forward-refs** na 0017/0018. **VENDO** (§D) em teams-demo; reverter seeds; backend intocado; nada commitado sem pedir. **§RESULTADO** neste handoff + memória de fechamento.

> **NOTA de sequência:** a Fatia 1 (loop de handoff) é a **fundação** e executa **antes**; esta fatia reusa o
> transporte de turno-continuação dela (M6/D6). Se as duas forem em paralelo, coordenar o seam do
> `postContinuationTurn`.

---

## Registro da decisão — preferência: **ADR-0031 nova**

Espelha a Fatia 1 (0030-nova). Aqui o T1 **trai** o núcleo *"o CEO não produz conteúdo"* da 0018 — divergência
consciente e escopada (orquestração sim, execução não). Adicionar **forward-refs**: na 0018 (o Planejador
produz o plano de orquestração) e na 0017 (PO/Arquiteto de orquestração viram personas; o arquiteto-de-domínio
sobrevive).

---

## Mensagem `/nova-sessao` (copiar para iniciar a EXECUÇÃO numa sessão nova)

> Execute a **FATIA 2 do v1: o CEO em 3 personas** (Questionador/Planejador/Executor). **Fonte da verdade:**
> `docs/handoff-grilling-3-ceos-personas-fechado.md` (seguir, não replanejar — o grill fechou D1–D12 e os
> mecanismos M1–M9, ancorados no código por 4 fan-outs). Skills: `/codebase-design` (o scanner-roteador, o
> workspace de conversa e a continuação por-persona como módulos profundos atrás de seams pequenos) → `/tdd`
> (teste-primeiro) → `/verify` (VENDO teams-demo, §D). **Espinha:** 3 `agent` `role='ceo'`+`persona`
> auto-semeados na criação de company → balcão único no Questionador → bastão autônomo por detecção de
> entregável (scanner-irmão do `ProposalScanner`) → workspace de conversa compartilhado → kickback cirúrgico
> `needs-grilling` → único gate duro no hire (T3). **Reusa** o transporte de turno-continuação da Fatia 1 (o
> seam D6). **Gotchas:** repo é git (branch antes da `main`; commit/push só quando o Matheus pedir); backend
> `:8080` compartilhado (o operador reinicia); DB `agente-orquestrador-db-1`; `DatabaseClock`. **Autorar os 3
> SKILL.md é fatia à parte** (guiada por `writing-great-skills`). Ao fim: **ADR-0031** + forward-refs 0017/0018,
> gates verdes, **§RESULTADO** neste handoff.

## Skills a usar (execução): `/codebase-design` · `/tdd` · `/verify`. Autoria das skills: `/write-a-skill` + `writing-great-skills`.

## Pendências de veto humano (sinalizadas; NÃO decididas sozinho na execução)

- **Formato dos blocos tipados** (```` ```handoff ````/```` ```needs-grilling ````) — é o contrato que faz o roteamento funcionar (era UNKNOWN, como o template do bilhete na Fatia 1). Propor e validar.
- **Formato/blocos do `writing-doc-plans`** (estratégia + Premissas + validação de cobertura) — dogfooda este handoff-fechado; lista exata a validar.
- **N da trava anti-loop de kickback** (M9) — espelha `MAX_DEPTH=5`; veto livre.
- **Governança off-book do `delegation`** (D10) — aceito no v1 (read-only + custo inócuo na assinatura); freio duro = subsistema (adiado). Confirmar.
- **Sourcing das skills externas** — URLs/versões e import-vs-author de cada: `cs-ceo-advisor`, `brainstorming` (superpowers), `teach` (mattpocock), `adaptive-communication` (bencium), `grill-me` (https://github.com/mattpocock/skills/tree/main/skills/productivity/grill-me), `grill-with-docs` (https://github.com/mattpocock/skills/tree/main/skills/engineering/grill-with-docs).
- **Gatilho do bootstrap** — DECIDIDO: auto-seed na criação de company (D9). Backfill das existentes (teams-demo).
- **ADR-0031 nova** vs revisão — preferência sinalizada (nova); confirmar.

---

## §ADENDO (2026-07-09) — EMENDAS DO DONO, grelhadas em sessão própria (VINCULANTES na execução)

> Origem: grill das insatisfações com o funcionamento observado
> (`docs/handoff-grilling-insatisfacoes-funcionamento-ceo.md`, decisões A1–A5 + B1–B2, enunciadas
> pelo Matheus e fechadas uma a uma). Onde um item abaixo conflitar com um D do plano, **o adendo vence**.

- **A1 — Dossiê do grill é APENDADO A CADA TURNO, não escrito no final.** UM arquivo `.md` por
  business/conversa; o Questionador grava cada pergunta+resposta no turno em que a resposta chega;
  o "sim" do dono (D5 confirma-antes) apenas CARIMBA o dossiê como pronto — ele já existe inteiro.
  *Emenda ao D6:* o entregável não nasce no turno do "sim"; ele cresce durante a conversa (se a
  janela estourar no meio do grill, nada se perde; e as palavras EXATAS do dono ficam preservadas).
- **A2 — O Executor PROTOCOLA o pedido de contratação sem mexer.** O pedido que chega ao menu do
  dono é EXATAMENTE o que o Planejador gerou (racional↔pedido 1:1). Buraco visto pelo Executor =
  kickback `needs-grilling` (D7), nunca conserto silencioso. A autonomia do Executor começa PÓS-approve:
  contratar, atribuir as skills a cada agente, entregar os briefs aos times.
- **A3 — Racional da escolha dos agentes existe NOS DOIS LUGARES.** Na CONVERSA: o Questionador
  entrega o racional legível ao dono (por que essas áreas/agentes, citando o dossiê) ANTES de ele ir
  ao menu. No MENU: cada agente proposto carrega racional estruturado (skills + motor + porquê).
- **A4 — No menu do hire: skills SOMENTE-LEITURA; MOTOR livremente editável pelo dono.** Racional
  do Matheus: o CEO tem viés de motor (CEO claude sempre proporá agentes Anthropic; CEO codex, codex)
  — a mão soberana no motor é obrigatória. Edição de motor não exige rejeição nem retorno ao Planejador.
- **A5 — Skill errada NÃO trava o gate.** Dois caminhos, à escolha do dono: (1) editar a skill do
  agente JÁ CONTRATADO (o pin por-agente que o board já tem); e/ou (2) avisar o Questionador na
  conversa ("o agente X tem uma skill que não se encaixa"), que roteia a correção pelas personas.
- **B (fluxo NOVO, fatia própria FUTURA — fica registrado aqui só o contrato de intenção):** dono
  aponta melhoria de task executada que não atendeu (referência = ID numérico #N, fatia própria já
  decidida). O CEO faz o racional = ROTEAMENTO (qual time/Arquiteto responsável, qual #N), registrado
  como Decisão + confirmação ao dono na conversa; o Arquiteto recebe **as palavras do dono VERBATIM**
  (nunca paráfrase do CEO — zero telefone-sem-fio) + a referência #N; a correção nasce como **task
  corretiva NOVA ligada à #N** (o Arquiteto a autora — ADR-0017; a #N mantém histórico intacto).

---

## §RESULTADO — EXECUTADA (2026-07-10, branch `feat/ceo-tres-personas-adr0031`)

**M1–M9 + adendo A1–A5 no código, TDD ponta a ponta.** Backend **469 verdes** (piso 448 + 21) ·
front **246** (piso 242 + 4) · tsc/lint limpos. **ADR-0031 commitada** + forward-refs 0017/0018 +
roadmap (linha Fatia 2 → IMPLEMENTADA). Commit `22ae1d2` (não pusheado; PR só com OK do dono).

**O que entrou** (mapa mecanismo→código):
- **M1/M3** `V24__agent_persona.sql` + `V25__ceo_persona_moldes.sql` (3 moldes `ceo-*` + BACKFILL:
  canônico→questionador preservando identidade/motor; P/E copiam o MOTOR do canônico) ·
  `org/agent/CeoPersona.java` (vocabulário único) · `CeoPersonaSeeder` (auto-seed **dirigido pelos
  moldes, fail-soft** — H2 dos testes sem V25 não semeia; companies de teste intactas).
- **M2** resolvedor `(role='ceo', persona='questionador')` + fallback canônico (legado) ·
  `AgentResponse.persona` · front `api.ts`/`page.tsx`.
- **M7** `composePrompt` por-persona (persona nula = prompt pós-G1/G2 **byte-idêntico**; Q estende
  com dossiê A1 + confirma-antes D5 + dever de re-grelha; P = estratégia de orquestração D12 +
  calibração Premissas×needs-grilling D7 + MAX 30/10 D10; E = protocola SEM MEXER A2 + proposta com
  `racional` A3 + autonomia PÓS-approve). Blocos tipados INLINE (indentados — ver M5).
- **M4** `WorkspaceResolver.resolveConversation` (workspace por `conversationId`; peek pré-claim no
  coordinator; volume 2-segmentos fora do reaper por-Issue) — bastão `.orq/conversa/<conv>/`
  (`ConversationBaton`: `dossie.md` · `estrategia.md`) cruza a fronteira de agente. ADR-0032 intocada.
- **M5/M6** `conversation/persona/`: `PersonaHandoffScanner` (JSON em fence, **coluna 0**, fail-soft)
  · `PersonaBatonRouter` (uma virada por settle) · `PersonaContinuationPoster` (sentinela
  `[continuação — bastão…]` INVISÍVEL; kickback `Re-grelha — …` VISÍVEL — precedente do aviso de
  corte da ADR-0030; idempotência sob reexecução) · ```proposta``` segue INTOCADO pro T3.
- **M9** 5 kickbacks seguidos sem o dono (sentinelas puladas; fala do dono zera) → `prossiga` pra
  ORIGEM; **decisão nova: reincidência pós-prossiga → CORTE** ("Bastão parado", ANSWERED, sem run
  novo) — o TDD travou o build em loop infinito antes do corte existir; sem ele o ping-pong
  autônomo giraria para sempre.
- **M8** `LiveStateProjector`: `### Equipe` agrupada por `dominio/foco` + `(sem time)`, determinística.
- **A4** `ApprovalService.EngineOverride` (decide 8-args; motor do ALVO honrado; override do dono
  vence campo-a-campo) + card com motor editável e racional visível (A3); `splitReply` limpa os
  blocos de bastão da bolha.

**FORMATO CANÔNICO DOS BLOCOS (proposto — VETO PENDENTE DO DONO; pinado em
`PersonaHandoffScannerTest`):** irmão exato do ```` ```proposta ```` — JSON numa cerca em coluna 0:
```handoff
{"para": "planejador|executor|questionador", "bastao": "<caminho do entregável>", "resumo": "<uma linha>"}
```
```needs-grilling
{"lacuna": "<a pergunta concreta que só o dono responde>"}
```
Racional: (1) o parser reusa o padrão já pinado do ProposalScanner (zero segundo dialeto);
(2) campos tipados sem DSL nova; (3) `origem` NÃO viaja no bloco — o roteador a deriva do EMISSOR
(fonte da verdade; menos a errar). Diferença deliberada: cerca só em **coluna 0** (o exemplo viaja
indentado no prompt e nunca dispara ao ser ecoado/citado). ⚠ avisar R1-D: `SYNC-FATIA-2` destravado.

**VENDO (2 camadas):**
1. **Prova de superfície REAL (2026-07-10, 2ª instância `:18080` + Postgres descartável — `:8080`
   e o JAR intocados):** Flyway aplicou V1..V25 num banco vazio (validate OK) · `POST /companies`
   semeou as 3 personas dos moldes (pin + trio por-molde) · **1 mensagem do dono → cascata
   AUTÔNOMA Q→P→E → approval PENDING** (thread do board = UMA VOZ; trilha com 3 personas
   atribuídas) · APPROVE com `EngineOverride` → "Dev Backend" nasceu `codex/gpt-5-codex/medium`
   (override venceu o `claude-code` protocolado) · kickback: `Re-grelha` VISÍVEL na thread com
   origem+lacuna → e o pior cenário rodou sozinho: **5 Re-grelhas → prossiga → reincidência →
   "Bastão parado" (corte, zero run novo)** · backfill V25 provado em pg real (Atlas→questionador
   com motor codex intocado; P/E copiando o motor; 2º CEO legado fica NULL; company sem CEO intocada).
2. **VENDO oficial §D na teams-demo = OPERADOR** (o harness barra restart do `:8080`; V24/V25
   aplicam no boot): scripts `f2-vendo-operacao.sh` + `f2-vendo-provas.sh` no scratchpad da sessão.
   Itens que SÓ o live com motor real prova: D5 semântico (o Q sentir cobertura e perguntar "posso
   seguir?") e D10 (`delegation` navegando/spawnando sub-agentes num run claude real).

**Pendências de veto do dono (sinalizadas, NÃO decididas):** formato dos blocos (acima) ·
N=5 da trava M9 (espelha MAX_DEPTH; 1 constante) · governança off-book do `delegation` aceita no
v1 (confirmar) · ADR-0031 nova (feita como nova — confirmar).

**Divergências/decisões novas no caminho (com racional):** (1) corte anti-reincidência no M9 (acima);
(2) kickback VISÍVEL (sem isso o dono nunca veria a re-grelha — run fresco não tem outra memória);
(3) auto-seed fail-soft dirigido por moldes (protege o parque de testes e degrada limpo em banco
sem V25); (4) skills EXTERNAS ficaram FORA dos moldes v1 (sourcing = veto pendente; entram por v2
de molde versionado); (5) backfill copia o MOTOR do canônico p/ P/E (precisa RODAR na teams-demo —
codex provado; moldes claude ficam p/ companies novas).
