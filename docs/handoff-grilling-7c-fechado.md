# Handoff — Grilling Fatia 7c (operador-only) + Recovery de Issue travada — DECISÕES FECHADAS

> Sessão `grill-with-docs` (domain-modeling), 2026-07-01. Fechou as **2 decisões abertas da 7c** (pivô
> operador-only vs agente-como-tool; primitiva de Approval) **e** a pendência adjacente §7 (recovery de
> Issue travada por Run FAILED). Duas lentes aplicadas — `human-architect-mindset` + `codebase-design` —
> confirmaram o recovery e cravaram o seam. **Nenhum código de feature foi escrito** — só decisões.
> Fonte de entrada: `docs/handoff-grilling-7c.md`. Este doc é a fonte para a sessão de implementação
> aplicar: emendas de ADR, ADR novo, mudanças de CONTEXT.md e a lista ORDENADA de mudanças.
> **Resultado-chave: a 7c (Rota A) e o §7 NÃO têm migration nova — são puro código.**

---

## 0. Ponto de partida
- **62/62** testes offline; migrations **V1→V10** validadas em Postgres 16.14 real (zero drift).
- Fatias 7a (anexo por-Issue + união) e 7b (KnowAll SkillSearch read-only) prontas e revisadas.
- Falta só a **7c** (install-on-attach + Approval + agente-como-tool) — agora fechada como **Rota A**.

---

## A) Decisões fechadas (por pergunta)

### Decisão (i) — o PIVÔ: **Rota A (operador-only)**
v1 da 7c **NÃO** inclui o canal agente-como-tool. Install/attach é **board via REST**, apenas.
**Razão dupla (vai pro ADR como o *porquê*, não "faltou tempo"):**
1. Agente-como-tool **contradiz os diferenciais centrais**: #1 (poluição de contexto — o agente pediria N
   skills "por via das dúvidas", gerando ruído/approval-spam) e #3 (isolamento — exigiria furar o container
   selado com rota de rede container→core + credencial run-scoped).
2. O efeito é **assíncrono de qualquer jeito** (materializa no **próximo Run** da Issue). Logo
   "agente pede → board aprova → efeito no próximo run" é **funcionalmente equivalente** a "operador anexa →
   efeito no próximo run", sem ganho *dentro* do run pedinte. Solo owner = **sem 2º humano** p/ justificar o
   laço pede→aprova.

**Q1 (pivô) — CLOSED = operador-only.** Agente-como-tool + Approval + canal callback = **fatia futura dedicada**.

