# Medição empírica — qwen3:0.6b como validador de planos (Uso B / B-ii)

**Data:** 2026-07-18 · **Modelo:** `qwen3:0.6b` (id `7df6b6e09427`, 522 MB) · **Ollama** v0.32.1 `:11434`
**Endpoint:** `/api/chat` (o mesmo que o Spring AI Ollama usa) · **temperature:** 0.0
**Checks:** as 3 REAIS do seed v1 do arquiteto (`V32__role_template_checks.sql`): `seam`, `file-line`, `test`
**Planos:** prosa em português com verdade conhecida embutida (como o arquiteto real escreve)

Harness: `probe.py` · `probe2.py` · `probe3.py` · `probe4.py` · `probe5.py` · `probe6.py` ·
`probe7.py` · `probe8.py` · `probe9.py`

---

## Resultado consolidado

Taxa-base = o placar de um chute CONSTANTE (o conjunto tem 5/9 presentes na rodada de 9,
10/18 na rodada de 18). **Bater a taxa-base é o mínimo para o modelo ter algum valor.**

| # | Variante de prompt | n | acertos | taxa-base | comportamento observado |
|---|---|---|---|---|---|
| 1 | JSON `{present,evidence}`, think=false | 9 | 3 | 5 | 9× `present:true`; JSON malformado (ecoou o placeholder) |
| 2 | Rótulo PRESENTE/AUSENTE 2 linhas | 9 | 4 | 5 | 8× PRESENTE |
| 3 | Rótulo + instrução explícita | 9 | 5 | 5 | **9× PRESENTE** (= taxa-base exata) |
| 4 | Idem, think=true, num_predict=1500 | 9 | 5 | 5 | 9× PRESENTE |
| 5 | Extração ("copie o trecho, ou NADA") | 9 | 4 | 5 | **9× NADA** (o viés só inverteu de constante) |
| 6 | Polaridade invertida TEM/FALTA | 9 | **6** | 5 | única que variou de verdade (+1 = ruído em n=9) |
| 7 | Pergunta natural + SIM/NÃO | 9 | 5 | 5 | **9× SIM** |
| 8 | Idem, think=true | 9 | 2 | 5 | **pior que a taxa-base**; 1 resposta VAZIA (truncada) |
| 9 | Redação do SEED (abstrata), 18 casos | 18 | 10 | 10 | **18× SIM** |
| 10 | Redação CONCRETA (literal), 18 casos | 18 | 10 | 10 | **18× SIM** — saída idêntica à #9 |
| 11 | Few-shot (1 SIM + 2 NÃO trabalhados) | 18 | 8 | 10 | **0× SIM / 18× NÃO** — abaixo da taxa-base |

