# Handoff do grill — Renovação de lease do run (ADR-0005)

**Data do grill:** 2026-07-10 · **Status:** GRILL FECHADO (6 decisões, D1–D6) · **Execução:** ~~CONDICIONADA~~ **EXECUTADA (2026-07-10, ver §RESULTADO — pende só o VENDO oficial com o operador)**
**Arquivo untracked até a execução** — quem executar a fatia commita este handoff junto (padrão dos grills anteriores).

## Contexto (o mínimo para executar sem reler tudo)

- **Insumo:** `docs/laudo-lease-adr0005.md` (untracked, NÃO commitar — insumo de decisão). O laudo provou:
  lease fixo de 2min sem renovação alguma (`HeartbeatDispatcher.java:30`); execução síncrona que excede o
  lease rotineiramente (campeão histórico: 12min58s = 6,5×, intocado); na topologia atual (1 nó, scheduler
  de 1 thread) o caso worker-vivo-reclamado é INALCANÇÁVEL (70 runs na história, `attempt>1` = 0), mas a
  sonda §5 provou o buraco por construção: reaper requeia worker VIVO → 2º claim na MESMA linha → o
  finalize do original cai no vazio EM SILÊNCIO.
- **Decisão de direção (Matheus, 2026-07-10, pós-laudo):** implementar renovação de lease — opção (a) do
  laudo. Este grill fechou só o COMO. A ADR-0005 já dizia *"o worker renova o lease"*: o código converge
  para a ADR, não o contrário.
- **Achado estrutural que ancora tudo:** um renovador pendurado no `@Scheduled` existente NUNCA rodaria —
  o pool do scheduler tem 1 thread e é ela que fica bloqueada dentro do run síncrono. O renovador exige
  thread própria.

## Decisões (D1–D6, com racional)

### D1 — Topologia: timer por run, ancorado no `HeartbeatDispatcher`
Um `ScheduledExecutorService` de 1 thread (daemon) do dispatcher. No claim OK, agenda "renova o lease do
run X a cada N ms"; no `finally` em volta de `executor.execute(...)` + finish, cancela a tarefa. A
renovação é UPDATE guardado por dono (`where id=:run and status=RUNNING and lease_owner=:nodeId`).
**Racional:** (1) vida amarrada ao run POR CONSTRUÇÃO — o timer nasce no claim e morre no finally; JVM
morta = timer morto = morte presumida sem lógica extra; a alternativa (varredura `WHERE lease_owner=eu`)
exige acertar o WHERE para sempre e um bug renovaria run já reclamado por outro nó. (2) **Zero toque no
`AdapterRunExecutor`** — o ledger registra colisão possível com a Fatia 2 exatamente nesse arquivo; a
fatia inteira fica em Dispatcher + Store + Repository. (3) Sobrevive ao dispatch assíncrono futuro: cada
run já carrega seu renovador.

### D2 — Cadência: 30s, esticando para o lease cheio (`now + 2min`)
Proporção 1:4 → o worker pode perder **3 renovações seguidas** (GC, hiccup de Postgres, lock demorado)
antes de o reaper poder tocá-lo. Lease de 2min e reaper de 5s ficam EXATAMENTE como estão. O intervalo
vira property no padrão do scheduler — `orquestrador.heartbeat.lease-renew-ms:30000` — porque o teste
permanente precisa encurtá-lo; o lease NÃO vira config (ninguém pediu variá-lo; config que ninguém varia
é superfície à toa).

### D3 — Semântica da perda: anti-silêncio, sem kill
Dois modos de falha do pulso, tratamentos diferentes:
- **Exceção transiente** (ex.: DB fora do ar): loga warn e **insiste no próximo pulso** — a folga 3-de-4
  da D2 existe para isso. ⚠ A task do `scheduleAtFixedRate` NUNCA pode deixar exceção escapar (mataria a
  repetição silenciosamente — a própria armadilha que a folga quer tolerar): catch-all DENTRO da task.
- **Posse perdida** (UPDATE = 0 linhas — reaper requeou + outro claim levou, ou hard-stop cancelou em
  voo): grava evento **`RUN_LEASE_LOST`** no rastro do run (anti-silêncio: a sonda mostrou o finalize
  caindo no vazio sem UMA linha de evidência) + **para o timer** + **NÃO mata o processo** — o run
  termina em paz e o guard do `finalizeRun` (`lease_owner=:owner`) já impede que o resultado dele
  sobrescreva o do novo dono.
