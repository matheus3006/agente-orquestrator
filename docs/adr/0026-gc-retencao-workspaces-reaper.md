# ADR-0026: GC/retenção de workspaces — o segundo reaper da casa (WorkspaceReaper)

**Status:** Aceito (2026-07-05, execução F.12 — `docs/handoff-grilling-gc-retencao-workspaces.md`)

## Contexto
Hoje **nenhum** workspace é removido, jamais: zero `docker volume rm` no código, zero
`@DeleteMapping` na API, FKs todas RESTRICT. Cada Issue cria um workspace durável (volume DOCKER
`orq_<c8>_<a8>_<i8>` / dir LOCAL `<root>/<company>/<agent>/<issue>`) que vive para sempre —
crescimento sem teto por construção. A dor é de **CONTAGEM** (um volume/dir por Issue, para
sempre; bytes hoje são KB porque o v2 manda deps para tmpfs), silenciosa e cumulativa: o cliente
herdaria hosts enchendo até intervenção manual.

Evidência de mercado (deep-research 2026-07-05, 10 achados confiança alta, verificação
adversarial — §E do handoff): a indústria converge em **reaper agendado/background com política
declarativa**, botão manual só como válvula de escape. On-completion como default foi REFUTADO
(GitLab não limpa by design + `FF_ENABLE_JOB_CLEANUP` default false; K8s modela TTL=0 como caso
degenerado do MESMO controller TTL-after-finished; GitHub Actions self-hosted não faz GC; Jenkins
roda `WorkspaceCleanupThread` por idade). TTL desde o estado **TERMINAL** é a âncora canônica
(K8s `lastTransitionTime`).

## Decisão
Um segundo reaper, par do `PreviewServerSupervisor.reapOnce()`/`LeaseReaper`: **`WorkspaceReaper`**
agendado no `HeartbeatScheduler`, coleta o workspace por-Issue de Issues **terminais**
(DONE/CANCELLED) após janela de retenção, nos DOIS alvos, com auditoria durável. Nenhum outro
artefato é tocado (as rows do banco ficam — banco inteiro < 10 MB; a foto v1 é eterna por design,
ADR-0021).

### B1. Natureza: mecanismo de PRODUTO (reaper agendado), não faxina ops
Molde estrutural já provado da casa: `sweepOnce()`/`sweepTarget()` público testável + `@Scheduled`
+ config `@Value` + teste com retenção curta. Faxina pontual/script resolve 1 vez e a dor volta a
cada fatia — recusada como mecanismo.

### B2. Política: TTL desde a TRANSIÇÃO TERMINAL, 7 dias, LIGADO por padrão, kill-switch
- Configs: `orquestrador.workspace.gc.enabled:true` · `…retention-days:7` · `…sweep-ms:3600000`
  (1h — TTL de dias não pede varredura de minuto).
- **Âncora v1 = `issue.updated_at`** (VERIFICADO no banco 2026-07-05: acompanha a transição
  terminal — todo `try{Complete,Cancel,…}` faz `updated_at = now()`). Direção de falha
  conservadora: qualquer toque posterior só ADIA a coleta.
- **Comparação SEMPRE com o relógio do BANCO** (`now()` do Postgres via `DatabaseClock`), nunca
  com o clock/memória do processo — a âncora é durável no banco e a comparação usa uma única
  leitura de relógio da MESMA fonte (defesa contra clock skew e contra reset no restart).
- Rejeitadas: TTL desde a criação (coletaria cedo Issue de vida longa); default desligado
  K8s-style keep-forever (dor silenciosa); caps por bytes/contagem/LRU (volumes KB — camada
  FUTURA, §H); âncora `terminal_at` dedicada (migração sem necessidade).

### B3. Escopo do colecionável: SÓ workspace por-Issue de Issue terminal vencida
- DOCKER: volume `orq_<c8>_<a8>_<i8>` · LOCAL: `<root>/<companyId>/<agentId>/<issueId>`.
- **Fora por construção**: Issues BLOCKED/PENDING_REVIEW (não-terminais → workspace de falha
  preservado para debug DE GRAÇA pela máquina de estados — equivalente do `cleanWhenFailure=false`
  do Jenkins); workspaces de agente/conversa (`orq_<c8>_<a8>` / dir `_agent`); volumes de
  credencial (`orq_claude_creds`/`orq_codex_creds`) e `pgdata` (**denylist ESTRUTURAL, não lista
  mágica**: nem casam o padrão de nome de workspace por-Issue); volumes não-`orq_` são invisíveis.
- **Órfãos** (nome com o formato de workspace por-Issue que não deriva de NENHUMA Issue do banco):
  v1 **detecta e LOGA WARN, não coleta** — buraco confesso da pesquisa (zero prior art); órfão
  logado é sinal de bug de derivação nosso, não alvo de deleção cega.

### B4. Sem pin no v1; consequência no preview v2 ACEITA pelo operador
O PreviewServer v2 monta o volume da Issue `:ro`; DONE não reabre. Coletar mata o botão "app viva"
para sempre. Issues v1 não perdem NADA (foto = `preview_artifact` no banco, eterna, ADR-0021).
Mitigação: start de v2 em Issue coletada responde **erro LEGÍVEL** ("workspace coletado pelo GC…")
pelo caminho de erro existente (preflight de volume, 409). **Front intocado.** Pin/`workspace_pinned`
recusado no v1 (migração + superfície + armadilha pin-pós-expiração). A lacuna "v2 sem registro
visual durável" fica anotada como argumento pró-fatia **thumbnail** (§H).

