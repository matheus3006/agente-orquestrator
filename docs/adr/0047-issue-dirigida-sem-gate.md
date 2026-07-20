# ADR-0047: A issue dirigida sem gate — a primeira Approval que NASCE decidida

**Status:** Aceito (2026-07-19, execução da fatia dirigir-sem-gate) · desenho grilado com o dono em
`HANDOFF-grill-dirigir-sem-gate.md` (**D-DSG-1..8**) + especificações de segurança da conferência
adversarial da sessão de controle (kickoff `HANDOFF-dirigir-issue-sem-gate-KICKOFF.md`) · migração
**V37** (`V37__issue_dirigida_sem_gate.sql`) · constrói sobre a **ADR-0045** (árvore-organograma) e
a **ADR-0046** (`gestor_status`) · **EMENDA a ADR-0018 e a ADR-0020** (ver §Emendas) · consome a
reserva `ActivityAction.ISSUE_DIRECIONADA_SEM_GATE` (ADR-0046 §3).

## Contexto

A D-ORG-3 (grill de org-design) fechou o CICLO do gestor: nasce em ESTÁGIO (dirige em lote no
gate — o dono vê COMO ele quebra antes de confiar) e, promovido pelo ato explícito do dono
(F-ORG-B/ADR-0046), passa a dirigir issues do próprio braço SEM gate. A F-ORG-B entregou o estado
(`team.gestor_status`); faltava o MECANISMO do dirigir. O grill dirigir-sem-gate (2026-07-19)
fechou o COMO em 8 decisões (D-DSG-1..8).

**A decisão que exige esta ADR**: o rastro do caminho sem-gate NÃO é `ActivityLog` puro (era a
recomendação da sessão de controle) — é uma **`Approval` que NASCE em estado `APPROVED`**, nunca
passa por `PENDING`, com o 8º valor de `ApprovalActionType` (`DIRECT_ISSUE`) e
`decidedByActorType=AGENT`/`decidedByActorId=<a gestora>`. Verbatim do dono: *"2 - como cartao ja
decidido e uma marcacao de qual gestor liberou."* — escolha consciente CONTRA a recomendação, pelo
custo menor de reusar a fila/UI de Approvals existente (a aba APPROVED já existe; o cartão
born-decided aterrissa nela e a aba PENDING continua significando só "aguardando o dono").

