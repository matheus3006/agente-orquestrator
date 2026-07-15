# ADR-0019: Decision-log — a decisão como átomo de memória

**Status:** Aceito (método fechado 2026-07-04; persistência/UI = residual a fechar na implementação de T.3)

> Registro do **método de memória do CEO ("segundo cérebro")**. Detalhe/porquê: `docs/handoff-grilling-memoria-
> ceo-segundo-cerebro.md` (D1–D12) + `docs/research/2026-07-04-{segundo-cerebro,memoria-de-agentes}.md`. O regime
> de racional (D6–D12) é o ADR-0020. A "carta" materializada é o ADR-0018.

## Contexto
O CEO precisa de memória **rastreável, achável por data, e que melhora com o progresso** — sem violar os
invariantes (carta soberana/derivada, `activity_log` imutável, company-scoping, deny-by-default) e sem inchar.
Modelo de referência real (código): o sistema `controle/` do cadillac-burger (memória em CAMADAS com **cap de
linha + graduação**). 2 deep-researches fundamentaram: consolidação episódico→semântico é a peça mais mal-servida
de memórias de agente; contexto longo **não** é memória (o gargalo é retrieval, não storage); write-validation-
gate antes de consolidar; JIT via índice→arquivo sem embeddings.

## Decisão

### D1/D2 — "segundo cérebro" = método de FINDABILITY (sem vetor)
Não é memória que cresce sozinha: é **organização para achar** — navegação **just-in-time** (índice→arquivo),
**SEM busca vetorial**. Anti-inchaço = **cap de linha + graduação + índice-não-conteúdo** (nada acumula ilimitado).

### D3 — os tiers de memória
```
activity_log (imutável, episódico)          ← ADR-0009, já existe
  → report .md por Issue  = o LEDGER          (capado/datado)
    → "ADR da Empresa" + Princípios/Playbook  (durável)
      → carta (sempre-on, soberana, DERIVADA) ← materializada como .claude/CLAUDE.md (ADR-0018)
        → cards vivos                          (derivado)
```

### D4 — a DECISÃO é a unidade atômica endereçável (não a Issue)
Issue = **contêiner de execução**; Decisão = **unidade de memória**. Schema:
```
Decisão {
  id, data, área,
  tipo:               direção | operacional | técnica,
  origem:             dono-no-gate | proposta-CEO | agente,
  o_que_mudou:        <ponteiro pro elemento do plano/artefato>,
  racional:           <texto>            # OBRIGATÓRIO (ADR-0020)
  racional_tipo:      lógico | intuição  # ADR-0020
  racional_arrancado: bool               # veio de grill?
  supersedes:         <id?>              # linhagem: a nova supersede a velha
  evidência, issue_ref?
}
```

### D5 — o gate de aprovação é FÁBRICA DE DECISÕES
Toda passagem pelo gate (a `approval` do ADR-0018 / a `review` do ADR-0012) emite **Decisões datadas**:
problemas apontados + mudanças pedidas + **riscos aceitos-sem-mudar**. O "quando" vira feature: **Log de Decisões
filtrável** (por data/área/tipo).

### A "Pergunta 1" fechada — a carta é PROJEÇÃO DETERMINÍSTICA
O consolidador da carta é **projeção determinística sobre as Decisões-de-direção aprovadas pelo dono** → **ZERO
LLM no caminho de escrita da carta** (defesa contra memory-poisoning; a carta é derivada, nunca auto-escrita pelo
agente). É o `CompanyMemoryConsolidator` do ADR-0018.

### O método de findability (o "achar" sem vetor)
1. **Endereçamento** — todo item tem ID estável, achável por eixos: tempo · unidade (Issue) · decisão (ADR-N) ·
   área/time · termo (glossário).
2. **Índice enxuto sempre-carregado** — mapeia conceito→endereço; no contexto fica o **índice, não o conteúdo**.
3. **Navegação JIT** — lê o arquivo exato quando o assunto surge; nunca injeta tudo.

## Consequências / Limites v1
- Sem vetor/pgvector, sem grafo/memória episódica (a carta é pequena, cabe no contexto; navegação JIT). D1.
- A graduação (episódico→durável) é o degrau que resolve o problema mal-servido — via ritual explícito
  (`execute-closure` do cadillac como referência; anti-padrão: *compactar Ledger = mover pra ADR, não deletar*).

