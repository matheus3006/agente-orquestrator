# ADR-0048: O gestor no gate de Review — parecer em estágio, decisão quando promovido

**Status:** Aceito (2026-07-20, execução da fatia F-ORG-C) · desenho grilado com o dono em
`HANDOFF-grill-org-design.md` (**D-ORG-1**, **D-ORG-2**, **D-ORG-9**) · migração **V38**
(`V38__review_parecer_do_gestor.sql`) · constrói sobre a **ADR-0045** (árvore-organograma) e a
**ADR-0046** (`gestor_status`) · replica o modelo de segurança da **ADR-0047** · **EMENDA a
ADR-0012 e a ADR-0019** (ver §Emendas) · consome `DecisionOrigem.AGENTE`, reservado desde a
ADR-0019 sem writer.

## Contexto

A dor real do dono (grill de org-design) não é governança: é VOLUME. *"Não escalo para julgar 3
times."* O gate de alto volume é o **Review** (ADR-0012) — toda entrega de risco ≥ 5 para na fila
dele. A D-ORG-1 fechou que a validação delegada é **só trabalho**: o gestor julga entregas do time
dele; a governança (as 8 ações do `ApprovalActionType`) continua 100% humana, e lá o gestor entra
como proponente, nunca decisor. Racional: **Review erra barato** (dá REOPEN); governança erra caro
(agente vivo, motor, estrutura).

A D-ORG-2 fechou o COMO da confiança: o gestor não nasce decidindo. Nasce em **ESTÁGIO**, dando
**parecer** (*"eu aprovaria, porque X"*) que o dono lê **ao lado** do card antes de carimbar;
quando os pareceres batem, o dono aperta **promover** (ato explícito, nunca automático) e o gestor
passa a **decidir sozinho**, com rastro e reabrível. A F-ORG-B entregou o estado
(`team.gestor_status`); esta fatia entrega o MECANISMO nos dois ramos.

## Decisão

### 1. O gesto: bloco ```parecer``` na saída do run, roteado pelo `gestor_status`

Molde exato do F-AT1, irmão do ```dirigir``` (ADR-0047 §1): `ParecerScanner` (parse
determinístico, cerca em coluna 0, descarte contado) + `ParecerGate` pendurado no ponto de saída
que já existe (`AdapterRunExecutor`, ao lado dos outros 3 gates) — **zero canal vivo novo**
(D-ORG-8). Campos, todos **obrigatórios**: `issue` (o **#N humano**, ADR-0033 — o LLM sabe o #N
que leu e alucina UUID, mesmo racional do `alvo` por nome), `decisao` (`APROVAR`|`REJEITAR`) e
`parecer` (o porquê — mesma régua do `racional` do dirigir, D-DSG-4: bloco sem o porquê é
descartado com aviso).

O **mesmo bloco serve os dois ramos** — o gestor não precisa saber o próprio status (D-DSG-1):
**ESTÁGIO → grava parecer consultivo**; **PROMOVIDO → decide o ciclo** (§3). CAP por run: 5
pareceres aceitos (padrão `SkillRequestGate`/`DirigirGate`).

### 2. O guard de braço: subárvore inteira, mais a cerca do auto-review

Três condições, iguais às do dirigir (D-DSG-2): o ator É gestor titular (`team.gestor_agent_id`)
de ao menos um time; o time de **quem ENTREGOU** (o `assignee` da issue sob revisão) pertence à
subárvore de um time gerido (`ChainOfCommandService.pertenceASubarvore` — reuso, nunca BFS novo);
e o `gestor_status` DESSE braço escolhe o ramo. Vale nos DOIS ramos.

**Uma cerca a mais, sem irmã na ADR-0047:** o gestor **não se auto-revisa**. `pertenceASubarvore`
inclui a raiz nela mesma, então o gestor está DENTRO do próprio braço; sem esta linha, um gestor
promovido assignado a uma issue do próprio time **aprovaria a própria entrega** — exatamente o que
o review gate existe para impedir. Vale também no ramo consultivo: opinar sobre a própria entrega
não é "julgar o time" (D-ORG-1). Issue **sem assignee** é recusada: sem quem entregou não há braço
a que ela pertença, e o default silencioso seria o furo.