A ADR-0046 §3 tinha RECUSADO esticar `Approval` pra nascer aprovada ("não há NENHUM precedente na
casa... e contradiz o próprio contrato board-decide"). O dono reverteu essa recusa NO GRILL, com o
trade-off na mesa — esta ADR é o registro formal dessa mudança consciente de invariante.

## Decisão

### 1. O gesto: bloco ```dirigir``` na saída do run, roteado pelo `gestor_status` (D-DSG-1/8)

Molde exato do F-AT1 (`pedido-skill`): `DirigirScanner` (parse determinístico, cerca em coluna 0,
descarte contado) + `DirigirGate` chamado do `AdapterRunExecutor` na saída do run do executor —
zero canal vivo novo (D-ORG-8). Campos: `alvo` (nome do agente), `titulo`, `descricao` (opcional),
`risco` (default 1; fora de 1-5 = descarte), `racional` **OBRIGATÓRIO** (D-DSG-4 — bloco sem o
porquê é descartado com aviso). O MESMO bloco serve os dois estados — o gestor nem precisa saber o
próprio status: **ESTÁGIO → `propose()` normal** (PENDING, lote/ADR-0035, dedupe de fingerprint de
graça); **PROMOVIDO → nasce-decidida** (§3). CAP por run: 5 ordens aceitas (padrão
`SkillRequestGate`).

### 2. O guard de braço: subárvore inteira, no momento do ATO (D-DSG-2)

`ChainOfCommandService.pertenceASubarvore(raiz, alvo)` — irmão público do BFS privado. Três
condições no settle: (a) o ator É gestor titular (`team.gestor_agent_id`) de ao menos um time;
(b) o time do agente-alvo pertence à subárvore de um time gerido (braço = subárvore INTEIRA,
D-ORG-3 reafirmada — "só reports diretos" foi rejeitada no grill); (c) o `gestor_status` DESSE
braço escolhe o ramo. O limite vale nos DOIS ramos. Alvo inexistente/ambíguo/fora do braço =
fail-soft com evento `DIRIGIR_DISCARDED` no StepTrace (anti-silêncio) — fora do braço é REJEITADO,
não "ambíguo". O alvo é endereçado por NOME e resolvido pra UUID no ato (mesmo racional do
`timeDoAlvo`: LLM sabe nome, alucina UUID).

**Guard só no PROPOSE, não reconferido no decide do ramo ESTÁGIO** (decisão explícita, item 8 do
kickoff): o guard limita a autoridade do AGENTE no momento do ato; depois disso quem autoriza é o
humano aprovando o cartão — gestor trocado/rebaixado entre propor e aprovar é informação visível
no cartão (requestedBy), não bloqueio. O alvo resolvido viaja por UUID no `targetDetail`; se o
agente sumiu do quadro no dispatch → 409 acionável (Approval segue PENDING).

### 3. O caminho nasce-decidida: método PRIVADO, literais fixos, um call site (D-DSG-3 + conferência)

`ApprovalService.nasceDecididaPelaGestora(...)` constrói a `Approval` e chama `decide()` **antes
do primeiro save** — nunca existe linha PENDING. `decidedByActorType=AGENT`,
`decidedByActorId=gestora`, `racional` = o do bloco, `decisionReason=null`, `racional_tipo`
derivado pelo MESMO `areaFor`/`deriveTipo` do propose (área `backlog` → LOGICO — os dois ramos
classificam idêntico). O grill do racional (ADR-0020) NÃO roda neste caminho.

**Escopo de segurança (conferência, NÃO-NEGOCIÁVEL, verbatim do kickoff):** o método que cria essa
Approval nasce-decidida é **PRIVADO** em `ApprovalService`, com `ApprovalActionType.DIRECT_ISSUE`
e `ActorType.AGENT` como **literais fixos no código, nunca parâmetros**, chamado de **um único
call site** (`dirigirIssueSemGate`, a rota nova do settle, atrás do guard do §2), com asserção
defensiva no topo (actionType ≠ DIRECT_ISSUE → `IllegalStateException`). Motivo: sem isso, a
mesma prosa do handoff seria satisfeita por um helper genérico "cria-e-já-decide" parametrizável —
um bypass PERMANENTE de `propose()`+`decide()`+guard-de-board pra QUALQUER um dos 8 tipos,
alcançável por qualquer chamador futuro. **Generalizar exige decisão nova com revisão de segurança
própria** — proibido como consequência silenciosa desta fatia.

### 4. O pouso e o selo (D-DSG-5/6)

Os DOIS ramos pousam por `IssueService.createAtribuida` (chokepoint único, assignee = o agente do
bloco — a gestora promovida JÁ é a triagem; Planejador fora do caminho; `aoNascerDoDono` não
dispara). O caminho sem-gate usa `createDirigida` (mesmo pouso + selo
`issue.dirigida_por_gestor_agent_id`, V37); a lista do board mostra "dirigida · \<gestor\>"
(resolução de nome client-side, no mapa de agentes que o `AssigneeChip` já carrega). Issues do
ramo ESTÁGIO **NÃO** ganham selo — passaram pelo gate como qualquer outra (decisão consciente; não
estender sem novo sinal do dono). Consequências de graça: review por `riskLevel`, disjuntor,
checkout — tudo igual às demais.

### 5. O rastro e o dedupe (D-DSG-3 + conferência item 7)

Na MESMA tx: Approval nasce-APPROVED → issue com selo →
`ActivityAction.ISSUE_DIRECIONADA_SEM_GATE` (reserva da ADR-0046 CONSUMIDA), ator=AGENT, detail
só-IDs `{approvalId, targetAgentId, targetTeamId, bracoTeamId, runId}` (ADR-0009). O caminho
nasce-decidida é o ÚNICO sem o backstop do `uq_approval_pending` (parcial, só PENDING) — o dedupe
do settle reexecutado (reaper) tem duas faces: `jaProtocolado` por
(companyId, runId, DIRECT_ISSUE, fingerprint) no service + índice único parcial
`uq_approval_dirigir_por_run` (V37, Postgres-only, mesmo desvio consciente do V17). O fingerprint
deste caminho é ESTRUTURAL desde o hardening pós-conferência (ver §Hardening, item 1) — não é
mais o MD5 do `targetDetail` inteiro.

### 6. "Quem decidiu" na entrega (conferência item 12)

`decidedByActorType`/`decidedByActorId` existiam na entidade/banco desde a V17 mas NUNCA eram
serializados — `ApprovalResponse` e o tipo `Approval` do front ganham os 2 campos (vale pra
QUALQUER approval). A fila renderiza "liberado por \<gestor\>" quando `decidedByActorType=AGENT`
(a marcação que o dono pediu verbatim). `FILTRO_TIPOS` da fila, 4-de-7 defasado, foi completado
sobre o enum (tipo sem filtro não podia ser decidido em lote — D2 do ADR-0035).

## Emendas

- **ADR-0018 (orquestração mediada por proposta / deny-by-default):** o contrato "toda mutação
  estrutural passa por proposta → PENDING → aprovação humana" ganha a sua PRIMEIRA exceção
  deliberada: o gestor PROMOVIDO cria issue no próprio braço com a Approval nascendo decidida por
  ELE (`decidedBy=AGENT`). A exceção é estreita por desenho: só `DIRECT_ISSUE`, só atrás do guard
  de braço, só pelo método privado do §3. A escada de autoridade ganha um degrau real entre
  operador e CEO: gestor promovido (dirige o próprio braço sem gate — e, desde a **ADR-0048**,
  também **decide o review** do próprio braço; o degrau é o MESMO, com dois poderes).
- **ADR-0020 (racional arrancado por grill):** o grill do racional NÃO roda no caminho
  nasce-decidida — ele foi desenhado pra HUMANO responder o probe; um racional gerado por LLM sem
  supervisão não tem quem grelhar. O racional continua OBRIGATÓRIO (D-DSG-4: bloco sem o porquê é
  descartado), mas a garantia muda de "arrancado no commit" pra "exigido na emissão";
  `racional_arrancado=false`, `grill_rounds=0` por construção. No ramo ESTÁGIO o grill segue
  intacto (o dono decide o cartão normalmente).
- **ADR-0046 §3:** a recusa "Approval não nasce pré-decidida" foi REVERTIDA pelo dono no grill
  dirigir-sem-gate, com o trade-off apresentado. A reserva `ISSUE_DIRECIONADA_SEM_GATE` sai de
  "sem emissor" para consumida.

## Hardening pós-conferência (2026-07-19, fatia hardening-dirigir-sem-gate)

A conferência adversarial do PR #48 achou 3 gaps no DESENHO da segurança (nenhum explorável no
estado atual; os 3 tocam o primeiro mecanismo de bypass de aprovação humana da casa). Fechados
nesta emenda — nenhuma D-DSG reaberta, nenhum schema novo:

