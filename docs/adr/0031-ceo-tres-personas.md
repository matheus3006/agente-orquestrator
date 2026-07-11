# ADR-0031: O CEO em 3 personas — Questionador / Planejador / Executor (Fatia 2 do v1)

**Status:** Aceito (2026-07-10) · grill fechado em 2026-07-07 + §ADENDO vinculante de 2026-07-09
(`handoff-grilling-3-ceos-personas-fechado.md`, D1–D12/M1–M9 + A1–A5)

## Contexto
O "CEO" era 1 agente fazendo três trabalhos que pedem posturas opostas: grelhar o dono (extrair
intenção), planejar orquestração (compor times) e executar o protocolo do gate (contratar). O grill
das insatisfações ao vivo (G1/G6, runs do Atlas) mostrou o custo: prompts genéricos demais, dossiê
inexistente, racional invisível. O dono pediu **auditoria**: ver três instituições distintas,
inspecionáveis e substituíveis — trias politica, independentes e harmônicas.

## Decisão

### 1. 3 agentes DURÁVEIS `role='ceo'` + discriminador `persona` (D1, V24)
Não é 1 agente trocando de chapéu: são 3 linhas `agent` (questionador/planejador/executor), cada
uma com seu motor, seu skill-set (`agent_skill_selection` próprio — `resolveForRun` já é
por-persona de graça) e sua trilha de runs. `persona` é texto livre (padrão dos alters V4/V12/V14),
NULL = agente comum/CEO legado.

### 2. Nascem do SISTEMA na criação da company (D9, V25 + `CeoPersonaSeeder`)
3 moldes-de-sistema `ceo-*` (espelham V14; `company_id NULL`) com os `skill_keys` internos
(`being-ceo`/`writing-doc-plans`/`delegation` — corpos na faixa R1-D; key ausente é pulada, T.1.b).
O seed é **dirigido pelos moldes e fail-soft**: banco sem V25 (H2 dos testes) não semeia nada.
Backfill das companies existentes na própria V25: o CEO canônico (mais antigo) VIRA o Questionador
— identidade, motor e histórico preservados; Planejador/Executor nascem copiando o MOTOR do
canônico (precisa RODAR; moldes ficam para companies novas).

### 3. Balcão único no Questionador (D2, M2)
`ConversationService` resolve o inbound do dono por `(role='ceo', persona='questionador')` com
fallback ao canônico (legado pré-backfill). Planejador e Executor são back-office: recebem bastão,
nunca a mensagem do dono. O front espelha (roster prefere o questionador).

### 4. Bastão autônomo por DETECÇÃO DE ENTREGÁVEL (D6, M5/M6) — blocos tipados
A persona emite um bloco cercado na RESPOSTA; o `PersonaHandoffScanner` (irmão do
`ProposalScanner`: JSON em fence, parse determinístico, malformado descartado fail-soft) roteia no
settle: ` ```handoff ` `{"para","bastao","resumo"}` → continuação da persona-alvo (sentinela
`[continuação — bastão…]`, INVISÍVEL na thread pelo filtro da ADR-0030); ` ```needs-grilling `
`{"lacuna"}` → kickback; ` ```proposta ` → gate T3, INTOCADO. Diferença deliberada do irmão: cerca
só vale em **coluna 0** — o exemplo do formato viaja indentado no prompt e nunca dispara quando
ecoado. Uma virada por settle; idempotente sob reexecução do reaper.

### 5. Workspace de conversa COMPARTILHADO (D4, M4)
Runs de turno de persona resolvem o workspace por `conversationId` (peek pré-claim; mesma forma
2-segmentos do workspace de agente — estruturalmente fora do reaper por-Issue). O bastão vive em
`.orq/conversa/<conv>/` (dossiê/estratégia — `ConversationBaton`), fora de `materializedSubtrees`:
sobrevive à re-materialização e cruza a fronteira de agente. Skills/carta re-materializam por-run
pela convenção do adapter (ADR-0032 intocada) — as personas trocam de chão limpo.

### 6. Confirma-antes Q→P (D5) e dossiê APENDADO (A1)
O dossiê cresce A CADA turno (`.orq/conversa/<conv>/dossie.md`, palavras EXATAS do dono); o "sim"
do dono só CARIMBA. O backend NUNCA interpreta a prosa: a persona decide e emite o bloco no turno
seguinte ao "sim". Resposta sem bloco = nada acontece (idle).

### 7. Kickback cirúrgico (D7) — turno VISÍVEL
`needs-grilling` vira um turno do Questionador com `owner_message` legível (`Re-grelha — o
<origem> travou…<lacuna>`), **visível** na thread (precedente do aviso de corte da ADR-0030):
a resposta do Q é a re-grelha que o DONO precisa ler. A origem viaja na mensagem — é assim que o
run fresco (sem `--resume`) sabe a quem devolver o bastão via `handoff`.

### 8. Trava anti-loop de kickback (M9) — prossiga, e na reincidência CORTA
Kickbacks seguidos SEM o dono no meio (sentinelas puladas; fala do dono zera a régua) até
`MAX_KICKBACKS_SEGUIDOS=5` (espelha `MAX_DEPTH`; veto do dono pendente) → a ORIGEM recebe
"prossiga com a melhor premissa e SINALIZE". **Reincidir após o prossiga → CORTE** (turno ANSWERED
visível, sem novo Run — espelho da ADR-0030): sem isso o ping-pong autônomo giraria para sempre
(achado do TDD: o teste travou o build antes do corte existir).

### 9. Gate T3 é o único gate duro; o menu ganha a mão soberana no MOTOR (D3 + A2/A3/A4)
O Executor PROTOCOLA sem mexer (1:1 com a estratégia; buraco = kickback, nunca conserto
silencioso); cada ` ```proposta ` carrega motor sugerido + `racional` estruturado por-agente (o
card exibe — A3). No decide, o dono pode sobrescrever o TRIO do motor (`EngineOverride`; campo
em branco cai no protocolado) sem rejeitar nem devolver ao Planejador (A4 — CEO tem viés de
motor). Skills: somente-leitura no menu; skill errada não trava o gate (A5 — corrige-se pelo pin
por-agente e/ou avisando o Questionador).

