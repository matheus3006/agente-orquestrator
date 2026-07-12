# Handoff — Grill do laudo motor-nativo (R5-A, 2026-07-11) — FECHADO

> **Produto da faixa R5-A da rodada 5.** Grill G1–G7 conduzido com o Matheus sobre
> `docs/laudo-motor-nativo.md` (R4-C, untracked). Sessão de grill: zero código, zero commit —
> este arquivo nasce UNTRACKED; quem o commita é a 1ª execução derivada. As decisões abaixo
> REORDENAM a Frente D do v1. Snapshot de referência: main `713a3f7` (piso 523/270).
> **R5-B (fix R6 — gramática do resume codex, `codex exec resume <id>`) corre em paralelo e é
> PRÉ-REQUISITO das fatias de D1/D3 no lado codex** — não foi decisão deste grill (bug shippado
> do ADR-0030, emitido pela sessão de controle antes do grill).

**Norte do dono (verbatim, 2026-07-11):** *"nao parece que estamos usando o que esses agentes
nos proporcionam nativamente, pelo contrario sinto que estamos regredindo a qualidade deles.
[...] nao deveria ser tao dificil."*

**Método:** perguntas uma a uma, com recomendação+porquê (base: laudo §4). Nas cinco indecisões
(G2, G3, G4, G6, G7) a decisão foi guiada por análise estruturada (skills
`human-architect-mindset` + `system-design`): fato de domínio → modos de falha → constraints →
teste de lealdade → síntese. **Padrão que emergiu das 7 decisões:** nativo TOTAL onde a
superfície é estável (D1/D2/D3); nativo ESTÁVEL onde a superfície é experimental (D4);
REQUISITO EMBUTIDO em vez de fatia nova onde o escopo é pequeno (D6/D7); MEDIR antes de
desenhar onde a evidência falta (D5).

---

## §0 O fluxo ideal do dono (verbatim, 2026-07-11) — norte da fatia F-N1

Colhido no G1, palavras dele:

> *"Seguimos conversando conforme estamos aqui, discutindo uma solucao, ou uma ideia minha
> atraves do /grill-me e /grill-with-docs, e entao chegamos ao um ponto onde ja fechamos o que
> quero e o que deve ser feito (similar ao que seria o seu Plan mode) e entao voce pega essa
> conversa tivemos e extrai dela as perguntas e respostas que tivemos, as nuances da conversa,
> onde eu te questionei, o que voce me respondeu, o que voce me perguntou e o que eu te
> respondi e passa para o CEO Planejador pegar essa informaçao e transformar nas tasks. Ai um
> ponto importante, no momento que fizermos isso, voce pode iniciar um novo chat, para ai sim
> começarmos a discutir, porem talvez seja interessante guardarmos esse registro de conversa em
> um banco de dados dessa empresa (como um arquivo .txt, porem com os atores da conversa bem
> sinalizado [...] na sequencia da conversa) e entao colocamos um ID sequencial, caso um dia eu
> queira visitar novamente o que aconteceu nos temos o registro."*

Mapeamento: (1) conversa viva = sessão nativa contínua por conversa (D1); (2) extração
fechou-entregável → Planejador = trilho Q→P existente (ADR-0036), que com D1 sai de quem viveu
a conversa inteira e com D2 sai validada por schema; (3) "novo chat depois" = escopo por
conversa; (4) o registro com atores em sequência **JÁ EXISTE** (`conversation_turn`:
`owner_message`/`ceo_reply`, V16; texto preservado pela retenção F.15/ADR-0029). Peça faltante:
**ID sequencial de conversa** → candidata v1.1 (§5), sem discussão (anti-círculo).

---

## §1 Decisões D1–D7

### D1 (G1) — Continuidade nativa de sessão POR CONVERSA: **SIM, com a dieta do prompt na MESMA fatia**

**Decisão:** religar o `--resume` nativo no caminho normal dos turnos de conversa (CEO/personas),
escopado à conversa (fechou o entregável/conversa → sessão morre; assunto novo = sessão nova).
Na MESMA fatia, emagrecer o `composePrompt`: turno 2+ manda só a mensagem nova + delta do
estado vivo (senão paga contexto em dobro — a sessão lembra E o prompt repete). O loop ADR-0030
NÃO morre: rebaixado a rede de segurança de quando a janela enche (destila e recomeça).

