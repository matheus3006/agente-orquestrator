# CONTEXT — Glossário canônico do agente-orquestrator

> Linguagem ubíqua do domínio. Apenas termos e significados — sem detalhes de implementação.
> Atualizado durante as sessões de grilling/domain-modeling.

## Núcleo organizacional
- **Company** — a empresa/tenant; fronteira de isolamento. O dono opera várias (uma por produto).
- **Agent** — um "funcionário" de IA dentro de uma Company; tem papel, título e linha de reporte (`reports_to`).
- **Board** — o contexto do operador humano (governança total).

## Teams — roles, times, orquestração e memória (frente Teams; ADR-0017/0018/0019/0020)
- **Tipo de função** — enum **FIXO** de 6 (Liderança · Gestão/PO · Planejamento/Arquiteto · Execução · Revisão/QA · Suporte); o esqueleto que segura os invariantes. **Revisão/QA e Suporte reservados no v1** (revisor = board, suporte = KnowAll). ADR-0017.
- **Role específica** — a especialização com skills; **catálogo ABERTO** (o dono/CEO adiciona). "Enum pro esqueleto, catálogo pra carne."
- **RoleTemplate / molde** — preset reutilizável, **GLOBAL** entre as companies do dono, **VERSIONADO** (`role_template` + `role_template_version` savelog imutável). Carrega motor (ref `engine_catalog`) + SkillSet (refs) + `reports_to` default. Cada Agent **pina** uma versão (`pinned_template_version_id`); contratar **copia o snapshot**; editar o molde = nova versão (não mexe em quem já roda); re-pin explícito e reversível (R10-A: re-pin **SUBSTITUI** — re-aplica motor+skillset da versão, mão soberana board-only). Governança de molde (propose-then-commit + drift-badge) reusa a **fila de Approval** (R10-A: o CEO propõe `CREATE_ROLE_TEMPLATE`, o dono aprova; a `review` não cabia — issue_id/run_id NOT NULL). **Drift-badge** = agente pinou uma versão velha (derivado na tela).
- **Team / Domínio / Foco** — Time = **entidade real** (não só `reports_to`): **domínio** macro (Dev/Marketing) + **foco** livre (frontend/SEO). Vários times por domínio; o **líder de área é dono do domínio** (o CTO lidera os times de Dev). *Não confundir com* **Team (template)** (pacote portável de seed — seção Governança & automação).
- **Arquiteto obrigatório** — todo time válido tem 1 arquiteto, imposto em 2 pontos (**backend valida**, canvas espelha): **composição** (trava dura no **dispatch**, não na criação) + **autoria** (o arquiteto é a FONTE das tasks; o executor só pega tasks que já existem). ADR-0017.
- **Cadeia de comando estrita** — cada agente conversa só com **líder direto (acima) + subordinados diretos (abaixo)**; o board fala **só com o CEO**. Hierarquia **recursiva** (Board→CEO→líderes→times). Cross-team pelo **ancestral comum** (sem comunicação lateral no v1). Posição na árvore é livre (`reports_to`); a *regra* é fixa.
- **CEO / orquestração mediada** — o agente orquestrador mestre; **delegador PURO** (não produz conteúdo — grelha, descobre, contrata, atribui, delega). Orquestra por **INTENÇÃO, mediado na EXECUÇÃO**: propõe pelo canal de SAÍDA → `approval` → aprovação humana. **Não é super-usuário** (deny-by-default é "a coisa pra bater o Paperclip"). ADR-0018.
- **Escada de autoridade** — operador (direto/**soberano**, precedência: a ação dele vence a do CEO) > CEO (escopado, entre marcos) > arquiteto (*pede* skill ao KnowAll) > executor (recebe e usa). O dono aprova o **QUÊ** (direção/roadmap/entregável-por-feature); o CEO decide o **COMO**.
- **Approval (pré-hoc)** — primitiva de aprovação de **ação** (o CEO propõe ANTES de agir); **espelha a `review`** (`PENDING→APPROVED/REJECTED`, `uq_approval_pending` parcial; `decision_reason`/racional na tabela, nunca no log). Nasce na frente Teams (ADR-0018; o "2º consumidor" que o ADR-0012 reservava). *Distinguir do review pós-hoc* (revisa output de run concluído).
- **Dimensionamento enxuto / montagem incremental** — a empresa é montada **time-por-time**, com aprovação de cada time (o **freio primário de quantidade**); "mínimo necessário" (1 agente de IA vale por N humanos). Nunca 90 agentes de uma vez.
- **`spawn_lineage`** — a cadeia de **quem CAUSOU o trabalho** (coluna em `heartbeat_run`/`agent_wakeup_request`; raiz `''` = ato humano direto). Guard em 2 faces (módulo profundo `execution.lineage`): acordar **ancestral** da própria linhagem = recusado (409 no enqueue; cancel no checkout — mata loop A→B→A) + **cap de profundidade** (5). **Linhagem é CAUSALIDADE, não autoria**: o wakeup aprovado **herda** a linhagem do run proponente (aprovação não lava a cadeia; ator no log segue = aprovador); raiz nova = só mão soberana direta. Sobrevive ao reaper; promote herda a mais funda dos DEFERRED. T.4/ADR-0016.
- **Fan-out cap por arquiteto** — `team.fan_out_cap` (default 5): o time não espalha mais **runs concorrentes** que o cap. 2 faces espelhando a trava do arquiteto: 409 no enqueue **só quando criaria run novo** (coalescing não é fan-out) + preflight cancela o que entrou por fora (conta o em-voo). Agente sem time = sem cap (o freio dele é o orçamento de orquestração). T.4.
- **Ledger de orquestração** — `AGENT_SPAWN`(100)/`WAKEUP`(10)/`RUN_OPEN`(1) cobrados **NA ABERTURA** como `cost_event` (V5) em **moeda própria** (`orchestration_action` não-nulo; somas de dólar filtram por nulo). Teto = **orçamento** (`orchestration_limit_cents` na policy), não contador solto; a conta cai em **quem causou** (último elo da linhagem; nulo = humano). Recusa (409) não cobra. Estourou → incidente + **drena até o checkpoint**: cancela só a FILA (QUEUED + wakeups); o EM-VOO termina o passo e liquida (settle/report); o preflight barra o sucessor. T.4/§A.6-#3.
- **Chats nomeados por assunto** — organização pro humano (Geral · Desenvolvimento · Marketing…), **todos com o CEO** — não muda o interlocutor. Docados na Central de Missão.
- **Turno de conversa** — a mensagem do dono + a resposta do CEO numa Conversation; **1 turno = 1 Run** (`PENDING→ANSWERED/FAILED`; claim guardado pelo motor; turnos excedentes drenam um-por-Run). A resposta é a **saída final** do Run (`RESULT` > prosa > stdout, **capada**) — a profundidade fica no StepTrace, a 1 clique. O prompt é montado **por-Run** (estado vivo + mensagem); a carta chega por `.claude/CLAUDE.md`, não pelo prompt.
- **Carta de interesses / `company_memory`** — a memória durável da empresa, **soberana e DERIVADA** (projeção **determinística**, **zero LLM** no caminho de escrita — defesa contra memory-poisoning); versionada, materializada como `.claude/CLAUDE.md` a cada Run do CEO (espelha o `SkillMaterializer`). ADR-0018/0019.
- **Decisão (átomo de memória)** — a **unidade atômica endereçável** da memória (não a Issue): tipo (direção|operacional|técnica), origem, o-que-mudou, **racional obrigatório** + `racional_tipo` (**lógico bloqueia / intuição commita**), `supersedes`. O gate de aprovação é **fábrica de Decisões**. ADR-0019/0020.
- **Decision-log / segundo cérebro** — método de **findability** (endereçamento + índice enxuto + navegação **JIT**, **SEM vetor**); tiers com **cap + graduação** (`activity_log` → Ledger por-Issue → ADR-da-Empresa/Playbook → carta → cards). O grill de racional é **auto-liquidante** (padrão vira princípio, para de perguntar). ADR-0019/0020.

## Trabalho
- **Issue** — a unidade de trabalho **e** de comunicação (não há chat separado). Tem único responsável.
- **Goal** — o "porquê"; Issues rastreiam ancestralidade até um Goal.
- **Project** — agrupamento de trabalho; liga Issues e Goals.
- **Issue BLOCKED / recovery** — uma Issue **estacionada** que precisa de decisão humana: cobre **run FAILED** (ADR-0011) **e** **review REJEITADA** (ADR-0012), distinguidos pelo **evento** (`ISSUE_BLOCKED` vs `REVIEW_REJECTED`), não pelo status. Lock liberado; fora do conjunto pegável, não re-executa sozinha. O board destrava com **REOPEN** (→`OPEN`, mantém o agente → o próximo wakeup re-executa) ou **CANCEL** (→`CANCELLED`). Sem auto-retry no v1.
- **settleAfterRun** (seam) — a função no `IssueService` que **liquida a Issue pós-Run**: sucesso→`DONE` (ou →`PENDING_REVIEW` se `risk_level ≥ limiar`, ADR-0012), falha→`BLOCKED`, sempre liberando o lock, guardada por `execution_lock_run_id` (só o run detentor liquida); recebe o `RunOutcome`.
- **`risk_level`** — escala **1-5** na Issue (1 = sem risco; 5 = alto risco). Gate = `risk_level ≥ limiar` (`orquestrador.review.risk-threshold`, config global, default 5). No v1 o **operador seta** (por-Issue, na criação); a avaliação do architect é conteúdo do plano, não número confiado.
- **Review gate / `PENDING_REVIEW`** — run de alto risco **não vira `DONE`** — vai para `PENDING_REVIEW` (lock solto, não roda → não gasta); o board **APPROVE → `DONE`** ou **REJECT → `BLOCKED`** (reusa o unblock). **Pós-hoc**: revisa o *output de um run concluído* (ADR-0012).
- **`review`** (entidade) — request→decision (`PENDING→APPROVED/REJECTED`) por ciclo de revisão; guarda `run_id`, reviewer, `decided_at`, `decision_reason` (texto livre **na tabela**, nunca no activity_log). Máx. 1 `PENDING` por Issue.
- **"Duas aprovações"** — aprovar o **plano** e depois o **resultado** = o **mesmo gate em duas Issues** (Issue-plano / Issue-implementação); a sequência entre elas é **manual** no v1 (sem dependência automática entre Issues).

## Execução (termos que estavam colados — agora separados)
- **Workspace** — o diretório de trabalho (git worktree) num **volume nomeado**; guarda *arquivos*. Atravessa Runs. No DOCKER o backend **não o enxerga direto**: ler e escrever arquivos atravessa o `ExecutionTarget` — leitor hermético (F.9/ADR-0021) e **escritor da árvore materializada** (F.9e/ADR-0022: replace por subtree, o host vence; `.claude/skills` todo run, `.claude/CLAUDE.md` só em turno; falha do escritor = falha do run).
- **Run** — uma execução de heartbeat, do começo ao fim. *Efêmero*. Roda num **ExecutionTarget**.
- **Heartbeat** — o disparo agendado/por-evento que acorda um Agent para produzir um Run.
- **Session (do adapter)** — o *handle de retomada* do CLI do agente (ex.: `session-id`, transcript, auth) que permite continuar o raciocínio entre Runs. **Não confundir com Run** (efêmero) nem com **Workspace** (arquivos). O ponteiro durável mora no banco (`agent_session`, chave `(agent, conversation)`); o estado pesado no Workspace. **Continuidade nativa LIGADA** (ADR-0038): o handle capturado volta como `priorSession` no caminho normal — o turno 2+ de uma **conversa** resume a sessão do turno 1 (F-N1, escopo por conversa) e o run N+1 de uma **Issue** resume o run N (F-N2, escopo por `issue.conversation_id` das absorvidas; Issue sem conversa segue fresca). O prompt entra em **dieta** no turno continuado (não re-injeta o que a sessão já tem). O **handoff** (destilação a 50-60%, ADR-0015/0030) foi rebaixado a **rede de segurança** de quando a janela enche. Resume que falha (sessão sumida) → `RUN_SESSION_LOST` + fresh start (nunca silêncio).
- **ExecutionTarget** — onde o Run roda: `docker` (canônico) ou `local` (dev). **LOCAL** herda o login do host e **não isola** (badge na UI); **DOCKER** (F.8/ADR-0016) roda a imagem pinada do adapter com **só a credencial do plano dele** montada (volume no home do container) — o isolamento de plano é real, com a ressalva: por-diretório-de-config, **não** fronteira de segurança rígida.
- **Adapter** — a integração com um runtime de agente (Claude Code, Codex, …); expõe `execute(ctx)`, **traduz `model`/`effort` nas flags do seu CLI** e **declara o próprio EngineContainer** (imagem + credencial — o executor não conhece nenhum dos dois). `claude-code`/`codex` são adapters **ricos**: parseiam o `stream-json` → steps semânticos e **instrumentam tokens** (`ClaudeStreamParser`/`CodexStreamParser`); `process` é o arquétipo demo. *Evitar:* "o modelo escolhe o plano" — **o adapter (qual CLI roda) escolhe o plano**.
- **EngineContainer** — o material de container de um runtime (F.8/ADR-0016): a **imagem com CLI pinado** (tag = versão; defesa contra o `--bare`) + o **volume de credencial do plano** montado no home do container (`orq_claude_creds`/`orq_codex_creds` — o home inteiro, cobrindo `~/.claude` E `~/.claude.json`) + o remédio legível quando a credencial quebra. A **montagem seletiva É o isolamento**: cada adapter declara só o seu volume. O login **nasce no container** (`infra/engines/setup-*.sh`, uma vez; nunca copiado do host — o refresh token rotaciona ao usar).
- **Motor / Engine** — o trio `adapterType` + `model` + `effort` que define o "cérebro" de um Agent (ADR-0014). O `adapterType` decide **qual plano de assinatura autentica** (`claude-code`→Claude, `codex`→ChatGPT, `process`→demo); `model`/`effort` são **atributos do Agent** (default persistente para todas as suas Issues), String livre **validada em runtime pelo adapter** contra o CLI (nunca enum; `null` = default do plano). Apresentado na UI como uma **escolha única**.
- **Catálogo de motores / `engine_catalog`** — tabela curada (seed), **global** (sem `company_id`, como o catálogo de Skills), servida por `GET /engines` (board-only); lista as combinações `adapter+model+effort` com `label` legível e `context_window`. **Sugere** para a UI (o label preenche os 3 eixos), **não restringe** o dado. Modelo novo = `INSERT`.
- **Handoff de contexto** — regra de ouro (ADR-0015): a ~50-60% de uso da **janela de contexto** de uma sessão, o agente **destila** o estado num documento e uma sessão **nova** assume lendo-o, em vez de recarregar a conversa inteira (`--resume` bruto, que só faz a janela crescer). Invariante de qualidade transversal ao produto; o `context_window` do `engine_catalog` é o denominador. O v1 só **instrumenta os tokens** (do stream); o loop completo (detectar → destilar → retomar) é fatia futura.
- **Contabilidade off-book de subagentes** (ADR-0041, F-N5): o `usage` do run principal **NÃO inclui** o gasto de subagentes (Task/spawn_agent) — provado empiricamente (sonda R6-B: claude marcou `output=63` vs 27.347 tokens reais de 2 subagentes; codex gastou 96k de input só de overhead multi-agente). Captura **assimétrica por motor** (canal nativo em cada um, nunca proxy próprio): **claude** soma `task_notification.usage.total_tokens` — já chega no stream padrão, sem flag nova (`ClaudeStreamParser.subagentTokens()`); **codex** não traz o usage do filho no stream do parent — só o `thread_id` (via `collab_tool_call.receiver_thread_ids`, `CodexStreamParser.childThreadIds()`); um side-channel (`CodexSubagentUsageReader`) lê o rollout de cada thread-filho em `~/.codex/sessions/**` (rollout ausente ⟹ WARN, fail-soft, nunca quebra o run). O gasto off-book vira uma **segunda `CostEmission`** (`offBook=true`, coluna `cost_event.off_book`, V28) — visível/rotulado no ledger, mas **excluído** da soma que o `HandoffThresholdDetector` faz (é gasto REAL, mas de **outra janela de contexto** — não pode disparar handoff falso do turno principal). Superfície de leitura: `BudgetService.costEventsForRun`/`GET .../runs/{runId}/cost-events`.

## Skills & conhecimento
- **Skill** — uma capacidade empacotada (um `SKILL.md`; assets multi-arquivo = fatia futura). Três facetas: **identidade global** (Camada 1, catálogo do KnowAll, sem `company_id`), **instalação por-company** (Camada 2, `company_skill`) e **versão** (conteúdo + hash). Fonte de verdade no banco.
- **Catálogo global de Skills** (Camada 1) — as identidades de Skill + o histórico de versões originais que o **KnowAll** conhece; vive no banco **sem** `company_id`. Alimentado por import do PC do operador; nenhum agente o acessa. *Evitar*: "catálogo da company".
- **Savelog de Skill** — o histórico de versões de uma Skill no catálogo global (`content_hash` + descrição da mudança + origem). A base é a versão de referência; um ajuste de company só vira base por ação explícita ("publicar", fatia futura).
- **Biblioteca da Company** (Camada 2; ex-*CompanySkillLibrary*) — as Skills instaladas numa Company (`company_skill`/`company_skill_version`); referencia a identidade global e **copia** o conteúdo da base global no install (customização não vaza entre companies). *install ≠ attach*.
- **SkillSet** — o que um Agent carrega num Run: o **set base** pinado por-agente (`agent_skill_selection`) **+** as Skills **anexadas por-Issue**. Cada Agent carrega só o seu — a defesa contra poluição de contexto.
- **Skill anexada por-Issue** (ex-"por-Run") — uma Skill (versão pinada) anexada ao trabalho de uma **Issue**. Efeito **efêmero** (materializa a cada Run da Issue, some quando a Issue fecha); o **registro é imutável** (auditoria).
- **install-on-attach** — anexar pela identidade **GLOBAL** (`skillId`): se a Skill já está na Biblioteca da Company, anexa a versão corrente; se não, **instala** (copia a versão corrente global, `source=CATALOG`) **+** anexa (1 ação, 2 eventos `SKILL_INSTALLED`+`SKILL_ATTACHED`). **Board-only no v1** (o agente-como-tool é fatia futura; a Approval pré-hoc que o destravará nasce na frente Teams — ADR-0018).
- **KnowAll** — capability **nível-operador** (acima das companies): mantém o catálogo global de Skills (identidades + savelog), responde "temos Skill para X?". Descoberta é global; instalação/conteúdo é por-company. **No v1 é operator-reachable**: o agente selado **não** o alcança (sem canal agente→core); o lado "tool (agente)" + body inline é fatia futura. *Evitar*: "o agente consulta o KnowAll em runtime".

## Observabilidade & criação
- **Plan** — o plano de uma Issue (documento revisável); o "o quê/como" antes da execução.
- **StepTrace** — a sequência observável de passos de um Run (decisões, tool calls); transmitida ao vivo.
- **PreviewArtifact** — a **foto imutável** do protótipo publicado por um Run (F.9/ADR-0021): 1 arquivo
  HTML standalone (app-in-a-file — o **browser do board executa**; motor cadillac), **capturado no
  settle** de run SUCCEEDED via leitor hermético (`--network none`, volume `:ro`, imagem do próprio
  adapter) e persistido no banco (`preview_artifact`, V20, append-only, cap 2MB). Nasce **por-Run**
  (auditoria; a Review embute a versão exata do run revisado), apresenta **por-Issue** (versões; dedup
  por `content_hash`). Renderiza SÓ dentro de `PreviewFrame`. *Evitar:* "preview ao vivo intra-run" (o
  vivo durante o run é o StepTrace) e "o agente registra o artefato" (o pipeline observa o contrato de
  lugar — deny-by-default).
- **Contrato de lugar (`.orq/preview/`)** — o canal de anúncio do preview: o agente anuncia
  **escrevendo arquivo**, nunca chamando API (ADR-0001). Dois contratos no v2: `index.html` (foto
  v1, ADR-0021) e **`server.json`** (manifest do PreviewServer, ADR-0023 — `{command, port}`, lido
  no settle e persistido por run; o que se declara é o que executa). Quem carrega os contratos é a
  **skill** (publicação como passo final — visível no StepTrace). Existe no settle → registra; não
  existe → run normal. **`.orq/`** é o namespace reservado de contratos do orquestrador dentro do
  workspace (ao lado do `.claude/` do SkillMaterializer). Run sem Issue não captura.
- **Mission Control** — a home da company no board: grid de cards ao vivo (1 por agente: status derivado,
  Issue em execução, mini-terminal do StepTrace, tempo decorrido) + feed de atividade. A materialização v1
  do diferencial #4. *Timeline Gantt por agente = evolução futura.*
- **F.0 / endpoints de observação** — leituras board-only que servem o Mission Control:
  `mission-control` (agregado, join no servidor), `runs/{id}/steps?offset=` (incremental),
  `issues/{id}/runs` (ledger), `activity` (feed).
- **Protótipo-como-spec** — o protótipo cadillac HTML standalone (em `prototipos/`, descartável) aprovado
  pelo operador vira a spec visual congelada das fatias de implementação; a aplicação real não recebe
  código de protótipo.
- **CompanyLink** (frontend) — o único caminho de navegação do board; auto-prefixa o `companySlug` ativo
  (par com `useCompanyNav`). *Evitar:* importar `next/link`/`useRouter` direto em páginas (ADR-0013).
- **PreviewFrame** (frontend) — o único lugar onde HTML de agente renderiza, em dois modos:
  **modo srcdoc** (foto v1): `sandbox="allow-scripts"` **e nada mais** (`allow-same-origin` aqui é
  letal — daria o origin do BOARD ao conteúdo), bytes pelo fetcher autenticado, injetados via
  srcdoc/blob (endpoint devolve texto + nosniff, **nunca** `text/html` navegável); **modo src**
  (PreviewServer v2): `src="http://127.0.0.1:<porta>"` com `sandbox="allow-scripts
  allow-same-origin allow-forms"` — aqui `allow-same-origin` é seguro E necessário (o origin
  concedido é o da porta do app, não o do board; sem ele o fetch do app à própria API quebra).
  Moldura device desktop/mobile; reusado em Issue, Review e tela Servidores (ADR-0021/0023).
  *Evitar:* `<iframe src=` apontando pra API (iframe não manda o header de auth).
- **PreviewServer** — o preview de PROCESSO/PORTA (v2, F.10/ADR-0023): servidor vivo executando o
  app do agente num **4º container da família** (run, leitor, escritor → servidor), criado pelo
  **pipeline sob demanda** (clique do operador), volume da Issue **`:ro`** + tmpfs `/tmp` (o que
  roda é o que o run liquidou), **sem credenciais**, porta efêmera no loopback, rede `orq-preview`
  sem saída. Morre por TTL de 15min sem heartbeat do board, checkout de run novo na Issue, ou stop
  manual (tela Servidores). Máximo 1 por Issue. ADIÇÃO ao v1, não substituição — a foto continua o
  registro imutável. *Evitar:* "o agente sobe o servidor" (o agente declara via manifest; o
  pipeline é dono do processo) e "servidor no container do run" (credencial + settle).

## Governança & automação
- **Budget** — limite de custo/token por escopo; estouro → hard-stop + auto-pause.
- **Approval-de-ação** — autorizar uma ação **antes** de executá-la (pré-hoc). **NÃO** é o review gate (pós-hoc). A primitiva **nasce na frente Teams** (ADR-0018) — entrada canônica: **Approval (pré-hoc)** na seção Teams; o consumidor agente-como-tool continua futuro (ADR-0012).
- **ActivityLog** — registro durável de toda ação mutante (ator + evento).
- **Routine** — trabalho recorrente (cron/webhook/api) que cria Issues e acorda Agents.
- **Team (template)** — pacote portável (org chart + Agents + Skills) para criar a Company de um novo produto. *Não confundir com* **Team** (entidade de runtime: domínio+foco — seção Teams).
