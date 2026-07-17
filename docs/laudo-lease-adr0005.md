# Laudo — ADR-0005: o que acontece quando um run vivo excede o lease de 2min

**Data:** 2026-07-10 · **Natureza:** investigação READ-ONLY (nenhum patch, nenhuma migração, nenhum restart). Arquivo untracked — NÃO commitar (insumo de decisão, não documentação permanente).
**Divergência auditada:** a ADR-0005 afirma *"o worker renova o lease"* (`docs/adr/0005-modelo-heartbeat-execucao.md:12`); no código não existe renovação alguma.

> **FECHO (2026-07-10, sessão de complemento):** as queries da §4 RODARAM no DB vivo e a sonda JUnit da §5 RODOU (PASS). **A predição falsificável da §4 foi CONFIRMADA** — zero runs com 2+ `RUN_STARTED` em toda a história do banco, incluindo um run de 12min58s (6,5× o lease) que terminou intocado como `attempt=1`. A análise da §3 (thread única = mitigante real) está corroborada por evidência histórica E por prova executável.
>
> **DECISÃO DO MATHEUS (2026-07-10): opção (a) — implementar renovação de lease.** A implementação sai em faixa própria (esta sessão era read-only); o trecho de lease da ADR-0005 só se edita DEPOIS da R1-E landar (mesma ADR, faixa do SKIP LOCKED).

---

## 1. Veredito em uma frase

**Hoje, na topologia real (1 nó, scheduler de 1 thread), o lease expirado é INALCANÇÁVEL enquanto o worker vive — double-work é impossível na prática; mas o desenho no banco PERMITE double-work do MESMO run (o índice `uq_run_active_agent` NÃO barra esse caso), e a bomba arma no dia em que houver 2º nó, pool de scheduler > 1 ou virtual threads — exatamente o cenário multi-nó que a ADR-0005 diz suportar.**

---

## 2. Linha do tempo do cenário (rastreada no código, file:line)

Cenário hipotético: run de agente real que leva 5min (rotineiro), com reaper capaz de interleave (multi-nó ou pool > 1).

| t | O que acontece | Evidência |
|---|---|---|
| t0 | Worker A faz o claim CAS `QUEUED→RUNNING`, `lease_owner=node-A`, `lease_expires_at=t0+2min`, `attempt=1`; grava evento `RUN_STARTED` | `HeartbeatDispatcher.java:81` + `:30` (lease fixo `Duration.ofMinutes(2)`); CAS em `HeartbeatRunRepository.java:64-73`; evento em `HeartbeatRunStore.java:43` |
| t0…t5min | Execução SÍNCRONA do processo do agente (CLI real), sem timeout e **sem qualquer renovação de lease** | `HeartbeatDispatcher.java:89` → `AdapterRunExecutor.java:249` (`adapter.execute`) → `LocalExecutionTarget.java:385` / `DockerExecutionTarget.java:505` (`process.waitFor()` sem timeout). Grep por `renew`/`extendLease`/`renova` no motor: vazio |
| t0+2min | Lease vence. O run continua `RUNNING` no banco; o processo continua vivo | — |
| t0+2min≤5s | Reaper (a cada 5s, `HeartbeatScheduler.java:43`) roda `requeueExpired`: `RUNNING→QUEUED`, `lease_owner=NULL`, `lease_expires_at=NULL`. **Não vai a RECOVERING** (status morto: nunca é atribuído em lugar nenhum do main — só lido em índice/queries). O reaper **não distingue worker morto de worker vivo** | `LeaseReaper.java:18-20` → `HeartbeatRunStore.java:80-83` → `HeartbeatRunRepository.java:85-92` |
| t0+2min+ε | Próximo dispatch (a cada 1s, `HeartbeatScheduler.java:38`) encontra o run `QUEUED` e faz o **2º claim DO MESMO run**: `attempt=2`, `lease_owner=node-B`, 2º evento `RUN_STARTED`. **`uq_run_active_agent` não barra**: o índice (`V3__heartbeat.sql:26`) impede uma 2ª LINHA ativa por agente — aqui é a MESMA linha reexecutada | `HeartbeatDispatcher.java:49` (findTop50 QUEUED) + `:81` (claim) |
| t0+2min+ε | O 2º processo de agente sobe **sobre o MESMO workspace** do 1º (workspace é por company/agent/issue, `WorkspaceResolver`), com o 1º processo ainda escrevendo nele. O checkout da Issue **não é barreira**: é idempotente para o mesmo runId (`execution_lock_run_id is null OR = :runId`) | `AdapterRunExecutor.java:156`; `IssueRepository.java:49-60` (comentário na linha 44: *"reentrar com o proprio runId casa o WHERE"*) |
| t0+5min | Worker A termina. `finishSuccess(runId, "node-A")` → `finalizeRun` exige `lease_owner = :owner`; o owner agora é node-B → **0 linhas, cai no vazio SILENCIOSAMENTE** (sem evento, sem log, sem promote) | `HeartbeatRunStore.java:64-69`; guard em `HeartbeatRunRepository.java:76-83` |
| t0+5min | **MAS os efeitos colaterais do worker A já aconteceram fora do CAS**: settle da Issue (DONE/PENDING_REVIEW + liberação do lock — o guard é `execution_lock_run_id = runId`, o MESMO runId das duas tentativas, então o settle do A VALE), captura de preview, settle de turno de conversa, CostEvents no ledger, StepTrace | `AdapterRunExecutor.java:103-114` (preview+settle), `:262` (recordCosts), `:242` (StepSink→`recordStep`); `IssueService.java:99-118` |
| t0+6min | Worker B termina. Se a Issue já foi liquidada pelo A, o settle do B é no-op (lock liberado); o `finalizeRun` do B vale e define o status final do run — **o resultado do attempt 2 vence, o do attempt 1 evapora do heartbeat_run** | mesmos pontos acima |

