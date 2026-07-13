# ADR-0003: Relação SkillSet × KnowAll (base estático + aumento dinâmico)

**Status:** Aceito (2026-06-30)

## Contexto
O diferencial central do produto é resolver a poluição de contexto: cada Agent deve carregar **só** as Skills que precisa. Mas tarefas variam — às vezes um funcionário precisa de uma Skill que não está no seu set. O KnowAll conhece todas as Skills. Como os dois se ligam sem reintroduzir a poluição?

## Decisão
**SkillSet base estático + aumento dinâmico por-tarefa.**
- Cada Agent tem um **SkillSet base** (`agent_skill_selection`) que o dono define — é o que é materializado por padrão em cada Run.
- Numa Issue específica, o Agent (ou o dono) consulta o **KnowAll** e pode **anexar** uma Skill ao **escopo daquele Run** (não permanente). A anexação por-Run é registrada (activity log) e expira com o Run.
- KnowAll **nunca** injeta sozinho/automaticamente (isso seria o modo "totalmente dinâmico", rejeitado).

## Alternativas consideradas
- **Só estático:** previsível, mas inflexível; KnowAll viraria só consulta informativa.
- **Totalmente dinâmico:** KnowAll injeta por análise da Issue — imprevisível e arrisca re-poluir o contexto (contra o objetivo).

## Consequências
- Contexto enxuto por padrão; flexibilidade sob demanda e auditável.
- Requer modelar "Skill anexada por Run" (escopo efêmero) além do SkillSet base.
- KnowAll é tool (chamável pelo Agent) **e** persona (consultável pelo humano) — ambos só *sugerem/anexam sob pedido*.

## Revisão 2026-06-30 (grilling)
- **Escopo da anexação: por-Issue, não por-Run.** A tarefa é a Issue; "por-Run" era impreciso. A Skill anexada materializa no início de **cada Run da Issue**; o tool do KnowAll devolve o **body inline** para uso imediato no Run vivo (sem injetar no container em execução).
- **Releitura de "expira com o Run":** o **efeito** (materialização em disco) expira; o **registro** (`issue_skill_attachment`) é **imutável** e persiste para auditoria. "Expira" de fato quando a **Issue fecha**.
- **Install-on-attach + Approval:** anexar uma Skill do catálogo global ainda não instalada **instala** na biblioteca da company (copiando da base global) **e** anexa — uma ação, **dois eventos** (`SKILL_INSTALLED` + `SKILL_ATTACHED`). Install novo disparado por **agente** passa por **Approval** (invariante #4); o **board** instala direto. Anexar o que já está instalado é livre (board e agente).
- Ver ADR-0008 (KnowAll nível-operador) e ADR-0007 revisado (modelo de dados).

## Revisão 2026-07-01 (grilling 7c)
- **v1 da 7c é operador-only.** Install/attach acontece via **board REST**, apenas. As frases acima "anexam
  board **E** agente (tool)", "install novo por agente → **Approval**" e "o tool do KnowAll devolve o **body
  inline** pro agente" descrevem o **estado-alvo FUTURO**, **NÃO** o v1.
- **Razão do adiamento (o *porquê*, não "faltou tempo"):** agente-como-tool contradiz os diferenciais #1
  (re-poluição de contexto — o agente pediria N skills "por via das dúvidas") e #3 (isolamento — exigiria
  furar o container selado com rota de rede container→core + credencial run-scoped). E o efeito é **assíncrono
  de qualquer jeito** (materializa no próximo Run da Issue), então "agente pede → board aprova → efeito no
  próximo run" é funcionalmente equivalente a "operador anexa → efeito no próximo run"; solo owner = sem 2º
  humano p/ justificar o laço pede→aprova. Sem canal agente→core no v1 (`env=Map.of()`).
- **KnowAll no v1 = operator-reachable only** — o agente selado não alcança search/body inline.
- **install-on-attach do board (o que a 7c constrói):** o input do attach é a identidade **GLOBAL** (`skillId`)
  — **fold** do endpoint da 7a (`companySkillId` sai do contrato). O servidor resolve o install-state:
  **INSTALLED** → anexa a versão corrente (1 evento `SKILL_ATTACHED`); **AVAILABLE** → **instala** (copia a
  versão corrente global, `source=CATALOG`, `SKILL_INSTALLED`) **+** anexa (`SKILL_ATTACHED`), na MESMA tx.

