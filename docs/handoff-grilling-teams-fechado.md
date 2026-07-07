# Handoff FECHADO — Grilling "Teams" (roles+presets · arquiteto obrigatório · CEO orquestrador + memória)

> **Entregável** da sessão de grilling que rodou sobre `docs/handoff-grilling-teams.md`.
> Formato **7c**: **A** decisões/porquê · **B** diffs de ADR/CONTEXT · **C** mudanças ORDENADAS · **D** limites v1.
> Incorpora o **grilling focado da memória do CEO** (rodado à parte, "segundo cérebro"), cujos artefatos já
> estão no repo: `docs/handoff-grilling-memoria-ceo-segundo-cerebro.md` + `docs/research/2026-07-04-*.md` +
> memória `ceo-memoria-metodo-segundo-cerebro.md`. **Zero código nesta frente — só decisões.**
> **Próximo:** implementar na ordem da seção C, abrindo **ADR-0017** (Teams) e **ADR-0018** (orquestração/CEO),
> mais **ADR-0019** (Decision-log) e **ADR-0020** (racional-por-grill).
> Método: 2 workflows de apoio rodados nesta sessão (deep-research de personas empresariais → o enum de tipos;
> brainstorm+research dos 4 pontos abertos → as ideias fechadas). O #1 ganhou um workflow próprio (CEO
> conversacional com memória) + um grilling dedicado à parte.

---

## A — DECISÕES + PORQUÊ

### A.0 — O pivô ressignificado (§3.9 do handoff de entrada)
A pergunta-pivô ("CEO operador-only × agente-tool") **era um falso binário** e foi dissolvida. O Paperclip é
**Rota B** (agente-tool: MCP expõe a API como tools + run-JWT + `canCreateAgents`), com gates humanos seletivos
por cima. O nosso produto escolheu **deny-by-default como "a coisa pra bater o Paperclip"** — então o CEO **não
é super-usuário**. Decisão: **CEO orquestra por INTENÇÃO, mediado na EXECUÇÃO**. Ele dirige (grelha, descobre,
propõe), mas toda mutação estrutural passa por **proposta pelo canal de SAÍDA → Approval → aprovação humana nos
marcos**. Não há canal inbound solto nem credencial run-scoped no v1 (o "agente-como-tool caro" fica adiado).

### A.1 — Governança Board↔CEO (o meio-termo)
- **Você aprova o QUÊ; o CEO decide o COMO.** Marcos de aprovação: **direção** (business init), **roadmap**,
  **entregável por feature**. Entre marcos, o CEO tem autonomia.
- **Tetos que fazem o CEO te chamar** (escalação): (a) **gasto por token** — só existe no modo **API paga**;
  no v1 (assinatura) o custo é 0, sem gate de gasto; (b) **plano com muitos riscos levantados**; (c) **erro se
  repetindo** (circuit breaker). A escalação é **assíncrona**: para só o pedaço travado, o resto continua.
- **CEO conversacional** (pausa e pergunta); **executores selados/batch** (env vazio, sem inbound). Reconcilia
  com "a Issue-que-pausa-no-meio foi morta" do ADR-0012 — aquilo valia pros executores; o CEO é a exceção com
  canal de conversa (com o humano).
- **Mãos diretas do operador (soberanas):** você pode **contratar, alocar em time, atribuir skill, criar task**
  DIRETO, sem passar pelo CEO. O `activity_log` sincroniza o CEO (ele lê o estado, não a própria memória do que
  fez). **Precedência do operador**: sua ação vence a do CEO; ele lê como fato, não desfaz.
- **Escada de autoridade:** operador (direto/soberano) > CEO (escopado, entre marcos) > arquiteto (*pede* skill
  ao KnowAll) > executor (recebe e usa).

### A.2 — Comunicação e hierarquia
- **Cadeia de comando estrita:** cada agente conversa só com **líder direto (acima) + subordinados diretos
  (abaixo)**. O board fala **só com o CEO**. Posição de cada cargo na árvore é **livre** (`reports_to`); a
  *regra* é fixa, as *posições* não.
