# ADR-0017: Teams — roles, presets e composição

**Status:** Aceito (2026-07-04)

> Registro de decisão da frente **Teams** (Camadas A+B). O detalhe do grilling e o "porquê" longo vivem em
> `docs/handoff-grilling-teams-fechado.md` (seções A.2–A.5) — este ADR é o **endereço citável** da decisão.
> Camada C (CEO/orquestração) = ADR-0018; memória = ADR-0019/0020.

## Contexto
O pedido do dono: (1) `role` deve virar **enum** + uma **tela pra adicionar roles** + **pre-settings por role**;
(2) todo time tem **≥1 executor e SEMPRE um arquiteto** com skills de arquitetura **do domínio** (Dev→código,
Marketing→planejamento, …). Hoje `Agent.role` é `String` livre; `reports_to_id` (uuid→agent) existe **sem uso**;
o produto já tem o padrão "catálogo curado versionado" (`engine_catalog` V13, skills-in-DB 2-camadas,
`agent_skill_selection`). A tensão do #1 — *enum* (fixo) × *tela pra adicionar* (extensível) — precisa ser
resolvida sem quebrar os invariantes (company-scoping, deny-by-default, single-assignee).

## Decisão

### Tipo de função (enum FIXO) × role específica (catálogo ABERTO)
Dois eixos, não um. O **tipo de função** é um enum fixo de 6 valores (o esqueleto que segura os invariantes);
a **role específica** é catálogo aberto (a "carne" com skills). Enum pro esqueleto, catálogo pra especialização.
- **Os 6 tipos** (decisão de design do grilling A.3 — sem base empírica citável; as 2 pesquisas em
  `docs/research/` são sobre MEMÓRIA, não org-design): **Liderança · Gestão/PO · Planejamento/Arquiteto ·
  Execução · Revisão/QA · Suporte.**
- **Reservados no v1:** **Revisão/QA** (o revisor é o board/humano — ADR-0012) e **Suporte** (é o KnowAll).
  Agentes próprios desses dois tipos = fatia futura.
- `reports_to` **NÃO** é fixo por cargo (decisão de design do grilling A.3 — não citar como achado de
  pesquisa). A *regra* de cadeia de comando é fixa; as *posições* na árvore são livres.

### Moldes (role-templates): globais, versionados, pin-por-agente
- **Molde = preset reutilizável**, **GLOBAL** entre as empresas do dono, **VERSIONADO** (savelog imutável,
  espelha `company_skill_version`). Preset carrega: **motor = TRIO desacoplado** (`adapter_type`+`model`+
  `effort` por VALOR — não ref ao `engine_catalog`, que só sugere; coerente com ADR-0014) + **SkillSet**
  (refs de skill do catálogo global) + **`reports_to` default**.
- Editar molde = **nova versão**; cada agente **pina** uma versão (`pinned_template_version_id`) — editar não
  mexe em quem já roda. **Contratar copia o preset (snapshot)**; ajuste avulso = "override do operador" (não
  propaga). Atualizar um agente pra vN = **re-pin explícito**, reversível.
- **O CEO pode CRIAR molde — a pedido do dono, com aprovação** (fluxo em A.5/ADR-0018): dono pede → CEO roda
  `/deep-research` (cargos + conhecimentos) → **KnowAll casa conhecimento→skill** do catálogo global → CEO
  propõe justificado → dono aprova → persiste. **Princípio do gate: o ALCANCE da ação decide** — ação local
  (uma empresa, pós-roadmap) = autônoma; ação que mexe no **catálogo global** (molde pra todas) = gate.
- **Skill que falta:** KnowAll não acha skill pro conhecimento → avisa o dono → mão soberana de importar a skill
  (invariante de skills-in-DB) fecha o loop.

### Time = entidade + arquiteto obrigatório
- **Time é entidade real** (não só `reports_to`): **domínio** (macro: Dev/Marketing) + **foco** (livre:
  frontend/backend/SEO). **Vários times por domínio**; o **líder de área é dono do domínio** (o CTO lidera os
  times de Dev).
