# Handoff — Teams T.3-FE (a conversa do CEO como coluna docada) — ✅ COMPLETA (2026-07-04)

> Fatia **T.3-FE** do plano Teams: o frontend de T.3.a (`docs/handoff-teams-t3a.md`). Spec visual
> congelada: `frontend/prototipos/ceo-conversa-mockup.{md,html}` (DESIGN APROVADO — implementado
> 1:1 sobre os tokens do board). Semântica projetada: ADR-0018 (gate por proposta) + ADR-0020
> (grill do racional). Repo sem git — nada commitado.

## Piso (verificado)
- Frontend **vitest 174/174** (eram 151; +23 de T.3-FE) · **tsc limpo** · **next build ✓**.
- Backend intocado (219/219 de T.3.a segue; jar V18 no `:8080`).
- **Verificação VIVA no Preview MCP** (`:3000` + jar real), console **zero warnings**: grill
  ponta-a-ponta (422 → probe → D7 "porque quis" rejeitado → commit com racional → carta v1→**v2**
  consolidada), mensagem viva (202 → PENDING "pensando" → ANSWERED ~1s), re-proposta pós-decisão
  abriu ciclo novo (badge voltou), chips/collapse/contador, 409 de assunto duplicado, deep-link
  `?chat=` sobrevive a reload, carta e report em dialogs, trace drawer da thread.

## O que T.3-FE entregou

### A tela (Mission Control virou DOCADA — não é 6ª superfície)
- **`src/app/[companySlug]/page.tsx`**: duas zonas full-height (`.central-docada`, grid 5fr/7fr,
  empilha <1100px). ESQUERDA = times observados (somente-leitura); DIREITA = coluna do CEO com o
  ÚNICO composer do board (cadeia de comando imposta pela UI). StepTraceDrawer compartilhado:
  cards da esquerda E disclosure da thread abrem o mesmo drawer (thread passa `agentName=CEO`).
- **`src/components/times-observados.tsx`**: chips-filtro (liga/desliga grupo; reusa
  `.filter-tab` + LED), grupos colapsáveis com **hue do time** (o `teamHue` de T.2) e o **gate do
  arquiteto no cabeçalho** (`sem arquiteto` = `.team-chip-gate` âmbar; "arquiteto: Lyra" quando
  designado), tally por grupo ("N · M rodando"), contador reativo ("N grupos · M agentes"), time
  vazio = empty-state "peça ao CEO pra montar". Cards = `AgentCard` da Central, intocado. O feed
  de atividade entra como `children` no fim do scroll (deliverable F.3 preservado).
- **`src/components/ceo-conversa.tsx`**: abas por assunto (badge âmbar de PENDING por chat via
  `pendentesPorConversa`; "+" abre dialog — 409 vira mensagem de produto), deep-link **`?chat=`**
  (useSearchParams + `useCompanyNav.replace(..., {scroll:false})` — o shim ganhou options,
  aditivo), thread com polling 2s (turnos + ancoradas; só a aba ativa polla; remount por chat =
  troca instantânea), composer (Enter envia, autogrow, 202 → invalida turnos → PENDING aparece na
  hora; sem CEO = placeholder explica), dialog da **carta** (404 = "nasce da primeira direção").
- **`src/components/approval-thread-card.tsx`**: o card ancorado. PENDING = corpo legível
  (verbo+alvo humanizados, resumo, "escalação assíncrona — o resto continua") + Aprovar/Rejeitar;
  **422 = grill de 1ª classe** (painel com probe + rodada visível + textarea com foco via
  `useEffect` — rAF disparava antes do commit, visto vivo); decidida = veredito + **racional
  exposto na thread** + "racional arrancado (N rodadas)"; disclosure "▸ report da issue"
  (`GET /issues/{id}/report`; 404 = estado legítimo) e "▸ abrir transcript".