Todo descarte é fail-soft com evento `PARECER_DISCARDED` no StepTrace (anti-silêncio): #N
inexistente, sem ciclo aberto, fora do braço, auto-review, acima do teto, eco colapsado.

### 3. O ramo PROMOVIDO: `reviewerActorType=AGENT`, pela porta do dono

`ParecerService.decidirReviewComoGestor(...)` chama o **MESMO** `IssueService.review(...)` que o
board chama. Não há caminho paralelo: herda de graça tudo que a review humana respeita — REJECT
exige motivo, transição guardada por `PENDING_REVIEW` (0 linhas → 409), fábrica de Decisão.

> ⚠️ **Emendado no hardening do PR #50 (2026-07-20) — ver §Hardening pós-conferência.** Este
> parágrafo afirmava, em negrito, que "o `unblock {REOPEN|CANCEL}` do dono continua a valer" e que
> "nada aqui é irreversível". Era **falso para o APPROVE**: `unblock` só aceita `BLOCKED`, e
> APPROVE leva a `DONE`, que era estado SINK. A reversibilidade passou a existir de verdade pela
> **ADR-0049** (`reabrirEntregaAprovada`, `DONE → BLOCKED`, board-only) — e só por causa dela a
> frase "é o que torna a delegação barata (D-ORG-1)" volta a se sustentar. A âncora
> `expectedReviewId`, também citada aqui originalmente, protege menos do que este texto sugeria:
> ver §Hardening, item 3.

**Escopo de segurança (molde da ADR-0047 §3, replicado elemento por elemento).** Este é o
**segundo** caminho de autorização não-humana da casa, e o primeiro que não passa pelos
`requireBoard` de `IssueController`/`ReviewController`:

- `exigirGestorTitularComAlvoNoBraco` roda como **PRIMEIRA linha** das duas entradas públicas,
  **antes de qualquer efeito e antes do dedupe** — o service REVALIDA sozinho, sem confiar no
  chamador (lição do hardening item 2 do PR #49: guard só no gate é seguro por CONVENÇÃO, não por
  construção). Cinco cercas, todas `IllegalStateException` fail-closed.
- `ActorType.AGENT` é **literal fixo** no único call site do decide, **nunca parâmetro**. Um
  `decidirReviewComo(ActorType, ...)` parametrizável seria bypass PERMANENTE do `requireBoard`
  para QUALQUER ator — **proibido como consequência silenciosa desta fatia**; generalizar exige
  decisão nova com revisão de segurança própria.
- **Asserts defensivos**, porque o compilador não pega troca de literal: **pré** — o ciclo que vai
  ser decidido é o MESMO que passou pelo guard (amarra guard e ato, fecha a janela de um refactor
  que reordenasse a resolução); **pós** — o ciclo ficou de fato carimbado como `AGENT` + o gestor
  (decisão delegada sem rastro é pior que decisão nenhuma).
- **Call site único** de produção: o `ParecerGate`.

### 4. O dedupe é ESTRUTURAL e sai de graça — sem fingerprint

O caminho sem-gate da ADR-0047 precisou de fingerprint estrutural + índice parcial porque **cria
linha nova**. Aqui não: o parecer é **1-para-1 com o CICLO** (a linha `review`), e a identidade é
de LINHA, não de texto. No zumbi do lease-reaper (mesmo `runId` re-executado, com chamada REAL
nova ao LLM que **reformula a prosa**):

- ramo **ESTÁGIO** → o ciclo já tem parecer; `temParecer()` é a cerca do **primeiro-vence**, e o
  gate transforma isso em aviso contado;
- ramo **PROMOVIDO** → não existe mais ciclo `PENDING` para aquela issue; o gate nem chega ao
  service.

Prosa reformulada não fura nenhum dos dois ramos **por construção** — o furo que custou o
hardening do #49 não tem como existir aqui. Reject → REOPEN → re-run abre um ciclo NOVO, com slot
de parecer novo: a cardinalidade certa é por ciclo, não por issue.

