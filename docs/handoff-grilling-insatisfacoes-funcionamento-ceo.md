# Handoff — GRILLING: insatisfações do dono com o FUNCIONAMENTO observado do CEO (2026-07-09)

> **Tipo:** registro de grill (não é código; nenhuma linha de produção foi escrita nesta sessão).
> **Gatilho:** os 2 runs ao vivo do Atlas (CEO, codex, LOCAL) que provaram o fix do reply
> (runs `68aac0d4` e `043de730`, company teams-demo). O fix do reply em si está FECHADO
> (última `agent_message` promovida a RESULT no `CodexStreamParser`) — **não foi rediscutido**.
> **Pauta enunciada pelo dono** (não presumida): os 4 pontos observados + o ponto central dele.

---

## §A — O ponto central do dono (enunciado por ele)

**O CEO deve MANTER UMA CONVERSA** (como o Claude Code faz com ele) — não pegar qualquer coisa
dita e repassar às áreas. **Delegação só em 3 casos:**

- **Caso A — novo business:** grill meticuloso com o CEO Questionador (cada pergunta+resposta salva
  em `.md`) → só então delega ao CEO Planejador (plano: áreas, skills, conhecimentos) → pedido de
  contratação ao CEO Executor **+ racional de volta ao Questionador para o dono LER** → dono aprova
  no menu de contratação (**podendo manipular skills e motores dos agentes propostos**) → Executor
  delega aos times contratados, atribui skills a CADA agente e entrega o que construir.
- **Caso B — melhoria de task que falhou:** o CEO faz o racional mas **passa as palavras do dono
  LIMPAS** ao Arquiteto responsável do time onde o agente errou. (Pede: **ID numérico incremental
  por task** para apontar "a task N deu problema".)
- **Caso C — pedido explícito de contratação** de um agente capaz de algo.

Mapeamento: caso A ≈ Fatia 2 (ADR-0031, grill fechado) **exceto os 2 refinamentos em negrito**;
caso B = fluxo NOVO (não coberto por nada planejado); caso C = propose→gate T3 existente.

## §B — Decisões-átomo (sintoma → evidência → causa → decisão + racional)

