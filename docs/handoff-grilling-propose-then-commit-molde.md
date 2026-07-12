# Handoff (grill FECHADO) — Propose-then-commit de molde + drift-badge

> **Fatia:** R10-A da rodada 10 (sessão de controle de paralelismo). Item congelado da **Frente D**
> (decisão v1 TOTAL do Matheus), destravado pela saída estruturada nativa (ADR-0039/F-N3).
> **Fonte da verdade da fatia** (as decisões abaixo mandam sobre a implementação).
> Grill conduzido com o Matheus em 2026-07-12 — uma pergunta por vez, exemplos concretos, recomendação
> com porquê (regra da casa). Snapshot base: main `999a310`, piso 635 backend / 280 front.

## §0 O contexto (a lacuna e o insumo)

**A lacuna.** Hoje molde nasce board-only-direto: [`RoleTemplateService.appendVersion`](backend/src/main/java/com/orquestrador/org/roletemplate/RoleTemplateService.java:107)
hardcoda `proposedBy = approvedBy = actorId` e `status = "APPROVED"`, com um comentário inline apontando
para ESTA fatia. O "propor" e o "comitar" são o mesmo ato, da mesma pessoa. A governança que a ADR-0017
prometeu (*"O CEO pode CRIAR molde — a pedido do dono, com aprovação"* + propose-then-commit · diff ·
drift-badge) nunca foi construída. A auditoria doc↔código de 2026-07-11 registrou os dois furos como
achados MÉDIA (questão H): (H1) *"criar molde é board-only direto; `ApprovalActionType` nem tem essa ação"*;
(H2) *"`pinForAgent` existe e é reversível, mas sem chamador de produção"*.

**O insumo que destravou.** A saída estruturada nativa (ADR-0039): "proposta de molde" nasce como um
bloco validado de `blocos[]`, precedente direto do `retrabalho` da R9-B — "campo validado → rota", sem
maquinário novo. O trilho já existe: [`TurnOutputSchema`](backend/src/main/java/com/orquestrador/conversation/TurnOutputSchema.java) →
[`ProposalScanner.scanStructured`](backend/src/main/java/com/orquestrador/governance/approval/ProposalScanner.java:70) →
[`ApprovalService.dispatchAction`](backend/src/main/java/com/orquestrador/governance/approval/ApprovalService.java:199)
switch (já roteia `HIRE_AGENT`/`CREATE_ISSUE`/`WAKEUP_AGENT`/`CREATE_TEAM`).

---

## §1 Decisões (Dn) — cada uma com o porquê

### D1 — Canal: o CEO PROPÕE pelo gate; o dono CRIA/EDITA direto (mão soberana). (G1)
O CEO ganha uma ação nova: **propor molde**. Na conversa, a persona **Executor** (a que protocola gates,
ADR-0031 §9) emite um bloco `proposta` com o preset → cai um card de aprovação → o dono decide. O dono
**continua** criando/editando molde direto na tela `/moldes` — é **mão soberana** (ADR-0018): o dono é o
próprio aprovador, gatear ele contra si mesmo é no-op.
- **Absorve a questão H1 da auditoria de vez:** `ApprovalActionType` ganha `CREATE_ROLE_TEMPLATE`.
- **Porquê:** é exatamente o fluxo da ADR-0017 §Moldes (*"dono pede → CEO propõe justificado → dono
  aprova → persiste"*) e o gate-por-ALCANCE (molde é GLOBAL → mexer nele passa pelo gate; ação local
  do dono = soberana).

### D2 — Fila: **Approval**, não Review. **Emenda à ADR-0017.** (G2)
A ADR-0017 §Consequências diz *"a governança de molde reusa a fila de **Review**"*. **Isso não cabe:** a
tabela `review` (V11) exige `issue_id` e `run_id` **NOT NULL** ([`Review.java:36`](backend/src/main/java/com/orquestrador/work/review/Review.java:36),
:39) e uma proposta de molde não tem issue nenhuma. A fila certa é a **Approval** (a mesma dos cards
`HIRE_AGENT`/`CREATE_TEAM`).
- **Porquê:** a Approval JÁ É a primitiva "propõe → aprova/recusa com kickback" (ADR-0018/0035); adicionar
  `CREATE_ROLE_TEMPLATE` + uma rota no dispatch é **idêntico** ao que a ADR-0037 fez com `CREATE_TEAM`.
  **Zero migração** (`ApprovalActionType` é `@Enumerated(STRING)`). O texto "reusa a Review" foi escrito
  em 2026-07-04, **antes** de a Approval amadurecer como fila de propostas — a realidade passou por cima.