> ⚠️ **Emendado no hardening do PR #50 — ver §Hardening, item 2.** A conclusão sobre a prosa segue
> de pé; o "**sai de graça**" não. Os dois ramos eram apresentados como igualmente estruturais, e
> **não eram**: o PROMOVIDO tinha CAS de banco real, o ESTÁGIO era read-then-write puro (lost
> update sob concorrência). Hoje os dois têm CAS de banco.

### 5. O parecer é campo, e não carrega voto legível por máquina

O parecer é **campo novo na `Review`** (V38: `parecer` + `parecer_por_agent_id` + `parecer_em` —
molde valor/quem/quando da V36), **não turno de conversa**: a D-ORG-2 pede que o dono carimbe
vendo o parecer **AO LADO**, o que é requisito espacial, e a alternativa custaria mais encanamento
(`reviews/page.tsx` não carrega turnos; o vínculo review→conversa é indireto). **Não sequestra o
`decision_reason`**, que já está semanticamente ocupado pelo revisor que DECIDE — misturar apagaria
a fronteira entre "quem opinou" e "quem decidiu", que é a fronteira desta fatia.

A POSIÇÃO do gestor e o porquê ficam **num texto só**, composto pelo SERVICE de forma
determinística (`"APROVARIA — <porquê>"`). **Não existe coluna de voto legível por máquina**, e
isso é deliberado: a D-ORG-2 proíbe pré-selecionar a decisão do dono a partir do parecer, e com um
enum ao lado do texto essa proibição valeria por disciplina — uma linha de UI que ninguém revisa
bastaria para quebrá-la. Sem o enum, **a UI não tem como** pré-selecionar. A proibição passa a
valer por construção.

### 6. O selo e a proveniência honesta

Review decidida por AGENT ganha o selo **"decidida · \<gestor\>"** na fila (irmão do
"dirigida · Ana" do PR #48; nome resolvido client-side no mapa de agentes que a página já carrega;
gestor que sumiu do quadro degrada para "decidida por gestor", porque o FATO de não ter sido o
dono é a informação que não pode se perder). Decisão do dono **não** ganha selo — o selo marca o
que fugiu do caminho normal.

A Decisão fabricada pela review passa a declarar `DecisionOrigem.AGENTE` quando quem carimbou foi
um agente, derivado do carimbo gravado no ciclo (nenhum chamador declara a própria proveniência).
Registrar decisão de gestor como `DONO_NO_GATE` seria rastro **falso** justamente no caminho que
mais precisa de rastro.

## Emendas

- **ADR-0012 (review gate):** o contrato "**revisor = board**", endpoint board-only, ganha a sua
  primeira exceção deliberada: um gestor **PROMOVIDO** decide o ciclo de uma entrega do próprio
  braço, com `reviewerActorType=AGENT` (o dado já aceitava desde a ADR-0012; faltava o writer). A
  exceção é estreita por desenho — só dentro da subárvore, nunca sobre a própria entrega, só pelo
  método atrás do guard do §3, e o `unblock` do dono continua soberano. A escada de autoridade
  ganha o segundo degrau real entre operador e CEO (o primeiro foi o `DIRECT_ISSUE`, ADR-0047).
- **ADR-0019/D4 (proveniência da Decisão):** `DecisionOrigem.AGENTE` existia desde a ADR-0019
  marcado como "fatia futura — nenhum writer no v1". Esta fatia é esse writer.

## Consequências

- A aba APPROVED/REJECTED da fila de review passa a conter ciclos que o dono **nunca viu como
  PENDING** — mesma leitura que a ADR-0047 impôs à fila de Approvals: PENDING continua
  significando só "aguardando o dono", e as outras abas viram extrato do braço.
- O `ParecerGate` é o **4º gate** com o problema herdado da "thread tardia"
  (`AdapterRunExecutor`): os gates de fim-de-execução disparam efeito colateral sobre o `result`
  local **sem reconferir se o `runId` ainda é dono de alguma coisa no banco**. Não é bug ativo
  (exige corrida real entre 2 processos) e **não foi consertado aqui** — registrado para que o
  conserto futuro cubra os 4 de uma vez.
