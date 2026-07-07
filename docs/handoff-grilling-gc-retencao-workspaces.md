# Handoff — GRILLING F.12: GC/retenção de workspaces (fatia FECHADA, pronta para execução)

> Origem: sessão de PLANEJAMENTO (grilling) 2026-07-05. Fatia escolhida pelo operador no menu da
> Fase 2 (era o DEFAULT proposto). Plano FECHADO pelo grill — a sessão de execução **NÃO re-planeja
> e NÃO reabre ADRs 0001–0025**. Repo SEM git em
> `/Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator/` — NÃO commitar.
> Piso backend: **334/334** (nunca baixar). **Front intocado** (a fatia não toca front — o aviso
> legível viaja pelo caminho de erro existente do backend).
> Evidência de mercado: deep-research 2026-07-05, 10 achados, todos confiança alta (§E).

## §A — O que é a fatia

**WorkspaceReaper — o segundo reaper da casa.** Hoje NENHUM workspace é removido, jamais: zero
`docker volume rm` no código, zero `@DeleteMapping` na API, FKs todas RESTRICT. Cada issue cria um
workspace (volume DOCKER `orq_<c8>_<a8>_<i8>` / dir LOCAL `<root>/<company>/<agent>/<issue>`) que
vive para sempre — crescimento sem teto por construção (dor de CONTAGEM; bytes hoje são KB porque
o v2 manda deps para tmpfs). A fatia: coletar o workspace de issues **TERMINAIS** (DONE/CANCELLED)
após janela de retenção, nos DOIS alvos, com auditoria durável. Nenhum outro artefato é tocado
(rows do banco ficam — banco inteiro < 10 MB; a foto v1 é eterna por design).

## §B — DECISÕES-ÁTOMO (racional + alternativas rejeitadas)

### B1. Natureza: mecanismo de PRODUTO (reaper agendado), não faxina ops
Molde estrutural: `PreviewServerSupervisor.reapOnce()` + `@Scheduled` no `HeartbeatScheduler` +
config `@Value` + teste com TTL curto — o padrão já provado da casa.
**Racional**: a indústria converge exatamente nisso (§E: K8s TTL-after-finished controller,
BuildKit GC, Jenkins WorkspaceCleanupThread; on-completion foi REFUTADO como default — GitLab não
limpa by design, flag opt-in default false; K8s o modela como caso degenerado TTL=0). A dor é real
e silenciosa (GitLab documenta hosts enchendo até intervenção manual de admin).
**Rejeitadas**: faxina pontual/script (resolve 1 vez, dor volta a cada fatia, cliente herda o
problema); retenção ampla (caps por bytes/contagem + arquivamento de board — rows não doem,
board-archive é UX, fatia ficaria gorda).

### B2. Política: TTL desde a TRANSIÇÃO TERMINAL, 7 dias, LIGADO por padrão
- Configs: `orquestrador.workspace.gc.enabled:true` · `orquestrador.workspace.gc.retention-days:7`
  · `orquestrador.workspace.gc.sweep-ms:3600000` (1h — TTL de dias não pede varredura de minuto).
- **Âncora v1 = `issue.updated_at`** — VERIFICADO no banco em 2026-07-05: acompanha a transição
  terminal (ex.: issue 67aa95e5 created 16:47 / updated 17:05 = instante do APPROVE). Direção de
  falha conservadora: qualquer toque posterior só ADIA a coleta. Comparação de TTL SEMPRE com o
  relógio do BANCO (`now()` do Postgres), nunca com o clock/memória do processo (§E: kubelet perde
  tracking no restart; K8s alerta clock skew).
