# ADR-0050: Prazo de subprocesso e o dispatch fora da thread do scheduler

**Status:** Aceito (2026-07-20, execução da fatia motor-thread-única) · achado 🟠 2 da conferência do
PR #53 (`docs/controle-paralelismo.md`) · kickoff `HANDOFF-motor-thread-unica-KICKOFF.md` · **zero
migração** (nenhum estado novo — ver §4, que é a decisão central) · **EMENDA a ADR-0005** (lease) e a
**ADR-0040** (interrupt), ver §Emendas · **§3 e §5 emendadas em 2026-07-21** (fatia
a1-escalacao-morte): a escalação de morte tem de **completar**, não só ser chamada — ver §A escalação
de morte tem de COMPLETAR. **ADR-0051 segue livre; V39 não consumida.**

## Contexto

Uma prova viva travou o motor inteiro e **ninguém percebeu por erro** — só porque coisas deixaram de
acontecer. A conferência mediu três fatos que se compõem:

1. **Subprocesso sem prazo.** Dos 9 `waitFor()` do backend, **8 não tinham prazo nenhum**. O único
   que tinha era o screenshot do `LocalExecutionTarget` (`waitFor(30s)` + `destroyForcibly`) — e o
   **gêmeo Docker da MESMA operação não tinha**.
2. **A thread do scheduler é UMA, por omissão.** `SchedulingConfig` é `@EnableScheduling` pelado e o
   `application.yml` não definia `spring.task.scheduling.pool.size` ⇒ default do Boot = 1. Os seis
   `@Scheduled` do `HeartbeatScheduler` dividiam `scheduling-1`.
3. **O run do agente rodava nessa thread.** `HeartbeatDispatcher.dispatchOnce` chama
   `executor.execute` sincronamente. Um processo de agente pendurado travava dispatcher +
   lease-reaper + GC de workspace + GC de preview + thumbnails **de uma vez, em silêncio**.

E a recuperação automática era **estruturalmente impossível**: o `LeaseReaper` é `@Scheduled` na
mesma thread travada; e mesmo que não fosse, o `LeaseRenewer` roda em daemon própria, então um run
pendurado **continua renovando o lease e nunca parece expirado**. Duas travas independentes.

### O que a conferência não viu, e muda o formato do conserto

A prescrição natural — *"troque `waitFor()` por `waitFor(n, unit)`, ~1 linha por site"* — **não
consertaria o caminho de maior exposição.** Medido antes de escrever qualquer conserto, com uma sonda
que imprime onde a thread está parada:

```
BLOQUEADO EM: java.base/java.io.FileInputStream.readBytes(Native Method)
(waitFor JA FOI ALCANCADO? false)
```

Os chamadores **drenam a saída antes de esperar** (`readLine()` em laço, `readAllBytes()`). Um
processo que vive para sempre segurando o stdout aberto nunca produz o EOF que encerra o drain: a
thread fica parada no `read`, e **o `waitFor` sequer é alcançado**. Um prazo posto nele nunca
dispararia. O mesmo RED aparece na suíte, apontando código de produção:

```
at java.base/java.io.BufferedReader.readLine(BufferedReader.java:400)
at com.orquestrador.execution.runtime.LocalExecutionTarget.run(LocalExecutionTarget.java:428)
```

O único site que já tinha prazo é também o único que drenava fora da thread principal, e o
comentário dele dizia por quê: *"o stderr e drenado num thread (o timeout do waitFor precisa poder
disparar)"*. **O idioma certo já existia na casa, aplicado uma vez só — e o que ele carrega não é o
argumento de timeout, é a estrutura de drenagem.**

## Decisão

### 1. `ProcessoComPrazo` — o prazo cerca a OPERAÇÃO, não a espera final

Módulo profundo (interface pequena, complexidade dentro) em `execution/runtime`. O chamador entrega
a própria coleta (drenar + esperar) como lambda; ela roda numa thread daemon que o estouro pode
abandonar; o prazo mede a coleta inteira. Estouro ⇒ derruba o processo e devolve
`Resultado.estourou()` com motivo legível + WARN no log.

Os `waitFor()` internos permanecem **sem argumento de tempo, de propósito**: eles já estão dentro de
um bloco com prazo, e o `destroyForcibly` do estouro é o que os desbloqueia. *(Consequência para
quem auditar depois: `grep "waitFor()"` continua achando 8 ocorrências — todas dentro de um
`ProcessoComPrazo.aguardar(...)`. O grep não é mais a régua; a régua é o bloco que as envolve.)*

### 2. Prazo por NATUREZA da chamada, não um número global

Quatro prazos, em `PrazosDeExecucao` (`orquestrador.execution.timeout.*`), com o racional de cada um
no javadoc, ao lado do código que o usa:

