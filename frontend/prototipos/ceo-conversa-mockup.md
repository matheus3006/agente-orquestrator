# Regras de design — Tela de conversa com o CEO (Central de Missão)
# Frente "Teams" · agente-orquestrator · referência de implementação
# STATUS: DESIGN APROVADO pelo dono (2026-07-04). Protótipo aprovado = `ceo-conversa-mockup.html`
#   (este diretório) — a referência visual canônica de T.3-FE quando a tela real for construída.
# Esboço visual aprovado: `ceo-conversa-mockup.html` (protótipo de referência, este diretório).
# Decisão-fonte: handoff-grilling-teams-fechado.md A.6 #1 + ADR-0018 (A.8). Este arquivo PINA a
# spec de interface no repo (fecha o gap de absorção #3 — a decisão-produto do canvas/CEO vivia só
# no handoff + Artifact). É T.3-FE (CEO conversacional); NÃO implementado — só o protótipo existe.

## 1. Onde ela vive
- NÃO é uma tela de chat separada (nem 6ª superfície). A conversa é uma COLUNA DOCADA dentro da Central de Missão (Mission Control).
- Duas zonas: cards vivos dos times à ESQUERDA · conversa do CEO à DIREITA. Observar a empresa rodar e dirigir o CEO sem trocar de tela.

## 2. Cadeia de comando (imposta pela UI)
- A caixa de texto existe SÓ na coluna do CEO — o board fala só com o CEO.
- Os cards de agentes são SOMENTE-LEITURA — sem botão de responder/refinar. Observa, não conversa com eles.
- Trabalho que cruza times aparece roteado pelo LÍDER COMUM, nunca como conversa lateral.

## 3. Chats nomeados
- Múltiplos chats por ASSUNTO (Geral · Desenvolvimento · Marketing · +), como abas no topo da coluna.
- TODOS com o mesmo CEO — muda o assunto, não o interlocutor. É organização pro humano, não mudança de arquitetura.
- Cada chat tem thread e histórico próprios; o "+" cria um novo.
- As aprovações caem no chat do assunto certo; um BADGE no chat avisa o que espera o board.

## 4. Aprovações (os marcos)
- Aparecem como CARDS ANCORADOS na thread, no ponto do tempo em que surgiram — não numa tela à parte.
- O corpo do card = o report .md (PLANO/RESULTADO/VALIDAÇÃO/ORQUESTRAÇÃO), nunca JSON cru nem StepTrace bruto.
- É a MESMA fila do Review (reusa a superfície), projetada em dois lugares (badge no shell + card na thread). A fonte da verdade é a linha `review`; a thread só projeta.
- Escalação ASSÍNCRONA: quando o CEO para e pede decisão, mostra "o resto dos times continua" — trava só o pedaço, não a empresa.

## 5. Disclosure (profundidade sob demanda)
- A thread mostra SÓ o que o CEO decide contar (as mensagens dele). A profundidade (StepTrace do executor, ledger de runs) é sempre 1 CLIQUE no card ("abrir transcript"), nunca despejada na conversa.

## 6. Filtro/organização de times (na Central)
- Os cards se agrupam por TIME (Liderança, Frontend, Backend…), com o líder no cabeçalho ("reporta a Marina/CTO").
- Chips no topo LIGAM/DESLIGAM cada time; os cabeçalhos de grupo RECOLHEM.
- Times não montados aparecem no filtro (ex.: Marketing desligado, empty-state "peça ao CEO pra montar").
- O contador ("N grupos · M agentes") reage ao que está visível.

## 7. Regra técnica (o que a tela pode / não pode)
- A tela OBSERVA e PEDE aprovação — nunca escreve por fora dos invariantes. As mutações (checkout, budget, decisão de review) passam pelo backend transacional (deny-by-default, company-scoping, single-assignee).
- A tela é IDÊNTICA nas duas rotas de backend (fachada fina / durável) — o que muda é atrás dela, não o layout.
- Transporte de eventos no v1 = polling/SSE (reusa o que a Central já faz); WS invalidation-first substitui depois sem mudar a tela.
