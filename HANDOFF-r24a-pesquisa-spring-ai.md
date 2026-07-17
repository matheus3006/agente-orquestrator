# HANDOFF — R24-A: pesquisa de melhorias no uso do Spring AI — **FECHADA (produto entregue)**

> Faixa **R24-A** (sessão satélite, 2026-07-17). UNTRACKED na raiz (precedente
> R22-A/R22-B/R23-A). Ledger: `docs/controle-paralelismo.md`. Faixa de **PESQUISA pura** — não
> implementou nada, não tocou código/branch/checkout/ambiente vivo, não decidiu provider, não
> autorizou `ACTIVE`. Fonte canônica do norte: `scratchpad/racional-spring-ai-verbatim-dono.md`
> (verbatim do dono, 2026-07-17) — reproduzido também no apêndice da ADR-0043.

## Produto

**`docs/pesquisa-spring-ai-melhorias.md`** (untracked, ~5590 palavras) — as 6 seções pedidas:
inventário de capacidades reais do Spring AI 2.0.0 (item a item, cada claim com fonte e nível de
confiança) · mapa capacidade × permitido/proibido/condicionado contra o racional · candidatas
F-SAI2+ ranqueadas (contrato + evidência + risco cada) · desenho da coleta de métricas SHADOW ·
matriz de providers sem recomendação fechada · fluxo de validação recomendado em 1 página com
diagrama.

## Método

`/deep-research` (fan-out de 5 ângulos, 107 agentes, verificação adversarial de 3 votos) cobriu
bem só 3 dos 11 itens pedidos (advisors/structured-output/chat-memory) na primeira passada — o
orçamento de verificação (25 de 122 claims extraídas) não chegou nos outros 8 itens nem na Parte B
(matriz de providers). Fui direto no `journal.jsonl` do workflow, resgatei ~24 extrações de fonte
primária com citação verbatim que não tinham passado pelo voto adversarial, e verifiquei
diretamente (WebFetch) os pontos de maior impacto arquitetural — resolvendo 2 contradições reais
entre fontes no processo. `/claude-api` para a linha Anthropic da matriz de providers (dados
verificados ao vivo). `/codebase-design` para o vocabulário de módulos profundos nas candidatas.
`/human-architect-mindset` rodado sobre as recomendações antes de fechar — mudou a ordenação da
seção 3 (ver §Achados).

## Achados que corrigem premissas do pedido original

- **`ToolCallingAdvisor` do Spring AI 2.0 é auto-registrado por padrão na chain do `ChatClient`,
  mesmo com zero tools configuradas** — inclusive em `ChatClient` construído manualmente. "Sem
  tools" hoje depende de *ausência* de bean `ToolCallback`, não de rejeição estrutural (a flag
  `spring.ai.chat.client.tool-calling.enabled=false` precisa ser setada explicitamente para
  garantir isso). É inconsistente com o resto do próprio módulo (`ContextPolicy` e
  `ContextAssessmentValidator` já rejeitam proativamente, não dependem de ausência). Registrado
  como achado de hardening barato da F-SAI1 já fechada — **não implementado**, é achado de
  pesquisa.
- **Correção de nomenclatura**: o starter Google do Spring AI 2.0.0 se chama "Google GenAI", não
  "Vertex AI Gemini" (não existe linha "Vertex AI" na tabela oficial de chat providers); "Azure
  OpenAI" também não é linha própria nessa tabela.
- **Contradição resolvida**: MCP Java SDK exigido pelo Spring AI 2.0 é **1.0.0 (RC1+)**, não 2.0.0
  como uma fonte (post de lançamento) sugeria; transporte default é **SSE**, não Streamable HTTP.
  Confirmado por refetch direto da página de referência específica de MCP.
