# Handoff de ENTRADA — Grilling "Teams" (roles+presets · arquiteto obrigatório · CEO orquestrador) → rodar `/grill-me`

> Documento de ENTRADA de uma sessão de grilling (formato igual a `handoff-grilling-motores.md` /
> `handoff-grilling-approval.md`). A sessão deve rodar a skill **`/grill-me`** (grelhar as decisões abertas
> até fechar) e produzir um `handoff-grilling-teams-fechado.md` (formato 7c: A decisões/porquê · B diffs
> ADR/CONTEXT · C mudanças ORDENADAS · D limites v1). **Zero código nesta frente — só decisões.**
> Contexto macro: memória `agente-orquestrator-stack.md`, `docs/ARQUITETURA.md`, ADRs (esp. 0002/0003/0004/
> 0006/0007/0008/0011/0012/0014/0016). Escrito na sessão da passada de consistência (board v1 fechado);
> **rodar o grilling em sessão NOVA** por causa da regra de ouro ADR-0015 (esta já está profunda).

## 0. Por que esta frente existe (o pedido do usuário)

Quatro pedidos, dados juntos após ver o board v1 rodando (print do hire dialog com o dropdown Motor):

1. **`role` deve ser enum** (hoje é `String` livre no `Agent`) — e **precisamos de uma tela para adicionar
   essas roles, junto com um "pre-settings" para essas roles**.
2. **Invariante de composição de time:** *"obrigatoriedade de entre 1 agente executor e o resultado final
   sempre vai ter um agente 'arquiteto' que vai ter skills de arquitetura de solução pros problemas que ele
   existir pra resolver — se eu tenho 1 time de Dev, o arquiteto tem skills pra planejar código e arquitetura
   de projeto; se for Marketing, um arquiteto com skills de planejamento de marketing, e por aí vai."*
3. **Agente orquestrador mestre "CEO"** (à la Paperclip, *"podemos até usar o mesmo nome"*) que **consegue
   fazer uso das funções existentes** (ex.: adicionar um novo agente) e **orquestrar esses agentes**.
4. (Bug já corrigido nesta sessão, fora do grilling) o dropdown Motor ficava ocultado atrás do dialog e
   transbordava sobre o roster → `ui/dropdown-menu.tsx` `z-[55]` + `max-height`/scroll + `collisionPadding`.

**Enquadramento central:** os pedidos 1–3 não são 3 features soltas — são **UM design: "Teams"**, em 3
camadas que dependem uma da outra (roles+presets → composição/arquiteto → orquestração/CEO). É frente nova,
toca **backend + modelo de domínio + execução + UI**, e o #3 mira a **fronteira mais deferida do produto**
(o "agente-como-tool"). Por isso: grilling antes de qualquer código.

## 1. Fatos ATERRADOS (mapeamento com evidência — NÃO re-derivar)

