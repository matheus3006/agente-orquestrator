# ADR-0020: Racional arrancado por grill no gate

**Status:** Aceito (2026-07-04) · **residual da triviabilidade FECHADO + D8 emendado
(2026-07-14, F-C1 — ver Revisão abaixo)**

> Registro do **regime de racional** das Decisões (ADR-0019). Detalhe/porquê: `docs/handoff-grilling-memoria-ceo-
> segundo-cerebro.md` (D6–D12). A Decisão-átomo é o ADR-0019; a `approval` que carrega o campo é o ADR-0018.

## Contexto
Uma Decisão (ADR-0019) só é **revisável e não-confabulada** se tiver racional. Mas exigir racional **de qualidade**
como bloqueio faz o dono não registrar nada (fricção mata o hábito) — e forçar lógica onde só há intuição **gera
razão falsa que envenena a memória**. Precisa de um regime que **arranca** o racional sem virar juiz de qualidade
e sem induzir confabulação. Fundamentação: o write-validation-gate das pesquisas (proveniência + contradição);
"user statement > agent inference" é a proveniência de mais alta ordem — o racional-arrancado É essa proveniência.

## Decisão (D6–D12)

- **D6 — racional OBRIGATÓRIO, arrancado por grill; regime de PRESENÇA, não qualidade.** O CEO é
  **escriba-que-insiste**, não juiz: **nunca bloqueia por qualidade**; buracos ficam **auditáveis** (marcados,
  não impedidos).
- **D7 — "porque quis" é REJEITADO** (anti-confabulação). Não se aceita racional vazio; mas também não se força
  lógica onde não há.
- **D8 — `racional_tipo: lógico | intuição`.** **Lógico BLOQUEIA** o commit até razão substantiva. **Intuição
  COMMITA na hora** (frontend visual, vídeo, imagem, criativo — onde a razão é gosto legítimo).
- **D9 — a classificação é DERIVADA do artefato/área**, não do julgamento subjetivo do CEO (menos gameável). Ex.:
  decisão sobre criativo/visual → intuição; decisão sobre arquitetura/custo/prazo → lógico.
- **D10 — o probe roda em AMBOS os tipos.** Na intuição é **maiêutico e NÃO-bloqueante**: discutir "por que me
  agrada" levanta o que o agente não viu + evidencia o que o dono não notou → **tácito vira explícito**.
- **D11 — sinal de revisabilidade:** lógico = **premissa testável** (reabre quando a premissa cai); intuição =
  **preferência** (reabre quando o gosto muda). É o gatilho de `supersedes` (ADR-0019).
- **D12 — princípios graduam de PADRÃO (N ocorrências) + promoção manual do dono** — **nunca de 1 decisão**.
  Graduam pro **Playbook** (tier durável, ADR-0019/D3); agentes aplicam JIT. **O grill é AUTO-LIQUIDANTE:** taxa
  de grill caindo = empresa mais esperta (o que já foi arrancado vira princípio e para de ser perguntado).

## Consequências
- A `approval` (ADR-0018) e a Decisão (ADR-0019) ganham **`racional` (texto, obrigatório) + `racional_tipo`
  (lógico|intuição, derivado) + `racional_arrancado` (bool)**. O gate: lógico sem razão substantiva → não commita;
  intuição → commita + registra o probe maiêutico como evidência.
- O regime é **presença auditável**: nada é bloqueado por "qualidade fraca"; o que falta fica visível pra revisão.

## Residual (a fechar na implementação de T.3)
- **Proporcionalidade / triviabilidade do grill** — ~~rec.: reusar o escape "Auto-aprovado por
  triviabilidade" do `controle/` do cadillac (não grelhar rename/typo/ajuste trivial)~~ —
  **FECHADO (2026-07-14, F-C1/C14):** a recomendação original caiu; ver Revisão abaixo.

## Revisão 2026-07-14 (F-C1 — grill da Frente C, C13/C14)

> Detalhe/porquê (alternativas rejeitadas inclusas): `docs/handoff-grilling-frente-c-memoria.md`.

