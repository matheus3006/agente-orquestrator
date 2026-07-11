---
name: writing-doc-plans
description: Escrever a estratégia de orquestração a partir do dossiê do grill — áreas, times e agentes com racional por agente — com bloco de Premissas, validação de cobertura e calibração de lacunas. Use ao receber o bastão do dossiê, ao concluir ou revisar uma estratégia, ou ao decidir se uma lacuna vira premissa ou needs-grilling.
---

# Writing Doc Plans — a estratégia de orquestração

## O entregável

A **estratégia de orquestração** é o documento que transforma o dossiê do grill em um pedido de
time: **áreas → times → agentes**, e para **cada agente**: role, skills, motor e o **porquê**. O
racional viaja 1:1 com o pedido — o que o dono lê na conversa é o que ele aprova no menu.

Estratégia é **escopo** de orquestração, não execução: quem quebra escopo→feature→task é o
arquiteto-de-domínio do time contratado (ADR-0017). Se você se pegar desenhando features, telas ou
tasks, subiu de altitude errada — apague e volte ao organograma.

## Estrutura do documento

1. `## Missão` — o business nas palavras do dossiê (cite; não parafraseie).
2. `## Áreas e times` — o organograma proposto e o porquê de cada área existir.
3. `## Agentes propostos` — por agente: role · skills · motor · porquê.
4. `## Premissas` — toda lacuna pequena assumida (uma linha por premissa: o que assumi + por que é
   seguro).
5. `## Premissas (não confirmadas)` — só via anti-loop (abaixo); sinalize alto.
6. `## Cobertura` — o resultado da validação de cobertura (abaixo).

## Método

1. Leia o dossiê **inteiro** antes de escrever qualquer linha — a fonte são as palavras exatas do
   dono, não a sua lembrança da conversa.
2. Rascunhe áreas → times → agentes. Cada agente existe por causa de algo dito no dossiê; se você
   não consegue apontar a linha do dossiê que o justifica, corte-o.
3. Registre cada suposição em `## Premissas` **no momento em que a fizer** — premissa lembrada no
   final é premissa perdida.
4. **Validação de cobertura** — varra o dossiê afirmação por afirmação. Critério de pronto (é ele
   que fecha o documento): **cada afirmação do dono tem exatamente um destino** — um endereço na
   estratégia (área/agente), uma linha em `## Premissas`, ou uma lacuna emitida em needs-grilling.
   Nenhuma sobra.
5. Estratégia concluída → emita o bloco `handoff` para o `executor`, com o bastão = o caminho da
   estratégia. O formato canônico do bloco está nas **Instruções do turno** (contrato único,
   inline — siga-o de lá; não o reproduza daqui).

## A calibração da lacuna

Teste rápido: **"se o dono responder diferente do meu chute, quanto da estratégia eu jogo fora?"**

- **Pequena** (jogo fora pouco; qualquer resposta razoável não muda a forma do organograma) →
  registre em `## Premissas` e siga.
  - Ex.: o dossiê não fixa o fuso do público → assuma o do dono e registre.
  - Ex.: não diz a ferramenta de analytics → proponha a área com skill genérica e registre.
- **Grande** (intenção do dono; o chute errado entrega o negócio errado) → emita o bloco
  `needs-grilling` com a **pergunta concreta que só o dono responde** e **pare o turno** —
  formato canônico nas **Instruções do turno**.
  - Ex.: vende a empresas ou a consumidores? — muda o organograma inteiro.
  - Ex.: o dossiê sugere dois públicos incompatíveis e não prioriza.

**Anti-loop:** se a mensagem do turno mandar "prossiga com a melhor premissa", não re-emita
needs-grilling — escolha a melhor premissa, sinalize-a em `## Premissas (não confirmadas)` e
conclua a estratégia. O dono a verá no review/gate.

## Tetos

Respeite os tetos das Instruções do turno (MAX de agentes e de profundidade de pesquisa) — são
teto **soft** de prompt; a skill `delegation` os detalha. Um organograma que encosta no teto merece
uma linha em `## Premissas` explicando por quê.
