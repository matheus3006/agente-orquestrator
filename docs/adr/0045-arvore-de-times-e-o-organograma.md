# ADR-0045: A árvore de times É o organograma — chefia derivada, hire com time, raiz por porta

**Status:** Aceito (2026-07-18, execução da faixa F-ORG-A — a primeira e ALICERCE das 10 fatias do
org-design) · desenho grilado com o dono em `HANDOFF-grill-org-design.md` (**D-ORG-9**, com
D-ORG-1..12 no entorno) · mini-grill de fatia D-ORG-A.1/2/3 (2026-07-18) · migração **V35**
(`V35__team_tree_organograma.sql`) · **emenda a ADR-0017** (o time ganha árvore e um segundo posto)
e a **ADR-0018** (o payload do `HIRE_AGENT` no gate) · as fatias F-ORG-B..J dependem desta.

## Contexto

O verbatim do dono que abre a decisão: *"quando eu contrato um funcionario ele DEVE pertencer a um
time … deve ser criado o departamento de marketing … sub times conforme vai crescendo, mas sempre
vinculado a algo maior. o CEO e todas as suas personas é vinculado a empresa."*

O sistema tinha as duas metades desconexas. De um lado `Team` (`org/team/Team.java`, ADR-0017) —
uma lista **plana** de times: `dominio`/`foco`/`arquitetoAgentId`/`fanOutCap`, sem nenhuma noção de
"time dentro de time". Do outro `Agent.reportsToId` (V1, ligado em T.2) — uma cadeia de comando
**editável à mão** via `PATCH /agents/{id}/reports-to`, com anti-ciclo próprio. Resultado: duas
verdades sobre "quem manda em quem", que só coincidiam se alguém mantivesse as duas na mão. E
`Agent.teamId` era opcional — na prática quase sempre nulo —, então a empresa era um saco de
agentes soltos com uma cadeia paralela por cima.

A D-ORG-9 fechou o desenho: **a empresa é uma árvore única e enraizada, e essa árvore É o
organograma**. Uma verdade, duas leituras. O que esta fatia entrega é só o DADO e os invariantes —
o COMPORTAMENTO do gestor (validar entrega, dirigir o braço, estágio→promoção) é F-ORG-B..D, a UI
da árvore é F-ORG-H, e o MCP vem por último.

## Decisão

### 1. `team.parent_team_id`: a árvore, com a EMPRESA como raiz (e sem linha de raiz sintética)

Todo time pendura em outro time ou na **raiz**, e a raiz é a própria company — não um `Team`
fantasma. `parent_team_id` nulo lê-se *"pendurado direto na empresa"*, que é exatamente o
**departamento** do verbatim. Isso torna o backfill da V35 vazio: todo time que já existia passa a
ser um departamento correto no instante em que a coluna nasce.

Invariantes (em `TeamService`): pai validado (existe + mesma company) · **sem ciclo** (ser pai de
si mesmo e descer para debaixo de um descendente são 409) · walk sempre com visited-set, para que
dado pré-corrompido não pendure a leitura.

### 2. Gestor ≠ arquiteto: dois postos, dois campos, dois propósitos (D-ORG-A.1)

`team.gestor_agent_id` nasce **ao lado** de `arquiteto_agent_id`, não no lugar dele:

| | arquiteto (ADR-0017) | gestor (esta ADR) |
|---|---|---|
| responde por | a ENTREGA (técnico) | as PESSOAS (organizacional) |
| ausência | **TRAVA o dispatch** (trava dura) | não trava nada — a chefia sobe pro pai |
| origem | designado, membro do time | designado, membro do time |

As duas alternativas foram recusadas com motivo. **Fundir** faria "designar gestor" destravar
trabalho — e um gerente de Marketing não responde tecnicamente pela entrega; pior, se a trava
passasse a exigir gestor, toda company existente pararia no dia da migração. **Pôr o gestor no
time-PAI** (organograma "puro") criaria gestor de departamento **sem time** no topo, colidindo de
frente com o "hire exige time" da §3 — a exceção da raiz deixaria de ser uma e viraria uma classe.
O gestor é, portanto, **membro do time que ele gerencia**, com o mesmo invariante-por-construção do
arquiteto: designar valida membership, e sair do time limpa o posto (nunca ponteiro stale).

Nada impede o mesmo agente acumular os dois postos num time pequeno.

### 3. `reportsTo` vira ESPELHO: a chefia deriva da árvore

`ChainOfCommandService` deixou de ser o editor da cadeia e virou o **motor da derivação**. Três
regras, sendo a segunda a única exceção:

1. agente comum → o **gestor do próprio time**;
2. **o gestor de um time → o gestor do time-PAI** (senão reportaria a si mesmo);
3. time sem gestor → **sobe** procurando (pai, avô…); sem achar até a raiz → `null` = raiz.

