# Handoff — Grilling pré-Fatia 7c (install-on-attach + Approval + agente-como-tool)

> Fonte-de-verdade para uma sessão nova de **grilling** (`grill-with-docs` / `/grilling`) resolver as
> DECISÕES ABERTAS da Fatia 7c. **NÃO re-derivar** — este doc aponta as fontes e enquadra o que falta decidir.
> Nenhum código de feature deve ser escrito na sessão de grilling: só decisões, no formato de saída do §7.
> Data: 2026-07-01. Idioma: PT-BR. Repo: `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/`.

---

## 0. Estado (o que já está pronto e verde)
- **62/62** testes offline (`mvn -o -f backend/pom.xml test`, H2-PostgreSQL). Migrations **V1→V10**
  validadas em **Postgres 16.14 real** (Flyway + Hibernate `validate` PASS, zero drift).
- Fase 0 + Fase 1 (slices 1–4) + Fase 2 (skills 2-camadas, activitylog, **Fatia 6** materialização,
  **7a** anexo por-Issue + união, **7b** KnowAll SkillSearch). Falta só a **7c**.

## 1. Onde ler ANTES (fontes; não re-derivar)
- **Memória `agente-orquestrator-stack.md`** (log vivo por-slice, autoritativo) — parágrafos das fatias 7a e 7b.
- **ADR-0003** (SkillSet × KnowAll, Revisão grilling): "anexam board **E** agente (tool)"; "install novo por
  agente → **Approval**; board instala direto"; "o tool do KnowAll devolve o **body inline** pro agente usar
  na hora". **ADR-0004** (contrato `AgentAdapter.execute`): **1 call site**, canal SÓ de saída. **ADR-0006**
  (governança runtime: budget + **gate de revisão** máquina-de-estados — a 2ª metade **não foi construída**).
  **ADR-0009** (activity log): ação delegada = **1 evento** (ator=aprovador, `detail.requested_by_agent`,
  `detail.approval_id`) → pressupõe uma entidade **Approval com id**.
- **Handoff de grilling anterior** `docs/handoff-grilling-skills-knowall.md` (formato-alvo do §7 + Ponto 2.0).

## 2. O que JÁ está travado (não reabrir)
- Install **copia** a base global p/ a biblioteca da company (`source=CATALOG`); fork não vaza (ADR-0008).
- Anexo é **por-Issue, imutável, versão pinada**; efeito efêmero (some quando a Issue fecha); registro persiste.
- Anexar o que **já está instalado** é livre; **install novo** por agente exige aprovação, board não.
- Skills v1 = **1 arquivo**; env de segredo **não injetado** (`Map.of()`); KnowAll é **global/nível-operador**.
- **7a**: attach por REST é **board-only**, corpo `{companySkillId, agentId}` (só o já-instalado). **7b**:
  `GET .../knowall/search` já é chamável por qualquer membro (inclusive agente **via REST**, se tivesse credencial+rede).

## 3. Constraints técnicos DUROS (o que o código impõe hoje — mudam o custo das opções)
1. **Não existe canal agente→core.** `AgentAdapter.execute(Context, StepSink)` só tem `StepSink.emit` (saída,
   StepTrace). O agente é um **subprocesso caixa-preta** (`ProcessAgentAdapter`/Docker); o core lê o stdout.
   Não há RPC do agente-em-execução de volta pro core.
