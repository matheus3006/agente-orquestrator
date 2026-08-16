# Plano — rumo ao uso real

> **Objetivo do dono:** poder apontar o orquestrador para os projetos dele e desenvolver com ele.
> **Este arquivo é ÍNDICE.** O detalhe de cada ponto mora na página linkada. Limite e regra em
> [`docs/convencao-plano-e-ledger.md`](../convencao-plano-e-ledger.md). Conformidade:
> `scripts/checar-plano.sh`.

**Estado:** [ledger](rumo-ao-uso-real.detalhe/ledger.html) — `main` `39dce6a` · 1 de 8 pontos concluídos.

## Pontos

| # | Ponto | Tipo | Estado | O que decide | Detalhe |
|---|-------|------|--------|--------------|---------|
| P1 | A suíte nunca rodou o schema que a aplicação embarca | grill | ✅ fechado | Régua vai para **Postgres real** (D-SCHEMA-1..9); 191 testes a triar em 2 fatias | [detalhe](rumo-ao-uso-real.detalhe/P1-grill-do-schema.html) |
| P2 | Uma chamada derruba uma Issue viva | fatia | kickoff pronto | Produtor e coletor do lock discordam do invariante | [detalhe](rumo-ao-uso-real.detalhe/P2-f2-produtor-coletor.html) |
| P3 | Você aperta "parar" e o sistema age mesmo assim | fatia | kickoff pronto | Os 4 gates disparam em nome de run sem autoridade | [detalhe](rumo-ao-uso-real.detalhe/P3-fatia2-posse-do-run.html) |
| P4 | O cinto protege o quintal, não a casa | fatia | a escrever | Pré-requisito: com projeto real na pasta, o furo muda de categoria | [detalhe](rumo-ao-uso-real.detalhe/P4-furo-do-guard.html) |
| P5 | O orquestrador não tem código no modelo dele | grill | a escrever | Credencial, merge, fronteira do reaper — o desenho do repositório | [detalhe](rumo-ao-uso-real.detalhe/P5-grill-repositorio.html) |
| P6 | Construir o repositório no modelo | fatias | não desenhado | O ponto em que ele passa a mexer nos seus projetos | [detalhe](rumo-ao-uso-real.detalhe/P6-fatias-do-repositorio.html) |
| P7 | O sistema sabe e não te conta | fatia | a escrever | 4 falhas silenciosas — o que impede deixar rodando sozinho | [detalhe](rumo-ao-uso-real.detalhe/P7-lote-falha-silenciosa.html) |
| P8 | A org trabalha, mas não se organiza | grill | a escrever | N4 — o buraco entre "agente bom com board" e "organização" | [detalhe](rumo-ao-uso-real.detalhe/P8-org-nao-decompoe.html) |

## Ordem e paralelismo

- **Agora:** P2 e P3 (os 2 slots da regra de faixas em voo). **P1 fechou em 2026-08-16** e produziu 3 fatias
  de implementação (A → B → C) descritas em `HANDOFF-grill-schema-flyway-h2.md`; quem as agenda e emite o
  kickoff é a sessão de controle. **13 classes de teste dessas fatias colidem com P2/P3** — ver o detalhe.
- **Em paralelo, quando houver sessão:** P4 e P5 — independentes entre si e dos três acima.
- **Depois:** P6 (exige P4 e P5 fechados) → P7 → P8.

O racional de cada posição está na página do ponto, na seção *"por que este ponto vem aqui"*. Duas
posições são explicitamente discutíveis e trazem o contra-argumento escrito: a do **P7** e a do **P8**.

## Fronteira de arquivo entre P2 e P3

| P2 é dona de | P3 é dona de |
|---|---|
| `IssueService` · `IssueRepository` · `LockOrfaoReaper` · ficha · front | `AdapterRunExecutor.java` inteiro, inclusive javadoc |