O espelho é reescrito em TODA mutação estrutural — mover agente, trocar gestor, mover time —
sempre sobre a **subárvore** afetada, porque um time sem gestor propaga a busca para os filhos.
"Mudou de time = mudou de chefe num ato só" é consequência, não código extra.

**Consequência dura, decidida no mini-grill (D-ORG-A.2):** o `PATCH /agents/{id}/reports-to` foi
**REMOVIDO**, e com ele o gesto de arrastar-agente-sobre-agente do canvas. Endpoint que só existe
para recusar é armadilha documentada; gesto que não significa mais nada é mentira na tela. O gesto
certo — arrastar para um TIME — é da F-ORG-H. Quem quer trocar o chefe de alguém move o agente de
time (`PATCH /agents/{id}/team`) ou troca o gestor (`PATCH /teams/{id}/gestor`).

### 4. Hire exige time; o `reportsToId` some do contrato de entrada

A porta normal — a única que a API HTTP e o gate de aprovação alcançam — **exige time**. O campo
`reportsToId` foi **removido** do `HireCommand` e do `CreateAgentRequest`: não basta ignorá-lo, ele
precisa não existir, senão a segunda verdade volta pela porta dos fundos. No gate, o alvo do
`HIRE_AGENT` passa a carregar `dominio` + `foco` — o **nome** do time, e não um UUID, pela mesma
razão que o `CREATE_ISSUE` já fazia assim (ADR-0037): quem escreve o bloco é um LLM, que sabe o
nome e alucinaria o UUID. Um `reportsToId` que ainda venha no alvo é **descartado com WARN**
(descarte CONTADO, ADR-0036), nunca honrado. `CREATE_TEAM` ganhou `paiDominio`/`paiFoco` opcionais
— omitidos, o time nasce departamento.

### 5. A exceção da raiz é uma PORTA, não um atributo (D-ORG-A.3)

CEO + as 3 personas moram na raiz, sem time. A regra podia ser escrita como `if (role == "ceo")` —
e seria uma fechadura com a chave escrita na porta, porque *role* é texto livre que o operador
digita no dialog. Em vez disso:

- `AgentHireService.hire(...)` — exige time. É a porta da API e do gate.
- `AgentHireService.hireAtRoot(...)` — contrata sem time. **Chamador único: `CeoPersonaSeeder`**, no
  nascimento da company. Não tem rota HTTP nem bloco de proposta que chegue nela.

Ser da raiz deixa de ser algo que se pede e passa a ser algo que só o sistema faz. Digitar
`role="ceo"` no dialog não fura nada. E o "caso futuro" do gestor de departamento direto na raiz
**não existe**: um departamento é um time filho da raiz, e seu gestor é membro dele — logo tem
time. A raiz de uma company nova tem exatamente **3 moradores** — as 3 personas de `role='ceo'`
que o seeder instala (Questionador/Planejador/Executor). Não há um 4º agente "CEO" avulso: o CEO
É o trio (ADR-0031), e a prova viva confirmou os 3 com `teamId=null` e `reportsTo=null`.

## Consequências

**Ganhamos.** Uma fonte de verdade para a estrutura, com o `reportsTo` legível de graça por quem já
o lia. Nada órfão por construção. O "braço do gestor" das fatias 3–5 já tem endereço: é a subárvore.
A F-ORG-H recebe o dado pronto para desenhar a árvore navegável da D-ORG-11.

**Pagamos.** Uma quebra de contrato ampla: `POST /agents` sem `teamId` agora é recusado, o
`PATCH /reports-to` sumiu, e ~60 fixtures de teste que contratavam agentes soltos precisaram
aprender a criar um time antes. O canvas perdeu o arrasto até a F-ORG-H. A conta foi paga de olhos
abertos: o dono declarou `estado_aplicacao: desenvolvimento` no meio desta fatia — *"nao me importo
com o que existe hoje … se fazer algo é bom overall façamos isso"* — o que derrubou toda camada de
compatibilidade defensiva que o desenho original previa (aceitar-e-ignorar `reportsToId`, 409 no
lugar da remoção do endpoint).

**Não fizemos.** Nenhum `DELETE` na migração e nenhuma limpeza automática de banco: a autorização
era para não ser precioso com o dado, não uma ordem de apagá-lo. O dono zerou o banco dele à mão,
com backup, durante esta faixa.

**Ficou aberto (herdado, não decidido aqui).** O MECANISMO do rastro do "dirigir sem gate" (o
gestor promovido cria issues direto) é F-ORG-B/D. E o critério de aceite herdado do kickoff — "agente
legado team-less segue funcional" — sobrevive com o racional TROCADO: não é sobre preservar legado
(que o dono declarou descartável), é que CEO+personas são team-less **por desenho**.