- **Hierarquia recursiva:** Board→CEO→líderes de área (CTO/CMO…)→times. Cada líder é "o CEO do seu pedaço". Os
  **tetos sobem em cascata** (executor→arquiteto→líder→CEO→você; só te alcança o que ninguém abaixo pôde decidir).
- **Cross-team pelo líder comum:** trabalho que cruza times (ex.: integração front↔back) é coordenado pelo
  **ancestral comum** (o CTO), **sem comunicação lateral no v1** (fatia futura, com o líder no loop).
- **Chats nomeados por assunto** (Geral · Desenvolvimento · Marketing · …), **todos com o CEO** — organização
  pro humano, **não muda o interlocutor**. O CEO puxa o CTO/CMO por baixo quando precisa.

### A.3 — Roles + moldes (Camada A)
- **Tipo de função (enum FIXO) × role específica (catálogo ABERTO).** O enum é o esqueleto que segura os
  invariantes; a role é a "carne" com skills. Resolve a tensão "enum × catálogo" do pedido #1: **enum pro
  esqueleto, catálogo pra especialização.**
- **Os 6 tipos** (decisão de design deste grilling — sem base empírica citável; as 2 pesquisas em
  `docs/research/` são sobre MEMÓRIA): **Liderança · Gestão/PO · Planejamento/Arquiteto · Execução ·
  Revisão/QA · Suporte.**
  No v1, **Revisão/QA e Suporte são "reservados"** (o revisor é o board/humano — ADR-0012; o suporte é o
  KnowAll); agentes próprios desses dois = fatia futura. `reports_to` **NÃO** é fixo por cargo (decisão
  de design deste grilling — não citar como achado de pesquisa).
- **Moldes (role-templates) reutilizáveis, GLOBAIS entre as empresas do dono, VERSIONADOS.** Editar molde =
  **nova versão** (savelog imutável, molde `company_skill_version`); cada agente **pina** uma versão
  (`pinned_template_version_id`) — editar não mexe em quem já roda. Contratar **copia** o preset (snapshot);
  ajuste avulso = "override do operador", não propaga. Atualizar um agente pra vN = **escolha explícita** (re-pin);
  reversível.