**Racional**: TTL-desde-terminal é a âncora canônica (K8s usa `lastTransitionTime` do
Complete/Failed); 7d cobre o período de review/demo; ligado por padrão porque a dor volta calada em
ambiente que esquecer de ligar (kill-switch existe).
**Rejeitadas**: TTL desde criação (coletaria cedo issue de vida longa); default desligado
K8s-style keep-forever (dor silenciosa); caps bytes/contagem com histerese e LRU (volumes KB —
camada FUTURA registrada, não v1); âncora `terminal_at` dedicada (migração sem necessidade —
revisitar apenas se issue DONE virar editável um dia); 48h (curto) / 30–60d (acúmulo demais).

### B3. Escopo do colecionável: SÓ workspace por-issue de issue terminal vencida
- DOCKER: volume `orq_<c8>_<a8>_<i8>` · LOCAL: `<workspace.root>/<companyId>/<agentId>/<issueId>`.
- **Fora por construção**: issues BLOCKED/PENDING_REVIEW (não-terminais → workspace de falha
  preservado para debug DE GRAÇA pela máquina de estados — equivalente do `cleanWhenFailure=false`
  do Jenkins); workspaces de agente/conversa (`orq_<c8>_<a8>` / dir `_agent` — bounded, 1 por
  agente); volumes de credencial (`orq_claude_creds`/`orq_codex_creds`) e `pgdata` (nem casam o
  padrão de nome — denylist ESTRUTURAL, não lista mágica); volumes não-`orq_` são invisíveis.
- **Órfãos** (nome `orq_*` que não deriva de NENHUMA issue/agente do banco): v1 **detecta e LOGA
  WARN, não coleta** — buraco confesso da pesquisa (zero prior art confirmado); conservador
  primeiro; órfão logado é sinal de bug nosso, não alvo de deleção cega.
**Rejeitadas**: coletar órfãos (bug de derivação viraria deleção de dado vivo); incluir `_agent`
(sem dor presente; histórico de arquivos da conversa se perderia).

### B4. Sem pin no v1; consequência no preview v2 ACEITA pelo operador
**Fatos**: o PreviewServer v2 monta o volume da issue `:ro`; DONE não reabre; as 3 issues v2 de
hoje (5f207cc0, e5946ef0, 96c1d2ea) NÃO têm foto — o único registro VISUAL delas é a app viva.
Issues v1 não perdem NADA (foto = `preview_artifact` no banco, eterna, ADR-0021).
**Decisão**: coletar mata o botão "app viva" para sempre; mitigação = start de v2 em issue
coletada responde **erro LEGÍVEL** ("workspace coletado pelo GC em <data>" — preflight de volume
no start, 409 pelo caminho de erro existente; front intocado). Demo viva é regenerável (1 issue
nova, ~5 min). A lacuna "v2 sem registro visual durável" fica ANOTADA como argumento objetivo
pró-fatia **thumbnail** (menu Fase 2).
**Rejeitadas**: pin/`workspace_pinned` (migração + superfície + armadilha documentada de
pin-pós-expiração — K8s não garante; pin é exceção opt-in na indústria, não feature de v1);
TTL 30d como colchão (adia sem resolver).

### B5. Mecânica: sweep WORLD-DRIVEN por DERIVAÇÃO, remoção pelo seam, auditoria verdadeira
- `WorkspaceReaper` — módulo profundo, par do `LeaseReaper`/`PreviewServerSupervisor`:
  `sweepOnce()` público testável, agendado pelo `HeartbeatScheduler`.
- **World-driven**: 1×/ciclo lista o MUNDO (`docker volume ls` filtrado `orq_` + listagem do root
  LOCAL). Reconhecimento por **DERIVAÇÃO**: o `WorkspaceResolver` (quem batiza — `volumeName(...)`)
  gera o nome esperado de cada issue terminal-vencida e intersecta com o mundo. **Zero parsing de
  nome, zero estado especular no banco, zero migração de schema** (filosofia provada do
  PreviewServerSupervisor: fonte de verdade é o mundo). Órfão = nome no mundo fora de TODA derivação.
- **Re-read fresco**: imediatamente antes de CADA remoção, re-lê o status da issue no banco
  (padrão do K8s TTL controller: fresh GET + precondition antes do delete).