## Residuais (status na implementação T.3.a, 2026-07-04)
1. **Dono do LEDGER** — **FECHADO na recomendação**: o report da Issue É a saída final do run
   concluinte do agente dela (read-model `IssueLedger` sobre o StepTrace via `RunFinalOutput` —
   nada duplicado em storage, D1); o CEO gradua para cima, nunca escreve no Ledger.
   `GET /issues/{id}/report` (capado/datado).
2. **"ADR da Empresa" vs "Princípios/Playbook"** — **FECHADO (2026-07-14, F-C2/C1): UM tier
   durável só, `MemoryKind.PLAYBOOK`** (ver Revisão F-C2 abaixo). O durável-PONTUAL já É o
   Decision-log — uma Decisão é o registro datado e endereçável; um tier "ADR-da-Empresa"
   duplicaria a tabela `decision`. O que faltava era só o DESTILADO (o degrau
   episódico→semântico).
3. **Onde o Decision-log mora** — **FECHADO**: tabela própria `decision` (V18) + Log filtrável
   `GET /decisions?area=&tipo=&from=&to=` (board-only). A carta é projeção SOBRE estas linhas
   (`CompanyMemoryConsolidator`, determinística, na MESMA tx do gate).

## Nota de reconciliação (2026-07-10 — divergências MÉDIAS D5/D11 da auditoria doc×código de 2026-07-07)

**Status: FECHADA (2026-07-14, F-C1 — os dois writers ACESOS; ver Revisão abaixo).** Decisão do
grill da Frente C (C11/C12): "puxar fatia para ligar" venceu — os seams mortos registrados aqui
eram exatamente a doença que a frente curou. Texto original preservado como história:

- **D5 — a passagem pela review NÃO fabrica Decision.** Das duas portas do gate, só a
  `approval` emite: `ApprovalService.decide → recordGateDecision`. A `review` (ADR-0012),
  em `IssueService.review`, grava `REVIEW_APPROVED/REJECTED` no activity_log e fecha o ciclo,
  mas **não cria Decision**. A fundação está pronta e morta: `Decision.anchoredToReview(reviewId,
  issueId)` (zero callers) e a coluna `review_id` da V18 (sempre NULL).
- **D11 — `supersedes` sem caminho de escrita.** O leitor existe e funciona: o
  `CompanyMemoryConsolidator` filtra as supersedidas da projeção da carta. Mas **nenhum writer
  seta `supersedesId`** — a entidade só tem getter e o `DecisionController` é GET-only (o campo
  aparece na resposta, nunca entra). Existe a fundação (coluna + read-filter), não a
  revisão-como-nova-Decisão da linhagem D4.

Nos dois casos o desenho acima segue válido como intenção; o que falta é o elo de escrita.
Inventário: `docs/roadmap-interno.md §Divergências`.

## Revisão 2026-07-14 (F-C1 "o átomo completo" — grill da Frente C, C3/C4/C11/C12/C15)

> Detalhe/porquê (alternativas rejeitadas inclusas): `docs/handoff-grilling-frente-c-memoria.md`.

- **O schema do D4 ganha DOIS eixos novos (V29 — a única migração da Frente C inteira):**
  - **`seq` — o endereço humano `#N` (C4), clone exato do V23/ADR-0033:** por company, denso,
    imutável, nunca reusado; alocado MAX+1 no chokepoint único (`DecisionLog.record`) sob o lock
    da linha da company. Era a **perna 1 do método de findability que não existia no código**:
    sem endereço estável, o D11 é impossível (o CEO não cita o que supersede), o `memory_fetch`
    (F-C3) não tem chave e a linhagem de um princípio não tem o que citar. O `render()` da carta
    passa a emitir o `#N` (`"- #7 [data] (área) o_que_mudou — …"`). *Rejeitado:* UUID como
    endereço citável (36 chars = convite a alucinação; a casa já escolheu `#N`).
  - **`grau: DECISAO | PRINCIPIO` — o eixo do ALCANCE (C3), ortogonal ao `tipo` (ASSUNTO):**
    resolve *um* caso × governa *casos futuros*. `grau` é derivado da PORTA, `tipo` da ÁREA —
    nenhum é julgado (D9 se estende de graça). *Rejeitados:* `tipo=PRINCIPIO` (erro de categoria
    — o Log filtrável passaria a MENTIR: `?tipo=TECNICA` perderia todo princípio técnico) e
    tabela `principle` própria (dois espaços de endereço; trai o D4). O valor `PRINCIPIO` entra
    JUNTO do writer de graduação (F-C2) — enum com o consumidor, nunca antes.