- **`role` é `String` livre, não-nula.** `Agent.java` (`org.agent`): `name`/`role` String, `reportsToId UUID`
  **nullable JÁ EXISTE mas está SEM USO**, `adapterType`/`runtimeCommand`/`executionTarget`, `model`/`effort`
  (F.7). Hire manda `role` de um `<input>` de texto livre (`hire-agent-dialog.tsx`, placeholder "architect,
  executor, reviewer"). **Não há tabela de roles, nem catálogo, nem template.**
- **`reports_to_id` (uuid → agent, nullable) é o ponteiro de hierarquia que já existe e nunca foi ligado.**
  A memória o cita como candidato ao **agente-revisor** (adiado) e agora é o candidato natural pro eixo
  CEO→arquiteto→executores. Decisão aberta: hierarquia é `reports_to` (árvore livre) ou um conceito "Team"
  explícito?
- **Já existe o padrão "catálogo curado no banco servido por endpoint":** `engine_catalog` (V13, global,
  `GET /api/companies/{id}/engines` board-only, F.7-E) — o molde exato pra um catálogo de **roles**. E o
  **skills-in-DB 2-camadas** (catálogo global + biblioteca per-company; `agent_skill_selection` = SkillSet
  pinado por-agente; `issue_skill_attachment` = anexo por-Issue) é o molde pra **"pre-settings de skills por
  role"**. Ou seja: um `RoleTemplate` = motor (ref `engine_catalog`) + SkillSet preset (refs de skill) +
  `reports_to` default. **As primitivas do preset já existem** — falta o conceito que as empacota.
- **"Arquiteto planeja, executor executa" já foi meio-decidido no review-gate.** ADR-0012 + o grilling do
  approval: as *"duas aprovações"* = **o MESMO gate em DUAS Issues** (plano/impl), sequência **manual** no v1;
  *"uma-Issue-que-pausa-no-meio" foi MORTA* (constraint: sem canal inbound). Forma do plano = **contrato da
  skill do architect + UI (diferencial #4)**, gate **artifact-agnostic**, **sem entidade `plan`**. → O
  "arquiteto obrigatório" precisa reconciliar com isso (não reabrir a Issue-que-pausa).
- **Invariantes travados que o CEO/orquestração NÃO pode furar:** single-assignee + checkout atômico (409,
  never retry); budget hard-stop + auto-pause (cost ledger = verdade); company-scoping; approval gates;
  activity log imutável; **deny-by-default** (`ActorResolutionFilter` + `CompanyAccessGuard` + tripwire).
- **A FRONTEIRA DEFERIDA (o cerne do #3) — "agente-como-tool" está reservado e NÃO existe:** a memória e os
  ADRs deferem repetidamente um agente que invoca funções do core de dentro de um run. **Não há:** (a) canal
  **inbound** agente→core; (b) **credencial run-scoped**; (c) primitiva de **Approval** genérica (só existem
  `BudgetIncident`); (d) rota inbound nem `env` de segredo (o adapter roda com `env=Map.of()`). O esquema
  reservado (ADR-0004/0011/0012): o agente emite o pedido pelo **`StepSink` de SAÍDA** → core cria um Approval
  PENDING → operador aprova. **Conflito registrado a reconciliar:** Q4 do 7c escolheu **operador-only**; o
  grilling posterior disse **"board E agente-tool"** → CONFLITO explícito, ainda aberto.
- **v1 NÃO isola (LOCAL).** Docker/isolamento por container = ADR-0016 (fatia futura F.8). Um CEO que spawna
  agentes **amplifica** a questão de isolamento e a de ToS (self-hosted da própria assinatura = ok; rotear
  assinatura de terceiros = proibido — ADR-0016). Agentes de motor Claude/GPT **não RODAM sem login de CLI
  real** no host (limite v1 aceito).
- **Diferenciais do produto que essa frente materializa:** #1 skill sets por-agente (preset de role usa isso),
  #4 visibilidade de plano/step (o plano do arquiteto), **#6 times/templates prontos** (roles+presets = o
  átomo de um team template). O CEO é a cara da **promessa de orquestração** do produto inteiro.

## 2. O enquadramento: um design "Teams" em 3 camadas

- **Camada A — Roles como catálogo gerenciável + presets.** `role` não é um enum Java hardcoded (isso brigaria
  com *"tela pra ADICIONAR roles"*); é um **catálogo** (tabela), extensível pela UI, tipo `engine_catalog`.
  Cada role carrega um **preset**: motor default + SkillSet default (+ `reports_to` default?). Contratar com
  uma role pré-preenche esses campos. **Construível já, sem tocar no frontier.**
- **Camada B — Composição de time + arquiteto obrigatório + domínio.** Regra: um time válido tem **≥1 executor
  E sempre 1 arquiteto**, cujo preset de skills é de **arquitetura do domínio** (Dev↔Marketing↔…). Precisa de
  um conceito de **"domínio/niche"** (o que decide as skills do arquiteto) e de **"time"** (hoje company = a
  fronteira; time é a company inteira ou um sub-agrupamento?). Reconcilia com as "duas aprovações" (arquiteto
  gera plano-Issue, executor gera impl-Issue).
- **Camada C — CEO orquestrador (o frontier deferido).** Um agente que **cria agentes / acorda agentes /
  cria Issues / anexa skills** — de dentro do seu run. Exige as 4 peças ausentes (canal inbound, auth
  run-scoped, Approval genérico, interface de tools) + reconciliar o conflito operador-only×agente-tool +
  cruzar com isolamento (ADR-0016). **É o grosso; depende de A e B fechadas.**

## 3. Decisões ABERTAS a fechar no grilling (as perguntas, por camada)

### A. Roles + presets
1. **Role = catálogo gerenciável (tabela, tipo `engine_catalog`) e NÃO enum Java** — confirmar. É **global**
   (como skills/engines) ou **per-company** (como a biblioteca de skills)? Roles "de sistema" (architect,
   executor, reviewer, ceo) são **seed imutável** + o operador **adiciona** as dele?
2. **O que o preset carrega?** motor (ref `engine_catalog`) + SkillSet (conjunto de skills a auto-anexar) +
   `reports_to` default + `risk_level` default da Issue? Aplica-se **no momento do hire** (copia/pré-preenche,
   editável) ou é **vínculo vivo** (mudou a role → muda o agente)? (Espelha a decisão install-copia-a-base do
   skills — provavelmente "copia no hire, editável".)
3. **Como a role se liga às skills** dado o modelo 2-camadas? O preset referencia o **catálogo global** de
   skills e instala-on-hire na biblioteca da company (como o install-on-attach do 7c)? Skill que a role pede
   e não existe → 404/Approval?
4. **Tela de gestão de roles:** superfície nova no board (6ª superfície) ou aba dentro de Agents? Reconvocar
   as 4 skills de design (é UI nova, a P.1 é silenciosa sobre isso — como foi o dropdown Motor).

### B. Arquiteto obrigatório + domínio + time
5. **O que é um "time"?** A company inteira (v1 solo) ou um sub-agrupamento (`team` explícito) dentro da
   company? O invariante "≥1 executor + 1 arquiteto" é por-company ou por-team?
6. **Como o "domínio" (Dev/Marketing/…) é modelado** e o que ele decide? É um campo do time/company que
   seleciona **qual arquiteto/quais skills**? É o mesmo eixo que os **team templates** (diferencial #6)?
7. **Enforcement:** o arquiteto obrigatório é **bloqueio duro** (não deixa criar time/rodar sem ele) ou
   **aviso**? Enforçado **quando** — ao compor o time, ao dispatch, ao criar a Issue? Cruza com o dispatch/
   heartbeat (single-assignee) — o arquiteto roda ANTES (plano) e trava a impl até aprovar (as "duas
   aprovações" do ADR-0012), ou é convenção?
8. **Skills de arquitetura por domínio** existem no catálogo hoje? (O KnowAll busca o catálogo global.) O
   grilling precisa decidir se o v1 **assume** que essas skills já foram importadas, ou se cria um seed.

### C. CEO orquestrador (o frontier deferido — a maior parte)
9. **Reconciliar o conflito travado:** o CEO age **operador-only** (o operador é quem executa os pedidos que
   o CEO propõe pelo StepSink de saída — Rota A do 7c) OU o CEO é **agente-tool de verdade** (canal inbound,
   invoca o core direto)? O #3 do usuário ("consegue fazer uso das funções existentes") sugere agente-tool —
   mas isso é justamente o que estava adiado. **Esta é a pergunta-pivô.**
10. **Se agente-tool:** qual o **canal inbound** (REST autenticado por credencial run-scoped? MCP tools que
    o adapter Claude expõe? um protocolo de function-calling no StepTrace)? Qual o **escopo de authz** — o
    CEO pode criar agente e acordar, mas **pode gastar budget / spawnar Issues sem gate**? Quais ações exigem
    **Approval** (a primitiva genérica que ainda não existe — construir mínima aqui)?
11. **Quais "funções existentes" o CEO alcança** e como cada uma respeita os invariantes: `hireAgent`
    (deny-by-default: com que ator?), `wakeup`/dispatch (single-assignee/checkout atômico), `createIssue` +
    `risk_level`, `attach skill` (install-on-attach), `review`/decisão (o CEO pode aprovar? ou só o board?).
12. **Isolamento (cruzar ADR-0016):** um CEO que spawna agentes de motores heterogêneos amplifica o furo do
    diferencial #3 e a linha de ToS (self-hosted ok / rotear terceiros proibido). É **LOCAL-only no v1** (sem
    isolamento, herda o host) e o isolamento real vem com F.8? Como o CEO NÃO vaza credencial de plano entre
    agentes?
13. **Loop/segurança da orquestração:** o que impede o CEO de spawnar agentes infinitamente / queimar budget?
    (O budget hard-stop cobre custo; e limite de profundidade/quantidade? aprovação humana pra criar agente?)
14. **Nome & modelo:** reusar "CEO" do Paperclip (ver `analisys-paperclip/`); o CEO é um `Agent` com uma role
    especial (Camada A) + um adapter próprio (tools) + no topo do `reports_to`? Ou um conceito à parte?

## 4. Conflitos / invariantes a NÃO reabrir (o grilling reconcilia, não quebra)

- **"Uma-Issue-que-pausa-no-meio" está MORTA** (sem canal inbound) — o arquiteto-planeja→executor-implementa
  usa **duas Issues** + as duas aprovações (ADR-0012), não uma Issue que espera.
- **Conflito operador-only × agente-tool** (Q4 7c vs grilling) — decidir na pergunta 9, é o pivô do #3.
- **Approval genérico não existe** (só `BudgetIncident`) — se o CEO for agente-tool, nasce aqui a primitiva
  mínima (não generalizar o BudgetIncident; ADR-0009/0012 já opinaram).
- **Deny-by-default / company-scoping / single-assignee / budget hard-stop / activity log imutável** —
  qualquer ação do CEO passa por eles (o CEO não é super-usuário que fura invariante).
- **v1 não isola (ADR-0016)** — o CEO não muda isso; se a orquestração exige isolamento real, isso puxa F.8.
- **`role` como enum-rígido brigaria com "tela pra adicionar roles"** — resolver como catálogo gerenciável
  com um seed de roles de sistema (pergunta 1).

## 5. O que o grilling deve PRODUZIR (entregável)

- `docs/handoff-grilling-teams-fechado.md` no **formato 7c**: **A** decisões + porquê · **B** diffs de
  ADR/CONTEXT.md prontos · **C** mudanças ORDENADAS (migrations + fatias) · **D** limites v1 aceitos.
- **ADR-0017 "Teams — roles, presets e composição"** (Camadas A+B) e provavelmente **ADR-0018
  "Orquestração / agente-como-tool"** (Camada C, reconciliando o frontier deferido) — OU um único ADR-0017
  se o grilling decidir manter junto. Emendas prováveis: ADR-0003 (session/hierarquia), ADR-0004 (contrato do
  adapter — tools do CEO), ADR-0009/0012 (Approval genérico), ADR-0016 (isolamento sob orquestração), ADR-0007
  (skills do preset). CONTEXT.md: Role/RoleTemplate, Team/Domínio, CEO/orquestração, Approval genérico.
- **Ordem de fatias sugerida** (o grilling confirma/reordena):
  - **T.1 (independente do frontier — construível já):** `role_catalog`/`role_template` + preset (motor+skills)
    + hire-com-role (backend-first, TDD) + **tela de gestão de roles** (design). Não toca agente-como-tool.
  - **T.2:** composição de time + domínio + invariante do arquiteto obrigatório (enforcement decidido em §3.7).
  - **T.3 (o grande):** CEO orquestrador — canal inbound + auth run-scoped + Approval mínimo + tools + reconciliar
    operador-only×agente-tool (§3.9). Depende de T.1/T.2 + cruza ADR-0016.

## 6. O caminho barato, se o usuário quiser momentum antes do CEO

A **Camada A (T.1: roles gerenciáveis + presets + tela)** é **construível já** sem tocar no frontier deferido —
é só domínio novo sobre primitivas existentes (`engine_catalog` + `agent_skill_selection` + `reports_to`).
O arquiteto-obrigatório (B) e o CEO (C) são os que exigem as decisões abertas duras. Se o grilling fechar A
primeiro, dá pra entregar a fatia de roles enquanto B/C ainda amadurecem — mas o **usuário escolheu grelhar
TUDO primeiro**, então o design fecha inteiro antes de qualquer código.
