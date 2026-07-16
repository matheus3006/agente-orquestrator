# HANDOFF — R21-A: PROVA FINAL do v1 — **v1 FECHADO pelo dono; bug #1 do roadmap achado**

> **▸ Proveniência (R22-A, 2026-07-16):** estado vivo da **faixa R21-A (prova final do v1)**,
> **congelado** e movido de `/HANDOFF-prova-final-v1.md` (raiz, untracked) para `docs/handoffs/`
> na fatia do conserto do bug #1 (R22-A, precedente R20-A). O norte do v1 é "TUDO registrado" —
> este é o registro da prova que fechou o v1 e abriu a fase de descoberta de bugs. O §4 (desenho
> grelhado do conserto) foi EXECUTADO pela R22-A (`fix/issue-compositor-adr0037`).

> Estado vivo da faixa **R21-A**. Era UNTRACKED de propósito enquanto vivo (precedente R20-A).
> Ledger: `docs/controle-paralelismo.md` (Rodada 21). **ZERO commit nesta faixa.**
>
> **A prova se pagou.** Ela existia para achar o que teste unitário não pega, e achou: um bug
> grave que nenhuma suíte pegaria, porque **o caminho nunca tinha sido pisado**.

## 1. VEREDITO — e a decisão do dono que o supersede

**DECISÃO DO DONO (2026-07-16, verbatim na conversa): o v1 está FECHADO.** O dono reconhece que
há "muita coisa para arrumar na casa" e **o bloqueador abaixo vira item do roadmap de ajustes,
não trava da declaração**. Esta seção antes dizia "o v1 NÃO fecha" — **superseded**; a recomendação
técnica virou input, a decisão é do dono.

**PRÓXIMA FASE (definida pelo dono): DESCOBERTA DE BUGS.** As ADRs viram a especificação; cada
funcionalidade descrita nelas é testada AO VIVO e classificada em funciona / não funciona; o
resultado vira o roadmap de ajustes. **Esta sessão é a prova de conceito do método**: a prova
ponta-a-ponta de UMA fatia achou 1 bug grave + 4 achados em ~4h, todos por pisar caminho que
teste unitário não pisa. Ver §10.

### O bug (item #1 do roadmap de ajustes)

**A organização que o CEO contrata não consegue trabalhar — e, em vez de falhar, ela declara a
issue `DONE`.** Trabalho fantasma carimbado como concluído.

Medido ao vivo: acordei o Arquiteto de Pricing (contratado pelo gate, `claude-code`/opus) na
issue #1 → run `e67ff7dd`: **58,6 ms**, 3 eventos (`RUN_QUEUED`→`RUN_STARTED`→`RUN_SUCCEEDED`),
**zero chamada de motor**, e a **#1 foi para `DONE`**.

Bate no norte do v1 ("CEO discute → racional de cargos → contrata → org") e no "v1 = TUDO
registrado". As pernas (g)/(h)/(i) da prova seguem bloqueadas por ele — **o conserto (§4) já tem
desenho grelhado pelo dono e é a primeira candidata do roadmap.**

## 2. A CAUSA — não é "o gate esqueceu um campo"

A forma real é uma **assimetria**: o caminho do turno tem compositor de prompt; **o caminho da
issue não tem**, e o `agent.runtime_command` (estático, digitado na contratação) é o tapa-buraco
que ficou no lugar dele.

| | run de **TURNO** | run de **ISSUE** |
|---|---|---|
| quem compõe a instrução | `ConversationTurnCoordinator.composePrompt(persona, assunto, estado vivo, fala do dono)` | **ninguém** |
| o que o agente recebe | prompt composto **por-run** (`AdapterRunExecutor:264-269`) | `agent.runtime_command` (`AdapterRunExecutor:224`) |
| entrega ao motor | `acceptsPromptAsCommand()` ? command : env `ORQ_PROMPT` | idem |

Os 3 elos do defeito, cada um defensável sozinho:

1. **`ApprovalService:348`** — o gate contrata com `runtimeCommand = null` **hardcoded**. O molde
   não supre: `RoleTemplateService.Preset` é `(adapterType, model, effort, skillKeys,
   reportsToDefault)` — **sem runtimeCommand**; e `AgentHireService:73` passa
   `command.runtimeCommand()` **cru, sem fallback no preset** (ao contrário de
   adapterType/model/effort, que fazem merge nas linhas 70-72).
2. **Os 3 adapters** — `ClaudeCodeAdapter:69`, `CodexAdapter:77`, `ProcessAgentAdapter:38`:
   comando vazio → `// no-op verde … não gasta token` → `Result.ok`.