- **A candidata mais barata do meu rascunho inicial (harness comparativo A/B de providers) estava
  mal-escopada**: só é livre de decisão nova se ficar dentro do Ollama (multi-modelo, zero
  egress). Cruzar para um provider cloud já toca a mesma decisão que o racional reserva ao gate do
  `ACTIVE` ("decidir... envio de dados a serviços externos") — virou duas candidatas separadas
  (1a livre, 1b condicionada a pré-aprovação explícita do dono) depois da revisão
  `/human-architect-mindset`.

## Decisões novas PROPOSTAS (nenhuma fechada — para o grill do dono)

1. Priorizar candidata **1a** (harness comparativo multi-modelo dentro do Ollama) como próxima
   fatia de evidência — mais barata e mais direta para o gap real de "estabilidade por
   modelo/provider" que o gate do `ACTIVE` pede (hoje é literalmente zero dados, só 1 modelo já
   rodou).
2. Tratar o achado do `ToolCallingAdvisor` (§Achados) como emenda de baixo custo à F-SAI1, à parte
   da ordem das outras fatias.
3. **Não** tratar comparação cross-provider (candidata 1b) como fatia livre até o dono decidir se
   quer pré-aprovar um experimento limitado e nomeado antes do gate completo do `ACTIVE`.
4. Condicionar F-SAI4 (busca híbrida do KnowAll) a uma medição barata de recall@5 do `pg_trgm`
   atual antes de investir em pgvector/`EmbeddingModel` — o plano propõe a melhoria, mas não há
   evidência nesta pesquisa de que o recall atual seja de fato um problema medido.
5. Observability nativa via actuator e `validateSchema()` auto-retry ficam rebaixadas/em standby,
   com racional específico registrado no doc (pouco valor incremental a primeira, caveat do
   modelo Qwen em produção a segunda).

## Pendências / próximos passos

1. **Grill do dono** sobre o produto — decidir quais candidatas avançam e em que ordem, e a
   pergunta explícita da 1b (pré-aprovar egress limitado ou não).
2. Registrar as decisões do grill como Decisão-como-átomo (padrão já em uso) antes de qualquer
   fatia F-SAI2+ nascer.
3. `ACTIVE`, F-SAI2/3/4 propriamente ditas, métricas SHADOW reais, decisão de provider/egress,
   ADR-0044 (ou o próximo número livre) — todos **fora e não autorizados**, como já valia antes
   desta faixa.

## Contrato

- **Zero código/commit/branch tocado.** Nenhum comando de escrita fora de
  `docs/pesquisa-spring-ai-melhorias.md` e deste handoff.
- **Checkout compartilhado intocado** — toda leitura via `git show <branch>:<path>`, nunca trocou
  de branch nem tocou HEAD alheio.
- **Ambiente vivo (`:8080`/`:3000`/pg) não tocado — nem leitura foi necessária** (pesquisa pura,
  sem prova ao vivo).
- **Untracked pré-existentes preservados, não tocados**: `HANDOFF-r22a-conserto-bug1.md` ·
  `HANDOFF-r22b-plano-descoberta-bugs.md` · `HANDOFF-r23a-integracao-frente-e.md` ·
  `docs/controle-paralelismo.md` · `docs/laudo-lease-adr0005.md` ·
  `docs/handoff-auditoria-doc-codigo-2026-07-11.md` · `docs/plano-descoberta-bugs.md` ·
  `docs/plano-descoberta-bugs.materiais/`.

## Notas

- Material intermediário de pesquisa (inventário bruto de claims, rascunhos de seção) ficou no
  scratchpad da sessão (`/private/tmp/claude-501/.../scratchpad/r24a-*.md`) — fora do repositório
  de propósito, não é parte do produto e não precisa sobreviver além desta sessão.
- Memória do dono (`~/.claude/projects/.../memory/`) ainda não tem entrada para R24-A — se a
  principal quiser, é uma entrada tipo `project` curta apontando para este handoff + o doc, no
  mesmo padrão das faixas anteriores (R22-A, R22-B, R23-A etc.).
