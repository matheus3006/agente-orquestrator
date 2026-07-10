# Handoff — GRILLING: fatia G5 — ID sequencial de Issue (#N por company) (2026-07-10)

> **Tipo:** registro de grill FECHADO (nenhuma linha de produção foi escrita nesta sessão).
> **Origem:** linha G5 do `handoff-grilling-insatisfacoes-funcionamento-ceo.md` §B — *"apontar
> 'qual task deu problema' exige colar UUID"*; pré-requisito prático do **caso B** (§B.2 B1/B2:
> task corretiva NOVA ligada à #N). Sequência **por company** porque a company é a fronteira de
> isolamento (decisão de origem, não re-discutida).
> **Contexto de orquestração:** faixa **R1-B** do ledger (`controle-paralelismo.md`) — G5 roda em
> PARALELO ao fecho da G3 (decisão do dono, 2026-07-10; colisão zero verificada arquivo a arquivo).
> **Migration V23 RESERVADA** para esta fatia; ADR-0033 consumida (autorada nesta sessão).

---

## §P — Premissas validadas no código (Explore, 2026-07-10)

- **P1 · Chokepoint único de criação.** Toda Issue nasce em `IssueService.create`
  (`IssueService.java:53-57` → `new Issue(UUID.randomUUID(), …)`). Callers: o POST direto do board
  (`IssueController.java:40-46`) e a proposta do CEO aprovada (`ApprovalService.java:188-197`,
  branch `CREATE_ISSUE`). TODOS os testes criam por `issues.create(...)`; **zero** seeds/fixtures/
  `new Issue()` fora daqui. O #N tem UM lugar para nascer.
- **P2 · Identidade hoje = título + UUID truncado.** UI inteira usa `shortId` (8 chars,
  `format.ts:33-35`); UUID completo só no props-panel do detalhe
  (`issues/[issueId]/page.tsx:290-291`). No prompt do CEO as issues aparecem **só por título**
  (`LiveStateProjector.java:95-102`) — duas tasks de título parecido são indistinguíveis até para ele.
- **P3 · Precedentes de ordinal: 5× MAX+1, todos com serializador natural.** `preview_artifact.version`
  (V20: *"seguro porque os runs de uma Issue sao serializados pelo execution_lock"* — a migration
  declara a precondição), `RoleTemplateVersionRepository.maxVersion:15-21` (*"nunca count+1"*),
  carta (`CompanyMemoryConsolidator.java:47-54`), 2× skills. **Criação de Issue NÃO tem serializador**:
  dono na UI + approve do CEO correm em paralelo (threads Tomcat).
- **P4 · Receita de serialização H2-testável já existe.** `AgentRepository.findByIdForUpdate:22-24`
  (`@Lock(PESSIMISTIC_WRITE)`), escolhida lá explicitamente por ser *"multi-no-safe e testavel no H2"*.
- **P5 · Uniques totais valem no H2 via anotação na entidade** (`PreviewArtifact.java:22-23`,
  `@UniqueConstraint`); índices **parciais** são Postgres-only. Testes rodam H2 com **Flyway OFF**
  (`ddl-auto: create-drop`) ⇒ backfill da V23 só é provável no VENDO live.
- **P6 · `IssueService.create` é `@Transactional`** ⇒ catch-retry de unique DENTRO da tx não
  transplanta (tx abortada — alerta literal em `SkillInstallService.java:21-22`; o retry-once do
  preview funciona porque roda FORA de tx).
- **P7 · 1 JVM hoje** (docker-compose sobe 1 backend; ~200 threads Tomcat + scheduler single-thread).
  Padrão da casa: constraint no banco já cobre o futuro multi-nó.
- **P8 · Lista de issues do board SEM ordenação** (`findByCompanyId` sem ORDER BY; página sem sort).
- **P9 · Conversa sem ponteiro de issue.** `ConversationTurn` não tem `issueId`; o dono aponta task
  por texto livre; colisão-por-título documentada em `ceo-conversa.ts:144-157`.
- **P10 · O run de Issue (worker) não recebe identidade da Issue no prompt** (`command` estático,
  `AdapterRunExecutor.java:174`) — injetar #N ali seria mecanismo NOVO, não desta fatia.

## §D — Decisões (com o racional arrancado)

