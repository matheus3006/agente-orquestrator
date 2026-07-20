# ADR-0049: A reabertura de entrega aprovada — `DONE` deixa de ser sink

**Status:** Aceito (2026-07-20, fatia `hardening-parecer-seguranca`) · **decisão do dono**
registrada em `docs/controle-paralelismo.md` §"2 decisões do dono + 2 kickoffs de hardening"
(**D-ORG-C.2**) · fecha o achado 🔴 1 da conferência do **PR #50** · **EMENDA a ADR-0011 e a
ADR-0012** (ver §Emendas) · **sem migração** (a reserva V39 foi devolvida — é UPDATE guardado, não
schema).

## Contexto

A conferência do PR #50 mapeou os estados-origem de **todos** os UPDATEs guardados da casa e achou
uma assimetria que ninguém tinha nomeado:

- REJECT → `BLOCKED` → o `unblock {REOPEN|CANCEL}` (ADR-0011 §7) destrava. **Reabrível.**
- APPROVE → `DONE` → **`DONE` nunca aparecia como estado-ORIGEM em UPDATE guardado nenhum**, só
  como alvo. `unblock` é `where status = 'BLOCKED'` nos dois ramos, então dava `ConflictException`.
  **Terminal.**

A ADR-0048 §3 e o `ParecerService` afirmavam **em negrito** que nada ali era irreversível. Falso —
e falso justamente na direção perigosa. Com a F-ORG-C, um **gestor promovido** passou a poder
carimbar uma entrega ruim e **fechar a issue sem nenhum humano tê-la visto como PENDING**. O único
recurso era abrir issue corretiva (`CorrecaoDeEntregaRouter`), que é **trabalho novo, não
reabertura**: a entrega ruim continua registrada como aceita.

Isso contradiz a **D-ORG-2**, decisão do dono no grill de org-design: *"gestor decide sozinho, com
rastro total e qualquer entrega reabrível pelo dono"*. **A reabertura era a válvula que justificava
dar autoridade de decisão a um agente.** Sem ela, promover é porta de mão única — e o cálculo de
risco da promoção, que o dono fez sabendo que podia desfazer, muda de figura.

## Decisão

**`DONE` deixa de ser sink.** Um UPDATE guardado novo — `IssueRepository.tryReabrirAprovada`,
`where status = 'DONE'` → `BLOCKED` — exclusivo do dono (board).

O dono decidiu o QUÊ; o mecanismo abaixo é desenho desta fatia.

### 1. Para qual estado a issue volta: `BLOCKED`, não `OPEN` nem `PENDING_REVIEW`

`BLOCKED` é exatamente onde um REJECT deixa a Issue. Dali, o `unblock {REOPEN|CANCEL}` — que já
existe, já é guardado, já é conhecido e já é testado — dá ao dono a escolha entre **refazer**
(REOPEN → `OPEN` → o próximo wakeup re-executa com o mesmo assignee) e **encerrar** (CANCEL).

Reabrir **converge para o caminho que já existe** em vez de inventar um segundo jeito de estar "em
revisão". Zero ramo novo rio abaixo.

- **Por que não `OPEN` direto:** forçaria "refazer" e tiraria do dono a escolha de cancelar. O
  passo a mais é a escolha, não burocracia.
- **Por que não `PENDING_REVIEW`:** exigiria ressuscitar um ciclo de review, e as duas formas de
  fazer isso são piores. Voltar o ciclo APPROVED para PENDING **apagaria o carimbo do gestor** — o
  rastro é a metade da D-ORG-2 que paga pela outra, e apagá-lo para reabrir destruiria exatamente
  o que torna a delegação auditável. Criar um ciclo NOVO apontando o mesmo run fabricaria uma
  segunda Decisão sobre um artefato que ninguém reentregou. O PENDING que o dono nunca viu nasce
  sozinho, e honesto, se ele der REOPEN e o re-run entregar de novo em risco ≥ 5: aí há **entrega
  fresca atrás dele**.

### 2. O que acontece com o ciclo de review: **nada** — ele continua `APPROVED`

