package com.orquestrador.activitylog;

/**
 * Vocabulário fechado de ações mutantes auditáveis. Cresce por fatia conforme novas ações governadas
 * são criadas (ex.: fatia 7 usa SKILL_INSTALLED/SKILL_ATTACHED). Ação delegada (agente pede, board
 * aprova) = UM evento com ator=aprovador e detail.requested_by_agent (ADR-0009).
 */
public enum ActivityAction {
    SKILL_IMPORTED,
    SKILL_INSTALLED,
    SKILL_ATTACHED,
    // Recovery de Issue travada por Run FAILED (ADR-0011): estacionar/destravar.
    ISSUE_BLOCKED,
    ISSUE_REOPENED,
    ISSUE_CANCELLED,
    // Review gate (ADR-0012). detail só com IDs (review_id/run_id); o decision_reason
    // (texto livre) vive na tabela review, NUNCA aqui (ADR-0009).
    ISSUE_SUBMITTED_FOR_REVIEW,
    REVIEW_APPROVED,
    REVIEW_REJECTED,
    // Approval pré-hoc (ADR-0018): o CEO propõe (ator=agente), o board decide (ator=aprovador,
    // detail.requestedByAgentId — ação delegada é UM evento). racional/decision_reason vivem na
    // tabela approval, NUNCA aqui (ADR-0009/0020).
    CEO_PROPOSED,
    APPROVAL_APPROVED,
    APPROVAL_REJECTED,
    // Preview real (F.9/ADR-0021). detail só com IDs/enum de motivo por FASE
    // (CAP_EXCEEDED/READ_FAILED/INVALID_CONTENT/PERSIST_FAILED — a auditoria nunca mente sobre a
    // fase que falhou); o texto do erro do leitor vai pro log do servidor, nunca aqui (ADR-0009).
    PREVIEW_CAPTURED,
    PREVIEW_CAPTURE_FAILED,
    // Preview processo/porta (F.10/ADR-0023). Declaracao no settle (ator=agente; motivo por fase:
    // CAP_EXCEEDED/READ_FAILED/INVALID_MANIFEST/PERSIST_FAILED) e ciclo de vida por acao do
    // operador (ator=board). detail so com IDs/enum; texto de erro vai pro log (ADR-0009).
    PREVIEW_SERVER_DECLARED,
    PREVIEW_SERVER_DECLARATION_FAILED,
    PREVIEW_SERVER_STARTED,
    PREVIEW_SERVER_START_FAILED,
    PREVIEW_SERVER_STOPPED,
    // GC/retencao de workspaces (F.12/ADR-0026): o WorkspaceReaper coletou o workspace por-Issue de
    // uma Issue terminal vencida. Ator = SYSTEM (ato do pipeline); detail so-IDs (target + ref do
    // workspace; o issueId e o entity_id — ADR-0009). Gravado APOS a remocao REAL (so REMOVED audita).
    WORKSPACE_COLLECTED,
    // Thumbnail do preview v2 (F.13/ADR-0027): o shot NAO conseguiu fotografar o servidor vivo.
    // Ator = SYSTEM (ato do pipeline, como o WorkspaceReaper — o shot e observacao do pipeline, nao
    // do board/agente); detail so-IDs/enum (runId + reason: UNREACHABLE/RENDER_FAILED/CAP_EXCEEDED/
    // PERSIST_FAILED/CAPTURE_ERROR). SO a falha vira evento — a ROW do thumbnail e o registro do
    // sucesso (como a foto v1, ADR-0021; nunca um "capturado" redundante).
    THUMBNAIL_CAPTURE_FAILED,
    // Cap+graduacao das rows append-only (F.14/ADR-0028): o RowRetentionReaper coletou a cauda
    // superseded de Issues terminais vencidas. Ator = SYSTEM (ato do pipeline, como o
    // WorkspaceReaper); RESUMO por ciclo-de-sweep-por-company (nao 1 por row — seria diluvio);
    // detail so-contagens {thumbnail, artifact, run_events} (numeros nao sao PII, ADR-0009). O
    // vigente por-Issue + o fixado por review NAO graduam (B3); gravado APOS a remocao REAL.
    ROWS_COLLECTED,
    // Absorcao de issue do board (ADR-0037): o Planejador triou a issue orfa do dono e o sistema
    // resolveu time→arquiteto e atribuiu. Ator = AGENTE (o Planejador do turno); detail so-IDs
    // {teamId, arquitetoAgentId} — o racional (texto de LLM) vive na Decision, NUNCA aqui (ADR-0009).
    ISSUE_ABSORVIDA,
    // Pedido de skill do EXECUTOR de uma Issue (F-AT1, grill agente-como-tool): o bloco
    // `pedido-skill` na saida do Run virou Approval PENDING. Ator = AGENTE (quem pediu);
    // detail.approvalId + requestedByAgentId (ADR-0009) — a decisao do dono segue o padrao
    // generico (APPROVAL_APPROVED/APPROVAL_REJECTED); o attach real emite SKILL_ATTACHED (ja
    // existente) no dispatch do APPROVE.
    SKILL_INSTALL_REQUESTED
}