- **D5 FECHADO — a review é fábrica de Decisões (C11):** `IssueService.review` fabrica;
  `Decision.anchoredToReview` ganhou caller; `review_id` (V18, sempre-NULL desde que nasceu)
  acordou. **Requisito duro cumprido:** a `area` vem do ARTEFATO (o time do assignee) — nunca
  default fixo, senão todo review viraria LOGICO e o D10 continuaria morto. O regime do racional
  nesta porta é o C14 (triviabilidade — ver ADR-0020, Revisão 2026-07-14).
- **D11 FECHADO — `supersedes` ganhou caminho de escrita nas DUAS portas (C12):** `supersedesSeq`
  opcional no decide (approval) e na review — o CEO propõe citando o `#N`, o dono sela; o leitor
  já filtrava supersedidas → a carta se **auto-cura** na hora. Zero migração (a coluna existia).
- **A fábrica é ÚNICA e extraída (C15):** o `recordGateDecision` (privado do `ApprovalService`)
  virou `memory/GateDecisionFactory.fabricate(GatePassage)` — módulo profundo colado no
  `DecisionLog.record`; as portas são adapters finos (approval + review hoje; caso B amanhã).
  Moram DENTRO da fábrica: a regra C14 "sem substância → sem Decisão", a maiêutica D10 como
  evidência e a consolidação da carta na MESMA tx. *Rejeitado:* espelhar a fábrica no
  `IssueService` (duas fábricas = o drift que criou os seams mortos que esta frente curou).
