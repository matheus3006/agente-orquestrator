# Sourcing das skills externas — dossiê para veto (R1-D v2)

> **VETADO pelo Matheus em 2026-07-10 (faixa R3-A):** a tabela import × não-importar foi
> CONFIRMADA como está — importar `grilling` + `adaptive-communication`; não importar as outras 5.
> As decisões por item estão carimbadas nas seções abaixo; o registro no DB é desta faixa.
> Levantado em **2026-07-10**, com upstreams verificados por `git ls-remote`/API do GitHub e as
> cópias instaladas em `~/.claude/skills/` inspecionadas arquivo a arquivo.

## As 3 restrições que decidem tudo

1. **Skill-in-DB v1 é arquivo ÚNICO** (`SkillMaterializer`: só `SKILL.md`; multi-arquivo é fatia
   futura). Skill com `references/`, `scripts/` ou `.md` irmãos **quebra no import cru** — os
   ponteiros materializam mortos.
2. **Context load por turno:** a description de cada skill do skillset senta no contexto de TODO
   run da persona. Cada import precisa pagar o próprio aluguel; dois skills com o mesmo gatilho
   (ex.: dois jeitos de "grelhar") tornam a invocação imprevisível.
3. **Fit com o desenho fechado:** a skill importada não pode contradizer ADR-0018/0031 nem o
   prompt do turno pós-G1/G2 (voz própria, casos A/B/C, balcão único).

## Resumo

| Skill | Origem @ versão | Forma | Recomendação |
|---|---|---|---|
| `grilling` (motor do grill-me) | mattpocock/skills @ `391a270` | 1 arquivo, 10 linhas | **IMPORTAR** (Questionador) |
| `adaptive-communication` | bencium (via coleção do Matheus) | 1 arquivo, 92 linhas | **IMPORTAR** (Questionador) |
| `grill-me` | mattpocock/skills @ `391a270` | wrapper de 7 linhas | NÃO importar (importa-se o motor `grilling`) |
| `grill-with-docs` | mattpocock/skills @ `391a270` | wrapper de 7 linhas | NÃO importar no v1 |
| `brainstorming` | obra/superpowers @ `d884ae0` | multi-arquivo (scripts) | NÃO importar no v1 |
| `teach` | mattpocock/skills @ `391a270` | multi-arquivo, user-invoked | NÃO importar no v1 |
| `cs-ceo-advisor` | alirezarezvani/claude-skills, v2.0.0 | multi-arquivo (Python) | NÃO importar no v1 |

## Item a item

### 1. `grilling` — IMPORTAR (é o motor por trás do `grill-me` pedido)

- **URL:** https://github.com/mattpocock/skills/tree/main/skills/productivity/grilling ·
  commit `391a2701dd948f94f56a39f7533f8eea9a859c87` (HEAD 2026-07-10). Instalada localmente.
- **Forma:** 1 arquivo, 10 linhas, model-invoked — importa limpo no v1.
- **Fit:** é exatamente o caso A do Questionador — entrevista implacável, **uma pergunta por vez**,
  cada pergunta com a recomendação e o porquê (bate com a regra da casa de perguntar com exemplos
  e racional). Complementa `being-ceo` sem colidir: `being-ceo` dá a instituição e o dossiê;
  `grilling` dá o músculo da entrevista.
- **Ajuste de import (1 linha) — VETADO: COM a emenda (Matheus, 2026-07-10).** O original manda
  "explorar o codebase" antes de perguntar — no run do CEO não há codebase; a linha importa
  emendada para "se a pergunta pode ser respondida pelo dossiê ou pelo estado vivo da conversa,
  explore-os em vez de perguntar" (a linha passa a reforçar o anti-repetição do grill).

### 2. `adaptive-communication` — IMPORTAR

- **URL upstream:** bencium — https://github.com/bencium/bencium-marketplace (a cópia instalada
  veio da coleção do Matheus, https://github.com/matheus3006/my-claude-skills; frontmatter sem
  campo de versão — pinar o texto da cópia local no import).
- **Forma:** 1 arquivo, 92 linhas, model-invoked — importa limpo no v1.
- **Fit:** Questionador — detecta hedging/intenção ambígua do dono e calibra exploração × resposta
  direta. Nicho disjunto de `being-ceo` (como falar, não o quê fazer) e de `grilling` (leitura do
  interlocutor, não roteiro de entrevista). **VETADO: importa em INGLÊS, sem tradução (Matheus,
  2026-07-10)** — instrução para o modelo (bilíngue), não para o dono; diff limpo com o upstream.

### 3. `grill-me` — NÃO importar (wrapper vazio sem o motor)

- **URL:** https://github.com/mattpocock/skills/tree/main/skills/productivity/grill-me @
  `391a270`. Instalada localmente.
- 7 linhas, user-invoked, corpo = "Run a `/grilling` session." — **depende da `grilling`**; solto
  no skillset é um ponteiro morto, e user-invoked nunca dispararia sozinho no run da persona.
  O pedido por trás dele é atendido importando o motor (item 1).
