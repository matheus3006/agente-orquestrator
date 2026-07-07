# Proposta — separar o CEO em 3 PERSONAS (Questionador / Planejador / Executor)

> **Status:** PROPOSTA a grilar — **FATIA 2 do v1**. Levantada por Matheus em **2026-07-07** durante o
> grill do loop de handoff. É o **1º cliente** do loop de handoff
> (`docs/handoff-grilling-loop-handoff-contexto-fechado.md`).
>
> **ESCLARECIMENTO-CHAVE:** NÃO são 3 contratações. São **3 PERSONAS de UM papel (o CEO)** — 1 agente
> trocando de chapéu, cada persona com um **skill-set FIXO** e papel claro. É a camada de
> **ORQUESTRAÇÃO**; os funcionários/times (execução) são OUTRA camada, contratada ABAIXO deles.

---

## 1. CEO Questionador — interage com o usuário
Quem grila e conversa com a gente (usuários). É o CEO conversacional de hoje.
**Skills fixas:** `cs-ceo-advisor` (alirezarezvani/claude-skills — instalar se faltar), `grill-me` +
`grill-with-docs`, `brainstorming` (obra/superpowers — instalar se faltar), `adaptive-communication`
(bencium — instalar se faltar).

## 2. CEO Planejador — ≈ Arquiteto do sistema; PRODUZ o plano
**Skills fixas:** `brainstorming`, `human-architect-mindset`, `/writing-doc-plans` (NÃO existe —
escrever), `being-ceo` (NÃO existe — escrever).
- **`/writing-doc-plans`** (escrever): cria planos a partir das perguntas do grill; organiza o levantado
  em **blocos lógicos**; detalha o que cada bloco deve conter; **VALIDA** que o que foi levantado no
  grilling está de fato coberto no plano de forma coesa.
- **`being-ceo`** (escrever): (1) analisa os funcionários que temos, cataloga por **ÁREA**; quando uma
  conversa vem do Questionador pra cá, a skill ativa e atualiza o doc `/{{path}}/funcionarios` se
  necessário. (2) sabendo os funcionários, ajuda a escrever o plano JÁ indicando o **melhor time** —
  reduz o custo do Executor.

## 3. CEO Executor — lê o plano; monta/delega
Lê o plano do Planejador; levanta o time pra compor a empresa; delega feature→time; contato direto com o
**KnowAll** pra distribuir skills aos funcionários contratados.
**Skills fixas:** `teach` (mattpocock — instalar) = saber o que cada skill faz e como usar; `delegation`
(NÃO existe — escrever).
- **`delegation`** (escrever): a partir do plano do Planejador, decidir quais **CARGOS** empresariais
  melhor executam aquele papel; busca online análoga ao `/deep-research` — **MAX 30 agentes** pra montar
  time do zero, **MAX 10** se for feature (**NUNCA** mirar no máximo — melhor resultado com menos
  agentes); objetivo: descobrir os melhores cargos.

---

## Tensões (LOYALTY aos ADRs — grilladas em 2026-07-07)
- **T1 — RESOLVIDA: TRAIR conscientemente.** O CEO Planejador = o Arquiteto + skills de CEO. Absorve o
  papel. ⟹ reescrever a **ADR-0018** ("o CEO não produz conteúdo") e reconciliar a cadeia da **ADR-0017**.
- **T2 — RESOLVIDA:** é uma **camada ACIMA dos funcionários** — garante que o plano que chega aos
  funcionários está melhor orientado. **3 CEOs = ORQUESTRAÇÃO (1 agente, 3 skill-sets); funcionários/times
  = EXECUÇÃO (N agentes contratados, abaixo).** PO/Arquiteto = personas, não contratações.
- **T3 — RESOLVIDA: o gate FICA.** deny-by-default intacto — o Executor apenas **PROPÕE** o time (não
  contrata direto), mas propõe com **base melhor e mais assertiva** (via `delegation`).

## Skills — estado (a confirmar no grill da Fatia 2)
- **JÁ temos (ou perto) no ambiente:** `adaptive-communication`, `ceo-advisor` (≈cs-ceo-advisor, fonte diferente),
  `human-architect-mindset`, `grilling` (≈grill-me), variantes de brainstorming.
- **Falta instalar** (`teach` + versões EXATAS linkadas por Matheus):
  - `cs-ceo-advisor` — https://github.com/alirezarezvani/claude-skills/blob/main/agents/c-level/cs-ceo-advisor.md
  - `brainstorming` (superpowers) — https://github.com/obra/superpowers/tree/main/skills/brainstorming
  - `adaptive-communication` (bencium) — https://github.com/bencium/bencium-marketplace/tree/main/adaptive-communication/skills/adaptive-communication
  - `teach` — https://github.com/mattpocock/skills/tree/main/skills/productivity/teach
  - `grill-me` / `grill-with-docs` — sem URL fornecida; localizar ou confirmar equivalência com o nosso `grilling`.
- **Falta escrever** (via `write-a-skill`): `writing-doc-plans`, `being-ceo`, `delegation`.

> **DISTINÇÃO a fechar no grill:** as skills FIXAS de cada persona são **skills-in-DB do PRODUTO**
> (`CompanySkillVersion` materializada via `SkillMaterializer` no `.claude/skills/<key>/SKILL.md` do
> run do CEO), **não** skills do Claude Code do desenvolvedor. "Instalar cs-ceo-advisor pro Questionador"
> = registrar como `CompanySkillVersion` de sistema e incluir no skill-set daquela persona. (As skills de
> GRILL que o dev usa — `/grilling` etc. — são outra coisa, do ambiente do dev.)

## Conexão com o loop de handoff
Os 3 CEOs passam o bastão via **bilhetes destilados**: Questionador→Planejador (destilado do grill) e
Planejador→Executor (o plano). É o **mesmo mecanismo** do loop de handoff — 1 agente deixando um bilhete de
si-pra-si — **+ troca de skill-set na virada**. Por isso o loop de handoff (Fatia 1) deixa **pronto o seam
do skill-set da continuação** (D6 do handoff); a **lógica de quando trocar de persona** é desta Fatia 2.