- **Remoção via seam `ExecutionTarget`** — método **ADITIVO** `removeIssueWorkspace(companyId,
  agentId, issueId)`: `DockerExecutionTarget` → `docker volume rm`; `LocalExecutionTarget` →
  delete recursivo com **GUARD de path** (canonical path DEVE estar sob o root canônico —
  invariante TESTADO; defesa contra rm -rf catastrófico). Mesmo padrão aditivo do posture
  (ADR-0025) — não toca o que está provado.
- **Workspace em uso** (ex.: PreviewServer vivo montando o volume): `rm` falha → **WARN + skip**,
  o próximo ciclo tenta de novo (o TTL de 15min do server resolve sozinho). O sweep NUNCA aborta
  por causa de 1 candidato — segue para os demais.
- Deleção **síncrona** no thread do scheduler (volumes KB; nunca dentro de transação de banco).
  Rename-then-async-delete (Jenkins Resource Disposer) = evolução registrada, não v1.
- **Auditoria**: evento novo `ActivityAction.WORKSPACE_COLLECTED` no activity_log, detail só-IDs
  (issueId, target, ref do workspace — ADR-0009), com **ATOR NOVO `ActorType.SYSTEM`** (hoje o
  enum só tem BOARD/AGENT; o reaper é ato do PIPELINE — atribuição verdadeira; logar como
  board/agente mentiria, exatamente a classe de sujeira do incidente Atlas). Conferir pontos de
  switch/exaustão ao adicionar o valor.
**Rejeitadas**: DB-driven com marcador `workspace_collected_at` (estado especular + migração,
contra a filosofia provada); auditar como BOARD/AGENT (mente a atribuição); não auditar (deleção
de dado sem rastro durável).

### B6. Registro: ADR-0026
`docs/adr/0026-gc-retencao-workspaces-reaper.md`, status **Aceito**, com B1–B5, riscos, recortes
(§H) e a evidência §E. Relaciona: ADR-0016 (ExecutionTarget), 0021 (foto eterna), 0023
(PreviewServer monta volume), 0025 (padrão de método aditivo no seam), 0009 (detail só-IDs).

## §C — DoD (verificável, nível VENDO)

1. **ADR-0026** escrito (Aceito).
2. **TDD red→green** — `WorkspaceReaperTest` moldado no `PreviewServerReaperTest` (TTL curto):
   coleta DONE/CANCELLED vencida nos DOIS alvos; NÃO coleta: não-terminal, terminal não-vencida,
   workspace de agente (`_agent`), órfão (só WARN), em-uso (skip + próximo ciclo); guard de path
   LOCAL (path fora do root → recusa); derivação-interseção via WorkspaceResolver; evento
   WORKSPACE_COLLECTED com ator SYSTEM.
3. Suíte backend **≥ 334 + novos, verde** (`cd backend && mvn -o test`); front intocado; contratos
   provados intocados (skills-path `.claude/skills`, `.orq/preview/*`, `CodexStreamParser`,
   posture ADR-0025 — o método novo no seam é aditivo).
4. **Prova VIVA na sujeira real** (rebuild jar → `:8080`): com `retention-days=0` TEMPORÁRIO via
   config, o sweep coleta TODOS os workspaces de issues DONE — teams-demo `142f1ac5` E a company
   `b92cb312` (operador aceitou explicitamente coletar TUDO, incluindo os 3 apps v2):
   - volumes DOCKER somem (`docker volume ls`), dirs LOCAL somem (`~/.orquestrador/workspaces`);
   - feed de atividade mostra 1 `WORKSPACE_COLLECTED` por issue (ator SYSTEM);
   - **VENDO no board**: a foto do kanban `67aa95e5` continua abrindo DEPOIS da coleta (cópia
     eterna); o start do app de notas `96c1d2ea` responde o aviso legível;
   - órfão sintético (`docker volume create orq_deadbeef_cafebabe_12345678`) → WARN no log,
     NÃO coletado;
   - `orq_claude_creds`, `orq_codex_creds`, `pgdata`, volume anônimo: intocados;
   - **restaurar `retention-days=7`** + rebuild/restart final (estado de repouso = default).
