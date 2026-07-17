# Desenho — Spring AI como VERIFICADOR AUXILIAR (Uso A: busca · Uso B: validador de planos)

> **Status:** DESENHO APROVADO PELO DONO (2026-07-17, sessão de grill). Aguarda fatiamento
> (F-SAI2+). Este documento é o **contrato de rumo**: nenhuma fatia destes dois usos entra em
> desenvolvimento contrariando o que está aqui, e nenhuma fecha sem a prova viva descrita na §5.
>
> **Relação com a pesquisa:** supera parcialmente a candidata **F-SAI4** de
> `docs/pesquisa-spring-ai-melhorias.md` — o dono decidiu **não** trocar o ranking da busca por um
> "adapter híbrido com embeddings" (o que a pesquisa propunha, §2:308). Decidiu algo mais
> conservador: a busca **continua rígida** e o modelo local só **confere** o resultado. Ver §1.
>
> **Fonte de princípio:** `~/.claude/.../memory/frente-e-spring-ai-racional.md` (racional verbatim
> do dono) + `scratchpad/racional-spring-ai-verbatim-dono.md`. Este desenho **estende** aquele
> racional; não o contradiz.

---

## 0. Princípio-mãe (verbatim do dono, 2026-07-17)

> **O modelo local é sempre um verificador AUXILIAR de peso menor. Ele SOMA ao julgamento
> principal (a IA forte de fornecedor — Claude/Codex —, ou a busca determinística), NUNCA
> substitui. Faz só checagens simples e pontuais. A IA forte mantém o peso maior e a palavra
> final.**

Isto vale para os **dois** usos abaixo e é a regra que todas as fatias herdam. Alinha com o
racional da Frente E ("camada AUXILIAR, substituível, controlada; nunca substitui os engines").

O modelo local hoje é `qwen3:0.6b` no Ollama (local, gratuito, `application.yml` — hoje em
`mode: "off"`). "IA forte" = os motores reais `claude` (Claude Code) e `codex` (OpenAI Codex),
donos do raciocínio/execução (`execution/adapter/ClaudeCodeAdapter.java`, `CodexAdapter.java`).

---

## 1. Uso A — busca (skills **e** memória) com o local como CONFERENTE

**Decisão do dono:** vale para os dois alvos de busca — as **skills** (KnowAll) **e** a **memória
do CEO** (Decisões). Mas o papel do local é **conferir**, não **buscar**.

**Como fica:**
- A busca **continua rígida como é hoje** e mantém o **peso maior**:
  - skills → `pg_trgm`/`ILIKE` sobre metadados (`skills/PgTrgmSkillSearch.java`,
    `SkillRepository.java:41`; seam `SkillSearch`, já real com 2 adapters);
  - memória → endereço exato `#N` / metadados selados (`memory/MemoryQuery.java`,
    `memory_search`/`memory_fetch`).
- **Atua só na busca "por parecido"** (`memory_search` + skill search por `pg_trgm`/query), NÃO na
  busca exata por `#N` (`memory_fetch` — ali não há dúvida: veio o que foi pedido).
- O modelo local **NÃO altera o ranking**. Entra **depois** da busca, e responde **uma** pergunta
  factual: *"o que a busca trouxe responde à pergunta que o agente fez?"*
  - Se sim → segue, a busca cumpriu.
  - Se não (resultado irrelevante, ou nada útil) → **avisa o agente** (decisão do dono, 2026-07-17,
    **Opção ii**): junto do resultado, o agente (IA forte) recebe o sinal e decide — buscar de novo com
    outras palavras, alargar. Peso maior é dele. (Re-busca automática do sistema = Opção iii, mais
    invasiva, **NÃO agora**.)

