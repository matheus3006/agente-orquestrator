# Handoff — GRILLING: ABSORÇÃO DE ISSUES DO BOARD PELO CEO — FECHADO

> **Data:** 2026-07-11 · **Tipo:** plano fechado de grill (não é código). Faixa **R4-B** do ledger de
> paralelismo (`docs/controle-paralelismo.md`). Grill de MECANISMO sobre o contrato de intenção do
> dono (verbatim 2026-07-10/11, ver §A e §G) — o contrato NÃO foi reaberto.
> **Executar numa sessão NOVA** com `/tdd` + `/verify` (skill do repo `agente-orquestrator:verify`:
> instância descartável 18081 + pg efêmero; **NUNCA** o `:8080`).
> **⛔ GATE DE COLISÃO: branch de main SÓ DEPOIS do merge do caso C (faixa R4-A,
> `feat/caso-c-hire-direto`)** — colisão real em `conversation/persona/` + prompts das personas +
> `ProposalScanner`/`PersonaBatonFlowTest`. Repo é git — **este arquivo fica UNTRACKED; quem
> commita é a execução** (precedente R2-B).

---

## §A — O que é a fatia (uma frase)

O dono cria uma issue no board e ela hoje fica ÓRFÃ (checkout é agent-driven; a UI não tem botão de
empurrar, de propósito); a fatia faz o **CEO absorvê-la**: a criação dispara um turno back-office ao
**Planejador**, que tria contra o `### Equipe` e emite um bloco tipado apontando o **TIME**; o
backend resolve **time→arquiteto** deterministicamente, **seta o assignee** (a issue nasce na mesa
do arquiteto), confirma numa **conversa própria ancorada na issue** (issue = conversa direcionada) e
grava Decision; quando NENHUM time cobre, o Planejador recomenda cargo na thread e o desfecho pode
ser um card único **`CREATE_TEAM`** no gate (time + arquiteto atômico, com retoma automática da
absorção pós-approve) — fechando também, na origem, a segunda fábrica de órfãs (proposta
`CREATE_ISSUE` passa a carregar o time e nascer com dono).

**Contrato de intenção (verbatim do dono, 2026-07-10):** *"Não aparece a opção de eu poder
atribuí-la a ninguém e sejamos honestos isso não é problema meu — quem deve delegar é o CEO. A ISSUE
deveria ser um menu para eu observar quem está cuidando do quê. Eu posso até criar uma issue lá, ou
atribuir manualmente a algum, mas quem deveria de fato fazer isso é o CEO. Caso eu criasse uma issue
lá, o CEO deveria pegar essa issue e absorver."*

**Invariante novo (verbatim da sessão, 2026-07-11):** *"Tudo que o CEO cria que não seja uma
pergunta que ele vai retornar a mim deveria ser direcionada a algum time"* → **nada que o CEO cria
nasce sem dono.** E: *"a abertura de uma issue nada mais é do que uma conversation, só que uma
direcionada"* → issue = conversa direcionada (D4).

---

## §B — DECISÕES-ÁTOMO (grill 2026-07-11; dono **H** = Matheus; todas confirmadas uma a uma)

