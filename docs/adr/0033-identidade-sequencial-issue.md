# ADR-0033: Identidade sequencial de Issue por company (#N) — humano fala número, máquina fala UUID (G5)

**Status:** Aceito (2026-07-10) · grill fechado em 2026-07-10 (`handoff-grilling-id-sequencial-issue.md`, D1–D5) · execução pendente (migration **V23**)

## Contexto
Apontar "qual task deu problema" exigia colar UUID (achado do dono, G5 do
`handoff-grilling-insatisfacoes-funcionamento-ceo.md` §B): a Issue só tinha `id uuid` (V2), a UI
mostrava `shortId` de 8 chars como identidade pobre e o prompt do CEO listava issues **só por
título** (`LiveStateProjector` — duas tasks de título parecido eram indistinguíveis até para ele).
O **caso B** (melhoria de task que falhou; decisões B1/B2 vinculantes) pressupõe uma referência
curta e estável: *"a task N deu problema"* → task corretiva NOVA **ligada à #N**. Sequência **por
company** porque a company é a fronteira de isolamento (toda entidade de domínio carrega
`company_id`, invariante da V1).

## Decisão

### 1. O contrato do #N
`issue.seq` (int, `not null`): **por company · denso** (1, 2, 3… sem buraco — a alocação acontece
na MESMA tx do insert, rollback não queima número) **· imutável · nunca reusado enquanto Issue não
for deletável · começa em 1**. Regra de identidade transversal (palavras do dono): **"#N é o que eu
vejo, UUID é o id único para auditoria e debug"** — `#12` em toda superfície humana (board, feed,
review, servers, estado vivo do CEO); UUID permanece identidade de máquina (rotas `/issues/{uuid}`,
props-panel, FKs, activity_log só-IDs da ADR-0009 — o join id→#N é da projeção/render, nunca do log).

### 2. Alocação: trava na linha da company + MAX+1, unique total de backstop
No chokepoint único de criação (`IssueService.create` — TODOS os caminhos passam por ele, inclusive
o `CREATE_ISSUE` do approve): `CompanyRepository.findByIdForUpdate(companyId)`
(`@Lock(PESSIMISTIC_WRITE)`, cópia da receita do Agent — *"multi-no-safe e testavel no H2"*) →
`coalesce(max(seq),0) + 1` (idioma `maxVersion`: *"nunca count+1"*) → insert, tudo dentro da tx já
existente do `create`. Backstop: `uq_issue_company_seq unique (company_id, seq)` **total**
(anotada também na entidade ⇒ enforçada no H2 dos testes) — bug que fure a serialização vira erro,
nunca duplicata; e o índice é o lookup natural do caso B.

**Por que MAX+1 aqui exigiu trava quando os 5 ordinais existentes não travam nada:** todos os
precedentes têm serializador natural (a V20 declara: *"seguro porque os runs de uma Issue sao
serializados pelo execution_lock"*; carta/skills têm escritor único). Criação de Issue **não tem**
— dono na UI e approve do CEO correm em threads concorrentes. A trava da company É o serializador
que faltava; a V23 declara a precondição no molde honesto da V20.

### 3. Backfill retroativo na V23, coluna obrigatória desde o dia 1
`row_number() over (partition by company_id order by created_at, id)` numera o que existe (UUID
desempata timestamps idênticos); depois `not null` + unique. Contrato sem exceção: toda Issue tem
#N, nenhuma superfície trata "sem número". Contexto que zera o risco: banco de desenvolvimento
(será zerado antes do uso real), nenhuma organização real do dono, ninguém referencia por número
hoje.

### 4. #N no contexto do CEO: 1 linha; instruções intocadas
`LiveStateProjector` → `- #12 [BLOCKED] Landing page — Feynman`. O exemplo ensina a notação (modelo
espelha o formato que lê); a seção de instruções do turno — recém provada ao vivo (G1+G2) — não é
reaberta. O cap de 30 issues permanece: dar ao CEO **busca** de qualquer #N é mecanismo do caso B.

## Condição de migração registrada (a mina desativada)
O "nunca reusado" do MAX+1 vale porque **não existe caminho que delete uma Issue** (o
RowRetentionReaper preserva a linha; FKs de preview/review/run a tornam praticamente indelével).
**Se um dia surgir delete de Issue, a fatia responsável DEVE migrar a alocação para contador
persistido** (ex.: `company.last_issue_seq`) — senão deletar a #12 faria a próxima nascer #12 de
novo e o histórico "task corretiva ligada à #12" (B2) ficaria ambíguo.

## Alternativas rejeitadas
- **Contador persistido na company já agora** — estado novo e mutável numa tabela que hoje é só
  identidade, para blindar um cenário (delete) que não existe; a condição acima cobre o futuro.
- **Só a unique, sem serializar** — sob rajada (Fatia 2 criará várias tasks de uma vez) um dos
  criadores leva erro e a task não nasce; criação falhando aleatoriamente é inaceitável.
- **Alocar fora da tx com retry-once (padrão do preview)** — quebraria a atomicidade insert+número;
  o retry do preview só funciona porque roda FORA de tx, e o `create` é `@Transactional`.
- **Sequence do Postgres por company** — DDL dinâmico por company; sem precedente no repo (zero
  sequences; PKs são UUID de aplicação); e sequence tem buraco em rollback (fere o "denso").
- **Número global (não por company)** — a company veria #7, #19, #23… fere o pedido de origem e a
  fronteira de isolamento.

## Relaciona
ADR-0009 (activity_log só-IDs — o #N entra no render, nunca no log) · ADR-0012/0011 (review/recovery
— superfícies que passam a exibir #N) · ADR-0018 (estado vivo no prompt do CEO — onde o #N fecha o
ciclo do chat) · ADR-0021 (V20 `preview_artifact.version` — o precedente de ordinal cuja precondição
declarada inspirou o desenho) · caso B futuro (B1/B2 do adendo vinculante — o consumidor do #N).
