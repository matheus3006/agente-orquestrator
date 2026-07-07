# ADR-0009: Modelo do Activity Log

**Status:** Aceito (2026-06-30, sessão grilling; embasado em deep-research)

## Contexto
Registrar toda ação mutante é invariante (#5). O install/attach de skills (fatia 7) já precisa do log. Duas decisões de modelagem: (a) como guardar detalhe variável por ação sem virar dump de PII, e (b) como registrar ações **delegadas** (agente pede, board aprova).

## Decisão
- **Schema híbrido:** as colunas tipadas atuais (`actor_type`/`actor_id`/`action`/`entity_type`/`entity_id`) **+** `detail jsonb` (nullable). O `detail` carrega **só IDs/enums/números** — nunca body, segredo ou PII (mascarar na ingestão).
- **Ação delegada = UM evento** (ator = o **aprovador**; `detail.requested_by_agent`, `detail.approval_id`), não dois. A decisão de aprovação é gravada **contra o registro da ação** (padrão CADF/CloudTrail; hoop.dev).
- **Imutabilidade no banco:** o role da app só faz `INSERT`; trigger `BEFORE UPDATE OR DELETE` que erra. **Guard Postgres-only** (H2 não força — mesmo padrão do índice único parcial já usado).
- **Writer = service explícito** (`ActivityLogService.record(...)` + enum de ações). **Não** Hibernate Envers (Envers versiona estado de entidade, não eventos semânticos).
- **Hash-chaining adiado** (over-engineering para solo single-node; reservar para requisito de compliance).

## Alternativas consideradas
- **Só colunas tipadas:** não acomoda o detalhe variável por ação.
- **Só um blob JSON:** perde consultabilidade das dimensões comuns; e o FUD anti-JSONB (2000×, TOAST tax, sem estatística) foi **refutado** na verificação adversarial para a nossa escala.
- **Dois eventos para ação delegada:** duplica e desacopla a decisão da ação.
- **Hibernate Envers:** versiona estado de entidade, não a semântica "quem fez o quê".

## Consequências
- Auditoria consultável (colunas) + flexível (`detail`) sem vazar PII.
- Imutabilidade real em produção (Postgres); nos testes H2 é convenção (não forçada) — o writer é INSERT-only por design.
- Fontes: CADF, AWS CloudTrail, hoop.dev, OWASP LLM07, guias de PII/GDPR logging.

## Revisão 2026-07-02 (review gate × approval_id)
- **Review NÃO é ação delegada** (o sistema impôs o gate; o agente não pediu nada). Os eventos de review
  (`ISSUE_SUBMITTED_FOR_REVIEW`/`REVIEW_APPROVED`/`REVIEW_REJECTED`) referenciam `entity=issue` e carregam
  `detail.review_id`/`detail.run_id` (**só IDs**).
- O **`decision_reason` (texto livre) fica na tabela `review`, NUNCA no `detail`** (regra PII desta ADR).
- O slot **`approval_id`** do padrão de ação delegada segue **reservado** para o agente-como-tool (a
  Approval-de-ação genérica, adiada — ADR-0012).

## Revisão 2026-07-04 (frente Teams — ADR-0018/0020)
- O slot **`approval_id`** **ganha consumidor real**: a `approval` pré-hoc do CEO (ADR-0018). Ação proposta pelo
  CEO = **1 evento**, ator=aprovador, `detail.requested_by_agent` (só IDs — o padrão de ação delegada desta ADR).
- Novos `ActivityAction`: **`CEO_PROPOSED` · `APPROVAL_APPROVED` · `APPROVAL_REJECTED`**.
- O **racional** (ADR-0020) e o `decision_reason` ficam **na tabela `approval`/`decision`, NUNCA no `detail`**
  (mesma regra PII desta ADR — texto livre fora do log).