5. **Faxina pontual** (§D) executada e reportada.
6. Handoff da execução atualizado com §RESULTADO; **ADR-0015** respeitado (cruzou ~50-60% do
   contexto → destilar handoff parcial e recomeçar).

## §D — Faxina pontual dos avulsos (decisão do operador: "derrubar kepler + investigar/apagar o anônimo")

Fora do mecanismo (o reaper não cobre containers avulsos nem volumes sem nome) — fazer UMA vez:
- `docker rm -f condescending_kepler` — probe manual de 2026-07-05 02:13 (`docker run
  orq-claude-code:2.1.201 claude` sem labels `orq.*`), 17h+ de pé segurando `orq_claude_creds`
  montado rw. A credencial em si fica intacta no volume dela.
- Volume anônimo `6bea9500b758…` (49 MB, links=0): montar SOMENTE-LEITURA e listar
  (`docker run --rm -v 6bea9500…:/x:ro alpine ls -la /x`); lixo óbvio de build/teste → `docker
  volume rm`; se parecer de OUTRO projeto do operador → deixar e reportar.

## §E — Evidência (deep-research 2026-07-05 — 10 achados, confiança alta; verificação adversarial)

- **Gatilho**: indústria converge em reaper agendado/background com política declarativa; botão
  manual só como válvula de escape. On-completion como default foi REFUTADO (GitLab não limpa by
  design + `FF_ENABLE_JOB_CLEANUP` default false; K8s TTL=0 é caso degenerado do mesmo controller;
  GitHub Actions self-hosted não faz GC; até o Jenkins roda WorkspaceCleanupThread por idade, 30d).
- **Política**: TTL desde o estado TERMINAL é a âncora canônica (K8s `lastTransitionTime`).
  Sistemas maduros empilham camadas (idade → bytes com histerese 85/80 → contagem; eviction LRU);
  defaults de idade variam 48h (BuildKit regenerável) a 30–60d (Jenkins/BuildKit cache); K8s
  default = keep-forever opt-in.
- **Proteções**: grace period mínimo (MinAge); pin = extensão de TTL válida só ANTES de expirar;
  manter workspace de build FALHO para debug; **re-ler estado fresco imediatamente antes de cada
  deleção** (K8s: fresh GET + precondition — fecha a race pin-vs-reaper).
- **Armadilhas**: âncora de tempo na memória do processo reseta no restart (kubelet
  imageMaximumGCAge) → ancorar em timestamp durável no banco + relógio único; clock skew coleta na
  hora errada; deleção síncrona lenta bloqueando trabalho vivo (Jenkins: rename instantâneo +
  delete assíncrono via Resource Disposer — anotado como evolução); wipe indiscriminado destrói
  cache quente (custo real de recriação).
- **Buracos confessos** (zero claims confirmadas — decisão nossa): política de ÓRFÃOS
  (v1: loga, não coleta) e AUDITORIA do coletado (v1: activity_log, o órgão de auditoria da casa).

## §F — Incidente Atlas: RESOLVIDO nesta sessão (decisão do operador: reverter + limpar zumbis)

O incidente documentado no handoff F.11 era **1 de 4**: o Atlas re-propõe "Issue de direcao:
nicho" ~30s depois de cada decisão (o `uq_approval_pending` só bloqueia duplicata enquanto há
PENDING), e o clique cego da automação de UI aprovou **4 vezes** em janelas diferentes
(04/07 19:45, 04/07 20:42, F.11 → issue 2a8503c9, 05/07 18:05). Executado:
- `da239e6d` revertida a **PENDING** (decision falsa apagada, issue 2a8503c9 apagada) — é a ÚNICA
  proposta viva do Atlas, na fila do operador. **NÃO decidir por ele.**
