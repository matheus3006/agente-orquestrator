# ADR-0046: O marcador de autoridade delegada do gestor — estágio → promoção

**Status:** Aceito (2026-07-19, execução da faixa F-ORG-B) · desenho grilado com o dono no
`HANDOFF-grill-org-design.md` (**D-ORG-2**, com D-ORG-1..12 no entorno) · mini-grill de fatia (3
perguntas, 2026-07-19) · migração **V36** (`V36__team_gestor_status.sql`) · constrói sobre a
**ADR-0045** (a árvore de times É o organograma, F-ORG-A) · não emenda nenhuma ADR existente ·
F-ORG-C (parecer) e F-ORG-D (dirigir sem gate) leem o estado que esta fatia entrega.

## Contexto

A F-ORG-A entregou o ALICERCE: `team.gestor_agent_id` resolve QUEM é o gestor de um time, e a
chefia deriva dele (`ChainOfCommandService`). Mas "quem é o gestor" não resolve "com que grau de
confiança o dono trata esse gestor" — um gestor recém-designado e um gestor testado há meses são o
MESMO dado (`gestor_agent_id` preenchido), mas merecem tratamento diferente nas fatias seguintes:
F-ORG-C (o parecer do gestor nas entregas do time é consultivo ou decisório?) e F-ORG-D (o gestor
dirige issues do braço dele em lote-no-gate, ou direto sem gate?).

O grill original (D-ORG-2) já tinha fechado o CICLO. Verbatim do dono: *"Vamos de mundo 2."* —
gestor nasce em estágio, dá **parecer** nas entregas ("eu aprovaria, porque X") e o dono carimba
vendo o parecer ao lado; quando os pareceres batem, o dono aperta **promover** (ato explícito,
nunca automático) → gestor decide sozinho, com rastro total e qualquer entrega reabrível pelo
dono. O que ficou para esta fatia decidir era a FORMA: onde mora o estado, o que reseta quando o
titular do posto troca, e como o botão promover se parece — três buracos herdados e explícitos na
própria ADR-0045 (§"Ficou aberto") e no `HANDOFF-f-org-a-arvore-organograma.md` (§5).

## Decisão

### 1. `team.gestor_status`: estado no TIME, não no agente — reseta a cada troca de titular

*Mini-grill, Pergunta 2.* A confiança do dono na Ana como gestora do Growth NÃO é um selo genérico
que ela carrega para outro posto: é confiança NAQUELE julgamento, NAQUELE braço específico.
`gestor_status` (`ESTAGIO`|`PROMOVIDO`) vive como coluna inline em `team`, ao lado de
`gestor_agent_id` — e `TeamService.setGestor` reseta pra `ESTAGIO` toda vez que o AGENTE titular do
posto muda (Bruno assume o Growth depois da Ana: começa do zero, mesmo o Growth já tendo tido um
gestor promovido antes). Reatribuir o MESMO agente já titular não conta como troca — um PATCH
redundante não deveria apagar confiança conquistada. A alternativa recusada — tabela nova
`gestor_autoridade(team_id, agent_id)`, guardando histórico por par e sobrevivendo à troca — foi
julgada complexidade desnecessária para o único caso que ela resolveria de fato (a MESMA pessoa
retornar a gerenciar o MESMO time depois de um intervalo).

### 2. O botão promover é endpoint direto board-only, não ação no gate de aprovação

*Mini-grill, Pergunta 3.* `PATCH /teams/{id}/gestor/promover`, no mesmo molde do
`PATCH /teams/{id}/gestor` já existente (ADR-0045) — ato imediato, sem corpo, 200 com o time
atualizado. `ApprovalActionType` (ADR-0018) é reservado para "agente PROPÕE, board DECIDE"
(inclusive `GRADUATE_PRINCIPLE`, que nasce de proposta do CEO); promover não tem NENHUM agente
propondo — é 100% julgamento do dono sobre desempenho observado (hoje, ainda sem o parecer
estruturado da F-ORG-C; o julgamento é qualitativo, e tudo bem). Forçar esse ato pelo gate
significaria inventar uma proposta fantasma só pra caber no vocabulário errado.

### 3. O rastro do ATO de promover é `activity_log`; o vocabulário do rastro contínuo nasce reservado

*Mini-grill, Pergunta 1.* O ato em si — "o dono promoveu o gestor X do time Y, neste instante" — é
gravado uma vez, via `ActivityLogService.record(...)` com o novo
`ActivityAction.GESTOR_PROMOVIDO` (`actorType=BOARD`, `entityType=team`, `entityId=teamId`,
`detail={gestorAgentId}`). Esticar `Approval` pra nascer já aprovada foi recusado — não há
NENHUM precedente na casa de Approval nascendo pré-decidida, e contradiz o próprio contrato "board
decide" (ADR-0018/0020).

O rastro CONTÍNUO — cada issue que um gestor promovido dirigir sem passar pelo gate — é
explicitamente território da fatia que vai construir esse "dirigir" (F-ORG-D): hoje NENHUM código
em lugar nenhum do sistema permite um agente-gestor criar issue (gated ou não; os dois caminhos
que existem — CEO propõe→gate, ou o board cria direto — não têm um terceiro posto "gestor" no
meio), então literalmente não há o que rastrear ainda.

**Decisão do dono, contra a recomendação original desta fatia:** mesmo avisado de que a estrutura
ficaria sem nenhum emissor até a fatia que implementa o "dirigir" existir, o dono confirmou —
reservar já o NOME do vocabulário. `ActivityAction.ISSUE_DIRECIONADA_SEM_GATE` nasce nesta fatia
(mesmo padrão de reserva-adiantada que o ledger de controle já usa para números de migração/ADR
antes de consumi-los), mas nenhum código o grava — não há endpoint, UI, nem parsing de saída de
gestor-agente desenhado em lugar nenhum. F-ORG-D consome o nome quando desenhar o mecanismo real;
esta fatia não constrói (nem parcialmente) o bypass do `CREATE_ISSUE` em si.

## Consequências

**Ganhamos.** F-ORG-C e F-ORG-D ganham um campo pronto pra consultar (`team.temGestor() &&
team.gestorPromovido()`) sem inventar nada de novo. O botão de promover segue o MESMO gesto de
"designar gestor" — sem menu novo, sem tela nova, board-only direto no menu ⋮ do canvas.

**Pagamos.** Nenhuma migração de dado quebra contrato existente — `DEFAULT 'ESTAGIO'` cobre todo
time vivo, com ou sem gestor designado hoje. Zero regressão na trava do dispatch (continua
intocada, é do arquiteto — reprovado ao vivo na prova desta fatia, réplica da prova da F-ORG-A).

**Não fizemos.** Rebaixar `PROMOVIDO`→`ESTAGIO` — nenhuma D-ORG pede reversão; se o dono quiser
isso no futuro, é decisão nova, não assumida aqui. A mecânica do PARECER em si (F-ORG-C) e
qualquer bypass real do gate de `CREATE_ISSUE` (F-ORG-D) — esta fatia só entrega o estado que os
dois vão ler.

**Ficou aberto (herdado, não decidido aqui).** `ActivityAction.ISSUE_DIRECIONADA_SEM_GATE` está
reservado mas sem nenhum emissor — F-ORG-D decide a forma exata do `detail` (que IDs entram, se
precisa de mais de um valor de enum pra fases distintas) quando desenhar o mecanismo real de
dirigir sem gate.
