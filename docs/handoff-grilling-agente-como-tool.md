# Handoff — Grill do AGENTE-COMO-TOOL (R11-A, 2026-07-12) — FECHADO

> **Produto da faixa R11-A da rodada 11 da sessão de controle.** Grill G0–G5 conduzido com o
> Matheus sobre o último item grande CONGELADO da Frente D (agente-como-tool), reconciliando o
> desenho da época da 7c (adiado deliberadamente — Rota A operador-only) com o mundo atual. Faixa
> **GRILL-ONLY** (precedente R5-A/R6-A): zero código de produto, zero commit — este arquivo nasce
> **UNTRACKED**; quem o commita é a **1ª execução derivada**. Snapshot de referência: main
> `3fdda05` (piso 640 backend / 286 front). Idioma: PT-BR.
> **A EMISSÃO das fatias derivadas é da sessão de controle (2-em-voo).**

**Método:** perguntas uma a uma, recomendação+porquê ancorada no código. No G5 (a indecisão de
fatiamento) a decisão foi guiada por análise estruturada com a skill **`human-architect-mindset`**
(fato de domínio → modos de falha → constraints → teste de lealdade → síntese) — a pedido do dono.

**Contexto verificado no código (não re-derivar):**
- `env = Map.of()` vivo — `AdapterRunExecutor.java:207` (executor sem credencial; isolamento #3).
- Teto codex `6/3` ATIVO em todo run — `CodexAdapter.java:44-45` (`agents.max_threads`/`max_depth`).
- `claude.disallowed-tools` com **default VAZIO** — `ClaudeCodeAdapter.java:53` (Task do claude roda
  **ungated por padrão**, não por falta de alavanca).
- Install-on-attach **JÁ EXISTE**: `installFromCatalog` em `SkillInstallService.java:45`, usado por
  `SkillSetService.java:106` (AVAILABLE→instala do catálogo global + anexa).
- Fila de Approval MADURA: `ProposalScanner` + `ApprovalService` em `governance/approval/`.
- Bloco de persona tem schema (`conversation/TurnOutputSchema.java`, ADR-0039); **o executor NÃO
  tem canal de bloco** hoje (os scanners são todos do lado conversa/CEO).
- Teto de delegação do CEO = **prompt** 30/10 (`ConversationTurnCoordinator.java:378`) — eixo
  distinto do teto NATIVO de subagente.

---

## §0 A razão dupla do adiamento (a que o grill teve de testar)

Da `handoff-grilling-7c-fechado.md` §A, verbatim:
1. **Razão 1** — agente-como-tool contradiz os diferenciais: **#1** (re-poluição — "o agente pediria
   N skills por via das dúvidas", ruído/approval-spam) e **#3** (isolamento — furar o container com
   rota de rede container→core + credencial run-scoped).
2. **Razão 2** — o efeito é **assíncrono de qualquer jeito** (materializa no próximo Run), logo
   "agente pede → board aprova → efeito no próximo run" é **funcionalmente equivalente** a "operador
   anexa → efeito no próximo run"; **dono-solo** = sem 2º humano p/ justificar o laço pede→aprova.

**O que mudou desde a 7c (cada item shippado):** CEO roda **LOCAL** (D10); executores **LOCAL ou
DOCKER** por config (ADR-0016); **Approval** virou fila madura (5 ações, lote ADR-0035, kickback);
**saída estruturada** validada (ADR-0039); **off-book** de subagentes (F-N5); **hooks PreToolUse**
(F-N6). A nota vinculante do motor-nativo §2: *"o desenho COMEÇA escolhendo entre subagentes
nativos × MCP — não desenhar transporte novo."*

---

## §1 Decisões D1–D6

### D1 (G0) — Significado do "agente-como-tool" no v1: **a dupla-skill (i)+(ii); (iii) é épico separado**

**Decisão:** o item congelado significa, no v1, a dupla que o roadmap nomeia — **(i)** o agente
instala/anexa uma skill pra si via gate + **(ii)** o KnowAll devolve o **corpo (body) inline** pro
agente na hora. A terceira leitura — **(iii)** expor um agente DURÁVEL como ferramenta chamável por
outro agente (a leitura `codex mcp-server`/`codex-reply`/threadId do motor-nativo) — é **capacidade
SEPARADA e maior**, fora deste item; vira **candidata v1.1 própria**.