### Efeitos colaterais concretos do double-work (quando alcançável)

- **Trabalho duplicado de verdade**: dois CLIs de agente executando a mesma Issue simultaneamente no MESMO diretório/volume → corrida de workspace (escritas intercaladas, artefatos meio-a-meio), custo/tokens em dobro no ledger (`recordCosts` roda nas duas tentativas — `AdapterRunExecutor.java:262`), StepTrace intercalado, 2× `RUN_STARTED`.
- **Run de turno de conversa (CEO)**: o attempt 2 chama `turnCoordinator.claimFor` e **não encontra turno** (o attempt 1 já o reivindicou) → roda o `runtimeCommand` pelado, sem prompt (`AdapterRunExecutor.java:171-174`) — queima tokens à toa.
- **Mitigante fino que JÁ existe**: `RUN_OPEN` de budget só é cobrado no `attempt == 1` (`HeartbeatRunStore.java:47-51` — o comentário mostra que o autor PREVIU a reexecução pelo reaper). Ou seja: o código foi desenhado para reexecução-de-worker-MORTO; o caso worker-VIVO ficou descoberto.

---

## 3. Por que hoje isso NÃO acontece (o mitigante real, e ele não é o índice)

**O mitigante dominante é a thread única do scheduler**, não o `uq_run_active_agent`:

- `@EnableScheduling` sem qualquer customização (`config/SchedulingConfig.java:7-8`) e nenhum `spring.task.scheduling.pool.size` no `application.yml` → Spring Boot cria o `ThreadPoolTaskScheduler` default com **pool = 1**.
- `dispatch()` e `reap()` são `@Scheduled` no MESMO pool (`HeartbeatScheduler.java:38-46`). Enquanto `dispatchOnce()` está bloqueado dentro do `executor.execute(...)` síncrono (o run de 5min), **o reaper fisicamente não roda** — os ticks dele só disparam depois que o dispatch inteiro devolve a thread.
- Quando a thread volta, a sequência é: `execute` retorna → `finishSuccess/finishFailure` **na mesma thread, no mesmo `dispatchOnce`** (`HeartbeatDispatcher.java:89-98`) → só então o reaper enfim roda e não encontra mais nada `RUNNING` vencido.
- Portanto **não existe janela**: do ponto de vista do reaper, o run passa de RUNNING-vencido a SUCCEEDED/FAILED atomicamente. O único RUNNING-vencido que o reaper encontra é o de worker realmente morto (crash/restart da JVM) — exatamente o caso que ele recupera bem (coberto por `HeartbeatReaperTest.java:49-71`).

**Corolário importante:** a cobertura de teste existente só prova o caso worker-MORTO. Não há teste do caso worker-VIVO — coerente com ele ser inalcançável hoje.

