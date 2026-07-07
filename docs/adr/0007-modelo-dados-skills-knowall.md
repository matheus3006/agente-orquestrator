# ADR-0007: Modelo de dados de Skills & KnowAll

**Status:** Aceito (2026-06-30)

## Contexto
Skills são o diferencial central e a fonte de verdade fica no banco (ADR de stack). O dono roda vários produtos = companies distintas, que podem querer versões diferentes da mesma Skill. KnowAll busca no catálogo.

## Decisão
Tabelas (espelham o `company_skills` do Paperclip, report 12):
- **`skill`** — registro lógico da Skill (identidade/key).
- **`company_skill`** — Skill instalada numa Company, com `source` (local_path/catalog/github/url), `origin_hash`, `content_hash`, estado.
- **`company_skill_version`** — versões com conteúdo (`SKILL.md` + assets como blob/object-storage) e hash.
- **`agent_skill_selection`** — o SkillSet base de um Agent, apontando uma **versão pinada** (ADR-0003).
- **Skill anexada por Run** — escopo efêmero (ADR-0003), registrada no activity log.

**Import:** `SkillIndexer` lê `.claude/skills/*/SKILL.md` (frontmatter: name, description, triggers) → grava no banco (fonte de verdade), **redige segredos** (LGPD), assets grandes como blob.
**KnowAll:** `SkillSearch` por `pg_trgm` sobre o catálogo **da Company**; exposto como tool (Agent) e persona (humano). `pgvector`/RAG plugável depois.

## Alternativas consideradas
- Catálogo global sem versão — sem isolamento por company nem pin de versão.
- Só filesystem indexado — perde durabilidade/portabilidade (decisão de stack já as exige).

## Consequências
- Multi-company isolado; cada produto pina sua versão; `origin_hash` detecta drift para update controlado.
- Skills duráveis e portáveis (independem do PC local).
- Mais tabelas — custo aceito pelo valor do diferencial.

## Revisão 2026-06-30 (grilling)
Modelo de **2 camadas** (KnowAll é nível-operador — ver ADR-0008):
- **`skill` = identidade GLOBAL** (Camada 1): **sem `company_id`**, `unique(skill_key)`. Deixa de ser per-company (corrige o refinamento provisório da Fatia 5). Carrega metadados denormalizados (`name`/`description`/`triggers`) e ponteiro para a versão-base corrente, para a busca.
- **NOVA `skill_version` (savelog global):** versões da base (`content_hash` + `change_description` + `origin`); `unique(skill_id, version)` e `unique(skill_id, content_hash)`. `origin = LOCAL_PATH` no v1 (PC → catálogo global); `GITHUB`/`URL` depois.
- **`company_skill_version` ganha** `change_description` e `source_global_version_id` (de qual versão global foi copiada). **`company_skill.source = CATALOG`** no v1 (catálogo global → biblioteca da company) — `CATALOG` deixa de ser "futuro".
- **`SkillSearch` busca o catálogo GLOBAL** sobre `name`+`description`+`triggers` (metadados redigidos, **não** o body); resultado **anotado com estado de install** por-company.
- **NOVA `issue_skill_attachment`** (escopo por-Issue, versão pinada, registro imutável) — ver ADR-0003 revisado.
- **Assets não materializados no v1 → só Skill de UM arquivo (`SKILL.md`).** Multi-arquivo (`scripts/`/`references/`/`assets/`) = fatia futura e **pré-requisito do preview (diferencial #5)**.
