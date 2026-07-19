# ADR-0018: Orquestração — o CEO mediado por proposta

**Status:** Aceito (2026-07-04)

> Registro de decisão da **Camada C** da frente Teams. Detalhe/porquê: `docs/handoff-grilling-teams-fechado.md`
> (A.0–A.1, A.5, A.8). Roles/times e a cadeia de comando (A.2) = ADR-0017; memória/decisão = ADR-0019/0020.

## Contexto
O pedido #3: um **agente orquestrador mestre "CEO"** (à la Paperclip) que usa as funções existentes (add agent…)
e **orquestra** os agentes. A pergunta-pivô do grilling ("CEO operador-only × agente-tool") **era um falso
binário**. O Paperclip é **Rota B** (agente-tool: MCP expõe a API como tools + run-JWT + `canCreateAgents`) com
gates humanos por cima. O nosso produto escolheu **deny-by-default como "a coisa pra bater o Paperclip"** — logo,
o CEO **não pode ser super-usuário**. Não existe hoje canal inbound agente→core, credencial run-scoped, nem
primitiva de Approval genérica (só `BudgetIncident`).

## Decisão

### O pivô dissolvido: intenção-agente × execução-mediada
**O CEO orquestra por INTENÇÃO, mediado na EXECUÇÃO.** Ele dirige (grelha, descobre, propõe), mas **toda mutação
estrutural passa por proposta pelo canal de SAÍDA (`StepSink`) → `approval` PENDING → aprovação humana**. Sem
canal inbound solto nem credencial run-scoped no v1 (o "agente-como-tool caro" fica adiado, ADR-0016).

### Governança Board↔CEO
- **O dono aprova o QUÊ; o CEO decide o COMO.** Marcos de aprovação: **direção** (business init), **roadmap**,
  **entregável por feature**. Entre marcos, o CEO tem autonomia.
- **Tetos que escalam** (o CEO te chama): (a) gasto por token — **só no modo API paga** (no v1/assinatura o custo
  é 0, sem gate de gasto); (b) plano com muitos riscos; (c) erro se repetindo (circuit breaker). Escalação
  **assíncrona**: para só o pedaço travado, o resto continua. **Tetos sobem em cascata**
  (executor→arquiteto→líder→CEO→dono): só alcança o dono o que ninguém abaixo pôde decidir.
- **CEO conversacional** (pausa e pergunta ao humano) × **executores selados/batch** (env vazio, sem inbound).
  Reconcilia com "a Issue-que-pausa-no-meio foi morta" (ADR-0012) — aquilo valia pros **executores**; o CEO é a
  exceção com canal de conversa **com o humano**.
- **Mãos diretas do operador (SOBERANAS):** o dono pode **contratar, alocar, atribuir skill, criar task** direto,
  sem passar pelo CEO. O `activity_log` sincroniza o CEO (ele lê o estado, não a própria memória do que fez).
  **Precedência do operador:** a ação dele vence a do CEO; ele lê como fato, não desfaz.
- **Escada de autoridade:** operador (direto/soberano) > CEO (escopado, entre marcos) > arquiteto (*pede* skill
  ao KnowAll) > executor (recebe e usa).

### O CEO delegador PURO (fluxo 4 fases)
O CEO **não produz conteúdo** (nem roadmap, nem plano) — só grelha, descobre, contrata, atribui, delega
(*"você DEVE delegar, não fazer você mesmo"*). Fases: **0 business-init** (dono×CEO grelham → dossiê) →
**1 montagem** (deep-research + KnowAll; dimensionamento enxuto; contratação **incremental** time-por-time com
aprovação = freio de quantidade) → **2 decomposição** (o PO vira o dossiê em roadmap→**features** — o PO PARA
na feature; quem decompõe feature→Issues-task é o ARQUITETO na fase 3, a FONTE das tasks — ADR-0017) →
**3 execução** (arquiteto planeja/decompõe · executor faz · QA valida). Entregável testável = contrato
Given-Quando-Então no `description` da Issue; feature = 1 Issue-plano + N Issues-task; 1ª task =
walking-skeleton; **Definition of Demoable = roda local com 1 comando** (preview real vem com F.8/container).

