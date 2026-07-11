# ADR-0036: Caso C direto — contratação pedida ao balcão + anti-silêncio do canal de proposta

**Status:** Aceito (2026-07-11) · grill R4-A fechado no mesmo dia (4 decisões do dono + carona)

## Contexto
Na prova viva da faixa R3-A (Demo Fixtures, conversa "Prova R3-A"), o dono pediu contratação
DIRETO ao Questionador. O Q emitiu o bloco ` ```proposta ` ELE MESMO, com shape inventado
(`{"tipo":"contratacao","papel":…}`): o formato canônico HIRE_AGENT só viajava no blocoExecutor
do prompt, que o Q nunca vê — caso A tinha trilho (dossiê→sim→handoff), caso B tinha trilho
(` ```correcao `), **caso C não tinha trilho nenhum**. O `ProposalScanner` descartou fail-soft
(sem nem um WARN) e o dono ficou olhando um **gate vazio**: o protocolo T3 não aconteceu num
fluxo que é elo central do produto. A premissa da ADR-0018 — *"malformado é descartado; o texto
segue visível na thread para o dono cobrar"* — caiu: a olho nu não se distingue proposta válida
de inventada.

Dois furos latentes descobertos na exploração: (i) o `ProposalScanner` rodava no settle de TODO
turno sem filtro de persona — um Q que acertasse o shape canônico ENCHERIA o gate sem estratégia
do Planejador (A2/A3 furados por dentro); (ii) o scanner aceitava cerca em QUALQUER coluna — um
motor ecoando o exemplo indentado do prompt (JSON válido, `acao: HIRE_AGENT`, placeholders
`<nome>`) criaria Approval fantasma.

Norte do dono (verbatim 2026-07-11): *"ceo fazer um racional de quais cargos deveriam ocupar
essas posicoes e entao a partir dai gerar o pedido de contratacao desses funcionarios … nao
deveria ser tao dificil."* — racional primeiro (Planejador), pedido depois (Executor): a esteira
Q→P→E que já existe É a resposta; o que faltava era o trilho.

## Decisão

### 1. Trilho do caso C no prompt do Questionador (prompt-only)
`blocoQuestionador` ganha o parágrafo que faltava: pedido de contratação EXPLÍCITO → **NUNCA**
emitir ` ```proposta ` (protocolo é do Executor, pós-estratégia); registrar o pedido no dossiê
(palavras EXATAS do dono) e emitir `handoff` para o `planejador` **NO MESMO turno** — o pedido
explícito **JÁ É o "sim"** do confirma-antes (D5 existe para fechar grelha longa de business
novo; re-perguntar "posso seguir?" num pedido direto é a fricção que o norte condena). Lacuna
GRANDE (chutaria o negócio errado) → UMA pergunta antes.

### 2. Cinto de emissor no canal de proposta
` ```proposta ` → Approval **só quando o emissor é o EXECUTOR ou o CEO legado** (persona nula —
caminho pré-Fatia 2 intocado). Questionador/Planejador emitindo (canônica ou inventada) =
descarte com WARN + aviso. Espelho exato da restrição de emissor do ` ```correcao ` (ADR-0034).
Fecha o furo simétrico: proposta sem estratégia nunca chega ao menu.

### 3. "Para o sistema, só existe cerca em COLUNA 0" — simetria dos scanners
O `ProposalScanner` alinha ao irmão `PersonaHandoffScanner`: cerca indentada é **citação**
(não propõe, não conta descarte, não avisa); cerca na margem é **emissão**. Mata o Approval
fantasma do eco de exemplo. O prompt do Executor declara a regra ("indentado é citação, não
emissão").

### 4. Anti-silêncio: descarte CONTADO + WARN + aviso VISÍVEL na thread
O scanner devolve `Scan(propostas, descartadas)`; todo settle com descarte (shape fora do
contrato OU emissor barrado) posta o `ProposalDescarteNotice`: turno **ANSWERED visível, sem
nenhum Run novo** — o espelho exato do corte "Bastão parado" (ADR-0031 §8). Voz = o balcão
(Questionador; fallback: o agente do turno). Idempotente sob settle reexecutado (marcador
`[gatilho <turnoId>]`, padrão do caso B). A régua M9 **pula** o aviso (não é "o dono de volta"
— senão needs-grilling + proposta quebrada na mesma resposta girariam para sempre).

### 5. Carona (achado do caso B): a prosa não nomeia destinatário
No trilho do ` ```correcao `, o Questionador confirma o encaminhamento **SEM nomear time/agente**
(cita só a #N) — na prova do caso B a prosa do opus chutou *"encaminho pro Feynman"* enquanto o
registro determinístico roteou, correto, à arquiteta. Zero palavra de LLM em registro (D3/D6).

## Alternativas rejeitadas
- **Formato canônico HIRE_AGENT também no blocoQuestionador** — fecharia o gate vazio
  institucionalizando o furo simétrico: proposta sem estratégia, racional improvisado no menu,
  A2/A3 mortos; e contradiz o próprio norte (racional→pedido).
- **Backend forçar a rota Q→P→E detectando "pedido de contratação"** — o backend interpretando
  prosa viola o princípio pinado (ADR-0031 §6: "o backend nunca interpreta a prosa") e seria
  mecanismo novo e frágil.
- **Descarte só com WARN no log** — o dono não lê log; o caso C teria acontecido igual (a
  necessidade dele, verbatim: "eu preciso ver o que está acontecendo em meu sistema").

## Ressalvas honestas
- **Proposta REAL emitida indentada fica invisível** (sem Approval e sem aviso) — aceito no v1:
  cerca em coluna 0 é o formato natural de markdown dos motores (as 27 propostas da prova viva
  da Fatia 2 vieram todas na margem), e o prompt do Executor agora declara a regra.
- **Textos do aviso e do trilho: propostos, implementados, reversíveis** (precedente dos textos
  M6 do caso B) — veto do dono pendente sobre a redação exata.
- A skill `being-ceo` (corpo no banco, R3-A) descreve os casos A/B com trilho e o C só como
  "(C) contratação pedida explicitamente" — o contrato único é o INLINE do prompt (a própria
  skill manda seguir "de lá"); sincronizar o corpo da skill é operação de DADO, fora deste
  código (registrado no handoff da faixa).

## Relaciona
ADR-0018 (emendada: o descarte fail-soft deixa de ser SILENCIOSO; forward-ref lá) · ADR-0031
(emendada: trilho do caso C no prompt do Q; cinto de emissor irmão do §9; forward-ref lá) ·
ADR-0034 (precedentes: restrição de emissor + idempotência `[gatilho]`) · ADR-0033 (a #N que a
carona cita) · ADR-0030 (o precedente do turno-aviso ANSWERED sem run).