2. **Sem credencial dentro do container.** `AdapterRunExecutor` monta o `Context` com **`env = Map.of()`**.
   O agente não tem chave nem, hoje, rota de rede pro core (isolamento = diferencial #3).
3. **Chave é por-AGENTE, não por-run.** `ActorResolutionFilter` resolve `Authorization: Bearer <agent-api-key>`
   (SHA-256) → `Actor.agent(agentId, companyId)`. Não há chave efêmera por-run.
4. **Não existe primitiva de Approval/Review.** Só governança de budget (`BudgetIncident` OPEN→RESOLVED como
   override). O "gate de revisão" do ADR-0006 nunca foi construído.

→ **Consequência:** "agente-como-tool" NÃO é um toggle de permissão. Exige, no mínimo: (a) credencial
   run-scoped injetada no container, (b) rota de rede container→core, (c) endpoints de attach/install aceitando
   ator AGENT (a 7a é board-only), (d) o gate de Approval p/ install novo. É uma superfície de segurança nova.

## 4. Decisão ABERTA (i) — quem instala/anexa no v1? (o PIVÔ)
**Conflito a reconciliar:** o Q4 inicial escolheu **operador-via-REST** e **adiou** o agente-como-tool; o
grilling posterior (ADR-0003) disse **"board E agente (tool)"**. Isso é um conflito de **timing/escopo do v1**.

- **Rota A — 7c operador-only (board via REST).** Board anexa uma skill do catálogo global ainda-não-instalada
  → **install-on-attach** (copia a versão corrente da base global p/ a biblioteca da company + anexa à Issue),
  **1 ação / 2 eventos** (`SKILL_INSTALLED` + `SKILL_ATTACHED`), **direto** (board não precisa de Approval).
  Agente-como-tool + Approval = **fatia futura dedicada**. → 7c fica **pequena, sem superfície nova, zero decisão restante**.
- **Rota B — 7c com agente-como-tool.** Constrói o canal callback adapter→core (credencial run-scoped + rede),
  relaxa attach/install p/ ator AGENT, e o **gate de Approval** (install novo por agente → PENDING → board
  decide). → 7c **grande**, nova superfície de segurança; abre as sub-perguntas Q2–Q6 abaixo.

## 5. Decisão ABERTA (ii) — primitiva de Approval (CONTINGENTE de (i))
Só é necessária **se (i) = Rota B** (agente dispara install). Se Rota A, **não se constrói Approval na 7c**.
Se Rota B, decidir a forma **mínima**: entidade `Approval`/`approval_request` (`id, company_id, kind,
requested_by_actor, payload jsonb, status PENDING/APPROVED/DENIED, decided_by, decided_at`), gate genérico
(serve também o futuro gate de revisão do ADR-0006?) ou específico de skill-install; **assíncrono** (o run do
agente já terminou quando o board aprova → efeito na PRÓXIMA Run da Issue) vs síncrono; reusar/generalizar o
padrão `BudgetIncident` (OPEN→resolve) ou separado. ADR-0009 já pressupõe `detail.approval_id`.

## 6. Perguntas de grilling (respondê-las FECHA a 7c → "zero decisão", como a 7a)
- **Q1 (pivô):** v1 da 7c inclui o **canal agente-como-tool**, ou é **operador-only** com agente-como-tool
  adiado p/ fatia dedicada? (Resolve o conflito Q4↔grilling.) *Se operador-only → pule Q2–Q6.*
- **Q2 (credencial):** callback usa a chave **por-agente** injetada no container, ou **chave efêmera por-run**
  (escopo company/agent/issue, expira no fim do Run)? (ADR-0003 falou "chave do run".)
- **Q3 (transporte/rede):** agente chama o **mesmo REST** (rede container→host) com a credencial do run, ou um
  canal adapter→core dedicado? Qual o modelo de rede sem furar o isolamento (diferencial #3)?
- **Q4 (o que o agente pode):** confirmar — **ler** (search + body inline) é sempre livre (sem Approval);
  **anexar já-instalado** é livre; só **install novo** passa por Approval. Attach por agente exige relaxar a 7a
  (hoje board-only) p/ aceitar ator AGENT?
- **Q5 (Approval):** entidade **genérica** (reusável no gate de revisão) ou específica de install? Modelo
  **assíncrono** (efeito na próxima Run) confirmado? Reusar o padrão `BudgetIncident` ou separado? Forma mínima?
- **Q6 (UX do efeito):** aprovado o install-on-attach pedido pelo agente, o anexo **materializa na próxima Run
  da Issue** (o run pedinte já usou o body inline; **sem** injeção no container vivo). Confirmar.
- **Q7 (mecânica comum às duas rotas):** install-on-attach recebe a **identidade GLOBAL** (`skillId`/`skillKey`
  do resultado do KnowAll com `installState=AVAILABLE`), não `companySkillId`. Endpoint novo/variante? Copia a
  `current_version` global? Tudo na **mesma transação** com os 2 eventos?

## 7. Pendência ADJACENTE (opcional grillar junto) — recovery de Issue travada por Run FAILED
Hoje um Run que falha deixa a Issue `IN_PROGRESS` **travada** e **não há destrava** (o reaper só re-enfileira
`RUNNING` com lease expirado). O "travar p/ recovery" é **intencional**, mas o mecanismo de unlock **não existe**
→ candidato a slice própria. Decisões abertas: quem destrava (board? auto após N falhas?), vira `BLOCKED`?,
limite de retentativas? Só grillar se o usuário quiser fechar isto também.

## 8. Entregável esperado da sessão de grilling
Um doc **no mesmo formato** de `handoff-grilling-skills-knowall.md`: (A) decisões fechadas por pergunta;
(B) diffs de ADR/CONTEXT a aplicar (emendar ADR-0003/0006, novo ADR de Approval se Rota B); (C) impactos de
schema/migration **ordenados** (V11…); (D) constraints/limitações do v1. Objetivo: deixar a 7c **totalmente
especificada, zero decisão aberta** — pronta p/ implementar em fatias finas com TDD + revisão adversarial.
