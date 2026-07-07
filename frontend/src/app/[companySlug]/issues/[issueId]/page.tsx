"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronRight, Clock, RotateCcw, ShieldCheck, X } from "lucide-react";
import { notFound, useParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { ActivityFeed } from "@/components/activity-feed";
import { AgentDot } from "@/components/agent-dot";
import { CompanyLink } from "@/components/company-link";
import { AssigneeChip, RiskBadge } from "@/components/issue-bits";
import { IssuePreviewSection } from "@/components/preview-section";
import { IssueServerSection } from "@/components/preview-server-section";
import { StepTraceDrawer, type TranscriptRequest } from "@/components/step-trace-drawer";
import { UnblockIssueDialog } from "@/components/unblock-issue-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { View } from "@/components/view";
import {
  getActivity,
  getIssueRuns,
  listAgents,
  listIssues,
  type IssueRun,
  type UnblockDecision,
} from "@/lib/api";
import type { FeedLookups } from "@/lib/feed";
import { fmtAgo, shortId } from "@/lib/format";
import {
  issueActivityEvents,
  issueBehindLedger,
  issueStatusLed,
  ledgerKeepsPolling,
  ledgerWhen,
  riskNote,
} from "@/lib/issues";
import { queryKeys } from "@/lib/query-keys";
import { isTerminalRunStatus } from "@/lib/transcript";
import { useActiveCompany } from "@/lib/use-active-company";
import { useNow } from "@/lib/use-now";

// Detail da issue (F.4): título/descrição + props panel, ledger de runs
// (linha abre o drawer COMPARTILHADO da F.3), unblock REOPEN/CANCEL quando
// BLOCKED, link para a fila quando PENDING_REVIEW, atividade da issue.
// A issue vem da LISTA (mesmo padrão company-por-slug); inexistente após um
// fetch real neste mount → notFound() (cai no not-found do segmento).

const ISSUE_FEED_ROWS = 6;

export default function IssueDetailPage() {
  const { issueId } = useParams<{ issueId: string }>();
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const nowMs = useNow(1000);

  const issuesQuery = useQuery({
    queryKey: queryKeys.issues(companyId ?? ""),
    queryFn: () => listIssues(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents(companyId ?? ""),
    queryFn: () => listAgents(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const issue = issuesQuery.data?.find((candidate) => candidate.id === issueId);

  const runsQuery = useQuery({
    queryKey: queryKeys.issueRuns(companyId ?? "", issueId),
    queryFn: () => getIssueRuns(companyId as string, issueId),
    enabled: !!companyId,
    // Poll de 2s enquanto a issue está VIVA (run em voo no ledger OU issue
    // OPEN/IN_PROGRESS — o run novo do próximo wakeup precisa aparecer);
    // issue estacionada com ledger terminal desliga (achado da revisão).
    refetchInterval: (query) =>
      ledgerKeepsPolling(issue?.status ?? "", query.state.data ?? []) ? 2000 : false,
    refetchIntervalInBackground: false,
  });
  const activityQuery = useQuery({
    queryKey: queryKeys.activity(companyId ?? ""),
    queryFn: () => getActivity(companyId as string, 50),
    enabled: !!companyId,
    staleTime: 30_000,
  });

  if (issuesQuery.isFetchedAfterMount && issuesQuery.isSuccess && !issue) {
    // 404 REAL: a lista veio do servidor NESTE mount e a issue não está nela
    // (o gate evita um falso 404 renderizado sobre cache stale).
    notFound();
  }

  // O espelho do mecanismo da F.3: o ledger (2s) viu o settle antes da lista
  // (30s) → invalida issues+activity para o props panel/action-bar/feed
  // acompanharem sem blur+refocus. Guard por dataUpdatedAt evita loop.
  const queryClient = useQueryClient();
  const runsUpdatedAt = runsQuery.dataUpdatedAt;
  const issuesUpdatedAt = issuesQuery.dataUpdatedAt;
  useEffect(() => {
    if (!companyId || !issue || !runsQuery.data) {
      return;
    }
    if (issueBehindLedger(issue, runsQuery.data) && issuesUpdatedAt < runsUpdatedAt) {
      queryClient.invalidateQueries({ queryKey: queryKeys.issues(companyId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.activity(companyId) });
      // O settle é também o momento da captura (F.9): a versão nova do preview
      // aparece junto com o desfecho do run, sem esperar staleTime.
      queryClient.invalidateQueries({ queryKey: queryKeys.issuePreviews(companyId, issueId) });
    }
  }, [issue, runsQuery.data, issuesUpdatedAt, runsUpdatedAt, companyId, issueId, queryClient]);

  // undefined = agents ainda não resolvidos (chips mostram "—"); null = sem nome.
  const agentName = (agentId: string | null): string | null | undefined =>
    agentsQuery.data ? (agentsQuery.data.find((agent) => agent.id === agentId)?.name ?? null) : undefined;

  const issues = issuesQuery.data;
  const lookups = useMemo<FeedLookups>(
    () => ({
      agentName: (id) => agentsQuery.data?.find((agent) => agent.id === id)?.name ?? null,
      issueTitle: (id) => issues?.find((candidate) => candidate.id === id)?.title ?? null,
      issueRisk: (id) => issues?.find((candidate) => candidate.id === id)?.riskLevel ?? null,
    }),
    [agentsQuery.data, issues],
  );

  const [trace, setTrace] = useState<TranscriptRequest | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [unblockDecision, setUnblockDecision] = useState<UnblockDecision | null>(null);

  const openRunTranscript = (run: IssueRun) => {
    setTrace({
      runId: run.id,
      issueTitle: issue?.title ?? null,
      // Run vivo abre no modo agente (streaming); encerrado abre CONGELADO
      // (spec: openDrawerForFrozenRun não tem agente por trás).
      agentName: isTerminalRunStatus(run.status) ? null : (agentName(run.agentId) ?? null),
    });
    setDrawerOpen(true);
  };

  if (issuesQuery.isError) {
    return (
      <View>
        <EmptyState tone="erro">
          Não deu para carregar a issue — recarregue ou confira o backend.
        </EmptyState>
      </View>
    );
  }

  if (!issue) {
    return (
      <View>
        <div className="h-6 w-64 animate-pulse rounded bg-overlay" aria-busy="true" />
        <div className="mt-4 h-24 animate-pulse rounded-lg bg-raised" />
      </View>
    );
  }

  const assignee = agentName(issue.assigneeAgentId);
  const issueEvents = issueActivityEvents(activityQuery.data ?? [], issue.id).slice(
    0,
    ISSUE_FEED_ROWS,
  );

  return (
    <View>
      <div className="crumb">
        <CompanyLink href="/issues">issues</CompanyLink>
        <ChevronRight size={11} strokeWidth={2} />
        <span>{issue.title}</span>
      </div>

      <div className="detail-grid">
        <div>
          <h1 className="detail-title">{issue.title}</h1>
          <p className="detail-desc">{issue.description || "— sem descrição —"}</p>

          {issue.status === "BLOCKED" && (
            <div className="action-bar">
              <button className="btn primary" onClick={() => setUnblockDecision("REOPEN")}>
                <RotateCcw size={13} strokeWidth={2} /> Reabrir (REOPEN)
              </button>
              <button className="btn danger" onClick={() => setUnblockDecision("CANCEL")}>
                <X size={13} strokeWidth={2} /> Cancelar issue
              </button>
            </div>
          )}
          {issue.status === "PENDING_REVIEW" && (
            <div className="action-bar">
              <CompanyLink href="/reviews" className="btn">
                <ShieldCheck size={13} strokeWidth={2} /> Decidir na fila de review
              </CompanyLink>
            </div>
          )}

          <IssuePreviewSection companyId={companyId} issueId={issue.id} nowMs={nowMs} />

          {/* F.10 (ADR-0023): o preview de PROCESSO — servidor declarado no run,
              sobe por clique do operador, some sem manifest (zero ruído). */}
          <IssueServerSection companyId={companyId} issueId={issue.id} />

          <h2 className="section-label">Ledger de execuções</h2>
          <div className="ledger">
            {runsQuery.data && runsQuery.data.length > 0 ? (
              runsQuery.data.map((run) => (
                <div
                  key={run.id}
                  className="ledger-row"
                  role="button"
                  tabIndex={0}
                  onClick={() => openRunTranscript(run)}
                  onKeyDown={(event) => {
                    // role=button de verdade: Enter E Espaço ativam (Espaço
                    // com preventDefault para não rolar a página)
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      openRunTranscript(run);
                    }
                  }}
                >
                  <span className={`run-status ${run.status}`}>{run.status}</span>
                  <AssigneeChip name={agentName(run.agentId)} />
                  <span className="ledger-err">{run.lastError ?? ""}</span>
                  <span className="ledger-when">{ledgerWhen(run, nowMs)}</span>
                  <span className="ledger-id">{shortId(run.id)}</span>
                </div>
              ))
            ) : runsQuery.isPending ? (
              <div className="h-12 animate-pulse rounded-md bg-raised" aria-busy="true" />
            ) : runsQuery.isError ? (
              <EmptyState compact tone="erro">
                não deu para carregar o ledger — recarregue
              </EmptyState>
            ) : (
              <EmptyState compact icon={<Clock />}>
                nenhuma execução ainda — o ledger conta a história completa da issue
              </EmptyState>
            )}
          </div>

          <h2 className="section-label">Atividade da issue</h2>
          {activityQuery.data ? (
            <ActivityFeed
              events={issueEvents}
              lookups={lookups}
              nowMs={nowMs}
              emptyNote="sem eventos registrados ainda"
            />
          ) : activityQuery.isError ? (
            <EmptyState compact tone="erro">
              não deu para carregar a atividade — recarregue
            </EmptyState>
          ) : (
            <div className="h-16 animate-pulse rounded-md bg-raised" aria-busy="true" />
          )}
        </div>

        <aside className="props-panel">
          <div className="prop">
            <span className="prop-key">status</span>
            <span className={`status-pill ${issue.status}`}>
              <span className={`led ${issueStatusLed(issue.status)}`} />
              {issue.status}
            </span>
          </div>
          <div className="prop">
            <span className="prop-key">risco</span>
            <span className="prop-val">
              <RiskBadge riskLevel={issue.riskLevel} status={issue.status} />
              <span className="text-[11px] text-faint">{riskNote(issue.riskLevel)}</span>
            </span>
          </div>
          <div className="prop">
            <span className="prop-key">assignee</span>
            <span className="prop-val">
              {assignee ? (
                <>
                  <AgentDot name={assignee} /> {assignee}
                </>
              ) : (
                "—"
              )}
            </span>
          </div>
          <div className="prop">
            <span className="prop-key">criada</span>
            <span className="prop-val mono">{fmtAgo(issue.createdAt, nowMs)}</span>
          </div>
          <div className="prop">
            <span className="prop-key">id</span>
            <span className="prop-val mono break-all text-right">{issue.id}</span>
          </div>
        </aside>
      </div>

      <UnblockIssueDialog
        companyId={companyId}
        issueId={issue.id}
        issueTitle={issue.title}
        decision={unblockDecision}
        onClose={() => setUnblockDecision(null)}
      />
      <StepTraceDrawer
        companyId={companyId}
        request={trace}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />
    </View>
  );
}
