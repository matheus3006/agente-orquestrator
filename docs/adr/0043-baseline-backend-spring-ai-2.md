# ADR-0043: Baseline do backend em Java 25, Spring Boot 4.1 e Spring AI 2.0

**Status:** Aceito (2026-07-16) · decisão do dono: migrar enquanto a aplicação ainda está em construção, evitando criar código novo sobre uma linha que seria substituída em seguida.

**Implementação:** F-PLAT0, F-SAI0 e F-SAI1 concluídas em 2026-07-17 na branch
`feat/backend-spring-ai-2`. `OFF` continua sendo o default; `ACTIVE` e F-SAI2+ não foram autorizados.

## Contexto

Na abertura desta decisão, o backend executava em **Java 21 + Spring Boot 3.5.0** e ainda não possuía Spring AI. A primeira análise do
controle de contexto propôs Spring AI 1.1.8, compatível com Boot 3.5.x, para minimizar mudança imediata. Essa
alternativa criaria, porém, duas transições próximas: primeiro introduzir a abstração na geração 1.1 e depois
migrar a plataforma e a integração para Spring AI 2.0.

A aplicação ainda está em construção e não há nesta decisão uma base de produção cuja estabilidade exija a
permanência em Boot 3. A versão 2.0 do Spring AI foi projetada para Spring Boot 4.0/4.1 e Spring Framework 7. O
salto de plataforma traz também mudanças reais, sobretudo a adoção de Jackson 3 e a modularização de starters
do Boot 4; portanto, não deve ser escondido dentro da primeira feature de IA.

O repositório deixa explícito esse custo: há uso de Jackson no código principal e nos testes, além de imagens
Docker baseadas em Java 21. Fazer a mudança agora reduz compatibilidade acumulada, mas ainda exige migração
sequencial e prova nos dois targets de execução e nos dois motores reais.

## Decisão

### 1. Matriz alvo pinada

O novo baseline do backend é:

- **Java 25 LTS**;
- **Spring Boot 4.1.0**, que governa Spring Framework 7 e as dependências suportadas da aplicação;
- **Jackson 3**, sem manter a ponte de Jackson 2 como estado final;
- **Spring AI 2.0.0**, importado por seu BOM oficial;
- **Maven Wrapper 3.9.16**, ponto de entrada reproduzível do projeto.

“Subir todas as versões do backend” significa elevar a **plataforma/BOM** e aceitar as versões transitivas
testadas por Spring Boot e Spring AI. Não significa fixar cada biblioteca transitiva na versão mais nova de
forma independente. Exceções ao gerenciamento dos BOMs exigem motivo, teste e registro explícitos.

### 2. Uma migração planejada, entregue em fatias verdes

A decisão é única, mas a implementação não será um commit big-bang. A ordem obrigatória é:

1. caracterizar o baseline e atualizar isoladamente Boot `3.5.0 → 3.5.15`;
2. migrar para Boot `4.1.0` ainda em Java 21, corrigindo starters, propriedades e APIs de framework;
3. concluir a migração de Jackson 2 para Jackson 3 e remover qualquer ponte temporária;
4. elevar compilação, runtime, Docker e automação para Java 25;
5. importar Spring AI `2.0.0` e criar o adapter de inteligência em `OFF`, depois `SHADOW`;
6. provar a aplicação em PostgreSQL/Flyway, LOCAL/DOCKER e nos runtimes Claude Code/Codex reais.

Cada fatia deve terminar com build e suíte verdes. Pontes oficiais de migração podem existir somente dentro de
uma fatia intermediária e devem ter remoção prevista antes de a migração ser considerada concluída.

### 3. Limite arquitetural do Spring AI

Spring AI entra como dependência **auxiliar do control plane**, atrás da interface local
`ContextIntelligence`. Ele não se torna `AgentAdapter`, não substitui Claude Code/Codex, não assume a memória
de sessão e não escreve contexto canônico.

O primeiro uso é o lint do Pacote de Contexto no regime `OFF → SHADOW`. Carta, playbook, Decisões, feedback,
skills, hooks, identidade, gates e output schema continuam determinísticos/verbatim. Saída de modelo é sempre
derivada, com proveniência e fallback. A ativação que alterar o contexto efetivo requer uma ADR posterior com
provider, política de dados/egress, canário, custo, kill switch e critérios de desligamento.

## Consequências

### Positivas

- uma única transição de geração para o código novo de IA, sem construir primeiro sobre Spring AI 1.1;
- acesso à linha atual de Spring AI, incluindo `ChatClient`, advisor chain com loops componíveis e validação
  autocorretiva de saída estruturada — úteis ao `ContextAssessment`, sem retirar a validação final do backend;
- MCP Java SDK 2.0 e modelo por anotações disponíveis para evolução futura do bridge; adotá-los ou substituir o
  enforcement atual do KnowAll continua sendo uma decisão separada;
- null-safety com JSpecify e opções imutáveis/consistentes na API de IA;
- baseline Java LTS moderno no momento em que o custo de compatibilidade ainda é menor;
- dependências governadas por matrizes oficiais e upgrades futuros mais previsíveis;
- oportunidade de tratar Jackson, Docker e configuração como migração explícita, com testes próprios.

### Custos e riscos aceitos

- mudança ampla de Jackson 2 para Jackson 3 em código principal e testes;
- revisão de starters, propriedades, auto-configuração e integrações afetadas pela modularização do Boot 4;
- atualização simultânea das imagens de build/runtime e dos ambientes de desenvolvimento/automação para Java 25;
- risco de regressão em JPA/Hibernate, Security, Flyway, PostgreSQL, H2 e serialização;
- a biblioteca Spring AI estará presente antes de o modo `ACTIVE` ser autorizado; em `OFF`/`SHADOW`, isso é
  deliberado e não concede autoridade ao modelo.