| ID | Decisão | Racional (ancorado no código/negócio) | Alternativa rejeitada |
|----|---------|----------------------------------------|-----------------------|
| **D1** | **Gatilho = issue criada pelo DONO (ator BOARD), que nasce sem assignee. Dispara UMA vez, na criação, na MESMA tx** (issue + conversa ancorada + turno + wakeup atômicos — espelho do `ConversationService.postMessage`). Sem varredura periódica de órfãs. | Verbatim exato do dono ("caso eu criasse uma issue lá…"). Disparar também para issues do gate = run extra do CEO triando a própria proposta (ruído — lição do G4: 31→9 eventos). A porta do gate tem conserto melhor NA ORIGEM (D2). | (b) toda issue órfã de qualquer porta (run extra + ruído); (c) só quando o dono pedir na conversa (devolve a delegação à mão do dono — contraria "quem deve delegar é o CEO"). |
| **D2** | **Segunda porta fechada por construção: a proposta `CREATE_ISSUE` ganha o TIME no alvo** (`dominio`/`foco`); no APPROVE o backend resolve **time→arquiteto** e a issue **nasce com assignee = arquiteto**. Proposta sem time ou corrente quebrada → **409 acionável no decide** (espelho do "Proposta de issue sem title" que o `ApprovalService` já lança) — nada some em silêncio; conserto = reject + nova proposta, ou o dono cria a issue (cai no D1). | Fato do código: `ApprovalService.dispatchAction` (case `CREATE_ISSUE`) só lê title/description/riskLevel — lacuna histórica (o gate nasceu na ADR-0018, antes da entidade Time). O invariante do dono: "nada que o CEO cria nasce sem dono". Resolução determinística = a corrente do caso B; zero run extra, zero LLM. NÃO reabre o T3 (enum de DECISÃO intocado aqui; campo aditivo no alvo). | (b) registrar como residual (deixaria o próprio CEO fabricando órfãs pela outra porta — fura o invariante recém-declarado). |
| **D3** | **Quem tria: o PLANEJADOR** (turno back-office; nunca o inbound do dono). Issue ambígua → `needs-grilling` → re-grelha VISÍVEL do Questionador (máquina M9 existente). Executor fora. **Atribuição NÃO tem gate** — o ato do dono de criar a issue É a autorização de delegar (mesmo racional do D1 do caso B); gate só se a triagem desembocar em contratação (D6/D7). | Triagem é estratégia de orquestração — o escopo literal do Planejador (ADR-0031 §10); o M8 (`### Equipe` por dominio/foco no `LiveStateProjector`) já entrega o insumo exato no prompt dele de graça. Questionador é balcão de INBOUND (mensagem), não de evento de board — misturar posturas desfaria a auditoria que motivou as 3 personas. | (b) Questionador tria (infla o balcão; skill errada para org-state); (c) atalho determinístico com 1 time só (atribuiria em silêncio o que talvez não caiba em time nenhum — o julgamento "cabe × não cabe" existe mesmo com 1 time). |
| **D4** | **Issue = conversa DIRECIONADA: toda issue absorvida ganha a SUA conversa, ancorada 1:1** (V27: `issue.conversation_id`), assunto determinístico *"#7 — <título>"*, criada na absorção. O turno-gatilho ao Planejador nasce NELA (sentinela interna, invisível pelo filtro da ADR-0030); **confirmação e re-grelha visíveis NELA**; a resposta do dono ali entra pelo balcão único normal e o bastão existente retoma o fluxo. Front: a thread mora no DETALHE do card (o board segue o menu de observação); na lista de chats o prefixo `#N —` distingue. Generalizar para as outras portas (toda issue nasce com conversa) = evolução registrada, fora desta fatia. | Reformulação do próprio dono ("a abertura de uma issue nada mais é do que uma conversation, só que direcionada") — mata a objeção de poluição (a thread mora no card, não compete com os chats de estratégia) e entrega o "menu para observar": clica na #7, vê quem cuida E a conversa sobre ela. Fato duro: `ConversationTurn.conversationId` é not-null — o turno PRECISA de uma conversa; `approval.conversationId` já ancora approval em conversa (mesmo movimento). | (a) conversa fixa "Triagem do board" por company (feedback da #7 longe do card da #7 — cruzar duas telas); (c) carona na conversa mais recente (contaminação de contexto + quebra a régua anti-loop por-conversa). |
| **D5** | **O ato de atribuir: bloco tipado apontando o TIME, nunca a pessoa, nunca API** — 4º irmão no scanner: ```` ```absorcao ```` `{"issue": 7, "dominio": "Dev", "foco": "Landing", "racional": "é trabalho de frontend"}` (formato = veto H pendente). Backend resolve **time→arquiteto** deterministicamente, seta assignee via UPDATE guardado, confirma na thread (com o `racional` do Planejador visível — G1/G6: racional invisível nunca mais). Elo quebrado = **para-e-avisa acionável**, nada atribuído. **SEM wakeup automático do arquiteto** — a issue nasce na mesa dele (o guard do checkout `assignee is null or = :agentId` JÁ honra o pré-set, provado no caso B); o disparo segue pelos caminhos existentes. | Mecanismo do caso B nota a nota (LLM emite o mínimo; sistema resolve a corrente no banco; scanner sealed extensível). Apontar pessoa deixaria o LLM um dia escolher um executor — furando "o arquiteto é a fonte das tasks" (ADR-0017); apontar o TIME entrega SEMPRE ao arquiteto por construção, e é o mesmo vocabulário do D2 (uma língua nas duas portas). API inbound de agente não existe e não deve existir no v1 (ADR-0018, deny-by-default). Auto-wakeup = organização gastando motor sem ninguém pedir (precedente: corretiva do caso B nasce na mesa, sem disparo). | (b) bloco aponta agente; (c) canal inbound/API; (d) wakeup automático do arquiteto. |
| **D6** | **Quando NENHUM time cobre: (b)+(c) do grill — para-e-avisa com RECOMENDAÇÃO DE CARGO na thread** (*"nenhum time cobre marketing de aquisição; recomendo time Marketing / Tráfego: um arquiteto de aquisição + um executor de mídia paga — quero que eu proponha?"*) **E, com o "sim" do dono, o card `CREATE_TEAM` no gate (D7)**. A mão soberana de criar time manual no board **fica intacta**. **Critério do Planejador (vai no prompt, texto = veto H):** atribui quando um time existente cobre o domínio; recomenda contratação quando nenhum cobre; **nunca atribui "no menos pior"**; na dúvida entre dois times plausíveis → `needs-grilling`. | O norte do dono literal: "CEO **discute** → racional de cargos → contrata" — tem um DISCUTE antes do contrata; hire seco no gate pularia a discussão. Com o D4, a discussão de contratação nasce ancorada na issue que a motivou (daqui a meses, o porquê do time Marketing está a um clique do card que o originou). O espírito do B1 (chute de destino proibido) vale na triagem. | (a) proposta de hire direto sem discussão; (só-b sem card composto — o dono decidiu ter os DOIS: a discussão E o card único). |
| **D7** | **Card `CREATE_TEAM` = TIME + ARQUITETO, atomicamente** (`{dominio, foco, arquiteto: {name, role, motor…}}`): o APPROVE cria o time E contrata o arquiteto na MESMA tx — **o time do gate nasce com arquiteto por construção**. Executores vêm DEPOIS, como `HIRE_AGENT` individuais (racional por-agente A3 + override de motor A4 intocados). **Alargamento DELIBERADO do enum fechado** `ApprovalActionType` (4ª ação) — registrado na ADR. **Retoma automática pós-approve:** o card nasce ancorado na conversa da issue (`approval.conversationId` já existe); o APPROVE posta o bastão ao Planejador (*"o time X existe; retome a #N"*) → ele emite o ```` ```absorcao ````. | Honra o invariante pendente do Arquiteto sem esperar o grill: nenhum caminho NOVO nasce errado (a assimetria da porta manual é exatamente o que o grill do Arquiteto vai fechar). Card com N executores aprovaria no atacado o que A3/A4 desenharam para o varejo (freio de quantidade da ADR-0017 = aprovação incremental). Retoma sem o dono digitar "encaminha": o approve JÁ é a autorização (precedente "approve dispara ação real" = `WAKEUP_AGENT`); transporte = `postBaton` existente. | (b) time completo num card (formulário gigante, aprovação no atacado); (c) só o time seco (fabricaria de propósito o estado ilegal "time acéfalo"); retoma manual (fricção pura). |
| **D8** | **Registro permanente: Decision origem `AGENTE`, esqueleto DETERMINÍSTICO + julgamento como evidência** — o-que-mudou = *"#8 absorvida → time Marketing / Tráfego (arquiteta Ana)"* (montado do banco); racional = a corrente resolvida em texto do sistema; `evidencia` = o campo `racional` do bloco (palavra do Planejador, rotulada como o julgamento DELE); `anchoredToIssue(#8)`. + evento **`ISSUE_ABSORVIDA`** no activity_log (só IDs, ADR-0009). | O Decision log é o 2º cérebro: "quem cuida do quê e por quê" é o átomo que ele existe para guardar. Fronteira determinístico/LLM honesta: esqueleto nunca alucinável; palavra de LLM SÓ em `evidencia` (onde o caso B já punha "a palavra de quem decidiu"). O VENDO do caso B provou o risco da prosa: o opus chutou "encaminho pro Feynman" enquanto o sistema roteava à arquiteta. Custo ~zero: `record`+`anchoredToIssue`+`withEvidencia` = o código literal do `CorrecaoDeEntregaRouter`. | (b) sem Decision (decisão sem endereço citável); (c) Planejador redige o registro inteiro (reabre a fresta que o D6 do caso B fechou). |
| **D9** | **Guardas (idempotência + anti-loop + legado):** (1) disparo único na criação, mesma tx; (2) re-edição de issue NÃO existe (sem endpoint) — se nascer um dia, "re-editar re-dispara?" pertence àquela fatia; (3) bloco para issue que JÁ tem dono → **aviso "a #7 já tem dona: Ana — nada mudei"**, nunca re-atribui (cobre settle reexecutado E corrida com checkout); (4) emissor: **só o PLANEJADOR** roteia ```` ```absorcao ```` (Q/E = log + descarte); (5) malformado / #N inexistente → fail-soft com log; time citado inexistente/sem arquiteto → para-e-avisa acionável; (6) **company sem Planejador = ESTADO ILEGAL, não caso de produto** — decisão do dono (2026-07-11): *"estamos em fase de desenvolvimento, prefiro resetar tudo, deletar essas companys que não têm planejador do que seguir com eles existindo e podendo atrapalhar minha visão"* → **faxina operacional nos dados reais** (M12); no código o skip-com-WARN vira **letra morta declarada** (alcançável só no harness H2 — forçar personas em testes alheios seria invasivo); (7) rajada de issues → coalescing existente (1 run ativo/agente, promote-on-finalize), sem cap novo; (8) anti-loop = **freio humano por construção** (todo ciclo para-e-avisa→retoma passa pelo dono: resposta na thread ou approve no gate — racional do D7 do caso B); `needs-grilling` já conta na régua M9=5. | 100% espelho dos padrões provados (caso B + poster de bastão); as únicas decisões novas são a nº 1 (sem varredura), a nº 3 (aviso, não re-atribuição) e a nº 6 (erradicar em vez de suportar — par com "time sem arquiteto é ilegal": os dois estados acéfalos recebem o mesmo tratamento). | Varredura periódica de órfãs (subsistema sem demanda); trava numérica nova (sistema mandando no dono); suportar company sem personas como modo de operação (o dono vetou explicitamente). |
| **D10** | **ADR-0037 NOVA** ("Absorção de issues do board pelo CEO") — **número CONFIRMADO** (a 0036 foi consumida pelo caso C na branch `feat/caso-c-hire-direto`; revalidar no merge). + **Forward-refs** nas ADRs relacionadas marcando o surgimento desta: **0017, 0018, 0031, 0033, 0034** (textos prontos em §I — pedido explícito do dono: "deixe marcado nas ADRs antigas que possuem relação com essa o surgimento dessa"). | Padrão da casa 6× seguidas (0030–0035 todas novas; o D8 do caso B rejeitou emenda com racional citável). A fatia carrega decisões sem endereço em ADR existente: a 2ª ponte conversa↔trabalho (direção INVERSA — board→conversa; a 0034 foi conversa→board), issue=conversa direcionada, o alargamento do enum do gate. | Emendas dentro das 0017/0018 (mistura histórias de fatias fechadas; decisões sem endereço citável). |

---

## §C — MECANISMOS a construir (ponto de extensão; base `backend/src/main/java/com/orquestrador/`)

- **M1 — V27 + ancoragem.** `V27__issue_conversa.sql`: `alter table issue add column conversation_id
  uuid null references conversation(id)` + índice. Nula em issue não-absorvida (gate/corretiva/legado).
  (Número V27 CONFIRMADO nesta branch — V26 é a última; revalidar pós-merge do caso C.)
- **M2 — O módulo profundo da fatia** (nome sugerido: `work/issue/AbsorcaoDeIssueRouter`; a execução
  pode renomear — regra de ouro). **Dois seams:** `aoNascerDoDono(issue)` — chamado no caminho do
  `IssueController.create` (ator BOARD), MESMA tx: get-or-create da conversa *"#N — <título>"*
  (`ConversationService.open` já garante assunto único) + ancoragem (M1) + turno sentinela ao
  Planejador (conteúdo: #N + título + descrição verbatim) + wakeup — espelho do `postMessage`;
  fail-soft se company sem Planejador (D9.6, letra morta declarada). E `rotear(turnoDoPlanejador,
  absorcao)` — guardas D9 + resolução `findByCompanyIdAndDominioAndFoco` → `arquiteto_agent_id` →
  `tryAbsorver` + Decision (M7) + confirmação (M6), MESMA tx do settle.
- **M3 — 4º padrão no scanner.** `PersonaHandoffScanner` (`:32-37` tem os 3 irmãos): `ABSORCAO_BLOCK`
  coluna-0 + record `Absorcao(int issue, String dominio, String foco, String racional)` no sealed
  `Route`. Malformado/N inválido = descarte fail-soft. **Formato proposto (veto H pendente):**
  campos `dominio`/`foco` SEPARADOS — não parsear "Dev / Landing" (dominio/foco são texto livre e
  podem conter "/").
- **M4 — Rota no roteador.** `PersonaBatonRouter.afterCeoTurnAnswered`: novo case do switch →
  emissor SÓ `PLANEJADOR` (espelho byte a byte da restrição do caso B, `:57-64`).
- **M5 — Atribuição guardada + lookup.** `IssueRepository.tryAbsorver(issueId, companyId,
  arquitetoId)`: UPDATE guardado por `assignee_agent_id is null and status = 'OPEN'` (0 linhas →
  guarda D9.3, aviso). `TeamRepository.findByCompanyIdAndDominioAndFoco` (o unique já existe —
  `existsByCompanyIdAndDominioAndFoco` é o precedente).
- **M6 — Confirmação/avisos visíveis sem run.** Molde protocolo do caso B (`postaProtocolo` do
  `CorrecaoDeEntregaRouter`): turno ANSWERED, zero run, marcador de idempotência
  `[gatilho <turnoId>]` no ownerMessage. Textos determinísticos (veto H): feliz (com
  arquiteto/time + racional do bloco), já-tem-dono, time inexistente, time sem arquiteto (legado,
  moldura de anomalia), recomendação de cargo (D6).
- **M7 — Registro.** Decision via `DecisionLog.record` (padrão literal do caso B: `OPERACIONAL`,
  `AGENTE`, `anchoredToIssue`, `withEvidencia` = racional do bloco) + `ActivityAction.ISSUE_ABSORVIDA`
  (enum aditivo; detail só-IDs).
- **M8 — `CREATE_TEAM` no gate.** Enum `ApprovalActionType` += `CREATE_TEAM` (o `ProposalScanner`
  aceita via valueOf sem mudança estrutural). `ApprovalService.dispatchAction` case novo:
  `TeamService.create` + `AgentHireService.hire`(arquiteto) + `assignAgent`(→time) +
  `setArquiteto`, MESMA tx; alvo malformado → 409 acionável (espelho dos cases existentes).
  **Retoma:** se `approval.conversationId != null`, posta bastão ao Planejador
  (*"o time <dominio>/<foco> existe; retome a triagem da #N"*) — reusa `PersonaContinuationPoster`.
- **M9 — `CREATE_ISSUE` com time (D2).** Alvo ganha `dominio`/`foco`; `dispatchAction` resolve
  time→arquiteto e cria com assignee (o nascimento-com-dono já existe: caminho da corretiva no
  `IssueService`); ausente/corrente quebrada → 409 acionável no decide. Card do gate mostra o
  destino (*"→ time Dev / Landing"*).
- **M10 — Prompt do Planejador.** Cabeçalho por-persona do `composePrompt` ganha a instrução da
  absorção: o critério do D6 (atribui × recomenda × re-grelha; nunca "no menos pior") + formato do
  bloco INDENTADO (nunca dispara ao ser ecoado — disciplina coluna-0). Prompt do Questionador:
  INTOCADO (G1/G2 provados ao vivo; atenção à carona do caso C no `blocoQuestionador` — rebase
  pós-merge).
- **M11 — Superfície.** `IssueResponse` += `conversationId` (aditivo); detalhe do card ganha a
  thread da issue (link/embed da conversa ancorada); card `CREATE_TEAM` no gate (destino + arquiteto
  + racional). Board: assignee no card JÁ renderiza (feedback primário custo zero).
- **M12 — Faxina operacional (D9.6).** Script para o OPERADOR (precedente faxina M9 do G3, 24→3):
  auditar companies sem as 3 personas → deletar. Gotchas conhecidos: `activity_log` append-only por
  trigger (`DISABLE` na janela) · o agente NÃO roda delete no `:8080` (leituras read-only; o
  operador executa). Provável já estar limpo (R3-A anexou as personas às 2 demos) — a auditoria
  confirma.
- **M13 — Registro doc.** ADR-0037 + forward-refs de §I nas 0017/0018/0031/0033/0034 + §RESULTADO
  neste handoff + linha do roadmap.
- **NÃO precisa:** mudança no gate T3 de hire (A3/A4 intocados) · `needs-grilling`/M9 (reusados
  como estão) · workspace novo · mudança no `ProposalScanner` além do enum · varredura/scheduler
  novo · wakeup automático do arquiteto.

---

## §D — DoD (VENDO na instância descartável — skill do repo: 18081 + pg efêmero; `:8080` INTOCADO)

1. **Feliz ponta a ponta (run claude REAL no Planejador):** dono cria *"#7 — Landing page nova"* no
   board → conversa *"#7 — Landing page nova"* nasce ancorada (`issue.conversation_id`) → Planejador
   emite ```` ```absorcao ```` → `assignee = arquiteto do time`, issue segue **OPEN**, `tryCheckout`
   só aceita o arquiteto; confirmação visível na thread com time/arquiteto/racional.
