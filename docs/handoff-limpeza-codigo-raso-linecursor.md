# Handoff — Limpeza de "código raso" (nomes) + módulo profundo `LineCursor`

> Sessão de **auditoria + refactor de qualidade**, 2026-07-01. Disparada por `/codebase-design`.
> **Nenhuma feature nova, nenhuma migration, nenhuma assinatura pública ou schema mudou.** Só (1)
> renomeação de variáveis/params/lambdas genéricos em todo o código e (2) extração de UM módulo
> profundo interno ao pacote `skills`. Suíte: **47/47 → 52/52 green** (+5 do `LineCursorTest`).
> Este doc existe para a sessão original saber que os *internos* de vários arquivos dela mudaram de
> nome (comportamento idêntico) e que agora há uma **regra de ouro** valendo para todo código futuro.

---

## A) O que foi feito (2 frentes)

### Frente 1 — Erradicação de nomes genéricos ("código raso")
Levantamento por `grep` sobre os **97 arquivos main** + 3 do frontend. Todo nome mecânico/genérico
virou um nome que carrega o sentido do fluxo. Aplicado em **29 arquivos** (27 backend + 2 frontend):

- **Contadores/índices:** `i`/`j`/`n` → `lineIndex`/`blockLineIndex`/`valueLineIndex`/`column`;
  `s`→`line`, `raw`→`rawLine`, `next`→`continuationLine`.
- **Lambdas de 1 letra:** `r`→`run`, `cs`→`companySkill`, `fe`→`fieldError`, `(k,v)`→`(envName,envValue)`,
  `w`→`wakeup`, `a`→`candidate`, `s`→`selection`, `v`→`version`, `p`→`path`; frontend `c`→`company`, `e`→`error`.
- **Abreviações:** `fm`→`frontmatter`, `gu`→`globalUpsert`, `md`→`skillFile`, `pb`→`dockerProcess`/`localProcess`,
  `buf`→`secretBytes`, `sel`→`selection`; frontend `res`→`response`.
- **Convencionais (regra ao pé da letra):** todo `catch (… e/ex)` → `exception` (o do interceptor →
  `handlerException`); `ctx` → `runContext`/`context`/`adapterContext`; `@RequestBody … req` → `request`
  (9 controllers). Método cifrado `nz(...)` → `zeroIfNull(...)`.
- **Exceção deliberada mantida:** `String value` em utilitário genérico (`ApiKeyHasher.sha256Hex`,
  `zeroIfNull`) — ali `value` É o papel real.

Arquivos backend tocados: `SecretRedactor`, `SkillFrontmatter`, `SkillIndexer`, `SkillService`,
`SkillSetService`, `SkillMaterializer`, `AgentSkillController`, `HeartbeatRunStore`, `HeartbeatDispatcher`,
`WakeupController`, `RunExecutor`, `AdapterRunExecutor`, `AgentAdapter`, `ProcessAgentAdapter`,
`DockerExecutionTarget`, `LocalExecutionTarget`, `ExecutionTargets`, `WorkspaceResolver`,
`AgentApiKeyService`, `ApiKeyHasher`, `ActivityLogService`, `ApiExceptionHandler`, `CompanyScopeInterceptor`,
`CompanyController`, `AgentController`, `IssueController`, `BudgetController`. Frontend: `app/page.tsx`, `lib/api.ts`.

### Frente 2 — Módulo profundo `LineCursor` (deep-module, seam)
Os parsers `SecretRedactor` e `SkillFrontmatter` tinham uma **máquina de estados de cursor exposta** —
dois índices ressincronizados à mão (`lineIndex = blockLineIndex - 1`). Extraído para um **módulo profundo**:

- **NOVO** `com.orquestrador.skills.LineCursor` (package-private): interface de **3 métodos** —
  `boolean hasLine()`, `String take()`, `List<String> takeBlockWhile(Predicate<String>)`. Esconde split,
  bounds-check, look-ahead e **uma única posição interna** (por isso o `= j - 1` sumiu — tomar a linha e
  consumir o bloco avançam o MESMO cursor). Somente-leitura.
- **REESCRITOS** sobre o cursor: `SecretRedactor.redactAssignments` (agora monta a saída, não muta array
  in-place) e `SkillFrontmatter.parse` (extraí `isContinuationLine` + `joinContinuation`). O que *varia*
  entre os dois callers virou o **predicado** passado ao seam.
- **NOVO TESTE** `LineCursorTest` (5 testes, direto na interface do seam).

## B) Verificação
- `mvn compile` + `mvn test-compile`: exit 0. `tsc --noEmit` (frontend): exit 0.
- **Suíte completa: 52 testes, 0 falhas, 0 erros, 0 skipped.** `SecretRedactorTest` (4) e `SkillIndexerTest`
  (8) passam **sem alteração** → comportamento dos parsers preservado byte-a-byte.

## C) O que a sessão original precisa saber
1. **Seus arquivos mudaram de nome por dentro, não de comportamento.** Se você tem o conteúdo antigo em
   contexto, os nomes de variáveis locais/params estão desatualizados — mas **nenhuma assinatura pública,
   schema, migration ou contrato HTTP mudou**. `SecretRedactor.redact(...)` e `SkillFrontmatter.parse(...)`
   têm a mesma assinatura e mesmo resultado.
2. **Contagem de testes: 47 → 52.** O delta é só o `LineCursorTest`. Se seu último marco dizia 47/47, o novo
   piso é 52/52.
3. **Reuse o `LineCursor`.** Se for escrever mais parsing linha-a-linha (ex.: skill multi-arquivo na fatia
   futura, ou o `SkillSearch` da Fatia 7), use o seam existente em vez de reintroduzir índices.
4. **Fatia 7 segue de onde parou** — este trabalho não mexeu no roadmap. Próximo continua sendo
   `issue_skill_attachment` + `SkillSearch` + install-on-attach + Approval + KnowAll REST (ver
   [[agente-orquestrator-stack]] e `handoff-grilling-skills-knowall.md`).

## D) Regra de ouro agora vigente (aplicar em TODO código novo)
Salva na memória do assistente (`regra-ouro-nomes-e-modulos-profundos`), aplicada por padrão:
1. **Nomes carregam o sentido do fluxo — zero variáveis genéricas** (nada de `i`/`x`/`tmp`/`ctx`/`req`,
   lambdas de 1 letra, abreviações cifradas, métodos cifrados). Exceção só quando "valor arbitrário" É o papel.
2. **Mecanismo raso e exposto vira módulo profundo atrás de um seam pequeno** — extrair, não só renomear;
   o que varia entre callers vira parâmetro do seam; testar o seam pela interface. Vocabulário: `/codebase-design`.

**Follow-up opcional (não feito aqui):** a regra de ouro vive só na memória do assistente, não no repo.
Se quiser que ela seja versionada, considere registrá-la em `CONTEXT.md` ou num ADR leve (ex.: ADR-00xx
"Padrão de nomes + módulos profundos"), para valer independente de qual sessão/assistente toca o código.
