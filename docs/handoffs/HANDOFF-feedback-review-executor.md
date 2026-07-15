# HANDOFF — R19-A: feedback do review → executor — **MERGED (PR #31)**

> **▸ Proveniência (fecho residual, 2026-07-15):** estado vivo da **faixa R19-A**, **congelado** e movido de
> `/HANDOFF-feedback-review-executor.md` (raiz, untracked) para `docs/handoffs/` no fecho documental residual (R20-A).

> Fatia C do fecho do v1 (rodada 19) — a **ÚLTIMA fatia de CÓDIGO do v1**. Entrega ao executor,
> no re-run, o feedback do REJECT que já virava Decisão mas nunca chegava nele.
> Fonte da verdade do plano/decisões do dono: `docs/handoff-decisoes-lista-h.md` item 1 (R17-B).
> Ledger da orquestração: `docs/controle-paralelismo.md` (linha R19-A + bloco de handoff ~:2009).
> Detalhe do desenho: emenda R19-A na `docs/adr/0011-recovery-issue-travada-park-for-human.md`.
> UNTRACKED de propósito.

## Estado

- **Branch `feat/feedback-review-executor-adr0011`** de `main 05d9b6d` (commit `5acab40`) →
  **[PR #31](https://github.com/matheus3006/agente-orquestrator/pull/31) MERGED pelo Matheus**
  (2026-07-15T12:15Z; merge commit `81e2423`). O "OK do Matheus" foi ele mesmo mergeando.
- **`origin/main` = `fd3be27`**: **R18-B (circuit-breaker, PR #30) mergeou logo depois** (12:16Z) —
  as **3 fatias de código do fecho do v1 landaram** (R18-A/#29 · R19-A/#31 · R18-B/#30).
- **`main` LOCAL ainda em `05d9b6d`** (HEAD devolvido em main, como o kickoff pediu). **O ff do
  checkout compartilhado para `fd3be27` + a re-medição do piso são passo da sessão de controle**
  (precedente R18-A: "ff feito, piso re-medido").
- Os 5 untracked de contrato intocados (+ este HANDOFF). Ledger com o bloco de handoff da R19-A.
- Piso: **729 backend** (piso 723 + 6) / **294 front** (intocado) na branch. **736 projetado na
  main pós-#30+#31** (723 + 6 R19-A + 7 R18-B — zero sobreposição de arquivo de teste).
- Sem migração (V30+ livres) · sem ADR nova (0043 livre) · **emenda ADR-0011**.

## O que a fatia entregou

**O gap** (verificado em `05d9b6d`): o REJECT do review já exige motivo e esse motivo já vira
Decisão endereçada (`#N`, grau=DECISAO, ancorada na review **e** na issue via `Anchor.NaReview`).
**Mas o executor nunca a recebia no re-run** — a premissa da ADR-0011 ("editar a Issue antes do
REOPEN") estava morta. Não faltava endpoint: faltava **ENTREGA**.

**A entrega — +1 dieta DIRECIONADA na ponta do executor (PUSH, não pull):**
- `DecisionRepository.findByCompanyIdAndIssueIdOrderBySeqAsc` — finder novo (nenhum existia por issue).
- `CompanyMemoryMaterializer` — ganha `issueId` (5-arg + overload 4-arg=`null` p/ o durável) e, quando
  há issueId, materializa a **seção do feedback**: as Decisões ancoradas naquela issue (reject
  `anchoredToReview` + caso B `anchoredToIssue`), com `#N` + racional, ordem de `seq`.
- `AdapterRunExecutor:242` — passa o `runContext.issueId()` na materialização. **O gap exato:** o
  issueId que ele já tinha em mãos não fluía pra dieta.

**Fronteira C10.b do `MemoryQuery` INTOCADA:** a Decisão do reject (grau=DECISAO, tipo
OPERACIONAL/TECNICA) **segue board-only** no `memory_fetch`. A entrega é caminho **NOVO e
DIRECIONADO** (só a issue do run), **não** alargamento do trilho JIT selado. Convivem por
construção (o kickoff proibiu "resolver" a tensão reabrindo qualquer decisão). Ponta do CEO
inalterada (issueId nulo → sem seção). Os testes de `MemoryQuery` seguem verdes SEM edição.

**Arquivos (7 tracked):** 4 produção (`DecisionRepository` · `CompanyMemoryMaterializer` ·
`AdapterRunExecutor` — só :242+comentário · `IssueService` — só o comentário stale :221) · 1 teste
(`CompanyMemoryMaterializationTest`, +6) · 2 docs (`adr/0011` Emenda R19-A · `roadmap-interno.md`).

## Decisões novas desta fatia (racional — não reabrir)

1. **Overload 4-arg `materialize(...)`=`materialize(..., null)`** — o run durável (turno do CEO, run
   sem issue) não tem conceito de issue; o overload preserva os testes de split da F-C3 byte-a-byte e
   mantém o `AdapterRunExecutor` como único caller 5-arg. Não é seam raso ("sem contexto de Issue" é
   caso real).
2. **A seção acende por `issueId != null`, NÃO por `audience==EXECUTOR`** — no motor `CEO ⟹ issueId
   nulo` e `issueId != null ⟹ EXECUTOR` (invariante do executor), então equivalem; `issueId != null`
   é a expressão direta ("entregue SE há issue") e deixa a ponta do CEO inalterada por construção.
3. **A seção RENDERIZA o racional** de propósito — este caminho ENTREGA (o agente selado não tem
   canal inbound), diferente do `memory_fetch` onde o log cru é board-only. É por isso que convive
   com a fronteira sem alargá-la.

## §VERIFY (`:18081` + pg efêmero `fb-review-pg`; `:8080`/`:5432` e a R18-B `:18082` INTOCADOS)

Cenário real via API + scheduler vivo, motor `process`:
- Issue de alto risco (riskLevel=5) → run#1 sucesso → **PENDING_REVIEW** → **REJECT com motivo real**
  (`REJECT_9F3K: falta cobrir o refresh token expirado…`) → **BLOCKED** → **REOPEN** → re-run.
- O `.claude/CLAUDE.md` do run continha o feedback **LITERAL** com `#N` — mtime casando o re-run
  (run#1, pré-reject, não podia tê-lo escrito → prova que foi o re-run que materializou).
- **Vantagem-de-agente (lição F-C3):** o próprio `process` fez `cat` do seu memoryFile e o StepTrace
  mostrou o feedback → `RUN_SUCCEEDED` — o agente RECEBE, não precisa buscar.
- **Probe:** 2º reject → a seção passou a listar `#1` e `#2` ordenados por `seq`.
- Confirmação no pg: a Decisão do reject = grau=DECISAO, tipo=OPERACIONAL, `issue_id` preenchido →
  fora do selado, como o desenho manda. Teardown feito.

## Pendências

- ✅ **MERGE FEITO** pelo Matheus (PR #31, 2026-07-15T12:15Z, merge commit `81e2423`).
- ⏳ **ff do checkout compartilhado (`main` local 05d9b6d → `fd3be27`) + re-medição do piso (esperado
  736/294)** — passo da sessão de controle (precedente R18-A). `origin/main` já em `fd3be27`.
- **Fecho do v1:** com a R19-A a **última fatia de CÓDIGO fecha**. Resta o **fecho documental
  RESIDUAL** (docs-only) + a **PROVA FINAL** → **v1 FECHADO**. (Lista residual em
  `HANDOFF-frente-c.md` §Pendências e no ledger.)
- **Sobra menor docs-only** (fora desta faixa): a linha da R18-A no `roadmap-interno.md` ("pende OK
  p/ merge") ficou stale pós-#29 — já rastreada pela sessão de controle para o fecho documental.

> **▸ Desfechos (fecho residual R20-A, 2026-07-15):** merge = **feito** · ff da main local + re-medição do piso =
> **feitos** (piso canônico **736/294** carimbado na `fd3be27` pela sessão de controle) · o **fecho documental
> RESIDUAL é ESTA faixa (R20-A)** — `lista-h-fecho-v1.md` §6 corrigida, roadmap varrido, HANDOFFs congelados;
> resta a **PROVA FINAL** (operador) → declaração "v1 FECHADO" · a sobra do roadmap (linha R18-A "pende OK") =
> **corrigida nesta faixa**. Arquivo movido para `docs/handoffs/`.
