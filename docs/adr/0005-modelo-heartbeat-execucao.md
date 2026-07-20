# ADR-0005: Modelo de heartbeat / execução

**Status:** Aceito (2026-06-30)
**EMENDADA 2026-07-20 (ADR-0050): o run pendurado deixa de renovar lease para sempre** — ver
§Revisão 2026-07-20 ao final.

## Contexto
O motor de execução é o subsistema mais difícil (no Paperclip virou um arquivo de ~12.528 linhas). Precisa: prevenir double-work e runaway, nunca perder sinal de wakeup, e — melhoria sobre o Paperclip — não ficar preso a single-node.

## Decisão
- **Concorrência:** no máximo **1 Run ativo por Agent**; lease chaveado por Agent (um funcionário foca numa coisa por vez).
- **DB-as-queue:** claim via `SELECT … FOR UPDATE SKIP LOCKED` + compare-and-swap (`UPDATE … WHERE status='queued'`). Sem broker externo no v1.
- **Coalescing:** wakeups redundantes para o mesmo Agent **mesclam** no Run vivo; se chegam tarde demais, **promote-on-finalize** (novo Run logo após o finalize). Nada se perde, sem duplicar.
- **Recovery (multi-nó):** cada Run vivo carrega `lease_owner` + `lease_expires_at`; o worker renova o lease; um **reaper** agendado recoloca leases expirados em recovery (idempotente).
- **Dois locks separados** (checkout vs. execução) liberados em UPDATEs distintos, para um handoff de retry nunca clobberar o outro (herdado do Paperclip).

## Alternativas consideradas
- Enfileirar todo wakeup (1=1 Run) — gera Runs redundantes, desperdiça budget.
- Debounce/descartar enquanto roda — perde sinais.
- Liveness process-local (Paperclip) — single-node only (teto duro de escala).

## Consequências
- Multi-nó possível desde cedo (lease é a chave que o `heartbeat_runs` do Paperclip não tinha).
- Complexidade concentrada no módulo `execution` com fronteiras claras — resistir ao god-file.
- Budget é checado no dispatch deste motor (ver ADR-0006).

## Nota de reconciliação (2026-07-10 — SÓ o trecho do claim/SKIP LOCKED; divergência MÉDIA da auditoria de 2026-07-07)

**Escopo desta nota: apenas o mecanismo de claim do bullet "DB-as-queue".** O trecho de
**renovação de lease** (bullet "Recovery") é uma divergência ALTA independente, com decisão já
tomada (implementar a renovação — laudo `docs/laudo-lease-adr0005.md`, 2026-07-10) e fatia
própria de execução; ele segue **intocado** aqui de propósito. *(A fatia landou — ver a nota
seguinte.)*

## Nota de implementação (2026-07-10 — o trecho Recovery/renovação de lease; divergência ALTA da auditoria de 2026-07-07)

**"O worker renova o lease" agora é verdade em código** — o código convergiu para esta ADR
(grill do COMO em `docs/handoff-grilling-renovacao-lease.md`, D1–D6). O mecanismo construído:

- **Timer por run, thread própria** (`LeaseRenewer`, 1 thread daemon): nasce no claim e morre no
  `finally` do `HeartbeatDispatcher` que envolve executar E finalizar — JVM morta = pulso morto =
  morte presumida, sem lógica extra. A thread é própria por necessidade: a única thread do
  scheduler é exatamente a que fica bloqueada dentro do run síncrono.
- **Cadência 30s → lease cheio** (`now + 2min`; property `orquestrador.heartbeat.lease-renew-ms`,
  default 30000): proporção 1:4 — o worker tolera 3 pulsos perdidos seguidos (GC, hiccup de DB)
  antes de o reaper poder tocá-lo. Lease de 2min e reaper de 5s ficam exatamente como estavam.
