# HANDOFF — R18-B: circuit-breaker de erro repetido (ADR-0018 teto (c)) — **PR #30 MERGED**

> **▸ Proveniência (fecho residual, 2026-07-15):** estado vivo da **faixa R18-B**, **congelado** e movido de
> `/HANDOFF-circuit-breaker.md` (raiz, untracked) para `docs/handoffs/` no fecho documental residual do v1 (R20-A).

Fatia B do fecho do v1 (item 3 da lista H, `docs/handoff-decisoes-lista-h.md`): implementa o
**3º teto de escalação** que a ADR-0018 prometeu (`:26-27`, *"erro se repetindo (circuit
breaker)"*) e que **nunca existiu** — o único freio de loop vivo era o teto de **atos** da
ADR-0016/T.4 (econômico: corta tarde, caro, sem diagnóstico).

## Estado

- **Branch:** `feat/circuit-breaker-adr0018` de `05d9b6d` (worktree isolado, já **removido**;
  commit `f85a876`). **PR #30 MERGED** na `main` (merge `fd3be27`) —
  https://github.com/matheus3006/agente-orquestrator/pull/30.
- **Piso:** **730 back** (723 pós-#29 + 7 novos) / **294 front** (intocado). Suíte inteira verde.
- **Vxx:** nenhum (breaker DERIVADO, zero coluna/tabela) → **V30 livre**. **Emenda 0018**, sem
  ADR nova → **0043 livre**.
- Base avançou durante a faixa: a R18-A (lote×Decision) mergeou como PR #29 (`a00815a →
  05d9b6d`); o worktree partiu de `05d9b6d`. Sem conflito de código (arquivos ortogonais).

## Mini-grill R18-B (2 params, respostas verbatim do dono)

1. **O limiar:** **"3 falhas (Recomendado)"**.
2. **O que para:** **"Só o par agente×issue (Recomendado)"** — o agente segue em outras issues;
   a issue segue para outros agentes.
3. **O que destrava:** **"Só wakeup manual direto (Recomendado)"** — a mão humana direta (root)
   fura o breaker. Gate FORA → o **`ApprovalService` NÃO foi tocado** (guarda de colisão com a
   R18-A não acionada).

## O que a fatia entregou

**Novos (main):**
- `execution/RepeatedFailureBreaker.java` — veredito DERIVADO do histórico. `avalia(agentId,
  issueId)` → `Optional<Tripped>`: pega os N desfechos FAILED/SUCCEEDED mais recentes do par
  (query `findRecentOutcomesByPair`); se todos FAILED → travado. `issueId` nulo nunca trava.
  Limiar via `@Value("${orquestrador.circuit-breaker.consecutive-failures:3}")`.
- `execution/RepeatedFailureNotice.java` — aviso M6 (`ConversationTurn` ANSWERED na conversa
  ancorada à issue, zero Run novo), voz = Questionador, idempotente por `[gatilho <runId>]`.
  Sem conversa → só WARN (fail-soft). Espelho de `conversation/ProposalDescarteNotice`.

**Modificados (main):**
- `execution/WakeupService.java` — check do breaker no enqueue: só quando `active.isEmpty() &&
  !lineage.isRoot()`; barra ANTES de criar wakeup/cobrar budget (retorna `WakeupOutcome.barrado()`).
- `execution/HeartbeatRunStore.java` — check no `promoteIfNeeded` (2º ponto): se par travado e
  deferidos non-root, não promove, avisa, descarta deferidos como `WakeupStatus.CANCELLED`.
- `execution/HeartbeatRunRepository.java` — `findRecentOutcomesByPair(agentId, issueId,
  desfechos, Pageable)` (JPQL, `order by createdAt desc`).
- `execution/WakeupOutcome.java` — 3º campo `blocked` + factory `barrado()` (compat 2-args mantida).
- `execution/lineage/SpawnLineage.java` — `isRoot()` (cadeia vazia = mão humana direta).
- `conversation/persona/PersonaContinuationPoster.java` — registra `RepeatedFailureNotice.isAviso`
  no `donoDeVoltaNaConversa` (senão o aviso seria lido como "dono voltou" e quebraria o
  roteamento de bastão entre personas — a "régua M9").

**Testes:** `execution/RepeatedFailureCircuitBreakerTest.java` (7, integração H2 real via motor):
3 falhas barram o wakeup automático · root fura (destrava) · par diferente (outro agente OU outra
issue) não afeta · intercalado com sucesso não abre · 2 falhas não abrem · promote (2º ponto)
barra · aviso M6 uma vez, ANSWERED, sem run, na conversa. Suporte:
`work/issue/IssueConversaTestSupport.java` (ancora issue↔conversa via package-private).

**Docs:** `docs/adr/0018-…md` (emenda: cascata FORA/escalação direta ao dono · teto (a)
não-promessa · teto (b)→v1.1 · teto (c) + fronteira). `docs/roadmap-interno.md` #11 (linha 213)
reescrito só com (b), corrigindo a frase refutada (`orchestrationLimitCents` = teto de ATOS da
ADR-0016/T.4, não de gasto).

## Decisões novas desta fatia (racional; não reabrir)

1. **Contador consecutivo por (agente, issue)** derivado — os N desfechos FAILED/SUCCEEDED mais
   recentes todos FAILED. CANCELLED/INTERRUPTED = abortos administrativos, ignorados (nem contam
   nem quebram); SUCCEEDED zera. Zero estado "aberto" persistido (recalculado sempre).
2. **Root fura** = o destravamento. Regra vive no call-site (`!lineage.isRoot()`), não no breaker
   (que é query pura sobre o par). Cada insistência do dono é consciente ("o freio é humano",
   como o caso B da correção).
3. **Promote barrado descarta deferidos como `CANCELLED`** (reuso do enum; não se perdem em loop).
4. **Aviso M6 no filtro de fluxo de personas** (correção — ver `PersonaContinuationPoster`).
5. **Fronteira aceita (v1):** run que morre com lease expirado nunca vira FAILED (reaper requeue
   RUNNING→QUEUED) → o contador não vê morte silenciosa. Anotada na emenda 0018.

## §VERIFY (`:18082` + pg efêmero PRÓPRIO; `:8080` e a R19-A/`:18081` INTOCADOS)

Todos os comandos + teardown re-pinados de 18081→18082 (a skill pina 18081; a R19-A ocupa a
:18081). Postgres REAL exercitou a query. Provado via o **promote** (caminho non-root motor-puro):
- par TRAVADO (3 FAILED do par): promote **barra** → 4 runs sem novo, 0 eventos promote, deferido
  `CANCELLED`;
- par NÃO travado (1 FAILED): promote **promove** → 3 runs com novo, 1 evento, deferido `COALESCED`;
- wakeup **root** via API `POST /wakeups` fura (`created:true`);
- anti-silêncio: WARN do breaker no log (par sem conversa).

O barramento no **enqueue** non-root exigiria o gate (setup pesado) e está coberto pelos 7 testes
de integração; o enqueue **root** (destravamento) foi provado via API.

## Pendências

- **Nenhuma.** PR #30 **MERGED** (`fd3be27`). A R19-A (feedback→executor) também mergeou (PR #31,
  `81e2423`) → as **3 fatias de código do v1** (lote×Decision #29, feedback→executor #31,
  circuit-breaker #30) estão TODAS na `main`. Resta o **fecho documental residual → prova final →
  v1 FECHADO** (não é mais desta faixa).
- Sem sobra técnica da fatia. Coordenação em `docs/controle-paralelismo.md` (seção "★ R18-B ✔").
- Colisão com a R19-A era só **roadmap**; ambas landaram (conflito de mesma linha, se houve, foi
  resolvido no merge de quem landou 2º). `AdapterRunExecutor`/routers `work/issue` não tocados.

> **▸ Desfechos (fecho residual R20-A, 2026-07-15):** **nada pendente** da fatia — as 3 fatias de código do v1
> estão na `main fd3be27` (piso canônico 736/294). O "fecho documental residual" citado é ESTA faixa (R20-A):
> criou a **linha própria da R18-B** na tabela da Frente B do roadmap (a fatia não tinha) e carimbou o blockquote
> das 3 fatias como MERGED. Arquivo movido para `docs/handoffs/`.
