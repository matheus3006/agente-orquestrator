"use client";

import { useQuery } from "@tanstack/react-query";
import { ChevronRight, Sparkles } from "lucide-react";
import { notFound, useParams } from "next/navigation";
import { useState } from "react";
import { AgentCard } from "@/components/agent-card";
import { AgentDot } from "@/components/agent-dot";
import { AgentMotorEditor } from "@/components/agent-motor-editor";
import { CompanyLink } from "@/components/company-link";
import { MintKeyButton } from "@/components/mint-key-button";
import { StepTraceDrawer, type TranscriptRequest } from "@/components/step-trace-drawer";
import { Termo } from "@/components/termo";
import { EmptyState } from "@/components/ui/empty-state";
import { View } from "@/components/view";
import {
  getMissionControl,
  listAgentRuns,
  listAgentSkills,
  listAgents,
  listEngines,
  listIssues,
  type AgentRun,
  type MissionControlAgent,
} from "@/lib/api";
import { engineLabel } from "@/lib/engines";
import { fmtAgo, shortId } from "@/lib/format";
import { cardRunId, statusClass, statusWord } from "@/lib/mission-control";
import { queryKeys } from "@/lib/query-keys";
import { isTerminalRunStatus } from "@/lib/transcript";
import { useActiveCompany } from "@/lib/use-active-company";
import { useNow } from "@/lib/use-now";

// Detail do agente (F.6): header + Trace ao vivo (REUSA o AgentCard do Mission
// Control ligado ao mesmo agregado de 2s) + Runs recentes (heartbeat-runs de
// shape REDUZIDO: run-status + "há X" [createdAt] + id curto; linha abre o
// drawer) + props + skillset em chips + mint de API key. Agente inexistente
// após um fetch real neste mount → notFound() (padrão da F.4).