- **O CEO pode CRIAR molde — a teu pedido, com tua aprovação.** Fluxo: você pede ("preciso de alguém pra
  marketing") → o CEO **roda `/deep-research`** pra descobrir os cargos + os conhecimentos que cada um precisa
  → **KnowAll casa conhecimento→skill** do catálogo global → o CEO **propõe justificado** → você aprova →
  persiste. **Princípio: alcance da ação decide o gate** — ação local (uma empresa, pós-roadmap) = autônoma;
  ação que mexe no **catálogo global** (molde pra todas) = gate.
- **Skill que falta:** se o KnowAll não acha skill pro conhecimento requerido, ele te avisa → tua mão soberana
  de **baixar/importar a skill nova** fecha o loop.

### A.4 — Times + arquiteto obrigatório (Camada B)
- **Time = entidade real** (não só `reports_to`), com **domínio** (macro: Dev/Marketing) + **foco** (livre:
  frontend/backend/SEO…). **Vários times por domínio.** O **líder de área é o dono do domínio** (o CTO lidera os
  times de Dev). "Alocar em time" pede uma caixa com identidade → entidade explícita.
- **Arquiteto obrigatório**, imposto em dois pontos: **(1) composição — trava dura no DISPATCH** (um time não
  dispara trabalho sem 1 arquiteto; trava no dispatch, não na criação, pra montar aos poucos); **(2) autoria —
  por construção** (o arquiteto é a FONTE das tasks: planeja→decompõe; o executor só pega tasks que existem).
  Sem trava explícita plano→impl no v1 (a autoria já garante a ordem; o ADR-0012 deixou dependência automática
  entre Issues como futuro).

### A.5 — O fluxo em 4 fases (o CEO é delegador PURO)
O CEO **não produz conteúdo** (nem roadmap, nem plano) — só grelha, descobre, contrata, atribui, seleciona,
delega. (É o CEO do Paperclip: *"você DEVE delegar, não fazer você mesmo"*.)
```
0. BUSINESS INIT  você × CEO grelham (grill-me · grilling · brainstorm) → DOSSIÊ (arquivo de QA + pesquisas)
1. MONTAGEM       CEO roda /deep-research → cargos + conhecimentos · KnowAll → atribui skills
                  · dimensionamento ENXUTO ("mínimo necessário" — 1 agente de IA vale por N humanos)
                  · monta a empresa INCREMENTAL (time por time), pedindo tua aprovação de CADA time
                    (nunca 90 agentes de uma vez; a aprovação incremental é o FREIO PRINCIPAL de quantidade)
2. DECOMPOSIÇÃO   o PO selecionado vira o dossiê em roadmap → features → tasks
3. EXECUÇÃO       arquiteto planeja · executor faz · QA valida
```

### A.6 — Os 4 pontos do brainstorm+research (ideias fechadas)
- **#1 Interface + memória do CEO** — conversa **docada na Central de Missão** (cards vivos dos times à esquerda
  + **filtro de times** [chips liga/desliga + grupos colapsáveis]; conversa à direita; **chats nomeados por
  assunto**; a caixa de texto existe **só na coluna do CEO**; cards read-only, transcript a 1 clique). Marcos de
  aprovação = **a fila de Review**, projetada como **cards ancorados na thread** (corpo = report .md). Esboço
  visual iterado e aprovado (Artifact `ceo-conversa-mockup`). **A natureza do CEO conversacional + a memória =
  ver A.7.**
- **#2 Fluxo** — resolvido pelo reenquadramento em A.5 (o CEO nunca decompõe; delega). O "entregável testável"
  vira **contrato Given-Quando-Então** no `description` da Issue (zero entidade nova); feature = **1 Issue-plano
  + N Issues-task**, **1ª task = walking-skeleton** (atravessa o sistema); **"Definition of Demoable"** = roda
  local com 1 comando (preview real vem com a F.8/container).
- **#3 Segurança** — **parar quando termina o passo** (drena até o próximo report — no v1 o custo é assinatura,
  estado limpo importa mais) **+ cobrar o custo de cada ato na ABERTURA** (previne o enxame). Contabilizar
  orquestração no **mesmo ledger de budget** (V5); **`spawn_lineage`** (acordar um ancestral = recusado no
  checkout, mata loop; + cap de profundidade); **fan-out limitado por arquiteto**; o executor nasce **sem "mãos"
  por ser do tipo Execução** (regra derivada do enum, não flag). A **contratação incremental (A.5)** é o freio
  primário; estes limites são a rede de segurança.
- **#4 Tela de roles** — **CANVAS visual** (org-chart arrastável, React Flow) já no v1 — e ele traz o **"time
  pronto de prateleira" (diferencial #6)** como cidadão de 1ª classe. **Biblioteca de moldes GLOBAL**
  (master-detail, fora do company-switcher, clona `engine_catalog`); **arquiteto obrigatório por drop-zone
  TIPADA** (nasce válido, não lint depois); invariantes validados no **backend**, canvas só espelha; a
  **governança de molde reusa a fila de Review** (propose-then-commit, diff, drift-badge).

### A.7 — A memória do CEO ("segundo cérebro") — do grilling focado (D1–D12)
> Detalhe completo: `docs/handoff-grilling-memoria-ceo-segundo-cerebro.md`. Modelo de referência real: o sistema
> `controle/` do cadillac-burger (memória em CAMADAS com CAP DE LINHA + GRADUAÇÃO). 2 deep-researches verificadas.

- **D1** — "segundo cérebro" = **método de findability** (navegação just-in-time índice→arquivo, **SEM vetor**),
  não memória que cresce sozinha.
