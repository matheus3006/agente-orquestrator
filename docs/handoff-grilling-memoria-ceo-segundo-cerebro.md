# Handoff — Grilling: Memória do CEO ("segundo cérebro")

> **Data:** 2026-07-04 · **Frente:** Teams / tópico #1 (Interface de conversa do CEO + Memória da empresa)
> **Status:** MÉTODO FECHADO · 4 residuais em aberto (ver fim)
> **Pesquisas de apoio:** [research/2026-07-04-segundo-cerebro-com-ia.md](research/2026-07-04-segundo-cerebro-com-ia.md) · [research/2026-07-04-memoria-de-agentes-de-ia.md](research/2026-07-04-memoria-de-agentes-de-ia.md)
> **Modelo de referência (código real):** `~/AlmaFactory/cadillac/cadillac-burger/controle/` + `.claude/skills/execute-closure` + hooks

---

## O que esta sessão fechou

Um **método de administrar a memória do CEO e seus subordinados** de forma que as decisões sejam **rastreáveis**, **acháveis por data**, e **melhorem conforme o progresso** — sem violar os invariantes do #1 nem a frente Teams.

Insight central: "segundo cérebro" **não** é memória que cresce sozinha; é um **método de organização para FINDABILIDADE** — navegação estruturada JIT (índice → arquivo), **sem busca vetorial**, exatamente o padrão `controle/` do cadillac (que por acaso é o estado da arte que as 2 pesquisas prescreveram).

---

## Decisões (D1–D12)

| # | Decisão |
|---|---|
| **D1** | "Segundo cérebro" = método de organização para achar informação (navegação JIT via endereçamento + índice enxuto, **sem vetor**), não memória auto-crescente. |
| **D2** | Modelo de referência = `controle/` do cadillac: memória em **camadas com CAP DE LINHA + GRADUAÇÃO**. Anti-inchaço = cap + graduação + índice-não-conteúdo. Nada acumula ilimitado. |
| **D3** | Tiers: `activity_log` imutável (episódico) → **report `.md` por Issue** (ledger capado/datado) → **"ADR da Empresa" + Princípios/Playbook** (durável) → **carta** (sempre-on, soberana, derivada) → cards vivos (derivado). |
| **D4** | A **Decisão é a unidade atômica endereçável** (não a Issue). Issue = contêiner de execução; Decisão = unidade de memória. |
| **D5** | O **gate de aprovação é uma fábrica de Decisões**: problemas apontados + mudanças pedidas + **riscos aceitos-sem-mudar** viram registros datados. |
| **D6** | **Racional obrigatório, arrancado por grill** — regime **PRESENÇA, não qualidade**. CEO = escriba-que-insiste, não juiz. Nunca bloqueia por qualidade; buracos ficam auditáveis. |
| **D7** | **"Porque quis" é rejeitado — anti-confabulação.** Forçar lógica onde só há intuição gera razão falsa que envenena a memória. |
| **D8** | `racional_tipo: lógico \| intuição`. **Lógico BLOQUEIA** commit até razão substantiva. **Intuição commita na hora** (frontend visual, vídeo, imagem, criativo). |
| **D9** | **Classificação derivada do artefato/área**, não do julgamento subjetivo do CEO (menos gameável). |
| **D10** | O **probe roda em AMBOS os tipos**; na intuição é **maiêutico e não-bloqueante** (discutir "por que me agrada" levanta o que o agente não viu + evidencia o que o dono não notou → tácito vira explícito). |
| **D11** | **Sinal de revisabilidade:** lógico = premissa testável (reabrir quando cai); intuição = preferência (reabrir quando o gosto muda). |
| **D12** | **Princípios graduam de PADRÃO (N ocorrências) + promoção manual do dono** — nunca de decisão única. Graduam pro Playbook; agentes aplicam JIT. **O grill é auto-liquidante** (taxa de grill caindo = empresa mais esperta). |

