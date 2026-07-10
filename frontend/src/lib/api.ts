// O fetcher ÚNICO do board (ADR-0013). Dois papéis load-bearing:
// 1. Auth v1: o X-Board-User entra AQUI e em nenhum outro lugar — trocar por
//    sessão/cookie no futuro toca este arquivo e nenhuma tela.
// 2. Erros: o formato {error, message} do ApiExceptionHandler (Spring) vira
//    ApiError tipado; telas decidem por status/error, nunca por parse ad-hoc.
//
// Base URL: no browser é same-origin (o rewrite /api → Spring do next.config
// mantém cookies futuros first-party); no servidor (layout por slug, prefetch)
// falamos direto com o Spring via BACKEND_ORIGIN.

import { recordApiLatency } from "./api-latency";

export class ApiError extends Error {
  readonly status: number;
  readonly error: string;

  constructor(status: number, error: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.error = error;
  }
}

type ApiFetchInit = Omit<RequestInit, "body"> & {
  body?: unknown;
  /**
   * Statuses de erro que são RESPOSTA de negócio, não falha: o corpo volta
   * parseado em vez de virar ApiError (o 422 do grill de Approval é retorno,
   * não exceção — espelha o DecideOutcome selado do backend, ADR-0020).
   */
  acceptStatuses?: number[];
};

function resolveBaseUrl(): string {
  if (typeof window !== "undefined") {
    return "";
  }
  return process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
}

