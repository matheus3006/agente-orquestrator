"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { EllipsisVertical, Stamp } from "lucide-react";
import { useEffect, useMemo, useRef } from "react";
import {
  Controls,
  Handle,
  Panel,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useNodesState,
  useReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { AgentDot } from "@/components/agent-dot";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  ApiError,
  assignAgentTeam,
  reparentAgent,
  setTeamArquiteto,
  type AgentBoardStatus,
  type AgentSummary,
  type Engine,
  type Team,
} from "@/lib/api";
import { engineLabel } from "@/lib/engines";
import { glossa } from "@/lib/glossario";
import { statusClass } from "@/lib/mission-control";
import { queryKeys } from "@/lib/query-keys";
import { montarOrganograma, ORG_NODE_H, ORG_NODE_W, teamHue } from "@/lib/team-canvas";
import { pushToast } from "@/lib/toaster";
import { cn } from "@/lib/utils";

// O ORG-CHART agora EDITÁVEL (T.2 ligou a cadeia de comando): arrastar um agente e soltar SOBRE
// outro reparenta (PATCH /reports-to); soltar no vazio volta pro lugar (nada muda). O menu do nó
// (⋮, nodrag) concentra o resto da composição: mover de time, virar raiz, designar arquiteto.
// O canvas segue ESPELHO — anti-ciclo e trava do arquiteto vivem no backend; um 409 vira toast e
// o layout re-assenta do dado real. Nós carregam a listra do TIME (cor determinística) e o LED
// vivo do agregado de 2s. Lib: @xyflow/react v12.

type DadosDoNo = {
  agent: AgentSummary;
  statusCls: string;
  motorLabel: string;
  time: Team | null;
  times: Team[];
  onReparent: (agentId: string, novoLiderId: string | null) => void;
  onMoverParaTime: (agentId: string, teamId: string | null) => void;
  onDesignarArquiteto: (teamId: string, agentId: string) => void;
};

type NoAgente = Node<DadosDoNo, "agente">;