### 1. Fingerprint do dedupe sem-gate: de conteúdo pra ESTRUTURAL

**O furo**: o fingerprint era MD5 do `targetDetail` canônico inteiro — incluindo `titulo`,
`descricao` e `racional`, prosa livre escrita pelo LLM. O zumbi do lease reaper (3 pulsos
perdidos → run volta a QUEUED com o MESMO `runId`) re-executa o run com uma chamada REAL nova ao
LLM — que reformula a prosa (LLM não é determinístico). Fingerprint diferente ⇒ nem o
`jaProtocolado` nem o índice V37 disparam ⇒ uma 2ª Approval nasce-APPROVED + uma 2ª issue, sem
NENHUM humano concordar — exatamente no **então único** caminho sem gate; a ADR-0048 abriu o
segundo, com o gestor promovido decidindo review. (O precedente da casa pro mesmo
zumbi: `PreviewArtifactCapturer`, que não confia em texto re-gerado.)

**O mecanismo escolhido** (variante do candidato A do kickoff): fingerprint =
MD5 de `{ordemDoAlvo, targetAgentId}` — zero campo de prosa. `ordemDoAlvo` é a POSIÇÃO (1-based)
da ordem entre as ACEITAS pro mesmo alvo naquele run (o `DirigirScanner` preserva a ordem do
documento; o `DirigirGate` conta por-alvo no momento do ato). Por que resolve a tensão dos dois
lados: (a) retry do MESMO run com prosa reformulada → mesma posição, mesmo alvo → dedupa; (b) 2
ordens genuínas pro mesmo alvo no mesmo run → posições 1 e 2 → não colidem. Contagem POR-ALVO (e
não global no run) pra reordenação entre alvos na re-execução não descasar. `runId` fica fora do
fingerprint porque já é coluna da chave (app e índice V37); `bracoTeamId` fica fora porque é
estado DERIVADO que pode mudar entre as duas execuções (mexida na árvore) e quebraria o dedupe
sem distinguir ordem nenhuma; `risco` fica fora porque também é output do LLM e deriva no retry.