### O que ARMA a bomba (qualquer um destes)

1. **2º nó do backend** (o objetivo declarado da ADR-0005: *"Multi-nó possível desde cedo"*) — o reaper do nó 2 roda enquanto o worker do nó 1 executa.
2. `spring.task.scheduling.pool.size > 1` — uma linha de config.
3. `spring.threads.virtual.enabled=true` — os `@Scheduled` deixam de se serializar (scheduler vira virtual-thread-per-task).
4. Dispatch assíncrono futuro (executar runs fora da thread do scheduler — a tentação natural quando quiserem agentes em paralelo).

Nota colateral visível HOJE: runs de 5min+ atrasam todos os outros jobs do mesmo pool (reaper de preview, GC de workspace, GC de rows, thumbnail) — sintoma da mesma thread única.

---

## 4. Evidência histórica no DB vivo — EXECUTADA (2026-07-10, sessão de complemento)

> Histórico: a sessão original rodou num modo que auto-negava tudo fora do allowlist e nenhuma consulta saiu. A sessão de complemento (permissões normais) rodou TODAS abaixo, via `docker exec -i agente-orquestrator-db-1 psql -U orquestrador -d orquestrador` (nota: o container real é `agente-orquestrator-db-1`, com "orquestrator" — a grafia "orquestrador" do rascunho original não existe no host).

Queries read-only e os resultados REAIS:

```sql
-- (a) Panorama: quantos runs excederam 2min, por status; quantos foram reclamados (attempt>1)
select status, count(*) total,
       count(*) filter (where finished_at - started_at > interval '2 minutes') as over_2min,
       count(*) filter (where attempt > 1) as attempt_gt_1,
       max(finished_at - started_at) as max_dur
  from heartbeat_run group by status order by status;

-- (b) Os runs longos: duração vs attempt (attempt>1 = reclamado; started_at é o do ÚLTIMO claim)
select id, status, attempt, created_at, started_at, finished_at,
       finished_at - started_at as dur_ultimo_claim,
       finished_at - created_at as dur_total
  from heartbeat_run
 where finished_at - created_at > interval '2 minutes'
 order by finished_at - created_at desc limit 20;

-- (c) A prova direta de reexecução: runs com 2+ RUN_STARTED no rastro
select run_id, count(*) as started
  from heartbeat_run_event where type = 'RUN_STARTED'
 group by run_id having count(*) > 1 order by started desc;

-- (d) Linha do tempo de um run suspeito (substituir :RUN)
select created_at, type, left(coalesce(detail,''), 80)
  from heartbeat_run_event where run_id = :RUN order by created_at;

-- (e) Existe algum RUNNING vencido AGORA (worker vivo com lease estourado, pré-reap)?
select id, agent_id, lease_owner, lease_expires_at, now() - lease_expires_at as vencido_ha
  from heartbeat_run where status = 'RUNNING' and lease_expires_at < now();
```

**Predição do laudo (falsificável):** (c) deve voltar VAZIO ou só runs cujo attempt anterior morreu com a JVM (restarts do :8080) — nunca um caso de worker-vivo reclamado — porque a thread única torna o caso inalcançável. Se (c) voltar um run com 2 `RUN_STARTED` separados por ~2min SEM restart do backend no meio, a análise da §3 está errada e a renovação vira urgente. Atenção ao ler (b): `requeueExpired` NÃO zera `started_at`; o claim seguinte o sobrescreve — `dur_ultimo_claim` mede só a última tentativa.

### Resultados reais (2026-07-10) — **PREDIÇÃO CONFIRMADA**

**(a) Panorama** — 70 runs na história do banco; `attempt_gt_1 = 0` em TODOS os status (nenhum run jamais foi reclamado):

```
  status   | total | over_2min | attempt_gt_1 |     max_dur
-----------+-------+-----------+--------------+-----------------
 FAILED    |     8 |         0 |            0 | 00:00:04.103936
 RUNNING   |     2 |         0 |            0 |
 SUCCEEDED |    60 |         9 |            0 | 00:12:58.33787
```

