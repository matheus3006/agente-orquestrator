# Fixtures REAIS para o protótipo P.1

Gravadas na F.0 (2026-07-02) contra o backend real (Postgres 16) com agentes echo executados
de verdade via wakeup → dispatcher → ProcessAgentAdapter. São respostas cruas dos 4 endpoints
board-only da F.0 — use-as como dados do protótipo (o movimento aprovado renderiza ESTES shapes).

| Arquivo | Endpoint | Momento |
|---|---|---|
| `mission-control-running.json` | `GET /api/companies/{id}/mission-control` | Atlas RUNNING no meio do run (mini-terminal com 5 steps), Nix/Echo IDLE |
| `mission-control-settled.json` | idem | Atlas IDLE com rastro de sucesso, Nix BLOCKED (2 falhas), Echo IDLE sem histórico |
| `steps-page-inicial.json` | `GET .../runs/{runId}/steps?offset=0` | primeira página do transcript (run ainda RUNNING) |
| `steps-delta-incremental.json` | `GET .../steps?offset=6` | o DELTA do poll de 2s (só o que chegou depois) |
| `steps-transcript-completo.json` | `GET .../steps?offset=0` | transcript inteiro pós-término (`runStatus: SUCCEEDED`, 12 steps) |
| `issue-runs-ledger.json` | `GET .../issues/{issueId}/runs` | 2 tentativas FAILED da issue do Nix (mais recente primeiro) |
| `activity-feed.json` | `GET .../activity?limit=50` | ISSUE_BLOCKED → ISSUE_REOPENED → ISSUE_BLOCKED, com atores |

Notas de shape:
- `status` do card: `RUNNING | QUEUED | BLOCKED | IDLE` (derivado no servidor).
- `lastSteps[*].detail` pode ser `null` (eventos de ciclo de vida: RUN_STARTED/RUN_SUCCEEDED/RUN_FAILED — estilize pelo `type`).
- `activeRun` é `null` fora de QUEUED/RUNNING; em QUEUED o `startedAt` é `null`.
- `nextOffset` do steps: repita-o no próximo poll; página vazia = sem novidade.
