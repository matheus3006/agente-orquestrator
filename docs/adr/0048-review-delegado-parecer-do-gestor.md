# ADR-0048: O gestor no gate de Review — parecer em estágio, decisão quando promovido

**Status:** Aceito (2026-07-20, execução da fatia F-ORG-C) · desenho grilado com o dono em
`HANDOFF-grill-org-design.md` (**D-ORG-1**, **D-ORG-2**, **D-ORG-9**) · migração **V38**
(`V38__review_parecer_do_gestor.sql`) · constrói sobre a **ADR-0045** (árvore-organograma) e a
**ADR-0046** (`gestor_status`) · replica o modelo de segurança da **ADR-0047** · **EMENDA a
ADR-0012 e a ADR-0019** (ver §Emendas) · consome `DecisionOrigem.AGENTE` — o **primeiro writer
pela porta do GATE** (ver §Emendas) · **contrato dos 4 gates de fim-de-run ampliado em 2026-07-21**
(posse do run como pré-condição + bloco sem destino deixa rastro — ver §Endurecimento do executor;
**ADR-0051 devolvida**, emenda bastou) · **rede anti-silêncio ampliada para a `proposta` na fatia
a1-escalacao-morte (2026-07-21)** — 4 das 9 gramáticas, as 5 restantes nomeadas.

> ⚠️ **Corrigido no fecho do parecer (2026-07-20).** A forma anterior dizia que esta fatia consumia
> `DecisionOrigem.AGENTE` *"reservado desde a ADR-0019 **sem writer**"*. **Era falso quando
> escrito:** já existiam DOIS writers — `AbsorcaoDeIssueRouter` e `CorrecaoDeEntregaRouter`. A
> alegação verdadeira é mais estreita, e continua interessante: é o primeiro writer **pela porta do
> gate** (`GateDecisionFactory`, D5 da ADR-0019). Detalhes no §Emendas.

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