| # | Sintoma | Evidência | Causa (verificada no código) | Decisão + racional |
|---|---------|-----------|------------------------------|--------------------|
| **G1** | CEO delegou UMA FRASE a um "worker Feynman" | run B, steps `collab_tool_call`; "Deleguei a frase candidata para um worker" | `collab_tool_call` = tool **NATIVA do CLI codex 0.142.5** (zero ocorrências no backend; ADR-0031 só planejada). A carta do turno — *"delegador PURO… nunca produza o conteúdo você mesmo"* (`ConversationTurnCoordinator.composePrompt`) — generalizou demais e o modelo obedeceu ao pé da letra. | **CORRIGIR AGORA: reescrever a instrução do turno** — CEO conversa com voz própria; delegação SÓ com trabalho real (casos A/B/C). Racional: "delegador puro" na ADR-0018 sempre foi sobre ENTREGÁVEIS, não sobre conversar; é o nosso seam (1 arquivo), barato, testável ao vivo. O collab nativo NÃO é bloqueado (D10 da ADR-0031 já decidiu sub-agentes in-run como "as mãos do CEO", teto de prompt). |
| **G2** | Bilhete de handoff gravado em TODO turno, mesmo trivial e com gatilho desligado (dial=99) | run B: ~10 dos 19 eventos são o ritual (mkdir/test/sed/file_change/sed); `.orq/handoff/9f8c63f5-….md` criado | `ConversationTurnCoordinator.java:110` — a linha *"Use a skill `distillation` e grave o bilhete…"* é **imperativo incondicional**; o "se a janela está enchendo (~50-60%)" ficou na linha anterior. O dial do backend só condiciona a CONTINUAÇÃO, não a instrução. | **CORRIGIR AGORA: dobrar o imperativo PARA DENTRO da condição** ("só SE… destile; senão NÃO grave nada"). Racional: o desenho D1 da ADR-0030 sempre foi auto-limite comportamental + rede pós-settle; a REDAÇÃO traiu o desenho. Zero mudança de mecanismo — se o agente não gravar e o % cruzar, a rede (resume cirúrgico D8-3) garante o bilhete. |
| **G3** | Atlas: "A skill `distillation` não está disponível nesta sessão" | run B, 1ª mensagem RESULT | Ponta do iceberg: **agente codex não recebe NEM carta NEM skills.** Tudo materializa em `.claude/` (`SkillMaterializer.java:34` → `.claude/skills/`; `AdapterRunExecutor.java:186` → `.claude/CLAUDE.md`); o codex lê `AGENTS.md` — **zero menção a AGENTS.md no backend inteiro**. O Atlas roda "pelado": sem memória da empresa (ADR-0018/0019) e sem SkillSet. | **DIVERGÊNCIA 🔴 nova no roadmap + FATIA DEDICADA:** o adapter declara sua convenção de workspace (claude → `.claude/`; codex → `AGENTS.md` espelhando carta + skills), como já declara o `EngineContainer`. Racional: é mudança de seam (TDD próprio), não ajuste de texto; sem ela o "motor duplo" (ADR-0024) executa mas não lembra nem sabe. Para a destilação em si: o **template vai inline** na instrução reescrita (G2) — funciona nos 2 motores. |
| **G4** | ~1min e 19 eventos para "quem é você"; `git status` em workspace sem git; `ERROR codex_core::tools::router` no STDOUT (run A) | rastro dos runs A/B | Consequência de G1+G2 (~12 dos 19 eventos). git status/ERROR = comportamento interno do CLI, informativo. | **ACEITAR + RE-MEDIR pós-fix:** repetir o mesmo turno trivial após G1/G2 e comparar (expectativa ~4 eventos). Só vira investigação própria se sobrar dor. NÃO filtrar o rastro (ADR-0010: profundidade a 1 clique é auditoria). |
| **G5** | Apontar "qual task deu problema" exige colar UUID | pedido do dono (caso B) | Issues só têm UUID (V1). | **FATIA PRÓPRIA (antes do caso B):** número sequencial **por company** na Issue (#N), migração + exibição na UI e no chat. Racional: pequena mas tem migração+UI (não pega carona no hotfix); é pré-requisito prático do caso B; sequência por company porque a company é a fronteira de isolamento. |
| **G6** | Refinamentos do caso A e o fluxo B sem endereço | §A acima | Fatia 2 (ADR-0031) não previu: racional-de-contratação de volta ao dono; manipular motores no menu do hire. Caso B não existia em nenhum plano. | **RE-GRELHADO em sessão própria (2026-07-09, a pedido do dono — "para não virar algo completamente diferente do que pedi").** Resultado: decisões **A1–A5 + B1–B2** abaixo (§B.2), anexadas como **§ADENDO VINCULANTE** no `handoff-grilling-3-ceos-personas-fechado.md`. Caso B segue fatia nova — mas o contrato de intenção já está fechado (B1/B2), o grill futuro é só de mecanismo. |

## §B.2 — Os fluxos do dono, grelhados um a um (2026-07-09; releitura validada por ele antes)

| # | Decisão | Racional |
|---|---------|----------|
| **A1** | UM dossiê `.md` por business, **APENDADO a cada turno** do grill; o "sim" do dono só CARIMBA (emenda o D6 da Fatia 2, que escrevia o dossiê no final). | Janela pode estourar no meio do grill — apendado, nada se perde (o handoff da ADR-0030 retoma pelo próprio dossiê); e preserva as palavras EXATAS do dono, não um resumo. |
| **A2** | O Executor **PROTOCOLA sem mexer** no pedido do Planejador; buraco = kickback `needs-grilling`; autonomia dele começa PÓS-approve (contratar, atribuir skills, entregar briefs). | O racional que o dono leu precisa bater 1:1 com o pedido aprovado; preserva "o Planejador produz a estratégia" (D12). |
| **A3** | Racional da escolha **nos dois lugares**: legível na CONVERSA (Questionador, citando o dossiê, ANTES do menu) + estruturado POR-AGENTE no menu (skills + motor + porquê). | A conversa é onde o dono vive (balcão único D2) e onde decide SE vai ao menu; o menu precisa do por-agente para a Decisão registrada no approve. |
| **A4** | No menu do hire: **skills SOMENTE-LEITURA; MOTOR livremente editável** pelo dono. **(Palavras dele, divergiu da recomendação.)** | Racional DELE: o CEO tem viés de motor — CEO claude sempre proporá agentes Anthropic, CEO codex sempre codex; a mão soberana no motor é obrigatória. |
| **A5** | Skill errada **NÃO trava o gate** — dois caminhos: editar a skill do agente já contratado (pin por-agente existente) **e/ou** avisar o Questionador na conversa ("agente X tem skill que não se encaixa"). **(Palavras dele.)** | Skill é corrigível depois sem segurar a contratação; a conversa roteia a correção pelas personas. |
| **B1** | Racional do CEO no caso B = **ROTEAMENTO** (time/Arquiteto responsável + task #N), registrado como Decisão + confirmação ao dono na conversa. O Arquiteto recebe **as palavras do dono VERBATIM** + a referência #N — nunca a paráfrase do CEO. | O valor do "limpo" é zero telefone-sem-fio no conteúdo; o valor do racional é auditabilidade de por que foi para onde foi. Análise do CEO junto ao payload ANCORARIA o Arquiteto na interpretação, não no dito. |
| **B2** | A correção nasce como **task corretiva NOVA ligada à #N**; o Arquiteto a autora (ADR-0017 — arquiteto é a fonte das tasks); a #N (DONE) mantém o histórico intacto. | Reabrir mistura "o que foi entregue" com "o que foi corrigido"; o REOPEN de hoje é para task BLOQUEADA, não DONE; a ligação torna reincidência visível. |

## §C — Decisões EM ABERTO (o dono dispensou a pergunta; aguardar a palavra dele)

1. **PR do fix codex reply** — branch `fix/codex-reply-final-agent-message`, mudanças NÃO
   commitadas (parser + 3 testes + roadmap), 428 verdes. Abrir/mergear **só com OK explícito**.
2. **Ordem de prioridade** entre: hotfix do prompt (G1+G2) · fatia AGENTS.md (G3) · Fatia 2
   (3 personas + refinamentos A) · fatia ID numérico (G5) · caso B. Recomendação apresentada
   (hotfix → AGENTS.md → Fatia 2 → ID → caso B; racional-chave: as skills das personas da Fatia 2
   não chegam ao codex sem G3) — **não aprovada nem rejeitada**.

## §D — Fatos de ambiente descobertos nesta sessão

- **PR #1 (loop de handoff, ADR-0030) está MERGED** (2026-07-08) — memória anterior dizia "não merged"; corrigida.
- A `main` já contém o `composePrompt` com a instrução M5 ⟹ o hotfix G1+G2 nasce de branch própria
  a partir da `main`, independente do fix codex (arquivos diferentes, zero conflito).
- `:8080` segue com dial `context-threshold-percent=99` (gatilho off) — restaurar `=1` para testar o loop.