**Q2 (credencial run-scoped) / Q3 (rota de rede) — MORTAS.** Sem canal agente→core; `env=Map.of()` fica;
isolamento (#3) intacto.

**Q4 (o que o agente pode) — CLOSED.** No v1 o agente **não alcança o KnowAll**: para chamar o search ou
receber o body inline ele precisaria de uma ida-e-volta agente↔core (request+response), e o constraint #1 só
dá o `StepSink` de **saída** (#2/#3 selam o container). Consequências:
- KnowAll (7b) é **operator-facing na prática**. O endpoint *autoriza* ator AGENT (pronto p/ quando o canal
  existir), mas nenhum agente selado o alcança. O teste `agente 200` prova a **authz**, não a alcançabilidade.
- Agente **não** lê, **não** anexa, **não** instala. Attach continua **board-only** (a 7a **não** relaxa).
- A frase do ADR-0003 "o tool do KnowAll devolve o body inline pro agente" é reclassificada como
  **comportamento da fatia futura**, não v1.

**Q5 (Approval) — NÃO se constrói na 7c.** Sem agente disparando install, não há o que aprovar. A primitiva
de Approval (idealmente genérica, reusável no gate de revisão do ADR-0006) fica p/ quando agente-como-tool entrar.

**Q6 (UX do efeito) — CLOSED.** Anexo materializa no **próximo Run da Issue** — idêntico à 7a (sem injeção
no container vivo).

**Q7 (mecânica do install-on-attach do board) — CLOSED.**
- **Q7.1 identidade/endpoint = DOBRAR (fold).** Input do attach passa a ser o **`skillId` global** (o que o
  `KnowAllService.SearchResult` já devolve); **`companySkillId` sai do contrato**. 7a e 7c viram **um caminho
  só**. O serviço resolve o install-state internamente:
  - **INSTALLED** → anexa a versão corrente da company. **1 evento** (`SKILL_ATTACHED`).
  - **AVAILABLE** → **instala** (copia a versão corrente global → `company_skill`(source=CATALOG) +
    `company_skill_version`) **+** anexa. **2 eventos** (`SKILL_INSTALLED` + `SKILL_ATTACHED`), mesma tx.
  - "Instalada ou não" vira **detalhe do servidor**, não decisão do cliente. Não há `company_skill` órfã
    (todo install tem pai global, `skill_id` NOT NULL) → dobrar é seguro.
- **Determinado por decisões travadas (não reaberto):** copia a **versão corrente global**
  (`skill.current_version_id`); anexo pina a corrente pós-install; **corrida de install concorrente** coberta
  por `uq_company_skill` (`saveAndFlush` + catch `DataIntegrityViolation` → degrada p/ attach; **mesmo padrão
  TOCTOU da 7a**); board-only; `created_in_run_id = null`.

### §7 — Recovery de Issue travada por Run FAILED — **CLOSED = park-for-human**
**Diagnóstico (do código):** a trava é o campo `execution_lock_run_id` na Issue. `checkout` seta; `complete`
zera **só no sucesso**; **na falha o executor deixa travado** (comentário literal). O **reaper só re-enfileira
Runs `RUNNING`** com lease expirado (`requeueExpired(RUNNING→QUEUED)`) — um `FAILED` (terminal) **nunca** é
tocado. Um Run novo falha o `tryCheckout` (`WHERE execution_lock_run_id is null OR = novo_run` → 0 linhas);
**nem o mesmo agente re-adquire** (a trava é por run-id). `IssueStatus.BLOCKED` e `RunStatus.RECOVERING`
**existem no enum mas nunca foram cabeados** — reservados exatamente p/ isto.

- **§7.Q1 (pivô do recovery) — CLOSED = estacionar em `BLOCKED`, auto-retry ADIADO.** Auto-retry cego de uma
  falha **determinística** queima budget num loop (contra o hard-stop do ADR-0006). O sinal p/ distinguir
  **transiente × determinístico** é o **erro tipado do ADR-0004** (`transient_upstream`+`retryNotBefore` vs
  `model_refusal`), que **não está cabeado** (o adapter hoje só devolve `String`). `BLOCKED` está **fora do
  conjunto pegável** (`status in ('OPEN','IN_PROGRESS')`) → uma Issue `BLOCKED` **não re-executa sozinha**.
- **§7.Q2 (forma do seam) — CLOSED = (A) `IssueService.settleAfterRun(company, issue, runId, RunOutcome)`.**
  `UPDATE` guardado por `execution_lock_run_id = runId`. Sucesso→`DONE`+lock null; falha→`BLOCKED`+lock null.
  - **Segurança grátis do guard:** o executor pode chamar `settleAfterRun` **incondicionalmente** na falha; se
    o run **não** detinha a Issue (ex.: falhou no checkout-conflict, ou na materialização **antes** do
    checkout) → casa **0 linhas → no-op**. Uma Issue-vítima **nunca** é bloqueada por um run que não a possuía.
  - **Antecipa o futuro sem reescrita:** recebe o **`RunOutcome`** (não um estado-alvo). Quando o erro tipado
    do ADR-0004 for cabeado, o ramo `transient_upstream → RECOVERING`(+`retryNotBefore`) entra **dentro** de
    `settleAfterRun`, com **zero mudança no call site** e zero mudança nos testes existentes.
  - **Seam correto (lente codebase-design):** vive no `IssueService` (onde `checkout`/`complete` já moram),
    **não** no executor (vazaria a máquina de estados p/ um caller), **não** numa policy pura separada
    (seam **hipotético** — hoje há **um** só mapeamento falha→BLOCKED; *"don't introduce a seam unless something
    varies across it"*).
- **§7.Q3 (assignee no REOPEN) — CLOSED = manter `assignee_agent_id`.** Board destrava via
  `POST /api/companies/{id}/issues/{issueId}/unblock` body `{decision: REOPEN|CANCEL}` (**board-only**, guard
  `status=BLOCKED`). `REOPEN`→`OPEN` (mantém o agente → o próximo wakeup re-executa a mesma Issue com o mesmo
  agente); `CANCEL`→`CANCELLED`. Re-execução é **só transição de estado** — **não** acopla `IssueService` ao
  motor de heartbeat (respeita os seams: estado-de-Issue ≠ Run-engine).

**Lentes aplicadas (confirmaram, não reverteram):**
- `human-architect-mindset`: **loyalty** (construir auto-retry sobre `String` **trairia** a taxonomia tipada
  do ADR-0004); **failure-isolation** (o bug atual viola "não corromper estado compartilhado / ter fallback" —
  ter *algum* fallback é inegociável); **systems-thinking** exigiu que `BLOCKED` seja **observável** (evento +
  listável), único empurrão-contra, mitigado e barato → refino, não reversão.
- `codebase-design`: seam profundo no `IssueService`, assinatura carregando o `RunOutcome`, guard como parte
  do contrato (colocado ao lado do `tryComplete` → locality).

---

## B) Diffs de ADR/CONTEXT a aplicar (ready-to-apply)

### ADR-0003 (emendar — nova seção "Revisão 2026-07-01 (grilling 7c)")
- **v1 da 7c é operador-only.** Install/attach via **board REST**. As frases "anexam board **E** agente (tool)",
  "install novo por agente → **Approval**" e "o tool do KnowAll devolve o **body inline** pro agente" descrevem
  o **estado-alvo futuro**, **NÃO** o v1.
- **Razão do adiamento (registrar):** agente-como-tool fura #1 (re-poluição) e #3 (isolamento); o efeito
  assíncrono torna operador ≡ agente-pede no resultado; solo owner sem 2º humano.
- **KnowAll no v1 = operator-reachable only** — o agente selado não alcança search/body inline (sem canal).
- **install-on-attach do board:** input = identidade **GLOBAL** (`skillId`), **fold** do endpoint da 7a;
  INSTALLED→1 evento, AVAILABLE→install (copia a versão corrente global, `source=CATALOG`)+attach → 2 eventos,
  mesma tx.

### ADR-0006 (emendar — nova seção "Revisão 2026-07-01")
- A **2ª metade** (gate de revisão + primitiva de Approval) **continua NÃO construída**; a 7c operador-only
  **não a exige**.
- **Primeiro pedaço concreto do enforcement runtime** da máquina de estados de Issue: **recovery de Run
  FAILED** → `IssueService.settleAfterRun` (falha→`BLOCKED`) + `unblock` do board. A decisão de estado vive
  numa função no `IssueService`, guardada por `execution_lock_run_id`, na mesma linha do princípio "decisão
  gravada na mesma transação da mudança de estado".

### NOVO ADR-0011 — Recovery de Issue travada por Run FAILED (park-for-human)
> **Contexto:** um Run que falha estacionava a Issue travada **para sempre** (o `execution_lock_run_id` nunca
> era zerado; o reaper só re-enfileira Runs `RUNNING`). O "travar p/ recovery" era intencional, mas o
> mecanismo de destrava nunca existiu.
> **Decisão:** na saída terminal, `IssueService.settleAfterRun(company, issue, runId, RunOutcome)` liquida a
> Issue — sucesso→`DONE`, **falha→`BLOCKED`** — sempre liberando o lock, sob guard `execution_lock_run_id =
> runId` (só o run detentor liquida). O board destrava via `unblock {REOPEN|CANCEL}` (REOPEN→`OPEN` mantendo o
> agente; CANCEL→`CANCELLED`). **Sem auto-retry no v1:** o sinal transiente×determinístico (erro tipado
> ADR-0004) não está cabeado, e auto-retry cego de falha determinística queima budget (contra ADR-0006). Os
> estados reservados `BLOCKED`(Issue)/`RECOVERING`(Run) passam a ser usados. A assinatura recebe o `RunOutcome`
> para o ramo futuro `transient_upstream → RECOVERING`(+`retryNotBefore`) entrar sem reescrita de caller.
> **Alternativa rejeitada:** auto-retry já no v1 — inseguro sem o erro tipado; premature p/ a escala solo.
> **Lentes:** `human-architect-mindset` (loyalty ao ADR-0004; failure-isolation; observabilidade obrigatória)
> + `codebase-design` (seam profundo no `IssueService`, não no executor nem em policy especulativa).

### CONTEXT.md (glossário — termos a atualizar/adicionar)
- **KnowAll** (refinar): no v1 é **operator-reachable** — o agente selado não o alcança (sem canal
  agente→core); o lado "tool (agente)" é fatia futura. _Avoid_: "o agente consulta o KnowAll em runtime".
- **install-on-attach** (NOVO): board anexa skill do catálogo global ainda-não-instalada → **instala** (copia a
  versão corrente global, `source=CATALOG`) **+** anexa; 1 ação / 2 eventos. Input = **`skillId` global**.
  Board-only no v1.
- **Issue BLOCKED / recovery** (NOVO): Issue **estacionada** após um Run FAILED (lock liberado); o board
  destrava com **REOPEN** (→`OPEN`, mantém o agente) ou **CANCEL**. Sem auto-retry no v1.
- **settleAfterRun** (NOVO, seam): função no `IssueService` que **liquida a Issue pós-Run** (sucesso→`DONE`,
  falha→`BLOCKED`), guardada por `execution_lock_run_id`; recebe o `RunOutcome`.

---

## C) Impactos de schema/migration — ORDENADOS

### ⭐ HEADLINE: **ZERO migration nova.** Nem a 7c nem o §7 tocam o schema.
- **7c** reusa `company_skill`/`company_skill_version` (V6/V7) + `issue_skill_attachment` (V10 — já tem
  `created_in_run_id` nullable, `source=CATALOG` suportado, e `uq_company_skill` p/ a corrida de install).
- **§7** reusa `IssueStatus.BLOCKED` (já no enum) + `execution_lock_run_id` (já existe). **Sem `failure_count`**
  no v1.
- **Não existe "V11"** nesta fatia. É **puro código** (service + controller + repo query + enum).

### Código ORDENADO — 7c (install-on-attach)
1. **Reusar o copy-from-global:** a lógica de copiar `skill`+`globalVersion` → `company_skill`(CATALOG) +
   `company_skill_version` já existe privada em `SkillIndexer.installIntoCompany(...)`. Expor um caminho de
   install-from-catalog — `SkillInstallService.installFromCatalog(companyId, globalSkillId, actorType, actorId)`
   — que carrega `skill` + `skill.current_version` (SkillVersion), copia, emite `SKILL_INSTALLED`, e trata a
   corrida `uq_company_skill` (`saveAndFlush` + catch `DataIntegrityViolation` → retorna o install existente).
2. **`SkillSetService`:** o attach passa a aceitar o `skillId` **global** → resolve install-state (via
   `CompanySkillRepository.findByCompanyIdAndSkillId`): **AVAILABLE**→`installFromCatalog` + pin + attach
   (2 eventos); **INSTALLED**→pin corrente + attach (1 evento). Tudo numa `@Transactional`.
3. **`IssueSkillController.AttachRequest`:** `companySkillId` → `skillId`. Board-only inalterado.
4. **Guards/validação:** `skillId` global inexistente → 404; global sem `current_version` → 409/422
   ("skill sem versão publicada"); re-attach idempotente (pré-check + `unique(issue_id, company_skill_version_id)`,
   padrão 7a).
5. **Testes:** atualizar os testes 7a p/ o novo contrato (`skillId`); + AVAILABLE→install+attach (2 eventos,
   `company_skill` criado `source=CATALOG`); INSTALLED→attach (1 evento); corrida de install concorrente→1
   install (perdedor degrada); 404 skill global inexistente; cross-tenant 403.

### Código ORDENADO — §7 (recovery)
6. **`IssueRepository`:** `tryBlock(issueId, companyId, runId)` — `UPDATE issue SET status='BLOCKED',
   execution_lock_run_id=null WHERE id=:issueId AND company_id=:companyId AND execution_lock_run_id=:runId AND
   status='IN_PROGRESS'` (espelho do `tryComplete`). `tryReopen`/`tryCancel` guardados `WHERE status='BLOCKED'`.
7. **`IssueService`:** `settleAfterRun(company, issue, runId, RunOutcome)` — sucesso→`tryComplete`;
   falha→`tryBlock` + emite `ISSUE_BLOCKED`. `unblock(company, issue, decision, actor)` — REOPEN→`OPEN` (mantém
   assignee) / CANCEL→`CANCELLED`; board-only; emite `ISSUE_REOPENED`/`ISSUE_CANCELLED`.
8. **`AdapterRunExecutor`:** trocar o tail — em **toda** saída terminal com `issueId != null` chamar
   `issues.settleAfterRun(company, issueId, runId, outcome)` (remove o "complete só no sucesso / deixa travado
   na falha"). O guard do `tryBlock` cuida das falhas **antes** do checkout (no-op).
9. **`IssueController`** (ou novo): `POST /api/companies/{id}/issues/{issueId}/unblock {decision}` board-only.
10. **`ActivityAction`:** add `ISSUE_BLOCKED`, `ISSUE_REOPENED`, `ISSUE_CANCELLED` (`SKILL_INSTALLED` já existe).
11. **Testes:** `settleAfterRun(failure)`→`BLOCKED`+lock null+`ISSUE_BLOCKED`; `settleAfterRun` de run que
    **não** detinha a Issue→no-op (vítima intacta); REOPEN→`OPEN` mantém agente → wakeup re-executa e conclui;
    CANCEL→`CANCELLED`; unblock em não-`BLOCKED`→409; board-only (agente 403).

### Ordem recomendada de implementação (fatias finas, TDD + revisão adversarial)
`7c.1` install-from-catalog (reuso) → `7c.2` fold do attach (service) → `7c.3` contrato do controller + testes
→ `§7.1` `settleAfterRun`+`tryBlock` → `§7.2` executor troca o tail → `§7.3` endpoint `unblock`.
(7c e §7 são independentes — podem ir em qualquer ordem entre si.)

---

## D) Constraints / limitações do v1 a lembrar
- **Agente não alcança o KnowAll** (constraints #1–#3): KnowAll é operator-only na prática; **sem body inline
  p/ agente**, **sem agente-como-tool**, **sem Approval**, sem credencial run-scoped, sem rota de rede
  (isolamento #3 **intacto**).
- **install-on-attach board-only**, copia a **versão corrente global** (não escolhe versão). Re-attach
  concorrente idêntico → 409 (padrão 7a, board-gated solo aceito).
- **Recovery:** **qualquer** falha → `BLOCKED` (sem distinção transiente×determinística — erro tipado ADR-0004
  não cabeado); **sem auto-retry**, sem `failure_count`; REOPEN mantém o agente; re-run pelo caminho **normal de
  wakeup** (não auto-enfileira).
- **Contrato da 7a muda** (`companySkillId`→`skillId`): atualizar os testes 7a existentes — **não é regressão**,
  é o fold. Piso atual: **62/62** offline.
- **Fatias futuras reservadas (fora do v1):**
  - agente-como-tool — forma-alvo que **respeita o constraint #1**: o agente **emite o pedido pelo `StepSink` de
    saída** que já tem (ex.: step `REQUEST_SKILL_INSTALL skillKey=X`), o core parseia → cria **Approval**
    PENDING (genérico, reusável no gate de revisão do ADR-0006), operador decide, efeito no próximo Run —
    **sem** rota de rede inbound nem credencial run-scoped.
  - auto-retry on transient — `RECOVERING` + `retryNotBefore`, gated no **erro tipado do ADR-0004**; ramo dentro
    de `settleAfterRun`.
  - `failure_count` / give-up-após-N-falhas (só quando o auto-retry existir).

---

## Nota de reconciliação (2026-07-14 — fecho documental; divergência MÉDIA da auditoria doc×código de 2026-07-11)

**A corrida de install concorrente NÃO "degrada pra attach" — devolve 409 limpo.** O Q7.1
previa `catch DataIntegrityViolation → degrada p/ attach`; o construído captura a exceção e
lança `ConflictException` ("Skill ja instalada nesta company (install concorrente)") →
**HTTP 409** (`SkillInstallService.java:64-66`). O código é MAIS estrito e mais correto que o
documentado: continuar dentro de uma transação abortada pós-`DataIntegrityViolation` não é
seguro em JPA (o "degrada e segue" exigiria transação nova), e o 409 é acionável — o caller
re-tenta o attach e encontra a skill instalada pelo vencedor da corrida. Doc desatualizado,
comportamento superior; nada a construir. Corpo original intocado.