- As outras 3 (ac459fe0, 41d85699, e0db5922): issues zumbis apagadas (34887e10, f3a6ffd2,
  a9507b71), decisions falsas apagadas, approvals marcadas **REJECTED com razão verdadeira**
  ("aprovação acidental por automação de UI — duplicata da proposta pendente da239e6d").
- `activity_log` NÃO foi tocado: o produto o protege com trigger append-only
  (`trg_activity_log_immutable` — DELETE/UPDATE proibidos; o SQL do handoff F.11 nunca teria
  rodado inteiro). As linhas registram cliques que de fato aconteceram — auditoria fica.
- Decision-log restante: apenas as 2 decisões deliberadas dos smokes T.4 (legítimas).
- Causa-raiz (gotcha permanente): seletor genérico `button:not([disabled])` em automação de UI.

## §G — Ambiente (fatos) + gotchas de sangue

- Backend jar no `:8080` (log `/tmp/orq-f11.log`; rebuild = pid NOVO, conferir `lsof -iTCP:8080`).
  Board: launch.json "frontend" (`:3000`, pode ter caído). Rebuild: `cd backend && mvn -o
  -DskipTests package`.
- DB: container **`agente-orquestrator-db-1`** ("orquestrator" sem 'd'); `docker exec -i
  agente-orquestrator-db-1 psql -U orquestrador -d orquestrador` (user/db COM 'd'; `-i`
  obrigatório para heredoc).
- Companies com issues DONE: teams-demo `142f1ac5-16d0-4e75-acde-fbad93a666c3` e `b92cb312-…`
  (a prova varre AS DUAS). Board user `b0a7d000-0000-4000-8000-000000000001` (header
  `X-Board-User` literal; wakeup SÓ por API).
- LOCAL root: `~/.orquestrador/workspaces` (config `orquestrador.workspace.root`).
- Volumes hoje (pós-F.11): 9 de workspace (`orq_142f1ac5_*`), 2 de creds, pgdata, 1 anônimo 49MB.
- Cobaias: claude `6fcaff70` NÃO tocar; `smoke-codex-local` 60a8a573 / `smoke-codex-docker`
  034c7264 reusáveis (runtime_command está com prompt v2 — trocar por SQL dollar-quote ao reusar).
- Board UI: cliques por coordenada NÃO disparam onClick React — `el.click()` programático; fila de
  review é `/teams-demo/reviews` (plural); **JAMAIS seletor genérico de botão** (§F). Esta
  execução só precisa VER (foto/aviso) — não há approval/review no fluxo do GC.
- `DONE` não reabre (REOPEN só de BLOCKED); `gtimeout` no macOS (não `timeout`); runs reais
  2–4min; probes de CLI no host sempre `</dev/null`.

## §H — Fora do escopo / recortes conscientes

- Caps por bytes/contagem, histerese, eviction LRU (camadas futuras do mesmo reaper).
- Pin/keep-forever · âncora `terminal_at` dedicada · delete assíncrono (Resource Disposer).
- Coleta de órfãos (v1 só loga) · GC de workspaces de agente/`_agent`.
- Retenção de rows do banco (preview_artifact/run_events — quando doer, é cap+graduação, outra
  conversa) · arquivamento/filtro de issues DONE no board (UX, não GC).
- **Thumbnail do preview** — fatia própria do menu; ganhou argumento NOVO aqui (v2 não tem
  registro visual durável; a coleta torna isso visível).
- Não reabrir ADRs 0001–0025; parser e contratos de lugar não mudam.

## §I — Ordem de execução sugerida (TDD)

