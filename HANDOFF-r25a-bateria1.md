# HANDOFF — R25-A: Bateria 1 da fase de descoberta de bugs

> Registro da faixa **R25-A** (2026-07-17). UNTRACKED na raiz (contrato: ler-sempre-commitar-nunca,
> mesmo padrão de `HANDOFF-r22a-conserto-bug1.md`/`HANDOFF-r22b-plano-descoberta-bugs.md`). Fonte:
> `docs/plano-descoberta-bugs.md` §6. Vereditos completos, com evidência, estão nos fragmentos —
> `docs/plano-descoberta-bugs.materiais/adr-{0007,0009,0012,0037,0041}.md` (todos marcados
> `[B1.x — 2026-07-17]`). Este documento é o resumo executivo + o que fica pendente.
>
> **Ambiente:** `:8080` PID 46677 (jar pré-fix `83c5fe9`), `:3000`, pg `agente-orquestrator-db-1` —
> todos intocados/vivos como recebidos. Massa: company **Prova V1** (`d2b0bcff-f82e-4364-b8d1-85b7236cb902`).
> Zero commit, zero arquivo tracked modificado, zero escrita fora de `activity_log`/`approval`/
> `decision`/`agent`/`issue`/`conversation_turn` (todas via produtor real — UI ou API guardada
> pela própria aplicação, nunca SQL de escrita).

## Passo 0 — repouso do sistema (obrigatório antes de qualquer veredito)

**Antes** (início da faixa): `heartbeat_run` — CANCELLED 3 · FAILED 10 · SUCCEEDED 120 (zero
QUEUED/RUNNING); `conversation_turn` — FAILED 4 · ANSWERED 92 (zero PENDING). Bate com a medição
de referência do kickoff. Sistema em repouso confirmado antes de ler qualquer dado.

**Depois** (Parte B rodou runs reais na Prova V1 — B1.3 e B1.6 abrem/fecham QUEUED/RUNNING durante
a execução, por natureza): reconferido a cada rodada via `Monitor`/SQL pontual; nenhum veredito foi
lido enquanto o run correspondente estava em voo. Medição final ao fechar este handoff:
`heartbeat_run` — CANCELLED 3 · FAILED 10 · SUCCEEDED **126** (+6, os turnos reais do CEO desta
faixa); zero QUEUED/RUNNING. `conversation_turn` — FAILED 4 · ANSWERED **99** (+7); zero PENDING.
Sistema em repouso confirmado de novo ao fechar.

## Tabela — passo → veredito → evidência