- **O gestor ainda não tem como DESCOBRIR que existe review pendente no braço dele.** O briefing
  (`IssuePromptComposer`) é role-blind e só fala da issue do próprio run; nenhuma das 4 tools MCP
  lista issues/reviews. O mecanismo desta ADR está completo e provado, mas só dispara quando o
  gestor souber o #N por outra via. Fechar isso é da **F-ORG-G** (briefing do gestor) e da
  **F-ORG-E** (roteamento proativo) — a mesma lacuna com que o ```dirigir``` da ADR-0047 já
  embarcou, conscientemente.
- Parecer em ciclo do ramo PROMOVIDO: não existe (lá o gestor decide). Se o dono um dia quiser
  "decidiu E deixou o porquê separado", o `decisionReason` já carrega o texto — não foi pedido.

## Hardening pós-conferência (2026-07-20, fatia hardening-parecer-seguranca)

A conferência adversarial do PR #50 achou 3 🔴 de segurança e 3 🟠 estruturais. Fechados nesta
emenda — nenhuma D-ORG reaberta, nenhum schema novo (a reserva **V39 foi DEVOLVIDA**: os 6
consertos são UPDATEs guardados e lógica, zero coluna nova). O item 1, por mudar um invariante de
ciclo de vida que **precede** esta fatia (`DONE` terminal), ganhou ADR própria: **ADR-0049**.

### 1. 🔴 O APPROVE do promovido era IRREVERSÍVEL → **ADR-0049**

Ver ADR-0049. Aqui ficam corrigidas as duas afirmações falsas que esta ADR fazia (§3) e que o
código repetia (`ParecerService`).

### 2. 🔴 O dedupe do ramo ESTÁGIO era lost update

`registrarParecerDoGestor` era read-then-write puro: `temParecer()` e depois `save`. `Review` não
tem `@Version` e o UPDATE do JPA é `where id = ?`; em READ COMMITTED duas transações liam
`parecer IS NULL` e a segunda **sobrescrevia em silêncio, devolvendo `true`** — sem o aviso "já
tinha parecer". Medido, não teorizado: **6 chamadas concorrentes gravaram as 6**.

**Mecanismo:** UPDATE guardado `ReviewRepository.tryRegistrarParecer` com `and parecer is null` —
o idioma da casa (`tryCheckout`, `tryApprove`, `tryAbsorver`), em que quem arbitra a corrida é o
`rows affected`. `rows==0` → alguém já gravou → `false` → aviso contado.

**Por que não `@Version`**: pegaria TODOS os writers de `Review` (approve/reject inclusive) e
trocaria um `false` limpo por uma exceção que cada chamador teria de tratar — blast radius grande
para um campo só. **Por que não índice parcial** (o remédio da V37/ADR-0047): lá o furo criava
LINHA nova; aqui é coluna de linha que já existe, e unique não expressa "no máximo um
preenchimento". O setter `Review.registrarParecer` foi **removido**: com a escrita só pelo UPDATE
guardado, o caminho inseguro não existe mais para ser reintroduzido por engano.

O teste que dava a falsa confiança chamava o gate **2× em sequência na mesma thread** (zumbi
SEQUENCIAL). O novo é CONCORRENTE de verdade, com barreira de largada.

### 3. 🔴 O `expectedReviewId` é CAS decorativo — **corrigida a AFIRMAÇÃO, não o mecanismo**

O §3 dizia que este ramo "herda de graça a âncora `expectedReviewId`". **Herda a chamada, não a
proteção.** O gate resolve o ciclo PENDING ele mesmo e o passa como âncora; o service re-resolve a
mesma query e compara com o valor que o gate produziu. No caminho humano a âncora vem de FORA (a
UI do dono viu o ciclo X); aqui **nada carrega a identidade do ciclo sobre o qual o LLM
raciocinou** — o bloco só traz o `#N`.

**O que ela protege de verdade** (e agora está escrito assim no código): a janela entre a
resolução no `ParecerService` e a resolução dentro do `IssueService.review` — o TOCTOU do item 4.
**O que ela não protege:** o cenário que `IssueService` documenta, de um APPROVE atrasado chegando
depois de reject→REOPEN→re-run abrir um ciclo NOVO.