export async function apiFetch<T = unknown>(path: string, init: ApiFetchInit = {}): Promise<T> {
  const { body, headers, ...rest } = init;
  const requestHeaders = new Headers(headers);
  requestHeaders.set("X-Board-User", process.env.NEXT_PUBLIC_DEV_BOARD_USER ?? "");
  if (body !== undefined) {
    requestHeaders.set("Content-Type", "application/json");
  }

  const startedAtMs = performance.now();
  const { acceptStatuses, ...fetchInit } = rest;
  const response = await fetch(`${resolveBaseUrl()}${path}`, {
    ...fetchInit,
    headers: requestHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (typeof window !== "undefined") {
    recordApiLatency(Math.round(performance.now() - startedAtMs));
  }

  if (!response.ok && !acceptStatuses?.includes(response.status)) {
    throw await toApiError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  const raw = await response.text();
  return (raw ? JSON.parse(raw) : undefined) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as { error?: string; message?: string };
    return new ApiError(
      response.status,
      body.error ?? "http_error",
      body.message ?? `HTTP ${response.status}`,
    );
  } catch {
    return new ApiError(response.status, "http_error", `HTTP ${response.status}`);
  }
}

/* ---------- tipos do domínio consumidos pelo board ---------- */

export interface Company {
  id: string;
  name: string;
  slug: string;
  createdAt?: string;
}

export function listCompanies(): Promise<Company[]> {
  return apiFetch<Company[]>("/api/companies");
}

/** Company por slug: o backend lista (slug é único, V1); o layout filtra. */
export async function findCompanyBySlug(slug: string): Promise<Company | undefined> {
  const companies = await listCompanies();
  return companies.find((company) => company.slug === slug);
}

/* ---------- superfície de observação do board (endpoints F.0) ---------- */

export type AgentBoardStatus = "RUNNING" | "QUEUED" | "BLOCKED" | "IDLE";

export type RunStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "RECOVERING" | "CANCELLED";

export interface MissionControlStep {
  runId: string;
  type: string;
  detail: string | null;
  createdAt: string;
}

export interface MissionControlAgent {
  agent: { id: string; name: string; role: string };
  status: AgentBoardStatus;
  /** null fora de QUEUED/RUNNING; em QUEUED o startedAt é null. */
  activeRun: {
    id: string;
    issueId: string | null;
    /** O #N da issue do run (ADR-0033) — o card mostra "executando #12 Título". */
    issueSeq: number | null;
    issueTitle: string | null;
    startedAt: string | null;
    attempt: number;
  } | null;
  /** ≤5, cronológicos; do run ativo OU do mais recente (card ocioso mantém o rastro). */
  lastSteps: MissionControlStep[];
  /** F.9 (ADR-0021 §5): a captura mais recente do agente — só o badge; null = sem preview. */
  lastPreview: {
    previewArtifactId: string;
    issueId: string;
    version: number;
    createdAt: string;
  } | null;
}

export interface MissionControlSnapshot {
  agents: MissionControlAgent[];
}

/** O JOIN no servidor: 1 poll de 2s = a tela inteira do Mission Control. */
export function getMissionControl(companyId: string): Promise<MissionControlSnapshot> {
  return apiFetch<MissionControlSnapshot>(`/api/companies/${companyId}/mission-control`);
}

export interface TranscriptStep {
  type: string;
  detail: string | null;
  createdAt: string;
}

export interface RunStepsPage {
  runId: string;
  runStatus: RunStatus;
  steps: TranscriptStep[];
  nextOffset: number;
}

/** Transcript incremental por offset de CONTAGEM (append-only ⇒ estável). */
export function getRunSteps(
  companyId: string,
  runId: string,
  offset: number,
  limit = 200,
): Promise<RunStepsPage> {
  return apiFetch<RunStepsPage>(
    `/api/companies/${companyId}/runs/${runId}/steps?offset=${offset}&limit=${limit}`,
  );
}

export interface ActivityEvent {
  id: string;
  actorType: string;
  actorId: string | null;
  action: string;
  entityType: string | null;
  entityId: string | null;
  /** JSON CRU do activity_log (só IDs, ADR-0009) — pode ser null. */
  detail: Record<string, unknown> | null;
  createdAt: string;
}

export function getActivity(companyId: string, limit = 50): Promise<ActivityEvent[]> {
  return apiFetch<ActivityEvent[]>(`/api/companies/${companyId}/activity?limit=${limit}`);
}

export interface IssueSummary {
  id: string;
  companyId: string;
  /** O #N humano da Issue (ADR-0033): por company, denso, começa em 1. UUID = máquina. */
  seq: number;
  title: string;
  description: string | null;
  status: string;
  riskLevel: number;
  assigneeAgentId: string | null;
  executionLockRunId: string | null;
  createdAt: string;
}

export function listIssues(companyId: string): Promise<IssueSummary[]> {
  return apiFetch<IssueSummary[]>(`/api/companies/${companyId}/issues`);
}

export interface NewIssueInput {
  title: string;
  description?: string;
  riskLevel: number;
}

export function createIssue(companyId: string, input: NewIssueInput): Promise<IssueSummary> {
  return apiFetch<IssueSummary>(`/api/companies/${companyId}/issues`, {
    method: "POST",
    body: input,
  });
}

export type UnblockDecision = "REOPEN" | "CANCEL";

/** Recovery de issue BLOCKED (ADR-0011): REOPEN mantém o assignee; CANCEL encerra. */
export function unblockIssue(
  companyId: string,
  issueId: string,
  decision: UnblockDecision,
): Promise<IssueSummary> {
  return apiFetch<IssueSummary>(`/api/companies/${companyId}/issues/${issueId}/unblock`, {
    method: "POST",
    body: { decision },
  });
}

/** Ledger de execuções da issue (F.0) — desc, a história completa. */
export interface IssueRun {
  id: string;
  agentId: string;
  issueId: string | null;
  status: RunStatus;
  attempt: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  lastError: string | null;
}

export function getIssueRuns(companyId: string, issueId: string): Promise<IssueRun[]> {
  return apiFetch<IssueRun[]>(`/api/companies/${companyId}/issues/${issueId}/runs`);
}

export interface AgentSummary {
  id: string;
  companyId: string;
  name: string;
  role: string;
  // Persona do CEO (ADR-0031): questionador/planejador/executor; null = agente comum.
  persona: string | null;
  reportsToId: string | null;
  adapterType: string;
  executionTarget: string;
  // Motor (ADR-0014): model/effort são String livre; null = default do plano.
  model: string | null;
  effort: string | null;
  // Molde pinado (ADR-0017): a versão de role-template copiada no hire; null = hire avulso.
  pinnedTemplateVersionId: string | null;
  // Time (ADR-0017, T.2): null = fora de time (dispatch livre — a trava do arquiteto é DO TIME).
  teamId: string | null;
  createdAt: string;
}

export function listAgents(companyId: string): Promise<AgentSummary[]> {
  return apiFetch<AgentSummary[]>(`/api/companies/${companyId}/agents`);
}

/* ---------- times + cadeia de comando (Teams T.2, ADR-0017) ---------- */

// Time = entidade real: domínio (macro) + foco (livre); arquiteto DESIGNADO (null = time ainda sem
// arquiteto → o DISPATCH trava com 409 — o canvas espelha esse estado, quem impõe é o backend).
export interface Team {
  id: string;
  companyId: string;
  dominio: string;
  foco: string;
  arquitetoAgentId: string | null;
  createdAt: string;
}

export function listTeams(companyId: string): Promise<Team[]> {
  return apiFetch<Team[]>(`/api/companies/${companyId}/teams`);
}

export function createTeam(companyId: string, dominio: string, foco: string): Promise<Team> {
  return apiFetch<Team>(`/api/companies/${companyId}/teams`, {
    method: "POST",
    body: { dominio, foco },
  });
}

/** Designa o arquiteto do time (null LIMPA o posto — o dispatch volta a travar). Board-only. */
export function setTeamArquiteto(
  companyId: string,
  teamId: string,
  agentId: string | null,
): Promise<Team> {
  return apiFetch<Team>(`/api/companies/${companyId}/teams/${teamId}/arquiteto`, {
    method: "PATCH",
    body: { agentId },
  });
}

/** Religa o líder direto do agente (null = vira raiz). Anti-ciclo no backend → 409. Board-only. */
export function reparentAgent(
  companyId: string,
  agentId: string,
  reportsToId: string | null,
): Promise<AgentSummary> {
  return apiFetch<AgentSummary>(`/api/companies/${companyId}/agents/${agentId}/reports-to`, {
    method: "PATCH",
    body: { reportsToId },
  });
}

/** Move o agente de time (null = sai). Sair do posto de arquiteto limpa o time antigo. Board-only. */
export function assignAgentTeam(
  companyId: string,
  agentId: string,
  teamId: string | null,
): Promise<AgentSummary> {
  return apiFetch<AgentSummary>(`/api/companies/${companyId}/agents/${agentId}/team`, {
    method: "PATCH",
    body: { teamId },
  });
}

/* ---------- motores / engine_catalog (F.7) ---------- */

// Uma combinação curada do catálogo (GET /engines, board-only). O `label` legível
// é o que a UI mostra; selecionar preenche os 3 eixos por baixo. É SUGESTÃO, não
// constraint — o adapter valida o modelo contra o CLI (ADR-0014/Q2).
export interface Engine {
  adapterType: string;
  model: string | null;
  effort: string | null;
  contextWindow: number | null;
  label: string;
}

export function listEngines(companyId: string): Promise<Engine[]> {
  return apiFetch<Engine[]>(`/api/companies/${companyId}/engines`);
}

// Promover o motor de um agente (PATCH, ADR-0014/Q6): manda o trio inteiro (o
// dropdown escolhe o motor por inteiro). Devolve o agente já com o motor novo.
export interface UpdateEngineInput {
  adapterType?: string;
  model?: string;
  effort?: string;
}

export function updateAgentEngine(
  companyId: string,
  agentId: string,
  input: UpdateEngineInput,
): Promise<AgentSummary> {
  return apiFetch<AgentSummary>(`/api/companies/${companyId}/agents/${agentId}`, {
    method: "PATCH",
    body: input,
  });
}

/* ---------- preview real (F.9, ADR-0021) ---------- */

// A lista devolve METADADOS (o content pesa até 2MB); o conteúdo sai por id,
// texto + nosniff (NUNCA text/html navegável), e só renderiza dentro do
// PreviewFrame (sandbox). Imutável por id → cache infinito no React Query.
export interface PreviewArtifactSummary {
  id: string;
  version: number;
  runId: string;
  agentId: string;
  contentHash: string;
  sizeBytes: number;
  createdAt: string;
}

/** Versões de preview da Issue, da mais nova para a mais antiga. */
export function listPreviewArtifacts(
  companyId: string,
  issueId: string,
): Promise<PreviewArtifactSummary[]> {
  return apiFetch<PreviewArtifactSummary[]>(
    `/api/companies/${companyId}/issues/${issueId}/preview-artifacts`,
  );
}

/**
 * O conteúdo de UMA versão — resposta TEXTO (não-JSON), por isso não passa pelo
 * apiFetch. Mesmo auth do fetcher central (o X-Board-User entra aqui e no
 * apiFetch, e em nenhum outro lugar); mesmo formato de erro.
 */
export async function fetchPreviewArtifactContent(
  companyId: string,
  artifactId: string,
): Promise<string> {
  const headers = new Headers();
  headers.set("X-Board-User", process.env.NEXT_PUBLIC_DEV_BOARD_USER ?? "");
  const response = await fetch(
    `${resolveBaseUrl()}/api/companies/${companyId}/preview-artifacts/${artifactId}/content`,
    { headers },
  );
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response.text();
}

/* ---------- servidor de preview (F.10, ADR-0023) ---------- */

// O board só orquestra o CICLO DE VIDA (start/stop/heartbeat/status); o app
// vivo é acessado DIRETO na porta do loopback pelo iframe (origin próprio,
// sem proxy — por isso nenhum fetcher de conteúdo aqui).

export interface PreviewServerManifestSummary {
  id: string;
  runId: string;
  command: string;
  port: number;
  createdAt: string;
}

export interface PreviewServerLiveSummary {
  url: string;
  hostPort: number;
  ttlRemainingSeconds: number;
}

export interface PreviewServerStatus {
  manifest: PreviewServerManifestSummary | null;
  live: PreviewServerLiveSummary | null;
}

export interface PreviewServerStartResult {
  started: boolean;
  url: string | null;
  hostPort: number | null;
  error: string | null;
}

export interface PreviewServerLiveEntry {
  serverName: string;
  issueId: string;
  /** O #N humano da issue (ADR-0033); null se a issue não resolver. */
  issueSeq: number | null;
  issueTitle: string | null;
  agentId: string;
  agentName: string | null;
  command: string | null;
  hostPort: number;
  url: string;
  ttlRemainingSeconds: number;
}

export interface PreviewServerCandidateEntry {
  issueId: string;
  /** O #N humano da issue (ADR-0033); null se a issue não resolver. */
  issueSeq: number | null;
  issueTitle: string | null;
  command: string;
  port: number;
  runId: string;
  createdAt: string;
  live: boolean;
}

export interface PreviewServersScreen {
  live: PreviewServerLiveEntry[];
  candidates: PreviewServerCandidateEntry[];
}

/** Declaração vigente + vivo (se houver) — status da Issue e do card de Review. */
export function fetchPreviewServerStatus(
  companyId: string,
  issueId: string,
): Promise<PreviewServerStatus> {
  return apiFetch<PreviewServerStatus>(
    `/api/companies/${companyId}/issues/${issueId}/preview-server`,
  );
}

/** Sobe (idempotente: vivo = reusa). started=false traz o motivo LEGÍVEL (com tail). */
export function startPreviewServer(
  companyId: string,
  issueId: string,
): Promise<PreviewServerStartResult> {
  return apiFetch<PreviewServerStartResult>(
    `/api/companies/${companyId}/issues/${issueId}/preview-server/start`,
    { method: "POST" },
  );
}

export function stopPreviewServer(
  companyId: string,
  issueId: string,
): Promise<{ stopped: boolean }> {
  return apiFetch<{ stopped: boolean }>(
    `/api/companies/${companyId}/issues/${issueId}/preview-server/stop`,
    { method: "POST" },
  );
}

/** O keepalive do frame montado (~60s): renova o TTL por inatividade. */
export function heartbeatPreviewServer(
  companyId: string,
  issueId: string,
): Promise<{ live: boolean }> {
  return apiFetch<{ live: boolean }>(
    `/api/companies/${companyId}/issues/${issueId}/preview-server/heartbeat`,
    { method: "POST" },
  );
}

/** A tela Servidores: vivos (matar/abrir) + candidatas (Issues com manifest). */
export function listPreviewServers(companyId: string): Promise<PreviewServersScreen> {
  return apiFetch<PreviewServersScreen>(`/api/companies/${companyId}/preview-servers`);
}

/* ---------- thumbnail do servidor de preview (F.13, ADR-0027) ---------- */

// O PNG DURÁVEL do app v2 — fotografado pelo shot enquanto o servidor estava
// vivo (janela pré-GC), guardado em bytea por-run. SOBREVIVE ao GC do workspace:
// quando o "Rodar app" morre (workspace coletado), esta imagem é o registro que
// fica. A lista devolve METADADOS (os bytes pesam); o conteúdo sai por id como
// image/png inerte (sem sandbox/iframe — um PNG não executa). Imutável por id.
export interface PreviewServerThumbnailSummary {
  id: string;
  runId: string;
  agentId: string;
  contentHash: string;
  sizeBytes: number;
  createdAt: string;
}

/** Versões do thumbnail da Issue, da mais nova para a mais antiga (a tela mostra a [0]). */
export function listPreviewServerThumbnails(
  companyId: string,
  issueId: string,
): Promise<PreviewServerThumbnailSummary[]> {
  return apiFetch<PreviewServerThumbnailSummary[]>(
    `/api/companies/${companyId}/issues/${issueId}/preview-server-thumbnails`,
  );
}

/**
 * Os bytes de UM thumbnail — resposta image/png (não-JSON), por isso não passa
 * pelo apiFetch. Mesmo auth do fetcher central (o X-Board-User entra aqui, como
 * no fetchPreviewArtifactContent). O <img src> não manda header custom, então o
 * front busca o blob autenticado e cria a object URL (o padrão da foto v1).
 */
export async function fetchPreviewServerThumbnailBlob(
  companyId: string,
  thumbnailId: string,
): Promise<Blob> {
  const headers = new Headers();
  headers.set("X-Board-User", process.env.NEXT_PUBLIC_DEV_BOARD_USER ?? "");
  const response = await fetch(
    `${resolveBaseUrl()}/api/companies/${companyId}/preview-server-thumbnails/${thumbnailId}/content`,
    { headers },
  );
  if (!response.ok) {
    throw await toApiError(response);
  }
  return response.blob();
}

/* ---------- fila de review (F.5) ---------- */

// A ReviewResponse é enxuta de propósito: título/risco/assignee da issue NÃO
// vêm aqui (resolver pela lista de issues); decisionReason é dado de negócio que
// vive só nesta tabela (PII fora do activity_log, ADR-0009). O run pode ter
// acabado FAILED com o review ainda PENDING (latente R.2) — nunca assumir status.
export interface ReviewSummary {
  id: string;
  issueId: string;
  runId: string;
  status: string;
  reviewerActorType: string | null;
  reviewerActorId: string | null;
  decisionReason: string | null;
  requestedAt: string;
  decidedAt: string | null;
}

/** Fila por status (default PENDING no server), ordem de CHEGADA (requestedAt asc). */
export function listReviews(companyId: string, status: string): Promise<ReviewSummary[]> {
  return apiFetch<ReviewSummary[]>(`/api/companies/${companyId}/reviews?status=${status}`);
}

export type ReviewDecision = "APPROVE" | "REJECT";

// A decisão é SEMPRE ancorada no reviewId do card (R.3): âncora ≠ ciclo aberto →
// 409 no server (decisão stale não aprova ciclo novo). Devolve a issue já no
// estado final (DONE no APPROVE, BLOCKED no REJECT).
export interface ReviewDecisionInput {
  decision: ReviewDecision;
  reason?: string;
  reviewId: string;
}

export function decideReview(
  companyId: string,
  issueId: string,
  input: ReviewDecisionInput,
): Promise<IssueSummary> {
  return apiFetch<IssueSummary>(`/api/companies/${companyId}/issues/${issueId}/review`, {
    method: "POST",
    body: input,
  });
}

/* ---------- agents (F.6) ---------- */

/** Chip do skillset: `skillKey` null se a versão pinada não resolve (degradar). */
export interface AgentSkillSelection {
  companySkillId: string;
  skillKey: string | null;
  version: number;
}

export function listAgentSkills(companyId: string, agentId: string): Promise<AgentSkillSelection[]> {
  return apiFetch<AgentSkillSelection[]>(`/api/companies/${companyId}/agents/${agentId}/skills`);
}

// Heartbeat-runs do agente: shape REDUZIDO (SEM startedAt/finishedAt/lastError —
// diferente do ledger de issue da F.0). A coluna de tempo usa createdAt ("há X");
// o erro não vem — quem conta é o drawer. Ordem desc (mais recente primeiro).
export interface AgentRun {
  id: string;
  agentId: string;
  issueId: string | null;
  status: RunStatus;
  attempt: number;
  createdAt: string;
}

export function listAgentRuns(companyId: string, agentId: string): Promise<AgentRun[]> {
  return apiFetch<AgentRun[]>(`/api/companies/${companyId}/agents/${agentId}/heartbeat-runs`);
}

// Mint de API key: o segredo em claro (`ag_...`) volta UMA vez; o servidor só
// guarda o hash SHA-256. É a IDENTIDADE do agente no control plane — NÃO chave
// de modelo. Nunca persistir além do reveal; nunca logar.
export interface MintedApiKey {
  apiKey: string;
}

export function mintAgentKey(companyId: string, agentId: string): Promise<MintedApiKey> {
  return apiFetch<MintedApiKey>(`/api/companies/${companyId}/agents/${agentId}/api-keys`, {
    method: "POST",
  });
}

export interface HireAgentInput {
  name: string;
  role: string;
  reportsToId?: string;
  adapterType?: string;
  runtimeCommand?: string;
  executionTarget?: string;
  // Motor escolhido no dropdown (ADR-0014): o item do catálogo preenche os 3 eixos.
  model?: string;
  effort?: string;
  // Molde escolhido (ADR-0017): o backend copia o snapshot (motor + skillset + pin). O motor
  // passado à parte (acima) VENCE o do preset e não propaga ao molde (override do operador).
  roleTemplateVersionId?: string;
}

export function hireAgent(companyId: string, input: HireAgentInput): Promise<AgentSummary> {
  return apiFetch<AgentSummary>(`/api/companies/${companyId}/agents`, {
    method: "POST",
    body: input,
  });
}

/* ---------- moldes / role-templates (Teams T.1, ADR-0017) ---------- */

// O tipo de função (eixo FIXO): o esqueleto que segura os invariantes. REVISAO_QA e SUPORTE são
// reservados no v1 (revisor = board; suporte = KnowAll) — aceitos, mas sem agente próprio.
export type TipoFuncao =
  | "LIDERANCA"
  | "GESTAO_PO"
  | "PLANEJAMENTO_ARQUITETO"
  | "EXECUCAO"
  | "REVISAO_QA"
  | "SUPORTE";

// Identidade de um molde GLOBAL (o catálogo é global; a rota é company-scoped só p/ gatear, board-only).
// `roleEspecifica` é a "carne" (architect/executor/…); `currentVersionId` aponta a versão corrente.
export interface RoleTemplate {
  id: string;
  tipoFuncao: TipoFuncao;
  roleEspecifica: string;
  currentVersionId: string | null;
  createdAt: string;
}

// Uma versão do preset (savelog append-only), LEGÍVEL por inteiro: motor = trio + skillset (o backend
// desserializa o jsonb em skillKeys) + reports_to sugerido. `status` DRAFT/APPROVED (board-direct = APPROVED).
export interface RoleTemplateVersion {
  id: string;
  roleTemplateId: string;
  version: number;
  adapterType: string | null;
  model: string | null;
  effort: string | null;
  skillKeys: string[];
  reportsToDefault: string | null;
  status: string;
  createdAt: string;
}

export function listRoleTemplates(companyId: string): Promise<RoleTemplate[]> {
  return apiFetch<RoleTemplate[]>(`/api/companies/${companyId}/role-templates`);
}

// O savelog completo de um molde, mais recente primeiro — a fonte do master-detail da biblioteca.
export function listRoleTemplateVersions(
  companyId: string,
  templateId: string,
): Promise<RoleTemplateVersion[]> {
  return apiFetch<RoleTemplateVersion[]>(
    `/api/companies/${companyId}/role-templates/${templateId}/versions`,
  );
}

// O preset (motor + skillset + reports_to sugerido) de um molde novo; cria a identidade global + v1.
export interface CreateRoleTemplateInput {
  tipoFuncao: TipoFuncao;
  roleEspecifica: string;
  adapterType?: string;
  model?: string;
  effort?: string;
  skillKeys?: string[];
  reportsToDefault?: string;
  changeDescription?: string;
}

export function createRoleTemplate(
  companyId: string,
  input: CreateRoleTemplateInput,
): Promise<RoleTemplate> {
  return apiFetch<RoleTemplate>(`/api/companies/${companyId}/role-templates`, {
    method: "POST",
    body: input,
  });
}

// Editar um molde = ANEXAR nova versão (savelog; quem pinou não é afetado). Devolve a versão nova.
export interface AddRoleTemplateVersionInput {
  adapterType?: string;
  model?: string;
  effort?: string;
  skillKeys?: string[];
  reportsToDefault?: string;
  changeType?: string;
  changeDescription?: string;
}

export function addRoleTemplateVersion(
  companyId: string,
  templateId: string,
  input: AddRoleTemplateVersionInput,
): Promise<RoleTemplateVersion> {
  return apiFetch<RoleTemplateVersion>(
    `/api/companies/${companyId}/role-templates/${templateId}/versions`,
    { method: "POST", body: input },
  );
}

/* ---------- KnowAll: casa conhecimento→skill (Teams T.1.b) ---------- */

// Um conhecimento requerido e seu top-1 no catálogo global (matched=false = gap). `installState` é
// relativo à company do chamador (INSTALLED = já na biblioteca; AVAILABLE = existe no catálogo, importar).
export interface KnowledgeMatch {
  knowledge: string;
  matched: boolean;
  skillId: string | null;
  skillKey: string | null;
  name: string | null;
  installState: "INSTALLED" | "AVAILABLE" | null;
}

// O casamento de um conjunto: detalhe por-conhecimento + gaps (sem skill) + o skillset pronto (keys distintas).
export interface KnowledgeMatchResult {
  matches: KnowledgeMatch[];
  gaps: string[];
  matchedSkillKeys: string[];
}

export function matchKnowledge(
  companyId: string,
  knowledge: string[],
): Promise<KnowledgeMatchResult> {
  return apiFetch<KnowledgeMatchResult>(`/api/companies/${companyId}/knowall/match`, {
    method: "POST",
    body: { knowledge },
  });
}

/* ---------- conversa com o CEO (Teams T.3, ADR-0018) ---------- */

// Um chat por ASSUNTO — todos com o mesmo CEO (muda o assunto, não o interlocutor).
// Assunto duplicado na company → 409 (uq company+subject).
export interface Conversation {
  id: string;
  subject: string;
  createdAt: string;
}

export type TurnStatus = "PENDING" | "ANSWERED" | "FAILED";

// Um turno da thread: a mensagem do dono + a resposta do CEO quando o Run liquidar.
// `runId` nasce null e aparece no CLAIM (coalescing junta N posts num run QUEUED).
export interface ConversationTurn {
  id: string;
  ownerMessage: string;
  ceoReply: string | null;
  status: TurnStatus;
  runId: string | null;
  createdAt: string;
  answeredAt: string | null;
}

/** O 202 do POST de mensagem: o turno é assíncrono — a resposta chega pelo POLLING da thread. */
export interface TurnAccepted {
  turnId: string;
  runId: string;
  runCreated: boolean;
}

export function listConversations(companyId: string): Promise<Conversation[]> {
  return apiFetch<Conversation[]>(`/api/companies/${companyId}/conversations`);
}

export function openConversation(companyId: string, subject: string): Promise<Conversation> {
  return apiFetch<Conversation>(`/api/companies/${companyId}/conversations`, {
    method: "POST",
    body: { subject },
  });
}

export function postCeoMessage(
  companyId: string,
  conversationId: string,
  message: string,
): Promise<TurnAccepted> {
  return apiFetch<TurnAccepted>(
    `/api/companies/${companyId}/conversations/${conversationId}/messages`,
    { method: "POST", body: { message } },
  );
}

export function listTurns(companyId: string, conversationId: string): Promise<ConversationTurn[]> {
  return apiFetch<ConversationTurn[]>(
    `/api/companies/${companyId}/conversations/${conversationId}/turns`,
  );
}

/* ---------- approvals: o gate pré-hoc (Teams T.3, ADR-0018/0020) ---------- */

export type ApprovalActionType = "HIRE_AGENT" | "CREATE_ISSUE" | "WAKEUP_AGENT";

export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED";

export type RacionalTipo = "LOGICO" | "INTUICAO";

// A MESMA fila projetada em dois lugares (badge no shell + card ancorado na thread).
// `targetDetail` é o JSON canônico do alvo (só-IDs/valores) — a UI HUMANIZA, nunca despeja cru.
export interface Approval {
  id: string;
  requestedByAgentId: string;
  conversationId: string | null;
  runId: string | null;
  actionType: ApprovalActionType;
  targetDetail: string;
  area: string;
  resumo: string | null;
  status: ApprovalStatus;
  racional: string | null;
  racionalTipo: RacionalTipo;
  racionalArrancado: boolean;
  grillRounds: number;
  decisionReason: string | null;
  requestedAt: string;
  decidedAt: string | null;
}

/** Fila por status (fonte do badge) — ordem de chegada. */
export function listApprovals(companyId: string, status: ApprovalStatus): Promise<Approval[]> {
  return apiFetch<Approval[]>(`/api/companies/${companyId}/approvals?status=${status}`);
}

/** Os cards ancorados de UMA thread (todas as decisões, decididas inclusas — a história fica). */
export function listApprovalsAnchoredTo(
  companyId: string,
  conversationId: string,
): Promise<Approval[]> {
  return apiFetch<Approval[]>(
    `/api/companies/${companyId}/approvals?conversationId=${conversationId}`,
  );
}

export type ApprovalDecisionInput = {
  decision: "APPROVE" | "REJECT";
  racional?: string;
  reason?: string;
  // A4 (ADR-0031): o trio de motor editado pelo dono no menu do hire — a mão soberana
  // vence o motor protocolado no alvo; ausente/vazio cai no protocolo.
  adapterType?: string;
  model?: string;
  effort?: string;
};

// O espelho do DecideOutcome selado do backend: o 422 do grill NÃO é falha —
// é o escriba-que-insiste devolvendo o probe (a rodada ficou contada; a
// Approval segue PENDING). A tela re-pergunta e reenvia com o racional.
export type DecideApprovalOutcome =
  | { kind: "committed"; approval: Approval }
  | { kind: "grilled"; probe: string; rounds: number };

export async function decideApproval(
  companyId: string,
  approvalId: string,
  input: ApprovalDecisionInput,
): Promise<DecideApprovalOutcome> {
  const body = await apiFetch<Approval | { grilled: boolean; probe: string; rounds: number }>(
    `/api/companies/${companyId}/approvals/${approvalId}/decision`,
    { method: "POST", body: input, acceptStatuses: [422] },
  );
  if ("grilled" in body) {
    return { kind: "grilled", probe: body.probe, rounds: body.rounds };
  }
  return { kind: "committed", approval: body };
}

/* ---------- memória: carta + report por-Issue (Teams T.3, ADR-0019) ---------- */

/** A carta corrente — a MESMA projeção que o CEO carrega como CLAUDE.md. 404 = sem direção ainda. */
export interface CompanyCarta {
  id: string;
  version: number;
  body: string;
  createdAt: string;
}

export function getCarta(companyId: string): Promise<CompanyCarta> {
  return apiFetch<CompanyCarta>(`/api/companies/${companyId}/memory/carta`);
}

/** O report do Ledger por-Issue (saída final do run concluinte). 404 = nenhum run concluiu. */
export interface IssueReport {
  issueId: string;
  runId: string;
  concludedAt: string;
  report: string;
}

export function getIssueReport(companyId: string, issueId: string): Promise<IssueReport> {
  return apiFetch<IssueReport>(`/api/companies/${companyId}/issues/${issueId}/report`);
}