2. **Decision no log:** origem `AGENTE`, esqueleto determinístico exato do D8, `evidencia` = racional
   do bloco, ancorada na #7; `ISSUE_ABSORVIDA` no activity_log; **carta INTOCADA** (md5 antes/depois).
3. **Não-cabe ponta a ponta:** org sem time compatível → recomendação de cargo na thread → dono:
   "propõe" → card `CREATE_TEAM` PENDING ancorado na conversa → APPROVE → time + arquiteto criados
   na MESMA tx → **retoma automática** (bastão ao Planejador) → ```` ```absorcao ```` → #8 atribuída.
4. **Elo quebrado (legado):** time citado sem arquiteto → para-e-avisa acionável, **nada atribuído**,
   0 Decision.
5. **Já-tem-dono:** bloco para issue com assignee → aviso "nada mudei", assignee INTACTO (prova
   também a idempotência do settle reexecutado).
6. **Fail-soft:** malformado / #N inexistente / emissor Q ou E → descarte com log, thread segue.
7. **D2 (segunda porta):** proposta `CREATE_ISSUE` com time → issue nasce com assignee = arquiteto;
   sem time → **409 acionável no decide**, proposta segue PENDING.
8. **Rajada:** 3 issues criadas em sequência → 3 conversas + 3 turnos coalescidos no MESMO
   Planejador (fila promote-on-finalize), as 3 absorvidas.
9. **Faxina (operador):** auditoria de companies sem 3 personas no `:8080` (read-only pelo agente;
   delete pelo operador SE houver sobra).
10. **Gates:** `mvn -o test` ≥ **piso da main pós-merge do caso C** (esperado **503**; confirmar
    rodando a suíte ANTES do 1º código — precedente caso B) + novos · front ≥ **263** + thread/card ·
    tsc/lint limpos.

---

## §E — Evidência (código real, verificado neste grill)

- **A órfã e o chokepoint:** `work/issue/IssueService.java:63-70` (create sem assignee; lock da
  company + MAX+1) · `IssueController.java:40-46` (POST do board, ator BOARD) ·
  `ApprovalService.java:207-216` (case `CREATE_ISSUE` — só title/description/riskLevel, SEM time:
  a lacuna do D2).
- **Nascimento com dono já existe:** `IssueService.createCorretiva` (assignee pré-setado) +
  `IssueRepository.tryCheckout` (guard `assignee is null or = :agentId` — provado no caso B).
- **Gatilho→turno mesma tx (o molde do M2):** `ConversationService.postMessage:58-80` (turno +
  wakeup atômicos; balcão único `:63-70`) · `open():44-51` (assunto único por company).
- **Scanner extensível:** `PersonaHandoffScanner.java:32-37` (3 padrões coluna-0; sealed `Route`) ·
  restrição de emissor: `PersonaBatonRouter.java:57-64` (só Q roteia `correcao` — o espelho do M4).
- **Corrente time→arquiteto:** `CorrecaoDeEntregaRouter.java:98-126` (resolução + para-e-avisa
  acionável) · `TeamService.assertDispatchAllowed/dispatchBlocked` (a trava do time acéfalo) ·
  `TeamRepository.existsByCompanyIdAndDominioAndFoco` (identidade dominio/foco).
- **Confirmação sem run + idempotência:** `CorrecaoDeEntregaRouter.postaProtocolo:168-175` +
  marcador `[gatilho <uuid>]` (`:205-212`).
- **Decision AGENTE:** `CorrecaoDeEntregaRouter.protocola:137-147` (o código literal do M7).
- **Ancoragem approval↔conversa (o trilho da retoma D7):** `approval.conversationId` +
  `ApprovalService.anchoredTo:157-160` · "approve dispara ação real" = case `WAKEUP_AGENT:217-230`.
- **Bastão/poster (transporte da retoma + turno do Planejador):**
  `PersonaContinuationPoster.postBaton/postTurno:54-119` (idempotência pending-flow + coalescing).
- **Numeração:** V26 = última migração NESTA branch (V27 livre) · ADR-0036 consumida pelo caso C
  (0037 livre) · piso na branch do caso C: backend **503** (497+6).

---

## §F — Gotchas / ambiente

- **⛔ O GATE (repetido de propósito): branch de main SÓ DEPOIS do merge do caso C (R4-A).**
  Colisão real: `conversation/persona/` (scanner/router/poster) + `composePrompt`
  (`blocoQuestionador` mudou no caso C) + `ProposalScanner` + `PersonaBatonFlowTest`. Pós-merge,
  REVALIDAR: piso (esperado 503/263), V27 livre, ADR-0037 livre.
- **A working tree de HOJE está na branch `feat/caso-c-hire-direto`** (o grill rodou nela; main =
  `745a1b7`). Este handoff está UNTRACKED nela. **Risco de varredura:** o hunk do roadmap desta
  sessão está NÃO-COMMITADO na mesma tree — se a execução do caso C commitar o roadmap
  (`git commit -a`), pode levá-lo junto; benigno em conteúdo, mas a sessão de controle foi avisada.
- **`:8080` compartilhado: NUNCA reiniciar/matar** — /verify = skill do repo (18081 + pg efêmero).
  Faxina M12 = mão do OPERADOR.
- **`DatabaseClock`, nunca `Instant.now()` cru**, nos caminhos novos com tempo.
- **Blocos coluna-0:** o exemplo do ```` ```absorcao ```` viaja INDENTADO no prompt do Planejador.
- **Prompt do Questionador: NÃO tocar** (G1/G2 + carona do caso C — rebase primeiro).
- **NUNCA commitar** `docs/controle-paralelismo.md` nem `docs/laudo-lease-adr0005.md`.
- Branch sugerida: **`feat/absorcao-issues-adr0037`** a partir da main PÓS-merge do caso C; PR;
  **merge só com OK do Matheus**. Este handoff + hunks do roadmap: a execução commita junto
  (precedente R2-B).