### Módulos de dados profundos (TDD, RED confirmado em todo ciclo)
- **`src/lib/ceo-conversa.ts`** (+13 testes): `splitReply` (prosa vs bloco ```` ```proposta ````
  — a bolha NUNCA mostra JSON cru; o conteúdo legível chega pelo card), `projetarThread` (ancora
  approval ao turno pelo **runId**; órfã não some — cai no fim), `pendentesPorConversa` (badge),
  `acaoLegivel` (CREATE_ISSUE/HIRE_AGENT/WAKEUP_AGENT humanizados; JSON quebrado degrada pra
  resumo, nunca lança), `issueDaApproval` (link approval→issue pro report — ver decisão 4).
- **`src/lib/team-groups.ts`** (+5): `agruparEstacoes` (Liderança=role `ceo` → times na ordem do
  catálogo → "Sem time"; gate espelhado; time vazio aparece) e `contarVisiveis`.
- **`src/lib/api.ts`**: tipos+funções de conversations/turns/approvals/carta/report;
  **`decideApproval` devolve UNIÃO** `{kind:'committed'}|{kind:'grilled',probe,rounds}` — o 422 é
  retorno, não exceção (espelha o `DecideOutcome` selado; `apiFetch` ganhou `acceptStatuses`).
  +3 testes (grill union, committed, 409 segue ApiError).
- **`src/lib/query-keys.ts`**: `conversations/conversationTurns/approvalsQueue/approvalsAnchored/
  approvalsScope/carta/issueReport` — as DUAS projeções da fila sob o mesmo prefixo (decidir
  invalida em bloco). +2 testes de shape.
- **`globals.css`**: seção T.3-FE (zonas, grupos, abas, balões, `.aprovacao`, `.grill`,
  composer, `.memoria-corpo`) — mockup traduzido pros tokens; keyframes `msg-in`/`badge-in`/
  `pensando-pulse`; reduced-motion cobre tudo.

## Decisões da fatia (registradas)
1. **Grill em duas fases**: o board clica Aprovar/Rejeitar SEM racional; o 422 abre o painel.
   REJECT também grelha (confirmado no `ApprovalService` — o probe roda antes de qualquer efeito).
   Racional digitado no grill é reenviado com a MESMA decisão; "Deixar pra depois" fecha o painel
   (a rodada já ficou contada no servidor — 422 nunca faz rollback).
2. **Snapshot inicial não anima**: as chaves da thread congelam quando turnos+ancoradas resolvem
   (ref lazy — `useState` no mount congelava vazio e a 1ª carga animava tudo; achado vivo). Só o
   APPENDADO pelo polling entra com `msg-in`. Troca de aba = instantânea (remount por `key`).
3. **Tallies grandes e ViewHead saíram desta tela** — o mockup aprovado os substitui por tally
   por-grupo + contador; o feed sobreviveu (fim do scroll da esquerda). As outras superfícies
   mantêm `View`/`ViewHead`.
4. **`issueDaApproval` resolve por TÍTULO exato** (mais recente vence): o id criado pelo APPROVE
   vive só no `detail` do activity_log (janela finita). Aprovar duas vezes o mesmo título cria
   duas issues e o link mira a mais nova — limitação v1 aceita e comentada no código.
5. **Polling pausa em aba oculta** (`refetchIntervalInBackground:false`, padrão da Central) — na
   verificação viva o preview roda hidden e o poll para; em aba visível flui. NÃO é bug.
6. **`useCompanyNav.replace` ganhou `options?` aditivo** ({scroll:false} pro deep-link não pular
   o scroll das zonas). Nenhum outro caller afetado.

## Residuais (aceitos, decidir depois)
1. Approval órfã de conversa (`conversationId` null — proposta fora de chat) não aparece em
   nenhuma thread; a fila PENDING global a contém, mas não há superfície dedicada (o badge só
   soma por-conversa). Superfície "fila do gate" é evolução.
2. O corpo do card usa resumo+alvo humanizado; o report .md como corpo (mockup §4, PLANO/
   RESULTADO/…) entra quando approvals de MARCO DE FEATURE existirem (v1 só tem propostas
   pré-hoc CREATE_ISSUE/HIRE_AGENT/WAKEUP_AGENT).
3. `reason` (decision_reason ≤2000) não tem campo na UI — o racional carrega a substância; o
   reason fica pra quando a fila do gate ganhar superfície própria.
4. Dado de demo: a verificação viva deixou na `teams-demo` o 2º approval APPROVED (grill 4
   rodadas), um 3º PENDING (ciclo novo), carta **v2**, chat "Desenvolvimento" e uma 2ª issue
   "Definir nicho do produto".

## Ambiente / repro
- Backend: jar V18 no `:8080` (não tocado). Front: `preview_start frontend` (`:3000`) — o
  `.next` foi limpo depois do build final (gotcha Turbopack coberto).
- Demo: `/teams-demo?chat=bedc10b0-268b-4b10-9bb1-46fc67506c33` (board dev
  `b0a7d000-0000-4000-8000-000000000001`).

## Depois de T.3-FE → **T.4** (contenção)
`spawn_lineage` + fan-out cap + ledger de orquestração (a única parede do CEO com mãos + LOCAL,
ADR-0016/0018). A tela está pronta pra projetá-los: o card de approval e a thread não mudam —
muda o que existe atrás do gate.