### 10. O Planejador PRODUZ a estratégia de ORQUESTRAÇÃO (D12) — divergência consciente da 0018
T1 trai o núcleo "o CEO não produz conteúdo" da ADR-0018 — escopado: estratégia é ESCOPO de
orquestração (áreas/times/agentes/skills/motor, tetos de prompt MAX 30 agentes/10 profundidade —
D10: `delegation` usa as ferramentas do PRÓPRIO run, off-book, sem subsistema), nunca conteúdo de
execução. A cadeia PO→Arquiteto da ADR-0017 vira personas NA ORQUESTRAÇÃO; o
**arquiteto-de-domínio sobrevive como empregado** (por-time, fonte das Issues-task) — o
`PLANEJAMENTO_ARQUITETO` segue valendo para empregados.

### 11. M8: estado vivo agrupado por área
`LiveStateProjector` projeta `### Equipe` agrupada por `team.dominio/foco` (determinístico, zero
LLM — ADR-0019): é o que o Planejador lê para propor times.

## Alternativas rejeitadas
- **1 agente + persona-por-turno** — mais barato, mas "uma mente com 3 modos": mata a auditoria
  (motivo central do dono) e o skill-set por-persona deixaria de ser gratuito.
- **3 contratações pelo gate** — seriam execução, não orquestração; e o gate viraria pré-requisito
  da própria constituição da company (D9 decidiu: auto-seed).
- **Swap de persona gated por approval** — alargaria o enum FECHADO `ApprovalActionType`
  (deny-by-default); ADR-0018 já dá autonomia entre marcos.
- **Bilhete agent-scoped** — quebraria no cross-persona; o transporte é conversation-scoped
  (paralela da ADR-0030, não cliente).
- **Canal "terminei" próprio** — o entregável REAL é o sinal (D6); canal-fantasma foi rejeitado
  já na Fatia 1.

## Ressalvas honestas
- **Formato dos blocos tipados: CONFIRMADO pelo dono (2026-07-10)** — pinado em
  `PersonaHandoffScannerTest`; a faixa R1-D (`⚠ SYNC-FATIA-2`) pode sincronizar os drafts.
- **N=5 da trava M9: CONFIRMADO pelo dono (2026-07-10)** (espelha MAX_DEPTH; trocar é 1 constante).
- **Governança off-book do `delegation` (D10): aceite no v1 CONFIRMADO (2026-07-10)** —
  sub-agentes/web do run são invisíveis a lineage/budget; freio duro seria subsistema (adiado,
  §G.3 do handoff).
- Os turnos de fluxo compartilham o prefixo `[continuação` com a ADR-0030: contam na régua do
  poster de reset (cap 5) — orçamento de segurança compartilhado, aceito no v1.
- Backfill copia o motor do canônico para P/E — companies legadas ficam com 3 CEOs do MESMO
  motor até o dono editar (a UI de motor já existe por agente).

## Relaciona
ADR-0017 (arquiteto-de-domínio sobrevive; forward-ref) · ADR-0018 (balcão único; "delegador puro"
escopado a entregáveis; forward-ref) · ADR-0019 (M8 determinístico) · ADR-0020 (grill do gate
intocado) · ADR-0030 (transporte de continuação; sentinela; travas-espelho) · ADR-0032 (as skills
das personas chegam ao codex por aquele seam) · ADR-0033 (o #N que o dono usa no caso B futuro) ·
**ADR-0035** (a recusa no gate volta ao proponente pelo MESMO poster de continuação — o 2º
consumidor do transporte M6, fora do settle; a divisão de personas segue intocada).