3. **`IssueService.settleAfterRun:172-179`** — run com sucesso → `tryComplete` → **DONE**.
   R1 fica abaixo do limiar de review, então o gate da ADR-0012 nem intercepta.

**Por que sobreviveu até o fim do v1:** *todo* agente da Teams Demo que executou issue tem
`runtime_command` preenchido **com a tarefa digitada à mão**:

> `Smoke F8 Claude` → *"Você tem skills instaladas em .claude/skills/ — use a que se aplicar.
> **Crie um protótipo da tela de agenda semanal de uma clínica.**"*

O operador escrevia o trabalho em dois lugares (título da issue + comando do agente) e o agente
só lia o segundo. **A issue era enfeite.** Esta prova é a primeira vez que um agente
**contratado pelo CEO** foi mandado executar. O caminho nunca tinha sido pisado.

Confirmado: `configureRuntime` tem **um único caller** (`AgentHireService:73`, na contratação) —
nada nunca atualiza o comando por issue. E `getTitle()/getDescription()` da issue **nunca**
chegam a prompt de executor (só absorção / Mission Control / texto de Decisão / corretiva).

## 3. Achado de arquitetura em cascata (encontrado ao desenhar o conserto)

**A estratégia não chega em quem trabalha.** O grill arrancou do dono, em 3 rodadas: piso de
margem 75%, âncora = dia de freela (R$800–1.200 / US$300–500), COGS ~US$600/mês, concorrentes
Devin/Cursor/Lovable/Replit, SMB→mid foco BR. **Nada disso está na descrição da #1.** O
Planejador gravou tudo em `.orq/conversa/307051c4…/estrategia.md` — que mora no workspace **da
conversa do CEO** (`workspaces.resolveConversation`), enquanto o run de issue usa o workspace
**por agente+issue** (`workspaces.resolve`). Pastas diferentes: o executor não alcança.

E o **endereço** da estratégia não é durável: quem batiza o arquivo é o CEO (campo `bastao` do
bloco `handoff`), e esse caminho só existe **dentro do texto do sentinela**
(`PersonaContinuationSentinel:29`) — não há coluna. O nome varia por etapa (`dossie.md` no
Q→P, `estrategia.md` no P→E).

Viabilidade OK: o leitor já existe e está **provado hoje** — `ContextHandoffLoop:73` usa
`readWorkspaceFile(spec, path, maxBytes)` (foi como o run 2 leu o bilhete de handoff ao vivo,
LOCAL e DOCKER).

## 4. O CONSERTO — desenho GRELHADO PELO DONO (2026-07-16)

Quatro judgment calls colhidos, com o racional de cada um:

1. **Escopo = os dois problemas, e eles são independentes.** (a) a issue passa a instruir quem
   trabalha (a causa) **E** (b) um run que não fez nada nunca mais fecha issue (a trava). Mesmo
   com (a), comando vazio segue possível por outra causa — sem (b) o board volta a mentir.
2. **`runtime_command` = só comando de script.** Hoje um campo tem dois significados: pedido pra
   IA (claude/codex) e comando de terminal (`process`, ex.: `mkdir -p .orq/preview && printf …`).
   Foi essa mistura que abriu o buraco. Pós-conserto ele serve só ao arquétipo `process`.
   **Consequência elegante: o `null` do `ApprovalService:348` deixa de ser bug e vira correto** —
   agente de IA não precisa do campo. Custo aceito: os agentes velhos da Teams Demo passam a ter
   esse campo ignorado (issues deles já DONE).
3. **O compositor carrega issue + estratégia.** `#N` + título + descrição + a estratégia do
   Planejador. Recusadas: "só a issue, o arquiteto repassa" (o arquiteto também não enxerga a
   estratégia — o buraco só andaria um degrau) e "pela memória R19-A" (mistura memória durável
   com briefing de issue, e reabre a fronteira C10.b fechada de propósito).
4. **O sistema anota o endereço da estratégia (migração V30).** O Planejador já diz o caminho no
   bloco `handoff`; o sistema passa a guardar num campo em vez de deixar só no texto do recado.
   Recusadas: convenção ditada (falha **silenciosa** se o CEO batizar diferente — o inimigo de
   sempre) e estratégia no banco (contraria o "índice-não-conteúdo" da ADR-0019; seria revisão de
   princípio, não conserto).

**Forma do conserto:** içar a composição do prompt **acima** do galho turno/issue em
`AdapterRunExecutor`. O seam já existe (`acceptsPromptAsCommand` + `PROMPT_ENV`, linhas 264-269);
falta só o compositor do lado da issue. **Não é reescrita — é completar uma simetria da qual o
código já tem metade.**