**Por que não o candidato B** (similaridade de conteúdo pós-conflito, molde
`PreviewArtifactCapturer`): lá o conteúdo é um ARQUIVO determinístico re-lido do workspace; aqui
o conteúdo é prosa re-GERADA — medir "similaridade suficiente" entre duas redações de LLM é
heurística com falso-negativo exatamente no caso que importa. A identidade estrutural é o mais
simples que resolve os dois cenários por construção.

**Assimetria consciente entre os ramos**: o ESTÁGIO segue com fingerprint de CONTEÚDO (via
`propose()`). A chave do dedupe de PENDING não tem `runId` — identidade posicional lá colapsaria
ordens de RUNS diferentes; e no ESTÁGIO há um humano no gate pra pegar duplicata. O javadoc do
`DirigirGate` ("idêntico nos dois ramos") foi corrigido.

**Acompanhante**: com posição-como-identidade, um ECO do LLM (bloco byte-idêntico 2× no MESMO
reply) viraria 2 issues — antes o hash de conteúdo os fundia. O `DirigirGate` colapsa blocos
idênticos do mesmo reply com aviso contado em `DIRIGIR_DISCARDED` (anti-silêncio).

### 2. Defesa em profundidade em `dirigirIssueSemGate`

**O furo**: o guard de braço (§2) rodava SÓ no `DirigirGate`; `dirigirIssueSemGate` — público,
num service injetado em 5 lugares — confiava no chamador. Seguro por convenção (1 call site),
não por construção: um gate irmão futuro chamando direto pularia a subárvore inteira.

**O fecho**: `exigirGestoraPromovidaComAlvoNoBraco` roda DENTRO do service, antes de qualquer
efeito (inclusive antes do dedupe — recusar não vaza linha existente): braço existe na company e
tem a gestora como titular; braço PROMOVIDO; alvo existe na company e o time dele pertence à
subárvore do braço (reuso de `ChainOfCommandService.pertenceASubarvore` — uma régua, duas
cercas). Violação = `IllegalStateException` fail-closed, mesmo cinto do assert do §3.

### 3. `requestedByAgentId` visível no card PENDING

**O furo**: o §2 justifica "guard só no propose" dizendo que gestor trocado/rebaixado é
"informação visível no cartão (`requestedByAgentId`)" — mas a fila nunca renderizava o campo pra
cards PENDING. O racional prometia uma visibilidade que a UI não dava.

**O fecho**: o card PENDING mostra "proposto por \<agente\>" (resolver `nomeDoAgente` já
existente, mesmo mapa do `AssigneeChip`) — pra TODOS os tipos de ação, não só `DIRECT_ISSUE`
(quem propôs é informação útil em qualquer card; o custo é zero, o mapa já está carregado).

## Consequências

- A aba APPROVED da fila passa a conter cartões que o dono NUNCA viu como PENDING — a leitura
  correta é "extrato de ordens do braço", não "backlog do dono". PENDING continua significando só
  "aguardando o dono".
- O ramo ESTÁGIO audita o propose como `CEO_PROPOSED` genérico (reuso consciente — o cartão
  carrega `requestedByAgentId`; ação dedicada tipo `SKILL_INSTALL_REQUESTED` ficou de fora do
  escopo por decisão de fatia estreita, D-DSG-7).
- Rebaixar/reverter uma Approval nascida APPROVED: não pedido por nenhuma D-DSG — fora de escopo.
- Selo nas issues do ramo estágio: decisão de UI futura, se o dono sentir falta (registro do
  grill, "ficou aberto").