**Nenhuma variante supera a taxa-base de forma significativa.** O melhor placar (#6, 6/9) está
dentro do ruído para n=9. As variantes #3/#5/#7/#9/#10/#11 são literalmente **funções constantes**:
a resposta não depende do conteúdo do plano — depende só de como a pergunta foi enquadrada.

## Controles — o problema é o MODELO, não o encanamento

**Controle A (`probe5.py`):** pelo **mesmo caminho** (mesmo system prompt, mesmo parser SIM/NÃO,
mesmo plano) o modelo responde **corretamente**:

| pergunta | esperado | obtido |
|---|---|---|
| "O texto contém a palavra banana?" | NÃO | **NÃO** ✅ |
| "O texto é uma receita de bolo?" | NÃO | **NÃO** ✅ |
| "O texto contém código em Python?" | NÃO | **NÃO** ✅ |
| "O texto fala de relatório?" | SIM | **SIM** ✅ |

⇒ O modelo **sabe dizer NÃO**, o system prompt **não** o envenena, o parser **funciona**.

**Sondagem de sanidade (`probe3.py`):** acerta o literal, erra o inferencial —
`"o texto contém a seção '## Testes'?"` → **SIM** ✅ · `"'## Riscos'?"` → **NÃO** ✅ ·
mas `"que número aparece depois de ReportService.java:?"` → respondeu **"2"** (era **142**) ❌.

## Diagnóstico

O `qwen3:0.6b` **discrimina em pergunta LEXICAL** ("essa palavra aparece?") e **colapsa numa
constante em pergunta ESTRUTURAL/QUANTIFICADA** — que é exatamente o que as checks pedem:
*"**cada** tarefa cita arquivo:linha?"*, *"o plano aponta **o seam que vai tocar**?"*,
*"tem **seção** explícita de teste?"*. Essas exigem varrer o documento, quantificar sobre itens e
julgar suficiência estrutural. Está fora do alcance de 0.6b, e **nenhum formato de prompt corrige** —
11 variantes, incluindo os dois levers clássicos (think on/off e few-shot).

Reescrever a check para linguagem concreta **não mudou nada** (#9 vs #10: saída idêntica).

## Consequência direta para a fatia

1. **O critério de aceite §5 é inalcançável com este modelo.** §5 exige: *"um arquiteto REAL escreve
   um plano que VIOLA de propósito uma check → o local aponta EXATAMENTE essa falha com evidência"*.
   Medido: no plano `violado`, a check `file-line` (violada de propósito) recebeu **SIM/PRESENTE em
   todas as variantes não-constantes-NÃO**. A prova não pode ser produzida.

2. **O modo de falha medido é o ESPELHO do que o kickoff temia.** O kickoff alertava para resposta
   vazia ⇒ "check ausente" ⇒ falso negativo. O que se mede é o contrário: **falso POSITIVO
   sistemático** — o validador diria "todas as checagens presentes" para qualquer plano, inclusive
   os que violam. Com o push **LIGADO em produção** (decisão do grill), a seção
   `## Checklist do validador` entregaria ao arquiteto uma **falsa tranquilização** constante.
   Isso é pior que não ter o validador.

3. Todo o resto da fatia (seam, store V33, telemetria, kill-switch, seção do materializer, ADR-0044)
   **não depende** da acurácia do modelo — é substrato válido e testável com o local mockado.

---

# Rodada 2 — `qwen3:1.7b` (decisão do dono: medir um modelo maior)

Modelo puxado: `qwen3:1.7b` (id `8f68893c685c`, **1.4 GB**). Mesmo harness, mesmos 18 casos.

| Config | acertos | taxa-base | disse-SIM | erros |
|---|---|---|---|---|
| 1.7b + redação CONCRETA + think=false | 12/18 | 10 | 4/18 | 6 (FN 6, FP 0) |
| 1.7b + redação CONCRETA + think=true | 14/18 | 10 | 6/18 | 4 (FN 4, FP 0) |
| **1.7b + redação do SEED + think=true** | **17/18** | 10 | 9/18 | **1 (FN 1, FP 0)** |

**O 1.7b DISCRIMINA** — deixou de ser função constante (9 SIM / 9 NÃO, acompanhando a verdade).
Dois achados que contrariam o palpite:
- **`think` tem direção positiva, mas a magnitude não foi isolada.** As comparações CONTROLADAS da
  tabela: think (na CONCRETA) 12/18 → 14/18 · redação seed (com think) 14/18 → 17/18.
  `seed+think=false` **não foi medido** — atribuir "12→17" só ao think mistura as duas variáveis.
  No 0.6b o thinking *piorava*.
- **A redação do SEED (a sua, no banco) venceu a minha reescrita "concreta"** (17/18 vs 14/18,
  ambas com think=true). Não há nada a consertar no texto das checks da V32.

## Plano LONGO e realista (`probe8.py` + `probe9.py`) — a armadilha do texto de brinquedo

| Config | plano ~3k chars | truncadas | acertos |
|---|---|---|---|
| `num_predict=300` | limpo + violado | **3/6** | 4/6 (2 INDETERMINADO) |
| `num_predict=800` | limpo + violado | 0/6 | **6/6** |
| `num_predict=800/1500` (`probe9.py`) | plano ~7k chars (2150 tok) | 0/3 | **3/3** |

No `longo-violado` o modelo respondeu **NÃO exatamente para `file-line` e `test`** (as duas
violadas de propósito) e **SIM para `seam`** (a satisfeita) — **a forma exata da prova §5**.

## Orçamento de tokens — o gotcha do kickoff, reproduzido e domado (`probe7.py`)

| `num_predict` | respostas vazias | truncadas | acertos | latência |
|---|---|---|---|---|
| 150 | **14/18** | 17/18 | 4/18 (14 INDETERMINADO) | 1.96s |
| 300 | 0 | 0 | 17/18 | 2.56s |
| 600 | 0 | 0 | 17/18 | 2.60s |
| 1200 | 0 | 0 | 17/18 | 2.61s |

O gotcha é **real e reproduzido**: com orçamento apertado o raciocínio come tudo e a resposta vem
VAZIA. E a salvaguarda **funciona**: as 14 vazias viraram **INDETERMINADO**, zero delas virou
"check ausente" (erros=0 naquela linha). É esta a regra que vai pro teste.

Latência não cresce com `num_predict` folgado (o modelo para sozinho, `done_reason=stop`) ⇒ orçamento
generoso é de graça.

## CONFIG DE PRODUÇÃO (toda medida, nenhuma chutada)

```
model        qwen3:1.7b     # o 0.6b é preditor constante — inutilizável
think        true           # direção medida: 12→14 na CONCRETA; seed+think=false não medido (magnitude não isolada)
num-predict  1500           # 300 trunca em plano longo; 800 já basta; 1500 = folga grátis
temperature  0.0
```
Custo: ~3-6s por check × 3 checks ≈ 15s por run, **async, fora do caminho do settle**.
Acurácia agregada medida: **26/27 (96%)** — 1 falso negativo, **0 falso positivo**.