**Racional:** é a regressão-mãe (laudo R1) — o raciocínio do turno morre no fim do run e o
modelo herda só o destilado (sintoma vivo: o chute "encaminho pro Feynman", caso B). O plumbing
existe INTEIRO nas duas pontas: `agent_session`/V16 + `Context.priorSession` + argv pronto; a
mudança central é ler `AgentSessionStore.currentHandle` onde hoje `AdapterRunExecutor.java:252-255`
passa `priorSession=null` literal. O comentário da V16 já dizia: *"FIAÇÃO PRONTA, FEATURE
DESLIGADA: o --resume por-Conversation religa lendo daqui"*. O fluxo ideal do dono (§0) é
exatamente este desenho.

**Condições carimbadas:** (a) dieta do prompt inseparável (mesma fatia); (b) pré-req codex =
R5-B merged; (c) semântica de retry a provar com teste: handle salvo = último gravado com
sucesso; run que falha antes de gravar → retry resume o handle anterior (perde só o turno
falhado, nunca a conversa) — o CLI rotaciona o session-id a cada uso (`AgentSession.java:61`);
(d) requisito D6 embutido (tetos nativos de subagente — ver D6).

### D2 (G2) — Saída estruturada nativa: **SIM em TODOS os turnos (opção A), em 2 etapas**

**Decisão:** migrar os 5 blocos tipados (```proposta```/```handoff```/```needs-grilling```/
```correcao```/```absorcao```) E as respostas de conversa para schema nativo (codex
`--output-schema <arquivo>` · claude `--json-schema` + `--output-format json`). Turnos de
conversa usam schema `{resposta: string, blocos: []}`. Implantação em 2 etapas DENTRO da fatia:
etapa 1 = turnos estruturalmente de entregável (risco baixo, valida a mecânica nos 2 motores);
etapa 2 = turnos de conversa, com a prosa provada ao VENDO com o dono olhando antes de fechar.
Se a etapa 2 revelar degradação intolerável, recuo informado para a etapa 1 (decisão com
evidência, não medo antecipado).

**Racional (o que desfez a indecisão A×B):** (1) **o backend não sabe de antemão qual turno
carrega entregável** — quem decide é o modelo, no meio do turno; o caso C aconteceu num turno
"de conversa" (B protegeria só turnos pré-rotulados e deixaria a regex viva exatamente onde
mordeu); (2) A mata as DUAS famílias de parse frágil que já deram bug em produção: shape de
bloco (caso C, ADR-0036) e extração de reply last-wins (narração do codex empilhada, fix
`CodexStreamParser`); (3) um contrato só — blocos futuros nascem de graça, cintos/anti-silêncio
dos PRs #12–#14 viram removíveis; (4) modo de falha: formato errado vira IMPOSSÍVEL ou
barulhento, nunca descarte silencioso. B não é destino alternativo, é no máximo parada no
caminho de A → decidiu-se o destino; o ritmo é das etapas.

**Gotcha carimbado:** claude <2.1.205 ignora schema INVÁLIDO em silêncio → o backend valida o
schema antes de enviar (e o shape na volta).

### D3 (G3) — Executores de task longa: **resume nativo POR ISSUE (opção A); fatia "handoff pros executores" SUPERSEDIDA**

**Decisão:** run N+1 do heartbeat continua a sessão do run N, escopada à Issue (fechou a Issue →
sessão morre). A fatia congelada da Frente D "handoff pros executores" (estender o pipeline de
bilhete do CEO) **MORRE SUPERSEDIDA por D3**. Bilhete `.orq/handoff/` fica APENAS para troca de
agente (executor A → executor B, claude → codex — sessão nativa não atravessa agentes).
**Requisito obrigatório: fallback barulhento** — resume falhou (handle inválido, sessão sumida)
→ fresh start do workspace + evento visível (espírito `RUN_LEASE_LOST`), NUNCA silêncio.