**Por que isto respeita a trava que o dono pôs (Constituição #9, `plano-implementacao-spring-ai.md`):**
o Decision-log **continua deliberadamente sem vetor** — a memória do CEO segue **auditável e
determinística**, achada por `#N` exato, **não** por similaridade difusa. O local **só audita** se a
busca acertou; nunca vira um "buscador por chute". A trava #9 permanece intacta.

**Sombra SE aplica ao Uso A** (correção — o desenho inicial dizia o contrário): como o conferente vai
**avisar/influenciar** o agente (Opção ii), ele nasce em **sombra** — anota em silêncio se a busca
respondeu, o dono mede a precisão, e só quando provar é que passa a avisar de verdade. Mesmo espírito
do Uso B (prova antes de dar poder). A pura telemetria (Opção i) é justamente o estágio-sombra.

**Nuance da #9 na memória:** na busca por parecido da memória, o conferente vê só os **metadados** que
o `memory_search` retorna (título/grau/área/`#N`) — **nunca o corpo** da Decisão, que segue capado e
só sai por `#N` no `memory_fetch`. A fronteira do corpo permanece intacta; a #9 idem.

---

## 2. Uso B — validador de planos ("o plano seguiu as skills do arquiteto?")

**O problema (verbatim do dono):** o plano é **variável** — depende da escrita de cada arquiteto —
e o que interessa validar é se ele **de fato seguiu as skills** que aquele arquiteto tem. Um modelo
fraco não julga isso bem.

**A técnica que torna um modelo fraco confiável aqui:** não pedir **opinião** a ele. Modelo fraco é
ruim em *"esse plano é bom?"* e bom em *"esta coisa concreta está presente no texto? sim/não"*.
Então **quebra-se** *"seguiu as skills?"* (julgamento difícil) numa **lista de checagens binárias e
factuais** (fácil).

**Fluxo, em 3 passos:**

1. **O molde do papel do arquiteto declara a lista de checagens pontuais** (NÃO cada skill) —
   asserções que dá pra responder **olhando o texto**, sem opinar. Ex.: *"o plano aponta o
   encaixe/seam?"*, *"tem teste de deleção?"*, *"cada tarefa cita `arquivo:linha`?"*.
   **Por que no papel e não na skill (decisão do dono, 2026-07-17):** skills podem ser baixadas do
   GitHub e chegam sem checagens; criar validação à mão por skill não escala. O lar é o molde do papel
   (`role_template_version` — já versionado, curado pelo dono, e já agrupa o `skillset`,
   `V14__role_template.sql`). As skills do arquiteto (ex.: `tdd`, `diagnosing-bugs`) só **inspiram**
   quais regras escrever; as regras moram no papel.
   **Dupla função, sem duplicar:** o MESMO conjunto de regras (i) entra no briefing que o arquiteto
   recebe ao criar o plano (via `IssuePromptComposer`) — então ele já sabe o que o plano deve ter — e
   (ii) alimenta o validador que confere depois.
   **Escopo = por papel** (vale para todo plano daquele arquiteto, não varia por tarefa). **Decisão
   reversível:** o dono avalia com o uso real quando a aplicação estiver no ar e muda a forma se não
   gostar.

2. **O modelo local confere item por item** — recebe o **plano** que o arquiteto escreveu (a saída
   estruturada do agente, F-N3 `{resposta, blocos[]}`) + a lista de checagens das skills daquele
   arquiteto. Responde: *"encaixe → presente; teste de deleção → NÃO achei; `arquivo:linha` → falta
   na tarefa 3"*, **cada item com trecho de evidência**. É serviço braçal e mecânico — o que o
   modelo fraco faz bem.

3. **O checklist é SOMADO ao trabalho da IA forte — que é o PRÓPRIO ARQUITETO num re-run** (decisão do
   dono, 2026-07-17, **Opção A**): os buracos factuais voltam pro arquiteto (claude/codex), que revisa
   e conserta o próprio plano antes de finalizar, já **com os buracos na mão**. Peso maior é dele: pode
   dizer *"faltou `arquivo:linha` na tarefa 3, mas tudo bem, é um delete óbvio"* — julgamento que o
   local não faz. **Reusa o padrão PUSH da R19-A** (feedback volta pro agente no re-run via
   `AdapterRunExecutor` + materialização). O checklist ser **factual** combate o viés de auto-revisão:
   o arquiteto não ignora um buraco concreto apontado. **Evolução possível (não agora):** revisor
   separado (Opção B) para olhar independente; e os buracos podem também aparecer na aprovação
   (Opção C) só como transparência, sem serem o mecanismo principal.

**Divisão de trabalho:**
- **Local (grátis, fraco):** conferir presença/ausência de cada item obrigatório. **Não julga.**
- **IA forte (cara, forte):** o julgamento de qualidade, livre do serviço braçal e municiada pelo
  checklist.

**Por que é seguro mesmo com modelo fraco:** se o local errar uma checagem, a IA forte (peso maior)
corrige — o erro dele **não é fatal**. E o validador **começa em sombra**: roda em silêncio sobre
planos reais, o dono compara *"o que o local apontou era verdade?"*, e ele só passa a ser somado de
verdade (e muito depois, a **barrar**) quando **provar** que confere direito — **promoção exige
decisão humana + evidência**, nunca automática.

**Contrato próprio:** o Uso B **não** reaproveita o `ContextAssessment` do inspetor de contexto
(severidade/categoria/ação foram desenhados para achados sobre `ContextSource`, não para "cumpriu a
checagem X"). Saída nova, escopada a checagens binárias + evidência.

**Critério para escrever boas checagens (regra para o dono e para os arquitetos):** só vale regra
que dá para responder **olhando o texto, sem opinião**.
- ✅ Bom (dar ao local): *"tem seção de teste?"*, *"cita `arquivo:linha`?"*, *"referencia a issue
  `#N` de origem?"*.
- ❌ Ruim (é da IA forte, nunca do local): *"a arquitetura é elegante?"*, *"o plano é inteligente?"*.

---

## 3. Invioláveis (o que NÃO pode acontecer no desenvolvimento)

Toda fatia destes usos **falha na revisão** se violar qualquer um:

1. O local **nunca** é a palavra final e **nunca** substitui a IA forte. Só soma, com peso menor.
2. **Uso A:** o local **nunca** altera o ranking da busca. A busca rígida decide; o local só confere.
3. **Uso A:** a memória do CEO **nunca** é vetorizada. Segue determinística por `#N` (trava #9). O
   local **audita**, não indexa por similaridade.
4. **Uso B:** o validador **nunca** barra sozinho. Nasce em **sombra**; promoção a "somar" e depois a
   "barrar" exige **decisão humana + evidência**.
5. **Uso B:** o local só recebe **checagens factuais** (presença/ausência). Julgamento de qualidade é
   sempre da IA forte.
6. Herdadas da Frente E: sem tools, sem terceira memória, provider trocável, falha do local **nunca**
   bloqueia o run, núcleo determinístico compila/ordena/hasheia **antes** — o local vê visão limitada
   **depois**.

---

## 4. Fatiamento previsto (a detalhar no kickoff de cada fatia)

- **Fatia Uso A** — conferente de busca sobre o resultado de `SkillSearch` + `MemoryQuery`, sinal de
  lacuna entregue à IA forte. Seam próprio; não toca ranking.
- **Fatia Uso B** — validador de conformidade plano×regras-do-papel: (a) o campo de checagens no molde
  do papel (`role_template_version`) + como o mesmo conjunto entra no briefing (`IssuePromptComposer`);
  (b) o seam que lê o plano (saída F-N3, irmão dos scanners `ProposalScanner`/`SkillRequestScanner`) e
  roda as checagens no local, só quando o agente é do papel arquiteto; (c) o re-run de auto-revisão do
  arquiteto que recebe o checklist (Opção A, padrão PUSH da R19-A); (d) o modo sombra e sua telemetria.

Ordem, contratos, ADR/emenda e teto de escopo saem no grill de cada fatia (método do projeto: fatia
pequena → contrato → `/verify` → PR → merge só com OK do dono; máximo 2 faixas em voo).

---

## 5. Como se PROVA que foi implementado (anti "disse que fez")

**Exigência explícita do dono (2026-07-17):** *"em alguns casos você diz que implementou mas não
implementa."* Portanto, para estes usos, "pronto" **não** é "os testes unitários passam" e **não** é
uma afirmação em prosa. "Pronto" é **comportamento observado no motor real**.

Precedente do próprio projeto que obriga isto:
`~/.claude/.../memory/verify-tool-de-agente-com-motor-real.md` — *o teste unitário passava com a
busca de memória quebrada; só quando o codex REAL perguntou como humano é que se viu que ele não
achava nada e respondia o `#N` errado.* Busca que "passa no teste" e falha no motor real é
exatamente o risco do Uso A.

Cada fatia fecha **somente** com `/verify` (skill `agente-orquestrator:verify`, instância descartável
com motor real, sem tocar o `:8080`) demonstrando, com evidência citável:

- **Uso A:** um agente REAL faz uma busca cuja resposta certa é conhecida; observar que (i) a busca
  rígida trouxe o resultado e (ii) o local de fato emitiu o parecer "responde / não responde" — e que
  em um caso de busca falha o local **sinalizou a lacuna**. Não vale mock; tem de ser claude/codex
  real citando o resultado.
- **Uso B:** um arquiteto REAL escreve um plano que **viola de propósito** uma checagem (ex.: omite
  `arquivo:linha` numa tarefa); observar que o local **apontou exatamente essa falha com evidência**,
  que isso **chegou** ao prompt da IA forte, e que a IA forte **usou** o sinal. E o caminho oposto:
  plano que cumpre tudo → local não inventa falha (sem falso-positivo).

Quem reportar o fecho **cita o trecho da evidência** (o que o motor real disse), não "implementado".
Se a prova não puder ser produzida, a fatia **não** está pronta — e isso é reportado como tal.