### O CEO conversacional, tecnicamente (~85% reuso)
- **Motor (REUSO):** o CEO é um `Agent` (`adapterType='claude-code'`, `model='opus'`, `role='ceo'` — o "4.8"
  vive só no `label` do `engine_catalog`); o `ClaudeCodeAdapter` já roda `claude -p`, parseia prosa e captura
  `session_id`. Um turno = 1 Run.
- **Estado vivo (REUSO+ext):** `LiveStateProjector` read-only destila `activity_log`+issues+runs no prompt
  (junta IDs→nomes; `detail` segue só-IDs por ADR-0009).
- **Memória / a "carta" (NET-NEW pequeno):** `company_memory` (versionada, particionada por `company_id`) →
  **materializada como `.claude/CLAUDE.md` a cada Run** (espelha o `SkillMaterializer`). A carta é **projeção
  determinística** (ADR-0019, zero LLM no caminho de escrita).
- **Agir com gate (NET-NEW):** o CEO **propõe pelo `StepSink` de SAÍDA** → cria uma **`approval` PENDING**;
  o board decide; APPROVE dispara a ação real (hireAgent/createIssue/wakeup) com ator=aprovador. Não-bloqueante
  (proposta é evento durável out-of-band).
- **Continuidade:** o `--resume` (ADR-0002) fica **desligado no v1** — a continuidade vem da carta + recorte do
  log por turno (o "handoff" que o ADR-0015 já prefere); religar é barato depois.

### A primitiva Approval (pré-hoc) NASCE aqui
`approval` genérica (id, company_id, requested_by_agent, action_type, target_detail jsonb **só-IDs**, status
PENDING→APPROVED/REJECTED, decision_reason, requested_at, decided_at; `uq_approval_pending` parcial). **Espelha a
`review`** (ADR-0012) — é o "2º consumidor real" que o ADR-0012 esperava. **NÃO generalizar o `BudgetIncident`.**
Ganha o campo **racional + `racional_tipo`** (ADR-0020).

## Consequências / Limites v1
- Sem canal inbound agente→core e sem credencial run-scoped (proposta é evento de SAÍDA; agente-como-tool caro
  adiado). Sem isolamento por container (LOCAL; ADR-0016) — o CEO com mãos + LOCAL tem como **única parede** o
  `spawn_lineage` + fan-out cap (seção C-T.4). Custo em dólar inócuo (assinatura).
- **O executor nasce SEM "mãos"** — invariante derivado do tipo Execução (enum, ADR-0017): mutação estrutural é
  do CEO-mediado/operador; o executor só pega tasks que já existem e produz output.

## Schema (fatia T.3 / seção C)
`conversation` (id, company_id, subject); `company_memory` (id, company_id, kind, body, version, created_at);
`approval` (ver acima); `agent_session += conversation_id`. Serviços: `CompanyMemoryMaterializer` (→CLAUDE.md),
`LiveStateProjector` (read-only), `CompanyMemoryConsolidator` (**projeção determinística, zero LLM**). Seam
inbound: mensagem-do-dono → turno pendente por Conversation → `WakeupService`; `command` montado por-Run.

## Relaciona
`docs/handoff-grilling-teams-fechado.md` (A.0–A.1, A.5, A.8; C-T.3/T.4 — A.2 vive no ADR-0017); ADR-0012 (Approval nasce espelhando a
review; a Issue-que-pausa vale só pra executor); ADR-0004 (`command` montado por-Run, CEO `issueId=null`);
ADR-0002 (session → Agent+Conversation, `--resume` desligado v1); ADR-0009 (`approval_id`, novos `ActivityAction`);
ADR-0016 (mãos + LOCAL → `spawn_lineage`); ADR-0019/0020 (memória/decisão/racional); ADR-0017 (o CEO cria molde).

