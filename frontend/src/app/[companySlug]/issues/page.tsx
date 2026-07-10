"use client";

import { useQuery } from "@tanstack/react-query";
import { CompanyLink, useCompanyNav } from "@/components/company-link";
import { AssigneeChip, RiskBadge } from "@/components/issue-bits";
import { NewIssueDialog } from "@/components/new-issue-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { View, ViewHead } from "@/components/view";
import { listAgents, listIssues } from "@/lib/api";
import { groupIssuesByStatus } from "@/lib/issues";
import { queryKeys } from "@/lib/query-keys";
import { useActiveCompany } from "@/lib/use-active-company";

// Issues (F.4): lista agrupada por status na ordem do protótipo, com LED,
// risk badge, assignee e #N (a identidade humana da Issue, ADR-0033). Linhas
// com hover-intent prefetch (prefetch={false} + onMouseEnter → nav.prefetch).

export default function IssuesPage() {
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const nav = useCompanyNav();

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

  // undefined = agents ainda não resolvidos (o chip mostra "—", não afirma
  // "livre"); null = resolvido e realmente sem correspondência.
  const agentName = (agentId: string | null): string | null | undefined =>
    agentsQuery.data ? (agentsQuery.data.find((agent) => agent.id === agentId)?.name ?? null) : undefined;

  const groups = groupIssuesByStatus(issuesQuery.data ?? []);

  return (
    <View>
      <ViewHead
        title="Issues"
        sub={issuesQuery.data ? `${issuesQuery.data.length} issues · agrupadas por status` : "…"}
      >
        <NewIssueDialog companyId={companyId} />
      </ViewHead>

      {issuesQuery.isError && (
        <EmptyState tone="erro">
          Não deu para carregar as issues — recarregue ou confira o backend.
        </EmptyState>
      )}

      {issuesQuery.isPending && !issuesQuery.isError && (
        <div className="flex flex-col gap-2" aria-busy="true">
          {Array.from({ length: 5 }).map((_, index) => (
            <div key={index} className="h-10 animate-pulse rounded-md bg-raised" />
          ))}
        </div>
      )}

      {issuesQuery.data && issuesQuery.data.length === 0 && (
        <EmptyState>nenhuma issue ainda — crie a primeira e acorde um agente para trabalhar nela</EmptyState>
      )}

      {groups.map((group) => (
        <section key={group.status} className="status-group">
          <div className="status-group-head">
            <span className="name">{group.label}</span>
            <span className="count">{group.issues.length}</span>
          </div>
          <div className="row-list">
            {group.issues.map((issue) => (
              <CompanyLink
                key={issue.id}
                href={`/issues/${issue.id}`}
                prefetch={false}
                onMouseEnter={() => nav.prefetch(`/issues/${issue.id}`)}
                className="row"
              >
                <span className={`led ${group.led}`} />
                <RiskBadge riskLevel={issue.riskLevel} status={group.status} />
                <span className="row-title">{issue.title}</span>
                <AssigneeChip
                  name={issue.assigneeAgentId ? agentName(issue.assigneeAgentId) : null}
                />
                <span className="row-meta">#{issue.seq}</span>
              </CompanyLink>
            ))}
          </div>
        </section>
      ))}
    </View>
  );
}
