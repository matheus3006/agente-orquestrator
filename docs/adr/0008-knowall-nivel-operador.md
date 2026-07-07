# ADR-0008: KnowAll é nível-operador — catálogo global vs biblioteca por-company

**Status:** Aceito (2026-06-30, sessão grilling)

## Contexto
O KnowAll (busca no catálogo de skills) é o diferencial central. O ADR-0007 original implicava um catálogo per-company, o que perde o conhecimento acumulado **entre** os produtos do operador — a razão de existir do KnowAll. Ao mesmo tempo, o isolamento entre companies (invariante #3) não pode ser quebrado.

## Decisão
O KnowAll opera **acima das companies** (nível do operador), sobre um modelo de **2 camadas**:
- **Camada 1 — Catálogo global** (`skill` + `skill_version`, **sem** `company_id`): identidades de Skill + savelog de versões originais. É o que o KnowAll conhece e busca. Fonte = import do PC do operador.
- **Camada 2 — Biblioteca da company** (`company_skill`/`company_skill_version`, company-scoped): conteúdo instalado/usável; **install copia a base global** (o fork de uma company nunca vaza para outra).

**Descoberta é global; instalação/conteúdo é por-company.** Instalar copia da base global, nunca do fork de outra company.

## Alternativas consideradas
- **KnowAll per-company** (o que o ADR-0007 implicava): perde o conhecimento acumulado entre produtos — anula o propósito do KnowAll.
- **Catálogo único compartilhado e mutável por todas** (sem cópia no install): quebra o isolamento — customização de A afeta B.

## Consequências
- Isolamento (invariante #3) preservado: o agente roda em container isolado e só recebe o **SkillSet materializado** (funil `PC → banco → biblioteca da company → SkillSet do agente → container/.claude/skills`). A pasta global do PC é só **fonte de import** — nenhum agente a acessa.
- Trade-off conhecimento-global × isolamento-estrito reconciliado pela fronteira **descoberta (global) / conteúdo (per-company)**.
- Exige as tabelas globais (Camada 1) além das per-company (ver ADR-0007 revisado).