**Racional (o que desfez a indecisão A×B):** a redundância de B não compra nada que já não
exista — (1) registro durável/auditável já é dever de protocolo do Executor (dossiê apendado
por turno, §ADENDO dos fluxos A/B); (2) rede de janela = compactação nativa (headless nos 2
motores, verificada); (3) rede de sessão perdida = o fallback natural É o baseline de hoje
(re-derivar do workspace). Assimetria com o CEO é princípio, não desleixo: a verdade-fonte do
executor é o WORKSPACE (durável, reconstruível); a do CEO é a CONVERSA (irrecuperável) — rede
cara para dado irrecuperável, fallback barato para dado reconstruível. B ainda criaria modo de
falha novo (3ª memória divergindo da sessão viva) e custaria ~a duplicação das 6 classes do
ADR-0030.

**Nota de plumbing:** Issue já é conversa direcionada (V27, `issue.conversation_id`) — o escopo
por Issue provavelmente reusa a chave `(agent_id, conversation_id)` do `agent_session` sem
migração; a fatia confirma.

### D4 (G4) — Interrupção mid-run: **kill + resume nativo (opção A); processo-vivo/steering = candidata v1.1**

**Decisão:** o botão "parar" do v1 = matar o processo (sessão sobrevive no JSONL, D1 garante) →
dono digita a correção → turno novo resume a mesma sessão com a correção. Granularidade de
turno. A fatia ABRE com uma **sonda** (molde S2 do G3): kill no meio de uma tool call nos DOIS
motores + prova de que o resume retoma são — o laudo §5 deixou isso sem prova empírica.
Transporte do stream vivo (SSE/WebSocket; hoje REST-poll `RunStepsController`) na MESMA fatia.
Processo-vivo/steering fino (claude `--input-format stream-json` · codex `app-server`
`turn/steer`+`turn/interrupt`) = **candidata v1.1 gateada na estabilização do app-server**.

**Racional (o que desfez a indecisão A×B):** as duas opções são nativas; a diferença é degrau
de profundidade. (1) Domínio: a cena é supervisão corretiva — granularidade de turno resolve; o
pedaço de turno que o kill descarta é barato (turno trivial ~8,5s) e a sessão inteira sobrevive
nos 2 desenhos. (2) `app-server` é EXPERIMENTAL no tag pinado, e o mesmo tag prova churn real
(`codex proto` REMOVIDO); recibo de interrupt do claude é pós-pino (2.1.205+). Apostar fatia
estrutural em superfície em demolição contradiz a defesa do pino (ADR-0014, validada pelo aviso
do `--bare`). (3) A mantém `ExecutionTarget` processo-por-run e o lease recém-estabilizado
(PR #9) intocados; B viraria supervisor de daemon + redesenho de lease. (4) A é o pedaço de
baixo DE B — quando o app-server estabilizar, o upgrade troca só o mecanismo de entrega da
correção; endpoint, board, semântica de parar e SSE ficam. Zero retrabalho.

### D5 (G5) — Contabilidade off-book: **MEDIR ANTES de desenhar (opção A)**

**Decisão:** sonda de 1 run POR MOTOR (molde S2): run que comprovadamente spawna N subagentes;
comparar o `usage` do result com o gasto real visível no stream. O desenho da fatia off-book
fica GATEADO no resultado: usage já inclui subagentes → fatia míngua para "rotular o que já
chega"; não inclui → capturar pelos canais nativos prontos (claude: hooks
`SubagentStart/Stop`/`PostToolUse` com handler http OU `--include-hook-events` no stream que o
parser já consome; codex: `collab_tool_call` que JÁ chega no `--json` + OTEL nativo). Nenhum
proxy de rede próprio em nenhum cenário.

**Racional:** a resposta muda o TAMANHO da fatia inteira e a sonda custa uma tarde; desenhar
assumindo o pior caso arriscaria reimplementar até o medidor.

### D6 (G6) — Enforcement fino: **SIM, com prioridade EMBUTIDA (requisito de aceite, não fatia)**

