# Roadmap interno — MIGRADO

> **Este arquivo mudou de lugar em 2026-07-27.**
>
> ### → **[`docs/planos/roadmap.md`](planos/roadmap.md)**

Ele continua aqui porque **9 documentos apontam para este caminho** (incluindo a ADR-0019 e 4
handoffs); apagá-lo deixaria essas referências penduradas. O conteúdo, porém, vive agora no índice
acima e nas páginas de detalhe.

## Por que migrou

O arquivo tinha **553 linhas** misturando inventário de 5 frentes, backlog, divergências, vetos e
histórico — e **não era atualizado desde 2026-07-17 10:41**. Duas consequências medidas:

1. **O roadmap de ajustes parava no item 10.** Os itens **#11–#21** tinham sido numerados pelas
   faixas de descoberta e **nunca foram escritos** — quem abrisse o arquivo lia um retrato
   incompleto sem saber disso.
2. **Os PRs #34–#61 não apareciam em lugar nenhum** — todo o conserto de duas semanas era invisível
   para quem consultasse o roadmap.

A migração para [a convenção de plano e ledger](convencao-plano-e-ledger.md) reconciliou as duas
coisas. Regra: o índice é raso por contrato, o detalhe é fundo, e o ledger vive fora do plano.

## Onde cada seção foi parar

| Seção original | Agora em |
|---|---|
| Frente A — fundação (28 ADRs, A.1–A.10) | [R1](planos/roadmap.detalhe/R1-frente-a-fundacao.html) |
| Frente B — v1 do CEO | [R2](planos/roadmap.detalhe/R2-frente-b-v1-do-ceo.html) |
| Frente C — memória do CEO | [R3](planos/roadmap.detalhe/R3-frente-c-memoria.html) |
| **Roadmap de ajustes** (era 10 itens, agora **21**) | [R4](planos/roadmap.detalhe/R4-roadmap-de-ajustes.html) |
| Frente D — candidatas v1.1 | [R5](planos/roadmap.detalhe/R5-backlog-v11.html) |
| Frente E — baseline e Spring AI | [R6](planos/roadmap.detalhe/R6-frente-e-spring-ai.html) |
| § Divergências doc × código | [R7](planos/roadmap.detalhe/R7-divergencias.html) |
| § Rastreabilidade de handoffs · § Vetos pendentes | [R8](planos/roadmap.detalhe/R8-rastreabilidade.html) |
| § Histórico de grills & auditorias | [ledger](planos/roadmap.detalhe/ledger.html) |

*(A § Nota de estrutura era uma proposta de reorganização das Frentes A–D, decidida como "só
sinalização" — foi absorvida pela própria estrutura do índice novo.)*