export default function AgentDetailPage() {
  const { agentId } = useParams<{ agentId: string }>();
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const nowMs = useNow(1000);

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents(companyId ?? ""),
    queryFn: () => listAgents(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const mcQuery = useQuery({
    queryKey: queryKeys.missionControl(companyId ?? ""),
    queryFn: () => getMissionControl(companyId as string),
    enabled: !!companyId,
    refetchInterval: 2000,
    refetchIntervalInBackground: false,
  });
  const issuesQuery = useQuery({
    queryKey: queryKeys.issues(companyId ?? ""),
    queryFn: () => listIssues(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const runsQuery = useQuery({
    queryKey: queryKeys.agentRuns(companyId ?? "", agentId),
    queryFn: () => listAgentRuns(companyId as string, agentId),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const skillsQuery = useQuery({
    queryKey: queryKeys.agentSkills(companyId ?? "", agentId),
    queryFn: () => listAgentSkills(companyId as string, agentId),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const enginesQuery = useQuery({
    queryKey: queryKeys.engines(companyId ?? ""),
    queryFn: () => listEngines(companyId as string),
    enabled: !!companyId,
    staleTime: 5 * 60_000,
  });

  const agent = agentsQuery.data?.find((candidate) => candidate.id === agentId);
  const station = mcQuery.data?.agents.find((candidate) => candidate.agent.id === agentId);
  const issues = issuesQuery.data ?? [];

  if (agentsQuery.isFetchedAfterMount && agentsQuery.isSuccess && !agent) {
    notFound();
  }

  const [trace, setTrace] = useState<TranscriptRequest | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const openStationTrace = (target: MissionControlAgent) => {
    const running = target.status === "RUNNING" || target.status === "QUEUED";
    setTrace({
      runId: cardRunId(target),
      issueTitle: target.activeRun?.issueTitle ?? null,
      agentName: running ? target.agent.name : null,
    });
    setDrawerOpen(true);
  };
  const openRunTrace = (run: AgentRun) => {
    setTrace({
      runId: run.id,
      issueTitle: issues.find((issue) => issue.id === run.issueId)?.title ?? null,
      // encerrado → CONGELADO (sem agente por trás); vivo → modo agente.
      agentName: isTerminalRunStatus(run.status) ? null : (agent?.name ?? null),
    });
    setDrawerOpen(true);
  };

  if (agentsQuery.isError) {
    return (
      <View>
        <EmptyState tone="erro">
          Não deu para carregar o agente — recarregue ou confira o backend.
        </EmptyState>
      </View>
    );
  }

  if (!agent) {
    return (
      <View>
        <div className="h-6 w-64 animate-pulse rounded bg-overlay" aria-busy="true" />
        <div className="mt-4 h-40 animate-pulse rounded-lg bg-raised" />
      </View>
    );
  }

  const status = station?.status ?? "IDLE";
  const engines = enginesQuery.data ?? [];
  const runtime = `${engineLabel(agent, engines)} · ${agent.executionTarget}`;
  const skills = skillsQuery.data;
  const currentIssue = station?.activeRun?.issueTitle ?? null;

  return (
    <View>
      <div className="crumb">
        <CompanyLink href="/agents">agents</CompanyLink>
        <ChevronRight size={11} strokeWidth={2} />
        <span>{agent.name}</span>
      </div>

      <div className="detail-grid">
        <div>
          <div className="mb-[18px] flex items-center gap-3.5">
            <AgentDot name={agent.name} />
            <div>
              <h1 className="detail-title m-0">{agent.name}</h1>
              <div className="roster-role">
                {agent.role} · {runtime}
              </div>
            </div>
            <span className={`agent-status-word ${statusClass(status)} ml-auto`}>
              {statusWord(status)}
            </span>
          </div>

          <h2 className="section-label">Trace ao vivo</h2>
          {station ? (
            <div className="mb-[26px]">
              <AgentCard
                station={station}
                issues={issues}
                nowMs={nowMs}
                onOpenTranscript={openStationTrace}
              />
            </div>
          ) : (
            <EmptyState compact className="mb-[26px]">
              sincronizando com o Mission Control — o trace ao vivo aparece no próximo poll
            </EmptyState>
          )}

          <h2 className="section-label">Runs recentes</h2>
          <div className="ledger">
            {runsQuery.data && runsQuery.data.length > 0 ? (
              runsQuery.data.map((run) => (
                <div
                  key={run.id}
                  className="ledger-row"
                  role="button"
                  tabIndex={0}
                  onClick={() => openRunTrace(run)}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      openRunTrace(run);
                    }
                  }}
                >
                  <span className={`run-status ${run.status}`}>{run.status}</span>
                  <span className="ledger-err">{run.issueId ? `issue ${shortId(run.issueId)}` : ""}</span>
                  <span className="ledger-when">
                    {fmtAgo(run.createdAt, nowMs)} · attempt {run.attempt}
                  </span>
                  <span className="ledger-id">{shortId(run.id)}</span>
                </div>
              ))
            ) : runsQuery.isPending ? (
              <div className="h-12 animate-pulse rounded-md bg-raised" aria-busy="true" />
            ) : runsQuery.isError ? (
              <EmptyState compact tone="erro">
                não deu para carregar os runs — recarregue
              </EmptyState>
            ) : (
              <EmptyState compact>
                nenhum run ainda — o wakeup dá o primeiro (o trace ao vivo mostra em tempo real)
              </EmptyState>
            )}
          </div>
        </div>

        <aside>
          <div className="props-panel static mb-4">
            <div className="prop">
              <span className="prop-key">issue atual</span>
              <span className="prop-val">{currentIssue ?? "—"}</span>
            </div>
            <div className="prop">
              <span className="prop-key">id</span>
              <span className="prop-val mono">{shortId(agent.id)}</span>
            </div>
          </div>

          <div className="kv-panel">
            <span className="prop-key">
              <Termo id="motor">motor</Termo>
            </span>
            <AgentMotorEditor
              companyId={companyId}
              agentId={agent.id}
              motor={{ adapterType: agent.adapterType, model: agent.model, effort: agent.effort }}
              engines={engines}
              loading={enginesQuery.isPending}
            />
            <span className="text-[11px] text-faint">
              {agent.executionTarget} · <span className="text-amber">sem isolamento</span> no v1
            </span>
          </div>

          <div className="kv-panel">
            <span className="prop-key">skillset ({skills?.length ?? 0})</span>
            {skills === undefined ? (
              <div className="h-6 animate-pulse rounded bg-overlay" aria-busy="true" />
            ) : skills.length === 0 ? (
              <span className="text-[12px] text-faint">nenhuma skill no set — pine skills para este agente</span>
            ) : (
              <div className="chips">
                {skills.map((skill) => (
                  <span key={skill.companySkillId} className="skill-chip">
                    <Sparkles size={11} strokeWidth={2} />
                    {skill.skillKey ?? "—"}
                    <span className="v">v{skill.version}</span>
                  </span>
                ))}
              </div>
            )}
          </div>

          <div className="kv-panel">
            <span className="prop-key">credencial</span>
            <MintKeyButton companyId={companyId} agentId={agent.id} agentName={agent.name} />
          </div>
        </aside>
      </div>

      <StepTraceDrawer
        companyId={companyId}
        request={trace}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />
    </View>
  );
}
