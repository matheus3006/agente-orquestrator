# Handoff — GRILLING: CASO B — melhoria de task já entregue (#N DONE que ficou ruim) — FECHADO

> **Data:** 2026-07-10 · **Tipo:** plano fechado de grill (não é código). Faixa **R3-C** do ledger de
> paralelismo (`docs/controle-paralelismo.md`). Grill **só de MECANISMO**: o contrato de intenção
> **B1/B2 é VINCULANTE** e não foi reaberto (`handoff-grilling-insatisfacoes-funcionamento-ceo.md`
> §B.2 + §ADENDO do `handoff-grilling-3-ceos-personas-fechado.md`).
> **Executar numa sessão NOVA** com `/tdd` + `/verify` (skill do repo: instância descartável 18081 +
> pg efêmero; **NUNCA** restart do `:8080`). Repo é git — **este arquivo fica UNTRACKED; quem
> commita é a execução** (precedente R2-B).

---

## §A — O que é a fatia (uma frase)

O dono aponta na conversa que uma task **ENTREGUE** ficou ruim (*"a task #12 ficou ruim — o
formulário não valida e-mail"*); o **Questionador** emite um bloco tipado novo com **só o número**;
o backend resolve a corrente **#N → assignee → time → arquiteto** deterministicamente, **cria a
task corretiva** (descrição = as palavras do dono copiadas **byte a byte do banco**, ligada à #N
pela coluna nova `corrige_issue_id`, responsável = o arquiteto), grava a **1ª Decisão de origem
AGENTE do produto** (texto determinístico) e **confirma ao dono na conversa** — tudo num settle,
zero LLM em registro permanente, zero gate (o pedido do dono É a autorização humana).

---

## §B — DECISÕES-ÁTOMO (grill 2026-07-10; dono **H** = Matheus; todas as 8 = a recomendação do agente, confirmadas uma a uma)

| ID | Decisão | Racional (ancorado no código/negócio) | Alternativa rejeitada |
|----|---------|----------------------------------------|-----------------------|
| **D1** | **Detecção = bloco tipado NOVO ```` ```correcao ```` `{"issue": N}`, emitido pelo QUESTIONADOR, roteado DIRETO** — sem cascata P→E, sem gate. 3º padrão no `PersonaHandoffScanner` (mesmo dialeto: JSON em cerca **coluna 0**). | O roteamento é 100% determinístico (a corrente está no banco) — não há estratégia (Planejador) nem protocolo de gate (Executor) no caminho; cascata = +2 runs (~4-8 min) sem decisão nova. ```` ```handoff ```` aponta **persona** (vocabulário `CeoPersona` — o alvo aqui é empregado); ```` ```proposta ```` existe para conteúdo que o CEO INVENTOU e o dono sela — aqui o conteúdo é DO dono. Payload mínimo deliberado: as palavras do dono **não viajam no bloco** (LLM redigitando = paráfrase). | (b) cascata Q→P→E por uniformidade; (c) card de approval (burocracia dupla: o dono acabou de pedir). |
| **D2** | **Corrente quebrada → PARA E AVISA** (nada é criado): elo vazio (#N sem assignee / assignee sem time / time sem arquiteto) gera aviso determinístico **acionável** na conversa — *"a #12 não tem arquiteto responsável: o time Landing está sem arquiteto. Designe um no board, ou me diga a qual time encaminhar."* Caminho feliz confirma no MESMO canal: *"Correção da #12 encaminhada — task #34 criada para o arquiteto Ana (time Landing)."* Transporte: turno ANSWERED visível **sem run** (molde `postCutNotice`/"Bastão parado"). | Espelha a trava dura existente `TeamService.assertDispatchAllowed` ("time sem arquiteto não despacha") — o caso B obedece a mesma regra; texto montado pelo SISTEMA é sempre exato. Pior desfecho evitado: a reclamação do dono na mesa errada em silêncio. | (b) Questionador chuta destino alternativo (o chute que B1 matou); (c) task sem dono (órfã esquecida; quebra "arquiteto cuida"). |
| **D3** | **VERBATIM = cópia mecânica do banco:** o backend copia `ConversationTurn.ownerMessage` (do turno-gatilho, fonte determinística) para a **descrição** da task corretiva + referência `#N`. Regra de prompt do Q: emitir o bloco **no turno em que a reclamação está completa**; se espalhada em vários turnos, pedir ao dono *"me resume numa mensagem só"* (o consolidado segue sendo palavra DO dono). | Paráfrase impossível **por construção** (bytes, não transcrição); a descrição da task é onde o arquiteto naturalmente lê o trabalho. O router já recebe o turno (`PersonaBatonRouter.afterCeoTurnAnswered(turno, reply)`) — zero fiação nova para alcançar a fonte. | (b) CEO transcreve no bloco (telefone-sem-fio de volta); (c) arquivo do workspace da conversa + ponteiro (o arquiteto não enxerga aquele workspace). |
| **D4** | **AUTORIA: o SISTEMA cria a task corretiva no settle do roteamento** (mesma tx): título padrão *"Correção da #N — <título da original>"*, descrição = verbatim + ref, `corrige_issue_id` setado, **assignee = arquiteto do time** (nasce na mesa dele; o `tryCheckout` já garante que só o assignee a executa). **DIVERGÊNCIA ASSUMIDA da LETRA do B2** ("o Arquiteto a autora") com a intenção preservada: CEO não escreve UMA palavra; task nova ligada; #N intacta (DONE); a correção na mão do arquiteto. **Evolução declarada na ADR-0034:** quando empregado ganhar motor pleno (brief no prompt + credencial de API no run — hoje NÃO existem, fato verificado no `AdapterRunExecutor`/`LocalExecutionTarget`), a corretiva pode virar "pedido" e o arquiteto autora as filhas. | Fato duro do código: run de Issue hoje só executa `runtimeCommand` — não recebe a descrição no prompt nem API key (`ActorResolutionFilter` tem Bearer de agente, mas nada o injeta no run). "Fiel à letra" = construir um subsistema inteiro; o caso B ficaria refém de fatia inexistente. | (b) arquiteto autora via motor (subsistema novo, fatia grande); (c) gate CREATE_ISSUE (já caiu no D1). |
| **D5** | **LIGAÇÃO = coluna `corrige_issue_id uuid null` (self-FK) na `issue` + índice — migração V26 CONSUMIDA.** Vazia em task normal; preenchida só na corretiva. DTO/board: campo aditivo (`corrigeIssueId` + `corrigeSeq`) e selo "corrige #12" na superfície. | "Reincidência visível" (racional literal do B2) vira consulta de 1 linha; relação task-corrige-task é 1:1 — uma coluna é o mecanismo mínimo. | (b) só texto na descrição (invisível pro sistema); (c) tabela de vínculos genérica (infra sem demanda). |
| **D6** | **DECISION determinística, no mesmo settle**, via `DecisionLog.record`: **origem = `AGENTE` (1º writer do produto** — o javadoc "fatia futura — nenhum writer no v1" se cumpre aqui), tipo = `OPERACIONAL` (não toca a carta — o consolidator só refresca `DIRECAO`), racional = a corrente resolvida em texto montado (*"Dono apontou melhoria na #12 (Landing page, entregue por Feynman, time Landing). Roteado ao arquiteto Ana. Task corretiva #34 criada com as palavras do dono."*), `evidencia` = o verbatim do dono (mesma cópia), `issueId` = a task corretiva. **Checado: NÃO esbarra nos seams D5/D11 da ADR-0019** (não supersede nada; não passa pela review) — nada decidido de carona. | O racional do roteamento É a corrente ("por que foi para onde foi") e a corrente está no banco; com isso o caso B inteiro fecha com **zero palavra de LLM em registro permanente**. `DecisionLog.record` é público e o backstop de racional (D6 da 0019) já protege. | (b) Questionador redige (reabre a fresta de LLM em registro permanente); (c) anexar também a resposta do Q (duplica o que a thread já guarda). |
| **D7** | **ANTI-LOOP: SEM trava numérica; SINAL de linhagem sempre visível.** Caso B sobre task que já é correção → a confirmação E a Decisão dizem *"atenção: esta é a 2ª correção na linhagem da #12"* (a coluna D5 torna a conta trivial). **GUARDA de definição:** #N apontada precisa estar **DONE**; senão aviso determinístico (*"a #12 ainda está em execução — o caso B é para task entregue"*), nada criado. | Cada volta do caso B começa com mensagem DO DONO — freio humano por construção; o N=5 da casa protege loop AUTÔNOMO (falso paralelo). Sistema que recusa a 5ª correção do dono fere a mão soberana (invariante de todas as fatias). | (b) trava N=5 na linhagem (sistema mandando no dono); (c) nada (perde a visibilidade de reincidência = racional do B2). |
| **D8** | **ADR-0034 NOVA (reserva CONSUMIDA).** | Primeira ponte conversa→trabalho (mensagem do dono cria task + designa arquiteto + grava Decisão, sem gate); cruza 0031/0033/0019; carrega 2 decisões próprias (divergência-da-letra do B2 + 1º writer AGENTE). Padrão da casa: 0030–0033 todas novas, nenhuma emenda. | Emenda na 0031 (mistura a história das personas — fechada e provada — com mecanismo que ela nunca prometeu). |

---

## §C — MECANISMOS a construir (ponto de extensão file:line; base `backend/src/main/java/com/orquestrador/`)

- **M1 — Lookup do #N.** `IssueRepository` ganha `findByCompanyIdAndSeq(companyId, seq)` — o
  lookup que a ADR-0033 §4 já previa ("dar ao CEO busca de qualquer #N é mecanismo do caso B").
  O índice `uq_issue_company_seq` (V23) já é o caminho natural.
- **M2 — 3º padrão no scanner.** `PersonaHandoffScanner` (`:32-35` tem os 2 irmãos): novo
  `CORRECAO_BLOCK` coluna-0 + record `Correcao(int issue)` no sealed `Route`. Malformado/N não
  numérico = descartado fail-soft (padrão da casa). **Formato proposto (veto H pendente, precedente
  Fatia 2):** ```` ```correcao ```` `{"issue": 12}`.
- **M3 — Rota no roteador.** `PersonaBatonRouter.afterCeoTurnAnswered` (`:44-59`): novo case do
  switch → entrega ao M4 **o turno inteiro** (o verbatim vem de `turno.getOwnerMessage()`, nunca
  do bloco). Restrição de emissor: só QUESTIONADOR roteia `correcao` (P/E emitindo = log + descarte,
  espelho do "uma virada por settle").
- **M4 — O módulo profundo da fatia** (nome sugerido: `work/issue/CorrecaoDeEntregaRouter` —
  regra de ouro: nome que carrega o fluxo; a execução pode renomear). Seam de UM método:
  `rotear(companyId, conversationId, turnoDoDono, issueSeq)`. Esconde: resolução da corrente
  (Issue→`assigneeAgentId`→`Agent.teamId`→`Team.arquitetoAgentId`) · guarda DONE (D7) · criação da
  corretiva (D4, via `IssueService.create` estendido — ver M5) · Decision (D6) · sinal de linhagem
  (D7) · confirmação/aviso (M6). Tudo na MESMA tx do settle; idempotente sob reexecução do reaper
  (não duplica task nem Decision — checar por `corrige_issue_id` + turno de confirmação existente,
  padrão do `PersonaContinuationPoster`).
- **M5 — V26 + nascimento com dono.** `V26__issue_correcao.sql`: `alter table issue add column
  corrige_issue_id uuid null references issue(id)` + índice `idx_issue_corrige`. `Issue`/`IssueService.create`
  ganham o caminho de nascer com `corrigeIssueId` + `assigneeAgentId` pré-setado (hoje o assignee
  só entra no `tryCheckout` — o guard `assignee is null or = :agentId` do checkout JÁ honra o
  pré-set: só o arquiteto executa). Alocação do #M da corretiva = o chokepoint existente (lock da
  company, ADR-0033 intocada).
- **M6 — Confirmação/aviso visível sem run.** Molde `postCutNotice` do `PersonaContinuationPoster`
  (turno ANSWERED, zero run novo). Textos determinísticos: feliz (com #M/arquiteto/time), corrente
  quebrada (acionável, D2), não-DONE (D7), linhagem (D7).
- **M7 — Superfície.** `IssueResponse` + front: `corrigeIssueId`/`corrigeSeq` aditivos + selo
  "corrige #N" no card/detalhe (escopo mínimo; front piso 246).
- **NÃO precisa:** approval novo (enum `ApprovalActionType` FECHADO segue intocado) · mudança no
  `ProposalScanner`/gate T3 · workspace novo · `supersedes`/review-Decision (seams D5/D11 da 0019
  seguem residual do H).

---

## §D — DoD (VENDO na instância descartável — skill `verify` do repo: 18081 + pg efêmero; `:8080` INTOCADO)

1. **Feliz ponta a ponta:** conversa com personas; dono escreve *"a task #2 ficou ruim — <detalhe>"*
   → Q emite ```` ```correcao ```` → task corretiva nasce: descrição **md5-idêntica** à mensagem do
   dono (+ref), `corrige_issue_id`=#2, assignee=arquiteto, OPEN; **a #2 segue DONE intacta**.
2. **Confirmação visível** na thread com #M/arquiteto/time; trilha atribuída ao questionador.
3. **Decision no log:** origem `AGENTE`, tipo `OPERACIONAL`, racional determinístico, `evidencia` =
   verbatim; **carta INTOCADA** (md5 antes/depois).
4. **Corrente quebrada:** time sem arquiteto → aviso acionável, **nada criado** (nem task nem Decision).
5. **Guarda DONE:** #N OPEN/BLOCKED → aviso "task ainda em execução", nada criado.
6. **Linhagem:** caso B sobre a própria corretiva → sinal "2ª correção na linhagem da #2" na
   confirmação e na Decision.
7. **Idempotência:** settle reexecutado não duplica task/Decision/confirmação.
8. **Fail-soft:** bloco malformado / #N inexistente / emissor P ou E → descartado com log, thread segue.
9. **Gates:** `mvn -o test` ≥ piso **474** + novos · front **246** + selo · tsc/lint limpos.
10. **CARONA (não gate — nota da sessão de controle):** se o VENDO usar run **claude** real, é a
    oportunidade natural de exercitar o **residual D10 da ADR-0031** (delegation web/Task in-run) —
    REGISTRAR o observado; não bloqueia a fatia.

---

## §E — Evidência (código real, verificado neste grill)

- **Corrente completa no dado:** `work/issue/Issue.java:46` (`assignee_agent_id`) ·
  `org/agent/Agent.java:60` (`team_id`) · `org/team/Team.java:39` (`arquiteto_agent_id`, nulo
  legal; trava de despacho em `TeamService.assertDispatchAllowed`).
- **#N pronto:** `Issue.seq` + `uq_issue_company_seq` (V23/ADR-0033); falta só `findByCompanyIdAndSeq`.
- **Verbatim na fonte:** `conversation/ConversationTurn.java:37-38` (`owner_message text`);
  o router já recebe o turno (`PersonaBatonRouter.java:36`).
- **Scanner extensível:** `PersonaHandoffScanner.java:32-35` (2 padrões coluna-0; sealed `Route`).
- **Turno visível sem run:** `PersonaContinuationPoster.postCutNotice` ("Bastão parado", ADR-0031 §8).
- **Decision:** `memory/DecisionOrigem.java` (`AGENTE` — "fatia futura, nenhum writer no v1") ·
  `memory/DecisionLog.java:30-37` (`record` público + backstop de racional) ·
  `CompanyMemoryConsolidator` só projeta `DIRECAO` (OPERACIONAL não toca a carta).
- **Fato que moldou o D4:** `AdapterRunExecutor.java:184-213` (run de Issue = só `runtimeCommand`;
  prompt/env de turno é exclusivo de conversa) · zero injeção de API key no run
  (`LocalExecutionTarget`/`ClaudeArgv` sem hits) · `ActorResolutionFilter` (Bearer de agente existe
  na API, não no run).
- **Assignee no checkout:** `IssueRepository.tryCheckout` (`:61-67` — `assignee is null or = :agentId`).
- **V26 livre:** migrations vão até `V25__ceo_persona_moldes.sql`.

---

## §F — Gotchas / ambiente

- **Repo é git** (`main` = `7c08855`). Branch **`feat/caso-b-adr0034`** a partir da main; **PR, merge
  só com OK do Matheus** (nunca fast-forward). Este handoff + hunks do roadmap ficam na working
  tree — **a execução os commita junto** (precedente R2-B; evita o gotcha da working tree da rodada 2).
- **Piso REAL na main: backend 474 · front 246** (roadmap linha "Renovação de lease" + §RESULTADO
  do `handoff-grilling-renovacao-lease.md`: 470+4; o brief da sessão de controle citou 473 —
  reconciliado aqui). Confirmar rodando a suíte ANTES do 1º código.
- **`:8080` compartilhado: NUNCA reiniciar** — /verify = skill do repo (`agente-orquestrator:verify`,
  instância 18081 + Postgres efêmero). Pende no ambiente: o VENDO do lease (operador) — não interferir.
- **`DatabaseClock`, nunca `Instant.now()` cru**, nos caminhos novos com tempo.
- **Blocos coluna-0:** o exemplo do formato viaja INDENTADO no prompt do Q (M7 da Fatia 2) para
  nunca disparar ao ser ecoado.
- **Prompt do Questionador:** a instrução do caso B (quando emitir ```` ```correcao ````, pedir
  consolidação ao dono) entra no cabeçalho por-persona do `composePrompt` — os blocos G1/G2
  (provados ao vivo) NÃO são reabertos; é acréscimo, não reescrita.

---

## §G — Fora do escopo (explícito)

1. **Autoria plena do arquiteto** (brief no prompt do run + credencial de API + ele criar as tasks) —
   evolução declarada na ADR-0034, fatia futura.
2. **Seams D5/D11 da ADR-0019** (review-fabrica-Decision · escrita de `supersedes`) — residual do H,
   intocados por construção (checado no D6).
3. **REOPEN de Issue DONE** — B2 manteve: reabrir é para BLOCKED; a #N fica intacta.
4. **Governança/budget sobre o trabalho do arquiteto** — nada muda no despacho existente.
5. **Casos A e C** do grill de insatisfações (Fatia 2 fechada · propose→gate T3 existente).

---

## §H — Ordem de execução sugerida (TDD)

1. **M5** — V26 + nascimento com `corrigeIssueId`/assignee (teste: corretiva nasce ligada e com dono;
   checkout só pelo arquiteto; #M denso via chokepoint).
2. **M1** — `findByCompanyIdAndSeq` (teste: acha por #N; company errada não vaza).
3. **M2** — 3º padrão do scanner (testes-espelho dos irmãos: coluna-0, malformado, N não numérico).
4. **M4 (núcleo)** — resolução da corrente + guarda DONE + criação + Decision + linhagem, tudo na
   mesma tx (testes: feliz · cada elo quebrado · não-DONE · linhagem · idempotência).
5. **M3 + M6** — fiação no router (só Questionador) + confirmação/aviso visível sem run.
6. **M7** — DTO/selo no front.
7. **Prompt do Q** (cabeçalho por-persona, acréscimo) + **ADR-0034** + roadmap (§RESULTADO aqui) +
   VENDO §D na descartável.

---

## §RESULTADO (execução 2026-07-10, branch `feat/caso-b-adr0034` — /tdd + /verify)

**EXECUTADA e VERIFICADA.** D1–D8/M1–M7 seguidos sem replanejamento. Suítes: **backend 489**
(piso 474 + 15 novos: 3 `IssueCorretivaTest` · 1 `IssueSeqTest` · 2 `PersonaHandoffScannerTest` ·
6 `CorrecaoDeEntregaRouterTest` · 2 `PersonaBatonFlowTest` · 1 `CeoTurnExecutionTest`) ·
**front 248** (246 + 2 `corrigeSelo`) · tsc/lint limpos. **ADR-0034 + V26 consumidas.**

**VENDO na descartável (18081 + pg efêmero; `:8080` INTOCADO) — §D:**
1. **Feliz ponta a ponta PASS — com run claude REAL** (Questionador semeado claude-code/opus, sem
   mock): dono postou *"a task #1 ficou ruim — o formulário de contato não valida e-mail e aceita
   envio vazio"* → o opus emitiu ```` ```correcao ```` `{"issue": 1}` DE PRIMEIRA (prompt novo
   provado ao vivo) → corretiva #2: `md5(description) = md5(owner_message)` **true** (byte a
   byte), `corrige_issue_id`=#1, assignee=Ana Arquiteta (arquiteta do time), OPEN; **#1 seguiu
   DONE**.
2. **Confirmação visível PASS:** turno ANSWERED do Questionador, `run_id` nulo, na thread do
   board: *"Correção da #1 encaminhada — task #2 criada para o arquiteto Ana Arquiteta (time
   Dev / Landing)."*