- **D2** — modelo = `controle/` do cadillac: camadas com **cap + graduação**; anti-inchaço = **cap + graduação
  + índice-não-conteúdo** (nada acumula ilimitado).
- **D3** — **tiers:** `activity_log` imutável → **report .md por Issue** (vira o LEDGER capado/datado) → **"ADR
  da Empresa" + Princípios/Playbook** (durável) → **carta** (sempre-on, soberana, DERIVADA) → **cards vivos**
  (derivado).
- **D4** — a **DECISÃO é a unidade atômica** endereçável (não a Issue). Issue = contêiner de execução; Decisão =
  unidade de memória.
- **D5** — o **gate de aprovação é FÁBRICA DE DECISÕES**: problemas apontados + mudanças + riscos aceitos-sem-
  mudar viram **registros datados**.
- **D6** — **racional OBRIGATÓRIO, arrancado por grill** — regime de **PRESENÇA, não qualidade** (o CEO é
  escriba-que-insiste, nunca bloqueia por qualidade; buracos ficam auditáveis).
- **D7** — **"porque quis" é rejeitado** (anti-confabulação).
- **D8** — `racional_tipo` = **lógico | intuição**. **Lógico BLOQUEIA** o commit até razão substantiva;
  **intuição COMMITA na hora** (frontend visual, vídeo, imagem, criativo).
- **D9** — a classificação é **derivada do artefato/área**, não do julgamento do CEO.
- **D10** — o probe roda em **AMBOS**; na intuição é **maiêutico/não-bloqueante** (discutir "por que me agrada"
  revela o que o agente não viu + o que o dono não notou).
- **D11** — **revisabilidade:** lógico = premissa testável (reabre quando cai); intuição = preferência (reabre
  quando o gosto muda).
- **D12** — **princípios graduam de PADRÃO** (N ocorrências) + promoção manual do dono — nunca de 1 decisão. O
  grill é **AUTO-LIQUIDANTE** (taxa de grill caindo = empresa mais esperta).
- **A "Pergunta 1" que estava no gelo FECHOU:** o **consolidador da carta é PROJEÇÃO DETERMINÍSTICA** sobre as
  Decisões-de-direção aprovadas pelo dono → **ZERO LLM no caminho de escrita da carta** (defesa contra
  memory-poisoning; a carta é derivada, nunca auto-escrita pelo agente).

### A.8 — O CEO conversacional, tecnicamente (workflow "ceo-conversacional-memoria")
Rodar o Claude conversacional voltado à empresa é **~85% reuso**. O único net-new de peso é a **memória**.
- **Motor conversacional (REUSO):** o CEO é um `Agent` (`adapterType='claude-code'`, `model='opus'` — o "4.8" vive só no `label` do `engine_catalog` —,
  `role='ceo'`); o `ClaudeCodeAdapter` já roda `claude -p`, parseia prosa e captura `session_id`.
- **Estado vivo (REUSO+ext):** um `LiveStateProjector` read-only destila `activity_log`+issues+runs no prompt
  (junta IDs→nomes na hora; `detail` segue só-IDs por ADR-0009).
- **Memória da empresa / a "carta" (NET-NEW, pequeno):** tabela `company_memory` (versionada, particionada por
  `company_id`) → **materializada como `.claude/CLAUDE.md` a cada Run** (espelha o `SkillMaterializer`). Um
  turno = 1 Run.
- **Agir com gate (NET-NEW):** o CEO **propõe pelo `StepSink` de SAÍDA** → cria uma **`approval` PENDING**
  (primitiva nova, **espelha a `review`**); o board decide; APPROVE dispara a ação real (hireAgent/createIssue/
  wakeup) com ator=aprovador. Não-bloqueante (proposta é evento durável out-of-band).
- **Continuidade (REUSO+ext, opcional no v1):** o `--resume` fiado no ADR-0002 pode ficar **desligado** no v1
  (a continuidade vem da carta + recorte do log a cada turno — o "handoff" que o ADR-0015 já prefere); religar
  é "poucas linhas" quando quiser o fio literal do CLI.