- **Não divergir em silêncio:** a ADR-0017 é emendada com este racional (ver D9).

### D3 — A versão nasce só no APPROVE, sempre `APPROVED`; `proposedBy`/`approvedBy` ganham valores DISTINTOS. (realização de D1+D2)
No caminho do CEO, nenhum `role_template_version` é criado enquanto a proposta está PENDING — o dispatch
do APPROVE é que chama `createTemplate`. A versão nasce **`APPROVED`** com `proposedBy` = o agente Executor
(`approval.requestedByAgentId`) e `approvedBy` = o dono (`actorId` do decide). É a governança que o
hardcode `proposedBy=approvedBy=actor` era um stand-in.
- **O status `DRAFT` fica vestigial** (nunca persistido): o "rascunho" vive na **Approval PENDING**, não
  numa linha de versão. Espelha `HIRE_AGENT` (que não cria um "agente rascunho"). **Sem mudança de schema.**
- **Serviço:** `createTemplate`/`appendVersion` ganham a separação proposer/approver; o caminho do dono
  direto passa `(actorId, actorId)`, o do CEO passa `(requestedByAgentId, ownerActorId)`.

### D4 — Drift-badge **derivado na tela**, zero estado novo. (G3)
Drift = a versão pinada do agente ≠ a versão corrente do molde. Os dois dados já existem: o agente carrega
`pinnedTemplateVersionId` e o molde carrega `currentVersionId`. Badge no card do canvas quando diferem.
- **O único ajuste de backend é um read-join:** `AgentResponse` passa a expor `pinnedTemplateId` (o
  `roleTemplateId` da versão pinada) — assim a tela mapeia agente→molde→corrente **sem** carregar todos os
  savelogs. É relação já existente exposta na leitura, **não estado novo** (a derivação em si mora na tela).
- **Porquê:** persistir um flag de drift ficaria stale quando o molde muda; é pura derivação.

### D5 — Re-pin **COMPLETO**: re-aplica o preset da versão (motor + skills) e move o ponteiro; **mão soberana, direto** (sem gate). (G4)
[`pinForAgent`](backend/src/main/java/com/orquestrador/org/roletemplate/RoleTemplateService.java:74) hoje
**só move o ponteiro** — não re-aplica motor nem skills (que foram copiados no hire, snapshot). Um botão que
só chamasse `pinForAgent` **limparia o badge sem mudar o agente** (mentira). Então o re-pin desta fatia
**re-aplica de verdade**: sobrescreve o motor com o da versão e reseta o skillset para o da versão, além de
mover o pin.
- **Absorve a questão H2 da auditoria:** o chamador/endpoint que faltava nasce aqui.
- **Alcance:** re-pin mexe em **um** agente de **uma** empresa = ação **local** → **mão soberana do dono,
  board-only-direto** (NÃO passa pelo gate; o gate é só pro molde, que é global).

### D6 — Re-pin **SUBSTITUI** (a versão manda): o agente vira EXATAMENTE a versão; ajustes manuais são descartados. (G4b)
Quando o operador já mexeu no agente à mão (trocou o motor, adicionou uma skill) e o dono re-pina pra vN, o
agente vira **idêntico à vN**: motor = o da vN, skillset = o da vN, extras descartados.
- **Porquê:** é o único que deixa o drift-badge **honesto** ("agora você é exatamente vN") e é previsível.
  A regra "override do operador não propaga" (ADR-0017) protege contra propagação **automática** quando se
  EDITA o molde — um re-pin é o dono **escolhendo** re-carimbar. E não guardamos proveniência de override
  (o agente só tem o estado atual), então "preservar overrides" nem seria implementável limpo.
- **Mecânica nova pequena:** um `deleteByCompanyIdAndAgentId` no `AgentSkillSelectionRepository` (hoje só
  tem `findBy…`) pra limpar as seleções antes de re-selecionar as da versão.