## Revisão 2026-07-12 (F-AT1, grill agente-como-tool)
- **O caveat "estado-alvo FUTURO" da revisão 2026-07-01 se ENCERRA para a metade agente-pede.** O item
  congelado desde a 7c (nota acima: *"install novo por agente → Approval"*, *"install/attach acontece via
  board REST, apenas"*) desconge­lou (grill R11-A) e a **(i)** entrou no v1: o **EXECUTOR** de uma Issue
  pede uma skill fora do seu set pelo canal de **SAÍDA** (`` ```pedido-skill ``, decisão-a interina —
  o Run de Issue não tem `outputSchema`/ADR-0039, ver §D2 do handoff do grill) → vira **Approval PENDING**
  (`SKILL_INSTALL_REQUEST`, 6ª ação do gate ADR-0018/0020) → **APPROVE** chama o MESMO
  `SkillSetService.attachToIssue` desta ADR (install-on-attach **reuso puro**, zero mecanismo novo).
- **O que NÃO mudou:** o attach efetivo continua **board-gated** — a (i) relaxa a 7a **só** para permitir
  que o PEDIDO nasça PENDING a partir de um ator `AGENT`; quem materializa a skill (aprova) segue sendo o
  dono, 1 clique na fila madura (ADR-0018). Nenhuma rota REST nova, nenhuma credencial nova no `env` do
  agente (#3 intacto) — o transporte é o `StepTrace`/StdOut que o core já lê (D2 do grill).
  `install/attach direto por agente` (sem gate) **continua NÃO existindo** — só o **pedido** é do agente.
- **O que ainda é FUTURO:** a **(ii)** — KnowAll body inline pro agente NA HORA (leitura síncrona via
  MCP-stdio) — segue **fora do v1** até a sonda MCP-consume confirmar o transporte (F-AT2, fatia própria).
- Ver `docs/handoff-grilling-agente-como-tool.md` (D1–D6) e ADR-0006 (o gate ganha seu 1º consumidor real).

## Revisão 2026-07-13 (F-AT2, grill agente-como-tool)
- **O caveat "estado-alvo FUTURO" se ENCERRA também para a metade (ii) — o body inline síncrono.** A
  revisão anterior deixou pendente: *"a (ii) segue fora do v1 até a sonda MCP-consume confirmar o
  transporte"*. A sonda (R12-B, `docs/laudo-sonda-mcp-consume.md`) deu **PASS nos 2 motores** e a
  **(ii)-LOCAL entrou no v1** (D3 revisada pelo dono): o **EXECUTOR** de uma Issue busca o **corpo** de
  uma skill fora do seu set **NA HORA, mid-run**, e **termina a task no MESMO run** — a autossuficiência
  mid-task que é o norte (não "operador anexa → próximo run"). Os dois caminhos CONVIVEM: o `fetch_body`
  é **leitura efêmera** livre; o **attach persistente** continua sendo o caminho da (i)/F-AT1.
- **Transporte (D2):** um **servidor MCP-stdio do KnowAll** (subprocesso do CLI, script Node único
  materializado por-run em `.orq/mcp/knowall-server.js`), com a **identidade do produto** — tools
  `knowall_search` + `knowall_fetch_body`, ambas `readOnlyHint:true` (requisito do gate próprio do codex
  no headless, laudo). O servidor faz **callback loopback** pro core com um **token efêmero por-run**
  (NÃO credencial de agente) no header `X-Orq-Mcp-Token`; o `KnowAllMcpBridge` resolve token→escopo do
  run. **Nenhuma credencial no `env` do agente** (`env=Map.of()` intacto, #3), **nenhum endpoint ator-
  AGENT** — a rota `/internal/mcp/**` é interna/loopback, mediada pelo produto, e **NÃO** a superfície
  `REST agente→core` (credencial + rota + endpoint ator AGENT) que a 7c rejeitou. Endurecer o bind a
  loopback + a fronteira do container = **(ii)-docker, v1.1, D7**.
- **Teto do #1 (D4), enforcement de PRIMEIRA CLASSE (aqui NÃO há clique do dono):** o `KnowAllMcpBridge`
  capa **3 corpos DISTINTOS por run** + **dedup por skill** (mesmo corpo 2× = 1 leitura, não consome o
  teto); estourou → `fetch_body` **recusa COM MOTIVO** (evento `SKILL_BODY_FETCH_REFUSED`), **não-fatal**
  — volta como resposta da tool e o agente **segue com o que tem**. Mais apertado que o cap de PEDIDOS da
  (i) (5, higiene de fila com gate) porque o corpo cai **DIRETO no contexto** sem clique — é o cinto
  literal do #1 (re-poluição). Mesmo espírito do 6/3: um número pequeno, embutido, provado por teste.
- **Auditoria (D5):** `SKILL_BODY_FETCHED` (rastro leve) — a (ii) é leitura efêmera (o attach persistente
  é da (i)). **Zero migração** (o servidor MCP é código novo; V29 livre) e **sem ADR nova** (dentro de
  "campo→rota"; ADR-0043 livre).
- **O que ainda é FUTURO:** a **(ii)-docker** (canal MCP alcançável do container, sequenciado com o D7) e
  a **(iii)** (expor agente DURÁVEL como tool) — épicos v1.1 próprios; persona-self-fetch idem (D6, só com
  demanda de domínio).
- Ver `docs/handoff-grilling-agente-como-tool.md` (§3 F-AT2, §5) e `docs/laudo-sonda-mcp-consume.md`
  (gramática exata por motor + o requisito `readOnlyHint`).
