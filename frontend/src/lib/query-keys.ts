// Registro CENTRAL de query keys (ADR-0013) — o contrato entre fetchers e
// invalidação. Toda query nova DEVE nascer aqui; nenhuma tela inventa chave.
// Shape: tudo de uma company vive sob o prefixo ["company", companyId] —
// invalidar o escopo inteiro (ex.: troca de company, mutação larga) é um
// invalidateQueries({ queryKey: queryKeys.companyScope(id) }).

export const queryKeys = {
  /** Global (fora de company): o picker da raiz e o switcher. */
  companies: () => ["companies"] as const,

  /** Prefixo de invalidação em bloco de uma company. */
  companyScope: (companyId: string) => ["company", companyId] as const,

  missionControl: (companyId: string) => ["company", companyId, "mission-control"] as const,

  activity: (companyId: string) => ["company", companyId, "activity"] as const,

  issues: (companyId: string) => ["company", companyId, "issues"] as const,

  issue: (companyId: string, issueId: string) =>
    ["company", companyId, "issues", issueId] as const,

  issueRuns: (companyId: string, issueId: string) =>
    ["company", companyId, "issues", issueId, "runs"] as const,

  runSteps: (companyId: string, runId: string) =>
    ["company", companyId, "runs", runId, "steps"] as const,

  reviews: (companyId: string, status: string) =>
    ["company", companyId, "reviews", status] as const,

  agents: (companyId: string) => ["company", companyId, "agents"] as const,

  agentRuns: (companyId: string, agentId: string) =>
    ["company", companyId, "agents", agentId, "runs"] as const,

  agentSkills: (companyId: string, agentId: string) =>
    ["company", companyId, "agents", agentId, "skills"] as const,

  /** Catálogo de motores (global no backend, mas cache por-company — a rota é company-scoped). */
  engines: (companyId: string) => ["company", companyId, "engines"] as const,

  /** Times da company (T.2, ADR-0017) — o organograma e a trava do arquiteto leem daqui. */
  teams: (companyId: string) => ["company", companyId, "teams"] as const,

  /** Catálogo de moldes/role-templates (global no backend, cache por-company — rota company-scoped). */
  roleTemplates: (companyId: string) => ["company", companyId, "role-templates"] as const,

  /**
   * Savelog de um molde (GET /versions — o catálogo é global; a company na chave é a LENTE da rota).
   * Vive SOB o prefixo do catálogo de propósito: a invalidação em bloco dos dialogs
   * (queryKey[2] === "role-templates") refaz catálogo E savelogs de uma vez.
   */
  roleTemplateSavelog: (companyId: string, templateId: string) =>
    ["company", companyId, "role-templates", templateId, "versions"] as const,

  /** Chats por assunto da company (T.3, ADR-0018) — as abas da coluna do CEO. */
  conversations: (companyId: string) => ["company", companyId, "conversations"] as const,

  /** A thread de um chat (turnos) — poll de 2s só na aba ativa. */
  conversationTurns: (companyId: string, conversationId: string) =>
    ["company", companyId, "conversations", conversationId, "turns"] as const,

  /**
   * A fila de approvals em duas PROJEÇÕES da mesma fonte (mockup §4): por
   * status (o badge) e ancorada por conversa (os cards na thread). As duas
   * vivem sob ["company", id, "approvals"] — decidir invalida o prefixo
   * inteiro e as duas projeções se refazem juntas.
   */
  approvalsQueue: (companyId: string, status: string) =>
    ["company", companyId, "approvals", "status", status] as const,

  approvalsAnchored: (companyId: string, conversationId: string) =>
    ["company", companyId, "approvals", "conversation", conversationId] as const,

  /** Prefixo de invalidação em bloco das duas projeções da fila. */
  approvalsScope: (companyId: string) => ["company", companyId, "approvals"] as const,

  /** A carta corrente (ADR-0019) — invalida quando um APPROVE de direção consolida. */
  carta: (companyId: string) => ["company", companyId, "memory", "carta"] as const,

  /** Report do Ledger por-Issue — sob o prefixo de issues (invalidação em bloco). */
  issueReport: (companyId: string, issueId: string) =>
    ["company", companyId, "issues", issueId, "report"] as const,

  /** Versões de preview da Issue (F.9/ADR-0021) — sob o prefixo de issues (invalidação em bloco). */
  issuePreviews: (companyId: string, issueId: string) =>
    ["company", companyId, "issues", issueId, "previews"] as const,

  /** Conteúdo de UMA versão de preview — imutável por id (staleTime Infinity). */
  previewContent: (companyId: string, artifactId: string) =>
    ["company", companyId, "preview-artifacts", artifactId, "content"] as const,

  /** Status do servidor de preview da Issue (F.10/ADR-0023) — manifest vigente + vivo. */
  previewServer: (companyId: string, issueId: string) =>
    ["company", companyId, "issues", issueId, "preview-server"] as const,

  /** A tela Servidores (F.10/ADR-0023): vivos + candidatas da company. */
  previewServers: (companyId: string) =>
    ["company", companyId, "preview-servers"] as const,

  /** Versões do thumbnail do servidor da Issue (F.13/ADR-0027) — sob o prefixo de issues. */
  previewServerThumbnails: (companyId: string, issueId: string) =>
    ["company", companyId, "issues", issueId, "preview-server-thumbnails"] as const,

  /** Os bytes de UM thumbnail — imutável por id (staleTime Infinity), espelho do previewContent. */
  previewServerThumbnailContent: (companyId: string, thumbnailId: string) =>
    ["company", companyId, "preview-server-thumbnails", thumbnailId, "content"] as const,
} as const;