### D7 — Teto D6 = **o gate**. Sem cap novo; o aceite prova. (G5)
A fatia amplia a autonomia do CEO (propor molde), mas a ação é **gateada por construção** — nada persiste
sem o APPROVE do dono. Diferente do `delegation` off-book (ADR-0031 D10), que age SEM gate e por isso
precisou de `MAX 30/10`. Freios que já existem cobrem spam: dedupe por fingerprint em
[`ApprovalService.propose`](backend/src/main/java/com/orquestrador/governance/approval/ApprovalService.java:95)
+ o `MAX 30/10` do run.
- **Requisito de aceite (D6 do grill motor-nativo, não opcional):** um teste prova que **nenhum**
  `role_template_version` nasce enquanto a proposta está PENDING; só o APPROVE materializa o molde.

### D8 — Escopo do canal do CEO: **só CRIAR molde novo**. Edição = dono direto; CEO-propõe-edição = v1.1. (G6)
O CEO propõe **molde novo** pelo gate. **Editar** (nova versão) de um molde existente segue pela mão direta
do dono (caminho que já existe).
- **Porquê:** é a letra da ADR-0017 §Moldes (*"O CEO pode CRIAR molde"*). Editar via CEO puxaria
  encanamento novo — o CEO teria de referenciar um molde existente por id, e molds são **globais** (não
  vivem no estado por-company que o CEO enxerga). O drift-badge continua exercitado pelas edições do dono.
  A regra anti-círculo (achado novo → v1.1) empurra o resto.

### D9 — Sem migração; **sem ADR nova** — emenda à ADR-0017 (+ forward-refs 0018/0039). ADR-0043 fica LIVRE. (regra ADR condicional)
A decisão é "realizar a governança adiada + campo validado → rota" — **não** semântica de produto nova.
Precedente direto R9-B (*"ADR-0031 emendada, SEM ADR-0043 nova"*). Então:
- **ADR-0017** ganha uma emenda: corrige "reusa a Review" → "reusa a **Approval**" e registra propose-then-commit
  (via `CREATE_ROLE_TEMPLATE`) + re-pin-substitui + drift-derivado como a realização da governança adiada.
- **ADR-0018** e **ADR-0039** ganham forward-ref curto (o gate ganhou a 5ª ação; o bloco `proposta` ganhou
  a variante de molde). O enum FECHADO `ApprovalActionType` alarga DELIBERADAMENTE (como na ADR-0037).
- **Zero migração** (enum STRING; re-pin e drift não criam coluna). **`docs/adr/0043` permanece livre**
  (buraco 0041 deliberado intocado; não renumerar).

### D10 — Diff no card: **derivado na tela**, mínimo. (parte de "propose-then-commit · diff" da ADR-0017)
O card de aprovação da proposta de molde mostra o **preset proposto** (motor + skills + role). Como D8 é
**criar-novo** (não há versão "de"), o "diff" v1 é o **preview do que vai nascer**, não um delta. Derivado
do `target_detail` da Approval, zero backend novo.

---

## §2 Mapa de mecanismo (arquivos a tocar)

**Backend — propor (D1/D2/D3/D8):**
- `governance/approval/ApprovalActionType.java` — `+ CREATE_ROLE_TEMPLATE`.
- `conversation/TurnOutputSchema.java` — nova variante `proposta`(CREATE_ROLE_TEMPLATE) no `anyOf` do
  **Executor** (alvo = tipoFuncao, roleEspecifica, motor-trio, skillKeys[], reportsToDefault,
  changeDescription, racional). Estender o builder pra um campo **array** (skillKeys) no strict do OpenAI.
- `governance/approval/ApprovalService.java` — `case CREATE_ROLE_TEMPLATE ->` no `dispatchAction`: valida
  campos (409 acionável se malformado, como os outros), chama `roleTemplates.createTemplate(...,
  proposedBy=requestedByAgentId, approvedBy=actorId)`; `detail.put("roleTemplateId", …)`. `ConflictException`
  em role duplicada já é tratado no service.
- `org/roletemplate/RoleTemplateService.java` — `createTemplate`/`appendVersion` ganham `proposedBy`/
  `approvedBy` distintos (o comentário inline das linhas 109-113 se cumpre aqui). Controller do dono passa
  `(actorId, actorId)`.
