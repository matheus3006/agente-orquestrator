# Roadmap interno — inventário de controle

> **Fonte única de CONTROLE** — "o que fizemos, o que falta, o que aguarda decisão".
> **Este arquivo é ÍNDICE.** O detalhe de cada frente mora na página linkada. Regra em
> [`docs/convencao-plano-e-ledger.md`](../convencao-plano-e-ledger.md); conformidade:
> `scripts/checar-plano.sh`.
>
> **Protocolo:** após cada grill OU execução — 1. atualize o estágio na página do ponto;
> 2. acrescente entrada no [ledger](roadmap.detalhe/ledger.html); 3. atualize a memória.

**Estado:** [ledger](roadmap.detalhe/ledger.html) — `main` `39dce6a` · último PR **#61** · fase de **conserto**.

## Pontos

| # | Ponto | Tipo | Estado | O que carrega | Detalhe |
|---|-------|------|--------|---------------|---------|
| R1 | Frente A — a fundação | inventário | VENDO/FECHADO | 28 ADRs implementados, com evidência-âncora por capacidade | [detalhe](roadmap.detalhe/R1-frente-a-fundacao.html) |
| R2 | Frente B — o v1 do CEO | inventário | FECHADO 2026-07-16 | As 17 fatias da camada de orquestração, PRs #1–#33 | [detalhe](roadmap.detalhe/R2-frente-b-v1-do-ceo.html) |
| R3 | Frente C — memória do CEO | inventário | COMPLETA | Átomo + graduação/cap + índice JIT; fechou 2 seams mortos | [detalhe](roadmap.detalhe/R3-frente-c-memoria.html) |
| R4 | **Roadmap de ajustes** | trabalho | **21 itens · 1 fechado** | O que a descoberta produziu — **reconciliado de 10 para 21 em 2026-07-27** | [detalhe](roadmap.detalhe/R4-roadmap-de-ajustes.html) |
| R5 | Backlog — candidatas v1.1 | backlog | 15 itens | Lacunas conhecidas e adiadas por decisão | [detalhe](roadmap.detalhe/R5-backlog-v11.html) |
| R6 | Frente E — baseline e Spring AI | inventário | F-PLAT0 ✅ · IA em OFF/SHADOW | Java 25/Boot 4.1 fechados; `ACTIVE` **não autorizado** | [detalhe](roadmap.detalhe/R6-frente-e-spring-ai.html) |
| R7 | Divergências doc × código | auditoria | 0 abertas · **congelada** | 48→25; alta e médias fechadas. **27 PRs sem varredura nova** | [detalhe](roadmap.detalhe/R7-divergencias.html) |
| R8 | Rastreabilidade e vetos | auditoria | 0 vetos pendentes | 40 handoffs mapeados; decisões do dono, todas resolvidas | [detalhe](roadmap.detalhe/R8-rastreabilidade.html) |

## Onde está o trabalho vivo

O único ponto com trabalho pendente é o **R4**. A **ordem de execução** dele não mora aqui — mora
em [rumo-ao-uso-real.md](rumo-ao-uso-real.md), que sequencia os itens que bloqueiam o uso do
orquestrador nos projetos do dono.

O estoque de caça residual (394 🎯 + 136 pós-reboot) é do
[plano de caça](descoberta-de-bugs.md).

## Duas dívidas de auditoria, nomeadas

1. **A varredura doc × código está congelada em `fd3be27` (2026-07-15)** — 27 PRs atrás, e a Frente E
   sozinha tocou 101 arquivos. Toda âncora `file:line` das páginas R1–R3 carrega esse drift.
2. **Os handoffs posteriores a 2026-07-15 não estão mapeados** no R8 — vivem como `HANDOFF-*.md`
   gitignored na raiz, registrados só no ledger.