### Schema da Decisão
```
Decisão {
  id, data, área,
  tipo:            direção | operacional | técnica,
  origem:          dono-no-gate | proposta-CEO | agente,
  o_que_mudou:     <ponteiro pro elemento do plano/artefato>,
  racional:        <texto>          # OBRIGATÓRIO
  racional_tipo:   lógico | intuição
  racional_arrancado: bool          # veio de grill? marca as não-dadas-de-graça
  supersedes:      <id?>            # linhagem: decisão nova supersede a velha
  evidência, issue-ref?
}
```

---

## Como encaixa no #1 fechado (sem quebrar invariante)

- **Invariantes intactos:** carta soberana e derivada · `1-turno=1-Run` · sem-vetor · `activity_log` imutável · company-scoping · deny-by-default.
- **Reuso:** o *report por Issue* do #1 **vira o LEDGER**; a *Approval* do #1 **ganha o campo racional-arrancado**.
- **Ganhos novos:** caps · ritual de graduação · tier Princípios/Playbook · Decisão-como-átomo · **Log de Decisões filtrável** (o "quando" como feature) · racional obrigatório no gate.
- **Fecha a Pergunta 1 que ficou no gelo:** o "consolidador" da carta é **projeção determinística** sobre as Decisões-de-direção aprovadas pelo dono → **zero LLM no caminho de escrita da carta**.

---

## O método de findability (o "achar" sem vetor)

1. **Endereçamento** — todo item tem ID estável, achável por eixos: tempo · unidade (Issue) · decisão (ADR-N) · área/time · termo (glossário). (No cadillac, `ADR-0027` é um endereço citado em 6 lugares.)
2. **Índice enxuto sempre-carregado** — mapeia conceito → endereço (invariante → `ADR-N`). Fica no contexto o **índice, não o conteúdo**.
3. **Navegação JIT** — lê o arquivo exato quando o assunto surge; nunca injeta tudo (é isso que impede o "fica pesado").

Mecânica do cadillac que sustenta o método: 4 arquivos por task (`LIMITES` 80 / `PLANO` 120 / `ESTADO` 60 / `LEDGER` 150 linhas) · watchdog bloqueia edit sem controle · `execute-closure` faz a graduação (anti-padrão: *"compactar LEDGER removendo decisões → mover pra ADR, não deletar"*) · painel DERIVADO de JSON (nunca à mão) = mesmo princípio "derivado, não autorado" da carta.

---

## Residuais em aberto (próxima sessão)

1. **Dono do LEDGER no multi-agente** — recomendado: o agente da Issue escreve o seu; o CEO só cresce por graduação pra cima. Falta confirmar.
2. **"ADR da Empresa" vs "Princípios/Playbook"** — tratados como dois sabores de durável (pontual vs destilado); podem colapsar num tier só.
3. **Persistência + UI** — onde o Decision-log mora no schema Postgres + a tela "Log de Decisões filtrável" (por data/área/tipo).
4. **Proporcionalidade/triviabilidade do grill** — recomendado reusar o escape "Auto-aprovado por triviabilidade" do cadillac (não grelhar rename/typo).

---

## Fundamentação (das 2 pesquisas)

- **Consolidação episódico→semântico é a peça mais mal-servida** de qualquer memória de agente (arXiv:2603.07670) — é o degrau que o método resolve via graduação.
- **Contexto longo não é memória; o gargalo é retrieval, não storage** (arXiv:2603.07670, 2602.19320) — justifica índice+JIT sobre despejo.
- **Write Validation Gate (proveniência + contradição) antes de consolidar** (SMSR arXiv:2606.12703 — "todo write é transição de estado privilegiada, verificada antes de commitar"; TMA-NM arXiv:2606.24322 — 0% de sucesso de ataque) — é o gate humano do dono; o racional-arrancado é a proveniência de mais alta ordem ("user statement > agent inference"). O framing "Write Validation Gate" veio do SSGM (arXiv:2603.11768), **refutado como prova** na verificação (segundo-cerebro §REFUTADO) — fica só como intuição-motivadora.
- **Just-in-time retrieval via índice → arquivo, sem embeddings** (Anthropic, effective-context-engineering) + **context rot / distractors** (Chroma) — fundam o "sem vetor" e o anti-inchaço.
- **Storage → Reflection → Experience** (arXiv:2605.06716) — o Playbook (D12) é a camada Experience: heurística transferível destilada de padrão.