3. **Decision PASS:** origem `AGENTE` (1º writer do produto), tipo `OPERACIONAL`,
   racional determinístico exato do D6, `evidencia` md5-idêntica ao verbatim, ancorada na
   corretiva; **carta INTOCADA** (company_memory vazia antes e depois — zero materialização).
4. **Corrente quebrada PASS:** arquiteto removido → *"A #1 não tem arquiteto responsável: o time
   Dev / Landing está sem arquiteto. Designe um no board, ou me diga a qual time encaminhar.
   Nada foi criado."* — 0 task, 0 Decision.
5. **Guarda DONE PASS:** #3 OPEN → *"A #3 ainda não está entregue (status atual: OPEN)…"* —
   nada criado.
6. **Linhagem PASS:** Ana entregou a #2 (checkout+complete reais) → correção da #2 → #4 nasceu
   `corrige_issue_id`=#2 e o sinal *"Atenção: esta é a 2ª correção na linhagem da #1."* saiu na
   confirmação E na Decision — sem trava.
7. **Idempotência:** provada no `CorrecaoDeEntregaRouterTest.settleReexecutadoNaoDuplicaNada`
   (chave = id do turno-gatilho no `ownerMessage` do protocolo — o marcador `[gatilho <uuid>]` é
   visível na thread live, mecanismo auditável).