**Racional do não-kill:** abortar o CLI exigiria seam de cancelamento atravessando `adapter.execute`
(ADR-0004 + `AdapterRunExecutor` — os arquivos que a D1 protege) para um ramo raríssimo (perder posse com
pulso de 30s exige DB fora por 2min+ ou cancel deliberado). Abort real, se um dia quisermos, é fatia
própria.

### D4 — Teste permanente: `LeaseRenewalTest` com 4 provas
Molde `HeartbeatReaperTest` (perfil test, scheduling off, componentes dirigidos na mão):
1. **O pulso estende** — claim → renova → `lease_expires_at` andou. Determinístico puro.
2. **Run renovado é invisível ao reaper** — claim, lease perto do fim, renova, `reapOnce()` → 0; segue
   RUNNING, attempt=1. (O `HeartbeatReaperTest` continua intocado provando o lado worker-MORTO.)
3. **O roteiro §5 do laudo inteiro + assertiva anti-silêncio** — claim node-A → lease ao passado → reap →
   2º claim node-B → renovação do A retorna false → **evento `RUN_LEASE_LOST` existe** → finalize do A
   no-op sob o guard → finalize do B vale. O seed literal é a §5 do laudo (7 passos).
4. **O timer morre com o run** — run curto com `lease-renew-ms` encurtado: durante a execução o lease
   estendeu ≥1×; após o finalize, nada mais pulsa. Prova que o `cancel()` do finally não vaza timer.
**Racional do 4-e-não-1:** 1–3 são determinísticos puros; só a 4 usa relógio encurtado — empacotar tudo
num teste faria a parte determinística refém da temporal.

### D5 — Zumbi de seed: FORA do código; morre por 1 UPDATE de operador no VENDO
Resta **1 linha `RUNNING` com `attempt=0`, lease NULL** no banco (a faxina M9 da G3 levou a outra) —
estado impossível pelo claim (sempre grava attempt≥1 + lease), só entra por INSERT de seed; invisível ao
reaper (`NULL < now()` nunca casa). **Zero lógica de reaper para isso** (código que defende contra estado
que o código não produz = começo do god-file, contra ADR-0010). **Mas ele NÃO é inofensivo** (correção ao
laudo): um RUNNING eterno num agente real **engole wakeups por coalescing para sempre** (run que nunca
finaliza nunca dispara promote-on-finalize → agente bricado) e infla MissionControl/TeamFanOutGate. Morre
na janela desta fatia: `UPDATE ... SET status='CANCELLED'` na linha específica (SELECT antes/depois) —
**CANCELLED, não DELETE** (preserva rastro; mesmo estado que um hard-stop produziria).

### D6 — Registro: emenda na PRÓPRIA ADR-0005; ADR-0034 segue livre
A decisão de registro ("o worker renova o lease") **já existe na ADR-0005** — ADR nova duplicaria a
decisão e o modelo de heartbeat passaria a exigir leitura dupla (precedente da casa: ADR nova quando
DIVERGE — 0029 vs 0028; aqui o código CONVERGE). A emenda do trecho Recovery é **parte obrigatória da
fatia** (M5, não opcional — decisão explícita do Matheus: sem o trecho na ADR a informação se perde):
cadência 30s→lease cheio 2min, timer por run nascido no claim e morto no finalize, posse perdida =
`RUN_LEASE_LOST` + término em paz sob o guard (sem kill). O gate R1-E (mesma ADR) resolve o timing por
construção. De carona, fecha a divergência 🔴 do roadmap na mesma edição.

## Mecânica (M1–M6)

- **M1** — `HeartbeatRunRepository`: query `renewLease` (`@Modifying UPDATE HeartbeatRun r SET
  r.leaseExpiresAt = :exp WHERE r.id = :id AND r.status = :running AND r.leaseOwner = :owner` → int).
- **M2** — `HeartbeatRunStore.renewLease(runId, owner, lease)` `@Transactional` → boolean: 1 linha →
  true; 0 linhas → grava `RUN_LEASE_LOST` (via `recordEvent`, detail com o owner que perdeu) e false.
  (O evento sai 1× por construção: o timer para no primeiro false.)