- **Arquiteto OBRIGATÓRIO**, imposto em dois pontos, **validado no backend** (o canvas só espelha):
  1. **Composição — trava dura no DISPATCH** (um time não dispara trabalho sem 1 arquiteto). Trava no dispatch,
     **não na criação** (pra montar aos poucos).
  2. **Autoria — por construção** (o arquiteto é a FONTE das tasks: planeja→decompõe; o executor só pega tasks
     que já existem). Sem trava explícita plano→impl no v1 — a autoria já garante a ordem.

### Cadeia de comando + montagem
- **Cadeia estrita:** cada agente conversa só com **líder direto (acima) + subordinados diretos (abaixo)**; o
  board fala **só com o CEO**. **Hierarquia recursiva** (Board→CEO→líderes→times; cada líder é "o CEO do seu
  pedaço"). **Cross-team pelo ancestral comum** (sem comunicação lateral no v1).
- **Montagem incremental** (time-por-time, com aprovação de cada time) + **dimensionamento enxuto** ("mínimo
  necessário"; 1 agente de IA vale por N humanos). A **aprovação incremental é o freio primário de quantidade**.

### Decisão-produto (UI): canvas + biblioteca de moldes
A tela de roles é um **canvas de org-chart React Flow** (T.1-FE read-only; arrastável/editável quando o
`reports_to` ligar, T.2) + **biblioteca de moldes master-detail** (diferencial #6, "time pronto de
prateleira"). O canvas **ESPELHA** — os invariantes de composição continuam no backend (drop-zone tipada
nasce válida, não lint depois). Spec de entrada: `docs/handoff-t1-fe-canvas.md`.

## Consequências / Limites v1
- Revisão/QA e Suporte são tipos "reservados" (sem agente próprio). Comunicação lateral entre times = futura.
  Sequência plano→impl = manual (dependência automática entre Issues = futura, ADR-0012).
- Invariantes de composição vivem no **backend** (canvas espelha; drop-zone tipada nasce válida, não lint depois).
- A governança de molde (propose-then-commit, diff, drift-badge) **reusa a fila de Approval** (emendado
  em 2026-07-12/R10-A — o texto original dizia "fila de Review", mas a `review` V11 exige `issue_id`/
  `run_id` NOT NULL e a proposta de molde não tem nenhum dos dois; ver Forward-ref R10-A).

## Schema (fatia T.1 / seção C do fechado)
- `role_template` (id, tipo_funcao enum, role_especifica, company_id NULL=global, current_version_id).
- `role_template_version` (append-only: version, **adapter_type, model, effort** (o trio do motor),
  skillset jsonb de refs, reports_to_default, change_type, change_description, proposed_by, approved_by,
  status, created_at).
- **Seed (V14):** 4 **moldes de sistema** globais (`architect`/`executor`/`product-owner`/`leader`, v1
  APPROVED) — a base do diferencial #6 ("time pronto de prateleira"); skill-refs ausentes do catálogo são
  puladas no hire.
- `agent.pinned_template_version_id`; (T.2) `team` (id, company_id, dominio, foco, arquiteto_agent_id) +
  `agent.team_id`; ligar o `reports_to` existente à cadeia de comando.

## Relaciona
`docs/handoff-grilling-teams-fechado.md` (A.2–A.5, B, C-T.1/T.2); ADR-0018 (CEO cria molde, gate por alcance);
ADR-0007 (skills 2-camadas — o SkillSet do preset); ADR-0014 (motor = trio `adapter_type+model+effort`; o `engine_catalog` só sugere);
ADR-0012 (governança de molde reusa a Review; QA reservado); ADR-0010 (nomes + módulos profundos).

## Forward-ref (2026-07-10 — ADR-0031, Fatia 2 do v1)
A cadeia PO→Arquiteto **de orquestração** virou personas do CEO (Planejador/Executor); o
**arquiteto-de-domínio SOBREVIVE como empregado** — por-time, fonte das Issues-task, com o
`PLANEJAMENTO_ARQUITETO` valendo como sempre. A V25 soma 3 moldes de sistema `ceo-*` ao seed
V14 e o hire ganhou um consumidor novo (o `CeoPersonaSeeder`, auto-seed na criação da company).

## Forward-ref (2026-07-11 — ADR-0037, absorção de issues)
A criação de time ganhou uma 2ª porta: o gate `CREATE_TEAM` (proposto pelo CEO na triagem de
issue órfã), que nasce COM arquiteto atomicamente — o caminho novo já obedece o invariante "todo
time nasce com arquiteto" declarado pelo dono (2026-07-11). A porta manual segue com a trava no
dispatch; o supersede da regra "trava no dispatch, não na criação" está PENDENTE DE GRILL próprio
(contrato de intenção verbatim em `handoff-grilling-absorcao-issues.md` §G.1 — inclui a possível
divisão do arquiteto em 2 personas e o multi-arquiteto por ociosidade).

## Forward-ref (2026-07-12 — R10-A, propose-then-commit de molde + drift-badge)
A governança de molde prometida (§Moldes: "O CEO pode CRIAR molde — a pedido do dono, com aprovação")
foi CONSTRUÍDA. Decisões em `docs/handoff-grilling-propose-then-commit-molde.md` (D1–D10). Resumo:
(D1/D2) o CEO PROPÕE molde pelo canal de SAÍDA → a **fila de Approval** (não a Review — ver
§Consequências), 5ª ação `CREATE_ROLE_TEMPLATE` do enum (precedente `CREATE_TEAM`, ADR-0037); o dono
segue criando/editando molde DIRETO (mão soberana, nasce APPROVED). (D3) a versão nasce só no APPROVE,
sempre APPROVED, com `proposed_by`=CEO / `approved_by`=dono DISTINTOS (o hardcode `proposedBy=approvedBy`
era stand-in); DRAFT fica vestigial (o rascunho vive na Approval PENDING). (D8) o CEO só CRIA molde novo;
editar molde existente segue mão-soberana-direta (CEO-propõe-edição = v1.1). (D4) **drift-badge derivado
NA TELA** (a versão pinada do agente ≠ a corrente do molde; `agent.pinnedTemplateId` read-join + o
`currentVersionId` já expostos). (D5/D6) **re-pin COMPLETO e SUBSTITUI**: o botão re-aplica o preset da
versão (motor + skillset) e move o pin; a versão MANDA (ajustes manuais do operador descartados) — é
mão soberana LOCAL (board-only-direto, sem gate). (D7) teto = o próprio gate (nada persiste enquanto
PENDING). **Sem migração, sem ADR nova** (emenda; ADR-0043 fica livre). Novo piso 640 back / 286 front.

## Nota de reconciliação (2026-07-14 — fecho documental; 2 divergências MÉDIA da auditoria doc×código de 2026-07-11)

As duas fichas MÉDIA que a auditoria (snapshot `2ab8eac`) apontou nesta ADR foram **SUPERADAS
pelo PR #22** (R10-A, propose-then-commit de molde, MERGED 2026-07-12 — o forward-ref acima):

1. *"CEO pode CRIAR molde com aprovação — não existe; `ApprovalActionType` nem tem a ação"* →
   **construído**: `CREATE_ROLE_TEMPLATE` é a 5ª ação do enum e o dispatch cria a versão no
   APPROVE com `proposed_by`≠`approved_by`. Evidência: `ApprovalActionType.java:22` +
   `ApprovalService.dispatchAction`.
2. *"Re-pin para vN existe (`pinForAgent`) mas sem chamador de produção"* → **construído**:
   `POST /api/companies/{id}/agents/{agentId}/repin` (board-only) chama
   `AgentHireService.repin` — re-pin COMPLETO que substitui motor+skillset e move o pin
   (D5/D6 da R10-A). Evidência: `AgentController.java:103-108`.

Nada a aplicar aqui além deste registro — o corpo já foi emendado pela própria R10-A.