**(b) Runs longos** — 9 runs acima de 2min (descontando 3 linhas seed `f14a0*` com `attempt=0`, sobram 6 reais); TODOS `attempt=1`. O campeão: `1006cca1` com **12min58s** — 6,5× o lease, e o reaper nunca o tocou. Demais reais: 5min24s, 4min37s, 3min35s, 3min21s, 2min31s — todos `attempt=1`, todos SUCCEEDED.

**(c) A prova direta** — **0 linhas.** Nenhum run com 2+ `RUN_STARTED` em toda a história — nem sequer o caso worker-morto-com-restart apareceu (não houve crash de JVM com run em voo até hoje).

**(d) Linha do tempo do run de 12min58s (`1006cca1`, 2026-07-08)** — um único `RUN_STARTED` (03:23:15), trabalho contínuo de agente (delegação Lyra, collab_tool_call de 3min, file_change), `RUN_SUCCEEDED` às 03:36:13 — **11 minutos após o lease vencer**, sem requeue, sem 2º claim. É a §3 fotografada ao vivo: enquanto o dispatch bloqueava a thread única, o reaper fisicamente não rodou.

**(e) RUNNING vencido agora** — 0 linhas com lease vencido. *Observação colateral (fora do escopo deste laudo):* existem 2 linhas `RUNNING` com `attempt=0`, `started_at/lease_owner/lease_expires_at` todos NULL (`bc48fd43…` e a sintética `b2b2b2b2-0000-…`) — inseridas direto no banco (o claim nunca deixa `attempt=0`), aparentam ser resíduo de seed/VENDO. Como `lease_expires_at IS NULL` nunca casa `< now()`, o reaper JAMAIS as recolherá — zumbis inofensivos, mas eternos. Fica registrado para uma eventual faxina de seed.

**Veredito da predição: CONFIRMADA.** Zero evidência histórica de reexecução; o run de 12min58s é a demonstração natural de que lease vencido + worker vivo ≠ requeue na topologia atual. A §3 está corroborada.

---

## 5. Apêndice — sonda JUnit local (worker vivo): **EXECUTADA, PASS (2026-07-10)**

> **Resultado:** `LeaseWorkerVivoSondaTest` (descartável, molde `HeartbeatReaperTest`, H2 in-memory — zero contato com o :8080/DB compartilhado): **Tests run: 1, Failures: 0, Errors: 0** (3,1s). Os 7 passos abaixo confirmados um a um em código: reaper requeia worker VIVO (`QUEUED`, `lease_owner` NULL — não RECOVERING); 2º claim na MESMA linha passa (`attempt=2`, 2º `RUN_STARTED` — o índice não barrou); `finishSuccess(node-A)` caiu no vazio EM SILÊNCIO (status seguiu `RUNNING`, zero `RUN_SUCCEEDED`); `finishSuccess(node-B)` definiu o resultado final; e o checkout da Issue aceitou o mesmo `runId` duas vezes (idempotência = sem barreira). **Arquivo da sonda deletado após o run**, como combinado — este roteiro é suficiente para reproduzir em ~2min.

O roteiro original (validado contra as queries JPQL/SQL reais — o comportamento é o texto das queries, sem branch de runtime), no molde do `HeartbeatReaperTest`:

1. `wakeups.enqueue(...)` → `store.claim(runId, "node-A", 2min)` — attempt=1, `RUN_STARTED`×1.
2. `jdbc.update` forçando `lease_expires_at` ao passado (simula o relógio; o "worker A" segue vivo).
3. `reaper.reapOnce()` → **espera-se 1**; status `QUEUED`, `lease_owner` NULL (não RECOVERING).
4. `store.claim(runId, "node-B", 2min)` → **espera-se true**; attempt=2, `RUN_STARTED`×2 — o índice não barrou (mesma linha).
5. `store.finishSuccess(runId, "node-A")` → **espera-se no-op silencioso**: status segue `RUNNING`, zero `RUN_SUCCEEDED` (o guard `lease_owner = :owner` de `HeartbeatRunRepository.java:80` não casa).
6. `store.finishSuccess(runId, "node-B")` → `SUCCEEDED`.
7. Bônus: `issues.checkout(company, issue, agent, runId)` duas vezes → ambas `acquired()` (idempotência do mesmo runId = sem barreira para reexecução).

---

## 6. Recomendação (a decisão é do Matheus)