**Decisão:** o enforcement MÍNIMO entra como **requisito de aceite das fatias que ampliam
autonomia**, não como fatia separada. Regra carimbada: **fatia que amplia autonomia não shippa
sem o teto correspondente.** Escopo mínimo: (1) teto nativo de subagentes — codex `[agents]
max_threads`/`max_depth` via `-c` + deny rules `Agent(...)`/`--disallowedTools` no claude —
embarcado nas fatias de D1 (CEO) e D3 (executores); (2) skills read-only por hook `PreToolUse`
— carona na fatia que tocar materialização/hooks ou follow-up cirúrgico (carrega também o cinto
do D7). Enforcement AMPLO (além do mínimo) segue sem pressa → candidata pós-v1.

**Racional (o que desfez a indecisão A×B):** a evidência "a prosa segurou até hoje" vem do
regime de RE-INJEÇÃO por turno — que o próprio D1 elimina por design (dieta do prompt) e a
compactação dilui; evidência do regime velho não transfere. E o estouro de delegação hoje é
DUPLAMENTE invisível: prevenção em prosa + gasto off-book (nem no ledger aparece — G5). Porém B
como fatia paralela colidiria nos MESMOS arquivos (`ClaudeArgv`/`CodexArgv`) das fatias D1/D2 e
queimaria um slot dos 2-em-voo. Síntese: prioridade de B (gate) com custo de A (zero slot,
zero colisão — quem já mexe no argv embarca as flags).

### D7 (G7) — Segurança do home-volume: **ACEITAR no v1, REFORÇADO (gate bloqueante + cinto de carona)**

**Decisão:** (1) ACEITAR no v1 com nota na ADR-0016 — e a nota é **GATE BLOQUEANTE, não
lembrete**: *nenhum tenant externo entra ANTES da fatia de isolamento shippada* (P0 automático
no onboarding, não depende de memória); (2) **carona no hook do D6**: `PreToolUse` nega acesso
aos diretórios de sessão dos motores (`~/.claude/projects`, `~/.codex/sessions`) fora do
próprio workspace — cinto contra acesso acidental e contra o caminho FÁCIL de prompt injection
via web da delegation (custo marginal ~zero; não é isolamento real, é elevar a barra); (3)
fatia de isolamento = **candidata v1.1 já com rascunho de desenho**: config-dir por company
(`CLAUDE_CONFIG_DIR`/`CODEX_HOME`) + solução de compartilhamento de credenciais (as creds moram
no config-dir — o motivo do volume compartilhado existir; symlink sobrevivendo a refresh de
token, ou equivalente) + reset único de handles (mudar config-dir muda onde as sessões moram),
**sequenciada DEPOIS de D1/D3 estabilizarem** (mesmos arquivos: `EngineContainer`, argv,
executor).

**Racional (o que desfez a indecisão A×B):** vítima hoje inexiste (todas as companies são do
dono; prêmio = o próprio material de dev dele) — MAS o vetor de injection vale hoje (delegation
tem web com `bypassPermissions`) e D1/D3 aumentam o valor do que vaza (JSONLs viram memória
viva). B agora custaria redesenho de pedaço da ADR-0016 (credenciais) + colisão frontal com
D1/D3 + slot, para proteger ativo sem vítima. A síntese pega o núcleo correto da hesitação
(não deixar o vetor sem NADA) pelo custo de uma deny rule.

---

## §2 Frente D — o que morre, o que muda, o que segue

| Fatia congelada | Destino | Por quê |
|---|---|---|
| Handoff pros executores | **MORTA — supersedida por D3** | Resume nativo por Issue substitui o pipeline de bilhete; bilhete fica só para troca de agente. Registrado como redesenho do congelado, não escopo novo. |
| Interrupção mid-run / streaming | **MUDA DE DESENHO (D4)** | Vira: sonda kill-mid-tool-call → kill+resume + endpoint de interrupt + transporte SSE do stream que JÁ é vivo. Não constrói checkpoint próprio. |
| Contabilidade off-book | **MUDA DE DESENHO (D5)** | Gateada na sonda de usage; nos 2 cenários usa canais nativos, nunca proxy próprio. |
| Auto-kickback amplo | **SEGUE, resequenciada** | DEPOIS de D2: com schema, "entregável detectado" vira campo validado — o kickback fica trivial, sem cinto/anti-silêncio por tipo de bloco. |
| Propose-then-commit de molde | **SEGUE, resequenciada** | Mesmo insumo: proposta de molde = saída estruturada validada (D2 primeiro). |
| Agente-como-tool | **SEGUE como estava, com nota** | O desenho da fatia COMEÇA escolhendo entre subagentes nativos × MCP (`codex mcp-server` já dá `codex`/`codex-reply` com continuidade por threadId, de fábrica) — não desenhar transporte novo. Decisão fica para o grill da própria fatia. |