O rollback operacional de cada fatia é o commit anterior verde. Não haverá downgrade parcial misturando Boot 4
com Spring AI 1.1 ou dependências Spring pinadas fora de seus BOMs.

## Alternativas rejeitadas

### Permanecer em Boot 3.5 e Spring AI 1.1.8

É a rota de menor mudança imediata, mas cria uma segunda migração já previsível e faz o novo módulo nascer sobre
uma geração que o projeto decidiu abandonar. Rejeitada pela decisão do dono de absorver o custo agora.

### Migrar para Boot 4, mas manter Java 21 e Jackson 2 indefinidamente

Java 21 é suportado pelo Boot 4.1 e a ponte de Jackson 2 pode ajudar durante a transição, porém manter ambos como
destino reduz o ganho do movimento e preserva dívida intencional. Aceitos apenas como estados intermediários.

### Atualizar cada dependência para sua última versão independentemente

Rejeitada: rompe a matriz testada pelos projetos Spring, aumenta a superfície de incompatibilidade e reduz a
reprodutibilidade. O Boot parent/BOM e o Spring AI BOM são os donos das versões gerenciadas.

### Migrar plataforma e ativar transformação de contexto no mesmo corte

Rejeitada: mistura regressões de infraestrutura com avaliação de qualidade e segurança da IA. A plataforma
chega primeiro; `ACTIVE` permanece uma decisão separada e baseada em evidência de `SHADOW`.

## Gates de conclusão

A migração da plataforma só pode ser marcada como concluída quando:

- Maven Wrapper compila e testa com Java 25, sem overrides transitivos não justificados;
- a ponte temporária de Jackson 2, se utilizada, foi removida;
- suíte completa passa com PostgreSQL/Flyway e os testes H2 ainda mantidos pelo projeto;
- imagem do backend constrói e executa com Java 25;
- provas LOCAL e DOCKER passam para Claude Code e Codex, incluindo sessão, handoff, MCP e materialização;
- `ContextIntelligence=OFF` preserva o resultado determinístico e `SHADOW` não altera arquivos, prompt ou
  desfecho do Run;
- versões efetivas e notas de migração ficam registradas no roadmap.

## Relações e efeito documental

- **Substitui** a escolha de stack do backend em Java 21/Spring Boot 3.5 da ADR-0001; seus invariantes de
  arquitetura e segurança permanecem válidos.
- **Funda** o plano `docs/plano-implementacao-spring-ai.md`.
- **Não autoriza** o modo `ACTIVE`; a decisão de contexto derivado efetivo permanece em ADR própria posterior.

## Resultado da execução — 2026-07-17

- Stack efetiva: Java `25.0.3`, Maven Wrapper `3.9.16`, Spring Boot `4.1.0`, Jackson 3 e Spring AI
  `2.0.0`. A árvore efetiva contém somente o starter Ollama; não há import nem ponte Jackson 2 no código da
  aplicação.
- Suíte completa H2: **757 testes**, zero falhas, erros ou skips. PostgreSQL/Flyway em banco limpo: migration
  `V30`, tabelas `run_context_manifest` e `context_assessment` presentes.
- Docker: build verde e runtime Temurin `25.0.3`. Claude Code e Codex reais concluíram materialização e Runs
  em LOCAL e DOCKER. Sessão e handoff foram exercitados ao vivo no alvo LOCAL; o bridge MCP atual também foi
  provado em LOCAL, seu alvo suportado. O executor continua deliberadamente sem MCP dentro do DOCKER nesta
  fatia, conforme a proibição de reescrever o bridge.
- `OFF`: zero assessments e comportamento determinístico. `SHADOW` com Ollama indisponível: Run
  `c6ca1e70-40e3-422d-a8be-ed445beb2682` terminou `SUCCEEDED`, resultado `SHADOW_RUN_OK` e assessment
  `FALLBACK/TIMEOUT` em 753 ms. Seu manifesto preservou exatamente o hash
  `8d558ff61e83441e7755417f9b5f1f749e2c9469c0915350562e278a88b2b2b2`, 2.471 bytes e 618 tokens
  estimados já observados nos Runs OFF LOCAL e DOCKER.
- Commits verdes e restritos: `ca026ed`, `5aade0e`, `4f31fdd`, `bde1e04`, `b183600`, `ef4c315`,
  `2e21356`, `1362523` e `50db7ff`.
- Caveat de runtime preservado: Codex CLI `0.142.5` rejeitou o modelo herdado `gpt-5.6-sol`; a prova passou
  após pinar o agente de teste em `gpt-5.4-mini` com effort `low`. Isso não altera os adapters nem cria
  fallback silencioso.

O modo `ACTIVE`, F-SAI2, F-SAI3 e F-SAI4 permanecem não iniciados. A promoção continua dependendo de ADR
posterior, métricas de `SHADOW` e decisão humana.

## Referências verificadas em 2026-07-16

- [Spring Boot 4.1.0](https://spring.io/blog/2026/06/10/spring-boot-4/)
- [Requisitos de sistema do Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)
- [Gerenciamento de dependências do Spring Boot](https://docs.spring.io/spring-boot/reference/using/build-systems.html)
- [Guia de migração para Spring Boot 4](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Java 25](https://openjdk.org/projects/jdk/25/)
