# ADR-0020: Racional arrancado por grill no gate

**Status:** Aceito (2026-07-04; triviabilidade = residual a fechar na implementação de T.3)

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
- **Proporcionalidade / triviabilidade do grill** — rec.: reusar o escape **"Auto-aprovado por triviabilidade"**
  do `controle/` do cadillac (não grelhar rename/typo/ajuste trivial).

## Relaciona
`docs/handoff-grilling-memoria-ceo-segundo-cerebro.md` (D6–D12); ADR-0019 (a Decisão-átomo que carrega o racional);
ADR-0018 (a `approval` ganha os campos; o gate arranca); ADR-0012 (o gate pós-hoc também vira fábrica de Decisões);
ADR-0010 (nomes + módulos profundos — o probe/graduação são mecanismos, não flags soltas).
