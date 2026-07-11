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
