# HANDOFF — Grill Spring AI: desenho dos 2 usos (verificador auxiliar) — **DESENHO FECHADO**

> Janela de **grill/desenho** (sessão satélite, 2026-07-17), a partir da PAUTA DO GRILL da sessão de
> controle #3. Consumiu o produto da R24-A (`docs/pesquisa-spring-ai-melhorias.md`) e fechou, com o
> dono, o desenho de DOIS usos novos do Spring AI. **NÃO implementou nada** — janela de grill: zero
> código, zero branch, zero ambiente vivo. Norte herdado: `memory/frente-e-spring-ai-racional.md`
> (verbatim do dono) + `scratchpad/racional-spring-ai-verbatim-dono.md`.

## Produto (o que foi FEITO nesta janela)

- **`docs/desenho-spring-ai-verificador-auxiliar.md`** (untracked, ~12,7 KB) — o **contrato de rumo**
  dos 2 usos, APROVADO pelo dono. Fonte canônica pro fatiamento/execução. Contém: princípio-mãe · Uso A
  · Uso B · §3 Invioláveis · §4 Fatiamento previsto · §5 "Como se prova" (anti "disse que fez").
- 2 memórias novas: `memory/desenho-spring-ai-verificador-auxiliar.md` (o desenho) e
  `memory/nao-declarar-implementado-sem-prova.md` (feedback do dono).
- `memory/MEMORY.md` compactado (21,4 → 13,7 KB; estava perto do limite de leitura) — nenhuma entrada
  perdida, 1 memória órfã (`faixas-paralelas-isolar-worktree`) re-linkada.

## Decisões fechadas (NÃO reabrir)

**Princípio-mãe (verbatim do dono):** o modelo local é sempre **verificador AUXILIAR de peso menor**
que **SOMA** à IA forte (claude/codex) ou à busca determinística — **nunca substitui**; só checagens
**simples/pontuais**; a IA forte tem peso maior e palavra final.

**Escopo:** implementar OS DOIS usos. Uso A **sem** o pré-passo de medir recall — o dono descartou
explicitamente ("não me importa se vale a pena, coloca").

**Uso A — busca (skills E memória), local CONFERE:**
- Atua só na busca **"por parecido"** (`memory_search` + skill search `pg_trgm`), **NÃO** na exata por
  `#N` (`memory_fetch`). NÃO altera o ranking.
- Se a busca não trouxe o que precisava → **avisa o agente** (Opção **ii**), que decide (buscar de
  novo/alargar). Re-busca automática (iii) = NÃO agora.
- **Nasce em sombra** (correção registrada: o desenho inicial dizia "sombra não se aplica" — errado, o
  conferente influencia o agente).
- **Trava #9 intacta:** memória segue por `#N`, sem vetor; na memória o conferente vê só os
  **metadados** do `memory_search`, nunca o corpo. Supera a F-SAI4 da pesquisa (que reordenava com
  embeddings — o dono escolheu o caminho conservador).

**Uso B — validador de planos (o plano do arquiteto seguiu as regras?):**
- Checagens = perguntas **sim/não factuais** (presença no texto, nunca opinião).
- **Moram no MOLDE DO PAPEL do arquiteto** (`role_template_version`), NÃO em cada skill — skills baixadas
  do GitHub chegam sem checagens e por-skill não escala. Skills do arquiteto (`tdd`, `diagnosing-bugs`)
  só inspiram quais regras escrever.
- **Dupla função:** o mesmo conjunto (i) entra no briefing que o arquiteto recebe (`IssuePromptComposer`)
  e (ii) alimenta o validador. **Escopo por papel** — decisão **reversível**, o dono avalia com o uso
  real quando estiver no ar.
- Local confere item a item com evidência → checklist volta pro **PRÓPRIO arquiteto**, que se corrige
  num re-run (**Opção A**; reusa o padrão PUSH da R19-A). Nasce em sombra.

**Feedback do dono (durável):** "às vezes você diz que implementou mas não implementa." → "pronto" =
comportamento observado no motor real + evidência citada, nunca "testes verdes" nem prosa. Formalizado
no §5 do doc (fecho de cada fatia só com `/verify` no motor real).

## Pendências / próximos passos

- **Construir as 2 fatias** — FORA desta janela (é grill). Vira sessão(ões) de execução, cada uma
  fechada com `/verify` no motor real (plano/busca bom E ruim), padrão do projeto. Dono priorizou o
  **Uso B**.
- Detalhes que ficam pro fatiamento/execução (não são decisão de rumo): o texto exato do aviso (Uso A);
  ordem skill-vs-memória; onde declarar as checagens no `role_template_version`; a telemetria do sombra.
- **Dependência de ambiente:** Ollama + qwen **não estão no ar em lugar nenhum hoje** (nem instalado,
  nem container; `application.yml` segue `mode: off` na main). Quem for construir/testar sobe o modelo
  primeiro — os testes da Frente E usavam mock, o qwen real só rodou num experimento pontual.

## Notas

- Correção de premissa feita ao dono: o uso ATUAL do Spring AI (linter de contexto em sombra) hoje está
  `off` e o parecer não é consumido por ninguém — o dono confirmou que não é esse uso que quer; os 2
  usos deste desenho é que dão propósito à peça.
- O grill rodou por partes (papel do Spring AI → princípio → Uso A → Uso B → quem revisa → onde moram as
  regras). Duas decisões minhas furadas foram corrigidas pelo dono no caminho e só a forma final ficou
  nos registros: (1) "checagens por skill" → por papel; (2) "sombra não se aplica ao A" → se aplica.
