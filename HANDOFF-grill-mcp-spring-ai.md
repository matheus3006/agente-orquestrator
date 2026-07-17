# HANDOFF — Grill MCP × Spring AI 2.0 — **EM ANDAMENTO**

> Janela de **grill** (sessão satélite, 2026-07-17), a partir da PAUTA do controle #3
> (`docs/controle-paralelismo.md` §"Fechamento da leva 2026-07-17 (tarde)"). Tema ÚNICO: incorporar
> (ou não) o suporte a **MCP** que o Spring AI 2.0 oferece. **NÃO implementa nada** — zero código,
> zero branch, zero ambiente vivo. Norte herdado: ADR-0043 (apêndice verbatim do dono) +
> `docs/desenho-spring-ai-verificador-auxiliar.md` (§3 Invioláveis) + Constituição de 10 regras
> (`docs/plano-implementacao-spring-ai.md`). Zero interseção com a R26-B (Uso B) em voo.

## A distinção que evita o mal-entendido viajar (o CORAÇÃO deste grill)

Há DOIS planos que a palavra "MCP" mistura, e eles têm regras opostas:

1. **"O Spring AI / o modelo local executa tools"** — **PROIBIDO, e NÃO está em revisão.** Vem do
   verbatim do dono (ADR-0043:233: "NÃO deve ... executar tools") e do princípio verificador-auxiliar
   (`desenho-spring-ai-verificador-auxiliar.md` §3: o local só confere, nunca age). Essa proibição é
   sobre **quem raciocina/age** — o modelo local (qwen/Spring AI) nunca vira um agente que chama
   ferramentas. Fim.