## Forward-ref (2026-07-10 — ADR-0031, Fatia 2 do v1)
O CEO virou **3 personas duráveis** (`agent.persona`, V24): o balcão único resolve para o
**Questionador**; o **Planejador PRODUZ a estratégia de orquestração** — divergência consciente e
escopada do núcleo "o CEO não produz conteúdo" (orquestração sim, execução não; o racional vive na
ADR-0031 §10). "Delegador puro" segue escopado a ENTREGÁVEIS (hotfix G1). O canal `` ```proposta ``
e o gate T3 seguem intocados; o menu do hire ganhou a mão soberana no MOTOR (A4).

## Forward-ref (2026-07-11 — ADR-0036, caso C direto)
O contrato do descarte foi EMENDADO: *"malformado é descartado (o texto segue visível na thread
para o dono cobrar)"* provou-se insuficiente na Prova R3-A (gate vazio silencioso). O descarte
segue fail-soft (deny-by-default intacto), mas deixa de ser silencioso: WARN no log + aviso
VISÍVEL na thread (`ProposalDescarteNotice`). A cerca do ` ```proposta ` agora só vale em
**coluna 0** (simetria com o scanner de personas) e ganhou **cinto de emissor** (só Executor/CEO
legado protocolam o gate). Racional completo na ADR-0036.

## Forward-ref (2026-07-11 — ADR-0037, absorção de issues)
O enum FECHADO `ApprovalActionType` foi alargado DELIBERADAMENTE com a 4ª ação `CREATE_TEAM`
(time + arquiteto atômico, retoma da absorção pós-approve). `CREATE_ISSUE` passou a carregar o
time no alvo e a nascer com assignee = arquiteto (invariante do dono: nada que o CEO cria nasce
sem dono). A issue criada pelo dono no board dispara a ABSORÇÃO (board→conversa — o transporte
inverso da 0034); issue = conversa direcionada (ancoragem 1:1). Racional completo na ADR-0037.

## Forward-ref (2026-07-11 — ADR-0038, continuidade nativa)
A continuidade do CEO conversacional deixa de ser "só carta + recorte do log por turno": passa a ser
**carta (canal nativo) + sessão NATIVA contínua por conversa (`--resume`) + estado vivo DIETADO**. O
turno 2+ resume a sessão do turno 1 e o `composePrompt` emagrece (não re-injeta os blocos estáticos
que a sessão já tem — pagaria contexto em dobro). O loop de handoff (ADR-0030) é rebaixado a rede de
janela cheia. "1 turno = 1 Run" e o prompt-por-Run seguem; muda só o que o prompt carrega no turno
continuado. Racional completo na ADR-0038.

## Forward-ref (2026-07-12 — R10-A, propose-then-commit de molde)
O enum FECHADO `ApprovalActionType` ganhou a 5ª ação `CREATE_ROLE_TEMPLATE` (o CEO propõe um molde
GLOBAL, o dono aprova — a governança que a ADR-0017 §Moldes prometeu; gate por ALCANCE). A Approval é
o "2º consumidor" que a `review` reservava, agora também para a proposta de molde (a `review` V11 não
cabia — issue_id/run_id NOT NULL). O APPROVE dispara `createTemplate` com `proposedBy`=proponente /
`approvedBy`=aprovador (o split que o gate sempre implicou). Emenda (sem ADR nova); decisões em
`docs/handoff-grilling-propose-then-commit-molde.md` (D1–D3/D7).

## Nota de reconciliação (2026-07-14 — fecho documental; divergência MÉDIA da auditoria doc×código de 2026-07-11)

**O alvo do `HIRE_AGENT` mudou (emenda da ADR-0045, F-ORG-A).** Desde a árvore-organograma, o alvo
carrega o **TIME** — `dominio` + `foco`, o NOME do time (mesma gramática do `CREATE_ISSUE`, porque
quem escreve o bloco é um LLM e UUID ele alucina) — e o time precisa já existir na company: sem
ele, o APPROVE é 409 acionável em vez de contratar um agente órfão. O campo `reportsToId` **saiu do
contrato**: a chefia deriva da árvore, e um `reportsToId` que ainda venha no alvo é descartado com
WARN (descarte CONTADO, ADR-0036). O `CREATE_TEAM` ganhou `paiDominio`/`paiFoco` opcionais —
omitidos, o time nasce pendurado na raiz (um departamento).

**`target_detail` NÃO é "só-IDs" — carrega texto livre por construção.** O corpo desta ADR
(§A primitiva Approval) prometeu `target_detail jsonb **só-IDs**` no espírito da regra PII da
ADR-0009; o construído guarda ali o **alvo canônico da proposta**, que para `HIRE_AGENT`
inclui `name`/`role`/`racional` em prosa (o `ProposalScanner` serializa o bloco `alvo`
canônico inteiro — `ProposalScanner.java:112-115`; V17 já nascia com o campo texto). A regra
"só-IDs" da ADR-0009 vale para o **`detail` do activity_log** (e É honrada lá); o
`target_detail` da `approval` é outra coisa — é o RASCUNHO da ação proposta, que o dono
precisa LER para decidir (um hire sem nome/racional seria inaprovável). O que permanece fora
dele: segredos e PII de terceiros (nada além do conteúdo que o próprio CEO propôs). Texto
reconciliado com o código; corpo original intocado.

