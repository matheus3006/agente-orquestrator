# ADR-0044: Validador auxiliar de planos — o local confere as checagens do molde e empurra os buracos ao próprio arquiteto

**Status:** Aceito (2026-07-18, execução da faixa R28-B — Uso B, fatia B-ii) · desenho de contrato
`docs/desenho-spring-ai-verificador-auxiliar.md` §2 (Uso B), §3 (Invioláveis, com a EMENDA ao #4),
§5 (a prova) · grill do dono de 2026-07-18 · migração **V33** (`V33__plan_validation.sql`) ·
medição empírica `docs/medicao-validador-qwen.md` · **abre, de forma escopada e atrás de gates, o
gate de influência-no-run que a ADR-0043 §3 (`:62-63`) mantinha fechado** — e só ele: o pacote de
contexto determinístico segue intocado.

## Contexto

O problema, verbatim do dono: o plano é **variável** — depende da escrita de cada arquiteto — e o
que interessa validar é se ele **de fato seguiu as skills** daquele papel. Um modelo fraco não
julga isso. A técnica que torna um modelo fraco útil aqui é **não pedir opinião**: quebra-se
*"seguiu as skills?"* (julgamento difícil) numa lista de **checagens binárias e factuais**
(*"o plano aponta o seam?"*, *"cada tarefa cita `arquivo:linha`?"*, *"tem seção de teste?"*) — o
serviço braçal que o modelo fraco faz bem, e que libera a IA forte para o julgamento.

A **fatia B-i** (PR #37, merged `a6b66a9`) já entregou a metade declarativa dessa frente: a coluna
`checks jsonb` no molde do papel (`V32__role_template_checks.sql`, com backfill do SEED v1 do
arquiteto — as três checagens `seam`/`file-line`/`test`), o leitor
`RoleTemplateService.checksForVersion` (`RoleTemplateService.java:128-132`, `record Check` em
`:202`) e a **primeira** das duas funções do mesmo conjunto: a seção `## Checagens que seu plano
deve satisfazer` no briefing do arquiteto (`IssuePromptComposer.java:78-89`). O que falta — e é o
objeto desta ADR — é a **segunda** função: alguém que **confira depois**, e o caminho pelo qual
esse conferimento chega a quem escreveu o plano.

Duas restrições herdadas moldam a fatia inteira. (1) O **princípio-mãe** do desenho (§0): o local é
sempre **verificador AUXILIAR de peso menor** — SOMA ao julgamento da IA forte (claude/codex),
**nunca substitui**. (2) A ADR-0043 §3 pôs Spring AI atrás de `ContextIntelligence` como camada
auxiliar do control plane e declarou que qualquer influência real exige ADR posterior com kill
switch e critérios próprios. Esta é essa ADR — para **um** uso, com **um** seam.

**A ordem de trabalho desta fatia é, ela própria, um achado que a ADR registra:** a medição do
modelo local veio **antes** do código (`docs/medicao-validador-qwen.md`). Ver §7 — o modelo que a
fatia ia usar por herança não funciona, e isso só não virou produto porque o harness rodou primeiro.

## Decisão

### 1. O que é: um conferente factual, com contrato próprio

Um modelo local (Ollama) confere, **checagem por checagem**, se o plano-PROSA que o arquiteto
escreveu satisfaz as checagens declaradas no molde do papel dele. O seam é
`PlanValidator.validate(plan, checks)` (`planvalidation/PlanValidator.java:14-17`) e a saída é
registro **próprio**: `[{checkId, presence, evidence}]` (`PlanCheckVerdict.java:14-19`), onde
`checkId` é a chave estável que a B-i persistiu no jsonb — é por ela que o buraco é endereçado
depois.

O local **não julga**: só presença/ausência factual, com o trecho que embasa. Julgamento de
qualidade é sempre da IA forte (Inviolável §3.5). Uma chamada **por checagem**, de propósito
(`SpringAiPlanValidator.java:66-74`): perguntar as três de uma vez devolve um bloco que o modelo
fraco mistura.

### 2. Onde encaixa: seam ÚNICO no pós-run de Issue, 2º consumidor do mesmo texto

`AdapterRunExecutor.java:401-415`, dentro do `if (pontaDoExecutor && result.succeeded())` que **já
existia** — o `skillRequestGate` (F-AT1, ADR-0006 emendada) era o primeiro consumidor do reply cru;
o validador é o segundo, sobre exatamente o mesmo texto. O plano é `replyCollector.text()`
(`RunFinalOutput`, capado em 20k chars — `RunFinalOutput.java:18`; o validador ainda tem cinto
próprio em `SpringAiPlanValidator.java:94-99`).

Três guards, cada um com um motivo distinto:

- **`pontaDoExecutor && result.succeeded()`** — só a ponta de Issue (`turno == null &&
  issueId != null`, `:301`) e só em sucesso; espelha o gate irmão do skill request.
- **`!result.noop()`** (`:409`) — precisa estar **aqui**, e não no portão, porque o check de no-op
  do executor só acontece mais abaixo (`:424`, depois do settle-de-falha). Sem este guard o
  validador conferiria o **texto vazio do run relâmpago** do bug #1 (ADR-0011 emendada) e gravaria
  telemetria de um plano que não existe.
- **"o molde pinado TEM checagens?"** (`resolveChecks`, `AdapterRunExecutor.java:151-157`) — a
  **mesma chave da B-i**: molde sem checagens ⟹ lista vazia ⟹ o local nem é chamado
  (`PlanValidationGate.java:62-68`). Isso **auto-escopa a fatia ao arquiteto sem hard-code de
  papel**, exatamente como o briefing.

O portão é **assíncrono e best-effort**, com máquina própria e deliberadamente simples
(`PlanValidationGate.java:43-47`, single-thread daemon): nada do que acontece lá dentro volta para
o caminho do settle da Issue.

### 3. O push: os buracos voltam ao PRÓPRIO arquiteto, em seção própria da dieta

Decisão do desenho §2 passo 3 (Opção A): quem recebe o checklist é **o próprio arquiteto**, no run
seguinte da **mesma issue**, e ele decide o que fazer com cada item — peso maior é dele. O caminho
é o **padrão PUSH da R19-A** (o agente selado não tem canal inbound; feedback tem de ser
materializado): `PlanChecklistSection.render` (`:39-63`) monta a seção e o
`CompanyMemoryMaterializer` a costura na dieta (`CompanyMemoryMaterializer.java:100-102`), com o
`agentId` passando a chegar até lá pelo `DefaultRunContextCompiler.java:115-119`.

- **Seção PRÓPRIA (`## Checklist do validador`), fora do Decision-log**: não toca Decisões, não
  consome `#N`, não grava nada no eixo `seq`/`grau` da ADR-0019/F-C1. O checklist é **sinal de
  máquina**, não decisão do dono — misturá-lo à memória canônica seria contaminar o átomo auditável.
- **Só BURACO entra** (`PlanCheckVerdict.isHole`, `:17-19`): `PRESENT` não é notícia e
  `INDETERMINATE` **não é buraco** (ver §4).
- **Vazio ⟹ nenhuma seção**: sem buraco, a dieta do arquiteto fica byte-a-byte como era antes desta
  fatia.
- O texto da seção diz ao arquiteto, explicitamente, que o conferente é **mecânico e pode errar** e
  que **o julgamento é dele** (`PlanChecklistSection.java:53-58`) — é a materialização, no prompt,
  do princípio-mãe.

### 4. Três valores de presença, não dois — `INDETERMINATE` jamais vira "ausente"

`Presence` = `PRESENT` / `ABSENT` / `INDETERMINATE` (`Presence.java:10-19`). O terceiro valor existe
para que **"não deu para saber"** (resposta vazia, truncada ou ilegível do modelo) **jamais** seja
gravado como `ABSENT`. A regra é do parser e não se negocia: o que não for reconhecível vira
`INDETERMINATE` (`PlanAnswerParser.java:26-38`, `:45-59` — o veredito só conta no **começo** da
linha, porque "não" aparece o tempo todo no meio da evidência e "sim" é sub-palavra de "assim").

O motivo não é teórico: **foi medido acontecendo**. Com orçamento de tokens curto
(`num_predict=150`) o raciocínio consome tudo e **14 de 18 respostas vieram vazias**
(`docs/medicao-validador-qwen.md`, §Orçamento de tokens). Sem o terceiro valor, aquelas 14 teriam
virado 14 "checagens ausentes" — **falso negativo sistemático**, empurrando ao arquiteto buracos que
não existem. Com ele, as 14 viraram `INDETERMINADO` e **zero** virou buraco. É esta a regra que o
teste trava (`PlanAnswerParserTest.respostaVaziaViraIndeterminadoNuncaAusente`,
`prosaSemVereditoViraIndeterminadoNuncaAusente`).

### 5. Os gates — todos obrigatórios, nenhum opcional

**(a) Registro-sombra SEMPRE.** Toda checagem conferida vira linha em `plan_validation` (**V33**),
com `check_id`/`check_text`/`presence`/`evidence`/`model` e o endereço
`company/run/agent/issue` (`PlanValidationStore.record`, `:29-43`). A coluna **`owner_audit`
nullable** é a **régua de precisão do dono**: é onde ele marca, depois, se o local acertou aquela
checagem — é o que transforma *"promoção exige evidência"* (Inviolável §3.4) em **dado**, não em
impressão. A tabela é deliberadamente separada do `context_assessment` (V31) — ver Alternativas.

**(b) Push atrás de kill-switch duplo.** GLOBAL: `orquestrador.plan-validation.mode` =
`off | shadow | push` (`PlanValidationProperties.java:33-57`; default **`off`** em
`application.yml:72-79`). POR-PAPEL: `push-disabled-roles` — o papel listado **nunca** recebe o
push, mesmo em `push` (`pushEnabledFor`, `:52-57`; resolvido no papel do agente daquele run,
`PlanChecklistSection.java:66-71`). Baixar de `push` para `shadow` **desliga o empurrão em produção
na hora, sem parar a telemetria** que mede a precisão — é essa assimetria que faz o kill-switch ser
utilizável sem cegar a régua.

**(c) "Barrar"/bloquear o run é PROIBIDO.** O validador **não gateia nada**: não reprova plano, não
segura settle, não muda status de Issue, não vira condição de nada. Ele escreve linha e, no máximo,
**texto num prompt**. Promoção a "barrar" exige nova decisão humana + evidência (§6) e **permanece
proibida** por esta ADR.

**(d) Falha do local NUNCA bloqueia o run** (Inviolável §3.6). Em três camadas: uma checagem que
explode vira `INDETERMINATE` e as irmãs seguem (`SpringAiPlanValidator.java:80-92`); qualquer
`RuntimeException` do lote é engolida em WARN (`PlanValidationGate.java:80-89`); e até a
**recusa de agendamento** (fila cheia) é capturada no caminho do executor
(`PlanValidationGate.java:73-77`). Em `mode: off` o bean do validador **nem existe**
(`@ConditionalOnExpression`, `SpringAiPlanValidator.java:30`) e o portão vira no-op silencioso
(`:41-43`) — Ollama ausente não é sequer um caminho de erro.

### 6. A EMENDA ao Inviolável #4 — a promoção sombra→"somar" acontece NO MERGE

Decisão do dono no grill de 2026-07-18, registrada verbatim no desenho §3 (Inviolável #4): no v1, a
promoção *sombra→"somar"* (o push do checklist ao arquiteto) acontece **no MERGE**, tendo como
*"decisão humana + evidência"* o **OK do dono + a prova §5** (caso violado + caso limpo, no motor
real). **Dispensa fase de sombra medida em produção PARA "SOMAR".**

**Racional do dono:** o arquiteto é o peso-maior e **decide se usa** cada buraco factual — pode
dizer *"faltou `arquivo:linha` na tarefa 3, mas tudo bem, é um delete óbvio"*. Logo, **push factual
≠ o local "barrando"**: entregar um fato conferível a quem tem a palavra final não é transferir a
palavra final. O que a emenda dispensa é a espera; o que ela **não** toca é o teto.

**"Barrar" segue exigindo nova promoção humana + evidência e permanece proibido** (§5c). As
salvaguardas que a emenda torna obrigatórias — e que esta ADR implementa — são exatamente
**kill-switch** (§5b) e **telemetria por-checagem** (§5a).

### 7. A escolha do modelo saiu de MEDIÇÃO, não de herança

A fatia ia, por inércia, usar o `qwen3:0.6b` — o modelo que a ADR-0043 já tinha em pé para o
inspetor de contexto. O harness mediu o contrário do esperado
(`docs/medicao-validador-qwen.md`, §Resultado consolidado):

- **`qwen3:0.6b` é preditor CONSTANTE — inutilizável como validador.** Respondeu "presente" em
  **18/18** casos, **exatamente a taxa-base do conjunto**, em **11 formatos de prompt diferentes**,
  incluindo os dois levers clássicos (`think` on/off e few-shot) e uma reescrita "concreta" das
  checagens que produziu **saída idêntica**. Os controles provaram que o problema é o **modelo**, e
  não o encanamento: pelo mesmo system prompt e mesmo parser ele responde certo a perguntas
  **lexicais** ("o texto contém a palavra banana?" → NÃO) e colapsa numa constante em pergunta
  **estrutural/quantificada** — que é justamente a forma de toda checagem ("**cada** tarefa cita
  `arquivo:linha`?").
- **`qwen3:1.7b` discrimina**: 17/18 na rodada curta e **26/27 agregado (96%) — 1 falso negativo, 0
  falso positivo** contando os planos longos e realistas. No `longo-violado` respondeu **NÃO
  exatamente para as duas checagens violadas de propósito e SIM para a satisfeita** — a forma exata
  da prova §5.
- **`think=true` é OBRIGATÓRIO** (12/18 sem → 17/18 com) — o inverso do 0.6b, onde o thinking
  piorava. E **`num_predict` curto faz a resposta vir vazia** (§4): 1500 é folga de graça, porque a
  latência não cresce com orçamento generoso (o modelo para sozinho).
- **A redação do SEED (a que o dono escreveu, no banco) venceu a reescrita "concreta"** (17/18 vs
  14/18): não há nada a consertar no texto das checagens da V32.

Config de produção pinada em `application.yml:72-79` e documentada no ponto de uso
(`PlanValidationProperties.java:10-28`). O `SYSTEM_PROMPT` é **verbatim do harness** — a redação que
mediu 17/18 (`SpringAiPlanValidator.java:35-40`); **alterá-lo sem re-medir é regressão silenciosa**,
e está escrito lá.

**O registro que importa desta seção:** a fatia quase foi construída sobre um modelo que **não
funciona**, e o modo de falha teria sido o **pior possível** — com o push ligado, o validador diria
"todas as checagens presentes" para **qualquer** plano, inclusive os violados, entregando ao
arquiteto uma **falsa tranquilização constante**. Isso é pior que não ter validador. Só não
aconteceu porque a medição veio **antes** do código.

## Alternativas rejeitadas

- **Reusar o `ContextAssessment` da ADR-0043** — é um **linter de fontes**: seu vocabulário é
  `Severity`/`Category`/`SuggestedAction` sobre `ContextSource`/`sourceHashes`
  (`context/intelligence/ContextAssessment.java:29-61`). Nenhum desses eixos responde *"cumpriu a
  checagem X?"*, e forçar a resposta binária para dentro de "severidade" tornaria a régua do dono
  (`owner_audit`) ambígua. Contrato próprio venceu — como o desenho §2 já previa.
- **Reusar a máquina assíncrona do `SpringAiContextIntelligence`** — ela é fire-and-forget sobre o
  **pacote de contexto**, com pool dimensionado, agenda de timeout, `FallbackReason` e cache por
  hash de view (`SpringAiContextIntelligence.java:76-77,94-97,128-131`). Aqui o insumo é a **SAÍDA**
  do run (outro momento, outro volume, sem cache aproveitável: cada plano é único). Reusa-se **só o
  bean `ChatModel`** do starter Ollama, com `ChatClient` próprio (`SpringAiPlanValidator.java:45-63`)
  — herdar a máquina inteira seria acoplar dois usos que só compartilham o transporte.
- **Exigir saída estruturada F-N3 do arquiteto (`{resposta, blocos[]}`)** — seria a parte **maior e
  mais arriscada**: schema novo (o existente é só das personas do CEO, ADR-0039), troca prosa→JSON
  no run do executor, contradiz o `## Report` que o briefing pede e **quebra o leitor raw-text do
  `skillRequestGate`**, que consome o mesmo texto. O v1 **valida a PROSA** no seam que já existe;
  F-N3 fica como **melhoria futura**, e só se a telemetria mostrar o modelo patinando na segmentação
  por-tarefa (é por isso que as checagens-semente do v1 são **nível-plano**, prosa-amigáveis).
- **Hard-code do papel "arquiteto" no seam** — rejeitado: o gate *"o molde pinado tem checagens?"*
  já auto-escopa, é a mesma chave da B-i e continua valendo quando o dono der checagens a um
  segundo papel, sem tocar código.
- **Empurrar `INDETERMINATE` como buraco "para o arquiteto decidir"** — rejeitado: seria o local
  **inventando trabalho a partir da própria falha de leitura**, exatamente o falso negativo que a
  fatia foi desenhada para não cometer (`PlanChecklistSection.java:20-22`).

## Consequências

### O que passa a ser possível

- Um plano de arquiteto passa a ser **conferido contra as regras do próprio molde**, item a item,
  com evidência citada — e os buracos chegam a quem pode consertá-los, **antes** da entrega.
- O par briefing×validador fecha o ciclo aberto na B-i: **o mesmo conjunto de checagens** instrui
  antes (`IssuePromptComposer.java:78-89`) e confere depois, sem duplicação de fonte.
- A precisão do conferente vira **dado auditável por checagem** (`plan_validation.owner_audit`), que
  é o insumo de qualquer promoção futura.
- O `ChatModel` local ganha um **segundo consumidor** com contrato e telemetria próprios, provando o
  seam da ADR-0043 sem alargá-lo.

### O que fica proibido (e permanece proibido depois desta ADR)

- **Barrar**: o validador não bloqueia, não reprova, não gateia — nem run, nem settle, nem Issue.
- **Contaminar a memória canônica**: o checklist não vira Decisão, não consome `#N`, não entra no
  Decision-log.
- **Mexer no `SYSTEM_PROMPT`, no `model`, no `think` ou no `num_predict` sem re-medir** — os quatro
  são resultado de harness, não de gosto.
- **Ligar o push sem kill-switch operável**: `mode` e `push-disabled-roles` são requisito da emenda
  ao Inviolável #4, não conveniência.
- **Tratar `INDETERMINATE` como `ABSENT`** em qualquer camada nova.

### O que a telemetria precisa mostrar antes de qualquer promoção a "barrar"

Nenhuma promoção pode ser argumentada em prosa. A V33 tem de sustentar, sobre planos reais:

1. **Falso positivo ≈ 0 auditado** — `owner_audit=false` sobre linhas `PRESENT` é o erro **fatal**
   (é a falsa tranquilização do §7); a medição de bancada deu 0 FP, mas bancada não é produção.
2. **Taxa de `INDETERMINATE` estável e baixa** — indeterminado crescendo é sinal de plano acima do
   orçamento ou de modelo degradado, não de plano ruim.
3. **Volume auditado suficiente** por checagem (`check_id`), não agregado: uma checagem pode ser
   confiável e a irmã não — a régua é por-checagem justamente por isso.
4. **Efeito observado do push**: que o arquiteto, tendo recebido o buraco, tenha de fato revisado —
   e quantas vezes ele o **descartou com razão** (o direito que o princípio-mãe lhe dá).

Enquanto isso, "barrar" segue exigindo **ADR nova + decisão humana + evidência**.

### Ressalvas honestas

- A acurácia citada é de **bancada** (`docs/medicao-validador-qwen.md`), com planos de verdade
  conhecida embutida — não de produção. É exatamente por isso que a `owner_audit` existe.
- Custo medido: ~3-6s por checagem × 3 checagens ≈ 15s por run — **assíncrono, fora do caminho do
  settle**, mas é trabalho real numa máquina local.
- O parser tolera a variação de redação do modelo fraco (`PlanAnswerParser.java:12-16`), inclusive o
  bloco repetido por tarefa; é heurística de formato, e formato de modelo muda com versão. O cinto é
  que **toda** falha de reconhecimento cai em `INDETERMINATE`.
- `latestForIssue` escopa o checklist ao **run inteiro** mais recente, não à linha mais nova
  (`PlanValidationStore.java:45-59`): sem isso, um run que conferiu 2 checagens misturaria a 3ª do
  run anterior e o checklist viraria colcha de retalhos.

## Prova

Cobertura unitária desta fatia: **20 métodos `@Test` em 5 classes novas**
(`backend/src/test/java/com/orquestrador/planvalidation/`) — `PlanAnswerParserTest` (o
`INDETERMINATE` que nunca vira `ABSENT`, eco de template, evidência com "não" no meio),
`PlanValidationGateTest` (molde sem checagens / saída vazia / `mode: off` não chamam o local; falha
do validador não atravessa para o run), `PlanChecklistSectionTest` (só buraco vira item; os dois
kill-switches, global e por-papel), `PlanValidationStoreTest` (uma linha por checagem com o texto
vigente do molde; o veredito escopado ao run) e `SpringAiPlanValidatorTest` (um veredito por check
carimbado com o `checkId`; falha de uma checagem não contamina as irmãs).

**O fecho da fatia, porém, é a prova §5 do desenho — no motor real, não em unitário.** Precedente que
obriga isso: `~/.claude/.../memory/verify-tool-de-agente-com-motor-real.md` — *o unitário passava com
a busca de memória quebrada*.

### §5 EXECUTADA (2026-07-18) — instância descartável `:18081` + Postgres efêmero, `:8080` intocado

Arquiteta REAL (`claude-code`/sonnet, pinada na v1 semeada com as 3 checagens), validador REAL
(`qwen3:1.7b` no Ollama). **7 runs, 18 linhas em `plan_validation`.**

**(a) Caso LIMPO — sem falso positivo.** A arquiteta escreveu um plano completo; o local respondeu
`PRESENT` nas 3, citando trecho de verdade:

| check | veredito | evidência gravada |
|---|---|---|
| `seam` | PRESENT | "O ponto de encaixe conceitual é o módulo de cobrança (`billing/`)…" |
| `file-line` | PRESENT | "Task 1: billing/models/subscription.py:1, adicionar os campos…" |
| `test` | PRESENT | "Testes" dedicada com detalhes de unitários, integração e cenário…" |

**(b) Caso com BURACO → push → a IA forte USOU o sinal.** Numa issue de resposta curta, o run N saiu
sem seam/arquivo:linha/teste e o local marcou as 3 `ABSENT`. No run N+1 da MESMA issue a seção
chegou ao briefing (materializada em `.claude/CLAUDE.md` do workspace):

```
## Checklist do validador
- **seam** — não encontrado. O que o validador viu: "Assim invalidar o cache fica simples…"
- **file-line** — não encontrado. O que o validador viu: "O texto não menciona nenhum arquivo ou linha…"
- **test** — não encontrado. O que o validador viu: "Assim invalidar o cache fica simples…"
```

E a arquiteta **usou o sinal exercendo o peso-maior** — dispensou um buraco com julgamento e
consertou os outros dois (verbatim do run N+1):

> "Quanto ao checklist do validador: ele foi desenhado para planos de implementação com diffs reais;
> aqui **não há arquivo:linha concreto porque nenhum código foi tocado**, mas **incluí o seam**
> (camada de acesso a dados / repositório) **e uma menção de teste para satisfazer a intenção do
> checklist** dentro do limite de 3 frases pedido pela issue."

O texto mudou de fato entre os runs: o N+1 nomeou `ProductListingRepository` e acrescentou "Teste:
cobrir com um teste de integração que verifica cache hit/miss e invalidação após update de produto"
— nenhum dos dois existia no run N. É exatamente a Opção A do desenho §2 passo 3 funcionando.

**(c) Kill-switch.** Com `mode: shadow`, o mesmo cenário **não materializou seção nenhuma**, e a
telemetria continuou gravando as 3 checagens — desligar o push não cega a régua.

**(d) Falha do local não bloqueia (§3.6).** Com `model: modelo-que-nao-existe:99b`, o run terminou
`SUCCEEDED`, a issue liquidou em `DONE` e as 3 linhas foram gravadas como **`INDETERMINATE`, não
`ABSENT`** — nenhum buraco inventado chegaria ao arquiteto. É a salvaguarda central desta ADR
observada em runtime, não argumentada.

### Ressalva honesta da própria prova

No run N+1 o local manteve `seam` como `ABSENT` **embora** a arquiteta tivesse nomeado
`ProductListingRepository` — um falso negativo real (o modelo citou o `**Report:**` em vez da
resposta). Coerente com a medição (1 FN em 27) e com o desenho: o erro do local **não é fatal**
porque quem decide é o arquiteto. Também se observou que a checagem `test` é satisfeita por MENÇÃO
lexical — num plano que dizia "cobertura de testes fica fora deste desenho" o veredito foi
`PRESENT`. Pela letra da checagem semeada ("seção **ou menção** explícita de teste") isso está
correto; se o dono quiser o sentido estrito, o conserto é na REDAÇÃO da checagem (B-i), não aqui.

## Relaciona

ADR-0043 (§3 `:62-63` — o gate de influência-no-run que esta ADR abre **escopado**; e o `ChatModel`
Ollama que ela reusa · forward-ref lá) · **B-i** (V32 `checks jsonb` + `RoleTemplateService` +
briefing — a outra metade da dupla função, sem ADR própria) · ADR-0006 emendada / F-AT1 (o
`skillRequestGate`, o **primeiro** consumidor do mesmo `replyCollector.text()` e o precedente do
seam) · ADR-0011 emendada + ADR-0004 emendada (`Result.noop` — o run relâmpago do bug #1 que o guard
`:409` mantém fora do validador) · ADR-0019 / F-C1 (o Decision-log que o checklist **não** toca) ·
R19-A (o padrão PUSH: feedback materializado na dieta, porque o agente selado não tem canal inbound)
· ADR-0039 (F-N3, a saída estruturada **rejeitada** para o v1 desta fatia) · ADR-0017 (o molde de
papel versionado onde as checagens moram) · `docs/desenho-spring-ai-verificador-auxiliar.md`
(contrato de rumo: §2, §3 com a EMENDA ao #4, §5) · `docs/medicao-validador-qwen.md` (a medição que
gateou a escolha do modelo — e que quase salvou a fatia de nascer inútil).