> **DECIDIDO (2026-07-10): opção (a) — implementar renovação de lease.** Registrado também em `docs/controle-paralelismo.md` (faixa R1-C concluída, com as constraints para a faixa de execução).

Duas opções defensáveis; a escolha depende de UMA pergunta de negócio: **multi-nó (ou paralelismo de dispatch) entra no horizonte do v1?**

### (a) Implementar renovação de lease (honrar a ADR)

Como seria: um heartbeat do worker estendendo `lease_expires_at` a cada ~30s enquanto `adapter.execute` vive; parar de renovar = morte presumida. Precisa de thread/timer próprio — carona só no StepSink é insuficiente (um adapter silencioso por minutos não pulsa).

- **Prós:** fecha o buraco na CAUSA (o lease volta a ser sinal de vida confiável); destrava multi-nó/pool>1/async com segurança — a promessa da ADR vira verdade; mata a classe inteira de corrida de workspace/custo-dobrado ANTES de ela nascer.
- **Contras:** código novo no subsistema mais delicado; mais um timer para raciocinar; **hoje não corrige nenhum bug observável** — é seguro contra o futuro.
- **Custo:** pequeno-médio (1 fatia: renovação + teste do caso worker-vivo + a palavra "renova" da ADR vira verdadeira de graça).

### (b) Reconciliar a ADR-0005 com o desenho real (lease-fixo + reaper + thread única)

Como seria: reescrever o trecho de recovery da ADR para o que o código É — lease fixo de 2min SEM renovação, reaper→QUEUED (não RECOVERING; status morto), e a invariante NOVA e não-escrita que sustenta tudo: **"a execução é síncrona na thread única do scheduler; dispatch e reap nunca se sobrepõem"**. Idealmente cravar a invariante em código barato: guard de pool-size=1 no startup (falha alta e cedo) ou lock em memória dispatch×reap, + teste documentando o caso worker-vivo como conhecido-e-inalcançável.

- **Prós:** zero risco de regressão (nenhuma linha de produção obrigatória); honesto com a topologia real do v1 (1 nó, 1 operador); o esforço vai para features. A trava anti-double-work que EXISTE (índice + CAS + guard de owner) segue cobrindo o caso real de hoje (worker morto).
- **Contras:** a promessa "multi-nó desde cedo" morre oficialmente (vira dívida declarada); a invariante thread-única é FRÁGIL — quebra com uma linha de config (`pool.size`, virtual threads) ou com o primeiro refactor de paralelismo, e quando quebrar o sintoma será bizarro e intermitente (workspace corrompido, custo dobrado), longe da causa. Sem guard executável, é invariante de comentário.
- **Custo:** mínimo (texto da ADR — atenção: outra faixa está editando o trecho SKIP LOCKED da MESMA ADR — + opcionalmente o guard/teste barato).

### Leitura do investigador

Se a resposta a "multi-nó/paralelismo no horizonte v1?" for **não** (o roadmap atual — 3 personas, G5, caso B — não pede), **(b) com o guard executável** é o melhor custo-benefício: reconhece o desenho real e converte a invariante implícita em falha-de-startup barata em vez de corrida silenciosa futura. A renovação (a) vira pré-requisito EXPLÍCITO, registrado na ADR reconciliada, da fatia que um dia introduzir o 2º nó ou o dispatch paralelo. Se paralelismo de agentes estiver mais perto do que parece (o "MOTOR livre no menu" da Fatia 2 pode empurrar para lá), (a) sobe para "fazer antes dessa fatia".

---

## 7. Handoff (contrato de docs/controle-paralelismo.md)

- **Faixa:** laudo lease ADR-0005 (read-only) — **CONCLUÍDO POR COMPLETO** (investigação: sessão original; queries §4 + sonda §5: sessão de complemento, 2026-07-10).
- **Entregável:** este arquivo em `docs/laudo-lease-adr0005.md` (untracked, não commitar).
- **Não tocado:** ADR-0005 (faixa vizinha do SKIP LOCKED), código de produção, banco (só SELECTs), :8080. Sonda JUnit rodada em H2 e deletada.
- **Pendência única para a sessão de controle:** decisão (a)×(b) do Matheus; se (b), incluir a invariante thread-única + guard na mesma reconciliação da ADR já em curso na outra faixa (R1-E toca só o trecho SKIP LOCKED — o trecho de lease espera esta decisão).