8. **Fail-soft PASS:** `{"issue": 99}` → WARN no log (*"correcao: #99 não existe"*), zero
   task/Decision/turno de protocolo, thread seguiu; malformado/emissor P·E cobertos por teste
   (`correcaoDePlanejadorEDescartadaSemNada`).
9. **Selo no front PASS (visual):** lista com selo "corrige #1"/"corrige #2"; detail da #4 com
   selo no título + prop `corrige #2` com link; descrição = as palavras EXATAS do dono; console
   sem erros (Next dev apontado ao 18081 — o :8080 nunca foi tocado).
10. **CARONA D10 (registro):** o run claude REAL do caso B respondeu DIRETO — thinking + resposta
    + bloco, **zero sub-agentes/Task/web no rastro** (`heartbeat_run_event`): o comportamento
    G1/G2 desejado (a delegação do caso B é o bloco tipado, não sub-agente). O uso REAL de
    web/Task in-run segue não-exercitado — residual D10 permanece aberto. Achado colateral: a
    PROSA do opus chutou *"encaminho pro Feynman"* enquanto o sistema roteou (correto) à
    arquiteta — exatamente a fresta que D3/D6 fecham (zero palavra de LLM em registro).

**Divergências mínimas de execução (intenção preservada):** seam do M4 =
`rotear(turnoDoDono, issueSeq)` (o turno já carrega company/conversa — parâmetros redundantes
caíram); descrição da corretiva = verbatim PURO (sem sufixo de ref — md5-idêntica provável; a
referência #N viaja no título + coluna, declarado na ADR-0034 §2).

**Pendências de veto do dono (propostos, implementados, reversíveis):** formato do bloco
```` ```correcao ```` `{"issue": N}` · textos M6 (os 5 acima, ver `CorrecaoDeEntregaRouter`) ·
título *"Correção da #N — <título original>"* · formato do protocolo
*"Correção da #N — protocolo do caso B [gatilho <uuid>]"* (o marcador é a chave de idempotência).

## Mensagem `/nova-sessao` — ver bloco de kickoff entregue na sessão de grill (R3-C) e replicado abaixo

> Execute a fatia **CASO B (ADR-0034): melhoria de task entregue** — fonte da verdade:
> `docs/handoff-grilling-caso-b.md` (D1–D8/M1–M7 FECHADOS; seguir, não replanejar).

## Pendências de veto humano (sinalizadas; NÃO decidir sozinho na execução)

- **Nome/formato do bloco** — proposto ```` ```correcao ```` `{"issue": 12}` (irmão exato dos
  pinados em `PersonaHandoffScannerTest`). Propor e validar com o dono (precedente Fatia 2).
- **Textos das mensagens determinísticas** (confirmação/avisos M6) — propor no PR; são a voz do
  sistema com o dono.
- **Título padrão da corretiva** — proposto *"Correção da #N — <título original>"*.