**Racional:** o item da Frente D tem nome e definição (`roadmap-interno.md:184`: *"Agente-como-tool
(attach por agente + KnowAll body inline)"*) = (i)+(ii). A (iii) muda o modelo de orquestração (o
org se chamando) e o CEO **já** delega a subagentes nativos hoje (web+Task, teto de prompt 30/10),
só que efêmeros/genéricos. Enfiar (iii) aqui incharia a fatia e misturaria decisões com razões de
adiamento distintas. A menção a `mcp-server`/`codex-reply` na nota se lê como **dica de transporte**
pro canal de (i)/(ii), não mandato de construir (iii).

**Palavra do dono:** *"I e II, III é uma boa ideia."*

### D2 (G1) — Transporte: **(i) por bloco de saída; (ii) por MCP-stdio local; nada novo**

**Decisão (a regra é ESCOLHER, não inventar — nota §2 do motor-nativo):**
- **(i) — escrita assíncrona que precisa de gate:** o agente **NÃO chama pra dentro**. Emite um
  bloco de pedido na própria SAÍDA (stdout, que o core já lê) → o core parseia → cria Approval
  PENDING → dono aprova → efeito no próximo run. **Zero transporte novo**; é o desenho reservado na
  `7c-fechado` §D ("o agente emite o pedido pelo `StepSink` de saída que já tem"), tornado trivial
  pela **ADR-0039** (bloco validado, não parse frágil). Nada fura o container (#3 intacto).
- **(ii) — leitura síncrona (body NA HORA):** único que precisa de canal **request→response**. A
  opção nativa (sem transporte novo) é **MCP**: os dois CLIs consomem servidores MCP de fábrica. O
  produto expõe um **servidor MCP do KnowAll** (`knowall_search` + `knowall_fetch_body`). **LOCAL**
  (CEO, executor local) = MCP por **stdio** (subprocesso do CLI, identidade do produto, callback pro
  core — sem credencial no `env`, sem rota REST de entrada → contorna o #3). **DOCKER** = servidor
  MCP alcançável do container = canal mais caro → **v1.1 com o D7**.

**Rejeitados nas duas:** **subagentes nativos** (claude Task / codex `multi_agent`) — não servem pra
(i)/(ii), um subagente é agente NOVO, não busca de body (servem pra (iii)); **REST agente→core** —
é exatamente a superfície que a 7c rejeitou (credencial + rota + endpoint ator AGENT).

**Consequência que decidiu o G2:** **(ii) é o ÚNICO que abre canal de ENTRADA** — onde mora o peso
de segurança e a tensão com o #1. A **(i)** por bloco de saída é barata e limpa.

### D3 (G2, REVISADA) — In/out: **(i) + (ii)-LOCAL entram no v1; só (ii)-docker → v1.1**

**Decisão:** **(i) e (ii)-local entram no v1** (sequência: (i) primeiro — reuso puro; (ii) depois —
gateada pela sonda MCP-consume). **Só (ii)-DOCKER fica v1.1** (precisa do canal MCP-no-container = a
fatia de isolamento do **D7**). **(iii)** separada (D1).

**Nota de revisão (o dono re-abriu *"por que v1.1 e não v1?"* — e tinha razão):** a recomendação
INICIAL da faixa era (ii)→v1.1; o dono contestou e o re-teste mostrou que foi conservadora demais.
Dos três motivos do adiamento, **dois caem**:
1. **#1 (re-poluição) NÃO justifica adiar — é TETO.** A ADR-0003 já aprovou o "aumento dinâmico por-
   tarefa"; body inline é a versão síncrona disso, não violação nova. "N skills por via das dúvidas"
   se **limita com teto** (mesma lógica do 6/3 — não se adia subagente por poder ser abusado, capa-se).
2. **A superfície nova é LEVE.** MCP-stdio-local é o mecanismo NATIVO dos dois CLIs (config, não
   protocolo), **sem credencial no `env`, sem rota REST de entrada** — muito menor que o "REST
   agente→core" que a 7c rejeitou.
3. **O valor NÃO é estreito (era o erro).** Só (i): o run TRAVA e espera o dono → skill só no próximo
   run. Com (ii): o executor **pega a skill AGORA e termina a task no mesmo run** — autossuficiência
   mid-task, que é o NORTE ("org executa sozinho"). (ii) está mais perto do norte que a (i).

**O que SOBREVIVE (real, mas não força (ii)-local pra fora do v1):**
- **A sonda MCP-consume** — fato empírico não provado (R6-B provou `multi_agent`, NÃO MCP-consume).
  Read-only/descartável, **roda em paralelo já**; **gateia o kickoff** da (ii), não empurra pra v1.1.
- **Docker** — (ii)-docker precisa do D7 → 2º corte v1.1; a (ii)-**local** serve alvo real (executor
  local + CEO local, ADR-0016).

**Teste da Razão 1 (mantido):** **#3** neutralizado nas duas via transporte (D2) — (i) por stdout,
(ii)-local por MCP-stdio-callback; **só vivo pra (ii)-docker**. **#1** não morde a (i) (install ≠
poluição); morde a (ii) mas **limitável por teto** (D4) — a decisão é capar, não adiar. **approval-
spam** só na (i), fila madura = 1 clique.

**Teste da Razão 2 (mantido):** a equivalência assíncrona **MORRE pra (ii)** (valor dentro do run que
"operador anexa" não dá) — por isso ela É o coração do valor e entra; pra (i) o efeito é assíncrono
mas "o agente nomeia a lacuna" já bate "operador adivinha", e o dono-solo vira 1 clique.

**Autoridade de escopo do dono (exercida):** puxar a (ii)-local pro v1 é decisão DELE, corrigindo a
conservadoria da faixa. O que ele aceita de olhos abertos: o **teto do #1** vira item de desenho do
v1; a fatia da (ii) **espera a sonda**; e aprova **"o agente decide, mid-run"** (não "operador anexa
depois") — bounded por teto. Palavras do dono: *"Por que v1.1? e nao na v1?"* → *"Sim"* (à correção).

### D4 (G3) — Teto (D6): os 6/3 são de OUTRO eixo; (i) e (ii) nascem com teto PRÓPRIO

**Decisão:** os tetos existentes (codex `6/3`, claude `disallowed-tools`) capam **subagente** — nem
a (i) nem a (ii) abrem subagente. Cada uma abre um **eixo NOVO** e, pela regra do D6 ("fatia que
amplia autonomia não shippa sem o teto"), nasce com o seu:

**Teto da (i) — volume de PEDIDOS (higiene de fila):**
1. **Cap por run** de pedidos DISTINTOS novos (número a cravar; recomendado **5**).
2. **Dedup** contra pendente/instalado na company (mesma skill 2× não vira 2 itens).
3. **Evento visível de descarte** quando estoura (anti-silêncio — o padrão da casa).
O cinto de verdade da (i) é o **clique do dono** (nada instala sem aprovar); o teto é higiene de fila.

**Teto da (ii) — volume de CORPOS em contexto (o cinto DIRETO do #1):**
4. **Cap por run** de corpos de skill buscados inline (número a cravar) — enforcement direto contra a
   re-poluição; estourou → `knowall_fetch_body` **recusa com motivo** (anti-silêncio), o agente segue
   com o que tem.
5. **Dedup** por skill (buscar o mesmo corpo 2× no run = 1 leitura).
Aqui **não há clique do dono** (leitura é livre), então o número é enforcement de PRIMEIRA CLASSE,
não higiene. Mesmo espírito do 6/3: um número, embutido, provado por teste.

**Registrado como pendência SEPARADA (fora destas fatias):** o `disallowed-tools` vazio do claude
(Task ungated) é do **eixo-subagente** (território F-N1/F-N6), não de (i)/(ii).

### D5 (G4) — Custo/observabilidade: (i) e (ii) são **cost-neutral**; auditoria pelo padrão ADR-0009

**Decisão:** nenhuma das duas adiciona ao ledger de custo.
- **(i):** o bloco `pedido-skill` já está na saída do run (tokens contados); o install é **cópia no
  banco** (zero token). Auditoria pelo padrão **ADR-0009**: `SKILL_INSTALL_REQUESTED` → aprovação →
  `SKILL_INSTALLED`/`SKILL_ATTACHED` (a fila já pressupõe `detail.approval_id` + `requested_by_agent`).
- **(ii):** a busca é **leitura no banco** (zero token do core); o corpo vira **input token no run do
  agente, já contado**. O teto do #1 é higiene de CONTEXTO, não de custo. Auditoria = evento leve
  opcional (`SKILL_BODY_FETCHED` p/ rastro) — o attach persistente é da (i); a (ii) é leitura efêmera.

**Registrado pro desenho v1.1 (não perder):**
- **(iii)** agente-durável-como-tool via threadId (`codex-reply`): o chamado é turno de agente
  inteiro → **thread com rollout próprio** → é **exatamente o padrão da F-N5** (seguir
  `receiver_thread_ids` + ler rollout). **Reusa** a F-N5, não inventa contabilidade.

### D6 (G5) — Fatiamento: **DUAS fatias, ambas alvo-EXECUTOR** (afiada pela lente Human-Architect)

**Decisão:** **F-AT1 (a (i)) e F-AT2 (a (ii)-local)**, ambas **alvo o EXECUTOR**, **(i) primeiro**
(reuso puro) e **(ii) depois** (gateada pela sonda MCP-consume). **Não** juntar CEO-personas em
nenhuma. Persona-self-request/fetch de skill = **candidata v1.1, não construída** (só se aparecer
demanda de domínio). O canal do pedido da (i) **inclina a saída estruturada** (ADR-0039) e **não** um
scanner cru; scanner só como **interino-com-nota-de-lealdade** se o kickoff provar que o executor não
tem saída "em forma de turno". Fatias **independentes e paralelizáveis** com a Frente C (subsistema
disjunto: skills/Approval/MCP × memória/graduação).

**Racional (`human-architect-mindset`) — o executor é o protagonista das DUAS:**
- **Fato de domínio:** (i) e (ii) são o "funcionário" da ADR-0003 = **executor** com skillset curado
  que bate na parede mid-task. Persona tem skill *being-ceo*; ela **propõe** skill pro time (bloco
  `proposta`), não executa domínio. Persona pedindo/buscando skill **pra si** é raro-a-degenerado.
- **Modos de falha:** juntar personas **acopla dois subsistemas** (`conversation/` + `execution/`) e
  **dobra a superfície** por um caminho sem demanda — anti-padrão "escopo sem fronteira".
- **Teste de lealdade:** (1) à definição do item ("por **agente**"=executor): persona-self é seam
  ESPECULATIVO; (2) **à ADR-0039**: scanner cru **reabriria o parse frágil que a 0039 aposentou** → o
  pedido da (i) sai por **saída estruturada** (mesma disciplina do "não construir sobre `String`").
- **Síntese:** a lente **corrigiu** o hedge inicial ("personas-primeiro" = o caminho de MENOR valor)
  → **executor-only** nas duas; personas só com demanda de domínio.

**Palavras do dono:** *"perfeito aprovado"* (G3) · *"Concordo com seu ponto"* (G4) · *"sim"* (G5) · e
a re-abertura *"Por que v1.1? e nao na v1?"* → *"Sim"*, que puxou a (ii)-local pro v1 (D3 revisada).

---

## §2 Frente D — o que este grill resolve

| Item | Destino | Por quê |
|---|---|---|
| Agente-como-tool (attach por agente + KnowAll body inline) | **DESCONGELADO E FATIADO** | (i) attach por agente **+** (ii) body inline **LOCAL** ENTRAM no v1 (2 fatias alvo-executor: **F-AT1 → F-AT2**); **(ii)-docker** → v1.1 (D7); (iii) agente-durável-como-tool = épico v1.1 separado. |

---

## §3 Fatias derivadas do v1 — F-AT1 e F-AT2 (escopo preliminar, arquivos prováveis, colisão)

> A EMISSÃO é da sessão de controle (2-em-voo). Escopo preliminar; cada fatia pode ganhar grill curto
> de kickoff. **A 1ª execução derivada COMMITA este handoff.** Ordem: **F-AT1 primeiro** (reuso puro),
> **F-AT2 depois** (gateada pela sonda); colidem em `skills/` → sequenciais.

**F-AT1 — Pedido de skill pelo EXECUTOR via Approval (a (i)).**

**Escopo:**
1. O executor emite um pedido `pedido-skill {skillKey}` — **via saída estruturada estendida ao
   executor** (inclinação D6, leal à ADR-0039) OU **scanner interino-com-nota** (decisão de kickoff).
2. Core parseia → `ApprovalService` cria PENDING (kind novo `SKILL_INSTALL_REQUEST` ou reuso do
   padrão do `ProposalScanner`).
3. Aprovação → `SkillSetService.attach(...)` no escopo da Issue (que já chama `installFromCatalog`
   no caso AVAILABLE) — **reuso puro** do install/attach existentes.
4. **Teto (D4):** cap-por-run (5) + dedup contra pendente/instalado + evento de descarte visível.
5. **Auditoria (D5):** `SKILL_INSTALL_REQUESTED` novo em `ActivityAction`; `SKILL_INSTALLED`/
   `SKILL_ATTACHED` já existem.

**Prova VENDO (esboço):** executor num run pede skill fora do set → item na fila do dono → dono
aprova → **próximo run da Issue materializa a skill** (md5 do corpo bate com o banco) → 6º pedido no
mesmo run DESCARTADO com evento → dedup provado (mesma skill 2× = 1 item). CLIs reais, modo LOCAL.

**Arquivos prováveis (matriz de colisão preliminar):**
- `execution/AdapterRunExecutor.java` — onde a saída do executor é lida; wiring do pedido.
- **Decisão-b:** maquinaria de saída estruturada do executor (análogo a `conversation/
  TurnOutputSchema.java` + `execution/adapter/*Argv.java`/parsers) — **atenção**: toca a área da
  ADR-0039. **Decisão-a (interino):** `governance/approval/SkillRequestScanner.java` NOVO (irmão do
  `ProposalScanner`).
- `governance/approval/ApprovalService.java` — novo kind/payload do pedido.
- `governance/approval/ProposalScanner.java` — template (referência).
- `skills/SkillSetService.java` — attach no escopo da Issue por ator AGENT (hoje o caminho é
  board-only; a (i) relaxa **só** pra criar o PENDING, o attach efetivo segue pós-aprovação).
- `skills/SkillInstallService.java` — `installFromCatalog` **reuso intacto**.
- `activity/ActivityAction` (ou equivalente) — `SKILL_INSTALL_REQUESTED`.
- Teto: código novo pequeno (cap/dedup/evento).
- Testes: `governance/approval/`, `skills/`, `execution/`.

**Colisão:** **baixa na main atual** (nada em voo pós-rodada 10). Toca `execution/` (saída do
executor) + `governance/approval/` + `skills/`. **Se decisão-b** (schema do executor) → toca a
maquinaria ADR-0039 (parsers/argv) — vigiar se alguma resequenciada da Frente D reabrir esses
arquivos. **Frente C** (memória/graduação) = **zero colisão** (subsistema disjunto) →
**paralelizável** (decisão de 2-em-voo do controle).

**Reservas (ficam com o controle — NÃO consumidas por este grill):**
- **ADR:** base = **emenda ADR-0003** (levanta o caveat "estado-alvo FUTURO" só pra (i)) **+
  ADR-0006** (o gate). **ADR-0043 candidata**, provavelmente **NÃO consumida** se ficar dentro do
  padrão Approval (precedente R9-B/R10-A: emenda > ADR nova quando couber; só abre ADR nova se além
  de "campo → rota").
- **Migração: V29 candidata, IMPROVÁVEL** — reusa `company_skill`/attach/Approval (V11 review
  existe). Só nasce V29 se o pedido precisar de kind/payload que a fila atual não comporte. Confirmar
  no kickoff.

**F-AT2 — KnowAll body inline SÍNCRONO pro executor via MCP-stdio LOCAL (a (ii)). DEPOIS da F-AT1; gate = sonda.**

**Escopo:**
1. **Servidor MCP do KnowAll** (novo): ferramentas `knowall_search` + `knowall_fetch_body`, rodando
   com a identidade do produto, callback pro core (reuso do `KnowAllService`/SkillSearch da 7b).
2. **Config do CLI** pra carregar o servidor: claude `--mcp-config` · codex `-c mcp_servers...` (o
   argv ganha o parâmetro — **atenção**: mesma área de `ClaudeArgv`/`CodexArgv`).
3. **LOCAL só** (MCP-stdio: subprocesso do CLI, **sem credencial no `env`, sem rota REST**). **Docker
   = v1.1** (D7).
4. **Teto da (ii) (D4, cinto do #1):** cap-por-run de corpos + dedup; estourou → `fetch_body` recusa
   com motivo (anti-silêncio).
5. **Auditoria (D5):** evento leve opcional `SKILL_BODY_FETCHED`.

**Gate DURO (pré-código):** a **sonda MCP-consume** — claude `-p` e codex `exec` consomem um servidor
MCP stdio na invocação EXATA do produto? R6-B provou `multi_agent`, **não** MCP-consume. Read-only/
descartável (precedente R6-B); **roda em paralelo já** (não usar a **:18081** sem confirmar com o
controle que não há faixa em voo). Falhou num motor → recuo informado (transporte alternativo é grill
próprio; **não** cair no REST rejeitado sem re-grelhar).

**Prova VENDO (esboço):** executor num run precisa de skill fora do set → chama `knowall_fetch_body`
→ recebe o corpo → **usa e termina a task NO MESMO run** (sem BLOCKED, sem próximo-run) → teto provado
(N+1º fetch recusado com motivo) → dedup provado. CLIs reais, modo LOCAL.

**Arquivos prováveis (colisão):** módulo novo do servidor MCP do KnowAll (`knowall/mcp/` ou similar);
`execution/adapter/ClaudeArgv.java` + `CodexArgv.java` (parâmetro mcp-config) — **mesma área de
F-N1/F-N3**, hoje mergeadas (baixa colisão na main atual, vigiar se resequenciada da Frente D reabrir
argv); `skills/` (SkillSearch/body da 7b, reuso); teto novo pequeno; testes. **Colide com F-AT1 em
`skills/` → sequencial (F-AT1 primeiro).** Frente C = zero colisão.

**Reservas:** mesmas da F-AT1 (emenda 0003+0006; ADR-0043 candidata; **V29 improvável** — o servidor
MCP é código novo, provável zero migração). Confirmar no kickoff.

---

## §4 Candidatas v1.1 registradas (anti-círculo — NÃO construídas neste v1)

- **(ii)-DOCKER:** body inline pro executor DOCKER — canal MCP alcançável do container,
  **sequenciado COM a fatia de isolamento do D7** (mesma dependência: o container selado). Gate do D7
  vale (nenhum tenant externo antes dele). _(a (ii)-LOCAL foi puxada pro v1 — F-AT2.)_
- **(iii) expor agente DURÁVEL como tool** (`codex mcp-server`/`codex-reply` threadId, ou subagente
  nativo) — épico próprio; contabilidade **reusa a F-N5**; o `multi_agent` do codex já é **stable**
  (laudo R6-B). Precisa do próprio grill (modelo de org se chamando, tetos de call-graph).
- **persona-self-request/fetch de skill** — só se aparecer demanda de domínio (D6).
- **`disallowed-tools` vazio do claude** (Task ungated) — pendência do eixo-subagente (D4).

**A sonda MCP-consume NÃO é candidata v1.1 — é GATE da F-AT2 no v1** (§3), roda em paralelo já
(read-only/descartável, precedente R6-B). **Não usar a :18081 sem confirmar com o controle que não há
faixa em voo nela.**

---

## §5 O que ficou ABERTO (decisões de kickoff, não de plano)

**F-AT1 (i):**
- **Canal do pedido do executor:** saída-estruturada (b, inclinado) × scanner-interino (a) — depende
  de o executor ter saída "em forma de turno" onde pendurar schema (código, kickoff).
- **Número do cap-por-run** (recomendado 5) — cravar com teste.
- **Kind da Approval:** `SKILL_INSTALL_REQUEST` novo × reuso do padrão `ProposalScanner` — depende do
  shape do `ApprovalService`.
- **Escopo do attach por ator AGENT:** a (i) relaxa a 7a **só** pra criar o PENDING (attach efetivo
  pós-aprovação, board-gated) — confirmar que não vaza attach direto por AGENT.

**F-AT2 (ii):**
- **Sonda MCP-consume PRIMEIRO** (gate duro) — antes de qualquer código da F-AT2.
- **Número do teto de corpos/run** (cinto do #1) — cravar com teste.
- **Gramática do config MCP por motor:** claude `--mcp-config` × codex `-c mcp_servers` — confirmar a
  forma exata contra os binários pinados (mesma disciplina do resume/schema, ADR-0014).
- **Comportamento no estouro do teto:** `fetch_body` recusa com motivo (anti-silêncio) — o agente
  segue com o que tem; confirmar que **não** vira erro fatal do run.

---

## §6 Regras de contrato desta faixa (cumpridas)

Zero código de produto, zero commit, **zero reserva consumida** (V29+/ADR-0043+ seguem livres; o
buraco deliberado da ADR-0041 intocado). Este handoff nasce **UNTRACKED** (1ª execução derivada
commita). Os 3 untracked de contrato (`controle-paralelismo.md`, `laudo-lease-adr0005.md`,
`handoff-auditoria-doc-codigo-2026-07-11.md`) **intocados**. Porta **:8080 não tocada**; nenhuma
sonda ao vivo rodada (a sonda MCP-consume é gate da F-AT2, roda em paralelo na emissão do controle).
HEAD segue em `3fdda05` (main).
Roadmap **não** tocado (o fecho documental cuida de mover o item da Frente D e registrar as
candidatas v1.1). **A EMISSÃO das fatias volta à sessão de controle**, respeitando 2-em-voo.