- **[R18-A, 2026-07-14] O LOTE reconciliado com a fábrica (ADR-0035):** acender os writers da F-C1
  transformou o gate em lote (ADR-0035/PR #12) em **N Decisões clonadas por ato do dono** — um bug
  de produção vivo (o mesmo racional/área N vezes + nudge C5 falsa). Fechado: o lote suprime a
  fabricação por-item e registra **UMA** Decisão (por área) com a linhagem dos N na `evidencia`, via
  a âncora nova **`Anchor.NoLote`** (3ª da fábrica; `approval_id` nulo — a Decisão do lote não tem
  approval singular, precedente no caso B). Ver ADR-0035, Revisão R18-A.

## Revisão 2026-07-14 (F-C2 "a assinatura: graduação + cap" — grill da Frente C, C1/C2/C5/C7/C8)

> Detalhe/porquê (alternativas rejeitadas inclusas): `docs/handoff-grilling-frente-c-memoria.md`.
> O RITUAL da graduação (7ª ação do gate) é a ADR-0020, Revisão F-C2.

- **Residual 2 FECHADO — o tier durável é UM: `MemoryKind.PLAYBOOK` (C1).** Projeção
  determinística GÊMEA da carta (`CompanyMemoryConsolidator`): `grau=PRINCIPIO` → corpo
  versionado em `company_memory`, zero-LLM no caminho de escrita, supersedidas fora (a
  auto-cura D11 vale aqui também). A ENTRADA é autorada (C2): o CEO propõe a frase, o dono
  sela pelo gate — a materialização projeta só princípios JÁ SELADOS, então o caminho
  automático continua zero-LLM (o invariante anti-poisoning fica intacto).
- **Cap por PROJEÇÃO, hard por construção (C7): carta=12 · playbook=12** (constantes no
  consolidator, provadas por teste). Capa-se a PROJEÇÃO, **nunca o log** (história
  append-only — o anti-padrão "compactar = deletar" segue proibido): a janela é das N mais
  recentes por `#N`; o excedente segue no log, endereçável, e vira **nudge de estouro** com
  os `#N` (C8: **graduar ou cair-pra-JIT** — nunca descartar, nunca comprimir por LLM).
- **Detector de graduação determinístico (C5, `memory/GraduationDetector`):** N=3 decisões
  numa área DESDE o último princípio daquela área — função pura sobre o log (área, ordem do
  `#N`), zero-LLM, idempotente, de CARONA no commit do gate (toda passagem re-projeta; sem
  mudança, sem versão nova). O resultado é só a **nudge de candidata na carta**, citando os
  `#N` — a linhagem que o CEO copia na proposta. *Rejeitada:* tabela de linhagem
  (`principle_source`) — não paga o custo; a linhagem vai em `evidencia`
  ("Graduado de #2, #3, #4"), documentação resolvível pelos `#N`, nunca FK.
- **`grau` filtra a SUPERFÍCIE:** a carta projeta direção `grau=DECISAO`; princípio mora no
  playbook — duplicar nas duas inflaria o sempre-on que o cap protege (C9 recebe as duas
  separadas na F-C3).
- **Decisões de kickoff (registradas):** a 2ª projeção roda na MESMA tx do `refresh` (espelha
  o contrato da fábrica da F-C1) · a nudge de estouro cita os `#N` do excedente (torna o
  "cair-pra-JIT" concreto já nesta fatia) · projeção que JÁ EXISTE re-renderiza até vazia —
  a auto-cura D11 vale para a ÚLTIMA direção supersedida (só o NASCIMENTO exige vigência).

## Revisão 2026-07-14 (F-C3 "índice + navegação JIT" — grill da Frente C, C9/C10)

**O SPLIT do sempre-on (C9): as duas pontas leem a MESMA memória, com DIETAS diferentes.**

| ponta | dieta sempre-on | por quê |
|---|---|---|
| **turno do CEO** | **CARTA + PLAYBOOK** | é o dono do rumo: direção vigente é material dele |
| **run do EXECUTOR** | **PLAYBOOK só** (os princípios) | **direção do dono não é material de executor** — ele executa uma Issue, não conduz a empresa |

- **Até aqui o executor não recebia memória NENHUMA** (`CompanyMemoryMaterializer`: *"em run de Issue o
  executor não o chama"*) — o **D12** (*"os agentes APLICAM"*) era **letra morta**, e sem nada sempre-on o
  agente sofria **cegueira-de-retrieval**: nunca lhe ocorria PROCURAR. Agora ele recebe os **princípios**,
  que **SÃO o índice enxuto** do método (frases terças, dono-seladas — indexar um índice seria absurdo) e
  que carregam o **`#N`**: a dieta sempre-on **ensina o trilho JIT de graça** (o `#N` é a chave do
  `memory_fetch`). Provado ao vivo: o executor leu o princípio, buscou o `#N` da decisão-mãe e trouxe o
  racional dela mid-run.
- A dieta é escolhida pela `MemoryAudience` (seam pequeno; o materializer concatena as projeções
  CORRENTES, cada uma VERBATIM — nada é reescrito) e vale para os 2 motores pelo `memoryFile` da
  `WorkspaceConvention` (ADR-0032): `.claude/CLAUDE.md` no claude, `AGENTS.md` na raiz no codex.
- **O `memoryFile` é território do PRODUTO em TODO run** — *judgment call do dono, colhido no kickoff da
  F-C3 (2026-07-14)*. Antes, o pipeline não tocava o `AGENTS.md`/`.claude/CLAUDE.md` de um run de Issue
  ("é do agente"); **isso era CONSEQUÊNCIA de o executor não receber memória**, não um princípio próprio.
  Com o C9 ele passa a receber, pelo **único canal sempre-on que existe** — e a regra fica a MESMA das
  outras superfícies de config do CLI que o produto **já** possui em run de Issue (`.claude/skills/` é
  varrido pela faxina; os hooks são escritos em todo run, F-N6). Consequência assumida: o que o AGENTE
  escrever nesse caminho é substituído pela dieta durável. "Quem materializa, faxina" — e agora o
  executor materializa, então ele também varre a convenção inativa (troca de motor nunca deixa cérebro
  velho no chão, ADR-0032/D6). Dieta vazia (sem princípio selado) → o arquivo **some** (nunca servir
  dieta velha).
- **A navegação JIT** (`memory_search`/`memory_fetch`, cap 3/run, fronteira = durável selado) reusa o
  bridge MCP da F-AT2 — está na **ADR-0003, Revisão 2026-07-14** (zero canal novo, C10/C10.b).
- **Zero migração** (V30+ seguem livres) · **sem ADR nova** (ADR-0043 livre).


## Relaciona
`docs/handoff-grilling-memoria-ceo-segundo-cerebro.md` (D1–D12, schema, findability); ADR-0018 (carta/
`company_memory`/materialização + `CompanyMemoryConsolidator`); ADR-0020 (racional-por-grill — D6–D12);
ADR-0009 (`activity_log` = tier episódico imutável); ADR-0012/0018 (o gate = fábrica de Decisões);
ADR-0015 (a carta É o handoff — mesma tese de destilar); ADR-0010 (nomes + módulos profundos).