---

## B — DIFFS DE ADR / CONTEXT.md

**ADRs NOVOS:**
- **ADR-0017 "Teams — roles, presets e composição"** (Camadas A+B): tipo×role (enum 6-tipos + catálogo);
  moldes globais versionados + pin-por-agente; time como entidade (domínio+foco); arquiteto obrigatório
  (dispatch + autoria); cadeia de comando estrita + hierarquia recursiva; montagem incremental + dimensionamento
  enxuto.
- **ADR-0018 "Orquestração / CEO mediado por proposta"** (Camada C): CEO delegador-puro conversacional; o pivô
  dissolvido (intenção-agente × execução-mediada); governança Board↔CEO (marcos + tetos + assíncrono); mãos do
  operador soberanas + precedência; o CEO conversacional técnico (A.8); a primitiva **Approval** genérica
  **nasce aqui** (o "2º consumidor real" que o ADR-0012 esperava).
- **ADR-0019 "Decision-log — a decisão como átomo de memória"** (do #1): D3/D4/D5; tiers de memória; a Decisão
  endereçável separada da Issue; report-por-Issue vira o Ledger; a carta como projeção determinística.
- **ADR-0020 "Racional arrancado por grill no gate"** (do #1): D6–D12; racional obrigatório em regime de
  presença; `racional_tipo` lógico|intuição (bloqueia × commita); classificação por artefato; probe maiêutico;
  graduação de princípios por padrão.

**EMENDAS:**
- **ADR-0002** (session): generalizar `agent_session` de `(Agent+Issue)` para **`(Agent+Conversation)`**;
  `--resume` por-Conversation religável (desligado no v1 aceito).
- **ADR-0004** (adapter): o `command` do Context passa a ser **montado por-Run** (estado vivo + msg do turno)
  em vez do `runtimeCommand` estático; o CEO roda com `issueId=null` (delegador-puro, sem workspace de código).
- **ADR-0009** (activity_log): o slot **`approval_id`** ganha consumidor real; novos `ActivityAction`
  (`CEO_PROPOSED`, `APPROVAL_APPROVED`, `APPROVAL_REJECTED`); `decision_reason`/racional **na tabela, nunca no
  log**.
- **ADR-0012** (review gate): a **Approval-de-ação genérica (pré-hoc) NASCE** (espelha a `review`; NÃO
  generalizar o `BudgetIncident`). O review pós-hoc segue como está.
- **ADR-0016** (isolamento): o CEO com mãos amplifica a questão; segue **LOCAL no v1**; **`spawn_lineage`** +
  fan-out cap são a contenção enquanto o container (F.8) não existe.
- **CONTEXT.md**: adicionar — Tipo de função/Role/RoleTemplate · Time/Domínio/Foco · CEO/orquestração mediada ·
  Approval (pré-hoc) · Carta de interesses/`company_memory` · Decision-log · Chats nomeados · Dimensionamento
  enxuto · Escada de autoridade.

---

## C — MUDANÇAS ORDENADAS (migrations + fatias)

**T.1 — Roles + moldes (independente do frontier; construível já)**
- Migrations: `role_template` (id, tipo_funcao enum, role_especifica, company_id NULL=global, current_version_id)
  + `role_template_version` (append-only: motor_ref, skillset jsonb de refs, reports_to_default, change_type,
  proposed_by, approved_by, status, created_at); `agent.pinned_template_version_id`.
- Backend-first TDD: hire-com-role (copia o preset da versão pinada); KnowAll casa conhecimento→skill.
- Frontend: **tela de roles = canvas** (React Flow), biblioteca de moldes master-detail global, drop-zone
  tipada (arquiteto obrigatório imposto no backend). 4 skills de design.

**T.2 — Times + arquiteto obrigatório**
- Migrations: `team` (id, company_id, dominio, foco, arquiteto_agent_id); `agent.team_id`.
- Enforcement do arquiteto no **dispatch** (guard: time sem arquiteto não dispara). Autoria das tasks pelo
  arquiteto (já é o fluxo). Ligar o `reports_to` (existe sem uso) pra a cadeia de comando.

**T.3 — CEO conversacional + memória + Approval (o grande; cruza ADR-0016)**
- Migrations: `conversation` (id, company_id, subject); `company_memory` (id, company_id, kind, body, version,
  created_at); `approval` (id, company_id, requested_by_agent, action_type, target_detail jsonb só-IDs, status
  PENDING→APPROVED/REJECTED, decision_reason, requested_at, decided_at; `uq_approval_pending` parcial);
  `decision` (o átomo D4 — ver ADR-0019/residual 3); `agent_session` += conversation_id.
- Serviços novos (pequenos, espelham moldes existentes): `CompanyMemoryMaterializer` (→ `.claude/CLAUDE.md`),
  `LiveStateProjector` (read-only), `CompanyMemoryConsolidator` (**projeção determinística, zero LLM**).
- Seam inbound: mensagem-do-dono → turno pendente por Conversation → `WakeupService`; `command` montado por-Run.
- Gate: step de proposta (SAÍDA) → `approval` PENDING; endpoint board-only aprovar/rejeitar → dispara ação real.
- Racional-arrancado (ADR-0020): a Approval **ganha o campo racional** + `racional_tipo`; grill de presença.
- Report-por-Issue vira o **Ledger** (tier D3).

**T.4 — Contenção de segurança (rede embaixo da montagem incremental)**
- `spawn_lineage` (coluna em `heartbeat_run`/`agent_wakeup`; guard anti-ciclo + cap de profundidade no checkout,
  módulo profundo `execution.lineage`); fan-out cap por arquiteto no preflight; contabilizar
  `AGENT_SPAWN/WAKEUP/RUN_OPEN` no ledger (V5) com `orchestration_limit_cents`; "drena até o checkpoint" no
  cancelScopeWork.

---

## D — LIMITES v1 ACEITOS

- **Sem `--resume` nativo** (continuidade = carta + recorte do log por turno; religar depois é barato).
- **Sem vetor/pgvector, sem grafo/memória episódica** (a carta é pequena, cabe no contexto; navegação JIT
  índice→arquivo). D1.
- **Sem canal inbound agente→core, sem credencial run-scoped** (proposta é evento de SAÍDA; agente-como-tool
  caro adiado).
- **Sem isolamento por container** (LOCAL; ADR-0016/F.8 futuro). O CEO com mãos + LOCAL = `spawn_lineage`+fan-out
  cap são a única parede.
- **Revisão/QA e Suporte = tipos "reservados"** no enum (sem agente próprio no v1; revisor = board, suporte =
  KnowAll).
- **Comunicação lateral entre times = futura** (cross-team pelo líder comum).
- **Sequência plano→impl manual** (dependência automática entre Issues = futura).
- **Custo em dólar inócuo** (assinatura; gate de gasto só liga no modo API paga).

---

## RESIDUAIS (a decidir na implementação)

1. **Dono do LEDGER no multi-agente** — rec.: o agente da Issue escreve; o CEO gradua p/ cima.
2. **"ADR da Empresa" vs "Princípios/Playbook"** — 1 tier ou 2?
3. **Onde o Decision-log mora no schema Postgres** + a **UI do "Log de Decisões filtrável"**.
4. **Proporcionalidade/triviabilidade do grill** — rec.: reusar o escape de "triviabilidade" do `controle/`.
5. **`reports_to` livre × os invariantes de `spawn_lineage`** — a árvore proíbe só ciclo e excesso de
   profundidade, nunca QUEM reporta.

**PRÓXIMO PASSO (implementação):** destilar os 4 ADRs (0017–0020) e começar por **T.1** (roles+moldes — não toca
o frontier), com o canvas de times (4 skills de design). T.3 é o grande e cruza ADR-0016.
