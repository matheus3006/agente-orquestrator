# ADR-0019: Decision-log — a decisão como átomo de memória

**Status:** Aceito (método fechado 2026-07-04; persistência/UI = residual a fechar na implementação de T.3)

> Registro do **método de memória do CEO ("segundo cérebro")**. Detalhe/porquê: `docs/handoff-grilling-memoria-
> ceo-segundo-cerebro.md` (D1–D12) + `docs/research/2026-07-04-{segundo-cerebro,memoria-de-agentes}.md`. O regime
> de racional (D6–D12) é o ADR-0020. A "carta" materializada é o ADR-0018.

## Contexto
O CEO precisa de memória **rastreável, achável por data, e que melhora com o progresso** — sem violar os
invariantes (carta soberana/derivada, `activity_log` imutável, company-scoping, deny-by-default) e sem inchar.
Modelo de referência real (código): o sistema `controle/` do cadillac-burger (memória em CAMADAS com **cap de
linha + graduação**). 2 deep-researches fundamentaram: consolidação episódico→semântico é a peça mais mal-servida
de memórias de agente; contexto longo **não** é memória (o gargalo é retrieval, não storage); write-validation-
gate antes de consolidar; JIT via índice→arquivo sem embeddings.

## Decisão

### D1/D2 — "segundo cérebro" = método de FINDABILITY (sem vetor)
Não é memória que cresce sozinha: é **organização para achar** — navegação **just-in-time** (índice→arquivo),
**SEM busca vetorial**. Anti-inchaço = **cap de linha + graduação + índice-não-conteúdo** (nada acumula ilimitado).

### D3 — os tiers de memória
```
activity_log (imutável, episódico)          ← ADR-0009, já existe
  → report .md por Issue  = o LEDGER          (capado/datado)
    → "ADR da Empresa" + Princípios/Playbook  (durável)
      → carta (sempre-on, soberana, DERIVADA) ← materializada como .claude/CLAUDE.md (ADR-0018)
        → cards vivos                          (derivado)
```

### D4 — a DECISÃO é a unidade atômica endereçável (não a Issue)
Issue = **contêiner de execução**; Decisão = **unidade de memória**. Schema:
```
Decisão {
  id, data, área,
  tipo:               direção | operacional | técnica,
  origem:             dono-no-gate | proposta-CEO | agente,
  o_que_mudou:        <ponteiro pro elemento do plano/artefato>,
  racional:           <texto>            # OBRIGATÓRIO (ADR-0020)
  racional_tipo:      lógico | intuição  # ADR-0020
  racional_arrancado: bool               # veio de grill?
  supersedes:         <id?>              # linhagem: a nova supersede a velha
  evidência, issue_ref?
}
```

### D5 — o gate de aprovação é FÁBRICA DE DECISÕES
Toda passagem pelo gate (a `approval` do ADR-0018 / a `review` do ADR-0012) emite **Decisões datadas**:
problemas apontados + mudanças pedidas + **riscos aceitos-sem-mudar**. O "quando" vira feature: **Log de Decisões
filtrável** (por data/área/tipo).

### A "Pergunta 1" fechada — a carta é PROJEÇÃO DETERMINÍSTICA
O consolidador da carta é **projeção determinística sobre as Decisões-de-direção aprovadas pelo dono** → **ZERO
LLM no caminho de escrita da carta** (defesa contra memory-poisoning; a carta é derivada, nunca auto-escrita pelo
agente). É o `CompanyMemoryConsolidator` do ADR-0018.

### O método de findability (o "achar" sem vetor)
1. **Endereçamento** — todo item tem ID estável, achável por eixos: tempo · unidade (Issue) · decisão (ADR-N) ·
   área/time · termo (glossário).
2. **Índice enxuto sempre-carregado** — mapeia conceito→endereço; no contexto fica o **índice, não o conteúdo**.
3. **Navegação JIT** — lê o arquivo exato quando o assunto surge; nunca injeta tudo.

## Consequências / Limites v1
- Sem vetor/pgvector, sem grafo/memória episódica (a carta é pequena, cabe no contexto; navegação JIT). D1.
- A graduação (episódico→durável) é o degrau que resolve o problema mal-servido — via ritual explícito
  (`execute-closure` do cadillac como referência; anti-padrão: *compactar Ledger = mover pra ADR, não deletar*).

## Residuais (status na implementação T.3.a, 2026-07-04)
1. **Dono do LEDGER** — **FECHADO na recomendação**: o report da Issue É a saída final do run
   concluinte do agente dela (read-model `IssueLedger` sobre o StepTrace via `RunFinalOutput` —
   nada duplicado em storage, D1); o CEO gradua para cima, nunca escreve no Ledger.
   `GET /issues/{id}/report` (capado/datado).
2. **"ADR da Empresa" vs "Princípios/Playbook"** — segue aberto (tier durável sem consumidor no
   v1; `MemoryKind` só tem CARTA — cresce quando a graduação D12 for construída).
3. **Onde o Decision-log mora** — **FECHADO**: tabela própria `decision` (V18) + Log filtrável
   `GET /decisions?area=&tipo=&from=&to=` (board-only). A carta é projeção SOBRE estas linhas
   (`CompanyMemoryConsolidator`, determinística, na MESMA tx do gate).

## Relaciona
`docs/handoff-grilling-memoria-ceo-segundo-cerebro.md` (D1–D12, schema, findability); ADR-0018 (carta/
`company_memory`/materialização + `CompanyMemoryConsolidator`); ADR-0020 (racional-por-grill — D6–D12);
ADR-0009 (`activity_log` = tier episódico imutável); ADR-0012/0018 (o gate = fábrica de Decisões);
ADR-0015 (a carta É o handoff — mesma tese de destilar); ADR-0010 (nomes + módulos profundos).