### B5. Mecânica: sweep WORLD-DRIVEN por DERIVAÇÃO, remoção pelo seam, auditoria verdadeira
- **World-driven**: 1×/ciclo lista o MUNDO (DOCKER `docker volume ls` filtrado ao formato de
  workspace por-Issue; LOCAL a árvore do root). Reconhecimento por **DERIVAÇÃO**: o
  `WorkspaceResolver` (quem batiza) gera o ref esperado de cada Issue e intersecta com o mundo.
  **Zero parsing de nome, zero estado especular no banco, ZERO migração de schema** — os nomes de
  volume usam UUID encurtado (8 chars), a inversão nome→banco é impossível por construção; a
  fonte da verdade é o mundo, como no `PreviewServerSupervisor`. Órfão = ref no mundo fora de TODA
  derivação.
- **Re-read fresco**: imediatamente antes de CADA remoção, re-lê o status da Issue no banco
  (padrão do K8s TTL controller: fresh GET + precondition antes do delete — fecha a race com
  REOPEN/checkout).
- **Remoção via seam `ExecutionTarget`** — método **ADITIVO** `removeIssueWorkspace(resolver,
  companyId, agentId, issueId)` → `IssueWorkspaceRemoval {REMOVED, ABSENT, IN_USE, FAILED}`:
  `DockerExecutionTarget` → `docker volume rm`; `LocalExecutionTarget` → delete recursivo com
  **GUARD de path** (canonical path DEVE estar sob o root canônico — invariante TESTADO; defesa
  contra `rm -rf` catastrófico). Síncrona no thread do scheduler (volumes KB; nunca dentro de
  transação de banco). Mesmo padrão aditivo do posture (ADR-0025) — não toca o que está provado.
- **Workspace em uso** (PreviewServer vivo montando o volume): `docker volume rm` falha → **IN_USE
  → WARN + skip**; o próximo ciclo tenta de novo (o TTL de 15min do server resolve sozinho). O
  sweep NUNCA aborta por 1 candidato — segue para os demais.
- **Auditoria**: evento novo `ActivityAction.WORKSPACE_COLLECTED` no `activity_log`, detail
  só-IDs (target + ref do workspace; issueId é o `entity_id` — ADR-0009), com **ATOR NOVO
  `ActorType.SYSTEM`** (o enum só tinha BOARD/AGENT; o reaper é ato do PIPELINE — atribuição
  verdadeira; logar como board/agente mentiria, a classe de sujeira do incidente Atlas).

### B6. Registro
Este ADR. O switch exaustivo de `ActorType` (`CompanyAccessGuard`) ganha `case SYSTEM -> false`
(fail-safe deny: o ator SYSTEM nunca autentica uma requisição company-scoped).

## Riscos → estratégias
- **Deleção catastrófica de path no LOCAL** — GUARD canonical-sob-root, invariante TESTADO por
  unidade (path fora do root → recusa, o alvo sobrevive). UUIDs não carregam `../`, mas o guard é
  defesa-em-profundidade obrigatória.
- **Tocar um seam provado (`ExecutionTarget`)** — mudança ADITIVA: métodos DEFAULT na interface
  (os 4 fakes de teste compilam intactos), Local/Docker sobrescrevem; TDD red→green; piso da suíte
  **≥334 verde**. Zero toque em skills-path (`.claude/skills`), `.orq/preview/*`, parsers, posture.
- **Coletar demais / cedo** — colecionável restrito a Issue TERMINAL + retenção + re-read fresco;
  órfão só LOGA. Direção de falha do TTL é conservadora (adia, não antecipa).
- **Deleção sem rastro** — `activity_log` (append-only por trigger V8) é o órgão de auditoria; o
  evento é gravado APÓS a remoção REAL (só REMOVED audita — nunca um falso "coletado").
- **Clock skew / reset no restart** — âncora durável (`updated_at`) + relógio único do banco.

## Recorte explícito (fora desta fatia — §H do handoff)
Caps por bytes/contagem/histerese/LRU · pin/keep-forever · âncora `terminal_at` dedicada · delete
assíncrono (Resource Disposer) · coleta de órfãos (v1 só loga) · GC de workspaces de agente/`_agent`
· retenção de rows do banco · arquivamento/filtro de Issues DONE no board · **thumbnail do preview**
(fatia própria; ganhou argumento novo aqui: v2 não tem registro visual durável) · não reabrir ADRs
0001–0025.

## Relaciona
ADR-0016 (seam `ExecutionTarget`; LOCAL dev-only) · ADR-0021 (foto eterna — o que a coleta NÃO
apaga) · ADR-0023 (PreviewServer monta o volume `:ro` — a causa do IN_USE e do aviso legível) ·
ADR-0025 (padrão de método ADITIVO no seam) · ADR-0009 (activity_log, detail só-IDs) · ADR-0010
(módulo profundo — `WorkspaceResolver` dono único da nomeação) · ADR-0012 (estados terminais vs
não-terminais).
