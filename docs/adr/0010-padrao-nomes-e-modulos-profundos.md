# ADR-0010: Padrão de nomes + módulos profundos ("regra de ouro")

**Status:** Aceito (2026-07-01)

## Contexto
Sessões e assistentes diferentes tocam este código. Um padrão de qualidade que vive só na memória de
uma sessão não sobrevive à troca. Uma auditoria `/codebase-design` (2026-07-01) erradicou "código raso"
em 29 arquivos e extraiu o primeiro módulo profundo (`LineCursor`). Para valer independente de quem
edita, o padrão precisa estar versionado no repo.

## Decisão
Todo código novo (backend e frontend) segue duas regras:

1. **Nomes carregam o sentido do fluxo — zero variáveis genéricas.** Nada de `i`/`j`/`x`/`tmp`/`ctx`/`req`,
   lambdas de 1 letra, abreviações ou métodos cifrados (`nz(...)` → `zeroIfNull(...)`). O nome diz o papel
   no fluxo (`lineIndex`, `companySkill`, `runContext`, `frontmatter`, `exception`). **Exceção única:**
   quando "valor arbitrário" É o papel real (ex.: `String value` em `ApiKeyHasher.sha256Hex`).

2. **Mecanismo raso e exposto vira módulo profundo atrás de um seam pequeno.** Extrair, não só renomear:
   esconder a mecânica (índices, look-ahead, bounds-check) atrás de uma interface estreita; o que varia
   entre callers vira parâmetro do seam; testar o seam pela interface. Exemplar:
   `com.orquestrador.skills.LineCursor` (3 métodos — `hasLine`/`take`/`takeBlockWhile(predicate)`) sob o
   qual `SecretRedactor.redactAssignments` e `SkillFrontmatter.parse` foram reescritos — o
   `lineIndex = blockLineIndex - 1` ressincronizado à mão sumiu.

Vocabulário e técnica: skill `/codebase-design`.

## Alternativas consideradas
- **Deixar só na memória do assistente:** não sobrevive à troca de sessão/assistente — foi o gatilho deste ADR.
- **Só um linter:** desejável no futuro, mas "nome carrega o fluxo" e "profundidade de módulo" são
  julgamento humano; o ADR guia a revisão (humana e adversarial), o lint pega só o superficial.

## Consequências
- Toda revisão passa a ter um critério citável para nomes e profundidade de módulo.
- Diffs de qualidade (renomear + extrair seam) são esperados, não ruído.
- Custo: disciplina em cada slice — assumido como parte do "definition of done".