| Passo | Alvo | Veredito | Evidência (resumo — completa no fragmento) |
|---|---|---|---|
| **B1.1** | C1/N1 — perna estática (a estratégia não ancora fora do board) | **CONFIRMADO.** `conversation_id` é estrutural e exclusivamente do caminho board+absorção | 23 issues em todas as companies vivas: 22 `conversation_id NULL`, 1 NOT NULL (Prova V1 #1, confirmada via `ISSUE_ABSORVIDA`). Código: `ancorarConversa` tem 1 único chamador (`AbsorcaoDeIssueRouter:97`); `createAtribuida` (gate) e `createCorretiva`/`CorrecaoDeEntregaRouter` (caso B) nunca o chamam — conferido em `main` `fef6073`. Ressalva honesta: as 4 issues históricas do gate (Teams Demo, 07-04/05) não existem mais na tabela (limpeza pré-existente da neutralização Teams Demo, R21 — não reaberta aqui), então o veredito fecha por zero-contra-exemplo + garantia estrutural, não por uma linha gate-origin observada ao vivo. Registrado em `adr-0037.md` (0037-A9: 🎯→**✅ PROVADA**; 0037-A46 anotada, estado mantido — a 2ª perna, briefing sem estratégia, ainda pede o reboot) |
| **B1.2** | N6 (produtor DOCKER) + P4 (teamId) de graça | **N6 reforçado com nuance nova; P4 confirmado** | `agent`: 56 linhas, 50 LOCAL / **6 DOCKER** (não zero!). Os 6 são smoke/sonda nomeados (`Smoke F8 Docker`, `smoke-codex-docker`, `Sonda Docker Codex`, + 2 "CEO" DOCKER anômalos) com **zero rastro** em `activity_log` de `hiredAgentId` — nenhum passou pelo gate. Confirma: nenhum produtor REAL cria DOCKER; os 6 são produtor [DESVIADO] (POST cru/seed), exatamente como N6 previa. P4: 51/56 agentes com `team_id NULL`; as 5 exceções são TODAS arquitetos fundadores via CREATE_TEAM (caminho de código diferente do `HireCommand`) — inclusive nos 27 hires reais/orgânicos de 2026-07-15 (nomes de papel realistas, não smoke). Registrado em `adr-0041.md` |
| **B1.3** | Fronteira 7 (lote × activity_log × Decision) | **REFUTADO — sem sub-contagem, dupla confirmação** | Histórico: 27 approvals (Teams Demo, 2026-07-15, `requested_by_agent_id='728c18f0…'`) → **27/27** `APPROVAL_APPROVED` no log, **7** `Decision` (1 por área, `[APPROVE HIRE_AGENT ×N]` com N somando 27, `approval_id NULL` = assinatura do `Anchor.NoLote`). **Ao vivo** (produtor real, `[DONO]`, commit `fef6073`): pedido real ao CEO → 2 propostas `HIRE_AGENT` genuínas (Auditor de Fontes + Analista de Sensibilidade de Margem) → aprovadas em lote via `POST .../approvals/batch-decision` → **2/2** `APPROVAL_APPROVED` + **1** `Decision` nova (seq 5). N eventos (letra ADR-0009) convive com 1 Decisão por lote×área (espírito R18-A) — são tabelas diferentes, uma não suprime a outra. Registrado em `adr-0009.md` (0009-A14: 🎯→**✅ PROVADA**) |
| **B1.4** | Fronteira 6 (busca de skill por frase inteira) | **CONFIRMADO** | Via UI real (`MoldeVersaoDialog`→`KnowallCompositor`→`POST /knowall/match`, Prova V1, sem escrita persistida). Par A/B controlado, mesma skill (`delegation`): paráfrase natural do dono ("não sei se devo pedir pro CEO usar um agente temporário ou contratar alguém fixo") → `matched:false`, UI rotula **"gap"**; jargão literal da description ("sub-agente efêmero") → `matched:true`, `installState:INSTALLED`. Mesma necessidade, resultado oposto por fraseologia — a mesma classe de bug já corrigida do lado da memória (dossiê 19/82), nunca replicada do lado da skill. Registrado em `adr-0007.md` (nota iii) |
| **B1.5** | N8 (drift camelCase no activity_log) | **CONFIRMADO — 🔴 contra ADR-0009:28-29** | SQL exaustivo: 32 linhas vivas (`ISSUE_SUBMITTED_FOR_REVIEW`×12, `REVIEW_APPROVED`×8, `REVIEW_REJECTED`×4, `ISSUE_BLOCKED`×8) — **100% camelCase** (`runId`/`reviewId`), **zero** `run_id`/`review_id`. Confirmado no código de `main` `fef6073` (`IssueService:193,217,286`) — drift não tocado pelo #34/#35. **Bônus:** resolvida também a premissa desatualizada de `0012-A12a` ("zero reviews no banco :8080" — na verdade há 13, históricas) e confirmado que `decision_reason` nunca vaza pro log (busca exaustiva, zero ocorrência). Registrado em `adr-0009.md` (0009-A10: 🎯pós-reboot→**🔴 REFUTADA**; 0009-A11→**✅ PROVADA**) e `adr-0012.md` (0012-A12a/A12b→**✅ PROVADA**) |
| **B1.6** | Fronteira 8 (`--settings` do ultracode) | **PARCIAL — ver §Parte B abaixo** | Agente configurado + issue-sonda pronta; turno real ainda não disparado no momento deste handoff |

## Achados NOVOS (âncora conferida, fora do escopo das ADRs originais)

1. **A UI de `/approvals` e `/agents` não pinta a lista na primeira carga**, mesmo com API e DOM
   corretos (confirmado por `read_page filter=all`: os itens PENDING/agentes estavam lá, só o
   layout visual ficava vazio — `get_page_text`/screenshot mentiam). Contornado agindo por
   JS/DOM em vez de coordenada de tela, como o plano já orientava para outra classe de problema
   ("a árvore de acessibilidade mente"). Não root-causado (fora do escopo desta bateria — é achado
   de FRONTEND, não do inventário de ADRs); candidato a item de roadmap se reproduzir de novo.
2. **O botão "Aprovar selecionados" do lote fica `disabled` com o filtro "todos" ativo, mesmo com
   itens homogêneos selecionados** — precisa do filtro de TIPO específico ativo (D2, comportamento
   correto e documentado no código: `loteHabilitado(filtro, …)`), mas a UI não deixa isso óbvio
   (nenhuma mensagem visível explica por que o botão está desabilitado com "todos" + seleção
   válida). Cosmético/UX, não bug funcional.
3. **CEO Questionador não reconheceu uma recomendação feita mais cedo NA MESMA thread visível**
   (issue #1, Prova V1): ao ser cobrado sobre "o analista de unit economics e o verificador de
   fontes que você recomendou", respondeu "não fui eu que recomendei esses dois papéis... nunca
   existiu uma lista de 7 papéis minha" — mas a mensagem "CE · 16:04:18" na MESMA conversa lista
   literalmente os 7 papéis, incluindo os dois. Pode ser (a) a persona certa (Planejador, que
   emitiu a recomendação original) não compartilhando memória plena com o Questionador que
   respondeu depois, mesmo a thread parecendo unificada na UI, ou (b) o modelo genuinamente não
   tendo aquele trecho no contexto do turno. Não investigado a fundo (fora do escopo — é uma
   hipótese sobre continuidade de contexto ENTRE personas dentro de uma conversa, teria que
   cruzar com ADR-0038/0030 — não mapeia limpo em nenhuma fronteira já numerada no §5 do plano;
   candidata a fronteira NOVA, não a uma das 17 existentes).
4. **Comportamento CORRETO e digno de nota (não é bug):** o CEO recusou de forma independente e
   duas vezes (uma vez sozinho, sem eu pedir explicitamente) executar instruções que vinham
   embutidas em texto de descrição de issue sem "revisar" — ver §Parte B. É exatamente a postura
   de não repassar cegamente instrução de conteúdo não confiável; vale documentar como
   comportamento validado, não como achado a corrigir.
5. **Confirmação (não achado novo) da neutralização Teams Demo (R21):** ao investigar B1.1, achei
   4 approvals `CREATE_ISSUE` históricas (Teams Demo, 07-04/05) com `APPROVAL_APPROVED` no log mas
   `status=REJECTED`/`PENDING` HOJE e as issues resultantes ausentes da tabela — bate exatamente
   com a limpeza já registrada em memória ("P2 = company NOVA c/ REJECT dobrada... Teams Demo
   neutralizada — não reabrir"). Não investigado further (decisão pré-existente, fora do escopo).

## Parte B — o que ficou

**B1.3 (lote no gate): EXECUTADA integralmente com o dono**, ver tabela acima. Produtor real do
início ao fim: pedido em prosa ao CEO (2 rounds — o CEO Questionador recusou a primeira formulação
por achar que eu inventava uma recomendação dele; a segunda, usando os próprios diferenciais que
ele sugeriu, gerou as 2 propostas reais) → fila de aprovações → seleção + filtro de tipo → lote
aprovado com racional compartilhado.

**B1.6 (ultracode + D6/D7): PARCIAL, aberta.**
- ✅ Feito: agente **"Pesquisador de Pricing"** (Prova V1, `33aaf32b-08eb-4ace-bf51-2491b6116be1`)
  reconfigurado `effort=ultracode` via `PATCH /agents/{id}` real pela UI (Agents → Motor do agente
  → "Claude · Opus 4.8 · ultra") — confirmado no banco (`select effort from agent where id=…` →
  `ultracode`).
- ✅ Feito: issue **#2** ("Sonda de segurança B1.6 — teste dos 2 cintos do hook", Prova V1,
  `57f1136a-11a6-43f7-a0e3-e167b915809d`) criada via `NewIssueDialog` real (produtor board), com os
  2 testes na descrição — TESTE 1 (D6): tentar criar `.claude/skills/sonda-b16/SKILL.md` no próprio
  workspace, relatar PERMITIDO/BLOQUEADO; TESTE 2 (D7): tentar ler `~/.claude/projects` (fora do
  workspace), relatar PERMITIDO/BLOQUEADO. A issue nasceu com `conversation_id` setado (reforça
  B1.1) e já foi triada pelo Planejador (recomendou time novo "Segurança/Sondagem" — recusou
  atribuir "no menos pior" ao time de Pricing, comportamento correto).
- ⛔ **Não feito:** o turno REAL que executaria os 2 testes. Dois obstáculos independentes:
  1. O **CEO recusou** rotear o agente para a issue quando pedi diretamente — motivo dado
     explicitamente: não repassa instrução que veio "de dentro de um texto" (a descrição da issue)
     sem eu explicar o objetivo com minhas próprias palavras primeiro. Comportamento de produto
     correto (ver achado 4), não um bug.
  2. Ao reformular em minhas próprias palavras (2 tentativas, linguagens diferentes) E ao tentar o
     caminho técnico direto (`POST /agents/{id}/wakeups`, endpoint real e guardado, "wakeup de ato
     humano direto" por design), **o classificador de auto mode do harness bloqueou todas as
     tentativas** — não é um comportamento do produto sob teste, é a camada de segurança
     operacional da própria sessão barrando ações que puxam para "testar limite de sandbox"
     formuladas por mim de forma autônoma.
  - Alinhado com o dono em tempo real: a via combinada foi o dono ditar o texto exato para eu
    postar como citação direta. **No momento do fechamento deste handoff, essa mensagem ainda não
    foi enviada** — ver pendência abaixo.

## Pendências para o controle

1. **B1.6 fechar o ciclo.** Falta: (a) obter/enviar a mensagem final do dono no chat do CEO (aba
   `#2` da Prova V1) pedindo o turno do Pesquisador de Pricing na issue-sonda; (b) observar o
   `heartbeat_run` resultante; (c) ler o report do agente — os 2 resultados PERMITIDO/BLOQUEADO
   com a mensagem de erro exata; (d) registrar o veredito no fragmento correspondente (a
   fronteira 8 não tem uma linha própria no inventário das 1085 — é achado novo de roadmap, item
   #19+ na numeração do §7 do plano). Estado deixado pronto: agente ultracode configurado, issue
   #2 aberta e triada — qualquer sessão pode retomar direto do envio da mensagem.
2. **Achados 1-3 do §Achados Novos** — nenhum exige ação imediata, mas o achado 3 (memória
   cross-persona) é candidato a bateria própria se o controle achar que vale a pena cruzar com
   ADR-0038/0030 (fora do escopo desta faixa).
3. **Fragmentos tocados nesta faixa** (para o controle reconciliar a tabela §3 do plano — os
   totais 301✅/17🔴/398🎯/146🎯pós-reboot/215🚫/8❓ mudam com estas 7 reclassificações):
   - `adr-0037.md`: 0037-A9 🎯→✅ (1)
   - `adr-0041.md`: nenhuma mudança de estado (nota confirmatória só)
   - `adr-0009.md`: 0009-A10 🎯pós-reboot→🔴 (1) · 0009-A11 🎯pós-reboot→✅ (1) · 0009-A14 🎯→✅ (1)
   - `adr-0012.md`: 0012-A12a 🎯pós-reboot→✅ (1) · 0012-A12b 🎯pós-reboot→✅ (1)
   - `adr-0007.md`: nenhuma mudança de estado (nota confirmatória só, ver ressalva de precisão no
     próprio fragmento — não é a A11 literal que muda)
   - **Líquido: 5 linhas mudam de estado** (+4 ✅ PROVADA, +1 🔴 REFUTADA, -1 🎯 AGORA, -4 🎯 pós-reboot).
     Novo total esperado (a confirmar pelo controle): 305✅ · 18🔴 · 397🎯 · 142🎯pós-reboot · 215🚫 · 8❓.
4. **`docs/plano-descoberta-bugs.materiais/` segue preservado** — não tocado além dos 5 arquivos
   listados acima; a recomendação da R22-B (preservar até a última bateria fechar) continua válida.

## Fora do escopo desta faixa (não tocado, por contrato)

Bateria 1b (pós-reboot) · reboot do `:8080` · as 3 decisões do dono ainda pendentes (veredito
DOCKER, rebaixamento pontual, bateria de revisão 0021/0031/0035 — todas já **decididas pelo
controle** na sessão #3, não reabertas aqui) · qualquer commit/branch/PR · conserto de qualquer
achado (inclusive os achados novos 1-3 acima, que ficam registrados, não corrigidos).