2. **"Infraestrutura MCP para os ENGINES"** — **JÁ EXISTE HOJE, provada com motor real.** O produto
   roda um servidor MCP **próprio, escrito à mão** (F-AT2/PR #24 + F-C3/PR #28, ADR-0003 emendada) que
   dá aos motores reais (claude/codex) 4 ferramentas de busca. Ter MCP não é novidade nem está proibido
   — é a espinha do KnowAll e da memória do CEO.

A proibição (1) **não** proíbe (2). São eixos diferentes: (1) é sobre o modelo local agir; (2) é sobre
os engines terem ferramentas de busca. A pergunta do dono ("como incorporar o MCP que o Spring AI
permite") mira, muito provavelmente, o plano (2) — o que o Spring AI oferece **como infraestrutura**.

## Estado factual (conferido na main `fef6073` — não re-descobrir)

### O MCP que o produto JÁ TEM (hand-rolled)
- **4 tools**: `knowall_search` (livre) + `knowall_fetch_body` (SÓ executor, **cap 3 corpos/run**,
  `KnowAllMcpBridge.java:37`) · `memory_search` (metadados, livre) + `memory_fetch` (corpo de Decisão
  por `#N`, teto próprio 3/run, `:47`, recusa não-fatal). Enforcement dos tetos é de PRIMEIRA CLASSE
  no backend, não no script (javadoc da classe).
- **Mecânica**: script node materializado no workspace do run (stdio MCP server) → proxy HTTP loopback
  pro backend (`mcpAddress.baseUrl()` + token efêmero por run + profile CEO/EXECUTOR)
  (`AdapterRunExecutor:269-289`, `KnowAllMcpMaterializer`, `KnowAllMcpBridgeController` em
  `/internal/mcp/...`).
- **Fiação nos 2 motores**: claude via `--mcp-config <arquivo>` + `--strict-mcp-config` (isola o run
  a enxergar SÓ este servidor, `ClaudeArgv:71-79`); codex via pares `-c mcp_servers.<name>.command/.args`
  (`CodexArgv:101-102`).
- **Limite conhecido**: gate `(pontaCeo || pontaExecutor) && target.type()==LOCAL && baseUrl != null`
  (`AdapterRunExecutor:273-274`) ⇒ **run em DOCKER fica SEM MCP** (segue normal, caminho pré-F-AT2).
  Gap REGISTRADO da Frente E (ADR-0043:142-144); conversa com candidata v1.1 #12 (paridade Docker) e
  roadmap de ajustes #15 (DOCKER sem produtor real).

### O que o Spring AI 2.0 OFERECE (pesquisa §1.8, verificado)
- MCP Java SDK **1.0.0 (RC1+)**; transporte default **SSE**; starters de **client**
  (`spring-ai-starter-mcp-client`/`-webflux`) **e** de **SERVER** (STDIO, WebMVC, WebFlux ×
  SSE/Streamable-HTTP/Stateless).
- Modelo declarativo no server: `@McpTool`, `@McpResource`, `@McpPrompt`, `@McpComplete`.
- ⚠️ Tools locais `@Tool` e tools MCP remotas compartilham a MESMA interface `ToolCallback` — o
  `ToolCallingAdvisor` NÃO distingue origem em tempo de chamada.
- ⚠️ Página oficial de segurança MCP marcada **"(WIP)"**. Tool-calling do Spring AI **sem** gate de
  aprovação nativo (§1.6).

## Direções em pauta (hipóteses — a decisão é do dono; racional PRIMEIRO)

- **D1** — Spring AI MCP **server** substituindo o bridge hand-rolled (as 4 tools viram `@McpTool`).
- **D2** — transporte **HTTP/SSE** para fechar "MCP dentro do Docker" (o gap concreto da Frente E).
- **D3** — MCP **client no modelo local** (dar tools ao qwen) — recomendação: manter **PROIBIDO**.
- **D4** — MCP **client nos ENGINES para servidores EXTERNOS** (conectores de terceiros) — hoje isolado
  de propósito por `--strict-mcp-config`.

## Levantamento de oportunidades (brainstorm + human-architect, 2026-07-17)

> O dono pediu o LEVANTAMENTO ("me diga o que ele pode resolver") em vez de dar um exemplo. Padrão
> nomeado no grill: partir da tecnologia para o problema (o brainstorm chama "solutioning before
> framing"; o human-architect chama "jumping to solutions"). Reancorado em PROBLEMAS reais com
> evidência do projeto. Cada oportunidade traça de volta a uma evidência, não à imaginação.

| # | Problema real (job-to-be-done) | O que o MCP destravaria | Evidência | Veredito honesto |
|---|---|---|---|---|
| **OP-1** | Executor em **Docker é cego**: não acha skill/Decisão como um LOCAL | transporte **SSE/HTTP** alcança o container (stdio+loopback de hoje não) | gate `AdapterRunExecutor:273-274`; gap ADR-0043:142-144; R25-A achou 6 DOCKER "produtor desviado" | **REAL, mas BLOQUEADA por pré-requisito** — só vale quando o Docker tiver produtor real (roadmap #15). Infra sem produtor = ponte para cidade que não existe |
| **OP-2** | Manter **menos código próprio** de protocolo | as 4 tools viram `@McpTool` num server Spring AI | bridge = node script + materializer + controller + argv 2 motores | **Troca lateral (refactor), não valor** — o agente não observa diferença (deletion test nulo); e teria de replicar token efêmero, profiles, tetos 1ª classe, `--strict-mcp-config`, recusa não-fatal, 1 a 1. "Betraying, not optimizing" a menos que a OP-1 force o transporte |
| **OP-3** | Agentes usarem **ferramentas de TERCEIROS** (GitHub, banco, docs) | MCP **client** apontando a servidores externos, allowlist por papel | hoje `--strict-mcp-config` ISOLA de propósito (decisão de laudo) | **REAL de capacidade, mas valor×risco alto** — superfície de prompt-injection nova via conteúdo de tool de 3º; mata o isolamento atual; exige política própria. Não entra de carona |
| **OP-4** | Dar tools ao **modelo local** (qwen) | MCP client no Spring AI | verbatim "não deve executar tools"; princípio verificador-auxiliar §3 | **LINHA VERMELHA** — não é oportunidade, é o que o norte proíbe. No mapa só para fechá-lo |
| **OP-5** | **Dirigir/consultar o orquestrador de FORA** por um agente (ex.: "liste issues", "estado do run X") | Spring AI MCP **server** expõe o orquestrador como servidor MCP externo | starters MCP server WebMVC/WebFlux existem | **Especulativa** — expansão de produto, auth nova, provável FORA do norte da Frente E (inteligência de contexto). Semente, não candidata |
| **OP-6** | Dar **mais tools de busca** aos engines (roadmap, issues relacionadas) | mais tools no bridge | — | **Ortogonal ao Spring AI** — evolução do bridge PRÓPRIO, não precisa de Spring AI. Registrar |

**Insight de arquiteto (o 2º nível da distinção-coração):** o **problema** (Docker cego) é separável da
**ferramenta** (Spring AI MCP). O bridge PRÓPRIO também pode ganhar transporte HTTP/SSE — logo "resolver
o Docker" **não exige** adotar o Spring AI MCP. Não confundir a dor com a tecnologia que passou por perto.

**Provocação (assumption testing):** a suposição do dono é "MCP traz vantagem". Testada: só OP-1 e OP-3 são
vantagem de CAPACIDADE, ambas condicionadas (pré-requisito / política); OP-2 é refactor, OP-4 é proibida,
OP-5/OP-6 são semente/ortogonal. A vantagem é REAL porém **menor e não-exige-Spring-AI** do que a certeza sugere.

## Aprofundamento OP-1 (Docker cego) — achados na main `fef6073`

- **Rodar em Docker JÁ funciona**: `execution/adapter/EngineContainer.java:27-46` (imagem pinada +
  só o volume do plano), ADR-0016, VENDO (roadmap:89). Alvo é config do agente (`Agent.java:42`,
  default `LOCAL`). A peça "executar em container" existe e é provada — cego é só o canal de busca.
- **Por que o MCP não alcança o container**: transporte de hoje = stdio + **loopback HTTP** para
  `mcpAddress.baseUrl()` (localhost). Dentro do container, `localhost` ≠ host → o callback não
  alcança o backend. Gate `AdapterRunExecutor:273-274` liga MCP só em `LOCAL`.
- **OP-1 já é ficha do projeto** = **roadmap de ajustes #1** (roadmap:267): "(ii)-docker do KnowAll
  body inline + fatia de isolamento do **home-volume** (D7) — sequenciadas JUNTAS (o canal
  MCP-no-container é a **MESMA OBRA**)"; com **nota-GATE na ADR-0016: bloqueante p/ tenant externo**.
  ⇒ MCP-no-Docker NÃO é obra solta: acopla ao isolamento do home-volume e à prontidão de Docker
  para tenant externo.
- **Gaps IRMÃOS de paridade Docker** (candidata v1.1 #12, roadmap:278): a saída estruturada da F-N3
  (schema-arquivo) e o side-channel da F-N5 TAMBÉM caem em fail-soft sob Docker. O MCP não é o único
  cego no container.
- **Estado do produtor**: R25-A (Bateria 1) achou 6 agentes DOCKER "produtor desviado" (sem rastro de
  gate); roadmap #15 = "DOCKER sem produtor real". Sinal de que não há fluxo de produto legítimo
  rodando executor em Docker hoje.
- **Separável da ferramenta (2º nível reforçado)**: dar ao container uma rota de rede até o backend +
  um endereço alcançável resolve o transporte **no bridge PRÓPRIO** também. Spring AI MCP **não é
  requisito** para matar a cegueira.

**Bifurcação de escopo que decide o tamanho da obra (e se o Spring AI entra):**
- **(A) agentes próprios/confiáveis, só paridade LOCAL↔Docker** → obra menor (rota container→backend);
  bridge próprio resolve; Spring AI dispensável.
- **(B) rodar tenant/código EXTERNO não-confiável isolado** → obra de segurança grande (isolamento
  home-volume + gate ADR-0016; a rota de rede vira superfície de ataque); o transporte MCP é a menor
  parte, e o Spring AI segue sendo detalhe perto do desenho de confiança.

## Decisões do dono (registradas conforme fecham)

- **D-MCP-1 (2026-07-17): a dor a resolver é a OP-1 — executor em Docker cego.** O dono: "também vejo
  problema exatamente onde você documentou o OP-1 do Docker, podemos resolver."
- **D-MCP-2 (2026-07-17): o caso de uso do Docker é isolamento forte dos agentes** ("é exatamente o
  caso, e também para isolar os agentes ao máximo"). Tende ao mundo (B) — a jaula existe pela
  segurança/contenção, não só paridade. Ativa a "obra grande": isolamento home-volume + gate ADR-0016.

### Tensão central descoberta (D-MCP-3, EM ABERTO — próxima pergunta)
**"Isolar ao máximo" e "dar busca via MCP" são forças OPOSTAS.** O MCP exige uma rota de rede
container→backend — cada ferramenta dada é um furo na jaula. Não há como ter os dois no talo; o
desenho escolhe o equilíbrio:
- **(i) Furo mínimo controlado** — jaula selada, menos UMA fresta autenticada (token efêmero por-run),
  read-only, que expõe SÓ as 4 tools (não o backend inteiro), com os tetos server-side de 1ª classe
  (cap 3, recusa) que já existem. Busca dinâmica preservada; superfície mínima e auditável.
- **(ii) Jaula selada** — zero rota viva; o agente em Docker vive só do que o núcleo pré-materializou
  (carta/playbook/skills do set). Isolamento absoluto; perde F-AT2/F-C3 (busca sob demanda) no Docker.

_Recomendação = (i): as defesas que estreitam a fresta já existem e foram feitas p/ agente
não-confiável × backend (token efêmero, read-only, cap 1ª classe, `--strict-mcp-config`). O trabalho
NOVO é garantir que a fresta exponha só o endpoint MCP (não o backend inteiro) — a obra que o roadmap
de ajustes #1 já fichou (isolamento home-volume + gate ADR-0016)._

_Só DEPOIS de (i) vs (ii): o formato do furo (bridge próprio evoluído × Spring AI MCP server = o D1)._

## Pendências para o controle

_(a preencher — fatias candidatas NÃO nascem aqui; o controle emite depois)_