- **M3** — Renovador: property `@Value("${orquestrador.heartbeat.lease-renew-ms:30000}")`;
  `scheduleAtFixedRate` após claim OK; task com catch-all (D3); `future.cancel(false)` no finally que
  envolve `executor.execute` + `finishSuccess/finishFailure`. **Forma sugerida (regra de ouro, módulo
  profundo):** extrair classe `LeaseRenewer` com `start(runId, nodeId)` → handle `AutoCloseable`
  (testabilidade direta da prova 4); o executor da fatia decide inline-no-dispatcher vs classe própria,
  mas o seam pequeno + nome que carrega o fluxo é o default da casa.
- **M4** — `LeaseRenewalTest` (4 provas da D4). Piso backend 448 → ~452.
- **M5** — Emenda do trecho Recovery da ADR-0005 (conteúdo da D6) + roadmap: divergência 🔴 ALTA do
  lease → FECHADA (apontar para este handoff) + commit deste handoff. **Pré-condição: R1-E landada.**
- **M6** — VENDO (ver plano abaixo), incluindo o UPDATE do zumbi (D5).

**Arquivos previstos:** `execution/HeartbeatRunRepository.java` · `execution/HeartbeatRunStore.java` ·
`execution/HeartbeatDispatcher.java` (+ `execution/LeaseRenewer.java` se extraído) ·
`test/.../LeaseRenewalTest.java` (novo) · `docs/adr/0005-modelo-heartbeat-execucao.md` (trecho Recovery)
· `docs/roadmap-interno.md` · este handoff. **`AdapterRunExecutor` INTOCADO** (D1). **Migration: NENHUMA**
(`lease_expires_at` já existe; se algo surgir, V26+ — V24/V25 são da Fatia 2).

## Plano de VENDO

1. **Pulso ao vivo (read-only pelo agente):** com o JAR novo no ar, disparar um run que passe de 2min
   (issue de sonda em Demo Fixtures) e repetir
   `select lease_expires_at, attempt from heartbeat_run where id = :run` — o `lease_expires_at` deve
   avançar a cada ~30s; ao final, `attempt=1`, `SUCCEEDED`, **zero `RUN_LEASE_LOST`** no rastro.
2. **Restart é do OPERADOR** (gotchas registrados): o harness barra kill de pid alheio no :8080
   compartilhado; e rebuild do JAR sob processo vivo quebra o classloader lazy → todo rebuild pede
   restart cirúrgico do operador ANTES da prova.
3. **Zumbi (D5):** `docker exec -i agente-orquestrator-db-1 psql -U orquestrador -d orquestrador` —
   SELECT da linha (`status='RUNNING' and attempt=0`), UPDATE para CANCELLED, SELECT de confirmação.
4. **Gates:** `cd backend && mvn -o test` (piso 448 → ~452) · front 242 intocado.

## Condições de execução (gates — copiar no topo do kickoff)

1. **Só depois da R1-E landar** (reconciliação doc das médias — mesma ADR-0005, trecho SKIP LOCKED).
   Conferir no dia: PR da branch `docs/reconciliacao-adrs-medias` merged.
2. **Antes de rodar, voltar à sessão de controle para recálculo de colisão em `execution/`** contra o
   diff REAL da Fatia 2 (R2-A, em execução) — esta fatia não toca `AdapterRunExecutor` (D1), mas
   Dispatcher/Store/Repository precisam de prova de disjunção arquivo-a-arquivo no dia.
3. **Migrations:** nenhuma prevista; necessidade nova = V26+ (V24/V25 reservadas à Fatia 2).
4. **Piso atual:** backend **448** · front **242** (20 suítes).
5. **Branch própria de `main`; landar por PR** (nunca fast-forward direto).

## §RESULTADO

**Executada em 2026-07-10 (sessão de execução, /tdd + /verify).** Fatia FECHADA em código; pende
só o VENDO oficial com o operador (abaixo).

### Gates do dia (todos PASS antes do 1º código)

1. **R1-E landada:** PR #7 (`docs/reconciliacao-adrs-medias`) merged na main (`c8b1693`); nota do
   SKIP LOCKED presente na ADR-0005.