O carimbo do gestor (`reviewerActorType=AGENT` + `reviewerActorId` + `decided_at`) fica intacto,
para sempre. **A reabertura é fato SOMADO por cima, nunca apagamento.** Quem quiser saber o que
aconteceu lê: o gestor aprovou, e depois o dono desfez — com o porquê.

### 3. O que acontece com a Decisão já gravada: **fica, e uma nova a SUPERSEDE**

A review é fábrica de Decisões (F-C1/ADR-0019). Três opções foram pesadas:

- **Apagar** — recusada. Memória é registro do que foi decidido; apagar reescreve a história. (E a
  diretriz vigente é explícita: autorização para mudar desenho ≠ ordem de apagar dado.)
- **Deixar quieta** — recusada, e é a pior das três. A Decisão é **o que o executor LÊ na dieta do
  re-run** (R19-A): um `[APPROVE REVIEW] #N` sem qualificação seguiria dizendo a todo agente futuro
  que a entrega foi aceita, no exato canal que molda o comportamento do próximo run.
- **Superseder** — escolhida. A reabertura fabrica Decisão própria: `[REABERTURA] #N <título>`,
  origem `DONO_NO_GATE` (quem desfez foi o dono, mesmo que quem carimbou tenha sido um gestor),
  racional = o motivo do dono, `supersedes` = a Decisão do carimbo desfeito, ancorada no MESMO
  ciclo. É a linhagem D11 usada para o que ela existe.

Se o APPROVE tiver sido em silêncio (C14: sem racional → sem Decisão), não há antecessora: a
reabertura nasce sem `supersedes` em vez de não nascer.

### 4. O motivo é OBRIGATÓRIO

Mesma régua do REJECT ("o motivo É a Decisão", C14/ADR-0020) e **mais forte que a do APPROVE**, que
é opcional. Desfazer uma entrega já aceita é precisamente o momento em que o porquê tem valor: ele
é a **contra-evidência que informa se o gestor deve continuar promovido**. A D-ORG-2 promove quando
"os pareceres batem"; este é o sinal do caminho inverso, e sem ele a promoção vira decisão sem
retroalimentação.

### 5. Board-only, revalidado DENTRO do service

`requireBoard` no `IssueController` **e** a checagem `actorType != BOARD` fail-closed dentro de
`IssueService.reabrirEntregaAprovada` — a lição do hardening item 2 do PR #49: guard só na porta é
seguro por CONVENÇÃO, não por construção. Um agente que alcançasse este método reabriria a própria
entrega reprovada, que é o **inverso exato** da cerca de auto-review da ADR-0048 §2.

Endpoint próprio (`POST /issues/{id}/reabrir`), não um valor novo no `unblock`: a pré-condição é
outra (`DONE`, não `BLOCKED`), o motivo é obrigatório aqui e inexistente lá, e o que se desfaz é um
APPROVE. Enfiar os dois no mesmo verbo apagaria a distinção no lugar onde ela mais importa.

## Emendas

- **ADR-0011 §7 (recovery/unblock):** o `unblock {REOPEN|CANCEL}` deixa de ser a única porta de
  volta de uma Issue liquidada. Ele segue **intacto e soberano sobre `BLOCKED`**; o que muda é que
  agora existe um degrau ANTES dele para entregas aceitas — `DONE → BLOCKED` — e os dois se
  compõem, em vez de competir.
- **ADR-0012 (review gate):** `DONE` deixa de ser terminal para Issue que passou pelo review gate.
  A ADR-0012 tratava a decisão do board como final; com a ADR-0048 abrindo a decisão para um gestor
  promovido, "final" passou a significar "um agente pode encerrar sozinho" — e é essa combinação,
  não a decisão do board, que exigia a válvula.