### D1 — Alocação: trava na linha da company + MAX+1, unique total de backstop
No `create`: `CompanyRepository.findByIdForUpdate(companyId)` (cópia da receita do Agent, P4) →
`maxSeqByCompany(companyId) + 1` (espelho do `maxVersion`, P3) → insert, tudo **na mesma tx** já
existente. Backstop: `unique (company_id, seq)` **total** (vale no H2, P5) — se qualquer bug furar a
serialização, o banco rejeita em vez de duplicar; e o índice é o lookup natural do futuro caso B
("task #12"). **Contrato do #N:** por company · denso (1, 2, 3… sem buraco — alocação na mesma tx do
insert: rollback não queima número) · imutável · **nunca reusado enquanto Issue não for deletável** ·
começa em 1.
**Rejeitadas:** contador persistido na company (estado novo e mutável numa tabela que hoje é só
identidade; a vantagem "nunca reusa mesmo com delete" é teórica — não existe caminho de delete de
Issue, o reaper F.14 preserva a linha e as FKs de preview/review/run a tornam praticamente indelével;
**condição de migração registrada na ADR-0033**: surgindo delete de Issue, essa fatia migra para
contador) · unique sem serialização (criação falharia aleatoriamente sob rajada — a Fatia 2 criará
várias de uma vez; inaceitável) · alocação fora da tx com retry (quebraria a atomicidade
insert+número sem necessidade, P6).

### D2 — Backfill: retroativo na própria V23, coluna obrigatória desde o dia 1
`row_number()` por company ordenado por `(created_at, id)` (UUID desempata timestamps idênticos);
depois `NOT NULL` + unique. **Racional:** contrato sem exceção ("toda Issue tem #N, sempre" — código
carrega `int`, nenhuma superfície trata "sem número"); o caso B pode apontar qualquer task da
história; #1 = a mais antiga bate com a intuição. **Contexto do dono (2026-07-10):** banco é de
desenvolvimento, será **zerado** antes do uso real; nenhuma organização real dele lá dentro — risco
do backfill ≈ 0; ninguém referencia por número hoje (só UUID colado), logo não há conflito possível
com referência existente.

### D3 — Superfícies: regra única, sem lista de exceções
**Palavras do dono:** *"#N é o que eu vejo, UUID é o id único para auditoria e debug."*
`#12` passa a ser a identidade humana da Issue em TODA superfície; UUID permanece como identidade de
máquina (rotas `/issues/{uuid}` intocadas + props-panel do detalhe). Formato: `#12` em mono apagado,
estilo dos chips de id existentes.

| Superfície | Hoje | Vira |
|---|---|---|
| Lista de issues (meta da linha, `issues/page.tsx:92`) | `a3f81c02` | `#12` |
| Detalhe (`issues/[issueId]/page.tsx:175`) | só título | `#12` ao lado do título (+ linha `número` no props-panel) |
| Feed de atividade (`feed.ts:61-64`) | `Título` ou `shortId` | `#12 Título`; fallback sem título = `#12` (fim do shortId de issue) |
| Card do agente (`mission-control.ts:71-96` / `agent-card.tsx:53-62`) | `executando Título` | `executando #12 Título` |
| Fila de review (`reviews.ts:35`) / Servers (`servers/page.tsx:78,126`) | título ou `shortId` | `#12 Título` / `#12` |
| Card de approval na conversa (`approval-thread-card.tsx:274`) | `Report — Título` | ganha `#12` quando a issue resolve |

**Consequência (não-decisão, defeito evitado):** a lista do board passa a ordenar por `seq` desc
(P8 — com números visíveis, ordem aleatória leria como bug); é a MESMA ordem do estado vivo
(`createdAt` desc ≡ `seq` desc).

### D4 — #N no contexto do CEO: 1 linha no estado vivo; instruções do turno INTOCADAS
`LiveStateProjector` linha da issue vira `- #12 [BLOCKED] Landing page — Feynman`. Fecha o ciclo já
no G5: dono digita "a task 12 deu problema" → CEO acha `#12` no estado vivo → responde citando `#12`
(mesmo número do board). Custo ~4 chars/linha. **Zero mudança na seção de instruções** — recém
reescrita e provada ao vivo no hotfix G1+G2 (31→9 eventos, ~8,5s); o exemplo no digest ensina a
notação sozinho. **Limite aceito de olhos abertos:** cap de 30 issues mais recentes permanece — #N
fora do recorte não está no contexto; dar ao CEO *busca* de qualquer #N é mecanismo do caso B.