| Prazo | Default | Por quê |
|---|---|---|
| `comando-curto` | 30s | Plano de controle do daemon (`volume ls/rm`, `port`, `ps`, `logs`, `rm -f`, `network`). Respondem em milissegundos com o daemon são; 30s só dispara com ele travado. |
| `workspace` | 5min | Leitura/listagem/escrita **sobem um container** e movem um tar da árvore. Imagem fria + árvore grande passam de um minuto legitimamente. |
| `screenshot` | 30s | Herdado do único prazo que já existia; agora vale para o gêmeo Docker também. Preserva o comportamento local ao byte. |
| `run` | 2h | **Teto duro, não prazo de resposta.** Falso positivo aqui mata trabalho bom — falha pior que a original. |

### 3. O estouro ESCALA a derrubada — matar o processo do host não basta

`destroyForcibly()` atinge o cliente `docker run`, que **não repassa sinal ao container** (o mesmo
motivo pelo qual o interrupt da ADR-0040 endereça o container pelo NOME). No LOCAL, um SIGKILL
só-no-pai deixa o filho da tool call vivo e órfão (sonda R6-B). Um prazo que devolve a thread e deixa
o trabalho rodando trocaria uma **trava visível** por um **vazamento invisível** — pior negócio.

Por isso o estouro roda uma escalação antes do golpe no cliente, e ela reusa o mecanismo que já
existe: `interrupt(runId)` nos dois alvos (`docker rm -f` pelo nome / descendentes-primeiro no
LOCAL); na escrita de workspace, o tar do host do outro lado do pipe.

### 4. O run morto por prazo **reentra no caminho de falha que já existe** — nenhum estado novo

Esta é a decisão que o kickoff pediu para desenhar, e a resposta é *deliberadamente* nada de novo.
Verificada elo a elo, não suposta:

```
run() devolve exit -1 + motivo
  → ProcessAgentAdapter.execute       exit != 0 ⇒ Result.fail(...)
  → AdapterRunExecutor.runToOutcome  ⇒ RunOutcome.failure(error)
  → AdapterRunExecutor.execute       ⇒ issues.settleAfterRun ⇒ Issue BLOCKED, lock liberado
  → HeartbeatDispatcher              ⇒ store.finishFailure ⇒ RUNNING→FAILED + evento RUN_FAILED
  → try-with-resources do Pulse      ⇒ renovação de lease CANCELADA
```

Respondendo às três perguntas do kickoff, nesta ordem:

- **Vira `RUN_FAILED`?** Sim, por `finishFailure`, com o motivo do prazo como `error` — a mesma
  forma que uma exceção do adapter já produzia. Nenhum chamador precisa aprender caso novo.
- **O lease é liberado?** Sim, e por construção: o `Pulse` vive num try-with-resources no
  `dispatchOnce`, então o fim da execução — qualquer que seja o motivo — cancela a renovação. O
  `finalizeRun` é guardado por dono, então o run sai de `RUNNING` sob CAS.
- **O `LeaseRenewer` precisa saber?** **Não, e é aqui que a alternativa tentadora está errada.** Dar
  ao renovador um prazo próprio — "pare de renovar depois de X" — criaria um segundo relógio,
  concorrente com o do processo, capaz de declarar morto um run vivo. O renovador continua com uma
  única regra ("enquanto eu tiver posse, renovo") e quem sabe que o run acabou é quem o executou.

**A trava dupla morre pelas duas pontas, sem estado novo:** o prazo garante que o run *termina*
(fim de renovação); o `DispatchWorker` (§5) garante que o reaper *estava rodando* o tempo todo.

### 5. `DispatchWorker` — o run sai da pool do scheduler, com limite explícito de UM

Executor próprio de uma thread daemon, e política de **pular, não enfileirar**: o tick que encontra
passada em voo é descartado, o próximo tenta de novo. O limite 1 preserva a garantia que o
`fixedDelay` já dava (nunca duas passadas simultâneas) em vez de estreá-la junto com a mudança de
thread; subir é decisão de capacidade, não de robustez.

O `finally` que libera a porta é parte da decisão, não detalhe: sem ele, a primeira passada que
explodisse deixaria o motor parado para sempre — **o mesmo modo de falha silenciosa, por outro
caminho**.

### 6. `pool-size` fica em 1, agora EXPLÍCITO

Subir a pool sozinho seria falso conserto: só adia a exaustão e não toca o lease. O defeito nunca foi
o valor — era ele valer **por omissão**, invisível. Com §1 (todo `exec()` limitado) e §5 (o run fora
da pool), a ocupação máxima da thread por tarefa passou a ser limitada, e o valor 1 vira escolha
defensável em vez de acidente.

Revisitar quando houver **medição** de starvation — o candidato é o lease-reaper de 5s atrás de uma
varredura de workspace longa (degradação limitada e auto-corretiva, ~5min uma vez por hora). Antes de
subir é preciso verificar que os 5 `@Scheduled` restantes são seguros de rodar concorrentemente entre
si, o que **nunca aconteceu até hoje**.

## Consequências

- Todo subprocesso do motor tem prazo; o modo de falha "silêncio para sempre" virou falha limitada,
  legível e recuperável.
- Um run pendurado não para mais o lease-reaper, o GC de workspace, o GC de preview nem os
  thumbnails.
