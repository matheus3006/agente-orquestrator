# Handoff — Grill: Aprovação em Lote (R3-B)

Sessão de GRILL — zero código, zero commit. Produto: este handoff + kickoff de execução.

Repo: `agente-orquestrator/` (Java 21/Spring Boot 3.5 + Next.js). main = `ebc399d`, piso 488
backend / 248 front no início desta faixa.

## Gatilho

Pedido do DONO ao vivo (2026-07-10, 20:22–23Z, durante o VENDO da Fatia 2), palavras
VERBATIM: **"deveria existir uma fila/tela de contratações pendentes, em lote, com `aprovar
todos` e `recusar todos`"**.

Dor real: a cascata da cafeteria (`teams-demo`) gerou **27 approvals `HIRE_AGENT` PENDING**
de uma vez (Planejador propôs 27 agentes, abaixo do teto de 30, todos com motor "GPT-5.4" —
achado que já motivou o override A4 na ADR-0031). A cascata parada de Demo Fixtures geraria
+7. Hoje o gate é aprovação um-a-um, via `ApprovalThreadCard` embutido na conversa.

## Levantamento de código (pré-grill)

- `Approval` tem 3 `ApprovalActionType`: `HIRE_AGENT`, `CREATE_ISSUE`, `WAKEUP_AGENT` —
  `ApprovalActionType.java:8-12`. A fila `GET /approvals?status=PENDING` mistura os 3.
- `ApprovalService.decide` é `@Transactional`, dispatch **síncrono na mesma transação**
  (`ApprovalService.java:40-42,118-181`): aprovar `HIRE_AGENT` já cria o agente de verdade
  ali (`AgentHireService.hire`), grava `activity_log` + `Decision`, e se
  `DecisionTipo.DIRECAO` roda `CompanyMemoryConsolidator.refresh` — também síncrono.
- Override A4 (motor no approve) NÃO é rota separada — é campo extra (`adapterType`,
  `model`, `effort`) no mesmo `POST .../decision`, só relevante para `HIRE_AGENT`
  (`ApprovalController.java:57-77`, `ApprovalService.java:194-204`).
- Zero endpoint de lote hoje. Zero tela dedicada — approvals só existem embutidos na
  conversa (`ApprovalThreadCard`, `ceo-conversa.tsx:174-175,237`). `/reviews/page.tsx` é
  outra entidade (`Review`, ADR-0012), mas tem um padrão estrutural bom (tabs de status +
  lista + `DecisionDialog`) para clonar.
- Guard: `CompanyAccessGuard.assertBoardMember` (só humano, nunca agente) —
  `ApprovalController.java:45,55`.
- Auditoria: `activity_log` é append-only via trigger Postgres (`V8__activity_log_detail.sql:8-16`),
  grava um evento por decisão individual. `Decision` (racional/memória) é separada, ancorada
  por `approval.getId()`, alimenta `CompanyMemoryConsolidator` quando `DecisionTipo.DIRECAO`.
- **Achado-chave**: `Approval.decisionReason` ("feedback livre do board") está **morto
  ponta a ponta hoje** — existe no schema/entidade/DTO, mas o frontend nunca envia e o
  backend nunca lê de volta para nada funcional (nem consolidação de carta, nem contexto do
  CEO). Só é espelhado cru na resposta da API.
- Precedente de design para "motivo volta ao agente": `CorrecaoDeEntregaRouter` (Caso B,
  ADR-0034) — motivo do dono **verbatim**, vira task corretiva ligada por `corrige_issue_id`,
  sem paráfrase de LLM. Não é o mecanismo usado aqui (ver D6), mas é a referência mais próxima
  que já existe no repo.
- Próxima migração livre: **V27**.

## Decisões (Dn)

**D1 — Superfície.** Tela nova dedicada, `/[companySlug]/approvals`, clonando o padrão
estrutural de `/reviews/page.tsx` (tabs de status + lista + `DecisionDialog`-like).
Racional: o pedido verbatim foi "tela", e approvals atravessam N conversas — uma fila de
verdade agrega por company, não por thread.

**D2 — Escopo do lote.** Lote funciona para qualquer `ApprovalActionType` (reaproveita
`dispatchAction` já existente), mas a UI **exige filtro por tipo ativo** antes de habilitar
"aprovar todos"/"recusar todos" — evita misturar `HIRE_AGENT` + `CREATE_ISSUE` +
`WAKEUP_AGENT` num clique só. Não trava o mecanismo de backend só em `HIRE_AGENT`.

**D3 — Semântica.** Checkboxes por item + "selecionar tudo" no topo + botões "Aprovar
selecionados"/"Recusar selecionados". Não são botões secos sem seleção — cobre o caso raiz
(clicar selecionar-tudo = "aprovar todos") e o caso intermediário (aprovar 25 de 27, vetar
2), mais realista dado o viés de motor já observado nos 27.

**D4 — Efeito da aprovação em massa / throttle.** Sem teto de execução nesta fatia — o lote
roda sequencial, reaproveitando `decide()` item a item, como está. **Plano B condicional**
(não implementar agora, só medir): se o `/verify` mostrar lentidão real de
`CompanyMemoryConsolidator.refresh` rodando N vezes em série, a correção cirúrgica é debounce
— chamar `consolidator.refresh` 1x no final do lote em vez de 1x por decisão. Decisão de
execução (medir e ajustar), não decisão de grill.

**D5 — Recusa em lote precisa voltar ao CEO.** Diverge do plano original ("recusa = terminal,
sem retorno"). Racional do Matheus: *"o CEO precisa saber por que esses agentes foram
recusados, pois isso pode reforçar a necessidade de um aprofundamento no grill ou até
evidenciar uma falha no racional do CEO, se transformando em algo que precisamos melhorar e
consolidar"*. Isso é escopo cheio, não higiene de campo morto.

**D6 — Mecanismo do kickback.** Via **kickback direto na conversa** (não via consolidação
lenta de carta) — reaproveita `PersonaBatonRouter`/`PersonaContinuationPoster`, hoje usado só
para o gatilho `needs-grilling`, com um SEGUNDO gatilho: recusa de approval. Posta um turno na
`conversation_id` de origem endereçado ao agente que propôs (Planejador/Questionador),
"proposta X recusada, motivo: Y" — o CEO lê no próximo turno da mesma conversa. Risco
sinalizado e aceito pelo Matheus: ruído de N kickbacks simultâneos em lote — mitigado por D7.

**D7 — Agregação do kickback.** Um kickback **agregado por `conversation_id`** — todas as
recusas de uma mesma conversa, dentro do mesmo lote, viram UM turno síntese (lista cada
proposta recusada + motivo/observação), não N turnos sequenciais. Conversas diferentes
continuam gerando kickbacks separados.

**D8 — Captura do motivo.** Híbrido: **motivo único compartilhado** (padrão do lote, campo
obrigatório) + **campo opcional de observação por item** para quem quiser detalhar um agente
específico. No kickback agregado (D7), a observação por item aparece distinta dentro do
turno síntese; cai no motivo comum quando ausente.

**D9 — Concorrência/idempotência.** Sistema é **single-user por design** (Matheus: "a ideia
desse sistema é de que EU owner possa administrar uma empresa... não multi users") — não há
cenário real de dois humanos colidindo. Implementação enxuta: lote processa sequencial, item
que não está mais PENDING (ou barrado pelo grill-probe, ver D14) é pulado sem quebrar o lote
inteiro; a resposta da API carrega a contagem naturalmente (efeito colateral do loop), **sem**
componente de UI dedicado a resumo parcial — engenharia desnecessária dado o contexto
single-user.

**D10 — API.** Endpoint de lote **novo no backend**:
`POST /api/companies/{companyId}/approvals/batch-decision` (nome exato a confirmar na
execução), recebendo lista de `approvalId` + decisão + motivo comum + observações por item.
Backend processa e agrega — necessário para D7 (agregação de kickback por conversa só é
natural dentro da mesma operação) e D9 (contagem sai do loop de graça). Guard reaproveitado:
`assertBoardMember` (sem mudança).

**D11 — Auditoria.** Mantém granularidade por item — 27 decisões em lote geram 27 entradas
de `activity_log` + 27 `Decision`, como se fossem 27 decisões individuais em sequência. Sem
`batch_id` novo — especulação sem caso de uso concreto hoje.

**D12 — Override de motor (A4) no lote.** Lote **não permite** override de motor — sempre
usa o motor sugerido pela proposta original, item por item. Motor errado em `HIRE_AGENT` tem
consequência cara (agente criado de verdade, síncrono). Caminho de escape: decidir aquele
item individualmente na tela antiga (`ApprovalThreadCard`), onde A4 já funciona sem mudança.

**D13 — Registro arquitetural.** **ADR-0035 nova**, dedicada (fila de lote + mecanismo de
feedback de recusa) — não emenda da ADR-0031, porque o kickback é um NOVO CONSUMIDOR do
`PersonaBatonRouter` (segundo lugar de onde é chamado), não uma mudança na divisão de
personas em si. Precedente no repo: ADR-0029 divergiu da 0028 sem emendar. **Adicionar
menção cruzada na ADR-0031** apontando para a ADR-0035 (pedido explícito do Matheus).
Migração: `V27` (confirmada livre; execução confirma se schema novo é necessário — é
possível que nenhuma coluna nova seja preciso, dado que reaproveitamos `decisionReason` e
lançamos observações por item de outra forma a definir na execução).

**D14 — Item barrado pelo grill-probe no meio do lote.** Tratado igual a D9: se
`racionalPolicy.grillProbe` barrar um item (racional insuficiente), ele entra na mesma
contagem de "não processados", o lote segue pros outros itens sem interromper. O item
continua PENDING, resolvível individualmente depois.

**D15 — Kickback também na recusa individual.** O mecanismo (D5-D8) não fica restrito à tela
de lote — a `ApprovalThreadCard` (recusa individual, já em produção) também ganha um campo de
motivo no reject, disparando o mesmo kickback. Racional: o pedido do Matheus foi sobre
QUALQUER recusa informar o CEO, não só recusas em lote; o backend já vai ter o mecanismo
disponível via D10, custo incremental no front é baixo (um `<textarea>` a mais no form que já
existe).

## Decisão de ambiente (não é Dn, mas registrada)

Os **27 approvals da cafeteria (`teams-demo`) NÃO são massa de teste preciosa**. Palavras do
Matheus: *"Estamos em fase de desenvolvimento, nada do que está no banco hoje é relevante, se
mudar como funciona, simplesmente apagamos e começamos do zero."* — a execução pode resetar o
banco de dev livremente se a fatia exigir (migração, mudança de schema, teste local). Isso
substitui a sugestão de pé original (manter os 27 como golden test data) — **não confirmada,
revogada no grill**.

## Reservas

- **ADR-0035**: CONSUMIDA — nova ADR, não emenda. Emenda cruzada pequena na ADR-0031
  (referência, sem mudar decisões dela).
- **V27**: reservada para uso nesta fatia se schema novo for necessário (execução confirma
  exata necessidade — é possível que campos existentes bastem).

## Fora do escopo desta fatia (não decidido, não implementar)

- Override de motor (A4) em lote (D12 — fica de fora, path individual intocado).
- `batch_id` em auditoria (D11 — sem caso de uso hoje).
- Debounce de `CompanyMemoryConsolidator.refresh` (D4 — só se `/verify` medir lentidão real).
- UI de resumo parcial dedicada (D9 — desnecessária, single-user).

## O que ficou aberto

- Nome exato do endpoint de lote (`batch-decision` é proposta, não trava).
- Confirmação, na execução, se V27 precisa de coluna nova ou se os campos existentes bastam
  para capturar motivo comum + observação por item.
- Custo real de `CompanyMemoryConsolidator.refresh` em série — medir no `/verify`, decidir D4
  plano B só se necessário.
