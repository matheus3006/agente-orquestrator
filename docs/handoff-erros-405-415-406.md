# Handoff — 405/415/406: verbo e mídia errados respondiam 500

> Origem: sessão satélite 2026-07-19, bug irmão do 404 de rota órfã. **FECHADA** — commit
> `ed96dca` na branch `matheus/exciting-engelbart-cdc179`, worktree
> `.claude/worktrees/exciting-engelbart-cdc179`. Suíte 835/835 e prova no motor real feitas.
> A branch está rebaseada na **main** e leva DOIS commits na mesma PR: `4344f8d` (404 de rota
> órfã) e `ed96dca` (esta fatia) — mesma raiz, mesmo arquivo.
>
> **POUSO — PR #44 MERGED** (merge `3c02a36`, 2026-07-18, pelo dono). A branch
> `matheus/exciting-engelbart-cdc179` e o worktree `.claude/worktrees/exciting-engelbart-cdc179`
> citados acima são **HISTÓRICOS** — o worktree foi podado pela R31 (2026-07-19). Os hashes
> `4344f8d`/`ed96dca` seguem válidos: vivem na main via o merge.

## Estado / North

`ApiExceptionHandler` não tratava três exceções de protocolo, então elas caíam no
`@ExceptionHandler(Exception.class)`: log ERROR com stack trace inteiro + 500
`{"error":"internal_error"}` para o que é erro de **cliente**. Mesma raiz do 404, pré-existente
(o arquivo em main não tratava nenhuma das três). Agora respondem 405/415/406 no formato
`{error, message}` da casa, com log em debug e sem stack trace.

## §RESULTADO

- **3 handlers novos** em `backend/src/main/java/com/orquestrador/common/error/ApiExceptionHandler.java`
  (+73/−0 — a correção do 404 ficou intacta, conferido por `git diff --numstat`):
  `HttpRequestMethodNotSupportedException` → 405 `method_not_allowed`;
  `HttpMediaTypeNotSupportedException` → 415 `unsupported_media_type`;
  `HttpMediaTypeNotAcceptableException` → 406 `not_acceptable`.
- **Header `Allow` no 405** — obrigatório pela RFC 9110 §15.5.6. Quem o preenchia era o handler
  default do Spring; ao interceptar a exceção nós viramos os responsáveis por ele.
  `getSupportedHttpMethods()` é anulável → vem testado antes do uso.
- **6 testes** em `backend/src/test/java/com/orquestrador/common/error/MethodNotAllowedHttpTest.java`
  (padrão do `NoRouteHttpTest`: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`).
- **Suíte 835, 0 falhas, BUILD SUCCESS** — medida duas vezes, antes e depois do rebase na main.
- **Provado no motor real** (:18081 + Postgres efêmero, receita da skill `verify`): os três com corpo
  da casa, `Allow: GET` no 405, `Content-Type: application/json` + corpo no 406, e o log fechando com
  `grep -c "Unhandled exception"` = **0** e zero stack trace.

## §A — O ACHADO DO 406 (medido, não deduzido)

O 406 **já respondia 406 antes da correção** — e isso quase passou por "não afetado". Não era.

Das 6 requisições do teste, 5 lançavam exceção, e o log pré-correção tinha exatamente
**5 `"Unhandled exception"`** — ou seja, a do 406 **também** passava pelo `handleGeneric`. Ele
montava um 500 com corpo `ApiError`; ao **escrever** esse corpo a negociação de conteúdo rodava de
novo, falhava porque o cliente pediu XML, e o Spring degradava para um **406 vazio**. O 406 era
acidente da falha de escrita, não tratamento.

Consequências de design que ficam:

- O handler do 406 fixa `Content-Type: application/json` explicitamente — é o que faz o conversor
  pular a negociação e o corpo sair. **Não é decoração**; sem isso o corpo não sai.
- O teste do 406 assere o **corpo**, não só o status. Um teste que olhasse só o 406 teria **passado
  antes da correção**, sem testar nada.

## §B — PISO DA SUÍTE (corrigir referências antigas)

Os pisos **808 (main) / 813 (pós-404)** que circulam são **anteriores aos merges recentes da main**
(organograma #43 etc.). Na main de hoje (`f13ac21`) o piso é **824**. A conta de agora:

```
835 total  =  824 (main atual)  +  5 (correção do 404)  +  6 (novos)
```

Atribuição confirmada no log da suíte: `NoRouteHttpTest` 4 + `NoRouteWithoutResourceMappingsHttpTest` 1
+ `MethodNotAllowedHttpTest` 6 = 11.

## §C — GOTCHAS (sangue)

- **`./mvnw test -Dtest=MethodNotAllowedHttpTest` falhou uma vez com _"No tests matching pattern"_ e
  nunca foi diagnosticado.** Resolveu-se sozinho — a classe roda normal na suíte cheia. Se voltar ao
  iterar em teste isolado, saiba que não foi entendido, só contornado rodando a suíte inteira.
- **Rebase em branch local não-pushada duplica commit.** Esta branch foi rebaseada em
  `fix/no-resource-found-404` (local, sem PR), o que trouxe os commits da main pra cima de uma base
  antiga e **duplicou o commit do organograma**. `git cherry -v main HEAD` mostra o duplicado com
  `-` (patch já upstream); `git rebase main` o descarta sozinho. Confira com `git cherry` antes de
  abrir PR de branch que passou por rebase.
- A skill `verify` escreve `mvn -o package`; a regra do projeto é **`./mvnw`, nunca `mvn`, nunca da
  raiz**. Use `cd backend && ./mvnw -o package -DskipTests -q`.
- 405 e 415 nascem **antes** do corpo do controller, então o `CompanyAccessGuard` não roda — probe
  não precisa de header `X-Board-User`. Já o 406 pós-controller passaria pelo guard e viria 403;
  por isso o probe de 406 usa `/actuator/health` (que declara `produces`), não rota company-scoped.

## §D — FORA DO ESCOPO / não reabrir

- Formato `{error, message}` e códigos (`method_not_allowed`, `unsupported_media_type`,
  `not_acceptable`) — seguem o padrão dos handlers vizinhos, decidido.
- Log em debug sem exceção anexada nos três — mesma decisão do 404, não reabrir.
- `NoResourceFoundException`/`NoHandlerFoundException` são do commit `4344f8d`, não desta fatia.