## Emenda (2026-07-14 — R18-B: circuit-breaker de erro repetido — o teto (c), e a cascata para fora)

Os "tetos que escalam" (§Governança Board↔CEO, `:26-29`) prometeram três freios e uma **cascata**;
**nenhum existia** (a lista H, R17-B, confirmou: `grep -ri circuit` no backend era vazio). O único
freio de loop vivo era o teto de **atos** da ADR-0016/T.4 — econômico (cobra o ato na abertura; o
loop drena o teto e para): corta **tarde, caro e sem diagnóstico**. Esta fatia entrega o teto (c) e
reconcilia os outros dois + a cascata:

- **(a) gasto por token — NÃO é promessa do v1.** A própria decisão já o dispensa (*"só no modo API
  paga; no v1/assinatura o custo é 0, sem gate de gasto"*). Nada a construir. (Correção de rota: o
  campo `BudgetPolicy.orchestrationLimitCents` que a lista H apontou como "o teto de gasto que
  existe" é o teto de **atos** da ADR-0016/T.4 — outro mecanismo, de outra ADR.)
- **(b) plano com muitos riscos → v1.1.** Falta um artefato para o agente declarar os riscos que
  identificou (o `TurnOutputSchema` não o conhece) — obra maior, e aqui *não sabemos que dá
  problema*. Fica na candidata #11 do roadmap, agora só com este teto.
- **(c) erro se repetindo → IMPLEMENTADO (esta fatia).** Circuit-breaker **DERIVADO do histórico**:
  **N falhas consecutivas do par agente×issue** (default **3**, configurável em
  `orquestrador.circuit-breaker.consecutive-failures`) **param de reenfileirar SÓ aquele par** — nos
  dois pontos que criam Run (`WakeupService.enqueue` e `HeartbeatRunStore.promoteIfNeeded`). O resto
  continua: o agente segue em outras issues, a issue segue para outros agentes ("escalação
  assíncrona: para só o pedaço travado"). Um **SUCCEEDED** zera a conta; abortos administrativos
  (CANCELLED/INTERRUPTED) não contam. **Zero estado novo** — derivado por query, não é o campo
  `attempt` (que conta reclaims do MESMO run). `RepeatedFailureBreaker` + `RepeatedFailureNotice`.
- **A cascata (executor→arquiteto→líder→CEO→dono) NÃO foi construída — escalação DIRETA ao dono.**
  Todo o resto do sistema escala direto ao dono (gate, avisos M6); manter a coerência. O aviso do
  breaker é o **padrão M6**: um turno já-ANSWERED na conversa ancorada à issue, **zero Run novo**
  (`RepeatedFailureNotice`, espelho do `ProposalDescarteNotice`).
- **O que DESTRAVA (grill R18-B):** o **wakeup manual direto** do dono (`WakeupController`, linhagem
  **raiz**) — a mão humana direta **fura o breaker** por construção (o mesmo *"o freio é humano"* do
  caso B, ADR-0034). Um `WAKEUP_AGENT` aprovado no gate (linhagem **causada**, não-raiz) **não**
  destrava no v1: seria tocar o `ApprovalService` sem ganho semântico claro (aprovar um wakeup ≠
  examinar o loop de erro daquele par).
- **Fronteira conhecida (v1):** um Run que morre com **lease expirado** nunca vira FAILED (o reaper o
  requeua RUNNING→QUEUED); um contador derivado de FAILED **não vê a morte silenciosa**. Aceitável —
  o breaker mira erro que *se repete com diagnóstico* (o `last_error`), não posse de lease perdida.

Parâmetros do grill (R18-B, verbatim do dono): limiar **3** · para **só o par agente×issue** ·
destrava **só pelo wakeup manual direto**. Racional completo em `docs/handoff-decisoes-lista-h.md`
(item 3) e no handoff da faixa.