- Prazo é property: operação com daemon lento ajusta sem recompilar.
- **Limite conhecido (declarado, não consertado):** os containers efêmeros de leitura/listagem/
  escrita rodam `docker run --rm` **sem `--name`**, então não há como endereçá-los no estouro — o
  golpe atinge o cliente e o container pode sobreviver até terminar sozinho. A thread do chamador é
  devolvida em todos os casos (que é o dano que esta ADR veio matar); nomear esses três é conserto de
  outra fatia.
- **Limite conhecido (fora do escopo desta fatia):** consulta JDBC pendurada não tem prazo — não há
  timeout de query configurado. Não é `exec()`, mas é o mesmo formato de risco na mesma thread.

## A escalação de morte tem de COMPLETAR (2026-07-21, fatia a1-escalacao-morte)

> **Emenda a esta própria ADR**, nas §3 (a escalação) e §5 (o `DispatchWorker`) — é aqui que o
> `ProcessoComPrazo` e a derrubada do container estão decididos. **Nenhuma ADR nova:** a reserva
> **ADR-0051 segue livre**, e a migração **V39** não foi consumida. Fecha o 🔴 que sobrou da
> conferência do PR #56, achado por dois verificadores adversariais de forma independente.

### O PR #56 restaurou a simetria na CHAMADA, não no EFEITO

O #56 fez o ramo de `InterruptedException` escalar como o de estouro (§3) — e o teste dele provava
exatamente isso: que `escalarMorte` **era chamada**. Só que o runnable daquele teste era um
`set(true)`, que não espera por nada; a escalação de verdade espera. Com o
`Thread.currentThread().interrupt()` rodando **antes** do `matar(...)`, a flag de interrupção já
estava reposta quando a escalação começava — e `docker rm -f` roda dentro de um
`ProcessoComPrazo.aguardar` **ANINHADO, na mesma thread** (`DockerExecutionTarget.interrupt` →
`exec` → `aguardar`). O `FutureTask.awaitDone` desse aninhado lança `InterruptedException` na hora
quando a flag está posta, sem sequer olhar o processo: o cliente do `rm -f` levava
`destroyForcibly` e voltava `exit -1`. **Container órfão — exatamente o desfecho que o #56 dizia ter
fechado, agora com um teste verde por cima.**

O ramo de estouro sempre funcionou porque **não tem flag nenhuma para repor**. A assimetria não era
entre "escala / não escala"; era entre "escala com a flag limpa / com a flag posta".

**A decisão:** no ramo de interrupção, `matar(...)` roda **antes** de repor a flag. Restaurar depois
preserva o contrato de nunca engolir a interrupção — quem recebe a exceção recebe a flag junto —, e
escalar com a flag limpa é o que faz a derrubada **concluir**. A ordem é o conserto, não estilo.

**O teste mudou de alvo junto.** A asserção é sobre o efeito DEPOIS da espera, não sobre a entrada
nela — e são dois: um sobre o contrato (a escalação conclui, com um ponto de espera sensível a
interrupção) e um sobre a FORMA que a produção usa (um `aguardar` aninhado que tem de colher o exit
real do subprocesso). Ambos ficam **vermelhos** em `b32c337` e verdes com a ordem trocada.

### `close()` esperar é a outra metade da mesma garantia

`DispatchWorker.close()` (§5) era `shutdownNow()` e voltava. Mas `shutdownNow` **interrompe** a
passada; ele não a conclui — e o que a interrupção dispara é justamente a escalação acima, que leva
tempo. Como a thread do dispatch é **daemon**, a JVM saía por cima da derrubada em curso: mesmo
desfecho, outro caminho. Fechar só um dos dois deixaria a garantia meia-verdadeira.

`close()` passa a esperar com `awaitTermination` e **teto curto e explícito de 5s** — folgado para um
`docker rm -f` real, bem abaixo do teto patológico de 30s do `comando-curto` porque este é o
orçamento do *shutdown*, não o do comando. Passado o prazo, a parada segue com WARN: perder uma
derrubada é ruim, pendurar o shutdown para sempre é pior.

### Limite conhecido, nomeado

A garantia é **"a escalação não é morta pela própria flag do chamador"**, e ela vale para o ramo de
interrupção do `aguardar` e para a espera do `close()`. Ela **não** promete que todo container morre
em todo desfecho: o limite dos três containers efêmeros `--rm` sem `--name` (§Consequências) segue
aberto e intocado, e um `SIGKILL` na JVM continua não deixando ninguém escalar nada.

## Emendas

- **ADR-0005 (lease e renovação)** — a §4 acrescenta que o fim do run por **estouro de prazo** é mais
  um caminho que encerra o pulso pelo try-with-resources do `dispatchOnce`, sem tocar a regra do
  renovador. Fecha a leitura de que "um run pendurado renova para sempre": ele não fica mais
  pendurado para sempre.
- **ADR-0040 (interrupt por runId)** — o `interrupt` deixa de ser só ato do operador e passa a ser
  também o **mecanismo de escalação do estouro de prazo** (§3). Nenhuma mudança de comportamento no
  caminho manual.