function AgenteNode({ data }: NodeProps<NoAgente>) {
  const { agent, time, times } = data;
  const ehArquiteto = time?.arquitetoAgentId === agent.id;
  return (
    <div className={`org-node ${data.statusCls}`}>
      {time && (
        <span
          className="org-node-time"
          style={{ background: teamHue(time.id) }}
          title={`time ${time.dominio} / ${time.foco}`}
        />
      )}
      <Handle type="target" position={Position.Top} isConnectable={false} />
      <div className="org-node-head">
        <AgentDot name={agent.name} />
        <span className="org-node-name">{agent.name}</span>
        <span className={`led ${data.statusCls}`} style={{ marginLeft: "auto" }} aria-hidden />
        <DropdownMenu>
          <DropdownMenuTrigger
            className="org-node-menu nodrag"
            aria-label={`Compor ${agent.name} (time, líder, arquiteto)`}
          >
            <EllipsisVertical size={12} strokeWidth={2} />
          </DropdownMenuTrigger>
          <DropdownMenuContent className="motor-menu" align="start">
            {time && !ehArquiteto && (
              <DropdownMenuItem
                className="motor-item"
                onSelect={() => data.onDesignarArquiteto(time.id, agent.id)}
              >
                <Stamp size={12} strokeWidth={2} className="shrink-0 text-phosphor-dim" />
                <span className="motor-item-label">designar arquiteto do time</span>
              </DropdownMenuItem>
            )}
            {agent.reportsToId && (
              <DropdownMenuItem
                className="motor-item"
                onSelect={() => data.onReparent(agent.id, null)}
              >
                <span className="motor-dot" aria-hidden />
                <span className="motor-item-label">desligar do líder (vira raiz)</span>
              </DropdownMenuItem>
            )}
            <DropdownMenuLabel>mover para time</DropdownMenuLabel>
            <DropdownMenuItem
              className={cn("motor-item", !agent.teamId && "on")}
              onSelect={() => data.onMoverParaTime(agent.id, null)}
            >
              <span className="motor-dot" aria-hidden />
              <span className="motor-item-label text-faint" title={glossa("dispatch")}>
                sem time (dispatch livre)
              </span>
            </DropdownMenuItem>
            {times.map((candidato) => {
              const on = agent.teamId === candidato.id;
              return (
                <DropdownMenuItem
                  key={candidato.id}
                  className={cn("motor-item", on && "on")}
                  onSelect={() => data.onMoverParaTime(agent.id, candidato.id)}
                >
                  <span
                    className="motor-dot"
                    style={{ background: teamHue(candidato.id), borderColor: teamHue(candidato.id) }}
                    aria-hidden
                  />
                  <span className="motor-item-label">
                    {candidato.dominio} / {candidato.foco}
                  </span>
                  {!candidato.arquitetoAgentId && <span className="motor-item-ctx">sem arquiteto</span>}
                </DropdownMenuItem>
              );
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <div className="org-node-role">
        {agent.role}
        {ehArquiteto && (
          <span className="org-node-arquiteto" title={glossa("arquiteto")}>
            {" "}
            · arquiteto
          </span>
        )}
      </div>
      <div className="org-node-foot">
        <span className="motor" title={glossa("motor")}>
          {data.motorLabel}
        </span>
        {agent.pinnedTemplateVersionId && (
          <span className="pin" title="contratado de um molde (versão pinada)">
            <Stamp size={10} strokeWidth={2.2} /> molde
          </span>
        )}
      </div>
      <Handle type="source" position={Position.Bottom} isConnectable={false} />
    </div>
  );
}

// Registro estático (recriar o objeto a cada render faz o React Flow remontar os nós).
const nodeTypes = { agente: AgenteNode };

export function TeamCanvas(props: {
  companyId: string;
  agents: AgentSummary[];
  engines: Engine[];
  teams: Team[];
  statusById: ReadonlyMap<string, AgentBoardStatus>;
}) {
  return (
    <ReactFlowProvider>
      <CanvasDaCadeia {...props} />
    </ReactFlowProvider>
  );
}

function CanvasDaCadeia({
  companyId,
  agents,
  engines,
  teams,
  statusById,
}: {
  companyId: string;
  agents: AgentSummary[];
  engines: Engine[];
  teams: Team[];
  statusById: ReadonlyMap<string, AgentBoardStatus>;
}) {
  const queryClient = useQueryClient();
  const rf = useReactFlow();
  const arrastando = useRef(false);

  function invalidarOrg() {
    queryClient.invalidateQueries({ queryKey: queryKeys.agents(companyId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.teams(companyId) });
  }

  function toastDeFalha(error: unknown, acao: string) {
    const mensagem = error instanceof ApiError ? error.message : `não deu para ${acao}`;
    pushToast(mensagem, "bad");
  }

  const reparent = useMutation({
    mutationFn: ({ agentId, novoLiderId }: { agentId: string; novoLiderId: string | null }) =>
      reparentAgent(companyId, agentId, novoLiderId),
    onSuccess: (atualizado) => {
      invalidarOrg();
      pushToast(
        atualizado.reportsToId
          ? `Cadeia religada — ${atualizado.name} tem novo líder`
          : `${atualizado.name} agora é raiz da cadeia`,
      );
    },
    onError: (error) => {
      toastDeFalha(error, "religar a cadeia");
      reassentar(); // o backend recusou (ex.: ciclo) — o canvas volta a espelhar o dado real
    },
  });

  const moverParaTime = useMutation({
    mutationFn: ({ agentId, teamId }: { agentId: string; teamId: string | null }) =>
      assignAgentTeam(companyId, agentId, teamId),
    onSuccess: (atualizado) => {
      invalidarOrg();
      pushToast(atualizado.teamId ? `${atualizado.name} mudou de time` : `${atualizado.name} saiu do time`);
    },
    onError: (error) => toastDeFalha(error, "mover de time"),
  });

  const designarArquiteto = useMutation({
    mutationFn: ({ teamId, agentId }: { teamId: string; agentId: string }) =>
      setTeamArquiteto(companyId, teamId, agentId),
    onSuccess: (time) => {
      invalidarOrg();
      pushToast(`Arquiteto designado — ${time.dominio}/${time.foco} destravou o dispatch`);
    },
    onError: (error) => toastDeFalha(error, "designar o arquiteto"),
  });

  const organograma = useMemo(() => montarOrganograma(agents), [agents]);
  const timePorId = useMemo(() => new Map(teams.map((team) => [team.id, team])), [teams]);
  const agentePorId = useMemo(() => new Map(agents.map((agent) => [agent.id, agent])), [agents]);

  const nosDoLayout = useMemo<NoAgente[]>(
    () =>
      organograma.nos.flatMap((no) => {
        const agent = agentePorId.get(no.agentId);
        if (!agent) {
          return [];
        }
        const status = statusById.get(agent.id) ?? "IDLE";
        return [
          {
            id: agent.id,
            type: "agente" as const,
            position: { x: no.x, y: no.y },
            data: {
              agent,
              statusCls: statusClass(status),
              motorLabel: engineLabel(agent, engines),
              time: agent.teamId ? (timePorId.get(agent.teamId) ?? null) : null,
              times: teams,
              onReparent: (agentId: string, novoLiderId: string | null) =>
                reparent.mutate({ agentId, novoLiderId }),
              onMoverParaTime: (agentId: string, teamId: string | null) =>
                moverParaTime.mutate({ agentId, teamId }),
              onDesignarArquiteto: (teamId: string, agentId: string) =>
                designarArquiteto.mutate({ teamId, agentId }),
            },
            style: { width: ORG_NODE_W, height: ORG_NODE_H },
            connectable: false,
          },
        ];
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- as mutations são estáveis por identidade de uso
    [organograma, agentePorId, timePorId, teams, engines, statusById],
  );

  const [nodes, setNodes, onNodesChange] = useNodesState<NoAgente>(nosDoLayout);

  // O dado real re-assenta o canvas (status do poll de 2s, mutações, refetch) — MENOS no meio de
  // um arrasto: resetar posições com o nó na mão mataria o gesto.
  useEffect(() => {
    if (!arrastando.current) {
      setNodes(nosDoLayout);
    }
  }, [nosDoLayout, setNodes]);

  function reassentar() {
    setNodes(nosDoLayout);
  }

  const edges: Edge[] = organograma.arestas.map((aresta) => ({
    id: aresta.id,
    source: aresta.managerId,
    target: aresta.reportId,
    type: "smoothstep",
  }));

  return (
    <div className="team-canvas">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        fitView
        fitViewOptions={{ padding: 0.1, maxZoom: 1 }}
        minZoom={0.3}
        maxZoom={1.3}
        nodesDraggable
        nodeDragThreshold={4}
        nodesConnectable={false}
        elementsSelectable={false}
        edgesFocusable={false}
        deleteKeyCode={null}
        onNodeDragStart={() => {
          arrastando.current = true;
        }}
        onNodeDragStop={(_event, node) => {
          arrastando.current = false;
          const alvo = rf.getIntersectingNodes(node)[0];
          const agente = agentePorId.get(node.id);
          if (!alvo || !agente) {
            reassentar(); // soltou no vazio — nada muda, o layout re-assenta
            return;
          }
          if (alvo.id === agente.reportsToId) {
            reassentar(); // já reporta a esse líder
            return;
          }
          reparent.mutate({ agentId: node.id, novoLiderId: alvo.id });
        }}
      >
        <Controls showInteractive={false} position="bottom-right" />
        {organograma.semCadeia && (
          <Panel
            position="top-center"
            className="rounded-md border border-line bg-raised px-3 py-1.5 font-mono text-[10.5px] text-faint"
          >
            nenhum reports_to definido — arraste um agente sobre o líder para ligar a cadeia
          </Panel>
        )}
      </ReactFlow>
    </div>
  );
}