**Amarrar identidade de raciocínio foi considerado e recusado.** A única âncora disponível sem
exigir do LLM um UUID que ele não tem seria **temporal** — "o ciclo já existia quando o run do
gestor começou", comparando `review.requested_at` com `heartbeat_run.started_at`. Ela pega o
cenário do ciclo novo, mas passa a produzir **recusa FALSA** assim que a F-ORG-E/F-ORG-G derem ao
gestor tools que listam review DURANTE o run: seria construir um guard que a próxima fatia teria
de remover. Preferiu-se o limite conhecido e escrito à proteção que não protege — que é pior,
porque a fatia seguinte confia nela.

### 4. 🟠 TOCTOU dono×gestor travava a issue DO PRÓPRIO GESTOR, para sempre

Se o dono decidisse o ciclo entre a resolução do gate e a do service, o service levantava e
**ninguém pegava**: o gate não tinha try/catch, o `AdapterRunExecutor` idem, e o `execute` chama
`runToOutcome` **antes** de `settleAfterRun` ⇒ a exceção **pulava o settle**. A issue ficava
`IN_PROGRESS` com `execution_lock_run_id` de um run morto; `tryCheckout` exige essa coluna
null-ou-igual e **não existe reaper para ela** — impegável para sempre. Contradizia o fail-SOFT que
o próprio gate declara.

**Fecho em duas camadas:** (a) no `ParecerGate`, try/catch POR PARECER — uma recusa do service é
resultado normal deste canal ("o gestor chegou tarde"), vira aviso contado e o lote segue; e
try/finally em volta do loop, para que os avisos já acumulados cheguem ao StepTrace em vez de se
perderem junto com a exceção. (b) No `AdapterRunExecutor`, `semDerrubarOSettle(...)` envolve os
**4** gates de fim-de-run — **a exposição herdada foi fechada para os 4 de uma vez**
(`skillRequestGate`, `dirigirGate`, `parecerGate`, `planValidationGate`), porque o dano não é "um
gate falhou", é a Issue travada para sempre. Não silencia: vira step `GATE_DE_SAIDA_FALHOU`.

### 5. 🟠 Roteamento multi-braço decidido por ordem de linha

`TeamRepository` sem `ORDER BY` + `.findFirst()`: um agente pode constar como gestor de um time PAI
e de um DESCENDENTE, `gestor_status` é POR TIME, e as duas subárvores contêm o entregador ⇒ **se
ele apenas OPINA ou DECIDE dependia da ordem arbitrária das linhas.**

**Regra:** vence o **MENOS PRIVILEGIADO** — qualquer braço aplicável em ESTÁGIO ⇒ parecer
consultivo; `id` como desempate. Fail-safe: na dúvida o humano fica no laço, que é o lado barato de
errar. A query ganhou `OrderById`, o que também estabiliza o `DirigirGate` (que segue com
`findFirst`; lá a consequência é sob qual braço a issue nasce, não se um humano está no laço).

**Alcance honesto:** pela porta de serviço o estado de 2 braços é hoje **INALCANÇÁVEL** —
`Agent.teamId` é único, `setGestor` exige que o gestor seja MEMBRO do time, e `assignAgent` revoga
o posto no time que o agente deixa. A cerca é defesa em profundidade (e o teste monta o estado
direto no banco); o que ela não pode é ser descrita como se já estivesse barrando um caminho vivo.

### 6. 🟠 A cerca de auto-review mirava no proxy errado

Usava o `assignee` **ATUAL**, não quem PRODUZIU o artefato sob revisão (`Review.runId` → o agente
do run). Coincidem hoje, mas por invariante que **nenhum UPDATE guardado afirma**: um endpoint
futuro de reatribuição converteria a cerca em **lavanderia** (entregar, reatribuir a um
subordinado, aprovar a própria entrega). Medido: com o assignee trocado, a aprovação da própria
entrega **passava**.

A cerca passou a mirar em `Review.runId`; a do assignee **fica de pé ao lado**, como cinto. A
subárvore também passou a ser medida pelo time de quem entregou. E esta era **a única das 5 cercas
sem teste de chamador direto ao service** — agora tem.
