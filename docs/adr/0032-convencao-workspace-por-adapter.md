# ADR-0032: Convenção de workspace por adapter — o codex recebe carta + skills pelos canais nativos (G3)

**Status:** Aceito (2026-07-10) · grill fechado em 2026-07-09 (`handoff-grilling-agents-md-codex.md`, D1–D10)

## Contexto
O "motor duplo" (ADR-0024) executava mas **não lembrava nem sabia**: carta e skills materializavam SÓ
em `.claude/` (layout do Claude Code — ADR-0007/0018/0022), que o codex **não lê**. Achado AO VIVO
(Atlas, run B: *"A skill `distillation` não está disponível"*); divergência 🔴 no inventário. Sondas
no codex real (0.142.5, o pinado nas imagens) provaram **dois canais nativos**: (S1/S2) o
`AGENTS.md` da **raiz** do workspace é lido como project doc (escopo = árvore inteira) e
`<workspace>/.codex/skills/<chave>/SKILL.md` é **descoberta por-projeto nativa** com progressive
disclosure — **a mesma forma exata** da árvore `.claude/skills/`. (S3) O workspace real do Atlas
tinha o espelho `.claude/` inteiro materializado onde o codex nunca leu — e o codex **explora por
busca**: cérebro velho no chão é divergência silenciosa armada.

## Decisão

### 1. O seam: `AgentAdapter.workspaceConvention()` — record de DADOS, default na interface
`WorkspaceConvention(memoryFile, skillsSubdir)` com fábricas `claude()` (`.claude/CLAUDE.md` ·
`.claude/skills`) e `codex()` (`AGENTS.md` · `.codex/skills`) + `KNOWN` (a união da faxina).
**Default = `claude()`**: ClaudeCodeAdapter e ProcessAgentAdapter não mudam nenhuma linha; só o
CodexAdapter sobrescreve. Espelho fiel do exemplar `EngineContainer` (F.8/ADR-0016): **o adapter
declara dados; quem faz o trabalho são os consumidores** — o executor (subtrees + ordem + faxina) e
os 2 materializers (caminho por parâmetro). O adapter nunca faz I/O.

### 2. Forma do espelho = NATIVA — skills vão AO LADO, não DENTRO
Carta → `AGENTS.md` raiz, corpo **VERBATIM** (irmão exato do `.claude/CLAUDE.md`); skills →
`.codex/skills/<chave>/SKILL.md` (o `SkillMaterializer` reusado mudando o prefixo). **Nada de skills
inline nem índice no AGENTS.md**: o cap `project_doc_max_bytes` do CLI truncaria em silêncio (S1).

### 3. Contrato de re-materialização = o MESMO de hoje
Idempotente, por-Run, **reescreve-ou-remove** (sem carta → `AGENTS.md` removido — nunca servir carta
velha). `AGENTS.md` **só em run de TURNO** (espelho do desenho ADR-0018: carta = memória do CEO em
conversa); run de Issue = só skills — um `AGENTS.md`/`CLAUDE.md` que o **agente** escreveu no próprio
workspace de Issue nunca é tocado (ADR-0022 §4 atravessa a convenção).

### 4. Faxina permanente: a convenção ativa escreve, as inativas SOMEM
Todo Run materializa pela convenção ATIVA e **apaga do host os caminhos das INATIVAS**
(`KNOWN − ativa`; skills em todo run, carta só em turno), **podando os pais esvaziados** (um
`.claude/` vazio no chão ainda é chão sujo — critério literal do §VENDO; pai com conteúdo do
agente fica). No DOCKER a remoção atravessa **de graça**:
o executor lista a **UNIÃO** das convenções no `writeWorkspaceFiles` e o replace-por-subtree
(ADR-0022 §2) remove do volume o que não existe no host — `rm` extra é no-op em volume limpo.
`.orq/*` **NUNCA** entra na união (risco #3 da ADR-0030; guard-rail de teste vigia).

### 5. De carona: fail-fast do adapter ANTES do checkout
`AdapterRegistry.forType` (lookup puro) sobe para antes da materialização — tipo desconhecido falha
com a Issue ainda **livre** (antes falharia com o lock preso por um run morto).

### 6. `distillation` entra pelo pipe único — zero caso especial
O `DistillationSkill.augment` injeta antes da materialização; com o materializer paramétrico ela cai
em `.codex/skills/distillation/SKILL.md` sozinha. Foi ela que denunciou o buraco; agora existe nos
2 motores. Prompt do turno intocado (template inline G2 segue o contrato primário).

### 7. `handoff/` NÃO muda (P6 validado)
Continuação fresca passa pelo executor (re-materializa o espelho); o **resume-cirúrgico** executa o
adapter direto e **herda o volume já materializado pelo turno disparador** — o espelho já está lá.

## Alternativas rejeitadas
- **If/else de tipo no executor** — reabriria o executor a cada motor novo (contra ADR-0004; o seam
  declarativo é o mesmo molde que o `EngineContainer` já provou).
- **Objeto-com-comportamento (a convenção materializa)** — moveria I/O para o pacote adapter sem
  ganho; a composição é trivial (copiar carta + copiar skills) e os materializers já existem.
- **Skills inline no AGENTS.md** — truncamento silencioso pelo `project_doc_max_bytes` (S1); e o
  canal nativo `.codex/skills/` tem descoberta com progressive disclosure (S2).
- **Faxina só na troca de motor (evento)** — exigiria detectar a troca; a faxina por-Run é
  idempotente, custa um `rm` no-op e cobre também o legado já sujo (S3).
- **Sincronizar a raiz inteira** — nukaria estado legítimo do agente; a união espelha SÓ os
  contratos dos materializers (mesmo argumento do ADR-0022 §4).

## Ressalvas honestas
- **`AGENTS.md` na raiz é território compartilhado**: um agente codex pode escrever no próprio
  `AGENTS.md` durante uma Issue (é o canal dele) — em run de Issue o pipeline preserva; no próximo
  TURNO o replace sobrescreve com a carta corrente. Aceito: é o mesmo trade-off do `.claude/CLAUDE.md`.
- **Convenção nova = tocar `KNOWN`** (1 linha + o adapter): a união e a faxina derivam dela.
- A prova viva (VENDO com Docker) usa a credencial `orq_codex_creds` — ausente, o operador roda
  `infra/engines/setup-codex.sh` (ADR-0016).

## Relaciona
ADR-0004 (BYOA — o seam vive no adapter) · ADR-0016 (EngineContainer, o exemplar do molde) ·
ADR-0022 (replace-por-subtree — a faxina DOCKER de graça) · ADR-0018/0019 (carta) · ADR-0007
(layout de skills) · ADR-0030 (guard `.orq`; resume-cirúrgico herda o volume) · ADR-0024 (o motor
duplo que rodava pelado) · ADR-0031 (reservada: Fatia 2/personas — as skills delas chegam ao codex
por ESTE seam).
