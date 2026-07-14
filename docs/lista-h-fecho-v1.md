# Lista H — decisões do dono para o fecho do v1

> **O que é:** compilação (fecho documental parcial, 2026-07-14, faixa R16-B) das decisões que
> SÓ o Matheus toma antes/durante a declaração "v1 FECHADO". **Nada aqui foi decidido** — cada
> item traz a fonte registrada e uma recomendação com o porquê (regra da casa). Nenhum item
> bloqueia a prova final; o que bloquearia já virou fatia.
>
> Fora desta lista por construção: tudo da Frente C em voo (F-C2/F-C3) e os textos
> C14/graduação — entram no **fecho residual** pós-F-C3.

## 1. Feedback do revisor → Issue (ADR-0011)

- **A decisão:** no reject de review, o feedback do revisor fica preso em
  `review.decision_reason` — não existe endpoint de edição de Issue, então o REOPEN re-executa
  com o texto original e o agente nunca vê o motivo. Puxar uma fatia (edição de Issue ou
  injeção do feedback no re-run) × aceitar como está?
- **Fonte:** auditoria doc×código 2026-07-11 (ficha MÉDIA incerta, ADR-0011 Revisão
  2026-07-02); evidência `IssueController.java:43-99` (superfície completa, sem edit); nota de
  reconciliação aplicada na ADR-0011 (2026-07-14).
- **Recomendação (da faixa, com porquê):** **aceitar como está no v1** — o feedback é visível
  ao DONO no board (é ele quem decide o REOPEN e pode reformular pela conversa/absorção,
  ADR-0037); uma superfície de edição de Issue é obra nova sem outra demanda registrada, e a
  regra anti-círculo manda não abrir obra pós-congelamento. Vira candidata natural quando o
  reject-com-re-run doer em uso real.

## 2. Veto de redação dos textos shippados (lote único)

- **A decisão:** aprovar (ou reescrever) a REDAÇÃO dos textos que as fatias shipparam como
  proposta reversível: **(a)** aviso + trilho do caso C (`ProposalDescarteNotice` + resposta
  do balcão, ADR-0036); **(b)** avisos M6 colapsados da absorção (ADR-0037); **(c)** assunto
  de conversa de issue absorvida `"#N — <título>"` (V27/ADR-0037); **(d)** card `CREATE_TEAM`
  no gate (ADR-0037). *(Os textos C14/pergunta-de-triviabilidade e os da graduação entram no
  fecho residual quando a F-C2 landar — mesma classe, momento errado.)*
- **Fonte:** achados/pendências da rodada 4 do ledger (item 2); vetos declarados "reversíveis,
  sem pressa" nos handoffs das faixas R4-A/R4-B.
- **Recomendação:** **manter como shippado** — os textos passaram por VENDO vivo sem ruído, e
  são todos reversíveis por edição trivial (zero mecânica); vetar em lote numa sentada de
  board (ler os 4 na tela) é mais barato que discuti-los em abstrato.

## 3. Os 3 tetos de escalação da ADR-0018 (governança)

- **A decisão:** a ADR-0018 promete 3 tetos que escalam ao dono — **gasto/token** ·
  **plano com muitos riscos** · **circuit-breaker de erro repetido**. Só o primeiro existe
  (`BudgetPolicy.orchestrationLimitCents`); contador de riscos e circuit-breaker não existem
  no backend. Construir no v1 × v1.1?
- **Fonte:** auditoria doc×código 2026-07-11 (ficha grill·teams-fechado — "o mais substantivo
  dos incertos: lacuna de GOVERNANÇA real, não drift de texto"); triagem do ledger (§Insumo
  externo, item 3).
- **Default REGISTRADO na triagem: v1.1, SALVO palavra do dono** (chegou junto do congelamento
  e não quebra a prova final — regra anti-círculo). Candidata #11 do registro v1.1 no roadmap.
- **Recomendação:** **confirmar o default (v1.1)** — a exposição real hoje é baixa (M9 corta
  reincidência, budget drena, gate segura mutação estrutural); o que os 2 tetos ausentes
  adicionam é conforto de autonomia LONGA, que é exatamente o tema do v1.1.

## 4. Bump do pino claude 2.1.201 → 2.1.206

- **A decisão:** o host roda claude 2.1.206; a imagem pina 2.1.201. Bumpar o pino (rebuild da
  imagem + `EngineContainerPinTest`) agora × deixar para uma fatia de infra?
- **Fonte:** achado da rodada 6 do ledger (versões); F-N3/ADR-0039 decisão 5 (pino MANTIDO
  deliberadamente — 2 cintos no backend cobrem a gotcha do `--json-schema` <2.1.205);
  nota de reconciliação na ADR-0014 (2026-07-14). Disciplina: bump vai com racional no PR
  (defesa do pino, ADR-0014/0016).
- **Recomendação registrada (sessão de controle, rodada 7): sem urgência** — os cintos cobrem
  a única gotcha conhecida; fazer o bump numa fatia de infra própria (rebuild + smoke dos 2
  motores) quando houver um segundo motivo para mexer na imagem, nunca de carona.

## 5. Destino dos artefatos vivos de VENDO

- **A decisão:** o que fazer com os artefatos que os VENDOs vivos deixaram nas companies de
  demo: **(a)** conversa "VENDO F2 — cafeteria" + **27 approvals HIRE_AGENT PENDING**
  (teams-demo); **(b)** cascata parada da Demo Fixtures (responder adapterType/effort geraria
  +7 PENDING; estacionar é inócuo — fail-open); **(c)** artefatos da G3 em Demo Fixtures (CEO
  "Sonda Docker Codex" + carta-marcador `GIRASSOL-CINZA-58` + volume). Manter × limpar ×
  rejeitar em lote?
- **Fonte:** resíduos registrados nos fechamentos das rodadas 2–4 do ledger; sugestão
  registrada na rodada 3: os 27 PENDING serviram de massa de teste da fatia de lote
  (ADR-0035).
- **Precedente registrado: MANTER como evidência** (decisão análoga da G3 — "artefatos VIVOS
  da prova; remover se atrapalhar demos").
- **Recomendação:** **seguir o precedente** — manter; se os 27 PENDING poluírem a demo, a
  própria tela de lote (`/approvals`, ADR-0035) rejeita todos numa ação, que é em si mais uma
  prova viva.

---

*Itens que JÁ SAÍRAM desta lista (registro):* CEO-criar-molde e re-pin-sem-chamador — ambas as
metades da questão H da auditoria foram ABSORVIDAS pela R10-A (PR #22; ledger rodada 10).
