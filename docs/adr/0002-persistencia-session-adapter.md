# ADR-0002: Persistência da Session do adapter (híbrida)

**Status:** Aceito (2026-06-30)

## Contexto
No modelo Docker-first, o **Workspace** (arquivos) mora num volume nomeado. Mas a **Session do adapter** — o handle de retomada do CLI do agente (ex.: `session-id` do Claude Code, transcript, contadores) — precisa atravessar heartbeats para o agente não recomeçar do zero. Onde ela mora?

## Decisão
**Híbrido.** O *ponteiro durável* no banco; o *estado pesado* no volume.
- Tabela `agent_session` (chaveada por Agent + Issue): tipo de adapter, `external_session_id`, status, custo/tokens acumulados, último Run, `lease`.
- O **Workspace** (volume) guarda transcript/arquivos/cache do CLI.
- `AgentAdapter.execute(ctx)` recebe o handle anterior (do banco) + o Workspace montado, e devolve o handle atualizado.

## Alternativas consideradas
- **Só no volume:** simples, mas opaco, acoplado ao volume e perde a sessão se o volume sumir.
- **Só no banco:** máxima portabilidade/multi-nó, mas exige modelar todo o estado de filesystem da sessão — custo alto agora.

## Consequências
- Sessão portável e queryável (custo, status) mesmo se o volume for recriado.
- Espelha o `agent_task_session` do Paperclip + o padrão "blobs como ponteiros" (análise, report 03/05).
- Define o contrato do `AgentAdapter.execute()` (handle in → handle out) — ver próxima ramificação do grilling.

## Revisão 2026-07-02 (grilling motores, ADR-0014)
- O v1 de motor **não ativa** o `--resume` nativo: `priorSession` segue `null`, cada Run é **fresh**. O adapter
  rico **captura** o handle nativo (`session_id` do `claude` / `thread_id` do `codex` → `Result.session`) mas
  **não o persiste** ainda (fiação pronta, feature desligada).
- A estratégia de continuidade é **reorientada** pela regra de ouro do handoff (ADR-0015): a continuidade
  preferida é **destilação (handoff a 50-60% da janela de contexto)**, não recarga bruta de sessão. O "handle em
  DB + estado no volume" desta ADR continua válido como forma-alvo, mas é **secundário** ao handoff — fica para a
  fatia de sessão dedicada.

## Revisão 2026-07-04 (frente Teams — ADR-0018)
- `agent_session` **generaliza** de `(Agent+Issue)` para **`(Agent+Conversation)`** — o CEO conversacional tem
  sessão por-**Conversation** (assunto), não por-Issue. `agent_session += conversation_id`.
- O `--resume` fica **religável por-Conversation** mas **desligado no v1** (a continuidade vem da **carta** +
  recorte do log por turno; ADR-0018/A.8, ADR-0019). Reafirma esta ADR: destilação/handoff > recarga bruta.
- **Implementado em T.3.a:** tabela `agent_session` criada (V16, chave `agent+conversation`), gravada
  **WRITE-ONLY** no settle de cada Run de turno (`AgentSessionStore`; handle nulo — ex.: process — não cria
  linha). Religar o `--resume` = ler daqui e preencher `priorSession` no Context. Status/custo/lease da
  forma-alvo continuam de fora até existir leitor.

## Revisão 2026-07-07 (loop de handoff — ADR-0030)
- Surge o **1º leitor de produção** do `external_session_id` (`AgentSessionStore.currentHandle`): a **rede
  resume-cirúrgico** do handoff ([ADR-0030](0030-loop-handoff-contexto.md), D6/M9). É uma **exceção escopada**
  ao "`--resume` desligado" desta ADR: religa a MESMA sessão UMA vez, terminal, só para DESTILAR o bilhete, e
  DESCARTA a sessão. `--resume` para **CONTINUAR** o trabalho segue PROIBIDO (a janela cresceria); só o
  destilar-e-encerrar o liga. `ClaudeArgv`/`CodexArgv` emitem `--resume <handle>` apenas quando `priorSession`
  é preenchido — todo run normal ainda passa `null`.

## Revisão 2026-07-11 (continuidade nativa — ADR-0038)
- O `--resume` **desligado no v1** desta ADR é **SUPERSEDIDO**: religa no **caminho normal** dos Runs
  (turno de conversa, F-N1; run de Issue, F-N2), lendo `AgentSessionStore.currentHandle` como
  `priorSession`. A exceção resume-cirúrgico do ADR-0030 vira caso particular da rede, não mais o
  único leitor. O handle segue **write-then-read** por `(agent, conversation)`; o executor agora
  também GRAVA em run de Issue (via `issue.conversation_id`, V27 — escopo por Issue, sem migração).
- Semântica de retry firmada: handle salvo = último gravado COM SUCESSO (`Result.fail`→`session=null`
  →`record()` no-op); Run que falha antes de gravar preserva o handle anterior. Racional na ADR-0038.