- **D8 emendado — o regime de COMMIT é ÚNICO nos dois tipos (C13). O código estava certo; este
  documento estava errado.** O texto original ("Lógico BLOQUEIA o commit até razão substantiva")
  prometia uma barra que o código nunca teve — e **não deve ter**. Empírico, não filosófico:
  `LOGICO` é 100% do tráfego padrão do gate (as 6 ações derivam pra estrutura-org/backlog/
  operacao/skills — nenhuma criativa); uma barra de qualidade no LOGICO cairia sobre TODO commit
  do dono → *"fricção mata o hábito"* (D6) + **confabulação em escala** (D7: o único jeito de
  passar por uma barra que você não satisfaz honestamente é inventar uma premissa). O regime dos
  DOIS tipos é o mesmo: **presença + anti-confabulação** (vazio e "porque quis" grelham; qualidade
  NUNCA é julgada). **A diferença lógico↔intuição continua viva onde é real:** o fraseado do probe
  · a evidência maiêutica (só na intuição, D10) · o sinal de revisabilidade (D11) que o
  `racional_tipo` carrega pela vida da Decisão.
  - *Alternativa rejeitada (registrada para NÃO ser re-litigada):* um check de **ESPÉCIE** (barrar
    racional em forma de preferência no LOGICO — "me agrada" não é premissa). REJEITADO porque as
    palavras de preferência são **AMBÍGUAS**: *"prefiro Postgres a Mongo porque o modelo é
    relacional"* contém "prefiro" **e é uma premissa perfeita**. `CONFAB_FORMS` só funciona porque
    seus membros são não-respostas inequívocas; uma lista de preferência dispararia falso no loop
    quente. Um lógico "mal-tipado" é auto-revelador no texto (custo do regime único: ZERO mecânico
    — `racional_tipo` tem exatamente 2 consumidores, o fraseado do probe e a evidência maiêutica).
- **Triviabilidade FECHADA (C14) — "silêncio DEPOIS de ser perguntado", só na porta da review:**
  | ato na review | o gate faz | Decisão |
  |---|---|---|
  | **REJECT** | **bloqueia** — motivo obrigatório | ✅ nasce |
  | **APPROVE com racional** | commita | ✅ nasce — o *"risco aceito-sem-mudar"* (D5) |
  | **APPROVE em silêncio** | commita **+ pergunta NÃO-bloqueante**, nas palavras da POLÍTICA (criativo → a maiêutica D10; não-criativo → *"alguma ressalva ou risco que valha registrar?"*) | ✅ se responder · ❌ se pular — fica só o `REVIEW_APPROVED` no activity_log (**memória durável não nasce do nada**) |

  A saída estava escrita nas decisões o tempo todo: D6 — escriba-que-**INSISTE**, **nunca
  bloqueia** (as duas metades) + D10 — o probe roda em AMBOS os tipos → **perguntar sempre,
  bloquear nunca**. **Não-gameável:** para escapar você precisa não dizer nada — e quem não diz
  nada não ganha memória, que é a verdade. A chamada segue UMA (`review(decision, racional?)`);
  **a política é dona das PALAVRAS (`RacionalPolicy.reviewProbe`), a UI é dona do MOMENTO**.
  A regra "sem substância → sem Decisão" mora DENTRO da fábrica única (`GateDecisionFactory`,
  ADR-0019/C15).
  - *Rejeitados:* **forçar racional em todo approve** (`racional: "ok"` em massa — e `CONFAB_FORMS`
    não pega "ok"; o risco-aceito fica enterrado no ruído) · **"silêncio = trivial" sem perguntar**
    (o writer D5 nasceria morto na prática; o registro que morre primeiro é justamente o
    risco-aceito — problema grande vira REJECT e fica; problema pequeno aceito evapora por inércia;
    viola o D10) · **`APPROVE_COM_RESSALVA` (3º valor de enum)** (adição rasa — deleta o valor e a
    capacidade continua; entrega nudge, não comportamento) · **o escape "Auto-aprovado por
    triviabilidade"** da recomendação original (julgar trivialidade a priori é a barra de qualidade
    que o D6 proíbe — a triviabilidade REAL é revelada pelo silêncio do dono após perguntado).
- **A porta da APPROVAL não muda (C14):** ela CRIA estado (`dispatchAction` contrata/cria/instala)
  — criação merece um porquê; aceitação em silêncio, não. O volume lá já tem a resposta da casa:
  o lote com racional COMPARTILHADO (ADR-0035) — "compartilhe o racional", nunca "pule o racional".
- **Bônus (pré-fiação da F-C2):** o detector de graduação (C5) e o cap (C7) passam a nunca contar
  carimbo — o Playbook não gradua ruído.
- **Achado registrado (C13):** a maiêutica (D10) era praticamente código morto — 100% do tráfego
  LOGICO fazia o `maieuticProbe` quase nunca disparar. **O que a ressuscitou foi o C11:** a review
  é onde o dono julga ARTEFATO — e artefato é onde mora o gosto (issue criativa revisada → área
  criativa → INTUICAO → a maiêutica dispara). D10 vivo pela primeira vez.

## Relaciona
`docs/handoff-grilling-memoria-ceo-segundo-cerebro.md` (D6–D12); ADR-0019 (a Decisão-átomo que carrega o racional);
ADR-0018 (a `approval` ganha os campos; o gate arranca); ADR-0012 (o gate pós-hoc também vira fábrica de Decisões);
ADR-0010 (nomes + módulos profundos — o probe/graduação são mecanismos, não flags soltas).