- `ProposalScanner.scanStructured` **não muda** — `acao` vira `ApprovalActionType.valueOf` automaticamente.

**Backend — re-pin (D5/D6):**
- `skills/AgentSkillSelectionRepository.java` — `+ deleteByCompanyIdAndAgentId` (limpar seleções).
- `skills/SkillSetService.java` — `+ clearForAgent(companyId, agentId)`.
- `org/agent/AgentHireService.java` — extrair o `copySkillSet` reusável (re-pin re-aplica o mesmo).
- `org/roletemplate/RoleTemplateService.java` **ou** `org/agent/…` — novo método `repinAndReapply(companyId,
  agentId, versionId)`: resolve o preset, sobrescreve motor (`configureEngine`), `clearForAgent` +
  re-`copySkillSet`, `pinTemplateVersion`. Board-only-direto.
- `org/agent/AgentController.java` — `+ @PostMapping("/{agentId}/repin")` (assertBoardMember, body =
  versionId). Endpoint = a mão soberana.

**Backend — drift read-join (D4):**
- `org/agent/AgentController.java` — `AgentResponse` ganha `pinnedTemplateId` (o `roleTemplateId` da versão
  pinada; read-join, zero coluna).

**Front (D4/D10):**
- `frontend/src/lib/api.ts` — `AgentSummary += pinnedTemplateId`; input/fn `repinAgent(companyId, agentId,
  versionId)`.
- `frontend/src/lib/role-templates.ts` (+ `.test.ts`) — helper puro `agenteEstaEmDrift(agent, templates)`
  (espelha o `versaoCorrente` existente). Teste unitário (convenção vitest em `lib/`).
- `frontend/src/components/team-canvas.tsx` — badge de drift ao lado do selo "molde" (linha ~155) +
  ação "atualizar pra vN" chamando `repinAgent`.
- `frontend/src/components/molde-novo-dialog.tsx` — atualizar o comentário (linhas 26-27) que diz
  "propose-then-commit/drift-badge é governança futura" — agora existe (dono direto SEGUE; CEO via gate).

---

## §3 Migração / ADR — o gate do controle

- **Migração: NENHUMA.** Todo o desenho (fila Approval + enum STRING + re-pin sobre campo existente + drift
  derivado + read-join) dispensa persistência nova. **Não pedir reserva de Vxx ao controle.**
- **ADR: emenda, não nova.** ADR-0043 fica livre.

## §4 Aceite / VENDO (o que provar na descartável :18081)

1. **Propose-then-commit vivo:** CEO (Executor) emite `proposta`(CREATE_ROLE_TEMPLATE) → card PENDING na
   fila de Approval → **nenhum** `role_template_version` existe ainda (prova do teto D7) → dono APPROVE →
   molde nasce `APPROVED` com `proposedBy=CEO`/`approvedBy=dono`. REJECT → arquiva, molde não nasce.
2. **Dono direto SEGUE** (mão soberana): criar/editar molde pela tela `/moldes` continua funcionando.
3. **Drift-badge visível:** contratar do molde v1 → dono edita o molde pra v2 (direto) → o card do agente
   no canvas mostra o badge de drift.
4. **Re-pin substitui:** clicar "atualizar pra v2" → o agente vira exatamente a v2 (motor + skills), badge
   some. Ajuste manual anterior descartado.
- Piso a superar: **> 635 backend / > 280 front**.

## §5 Fora de escopo / NÃO tocar

- **Agente-como-tool** (grill próprio, próxima da fila) · Frente C · fecho documental.
- **CEO-propõe-EDIÇÃO** de molde (v1.1, D8) · re-pin que **preserva** overrides (D6 fechou: substitui).
- Redesenho da biblioteca de moldes FE além do necessário pro badge + ação.
- **NÃO commitar** os 3 untracked de contrato (`controle-paralelismo.md`, `laudo-lease-adr0005.md`,
  `handoff-auditoria-doc-codigo-2026-07-11.md`). **NÃO tocar o :8080.** Buraco 0041/0043 intocado.

## §6 Handoff de volta (contrato do ledger)
Ao fechar: branch/PR · arquivos · **Vxx = nenhuma** · piso novo · pendências · decisões novas (D1–D10)
com racional. PR **aberto sem merge** (ou nem pushado) — MERGE só com o OK do Matheus (precedentes #16–#21).
