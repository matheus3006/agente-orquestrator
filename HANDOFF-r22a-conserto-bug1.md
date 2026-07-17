# HANDOFF — R22-A: conserto do bug #1 do roadmap de ajustes — **FECHADA (PR #34 MERGED)**

> Registro da faixa **R22-A** (sessão satélite, 2026-07-16/17). UNTRACKED na raiz (precedente
> R20-A/R21-A). Ledger com o handoff de volta completo: `docs/controle-paralelismo.md`
> (Rodada 22, §Handoff de volta — R22-A). ⚠️ Os caminhos citados existem na **main `4c1b35f`**;
> o checkout compartilhado pode estar em OUTRA branch (ver §Notas).

## Implementado (tudo MERGED — PR #34, main `4c1b35f`, OK do dono 2026-07-16)

O bug: agente contratado pelo gate nascia mudo (`runtime_command` NULL) → run relâmpago no-op
verde (59ms, zero motor) → issue **DONE fantasma**. As 4 decisões grelhadas do dono aplicadas:

1. **Compositor da issue** — `backend/.../execution/IssuePromptComposer.java` (novo): briefing
   por-run `#N`+título+descrição+**estratégia** (lida do workspace da conversa pelo endereço
   V30); nunca null; ilegível → lacuna sinalizada no próprio briefing. Içado acima do galho
   turno/issue no `AdapterRunExecutor` (seam T.3 `acceptsPromptAsCommand`/`ORQ_PROMPT`).
2. **Trava no settle** — `Result.okNoop` nos 3 adapters → `RunOutcome.noop` (só com Issue) →
   `IssueService.settleAfterRun` estaciona em BLOCKED (nunca DONE nem review); run FAILED com
   erro legível; o breaker R18-B conta (desejado). Fora de Issue o "loop verde" segue.
3. **`runtime_command` = só script do arquétipo `process`** — adapter rico não o lê mais; o
   NULL do gate (HIRE e CREATE_TEAM) virou correto.
4. **V30** — `conversation.bastao_path` (last-wins, anotado em
   `PersonaContinuationPoster.postBaton`; sem backfill). `V30__conversation_bastao_path.sql`.

Emendas junto do código: **ADR-0037 §2** (âncora do grill) · **ADR-0011** (trava) ·
**ADR-0004** (contrato do adapter). Zero ADR nova. Piso **750 back / 294 front** re-medido na
main mergeada. Carona docs: roadmap `:92` corrigido + park-for-human desambiguado + bloco
estado-final refeito + linha do PR #33 + carimbo "v1 FECHADO pelo dono" + seção **"Roadmap de
ajustes"** (10 itens); `docs/handoffs/HANDOFF-prova-final-v1.md` (movido da raiz, com
proveniência).

## /verify (descartável :18081 + pg efêmero; vivo :8080/:3000 intocado)

Bug **reproduzido ANTES** na main `83c5fe9` (fluxo 100% real: cascata Q→P→E process → gate →
NULL → run 59,0ms/zero motor/DONE). Depois, com o jar do fix (V30 aplicada por Flyway em banco
POPULADO): **(g)** claude REAL citou o código-sentinela `PRC-7391-XY` que só existia na
estratégia do workspace da conversa · **(h)** caso B → corretiva ligada à #N com verbatim ·
**(i)** memoryFile da corretiva materializou a Decisão (R19-A) e o claude a citou no report ·
**trava**: FAILED legível + BLOCKED + ciclo REOPEN→conserto→DONE legítimo.

## Decisões fechadas (não reabrir)

- No-op = **falha do run** (trilha não finge verde); o guard vive **no settle**, não só no
  executor; anotação V30 **só quando o bastão posta** (dedup não move o endereço); estratégia
  ilegível → sinalizar no briefing, não falhar o run.
- Recusadas no grill (registradas na emenda 0037): "só a issue, arquiteto repassa" · "pela
  memória R19-A" · convenção ditada de nome · estratégia no banco · derivar prompt do role.
- Fixture `prova-v1` do VIVO intocada (contrato da faixa).

## Pendências / próximos passos

1. **Re-execução viva da #1 da `prova-v1`** (DONE incorreta no vivo) — massa da 1ª bateria da
   descoberta, com o dono. Fora desta faixa por decisão do controle.
2. **Carimbo do item 1 do "Roadmap de ajustes"** (`docs/roadmap-interno.md:224`, "EM PR" →
   "MERGED PR #34") — adiado: o arquivo estava com WIP de outra frente (ver Notas).
3. **D10** (web/Task in-run do claude) NÃO fechou de carona — segue pendência da descoberta.

## Notas (estado do ambiente ao encerrar)

- **⚠️ O checkout compartilhado está na `feat/backend-spring-ai-2` (Frente E), baseada em
  `83c5fe9` — PRÉ-fix:** não contém o PR #34 nem a V30 (a V31 deles numera correto, contando
  com a V30 da main). **Precisa de rebase/merge sobre `4c1b35f`** antes de qualquer prova de
  runtime naquela branch — sem isso, o comportamento do bug #1 (DONE fantasma) ainda existe lá.
- Durante o fecho havia WIP docs-only dessa frente solto na main (CONTEXT.md · ARQUITETURA ·
  adr-0001 · roadmap "Frente E" + ADR-0043/plano-spring-ai untracked) — preservado por
  stash→ff→pop (auto-merge limpo do roadmap); depois a frente o commitou na branch dela.
- Memória da sessão principal atualizada: `r22a-conserto-bug1-executada.md` (+ índice).