2. **Disjunção arquivo-a-arquivo:** diff REAL da Fatia 2 (PR #8, `c8b1693..1129b61`) toca
   `execution/AdapterRunExecutor.java` + `runtime/WorkspaceResolver.java` e NENHUM dos três desta
   fatia (Dispatcher/Store/Repository). A Fatia 2 já estava MERGED — trabalho em cima, colisão
   zero por construção.
3. **Migrations:** nenhuma (confirmado: `lease_expires_at` existe desde a V3).
4. **Pisos reais na main do dia:** backend **470** (448 do kickoff + 22 da Fatia 2 que landou
   depois) · front **246** (242 + 4 idem) — ambos 100% verdes antes da fatia.

### Entregue (M1–M5)

- **M1** `HeartbeatRunRepository.renewLease` — UPDATE CAS `status=RUNNING AND lease_owner=:owner`.
- **M2** `HeartbeatRunStore.renewLease` — true = lease→now+2min; 0 linhas = `RUN_LEASE_LOST`
  (detail com o dono que perdeu) + false. **Adendo mínimo à M2 (não muda D3):** 0 linhas com o
  run JÁ TERMINAL pelo MESMO dono = o próprio finalize venceu o último pulso na corrida
  finalizar×timer — fim normal, false SEM evento (um `RUN_LEASE_LOST` depois do
  `RUN_SUCCEEDED` do próprio run seria mentira no rastro). Hard-stop (`CANCELLED` em voo)
  continua gravando o evento, como a D3 manda.
- **M3** `LeaseRenewer` extraído (a forma sugerida): 1 thread daemon, `start(runId, owner, lease)`
  → handle `Pulse` (`AutoCloseable`); catch-all DENTRO da task (transiente = insiste); posse
  perdida = para no primeiro false. No dispatcher: try-with-resources envolvendo executar E
  finalizar. `AdapterRunExecutor` INTOCADO (D1). Property
  `orquestrador.heartbeat.lease-renew-ms:30000`.
- **M4** `LeaseRenewalTest` — 4 provas, RED→GREEN uma a uma. Backend **474** (470 + 4) · front
  **246** intocado. *Nota da prova 2:* a assertiva é no RUN (segue RUNNING/attempt=1), não no
  count global do `reapOnce()` — o H2 é compartilhado entre suítes, count global seria frágil.
- **M5** Emenda "Nota de implementação" no trecho Recovery da ADR-0005 + roadmap: divergência 🔴
  ALTA FECHADA (item riscado, header 0 abertas, tabela do inventário, pauta e diário) + este
  handoff commitado.

### /verify (instância descartável — :8080 INTOCADO)

App próprio na 18081 + Postgres descartável (`lease-vendo-pg`), agente `process` LOCAL,
`lease-renew-ms=3000`:

- **Pulso:** run `sleep 12` → lease avançou 4× (~3s cada, sempre para now+2min:
  `17:20:40.961 → :43.994 → :46.989 → :49.987 → :52.993`), terminou `SUCCEEDED`/`attempt=1`,
  lease CONGELADO após o finalize (timer morreu), rastro limpo (RUN_STARTED×1, zero
  `RUN_LEASE_LOST`).
- **Probe da perda (D3 live):** run `sleep 20`, posse roubada no meio via UPDATE
  (`lease_owner='node-intruso'`) → pulso seguinte: `RUN_LEASE_LOST` 1× com o dono perdedor no
  detail + WARN na thread `lease-renewer` + **processo vivo** (health UP) + finalize do perdedor
  no-op sob o guard (run seguiu RUNNING do intruso, zero RUN_SUCCEEDED).

### VENDO oficial (PENDENTE — com o operador)

1. **Restart cirúrgico do :8080 pelo operador** (JAR novo da main pós-merge; rebuild sob processo
   vivo quebra o classloader).
2. **Pulso em produção local:** run >2min em Demo Fixtures; repetir
   `select status, attempt, lease_owner, lease_expires_at from heartbeat_run where id='<run>';`
   → `lease_expires_at` avança ~30s; ao final `SUCCEEDED`, `attempt=1`, zero `RUN_LEASE_LOST`.
3. **Zumbi D5** (confirmado vivo em 2026-07-10, read-only — sobrou SÓ a linha sintética):
   ```sql
   select id, status, attempt from heartbeat_run where status='RUNNING' and attempt=0;
   -- 1 linha: b2b2b2b2-0000-4000-8000-000000000001
   update heartbeat_run set status='CANCELLED'
    where id='b2b2b2b2-0000-4000-8000-000000000001' and status='RUNNING' and attempt=0;
   select id, status from heartbeat_run where id='b2b2b2b2-0000-4000-8000-000000000001';
   ```
   (CANCELLED, não DELETE — preserva rastro.) Via
   `docker exec -i agente-orquestrator-db-1 psql -U orquestrador -d orquestrador`.
