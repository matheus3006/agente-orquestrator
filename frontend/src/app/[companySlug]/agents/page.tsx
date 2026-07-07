"use client";

import { useQueries, useQuery } from "@tanstack/react-query";
import { Network, Rows3 } from "lucide-react";
import { useState } from "react";
import { AgentDot } from "@/components/agent-dot";
import { CompanyLink, useCompanyNav } from "@/components/company-link";
import { HireAgentDialog } from "@/components/hire-agent-dialog";
import { TeamCanvas } from "@/components/team-canvas";
import { TeamToolbar } from "@/components/team-toolbar";
import { EmptyState } from "@/components/ui/empty-state";
import { View, ViewHead } from "@/components/view";
import { getMissionControl, listAgentSkills, listAgents, listEngines, listIssues, listTeams } from "@/lib/api";
import { rosterDoing } from "@/lib/agents";
import { engineLabel } from "@/lib/engines";
import { statusClass, statusWord } from "@/lib/mission-control";
import { queryKeys } from "@/lib/query-keys";
import { useActiveCompany } from "@/lib/use-active-company";
import { cn } from "@/lib/utils";

// Agents roster (F.6): uma linha por agente. O status/"doing" derivam do
// MESMO agregado de 2s do Mission Control (cache compartilhado); o runtime é
// `adapterType · executionTarget`; a contagem de skills vem de uma query por
// agente (roster é TIME, não fleet — custo aceitável).
// T.1-FE: a MESMA superfície ganha a visão ORGANOGRAMA (org-chart read-only da
// cadeia reports_to) — duas projeções do mesmo time, sem 5ª entrada de navegação.

export default function AgentsPage() {
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const nav = useCompanyNav();
  const [visao, setVisao] = useState<"roster" | "organograma">("roster");

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
  const enginesQuery = useQuery({
    queryKey: queryKeys.engines(companyId ?? ""),
    queryFn: () => listEngines(companyId as string),
    enabled: !!companyId,
    staleTime: 5 * 60_000,
  });
  const teamsQuery = useQuery({
    queryKey: queryKeys.teams(companyId ?? ""),
    queryFn: () => listTeams(companyId as string),
    enabled: !!companyId && visao === "organograma",
    staleTime: 30_000,
  });

  const agents = agentsQuery.data ?? [];
  const engines = enginesQuery.data ?? [];
  const skillsQueries = useQueries({
    queries: agents.map((agent) => ({
      queryKey: queryKeys.agentSkills(companyId ?? "", agent.id),
      queryFn: () => listAgentSkills(companyId as string, agent.id),
      enabled: !!companyId,
      staleTime: 30_000,
    })),
  });

  const stationById = new Map((mcQuery.data?.agents ?? []).map((station) => [station.agent.id, station]));
  const issues = issuesQuery.data ?? [];

  return (
    <View>
      <ViewHead
        title="Agents"
        sub={agentsQuery.data ? `${agents.length} no roster · runtime por agente` : "…"}
      >
        <HireAgentDialog companyId={companyId} />
      </ViewHead>

      <div className="filter-tabs" role="tablist" aria-label="Visão do time">
        <button
          type="button"
          role="tab"
          aria-selected={visao === "roster"}
          className={cn("filter-tab", visao === "roster" && "on")}
          onClick={() => setVisao("roster")}
        >
          <Rows3 size={11} strokeWidth={2} /> roster
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={visao === "organograma"}
          className={cn("filter-tab", visao === "organograma" && "on")}
          onClick={() => setVisao("organograma")}
        >
          <Network size={11} strokeWidth={2} /> organograma
        </button>
      </div>

      {agentsQuery.isError && (
        <EmptyState tone="erro">
          Não deu para carregar o roster — recarregue ou confira o backend.
        </EmptyState>
      )}

      {agentsQuery.isPending && !agentsQuery.isError && (
        <div className="flex flex-col gap-2" aria-busy="true">
          {Array.from({ length: 4 }).map((_, index) => (
            <div key={index} className="h-14 animate-pulse rounded-md bg-raised" />
          ))}
        </div>
      )}

      {agentsQuery.data && agents.length === 0 && (
        <EmptyState>nenhum agente ainda — contrate o primeiro para montar o time</EmptyState>
      )}

      {visao === "organograma" && (
        <TeamToolbar
          companyId={companyId}
          teams={teamsQuery.data ?? []}
          carregando={teamsQuery.isPending}
        />
      )}

      {agents.length > 0 && visao === "organograma" && companyId && (
        <>
          <TeamCanvas
            companyId={companyId}
            agents={agents}
            engines={engines}
            teams={teamsQuery.data ?? []}
            statusById={
              new Map((mcQuery.data?.agents ?? []).map((station) => [station.agent.id, station.status]))
            }
          />
          <p className="mb-0 mt-2.5 font-mono text-[10.5px] text-faint">
            arraste um agente e solte SOBRE o novo líder para religar a cadeia · ⋮ no nó = time /
            arquiteto / raiz · anti-ciclo e trava do arquiteto valem no backend (o canvas espelha)
          </p>
        </>
      )}

      {agents.length > 0 && visao === "roster" && (
        <div className="roster">
          {agents.map((agent, index) => {
            const station = stationById.get(agent.id);
            const status = station?.status ?? "IDLE";
            // #13: a palavra-status à direita só AGREGA em running/blocked — nos
            // demais a linha-doing já diz o estado ("ocioso — nunca executou",
            // "na fila…"), então o chip some (filtro de redução: sem eco por linha).
            const mostraStatus = status === "RUNNING" || status === "BLOCKED";
            const doing = rosterDoing(station, issues);
            const skills = skillsQueries[index]?.data;
            return (
              <CompanyLink
                key={agent.id}
                href={`/agents/${agent.id}`}
                prefetch={false}
                onMouseEnter={() => nav.prefetch(`/agents/${agent.id}`)}
                className="roster-row"
              >
                <span className="roster-id">
                  <AgentDot name={agent.name} />
                  <span>
                    <div className="roster-name">{agent.name}</div>
                    <div className="roster-role">{agent.role}</div>
                  </span>
                </span>
                <span className="roster-doing">
                  {doing.note ? (
                    doing.note
                  ) : (
                    <>
                      <span className="text-faint">{doing.verb}</span> <b>{doing.title}</b>
                    </>
                  )}
                </span>
                <span className="roster-cell">
                  {engineLabel(agent, engines)} · {agent.executionTarget}
                </span>
                <span className="roster-cell">{skills ? `${skills.length} skills no set` : "…"}</span>
                {mostraStatus && (
                  <span
                    className={`agent-status-word ${statusClass(status)}`}
                    style={{ justifySelf: "end" }}
                  >
                    {statusWord(status)}
                  </span>
                )}
              </CompanyLink>
            );
          })}
        </div>
      )}
    </View>
  );
}