---

## §G — Fora do escopo (explícito) + CONTRATOS DE INTENÇÃO registrados (pendentes de grill)

1. **⭐ GRILL DO ARQUITETO (faixa nova para a sessão de controle)** — contrato de intenção VERBATIM
   do dono (2026-07-11), registrado aqui como SEMENTE do grill, **decisão de modelo NÃO tomada nesta
   fatia:** *"Time sem arquiteto NÃO DEVE existir; todo time nasce com um arquiteto — nem é questão
   de precisar do aprove. O arquiteto é a entidade mais importante de todo time: é quem tem a
   capacidade de lidar com os planos e designar qual tarefa vai pra qual funcionário dentro daquele
   time. Possível divisão em 2, semelhante ao CEO: o arquiteto que recebe a tarefa grande e sem
   especificação é o Planejador; ele designa ao Executor, que possui o conhecimento dos agentes e
   das skills de cada um e sabe para quem enviar. Escala: demanda muito grande criando ociosidade
   >30min → cria-se cópia do arquiteto para produzir mais soluções."* Território daquele grill:
   invariante de nascimento na porta MANUAL (supersede parcial da ADR-0017 "trava no dispatch, não
   na criação"); `setArquiteto(null)` e a limpeza-do-posto no `assignAgent`; multi-arquiteto
   (schema: coluna única hoje); clone-por-ociosidade (governança: passa pelo gate?); a divisão em
   2 personas. **Nesta fatia:** o caminho novo (gate `CREATE_TEAM`) já nasce certo por construção
   (D7); o para-e-avisa cobre o legado como cinto de segurança; a absorção aponta o TIME atrás de
   um seam — a evolução do arquiteto muda a resolução num lugar só.
2. **Re-edição de issue** (endpoint não existe; "re-editar re-dispara?" pertence à fatia que criar
   a edição).
3. **Wakeup automático do arquiteto pós-absorção** (D5 — evolução possível se a prática pedir).
4. **Generalizar "toda issue nasce com conversa"** para as outras portas (gate/corretiva) — o
   trilho (V27) fica pronto; a generalização é fatia futura.
5. **Absorção de issues criadas pelo gate** — fechada NA ORIGEM pelo D2 (nascem com dono), não por
   absorção.
6. **Autoria plena do arquiteto** (brief no prompt do run + credencial) — evolução declarada na
   ADR-0034, segue futura.
7. **Deleção de companies como FEATURE de produto** — a faxina M12 é operação de ambiente de
   desenvolvimento, não superfície.
8. **Atribuição MANUAL pelo dono** (endpoint/botão de assign na UI) — o verbatim a cita como
   exceção possível ("eu posso até… atribuir manualmente a algum"); leitura do ledger: exceção,
   não fluxo. Segue INEXISTENTE de propósito nesta fatia (a absorção É o fluxo); se a exceção
   fizer falta na prática, é fatia própria pequena.

---

## §H — Ordem de execução sugerida (TDD)

1. **M1** — V27 + ancoragem (teste: issue nasce/fica sem conversa; ancorada aponta certo).
2. **M5** — `tryAbsorver` guardado + `findByCompanyIdAndDominioAndFoco` (testes: atribui órfã OPEN;
   0 linhas com dono já setado; company errada não vaza).
3. **M3** — 4º padrão do scanner (testes-espelho dos 3 irmãos: coluna-0, malformado, campos
   faltando, N inválido).
4. **M2 (núcleo)** — os dois seams do router: `aoNascerDoDono` (conversa+turno+wakeup mesma tx;
   fail-soft sem Planejador; NÃO dispara para corretiva/gate/agente) e `rotear` (guardas D9 · feliz ·
   elo quebrado · já-tem-dono · Decision · confirmação) — tudo na mesma tx.
5. **M4** — fiação no `PersonaBatonRouter` (emissor só Planejador).
6. **M6 + M7** — textos determinísticos + Decision/`ISSUE_ABSORVIDA`.
7. **M9** — `CREATE_ISSUE` com time (D2) no `ApprovalService` (+ 409 acionável).
8. **M8** — `CREATE_TEAM` (enum + dispatch atômico + retoma pós-approve).
9. **M10 + M11** — prompt do Planejador (indentado) + front (thread no card, card do gate,
   `conversationId` no DTO).
10. **M12 + M13** — faxina do operador + ADR-0037 + forward-refs §I + roadmap + VENDO §D na
    descartável.

---

## §I — Forward-refs PRONTAS (aplicar nas ADRs antigas — pedido explícito do dono; ajustar o número se a 0037 mudar no merge)

- **ADR-0017 →** `## Forward-ref (2026-07-11 — ADR-0037, absorção de issues)` — *"A criação de time
  ganhou uma 2ª porta: o gate `CREATE_TEAM` (proposto pelo CEO na triagem de issue órfã), que nasce
  COM arquiteto atomicamente — o caminho novo já obedece o invariante 'todo time nasce com
  arquiteto' declarado pelo dono (2026-07-11). A porta manual segue com a trava no dispatch; o
  supersede da regra 'trava no dispatch, não na criação' está PENDENTE DE GRILL próprio (contrato de
  intenção verbatim em `handoff-grilling-absorcao-issues.md` §G.1 — inclui a possível divisão do
  arquiteto em 2 personas e o multi-arquiteto por ociosidade)."*
- **ADR-0018 →** `## Forward-ref (2026-07-11 — ADR-0037, absorção de issues)` — *"O enum FECHADO
  `ApprovalActionType` foi alargado DELIBERADAMENTE com a 4ª ação `CREATE_TEAM` (time + arquiteto
  atômico, retoma da absorção pós-approve). `CREATE_ISSUE` passou a carregar o time no alvo e a
  nascer com assignee = arquiteto (invariante do dono: nada que o CEO cria nasce sem dono). A issue
  criada pelo dono no board dispara a ABSORÇÃO (board→conversa — o transporte inverso da 0034);
  issue = conversa direcionada (ancoragem 1:1)."*
- **ADR-0031 →** `## Forward-ref (2026-07-11 — ADR-0037, absorção de issues)` — *"O Planejador
  ganhou a triagem das issues órfãs do dono: 4º bloco tipado ```` ```absorcao ```` (emissor restrito
  a ele, espelho da restrição do caso B), decidindo time-alvo contra o `### Equipe` (M8); kickback
  `needs-grilling` e régua M9 reusados como estão."*
- **ADR-0033 →** parágrafo no fim — *"Forward-ref (2026-07-11): o lookup por `#N` ganhou o 2º
  consumidor previsto — a absorção de issues (ADR-0037) referencia a issue pelo seq no bloco e nas
  confirmações."*
- **ADR-0034 →** parágrafo no fim — *"Forward-ref (2026-07-11): o padrão bloco-tipado→corrente
  determinística→para-e-avisa ganhou o irmão INVERSO na ADR-0037 (absorção: board→conversa; aqui foi
  conversa→board), incluindo a 2ª Decision de origem AGENTE e o reuso do molde de
  protocolo/idempotência `[gatilho <id>]`."*

---

## § Pendências de veto do dono (propor no PR; NÃO decidir sozinho na execução)

- **Formato do bloco** ```` ```absorcao ```` — proposto `{"issue": 7, "dominio": "Dev", "foco":
  "Landing", "racional": "…"}` (campos separados > parse de string; irmão dos pinados em
  `PersonaHandoffScannerTest`).
- **Textos determinísticos** (M6): feliz · já-tem-dono · time inexistente · sem arquiteto (moldura
  de anomalia) · recomendação de cargo · retoma pós-approve.
- **Assunto da conversa ancorada** — proposto *"#7 — <título da issue>"*.
- **Nome do evento** — proposto `ISSUE_ABSORVIDA`.
- **Card `CREATE_TEAM`** — layout/textos (destino + arquiteto + racional).
- **Critério do Planejador no prompt** (D6) — o texto exato.
- **Nome do módulo** — proposto `AbsorcaoDeIssueRouter`.

---

## Mensagem `/nova-sessao` — kickoff de execução

> Ver bloco de kickoff entregue na sessão de grill (R4-B) — replicado no ledger da sessão de
> controle. Fonte da verdade desta fatia: **este arquivo** (D1–D10/M1–M13 FECHADOS; seguir, não
> replanejar).

---

## §RESULTADO (execução 2026-07-11, branch `feat/absorcao-issues-adr0037`)

**Gate de colisão:** PASS — caso C (PR #13) confirmado MERGED na main (`4b45a1a`) antes da
branch; piso pós-merge re-medido rodando a suíte ANTES do 1º código: backend **503** (exato o
esperado) · front 263. V27 e ADR-0037 revalidadas livres.

**Executado como planejado (D1–D10, M1–M13, ordem §H).** TDD vertical: cada mecanismo nasceu de
teste RED → GREEN. Código novo: `V27__issue_conversa.sql` · `AbsorcaoDeIssueRouter` (2 seams) ·
`AbsorcaoSentinel` (gatilho + retoma) · 4º padrão `Absorcao` no scanner · case no
`PersonaBatonRouter` (emissor só Planejador) · `tryAbsorver` + `findByCompanyIdAndDominioAndFoco`
+ `findFirstByCompanyIdAndConversationId` · `CREATE_TEAM` no enum/dispatch/retoma ·
`CREATE_ISSUE` com time (`createAtribuida`/`nascerAtribuida`, 409 acionável) · prompt do
Planejador (critério D6 + bloco INDENTADO; Questionador INTOCADO) · front (thread no detalhe do
card via `IssueConversaSection`, destino no card de `CREATE_ISSUE`, card `CREATE_TEAM` com
racional, `conversationId` no DTO/tipo).

**VENDO §D na descartável (18081 + pg efêmero; :8080 INTOCADO) — 10/10 PASS:**
1. **Feliz (run claude REAL do Planejador):** #2 absorvida — conversa "#2 — …" ancorada, bloco
   emitido pelo opus, assignee = arquiteta, OPEN, confirmação com racional visível; `tryCheckout`
   intruso=409 / arquiteta=200. *Aprendizado vivo:* na 1ª rodada o opus leu `#### Dev/Landing`
   do `### Equipe` como UM campo (`dominio:"Dev/Landing"` + foco inventado) → o prompt ganhou o
   mapeamento explícito (`#### <dominio>/<foco>` → dois campos EXATOS) e a 2ª rodada absorveu.
2. **Decision/evento/carta:** Decision AGENTE/OPERACIONAL, o-que-mudou = esqueleto D8 exato,
   `evidencia` = racional do bloco, ancorada; `ISSUE_ABSORVIDA` só-IDs; carta md5 IGUAL
   antes/depois.
3. **Não-cabe ponta a ponta:** recomendação de cargo visível → "propõe" do dono → trilho Q→P→E →
   card `CREATE_TEAM` PENDING ancorado → APPROVE → time + arquiteta Aqua na MESMA tx (membro ✓)
   → **retoma automática** → ```` ```absorcao ```` → #3 atribuída à Aqua.
4. **Elo quebrado:** time Ops/Infra sem arquiteto → aviso com moldura de anomalia, nada
   atribuído, zero Decision nova.
5. **Já-tem-dono:** bloco para a #2 → "A #2 já tem dona: Ana Arquiteta — nada mudei", assignee
   intacto.
6. **Fail-soft:** #99 → descarte com WARN exato, thread sem protocolo; Questionador emitindo →
   WARN "só o Planejador tria o board", nada atribuído.
7. **D2:** `CREATE_ISSUE` com time → #7 nasce com dona (e SEM conversa — negativa correta);
   sem time → HTTP 409 com a mensagem do invariante, proposta segue PENDING.
8. **Rajada:** 3 issues em sequência → 3 conversas + turnos coalescidos no MESMO Planejador
   (resposta dinâmica por `#N` extraído do prompt), as 3 absorvidas.
9. **Faxina (auditoria read-only no :8080):** 2 demos com 3 personas ✓; **1 ilegal:
   `thumb-demo-9368` (0 personas)** — delete é mão do OPERADOR
   (`scratchpad/absorcao-faxina-m12-auditoria.sh`, PARTE 2 comentada).
10. **Gates:** backend **523** (piso 503 +20) · front **270** (piso 263 +7) · tsc/lint limpos.
    UI verificada ao vivo (next dev 13000 → 18081): thread limpa no detalhe do card (blocos de
    mecanismo descascados), lista de chats com prefixo `#N —`, card CREATE_TEAM completo.

**Divergências mínimas de execução (com racional):**
- **Prompt do EXECUTOR ganhou o formato do `CREATE_TEAM`** (M10 só citava o Planejador): sem o
  shape no prompt, o motor inventaria o formato e o gate ficaria vazio — a lição literal do caso
  C/Prova R3-A. Acréscimo espelho do exemplo do HIRE_AGENT, INDENTADO.
- **"time inexistente" e "recomendação de cargo" colapsaram num ÚNICO aviso** (M6 listava os
  dois): com o bloco apontando o time RECOMENDADO (D6), citação-errada e recomendação são
  indistinguíveis por construção — o texto único serve às duas leituras (nome visível + racional
  + como proceder). Reversível por veto.
- **Carona de front:** `splitReply` passou a descascar também ```` ```correcao ```` e
  ```` ```absorcao ```` (só descascava handoff/needs-grilling — o bloco do caso B já vazava cru
  na bolha do chat; 1 linha, teste pinado).
- **Prompt do Planejador ganhou o mapeamento `#### <dominio>/<foco>` → dois campos** (achado da
  prova 1 ao vivo, acima) — emenda de texto dentro do próprio M10, proposto como veto.

**Vetos pendentes PROPOSTOS no PR (implementados como propostos; NENHUM decidido em definitivo):**
formato do bloco (campos separados; racional OBRIGATÓRIO — sem ele não há evidência de Decision)
· textos M6 (feliz/já-tem-dono/recomendação-única/sem-arquiteto; voz = Planejador, molde caso B)
· assunto "#N — <título>" · evento `ISSUE_ABSORVIDA` · card CREATE_TEAM (verbo "Criar time",
destino + arquiteto + racional) · critério do Planejador no prompt (texto exato, com o
mapeamento do ### Equipe) · nome `AbsorcaoDeIssueRouter`.