- **VETADO (Matheus, 2026-07-10): NÃO importa como arquivo; a intenção vive no `being-ceo`** — o
  modo "não temos nada → grill do zero com o motor" virou disciplina do caso A (seção "Os dois
  modos do grill" no draft).

### 4. `grill-with-docs` — NÃO importar no v1

- **URL:** https://github.com/mattpocock/skills/tree/main/skills/engineering/grill-with-docs @
  `391a270`. Instalada localmente.
- Wrapper de 7 linhas: `grilling` + `domain-modeling`. O `domain-modeling` mantém CONTEXT.md/ADRs
  de um repositório — o produto já tem o seu registro (dossiê apendado + Decisão no approve +
  `company_memory`); importá-lo criaria uma segunda memória fora do `company_memory`
  (contradiz ADR-0018/0019). Reavaliar se um dia as personas mantiverem docs de domínio próprios.
- **VETADO (Matheus, 2026-07-10): NÃO importa como wrapper; a intenção vive no `being-ceo`** — o
  modo "assunto já existe → ler dossiê/estratégia/memória primeiro e grelhar CONTRA o
  entendimento, estressando-o" virou disciplina do caso A, apontando para os docs que o produto
  realmente materializa (não os CONTEXT.md/ADRs do `domain-modeling`).

### 5. `brainstorming` (superpowers) — NÃO importar no v1

- **URL:** https://github.com/obra/superpowers/tree/main/skills/brainstorming · commit
  `d884ae04edebef577e82ff7c4e143debd0bbec99` (HEAD 2026-07-10). **Não** instalada localmente.
- Multi-arquivo (`scripts/frame-template.html`, `helper.js`) — quebra na restrição 1. E o nicho
  (refinar ideia por perguntas socráticas, uma por vez) colide com `grilling` — dois gatilhos
  iguais no mesmo skillset = invocação imprevisível (restrição 2). Reavaliar só se o grill do
  Questionador se mostrar raso em divergência/exploração de alternativas.

### 6. `teach` (mattpocock) — NÃO importar no v1

- **URL:** https://github.com/mattpocock/skills/tree/main/skills/productivity/teach @ `391a270`.
  Instalada localmente.
- Multi-arquivo (4 `*-FORMAT.md` irmãos) + workspace stateful de aulas (MISSION.md, lessons HTML)
  + user-invoked — quebra nas restrições 1 e 2, e "ensinar o dono em várias sessões" não é nenhum
  dos casos A/B/C. Sem caso de uso no v1.

### 7. `cs-ceo-advisor` — NÃO importar no v1

- **Origem:** bundle c-level de Alireza Rezvani — https://github.com/alirezarezvani/claude-skills;
  cópia instalada `ceo-advisor` v2.0.0 (MIT, updated 2026-03-05).
- **Forma:** 169 linhas + `references/` + `scripts/` (Python: `strategy_analyzer.py`,
  `financial_scenario_analyzer.py`) — quebra na restrição 1 (import cru = ponteiros mortos).
- **Fit baixo (restrição 3):** aconselha um CEO **humano** (fundraising, board de investidores,
  succession planning) e o empurra a **produzir** estratégia executiva — contradiz o "delegador
  puro sobre entregáveis" (ADR-0018) e não mapeia em nenhum caso A/B/C de uma company sintética.
  **VETADO: DESCARTAR de vez, sem extrato (Matheus, 2026-07-10)** — texto que nunca dispararia
  pagando aluguel de contexto; se um gap executivo aparecer ao vivo, autora-se na hora, ancorado
  no gap observado (mesmo tratamento do `brainstorming`).

## Composição dos moldes (M3) — VETADA: CONFIRMADA (Matheus, 2026-07-10)

> Confirmada com a emenda dos dois modos do grill: "do zero × contra material existente" vive
> DENTRO do `being-ceo` (seção "Os dois modos do grill"); os wrappers upstream seguem fora.

| Persona | Skills da casa (autoradas nesta faixa) | Externas importadas |
|---|---|---|
| Questionador | `being-ceo` | `grilling` · `adaptive-communication` |
| Planejador | `writing-doc-plans` · `delegation` | — |
| Executor | `delegation` | — |

- `delegation` nos dois back-office: a pesquisa com as "mãos do CEO" (D10) é do Planejador e do
  Executor; o protocolo do Executor só dispara nos gatilhos dele (a description roteia).
- Licenças (conferidas via API do GitHub na execução R3-A, 2026-07-10): mattpocock/skills =
  **MIT** ✅ · obra/superpowers = **MIT** ✅ · `ceo-advisor` declara MIT ·
  bencium/bencium-marketplace = **SEM licença declarada** — risco conhecido e aceito no import da
  `adaptive-communication` (uso interno da cópia já instalada na coleção do Matheus).

## Vetos R3-A dentro dos drafts (Matheus, 2026-07-10)

Os SKILL.md são corpo registrado no DB (o modelo lê em todo turno) — as decisões de veto ficam
registradas AQUI, não neles:

- **`writing-doc-plans` — lista das 6 seções CONFIRMADA** (Missão · Áreas e times · Agentes
  propostos · Premissas · Premissas (não confirmadas) · Cobertura). `## Premissas` e `## Premissas
  (não confirmadas)` são os nomes literais que o prompt do turno já instrui; `## Agentes
  propostos` espelha 1:1 o bloco `proposta` do gate (A3).
- **`delegation` — governança do off-book CONFIRMADA no v1** (pesquisa read-only + custo inócuo na
  assinatura + "você é o freio"); freio duro = subsistema, adiado para fatia própria se doer ao vivo.
- **`delegation` — tetos soft 30/10 MANTIDOS**, espelhando o prompt shippado
  (`ConversationTurnCoordinator.blocoPlanejador`); mudança futura = mudar nos dois lugares, com código.
- **`being-ceo` — os dois modos do grill** (emenda do veto 5): "não temos nada → grill do zero com
  o motor `grilling`" × "já existe material → ler dossiê/estratégia/memória primeiro e grelhar
  CONTRA o entendimento". Substitui o import dos wrappers `grill-me`/`grill-with-docs`.
- **Notas de staging removidas dos 3 drafts** (decisão do kickoff: removíveis no registro); os
  marcadores de sincronização com a Fatia 2 resolvidos com PONTEIRO ao contrato inline das
  Instruções do turno (R1-D v2: as skills apontam, nunca duplicam).