- **ADR-0026 (F.12, `WorkspaceReaper`) e ADR-0028 (F.14, `RowRetentionReaper`) — a emenda que
  FALTAVA** (acrescentada no fecho do parecer, 2026-07-20; conferência dos PRs #51/#52, 🟠 3).
  Esta ADR mudou um invariante do qual **outros componentes vivem** e a lista acima não os
  alcançou. Os dois reapers definem terminal como `{DONE, CANCELLED}` com TTL sobre
  `issue.updated_at`:
  - `RowRetentionPolicy` / `RowRetentionReaper` — 30 dias, e ao menos **protege o artifact fixado
    por review** (o pin da ADR-0028);
  - `WorkspaceReaper` — retenção default de **7 dias** e **SEM exceção de pin**. Este é o que
    doía: reabrir uma entrega DONE mais velha que a retenção devolvia a issue ao jogo com o
    workspace **já deletado**, e o assignee re-executava contra o vazio em silêncio.

  **Classe do erro, registrada para a próxima vez:** "o conserto mudou um invariante do qual outro
  componente vive". A varredura de quem depende de um invariante é parte do conserto, não follow-up.

## Consequências

- **A D-ORG-2 volta a valer como foi escrita**, em vez de ser rebaixada para "vale só no REJECT".
- A UI ainda **não** tem botão de reabrir: a capacidade existe na API e é exercitável pelo dono,
  mas quem só usa a tela não a alcança. É a mesma classe de lacuna que o hardening do PR #49
  fechou para o `requestedBy` (racional prometendo visibilidade que a UI não dava) — aqui,
  conscientemente, ficou de fora do escopo desta fatia e está declarado no handoff.
- Reabrir uma issue que já foi reaberta e refeita funciona sem caso especial **no que diz respeito
  a ciclos e Decisões**: cada ciclo tem seu carimbo e sua Decisão, e a linhagem `supersedes`
  encadeia.

  > ⚠️ **Ressalva acrescentada no fecho do parecer (2026-07-20).** A frase original — "funciona sem
  > caso especial", sem qualificador — era **falsa para o WORKSPACE**. Passada a retenção do
  > `WorkspaceReaper` (7 dias), o artefato a corrigir não existe mais, e a reabertura entregava uma
  > issue viva apontando para o nada. **Decisão do dono (D-FP-1): FALHAR ALTO** — o endpoint checa
  > o evento durável `WORKSPACE_COLLECTED` e **RECUSA com 409**, dizendo a data da coleta e que
  > reabrir não recupera o artefato. Racional: transforma **perda silenciosa em recusa explícita**;
  > o dono decide sabendo do custo em vez de descobrir depois que o agente refez do zero.
  > Descartadas: fixar o workspace (cresce disco indefinidamente, exige política própria de
  > expiração) e avisar-e-seguir.
  >
  > A pergunta "este workspace foi coletado?" é UMA régua só (`ColetaDeWorkspace`), compartilhada
  > com o preflight do start de preview server (F.12/ADR-0026 B4), que já fazia a mesma checagem.
  > A fonte é o **evento durável**, não o mundo do alvo, de propósito: `listIssueWorkspaces`
  > devolve lista vazia quando a listagem FALHA, então perguntar ao daemon faria uma queda de
  > infraestrutura virar recusa em massa. Limite conhecido e aceito: workspace apagado FORA do
  > reaper não produz evento e não é visto.
- **Não existe reabertura de `CANCELLED`.** Não foi pedido, e `CANCELLED` é o único terminal que o
  dono escolhe explicitamente sabendo que encerra — a assimetria que motivou esta ADR é a de uma
  porta que um AGENTE fecha.
- **O escopo real é `DONE`, que é MAIOR que "um APPROVE de review"** (precisado no fecho do
  parecer, 2026-07-20; conferência dos PRs #51/#52, 🔵). O guard do UPDATE é `status='DONE'` e
  nada mais, então a porta também reabre a issue de risco BAIXO que chegou a DONE por
  `tryComplete` e **nunca teve ciclo de review** (`review_id` é nullable). O nome
  `reabrirEntregaAprovada`, a mensagem de 409 e o título desta ADR falavam só de "desfazer um
  APPROVE" — a descrição é que estava estreita, não o código. **Fica assim de propósito:** no
  vocabulário da casa `DONE` É a entrega ACEITA, pelo revisor quando o risco ≥ limiar ou pela
  própria política de risco quando está abaixo dele (ADR-0012 — "risco baixo dispensa review" é
  uma decisão de aceite, não a ausência de uma). Estreitar o guard tiraria do dono a válvula sobre
  metade das entregas sem ganho de segurança nenhum.