### D5 — ADR-0033: sim, autorada NESTA sessão (numeração é por decisão, não por merge)
`docs/adr/0033-identidade-sequencial-issue.md` (untracked; a execução **commita, não reescreve**).
Registra: o contrato do #N (D1), por que MAX+1 aqui exigiu trava quando os outros 5 ordinais não
travam nada (a precondição da V20 não vale na criação de Issue), a condição de migração para
contador, e a regra transversal de identidade (#N humano / UUID máquina).

## §M — Mapa de implementação (TDD, sessão de execução)

- **M1** — `V23__issue_seq.sql`: `alter table issue add column seq int` → backfill
  `row_number() over (partition by company_id order by created_at, id)` → `set not null` →
  `constraint uq_issue_company_seq unique (company_id, seq)`. Comentário no molde da V20 (declarar a
  precondição: *"seguro porque a alocação serializa no lock da linha da company; a unique é
  backstop, não mecanismo"*).
- **M2** — `Issue.java`: campo `seq` (`int`, `nullable=false`) + construtor ganha o parâmetro +
  `@Table(uniqueConstraints = @UniqueConstraint(name="uq_issue_company_seq",
  columnNames={"company_id","seq"}))` (receita `PreviewArtifact.java:22-23` — é isso que faz o H2
  dos testes enforçar).
- **M3** — `CompanyRepository.findByIdForUpdate` (cópia verbatim da receita
  `AgentRepository.java:17-24`) + `IssueRepository.maxSeqByCompany`
  (`select coalesce(max(i.seq), 0) from Issue i where i.companyId = :companyId` — espelho do
  `maxVersion`).
- **M4** — `IssueService.create(4-args)`: lock da company (`Optional` vazio →
  `NotFoundException` — **de carona**, create ganha 404 limpo para company fantasma; hoje estoura na
  FK) → `seq = maxSeqByCompany + 1` → `new Issue(…, seq)`. Callers e testes existentes intocados (P1).
- **M5** — Contrato API: `IssueResponse` ganha `seq`; `MissionControlService.ActiveRun` ganha
  `issueSeq` (o join `issueTitlesFor` vira id→(title, seq)); front `IssueSummary.seq: number` +
  tipo `ActiveRun` (`api.ts:188-198`).
- **M6** — Superfícies front (tabela da D3) + ordenação da lista por `seq` desc + lookups das
  páginas estendidos de id→título para id→(título, seq) (`feed.ts` e quem monta os mapas).
- **M7** — `LiveStateProjector.java:96`: prefixo `#N ` na linha da issue (+ ajuste do teste do
  projector).
- **M8** — Commit dos untracked desta fatia: ADR-0033 + este handoff.
- **M9** — Roadmap: linha G5 `PLANEJADO → EM EXECUÇÃO → VENDO/FECHADO`.

**Testes-chave (o RED honesto do TDD):**
- **T-corrida (o teste que justifica o mecanismo):** N threads criando Issue na MESMA company →
  seqs distintos e densos 1..N, zero exceção. Sem o lock (só a unique) ele QUEBRA. Molde: o teste
  concorrente do lock do Agent (wakeup) — localizar e copiar o padrão (TransactionTemplate).
- T-independência: companies A e B numeram 1, 2, … independentes.
- T-caminho-CEO: assert de `seq` no teste existente do dispatch `CREATE_ISSUE` do `ApprovalService`.
- T-projector (`#N` na linha) · T-response (`seq` no JSON) · vitest: feed com `#N`, lista ordenada.

## §VENDO — plano de prova (Postgres real; o backfill NÃO aparece no H2)

1. **OPERADOR** aplica a V23 no `:8080` compartilhado (gotcha registrado: o harness barra restart
   de pid alheio — script cirúrgico no molde do `f15-vendo.sh`/`g3-m9-operacao.sh`; agente só faz
   verificação read-only).
2. SQL: `select company_id, seq, title, created_at from issue order by company_id, seq` →
   denso 1..N por company, ordem espelha `created_at` (D2).
3. UI: criar issue nova → nasce MAX+1; lista mostra `#N` ordenada; detalhe com `#N`; feed com `#N`.
4. CEO (teams-demo): perguntar *"como está a #2?"* → a resposta cita a task certa pelo número
   (estado vivo carregou o `#N`, D4).
5. Card do agente com run ativo mostra `#N Título`.

## §FORA DO ESCOPO (explícito)

O caso B em si (roteamento ao Arquiteto + task corretiva ligada à #N — fatia futura, contrato B1/B2
já fechado) · busca/lookup de #N além do cap de 30 do estado vivo · rota por número
(`/issues/#12`) · conserto da resolução-por-título do approval card (colisão `ceo-conversa.ts:144-157`
fica como está; o card só EXIBE `#N`) · #N no prompt do run de Issue (P10 — mecanismo novo) ·
ordenação de outras listas além da de issues.

## §ARMADILHAS para a execução (do ledger + desta sessão)

- **Branch própria a partir de `main`** — NUNCA de `feat/agents-md-codex-adr0032`. Landing por PR.
- **V23 reservada** para esta fatia — conferir no dia que segue sendo a próxima livre.
- **Piso de testes:** main = **429** em 2026-07-10; se a G3 mergear antes, vira **443** — reconferir
  com `mvn -o test` na main do dia. Front: última contagem conhecida **242** (vitest) — reconferir.
- **`frontend/src/lib/api.ts` colide com a Fatia 2** (campo `persona`) — por isso o G5 landa ANTES.
- **Untracked na working tree compartilhada** (hoje na branch da G3): este handoff + ADR-0033 + a
  linha G5 do roadmap. São DESTA fatia — a execução do G5 os commita; o fecho da G3 (R1-A) não deve
  varrê-los num `git add` largo (a carona da linha do roadmap é conflito trivial aceito no ledger).

## §Kickoff — mensagem para a sessão de execução

Ver bloco de código no fim da sessão de grill (registrado também aqui para durabilidade):

```
Invoque as skills /tdd e /codebase-design. Execute a fatia G5 — ID sequencial de Issue (#N por
company). O plano está FECHADO em docs/handoff-grilling-id-sequencial-issue.md (P1–P10, D1–D5,
M1–M9, §VENDO, §ARMADILHAS) e a ADR-0033 JÁ ESTÁ AUTORADA em
docs/adr/0033-identidade-sequencial-issue.md — commitar como está, não reescrever.

Repo: /Users/matheussouza/Pessoal/orquestrador-agentes/agente-orquestrator.

Setup (ANTES de codar):
1. `git checkout main && git pull`; branch nova `feat/issue-seq-adr0033` A PARTIR DE MAIN — NUNCA
   de feat/agents-md-codex-adr0032. Os untracked desta fatia (handoff, ADR-0033, linha G5 do
   roadmap) vêm junto na working tree: commite-os NESTA branch.
2. Reconferir o piso do dia: `cd backend && mvn -o test` na main (2026-07-10 = 429; se a G3
   mergeou, ~443) e `cd frontend && npx vitest run` (última conhecida 242). Anotar os dois.
3. Conferir que V23 segue sendo a próxima migration livre (última existente hoje: V22).

Execução: TDD RED→GREEN na ordem M1→M9 do handoff. O teste que justifica o mecanismo é o
T-corrida (N threads criando na MESMA company → seqs densos, zero exceção; sem o lock ele quebra).
Receitas a COPIAR, não inventar: AgentRepository.findByIdForUpdate:17-24 (lock) ·
RoleTemplateVersionRepository.maxVersion:15-21 (max+1) · PreviewArtifact.java:22-23
(@UniqueConstraint na entidade — o que faz o H2 enforçar a unique).

Depois: /verify no fluxo real (criar issue pela UI → #N no board/feed/detalhe; turno com o CEO na
teams-demo citando "#2") + §VENDO do handoff. ATENÇÃO: o backfill da V23 SÓ aparece no Postgres
real, e o :8080 é compartilhado — o OPERADOR roda restart/migração (gotcha
ambiente-restart-8080-compartilhado); o agente verifica read-only.

Landing: PR para main (nunca push direto; regra prefer-PR). No fim, handoff de volta à sessão de
controle no contrato do ledger (docs/controle-paralelismo.md §Contrato): branch/PR, arquivos
tocados, V23 consumida, piso novo, pendências, decisões novas com racional.

ARMADILHA: frontend/src/lib/api.ts colide com a Fatia 2 (campo persona) — o G5 landa ANTES; se a
Fatia 2 já estiver em voo quando você começar, PARE e avise o Matheus antes de tocar api.ts.
```
