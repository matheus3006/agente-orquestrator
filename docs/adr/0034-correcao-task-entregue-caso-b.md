# ADR-0034: Correção de task entregue (caso B) — a mensagem do dono cria a task corretiva, sem gate

**Status:** Aceito (2026-07-10) · grill fechado em 2026-07-10 (`handoff-grilling-caso-b.md`, D1–D8/M1–M7) · migração **V26**

## Contexto
O contrato de intenção B1/B2 (grill de insatisfações, 2026-07-09) é vinculante: quando o dono
aponta que uma task **JÁ ENTREGUE** ficou ruim (*"a task #12 ficou ruim — o formulário não valida
e-mail"*), o CEO **não chuta destino** (B1) e a correção vira **task NOVA ligada à #N**, com a #N
intacta e a reincidência visível (B2). Os pré-requisitos já existiam: o #N humano com lookup
natural (ADR-0033), o roteador de blocos tipados no settle (ADR-0031) e a corrente
`Issue.assignee_agent_id → Agent.team_id → Team.arquiteto_agent_id` inteira no banco. Esta ADR é
a **primeira ponte conversa→trabalho**: mensagem do dono cria task + designa arquiteto + grava
Decisão — sem passar por gate.

## Decisão

### 1. Detecção: bloco tipado `correcao`, emitido SÓ pelo Questionador, roteado DIRETO
3º padrão do `PersonaHandoffScanner` (mesmo dialeto dos irmãos: JSON em cerca **coluna 0**;
malformado/N não-numérico descartado fail-soft): ```` ```correcao ```` `{"issue": 12}`. **Payload
mínimo deliberado** — as palavras do dono **não viajam no bloco** (LLM redigitando = paráfrase).
Sem cascata Q→P→E e **sem gate**: o roteamento é 100% determinístico (a corrente está no banco) —
não há estratégia para o Planejador nem protocolo para o Executor; e **o pedido do dono É a
autorização humana** (card de approval seria burocracia dupla). P/E emitindo o bloco = log +
descarte (`PersonaBatonRouter`, espelho do "uma virada por settle").

### 2. Verbatim: cópia mecânica do banco, nunca transcrição
No settle, o backend copia `ConversationTurn.ownerMessage` do turno-gatilho (fonte determinística
que o router já recebe) para a **descrição** da task corretiva — **byte a byte**, sem sufixo: a
descrição é md5-idêntica à mensagem do dono; a referência à #N viaja no **título** e na coluna
`corrige_issue_id` (a referência estrutural). Paráfrase impossível **por construção**. Regra de
prompt do Questionador (acréscimo ao cabeçalho por-persona; G1/G2 intocados): emitir o bloco no
turno em que a reclamação está completa; espalhada em vários turnos → *"me resume numa mensagem
só"* (o consolidado segue sendo palavra DO dono).

### 3. Autoria: o SISTEMA cria a corretiva no settle — divergência assumida da LETRA do B2
`work/issue/CorrecaoDeEntregaRouter` (seam de um método, `rotear(turnoDoDono, issueSeq)`) resolve
a corrente e cria a corretiva via `IssueService.createCorretiva` (MESMO chokepoint da ADR-0033:
lock da company + MAX+1): título *"Correção da #N — <título original>"*, descrição = verbatim,
`corrige_issue_id` setado, **assignee = arquiteto do time** — a task nasce na mesa dele e o guard
do `tryCheckout` (`assignee is null or = :agentId`) garante que só ele a executa.

**B2 diz "o Arquiteto a autora"; aqui quem autora é o sistema.** Fato duro do código que moldou a
divergência: run de Issue hoje só executa `runtimeCommand` — não recebe a descrição no prompt nem
credencial de API (`AdapterRunExecutor`/`LocalExecutionTarget`; o Bearer do
`ActorResolutionFilter` existe na API, não no run). "Fiel à letra" = construir um subsistema
inteiro. A intenção está preservada: CEO não escreve UMA palavra; task nova ligada; #N intacta
(DONE); a correção na mão do arquiteto. **Evolução declarada:** quando o empregado ganhar motor
pleno (brief no prompt + API key no run), a corretiva pode virar "pedido" e o arquiteto autora as
filhas.

### 4. Ligação: `corrige_issue_id` (V26) + selo na superfície
`alter table issue add column corrige_issue_id uuid null references issue(id)` + índice.
"Reincidência visível" (racional literal do B2) vira consulta de 1 linha; a relação
task-corrige-task é 1:1 — uma coluna é o mecanismo mínimo. Superfície: DTO aditivo
(`corrigeIssueId` + `corrigeSeq`) e selo **"corrige #N"** no board (lista + detail, com link).

### 5. Corrente quebrada / #N não-DONE: PARA-E-AVISA — nada é criado
Elo vazio (#N sem assignee · assignee sem time · time sem arquiteto) → aviso determinístico
**acionável** na conversa (*"a #12 não tem arquiteto responsável: o time Dev / Landing está sem
arquiteto. Designe um no board, ou me diga a qual time encaminhar."*) — espelho da trava dura
`TeamService.assertDispatchAllowed`. Guarda de definição: #N não-DONE → *"ainda não está
entregue"*. #N inexistente/bloco malformado → descarte fail-soft com log. O pior desfecho evitado:
a reclamação do dono na mesa errada em silêncio.

### 6. Decision determinística: o 1º writer de origem AGENTE do produto
No mesmo settle, `DecisionLog.record`: **origem `AGENTE`** (o javadoc "fatia futura — nenhum
writer no v1" se cumpre aqui), tipo `OPERACIONAL` (não toca a carta — o consolidator só projeta
`DIRECAO`), racional = a corrente resolvida em texto montado pelo sistema, `evidencia` = o
verbatim (a mesma cópia), `issueId` = a corretiva (âncora nova `anchoredToIssue` — proveniência
fora de gate). O caso B inteiro fecha com **zero palavra de LLM em registro permanente**.
Checado: não esbarra nos seams D5/D11 da ADR-0019 (não supersede nada; não passa pela review).

### 7. Confirmação visível sem run + anti-loop SEM trava numérica
Confirmação/aviso = turno **ANSWERED** do Questionador, sem wakeup (molde `postCutNotice` da
ADR-0031): *"Correção da #12 encaminhada — task #34 criada para o arquiteto Ana (time Dev /
Landing)."* O `ownerMessage` do protocolo carrega o id do turno-gatilho — a chave de
**idempotência** do settle reexecutado (não duplica task/Decision/confirmação). Linhagem
(correção de correção): sinal *"atenção: esta é a 2ª correção na linhagem da #12"* na confirmação
E na Decision — **sem trava**: cada volta começa com mensagem DO DONO (freio humano por
construção; o N=5 da casa protege loop AUTÔNOMO — falso paralelo). Recusar a K-ésima correção do
dono feriria a mão soberana.

## Alternativas rejeitadas
- **Cascata Q→P→E por uniformidade** — +2 runs (~4-8 min) sem decisão nova no caminho.
- **Card de approval (gate)** — burocracia dupla: o dono acabou de pedir.
- **CEO transcreve as palavras no bloco** — telefone-sem-fio de volta (a fresta que o B1 matou).
- **Arquiteto autora via motor** — subsistema inexistente (prompt/credencial no run); fatia
  refém de outra fatia. Registrada como evolução, não como bloqueio.
- **Só texto na descrição como ligação** — invisível pro sistema; **tabela de vínculos genérica**
  — infra sem demanda.
- **Trava numérica na linhagem (N=5)** — sistema mandando no dono.
- **Emenda na ADR-0031** — mistura a história das personas (fechada e provada) com mecanismo que
  ela nunca prometeu.

## Consequências
- O produto ganha o precedente "mensagem do dono → trabalho roteado sem gate" — válido SÓ porque
  o roteamento é determinístico de ponta a ponta; qualquer variação que exija julgamento do CEO
  deve voltar ao desenho de gate.
- `DecisionOrigem.AGENTE` deixa de ser letra morta; o Decision-log passa a ter writer fora dos
  gates (o backstop de racional da 0019/D6 cobre).
- A corrente depende de `Team.arquiteto_agent_id` — companies sem arquiteto verão o caso B
  parar-e-avisar até designarem (comportamento desejado, espelho do dispatch).
- Pendências de veto do dono (sinalizadas no PR): nome/formato do bloco `correcao`, textos das
  mensagens determinísticas (M6) e título padrão da corretiva.

**Forward-ref (2026-07-11):** o padrão bloco-tipado→corrente determinística→para-e-avisa ganhou o
irmão INVERSO na ADR-0037 (absorção: board→conversa; aqui foi conversa→board), incluindo a 2ª
Decision de origem AGENTE e o reuso do molde de protocolo/idempotência `[gatilho <id>]`.