- `exigirGestorTitularComAlvoNoBraco` roda **antes de qualquer EFEITO e antes do dedupe** nas duas
  entradas públicas — o service REVALIDA sozinho, sem confiar no chamador (lição do hardening item
  2 do PR #49: guard só no gate é seguro por CONVENÇÃO, não por construção). Cinco cercas, todas
  `IllegalStateException` fail-closed.

  > ⚠️ **Precisão corrigida no fecho do parecer (2026-07-20).** Dizia "roda como **PRIMEIRA
  > linha**". Deixou de ser literalmente verdade no PR #52: roda em SEGUNDO nas duas, depois de
  > `cicloAbertoAncorado`, porque a cerca de auto-review passou a mirar em quem ENTREGOU e o
  > entregador só se resolve a partir do ciclo. O que precede é leitura pura + `throw`, efeito
  > ZERO — **a propriedade de segurança (nenhum efeito antes da autorização) continua valendo**, e
  > é ela que este item afirma. A mesma frase vivia no javadoc do `ParecerService` e na ADR-0012;
  > as **três** foram corrigidas juntas, que é o ponto inteiro de uma fatia ter o assunto todo.
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

> ⚠️ **Emendado DE NOVO na fatia fecho-do-parecer (2026-07-20) — ver §Fecho, item A1.** A última
> frase acima ("Hoje os dois têm CAS de banco") era verdadeira só na LETRA: os dois passaram a ter
> CAS, mas guardavam **coisas diferentes** — o PROMOVIDO guardava o CICLO DE VIDA
> (`where status='PENDING_REVIEW'`), o ESTÁGIO só a DUPLICATA (`where parecer is null`).
> Apresentá-los como simétricos era **a mesma forma de over-claim do "sai de graça", um nível
> abaixo** — e a assimetria era explorável: com o dono decidindo dentro da janela do guard de
> braço, o parecer consultivo entrava num ciclo já APPROVED. O UPDATE do estágio ganhou
> `and status = 'PENDING'`; só agora a simetria afirmada existe de fato.

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

  > ⚠️ **Corrigido no fecho do parecer (2026-07-20) — deslize CONCEITUAL, não factual.** A escada é
  > de **ATORES** — operador > gestor promovido > CEO (mediado) > arquiteto > executor. O gestor
  > promovido é **UM** degrau, aberto pela ADR-0047, que agora tem **DOIS PODERES**: *dirigir o
  > próprio braço sem gate* (ADR-0047) **e** *decidir o review do próprio braço* (ADR-0048).
  > **Nenhum nível novo foi criado** entre operador e CEO — falar em "segundo degrau" sugere uma
  > hierarquia mais funda do que a fatia produziu, que é justamente o tipo de inflação que um
  > contrato durável não pode carregar. A formulação correta já tinha sido escrita na ADR-0012
  > (§"Nota de precisão sobre a escada de autoridade") e nunca voltou para cá — órfã da proibição
  > cruzada entre as faixas do PR #51/#52.
- **ADR-0019/D4 (proveniência da Decisão):** esta fatia é o **primeiro writer de
  `DecisionOrigem.AGENTE` pela porta do GATE** (`GateDecisionFactory` — o chokepoint da D5 da
  ADR-0019), e é isso que a torna interessante: a proveniência `AGENTE` passa a nascer de uma
  Decisão **tomada num gate de governança**, não de um roteador de trabalho.

  > ⚠️ **Corrigido no fecho do parecer (2026-07-20) — afirmação falsa + citação MISATRIBUÍDA.** A
  > forma anterior dizia: *"`DecisionOrigem.AGENTE` existia desde a ADR-0019 marcado como 'fatia
  > futura — nenhum writer no v1'. Esta fatia é esse writer."* Dois erros:
  > 1. **Já havia dois writers** — `AbsorcaoDeIssueRouter` e `CorrecaoDeEntregaRouter`. "Esta fatia
  >    é esse writer" era falso; o correto é o recorte estreito acima (primeiro pela porta do gate).
  > 2. **A frase citada nunca esteve na ADR-0019.** Ela era javadoc de
  >    `memory/DecisionOrigem.java` — atribuir a um contrato durável um texto que vive no código faz
  >    o leitor procurar (e "corrigir") o documento errado.
  >
  > O Java (`DecisionOrigem.java`, `IssueService`) e a ADR-0019 já tinham sido corrigidos no PR #51;
  > **só esta ADR seguia com as duas versões falsas**, porque a faixa que podia consertá-la estava
  > proibida de editar `docs/adr/*.md` e a que estava autorizada não sabia delas.

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
null-ou-igual. Contradizia o fail-SOFT que o próprio gate declara.

> 🔧 **Emenda pontual 2026-07-21 (Fatia I-bis do lock órfão) — toque DECLARADO.** A frase original
> terminava com *"e **não existe reaper para ela** — impegável para sempre"*. Isso deixou de ser
> verdade com o `LockOrfaoReaper` (Fatia I, PR #59) e, para o run **sem linha** em `heartbeat_run`,
> só passou a ser verdade com o fecho do ponto cego (Fatia I-bis). O dano descrito acima segue
> real — a exceção ainda pula o settle —, mas ele agora é **uma Issue estacionada em `BLOCKED` pela
> varredura**, não um bloqueio permanente. Esta ADR foi tocada **exclusivamente** nesta frase: a
> reserva das fatias é sobre **número de ADR**, não sobre arquivo, e deixar afirmação falsa de pé
> para preservar fronteira de reserva é pior que o toque. Nada mais no documento foi alterado.

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

> ⚠️ **Emendado na fatia fecho-do-parecer (2026-07-20) — ver §Fecho, item A2.** A ressalva entre
> parênteses acima **é FALSA**, e a regra do menos-privilegiado foi aplicada ao `DirigirGate`
> também. Duas correções:
>
> 1. **`OrderById` não torna o `findFirst` fail-safe.** Ele troca "ordem arbitrária das linhas" por
>    "ordem de UUID **aleatório**": vira repetível, continua sorteio. Estabilidade ≠ fail-safe.
> 2. **A consequência no dirigir é exatamente "se um humano está no laço".** Se o braço de menor
>    UUID for o PROMOVIDO, a issue nasce **born-APPROVED e o dono nunca a vê**; se for o ESTÁGIO, o
>    cartão para PENDING no gate e o dono decide. Medido no teste
>    `gestorComDoisBracosAplicaveisNoDirigir_oMENOSPrivilegiadoVence_eOHumanoFicaNoLaco`, cujos dois
>    arranjos espelhados davam respostas OPOSTAS antes do conserto.
>
> **Procedência do erro, registrada de propósito:** esta caracterização não nasceu na fatia que
> escreveu a ADR — ela veio do kickoff da sessão de controle e foi transcrita de boa-fé. O mesmo
> texto estava em `TeamRepository`, e os dois foram corrigidos juntos. O **atenuante** continua
> valendo e não vira desculpa: o estado de 2 braços é hoje inalcançável pelas portas de serviço.

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

## Fecho do parecer (2026-07-20, fatia fecho-do-parecer)

A conferência dos PRs #51/#52 achou 3 🟠 novos, 1 🟡 e 7 🔵 — e, mais importante, que a **divisão da
rodada anterior em "faixa de código" × "faixa de documentação" produziu ÓRFÃOS**: três afirmações
falsas nesta ADR ficaram sem dono, porque quem podia consertá-las estava proibido de editar
`docs/adr/*.md` e quem estava autorizado não sabia delas. Esta fatia teve o assunto INTEIRO.
**Reserva ADR-0050 DEVOLVIDA** — tudo aqui é emenda à 0048/0049, nenhuma decisão nova.

### A1. O guard do ESTÁGIO guardava o CAMPO, não o CICLO

`ReviewRepository.tryRegistrarParecer` era `where id + company_id + parecer is null`. Faltava
`and status = 'PENDING'`. A janela entre `cicloAbertoAncorado` e o UPDATE contém
`exigirGestorTitularComAlvoNoBraco`, que faz **4 round-trips ao banco** — não é janela de
nanossegundo. Com o dono decidindo o ciclo aí dentro, `parecer is null` seguia verdadeiro e o
UPDATE **gravava parecer consultivo num ciclo já APPROVED/REJECTED**, devolvia `true`, emitia
`REVIEW_PARECER_REGISTRADO` e deixava `parecer_em > decided_at` **sem aviso nenhum**.

**Medido, não teorizado:** o teste
`donoDecideNoMeioDaJanela_parecerConsultivoNaoEntraEmCicloFechado` falha contra o código antigo com
`expected: 0 but was: 1` — o parecer entrou mesmo. E a ADR **repetia o over-claim que a própria
rodada veio corrigir** (§4, "hoje os dois ramos têm CAS de banco"): ver a emenda lá em cima.

### A2. A regra fail-safe não tinha ido ao `DirigirGate`

`DirigirGate` seguia com `.findFirst()`. Com o `OrderById` da query virou determinístico **por
comparação de UUID aleatório — repetível, mas não fail-safe**. Aplicada a mesma regra do
`ParecerGate` (`min(comparing(gestorPromovido).thenComparing(getId))` ⇒ ESTÁGIO vence). A
justificativa registrada no §Hardening item 5 era **falsa** e foi corrigida lá — ver a emenda, que
registra também a **procedência do erro** (veio do kickoff da sessão de controle, transcrito de
boa-fé), porque errar em artefato durável é diferente de errar em conversa.

### A3. `DONE` reabrível × os reapers → **FALHAR ALTO** (D-FP-1)

A ADR-0049 mudou o invariante "DONE é terminal" e não varreu quem vive dele. Decisão do dono:
o endpoint de reabrir **RECUSA com 409** se o workspace já foi coletado. Detalhes e racional na
**ADR-0049 §Emendas** (emendada por esta fatia), incluindo por que a fonte é o evento durável e não
o mundo do alvo.

### A4. O invariante que a própria classe declarava ficou literalmente falso

`ParecerService` afirmava que o guard roda "como **PRIMEIRA linha** das duas entradas públicas".
Desde o PR #52 roda em **segundo** nas duas, porque passou a precisar do ciclo para resolver o
entregador. Sem consequência de privilégio — o que precede é leitura pura + `throw`, efeito ZERO —
mas esta é a classe cujo argumento de segurança inteiro é "molde da ADR-0047 replicado elemento por
elemento", e **o elemento que uma fatia futura vai grepar deixou de descrever o código**. Reescrito
para dizer a ordem real e o porquê. A ADR-0012 carregava a MESMA frase e foi corrigida junto.

### Os 🔵, todos fechados

- **`semDerrubarOSettle` ganhou teste** (era zero `GATE_DE_SAIDA_FALHOU` em `src/test`) — o cinto
  descrito como "o que mantém a afirmação verdadeira se um gate regredir amanhã" era exatamente o
  que regredia sem ninguém ver.
- **A recuperação do cinto era desprotegida:** o `recordStep` do catch desce em
  `findById().orElseThrow()`; se levantasse, escapava e **pulava o settle — o dano exato que o
  cinto existe para evitar**. Um cinto cuja falha reproduz o dano que ele previne não é cinto.
  Fechado no `AdapterRunExecutor` e na forma irmã do `finally` do `ParecerGate`.
- **A "SUPERSEDIDA" agora chega ao leitor que a ADR-0049 §3 cita como motivo:**
  `CompanyMemoryMaterializer.feedbackDaIssue` renderizava TODAS as Decisões ancoradas sem marca (o
  filtro existia só na projeção da carta), então o `[APPROVE REVIEW] #N` desfeito por uma reabertura
  seguia na dieta do re-run dizendo que a entrega valia. Marca, não esconde — o rastro é a metade da
  D-ORG-2 que paga pela outra.
- **`V38__review_parecer_do_gestor.sql`** ainda carregava a alegação REFUTADA do "sai de graça";
  ADR e Java já tinham sido emendados, só a migração não. Comentário corrigido (o DDL, não).
- **`ISSUE_REABERTA` ganhou case** em `frontend/src/lib/feed.ts` — caía no `default` e aparecia como
  token cru para o operador.
- **Ramo morto removido** em `IssueService` (os 2 lados do ternário produziam o mesmo `Anchor`).
- **`tryReabrirAprovada` tem escopo maior que o nome** — e fica assim de propósito, agora escrito:
  ver ADR-0049 §Consequências.

## Endurecimento do executor (2026-07-21, fatia endurecimento-executor)

> **Emenda a esta própria ADR**, na seção que definiu o porteamento dos 4 gates de fim-de-run
> (§Hardening pós-conferência, item 4). **Nenhuma ADR nova:** a reserva **ADR-0051 foi DEVOLVIDA** —
> não há decisão de registro nova aqui, há a *conclusão* de uma que já estava tomada. O kickoff
> previa emenda à 0047 ou à 0050; o dono do contrato dos 4 gates é **esta** ADR (é aqui que
> `semDerrubarOSettle` e a lista dos quatro estão escritos), então a emenda mora aqui.

### O cinto do PR #53 endereçava EXCEÇÃO, não POSSE — e são coisas diferentes

O item 4 acima fechou "um gate que EXPLODE não pode pular o settle". Ficou aberto o achado do PR #49:
os 4 gates disparavam side-effect **incondicionalmente**, sem reconferir se o run ainda era dono de
alguma coisa.

**A "thread tardia", passo a passo.** O nó A roda o run R sobre a issue I. A trava — pausa de GC,
partição de rede — e o lease expira; o reaper devolve R para a fila; o nó B pega R, executa e
LIQUIDA. O `settleAfterRun` de B casa o guard `execution_lock_run_id = R` e **libera a trava**. Só
então A acorda, o `adapter.execute()` dele retorna, e A dispara os 4 gates **em nome de um run já
liquidado por outro** — criando Approval, issue dirigida e parecer REAIS, que um humano depois tem
de desfazer. O settle de A cai no vazio, porque a issue é guardada; os gates não tinham guard nenhum.

**A régua é a do banco, de propósito.** `IssueService.runAindaDetemAIssue` expõe como PERGUNTA o
mesmo predicado que o `where` de `tryBlock`/`tryComplete` usa para arbitrar a corrida. Reusar em vez
de inventar um invariante de ciclo de vida é o que impede as duas cercas de divergirem amanhã — e é
por isso que a válvula de escape do kickoff ("se exigir invariante novo, PARE") **não foi puxada**.
O ponto é ÚNICO: `AdapterRunExecutor.entregarSaidaAosGates` consulta a posse uma vez e os quatro
gates ficam atrás dela.

**Fail-CLOSED, e o lado barato de errar é este.** Posse não confirmada ⇒ os gates não rodam. Um
pedido de skill perdido o agente refaz no próximo run; uma issue dirigida ou um parecer criados por
um run fantasma ninguém desfaz sozinho. O descarte **nunca é mudo**: vira step
`GATE_DE_SAIDA_SEM_POSSE`, no mesmo espírito anti-silêncio do `GATE_DE_SAIDA_FALHOU` irmão.

**Limite conhecido, escrito para não virar a próxima alegação de completude.** A régua é a posse da
ISSUE. Se o nó B ainda estiver EXECUTANDO R — o re-checkout usa o mesmo runId, então a trava volta a
valer R — A passa. Fecha o caso em que alguém **já liquidou**, que é o que cria artefato duplicado;
não fecha a sobreposição de dois executores do mesmo runId, que exigiria posse por DONO (`nodeId`,
hoje privado do `HeartbeatDispatcher`) e é desenho de outro tamanho.

### O bloco de saída sem destino deixou de sumir calado

`pontaDoExecutor = turno == null && issueId != null`. Um run de **wakeup sem issue** não é turno de
conversa nem run de Issue: cai fora dos dois ramos, os 4 gates são pulados, e um ` ```dirigir` /
` ```parecer` / ` ```pedido-skill` emitido nele era descartado **sem erro, sem aviso, sem StepTrace**.
O gestor exercia autoridade que de fato tem, e o ato evaporava.

**Anti-silêncio, não roteamento** (ADR-0037 D1). Para ONDE mandar o bloco órfão — sob que autoridade,
ancorado em qual issue, com qual precedente (a issue órfã do board é absorvida pro arquiteto; um
`parecer` não tem análogo) — é pergunta de desenho e **fica registrada aqui como candidata a grill**,
não resolvida de passagem. O que esta fatia fecha é o pior: a perda deixou de ser muda, via step
`BLOCO_DE_SAIDA_SEM_DESTINO`.

**Escopo deliberado:** só o run sem issue E sem turno. No TURNO o canal de saída existe e funciona
(o `ProposalScanner` lê a `proposta`, o `PersonaHandoffScanner` lê o bastão de persona), então avisar
lá seria ruído — é caso distinto, não esquecimento. O detector é **grosso de propósito**: pergunta só
"existe cerca deste tipo?", em coluna 0 como os scanners, e nunca parseia o conteúdo — replicar a
gramática de dentro criaria duas versões para divergir.

### A rede anti-silêncio ganhou a `proposta` (2026-07-21, fatia a1-escalacao-morte)

A rede nasceu cobrindo **3 das 9** gramáticas cercadas da casa — as três que os 4 gates leem — e
deixou de fora o bloco de autoridade mais forte que existe: ` ```proposta`, o canal de saída do CEO
(ADR-0018/0036, `ProposalScanner` → gate T3). Ele é lido **só** no settle de um TURNO; num wakeup sem
issue não há turno nem gate, e ele seguia sumindo calado pelo mesmo caminho que os outros três
deixaram de sumir. Agora são **4 de 9**, e o par negativo (wakeup sem bloco não ganha aviso nenhum)
continua valendo.

**O aviso passou a ser por CANAL, não uma frase só.** O texto antigo terminava em "para que valham,
acorde o agente sobre uma issue" — conselho correto para `dirigir`/`parecer`/`pedido-skill`, que
**têm** um caminho que os faz valer, e mentira para a `proposta`, que nenhum run de agente lê em
caminho nenhum. Num aviso de PERDA, mandar o leitor a um caminho que também não funciona é pior que
não aconselhar nada; a `proposta` ganhou a frase dela.

**Limite conhecido, nomeado uma a uma em vez de "as outras".** Faltam as **5 do bastão de persona**:
`handoff`, `needs-grilling`, `correcao`, `absorcao`, `retrabalho` (`PersonaHandoffScanner` →
`PersonaBatonRouter`). Ficaram de fora por **juízo, não por esquecimento**: não são atos de
autoridade e sim roteamento interno entre personas do CEO, e `handoff`/`correcao`/`retrabalho` são
palavras comuns o bastante para um agente cercar como CONTEÚDO — o que faria o aviso virar
exatamente o ruído que o par negativo desta rede existe para impedir. Somá-las é uma linha de código
cada e uma decisão de quem quiser pagar esse custo; não é dívida escondida.