---

## §3 Fatias derivadas — escopo preliminar, arquivos prováveis (matriz de colisão), ordem

> A EMISSÃO é da sessão de controle (2-em-voo). Escopos abaixo são preliminares — cada fatia
> pode ganhar grill curto próprio no kickoff. A 1ª fatia executada COMMITA este handoff (e
> avalia commitar o laudo).

**F-N1 — Continuidade nativa por conversa (D1) + dieta do composePrompt + tetos D6-CEO.**
Escopo: ler `currentHandle` no caminho normal de turno de conversa; dieta do prompt (turno 2+ =
mensagem nova + delta estado vivo); ADR-0030 rebaixado a rede (dial de compactação nativa
avaliado junto — `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE` / `model_auto_compact_token_limit` ao nível
da regra 50-60%); semântica de retry de handle com teste; tetos de subagente (codex `-c` +
claude deny rules) como requisito de aceite. ADR nova (emenda 0002/0018/0030). Prova VENDO:
conversa de 3+ turnos com memória viva demonstrada + teto provado.
Arquivos prováveis: `AdapterRunExecutor.java`, `ConversationTurnCoordinator.java`,
`AgentSessionStore.java`, `ClaudeArgv.java`, `CodexArgv.java`, `ContextHandoffLoop.java`,
`HandoffThresholdDetector.java`, testes dos pacotes `execution/`+`handoff/`.
Pré-req: **R5-B merged**.

**F-N2 — Resume de executores por Issue (D3) + tetos D6-executores.**
Escopo: mesma plumbing de F-N1 aplicada a run de Issue/heartbeat; escopo de sessão por Issue
(provável reuso da chave via `issue.conversation_id`, V27 — confirmar); fallback barulhento
(evento novo tipo `RUN_SESSION_LOST`); bilhete restrito a troca de agente. Emenda de ADR
(0030 + a nova de F-N1).
Arquivos prováveis: `AdapterRunExecutor.java`, `AgentSessionStore.java`, ciclo de heartbeat,
testes. **Colide frontalmente com F-N1 (mesmos arquivos) → sequencial imediata a F-N1 ou MESMA
faixa** (recomendo mesma faixa se o apetite couber: é a mesma mudança em dois call-sites).