1. ADR-0026 (B6). 2. `WorkspaceReaperTest` red (casos do §C.2) → `WorkspaceReaper` green
(derivação+interseção, re-read, skip-em-uso). 3. Seam: `removeIssueWorkspace` nos dois targets
(guard de path LOCAL testado). 4. `ActorType.SYSTEM` + `WORKSPACE_COLLECTED` (exaustão de
switches). 5. Agendamento no `HeartbeatScheduler` + configs. 6. `mvn -o test` ≥334+novos → rebuild
→ `:8080`. 7. Prova viva §C.4 (retention 0 → coletar TUDO VENDO → restaurar 7). 8. Faxina §D.
9. §RESULTADO neste handoff.

---

## §RESULTADO — EXECUÇÃO FECHADA (2026-07-05)

Fatia F.12 **entregue e provada VIVA**. Suíte **334 → 357 verde** (23 novos), front e contratos
provados intocados, **ZERO migração de schema** (confirmado em runtime: o backend novo subiu com
`ddl-auto: validate` + Flyway on, sem erro).

### O que foi construído (TDD red→green por fatia vertical)
- **ADR-0026** `docs/adr/0026-gc-retencao-workspaces-reaper.md` — Aceito.
- **Seam ADITIVO** em `ExecutionTarget` (2 métodos DEFAULT — os 4 fakes de teste compilam
  intactos): `listIssueWorkspaces(resolver)` (o MUNDO) + `removeIssueWorkspace(resolver,c,a,i)` →
  `IssueWorkspaceRemoval {REMOVED, ABSENT, IN_USE, FAILED}`. `DockerExecutionTarget`: `docker
  volume ls/rm` + parse/classify PUROS (testados sem daemon). `LocalExecutionTarget`: walk do root
  + delete recursivo com **GUARD de path canonicalizado sob o root** (`deleteWorkspaceTree` —
  invariante TESTADO: caminho fora do root → `SecurityException`, alvo sobrevive).
- **`WorkspaceResolver`** virou o dono único da derivação: `pathFor` (puro, sem mkdir), `root()`,
  `refFor(type,…)` (DOCKER=volumeName / LOCAL=path) e `isIssueVolumeName` (denylist ESTRUTURAL —
  `orq_<8hex>_<8hex>_<8hex>`; creds/pgdata/workspace-de-agente não casam).
- **`DatabaseClock`** — relógio do BANCO (`select now()` coagido a `Instant` pela MESMA máquina de
  tipo do Hibernate que lê `updated_at`). ⚠️ **Bug latente pego pelo TDD**: coagir o escalar na mão
  descasava do frame das entidades por ~3h (o H2 do teste devolve `now()` zone-naive; o Hibernate
  lê a coluna em UTC) — o reaper teria comparado a retenção 3h errado. O teste virou invariante de
  MESMO FRAME entre `clock.now()` e `issue.updated_at`.
- **`WorkspaceReaper`** — `sweepOnce()` (kill-switch + loop dos alvos) + `sweepTarget()` (núcleo
  profundo testável): world-driven por DERIVAÇÃO, re-read fresco antes de cada remoção, órfão→WARN,
  em-uso→skip, auditoria `WORKSPACE_COLLECTED` ator **`ActorType.SYSTEM`** (novo; switch exaustivo
  do `CompanyAccessGuard` ganhou `case SYSTEM -> false`, fail-safe deny). `IssueRepository
  .findByAssigneeAgentIdIsNotNull()` é a base da derivação. Zero estado especular, zero migração.
- **Agendamento** no `HeartbeatScheduler` (`reapWorkspaces` @Scheduled `gc.sweep-ms:3600000`).
  Configs `@Value`: `gc.{enabled:true, retention-days:7, sweep-ms:3600000}`.
- **Aviso legível (B4)** — preflight em `PreviewServerSupervisor.startFor`: se há
  `WORKSPACE_COLLECTED` da Issue no `activity_log`, responde `StartResult.failure` com a data (o
  caminho de erro EXISTENTE → `StartResponse.error`; **front intocado**), em vez de subir sobre um
  volume vazio auto-criado pelo daemon.