- **Renovação é CAS guardado por dono** (`HeartbeatRunStore.renewLease`): `UPDATE … WHERE
  status=RUNNING AND lease_owner=:owner`. **Posse perdida** (0 linhas — reaper requeou + outro
  claim levou, ou hard-stop cancelou em voo) = evento **`RUN_LEASE_LOST`** no rastro do run
  (anti-silêncio) + timer para + **sem kill**: o run termina em paz e o guard por dono do
  `finalizeRun` impede que o resultado sobrescreva o do novo dono. Exceção sem evento: run já
  terminal pelo MESMO dono (o próprio finalize venceu o último pulso — fim normal, não perda).
- **Prova permanente:** `LeaseRenewalTest` (4 provas: pulso estende · renovado é invisível ao
  reaper · roteiro worker-vivo do laudo com `RUN_LEASE_LOST` no rastro · timer morre com o run).
  O `HeartbeatReaperTest` segue provando o lado worker-morto.

**O claim real não usa `SELECT … FOR UPDATE SKIP LOCKED`.** O mecanismo construído é:

1. **Enumeração sem lock** — `HeartbeatRunRepository.findTop50ByStatusOrderByCreatedAtAsc(QUEUED)`
   (SELECT simples).
2. **CAS no claim** — JPQL `HeartbeatRunRepository.claim`: `UPDATE … SET status=RUNNING,
   lease…, attempt+1 WHERE id=:id AND status=:queued`; rows==1 → claimed, rows==0 → outro
   worker venceu (no-op, não erro).

A metade compare-and-swap do desenho original existe; a metade SKIP LOCKED não. O invariante que
o SKIP LOCKED servia (**nenhum double-claim**) é honrado pelo CAS sozinho: workers concorrentes
podem enumerar as mesmas linhas QUEUED, mas só um vence o UPDATE guardado — o custo é contenção
benigna na enumeração, não double-work. O único `FOR UPDATE` do backend é o `PESSIMISTIC_WRITE`
no **Agent** (`AgentRepository.findByIdForUpdate`), que serializa o dispatch por agente — não é o
claim do Run. Direção decidida no levantamento de paralelismo: **doc passa a refletir o código**;
adotar SKIP LOCKED fica como otimização possível se a enumeração virar gargalo multi-worker (não
é correção de corretude).

## Revisão 2026-07-20 (prazo de subprocesso, ADR-0050)

> Emenda ADITIVA da fatia motor-thread-única. **Nenhuma regra do renovador muda** — o que muda é um
> fato sobre o mundo em que ele opera.

Esta ADR desenhou a renovação de lease assumindo que um run **termina**. A conferência do PR #53
mostrou que isso não era garantido: sem prazo nos subprocessos, um run pendurado ficava vivo
indefinidamente e o `LeaseRenewer` — que roda em daemon própria, justamente para não depender da
thread do scheduler — **continuava renovando o lease de um trabalho morto, que nunca parecia
expirado**. Era uma das duas travas que tornavam a recuperação automática estruturalmente
impossível (a outra: o `LeaseReaper` estava na thread travada).

A ADR-0050 fecha isso **sem tocar a regra do renovador**, e isso é decisão consciente:

- O prazo garante que o run termina. Terminado o run — por sucesso, falha ou **estouro de prazo** —
  o `Pulse` fecha pelo try-with-resources do `HeartbeatDispatcher.dispatchOnce` e a renovação para.
  O estouro de prazo é só mais um caminho para o mesmo `finally` que já existia.
- **O renovador NÃO ganha prazo próprio.** Foi a alternativa tentadora e está errada: seria um
  segundo relógio, concorrente com o do processo, capaz de declarar morto um run vivo. Ele mantém
  uma regra só — *enquanto eu tiver posse, renovo* — e quem sabe que o run acabou é quem o executou.

Leitura que fica obsoleta: *"um run pendurado renova o lease para sempre"*. Ele não fica mais
pendurado para sempre.