**O que decidiu a questão foi o verbatim do dono** (ADR-0037 §1, 2026-07-11): *"a abertura de uma
issue nada mais é do que uma conversation, só que uma **direcionada**"*. Se a issue é conversa
direcionada, ela **instrui**. O compositor ausente contradiz o modelo de domínio do próprio dono.
Por isso "derivar o prompt do `role`" e "CEO propõe campo de prompt" foram **recusadas**: viram o
agente num **robô de tarefa única** (refaria o mesmo texto na issue #2) e conflatam "quem você é"
(role) com "o que fazer agora" (issue).

**Ponta solta resolvida:** o report (ADR-0010/0019) **não muda** — o contrato é "report = saída
final do run concluinte", e segue valendo. Pedir report explícito no compositor é ajuste de
prompt, não arquitetura.

## 5. O QUE A PROVA PROVOU (pernas a–f: PASS)

Tudo com motor claude REAL, pela UI real (o dono waived a regra "escritas só pelo dono" e mandou
a faixa tocar; a issue foi criada pelo `NewIssueDialog`, não por curl).

- **(a) Issue órfã** `#1` (`47e6f6e3`) criada pela UI: `OPEN`, `assignee NULL`, R1. ✔
- **(b) Absorção — o roteiro anterior ESTAVA ERRADO.** O handoff mandava `POST /wakeups` citando
  o §3; mas o §3 diz *"sem wakeup automático **do arquiteto**"* (outro passo). O gatilho é o
  **§1**, **atômico com a criação** — provado: issue 19:01:27.959 → conversa .965 → wakeup .976 →
  run .980. **O `POST /wakeups` da perna (b) teria disparado um run concorrente.** ✔
- **(c) CREATE_TEAM (4ª ação)** — time `bffea35f` criado **já com `arquiteto_agent_id`**
  (`567ada39`) na mesma tx; approval `APPROVED` por BOARD com `racional_tipo=LOGICO`,
  **`racional_arrancado = t`**, `grill_rounds = 1`. O gate grelha o **dono** (ADR-0020) — o método
  do segundo cérebro fechando no banco. **Retoma automática do §5 confirmada** (run `ff8e5282`,
  bastão "o time existe; retome a #N", sem ninguém pedir). Absorção completa: #1 → Arquiteto,
  `OPEN` na mesa dele (§3); evento `ISSUE_ABSORVIDA` (ator AGENT, detail só-IDs). ✔
- **(d) Grill** — 3 rodadas, o Questionador **não aceitou o desenho do Planejador** (7 agentes →
  2, com racional: *"o gargalo é profundidade de pesquisa + síntese, não volume"*) e ancorou
  preço no valor, não em custo+markup. Racional de cargos ao vivo. ✔
- **(e) Plano A (D12) FUNCIONOU** — pedi `claude-code` no grill e a condição atravessou
  Q→P→E→gate: os cards nascem com `adapterType: claude-code` e o racional cita *"Motor claude-code
  para alinhar à condição dura do dono"*. O D12 não mordeu porque **o motor viaja no card, não no
  lote**. ✔
- **(f) A DOBRA DO P1 — FECHADA.** A via REJECT + RecusaKickback, que o P1 não exercitou:
  - Decision seq 3 `[REJECT HIRE_AGENT ×1]` com **`approval_id NULL` = `Anchor.NoLote`** →
    **mesma `fabricaDecisaoDoLote` do APPROVE**. R18-A agora provada nos dois lados.
  - `evidencia` = `"Lote (ADR-0035): 1 ato do dono, 1 alvos — …"` (mesma linhagem do P1).
  - **Kickback mudou o comportamento do CEO**: o motivo chegou verbatim e ele respondeu *"a recusa
    é de sequência, não defeito da estratégia … NÃO re-proponho o Estrategista neste turno … quando
    o levantamento chegar, re-proponho com esse achado como justificativa concreta. Nada
    silencioso"*. ✔

## 6. Achados v1.1 (nenhum quebra-prova)

1. **Vão ADR-0037 §7(8) × ADR-0030 (o mais importante).** O §7(8) declara: *"anti-loop = **freio
   humano por construção** (todo ciclo para-e-avisa→retoma passa pelo dono)"*. **Falso na
   prática**: o para-e-avisa esperava o dono e a **continuação de contexto retomou sem ele** —
   refez a triagem e duplicou o aviso na thread (run real de 30s queimado). O Planejador em run 2:
   *"o bastão não andou. Então re-emito os dois blocos"* — o board não andou **porque esperava o
   'sim'**, mas a continuação não sabe disso. A idempotência do §7 (`[gatilho <turnoId>]`) não pega
   porque re-emissão vem de **turno novo** = gatilho novo; e a guarda (3) só cobre o ramo "issue já
   tem dono". O `parkedForHuman` do #33 só estaciona em **kickback** — para-e-avisa não é kickback.
   Mesma assinatura do #33: duas ADRs corretas isoladamente deixando um vão. Convergiu sozinho
   (run 2 foi curto, não cruzou limiar).
2. **`HIRE_AGENT` não carrega time.** `ApprovalService:336-353` monta o `HireCommand` sem
   `teamId`; `assignAgent` só é chamado em `:411` (arquiteto do CREATE_TEAM) e em
   `AgentController:97` (porta manual). O gate cria o time, contrata o executor **que aquele time
   foi desenhado para ter**, e o executor **nasce fora dele** — medido: Pesquisador com `team_id`
   NULL. O §5 diz "Executores vêm DEPOIS … A3/A4 **intocados**" — e é por serem intocados que caem
   fora. Só o organograma manual conserta.
3. **`FILTRO_TIPOS` ficou stale.** `frontend/src/lib/approvals.ts:12-22` lista só 3 tipos
   (HIRE_AGENT, CREATE_ISSUE, WAKEUP_AGENT) e `loteHabilitado` exige filtro ≠ "todos". O gate
   cresceu para 7 ações (CREATE_TEAM/0037, SKILL_INSTALL_REQUEST/R12-A, GRADUATE_PRINCIPLE/F-C2).
   Efeito: a fila **lista um card em que não consegue agir** e a dica manda "escolha um tipo acima"
   para um filtro que não existe. **Não é bloqueador** — o `ApprovalThreadCard` (renderizado em
   `ceo-conversa.tsx:237`) decide individualmente e trata CREATE_TEAM.
4. **`você` em turno que o sistema escreveu.** `issue-conversa-section.tsx:44` chumba
   `<span>você</span>` em todo `ownerMessage` — o dono lê *"você: Absorção da #1 — protocolo da
   triagem [gatilho 82311ce1-…]"*, com UUID cru, sobre algo que nunca escreveu. Vale para os dois
   lugares (detalhe da issue e chat).
5. Cosmético: `..` duplicado no texto do para-e-avisa ("verificador de fontes.. Nada foi
   atribuído").

**Candidata v1.1 herdada do P1 (não enterrar):** aprovação em LOTE não dá feedback na UI.

## 7. Pendências

1. **A fatia do conserto** (§4) — desenho grelhado, pronto para kickoff. **Candidata #1 do
   roadmap de ajustes** (não bloqueia mais a declaração — o dono fechou o v1).
2. **Pernas (g)/(h)/(i)** — executa #N, caso B, corretiva com feedback no memoryFile (R19-A) —
   todas dependem do conserto. Confirmado no dado que **a R19-A só pode ser provada na
   corretiva**: a Decisão do REJECT de HIRE nasce **sem âncora de issue** (`issue_id` NULL —
   HIRE_AGENT não pertence a issue), enquanto a corretiva grava
   `.anchoredToIssue(corretiva).withEvidencia(verbatim)` (`CorrecaoDeEntregaRouter:133-145`).
3. **Sujeira de estado:** a **#1 está `DONE` incorretamente** (`prova-v1`, company-fixture). O
   time `bffea35f` existe com arquiteto; o Pesquisador existe **sem time**; `18db8641` REJECTED.
4. **Drift do roadmap (herdado):** `docs/roadmap-interno.md:184` ainda diz "main fd3be27 · piso
   736 · ZERO fatia de código restante" — **agora duplamente falso** (o #33 landou depois, e esta
   fatia reabre código). `roadmap:92` cita `IssueService.java:108` mas o `tryBlock`+`ISSUE_BLOCKED`
   mora na `:182`. "park-for-human" está sobrecarregado (ADR-0011 recovery × fix do #33).

## 8. Ambiente (validado 2026-07-16)

- `main` LOCAL = **`83c5fe9`**, limpo. Piso **738/294**.
- `:8080` UP (PID 46677, `java -jar` da raiz do workspace) · `:3000` UP (`next dev`,
  `.env.local` aponta pro `:8080`) · db `agente-orquestrator-db-1` healthy.
- **⚠️ NÃO use `preview_start {name:"frontend"}`** — o `.claude/launch.json` é tracked e força
  `BACKEND_ORIGIN=:18081` (backend-vendo, morto). Use `preview_start {url:"http://localhost:3000/…"}`.
- **Gotcha do browser pane:** `read_page` devolveu árvore stale e as coordenadas do screenshot
  saem de sincronia com o viewport. O caminho confiável: JS (`focus()` + `.click()`) com guarda de
  conteúdo antes de agir — clicar por pixel gerou ação no card errado.

### IDs da prova
company `d2b0bcff-f82e-4364-b8d1-85b7236cb902` (`prova-v1`) · issue #1 `47e6f6e3-945b-46e1-9784-5d2304c64793`
· conversa `307051c4-ebef-4ba9-9c6a-8b24d3cf2266` · time `bffea35f` · arquiteto `567ada39`
· run do bloqueador `e67ff7dd-d4bb-47dd-b105-08bed3007cd2`.
`X-Board-User: b0a7d000-0000-4000-8000-000000000001` · SQL read-only:
`docker exec agente-orquestrator-db-1 psql -U orquestrador -d orquestrador -c "…"`.

## 9. Erros desta faixa (registro honesto)

- **Li estado transitório como terminal.** Consultei o banco no meio do voo e chamei um turno de
  "órfão travado". Pior: mandei um subagente investigar partindo dessa premissa falsa e ele
  construiu uma cadeia causal elaborada (adoção de issue no `WakeupService`, requeue do
  `LeaseReaper`, "turno envenena o agente inteiro") que **o dado já contradizia** (`attempt=1`,
  `issue_id` NULL). Morreu no log. **Lição: laudo de subagente sobre premissa minha não é
  evidência — o dado é.**
- **Chamei de bug o polling de 5s** do chat (o grill "não aparecia"). Era latência da UI.
- **Quase declarei o CREATE_TEAM "indecidível"** por causa do `FILTRO_TIPOS` — sem antes checar a
  outra superfície (o card da thread, que decide individualmente).

## 10. PRÓXIMA FASE — descoberta de bugs (definida pelo dono, 2026-07-16)

**Escopo verbatim:** *"essa proxima fase sera de descovery de bugs, voce ira planejar e executar
todos os testes das aplicacao usando o que esta descrito como funcionalidade nas ADRs e comprovar
o que esta funcionando e o que nao esta, apartir dai criaremos um roadmap de ajustes"*.

**Método:** as ADRs viram a **especificação**. Cada funcionalidade que uma ADR descreve é testada
**ao vivo** (motor real, UI real) e classificada em **funciona / não funciona**. O resultado vira
o roadmap de ajustes.

**Esta sessão é a prova de conceito do método** — e o resultado justifica a fase: a prova
ponta-a-ponta de **uma** fatia achou **1 bug grave + 4 achados** em ~4h. Todos por pisar caminho
que teste unitário não pisa. **Piso 738/294 verde não viu nenhum deles.**

**O que esta sessão ensina sobre COMO rodar a fase (lições caras, não repetir):**

1. **A ADR afirma; o dado decide.** Duas ADRs *declaram* invariantes que o dado desmente:
   ADR-0037 §7(8) diz *"anti-loop = freio humano **por construção**"* (falso: a continuação da
   ADR-0030 retoma sem o humano) e §5 promete "todo time nasce com arquiteto" (verdade) mas os
   executores nascem **fora** do time. **Toda afirmação de invariante numa ADR é uma hipótese
   testável** — é ali que os bugs moram.
2. **Bug mora no cruzamento, não dentro da peça.** Os 3 achados sérios têm a MESMA assinatura:
   peças corretas isoladamente compondo um buraco (#33 = 2 contadores que só viam o próprio tipo;
   §7(8)×0030; e o bug #1 = gate + no-op verde + settleAfterRun, cada um defensável sozinho).
   **Priorizar teste de FRONTEIRA entre ADRs**, não de ADR isolada.
3. **O caminho que o fixture nunca pisa é onde o bug vive.** O bug #1 sobreviveu ao v1 inteiro
   porque **todo smoke fabricava o agente à mão**. Regra: para cada funcionalidade, perguntar
   *"quem produz esse insumo no mundo real?"* — e testar POR ESSE produtor, não por fixture.
4. **Não confiar em snapshot de sistema em voo.** Li estado transitório como terminal e um
   subagente construiu teoria inteira sobre a premissa falsa (§9). Esperar o sistema **parar**
   (0 runs ativos E 0 turnos PENDING) antes de julgar.
5. **Ambiente:** ver §8 (host `java -jar`; o browser pane mente — usar JS com guarda de conteúdo).