- Testes novos: `WorkspaceResolverTest`, `LocalExecutionTargetRemoveTest`, +4 em
  `DockerExecutionTargetArgsTest`, `DatabaseClockTest`, `WorkspaceReaperTest` (8),
  `WorkspaceReaperRetentionTest`, `WorkspaceReaperKillSwitchTest`, `PreviewServerStartAfterGcTest`.

### Prova VIVA na sujeira real (§C.4) — TUDO VENDO
Backend novo com `retention-days=0` + `sweep-ms=5000` (log `/tmp/orq-f12.log`). As DUAS companies
exercitaram os DOIS alvos (142f1ac5 tem agentes DOCKER **e** LOCAL; b92cb312 é LOCAL):
- **`GC LOCAL: 15 coletado(s), 0 pulado(s), 0 orfao(s)`** e **`GC DOCKER: 7 coletado(s), 0 pulado,
  1 orfao`** — exatamente o previsto.
- **DOCKER**: os 7 volumes por-Issue de DONE sumiram; **intocados**: `orq_142f1ac5_31b4798e` e
  `orq_142f1ac5_6fcaff70` (workspaces de AGENTE — a **cobaia 6fcaff70 NÃO foi tocada**),
  `orq_claude_creds`, `orq_codex_creds`, `agente-orquestrator_pgdata`, o anônimo 49MB.
- **LOCAL**: os 15 dirs de DONE sumiram; **preservados** 6 `_agent` + os **3 OPEN** de b92cb312
  (`5ac498be`, `c981a028`, `c3b6ff72`) — prova viva de que discrimina por status TERMINAL.
- **Órfão sintético** `orq_deadbeef_cafebabe_12345678` → **WARN, não coletado** (todo ciclo).
- **Auditoria**: **22** eventos `WORKSPACE_COLLECTED`, todos ator **SYSTEM** (actor_id NULL),
  detail só-IDs (`{target, workspaceRef}`).
- **Foto do kanban 67aa95e5**: `GET …/preview-artifacts/…/content` → **HTTP 200, 26870 bytes**
  DEPOIS da coleta (cópia eterna, ADR-0021, intacta).
- **App de notas 96c1d2ea start** → `{"started":false,"error":"workspace coletado pelo GC em
  2026-07-05 22:06 UTC — a app viva nao volta; recrie a demo com um run novo (a foto do preview
  segue eterna)"}`.
- **`retention-days=7` RESTAURADO** + rebuild/restart (estado de repouso: pid novo no `:8080`,
  sweep 1h, primeira varredura `0 coletado, 1 orfao`). Aprovação `da239e6d` do Atlas: **PENDING,
  intocada** (decisão do operador — não decidi por ele).

### Faxina §D
- `docker rm -f condescending_kepler` — feito (o zumbi sem labels `orq.*`, `orq-claude-code:2.1.201`,
  17h+ segurando `orq_claude_creds` rw). **A credencial ficou intacta** (`orq_claude_creds` segue lá).
- Órfão sintético `orq_deadbeef_cafebabe_12345678` — removido (era MEU artefato de teste; evita WARN
  horário eterno).
- **Volume anônimo `6bea9500…`** (49MB, links=0): montado `:ro` — é um **DIRETÓRIO DE DADOS
  POSTGRES** (`PG_VERSION`, `base/`, `pg_wal`, `postmaster.pid`, de 01/07). **NÃO é lixo óbvio de
  build/teste** → apliquei o ramo conservador do §D: **DEIXADO e REPORTADO**. Apagar um pgdata é
  irreversível; pode ser um pgdata descartado do início DESTE projeto OU de outro projeto do
  operador — **decisão do operador** (recomendo `docker volume rm 6bea9500…` só se confirmar que
  não é dado que ele quer).

### Recortes mantidos (não entraram — §H)
Caps bytes/contagem, LRU, delete assíncrono, pin, `terminal_at`, coleta de órfãos (v1 só loga), GC
de `_agent`, retenção de rows, thumbnail. ADRs 0001–0025 não reabertos.