**F-N3 — Saída estruturada nativa (D2), 2 etapas.**
Escopo etapa 1: schema nos turnos estruturalmente de entregável; validação de schema no backend
(gotcha claude <2.1.205); shape na volta validado. Etapa 2: conversa via `{resposta, blocos[]}`;
reply passa a ser o campo (mata o last-wins); prosa provada ao VENDO com o dono. Depois:
remoção gradual dos cintos/anti-silêncio (PRs #12–#14) em fatia de faxina própria. ADR nova.
Arquivos prováveis: `ClaudeArgv.java`, `CodexArgv.java`, `ClaudeStreamParser.java`,
`CodexStreamParser.java`, `ProposalScanner.java`, `PersonaHandoffScanner.java`,
`PersonaBatonRouter`, `ConversationTurnCoordinator.java`, `RunFinalOutput`, testes.
**Colide com F-N1/F-N2 (argv, coordinator, parsers) → DEPOIS delas.**

**F-N4 — Interrupção + SSE (D4).**
Escopo: sonda kill-mid-tool-call (2 motores) ABRE a fatia; endpoint de interrupt (kill
controlado + estado de run INTERRUPTED); retomada com correção do dono; transporte SSE dos
steps (substitui/complementa o REST-poll de `RunStepsController.java`); front do board (botão
parar + stream vivo). ADR nova.
Arquivos prováveis: `AdapterRunExecutor.java`/`ExecutionTarget`, controller novo + SSE,
`RunStepsController.java`, lifecycle de run, front (`board-shell`/feed), testes.
Pré-req: F-N1 (sem sessão contínua, kill perde o turno). Colisão média com F-N1/F-N2 no
executor → depois delas; a SONDA pode rodar antes, em paralelo (read-only + descartável).

**F-N5 — Sondas G5 (usage × subagentes) → desenho off-book.**
Escopo: sonda 1-run por motor em instância descartável; laudo curto; desenho da fatia off-book
gateado no resultado. **Zero colisão (não toca produto) → pode rodar em paralelo a qualquer
momento, inclusive já.**

**F-N6 — Hook PreToolUse mínimo (D6 skills read-only + D7 cinto de sessão).**
Escopo: materialização de hooks (`settings.json` claude / `hooks.json` codex — atenção ao
gotcha de trust do codex: `--dangerously-bypass-hook-trust`) negando Write/Edit nos paths de
skills para personas e leitura dos dirs de sessão fora do workspace. Pequena; carona ou
follow-up de F-N1/F-N2 (mexe na materialização que essas fatias já tocam).

**Ordem recomendada (respeitando 2-em-voo e a matriz):**
1. **F-N1** (→ **F-N2** na mesma faixa ou sequencial imediata) — ataca a regressão-mãe; gate: R5-B merged.
2. **F-N5 em paralelo** desde já (zero colisão) — destrava o desenho off-book enquanto F-N1 roda.
3. **F-N3** depois de F-N1/F-N2 (colisão argv/coordinator/parsers).
4. **F-N4** depois de F-N3 (a sonda de kill pode adiantar em paralelo).
5. **F-N6** como carona/follow-up de F-N1/F-N2.
6. Então as resequenciadas da Frente D: auto-kickback + propose-then-commit (pós F-N3),
   off-book (pós F-N5), agente-como-tool (grill próprio).

---

## §4 O que ficou ABERTO e por quê

- **Sonda kill-no-meio-de-tool-call** (abre F-N4): comportamento do resume pós-kill nas versões
  pinadas nunca foi provado empiricamente (laudo §5 — a faixa era read-only).
- **Sonda usage × subagentes** (F-N5): idem; decide o tamanho da fatia off-book.
- **Semântica de retry do handle** (F-N1): desenho proposto (handle = último gravado com
  sucesso; falha antes de gravar → resume o anterior) a PROVAR com teste.
- **Prosa da etapa 2 do D2** (F-N3): provada ao VENDO com o dono; recuo informado para a etapa
  1 se degradar.
- **Escopo de sessão por Issue reusa `(agent_id, conversation_id)` via V27?** (F-N2): provável
  sim; confirmar no código antes de migrar qualquer coisa.
- **Agente-como-tool**: escolha subagentes nativos × MCP fica para o grill da própria fatia.
- **Dial da compactação nativa** (F-N1): calibrar o disparo ao nível da regra de ouro 50-60% e
  decidir a relação com o gatilho do bilhete (rede) — detalhe de implementação com decisão
  dentro da fatia.

## §5 Candidatas v1.1 registradas no caminho (anti-círculo — sem discussão)

- **ID sequencial de conversa (#N) + revisita fácil do registro** (do fluxo verbatim do dono,
  §0; `conversation_turn` já guarda tudo — falta o ID e a superfície de consulta).
- **Processo-vivo/steering fino** (claude `--input-format stream-json` · codex `app-server`) —
  gate: `app-server` sair de experimental; upgrade evolutivo sobre F-N4.
- **Enforcement fino AMPLO** (além do escopo mínimo de D6).
- **Fatia de isolamento do home-volume** (rascunho no D7; **gate bloqueante: nenhum tenant
  externo antes dela** — carimbar na ADR-0016 no fecho documental).

## §6 Regras de contrato desta faixa (cumpridas)

Zero código, zero commit; este handoff e o laudo seguem untracked (1ª execução derivada
commita); untracked de contrato intocados (`controle-paralelismo.md`, `laudo-lease-adr0005.md`,
`handoff-auditoria-doc-codigo-2026-07-11.md`, `laudo-motor-nativo.md`); roadmap não tocado (o
fecho documental cuida, incluindo a nota/gate da ADR-0016 e a nota de defesa do pino).
