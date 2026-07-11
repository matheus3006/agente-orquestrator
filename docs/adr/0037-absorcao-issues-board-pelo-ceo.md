# ADR-0037: Absorção de issues do board pelo CEO — issue do dono vira triagem do Planejador; issue = conversa direcionada

**Status:** Aceito (2026-07-11) · grill fechado em 2026-07-11 (`handoff-grilling-absorcao-issues.md`, D1–D10/M1–M13) · migração **V27**

## Contexto
Uma issue criada pelo dono no board ficava **ÓRFÃ**: o checkout é agent-driven e a UI não tem
botão de empurrar, de propósito — mas "quem deve delegar é o CEO" (verbatim do dono, 2026-07-10:
*"eu posso até criar uma issue lá… mas quem deveria de fato fazer isso é o CEO. Caso eu criasse
uma issue lá, o CEO deveria pegar essa issue e absorver"*). E o próprio CEO fabricava órfãs pela
outra porta: o `CREATE_ISSUE` do gate (ADR-0018, nascido antes da entidade Time) só lia
title/description/riskLevel. Dois invariantes novos do dono (2026-07-11): **"nada que o CEO cria
nasce sem dono"** e **"a abertura de uma issue nada mais é do que uma conversation, só que uma
direcionada"**. Esta ADR é a **segunda ponte conversa↔trabalho** — a direção INVERSA da ADR-0034
(lá conversa→board; aqui board→conversa).

## Decisão

### 1. Gatilho: issue criada pelo DONO (ator BOARD), UMA vez, na MESMA tx da criação (D1)
`IssueController.create` com ator BOARD passa por `AbsorcaoDeIssueRouter.aoNascerDoDono`:
criação + conversa ancorada + turno-gatilho + wakeup **atômicos** (espelho do
`ConversationService.postMessage`). **Sem varredura periódica de órfãs** (subsistema sem
demanda); issues do gate/corretiva/agente NÃO disparam (nascem com dono — §4; ruído de run extra
foi a lição do G4).

### 2. Issue = conversa DIRECIONADA, ancorada 1:1 (D4, V27)
`issue.conversation_id` (V27) aponta a conversa da issue, assunto determinístico
**"#N — \<título\>"**, criada na absorção. O turno-gatilho ao Planejador nasce NELA como
**sentinela** (prefixo `[continuação` — invisível pelo filtro da ADR-0030); **confirmações e
re-grelha são visíveis NELA**; a resposta do dono ali entra pelo balcão único normal e o bastão
existente retoma o fluxo. Front: a thread mora no **detalhe do card** (o board segue o menu de
observação); na lista de chats o prefixo `#N —` distingue. Generalizar "toda issue nasce com
conversa" às outras portas = evolução registrada, fora desta fatia (o trilho V27 fica pronto).

### 3. Quem tria: o PLANEJADOR; o bloco aponta o TIME; atribuição SEM gate (D3/D5)
Triagem é estratégia de orquestração — escopo literal do Planejador (ADR-0031 §10); o
`### Equipe` do estado vivo já entrega o insumo. 4º bloco tipado do `PersonaHandoffScanner`
(mesmo dialeto: JSON em cerca **coluna 0**; fail-soft): ```` ```absorcao ````
`{"issue": 7, "dominio": "Dev", "foco": "Landing", "racional": "é trabalho de frontend"}` —
campos `dominio`/`foco` **separados** (texto livre pode conter "/") e `racional` obrigatório
(G1/G6: racional invisível nunca mais). O bloco aponta o **TIME, nunca pessoa, nunca API**: o
sistema resolve **time→arquiteto** deterministicamente e seta o assignee via UPDATE guardado
(`tryAbsorver`: `assignee is null and status = 'OPEN'`) — a issue segue OPEN **na mesa do
arquiteto** (o guard do `tryCheckout` já honra o pré-set, provado no caso B). **SEM wakeup
automático do arquiteto** (organização gastando motor sem ninguém pedir); **sem gate** na
atribuição — o ato do dono de criar a issue É a autorização de delegar (racional do D1 do caso
B). Emissor restrito: só o Planejador roteia (`PersonaBatonRouter`); Q/E = log + descarte.
Critério no prompt (M10): atribui quando um time existente cobre; recomenda quando nenhum cobre;
**nunca "no menos pior"**; dúvida entre dois plausíveis → `needs-grilling` (máquina M9 reusada).

### 4. Segunda porta fechada NA ORIGEM: `CREATE_ISSUE` carrega o time (D2)
O alvo da proposta ganha `dominio`/`foco`; o APPROVE resolve time→arquiteto e a issue **nasce com
assignee = arquiteto** (`IssueService.createAtribuida`, mesmo chokepoint da ADR-0033). Proposta
sem time ou corrente quebrada → **409 acionável no decide** (mesma tx: rollback total, a Approval
segue PENDING); conserto = reject + nova proposta, ou o dono cria a issue no board (cai no §1).
NÃO reabre o T3: enum de DECISÃO intocado; campo aditivo no alvo.

### 5. Quando NENHUM time cobre: recomendação de cargo na thread + card `CREATE_TEAM` (D6/D7)
O Planejador emite o bloco com o time que **RECOMENDA criar** (dominio/foco novos) e o racional
dos cargos; o sistema para-e-avisa VISÍVEL com a recomendação rotulada como julgamento DELE (a
fronteira determinístico/LLM do §6) e ensina o próximo passo. Com o "sim" do dono na thread, o
trilho existente (Q→P→E) protocola o card único **`CREATE_TEAM`** —
`{dominio, foco, arquiteto: {name, role, motor…}, racional}` — **alargamento DELIBERADO do enum
fechado** `ApprovalActionType` (4ª ação): o APPROVE cria o time E contrata o arquiteto na MESMA
tx (`TeamService.create` + `hire` + `assignAgent` + `setArquiteto`) — **o time do gate nasce com
arquiteto por construção** (o invariante do §G.1 honrado no caminho novo; a porta manual segue
com a trava no dispatch — supersede pendente de grill próprio). Executores vêm DEPOIS, como
`HIRE_AGENT` individuais (aprovação incremental da ADR-0017; A3/A4 intocados). **Retoma
automática:** o card nasce ancorado na conversa da issue (`approval.conversationId`); o APPROVE
posta o bastão ao Planejador (*"o time existe; retome a #N"*) — o approve JÁ é a autorização
(precedente "approve dispara ação real" = `WAKEUP_AGENT`); transporte = turno sentinela + wakeup.

### 6. Registro permanente: Decision origem AGENTE com esqueleto determinístico (D8)
O-que-mudou = *"#8 absorvida → time Marketing / Tráfego (arquiteto: Ana)"* (montado do banco);
racional = a corrente resolvida em texto do sistema; **`evidencia` = o campo `racional` do
bloco** (a palavra do Planejador, rotulada como o julgamento DELE — o molde do caso B);
`anchoredToIssue`. + evento **`ISSUE_ABSORVIDA`** no activity_log (ator = o Planejador; detail
só-IDs `{teamId, arquitetoAgentId}` — ADR-0009).

### 7. Guardas (D9)
(1) disparo único na criação, mesma tx; (2) re-edição de issue não existe — pertence à fatia que
a criar; (3) bloco para issue que JÁ tem dono → aviso *"já tem dona: X — nada mudei"*, nunca
re-atribui (cobre settle reexecutado E corrida com checkout; `tryAbsorver` 0-linhas é o
backstop); (4) emissor só Planejador; (5) malformado/#N inexistente → fail-soft com log; time
sem arquiteto → para-e-avisa com moldura de anomalia (legado); (6) **company sem Planejador =
ESTADO ILEGAL** — decisão do dono: erradicar via faxina do operador, não suportar; o
skip-com-WARN é **letra morta declarada** (alcançável só no harness H2); (7) rajada → coalescing
existente (1 run ativo/agente, promote-on-finalize), sem cap novo; (8) anti-loop = **freio humano
por construção** (todo ciclo para-e-avisa→retoma passa pelo dono); `needs-grilling` conta na
régua M9=5. Idempotência do protocolo: marcador `[gatilho <turnoId>]` (molde da ADR-0034).

## Consequências
- A órfã do board morre nas DUAS origens: a issue do dono é absorvida; a issue do gate nasce com
  dono. O board vira o "menu para observar quem está cuidando do quê" do verbatim.
- O gate ganha a 4ª ação (`CREATE_TEAM`) — todo caminho NOVO de criação de time nasce com
  arquiteto; a assimetria da porta manual fica explícita e endereçada ao grill do Arquiteto
  (semente verbatim em `handoff-grilling-absorcao-issues.md` §G.1).
- `conversation_id` nulo nas issues não-absorvidas é honesto: o trilho da generalização
  "toda issue nasce com conversa" fica pronto sem forçar a decisão agora.
- Textos das confirmações/avisos, formato do bloco, assunto da conversa, nome do evento, card e
  critério do prompt: PROPOSTOS ao dono no PR (vetos pendentes, reversíveis).

## Forward-refs aplicadas
0017 (2ª porta de criação de time), 0018 (4ª ação + CREATE_ISSUE com time + transporte inverso),
0031 (4º bloco do Planejador), 0033 (2º consumidor do lookup #N), 0034 (o irmão inverso).
