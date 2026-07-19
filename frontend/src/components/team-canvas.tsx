"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CircleArrowUp, EllipsisVertical, Stamp } from "lucide-react";
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
  repinAgent,
  setTeamArquiteto,
  setTeamGestor,
  type AgentBoardStatus,
  type AgentSummary,
  type Engine,
  type RoleTemplate,
  type Team,
} from "@/lib/api";
import { engineLabel } from "@/lib/engines";
import { glossa } from "@/lib/glossario";
import { statusClass } from "@/lib/mission-control";
import { queryKeys } from "@/lib/query-keys";
import { agenteEmDrift } from "@/lib/role-templates";
import { montarOrganograma, ORG_NODE_H, ORG_NODE_W, teamHue } from "@/lib/team-canvas";
import { pushToast } from "@/lib/toaster";
import { cn } from "@/lib/utils";

// O ORG-CHART é ESPELHO da ÁRVORE DE TIMES (ADR-0045): a chefia não se edita aqui — ela DERIVA do
// gestor do time. Por isso o arrastar-para-reparentar MORREU junto com o `PATCH /reports-to`:
// arrastar hoje só re-assenta o layout. O que MOVE a estrutura vive no menu do nó (⋮, nodrag):
// mover de time, designar arquiteto (destrava o dispatch) e designar GESTOR (move a chefia de
// todos os membros no mesmo ato). O gesto "arrastar para um TIME" é da F-ORG-H.
// Anti-ciclo e trava do arquiteto vivem no backend; um 409 vira toast e o layout re-assenta do
// dado real. Nós carregam a listra do TIME (cor determinística) e o LED vivo do agregado de 2s.
// Lib: @xyflow/react v12.

type DadosDoNo = {
  agent: AgentSummary;
  statusCls: string;
  motorLabel: string;
  time: Team | null;
  times: Team[];
  // R10-A/D4-D5: o molde do agente derivou (pinou versão velha) → badge clicável de re-pin. O alvo
  // é a versão CORRENTE do molde (null quando não há drift). Derivado na tela (agenteEmDrift).
  emDrift: boolean;
  driftTargetVersionId: string | null;
  onRepin: (agentId: string, versionId: string) => void;
  onDesignarGestor: (teamId: string, agentId: string) => void;
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
            {time && time.gestorAgentId !== agent.id && (
              <DropdownMenuItem
                className="motor-item"
                onSelect={() => data.onDesignarGestor(time.id, agent.id)}
              >
                <span className="motor-dot" aria-hidden />
                <span className="motor-item-label">designar gestor do time</span>
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
        {data.emDrift && data.driftTargetVersionId ? (
          <button
            type="button"
            className="pin drift nodrag"
            title="o molde ganhou uma versão nova — atualizar este agente (substitui motor e skills)"
            onClick={() => data.onRepin(agent.id, data.driftTargetVersionId as string)}
          >
            <CircleArrowUp size={10} strokeWidth={2.2} /> atualizar molde
          </button>
        ) : (
          agent.pinnedTemplateVersionId && (
            <span className="pin" title="contratado de um molde (versão pinada)">
              <Stamp size={10} strokeWidth={2.2} /> molde
            </span>
          )
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
  roleTemplates: RoleTemplate[];
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
  roleTemplates,
  statusById,
}: {
  companyId: string;
  agents: AgentSummary[];
  engines: Engine[];
  teams: Team[];
  roleTemplates: RoleTemplate[];
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

  // ADR-0045: designar o GESTOR é o que MOVE a chefia agora — os membros do time passam a
  // reportar a ele no mesmo ato (o backend re-deriva a subárvore; o canvas só espelha).
  const designarGestor = useMutation({
    mutationFn: ({ teamId, agentId }: { teamId: string; agentId: string | null }) =>
      setTeamGestor(companyId, teamId, agentId),
    onSuccess: (time) => {
      invalidarOrg();
      pushToast(
        time.gestorAgentId
          ? `Gestor designado — a chefia de ${time.dominio}/${time.foco} deriva dele`
          : `${time.dominio}/${time.foco} ficou sem gestor — a chefia sobe pro time-pai`,
      );
    },
    onError: (error) => toastDeFalha(error, "designar o gestor"),
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

  // R10-A/D5-D6: re-pin COMPLETO — re-aplica o preset da versão corrente (motor + skillset) e move
  // o pin. SUBSTITUI (ajustes manuais descartados); mão soberana board-only. Some o drift-badge.
  const repin = useMutation({
    mutationFn: ({ agentId, versionId }: { agentId: string; versionId: string }) =>
      repinAgent(companyId, agentId, versionId),
    onSuccess: (atualizado) => {
      invalidarOrg();
      pushToast(`${atualizado.name} atualizado para a versão atual do molde`);
    },
    onError: (error) => toastDeFalha(error, "atualizar o molde do agente"),
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
        // Drift derivado NA TELA (D4): a versão pinada ≠ a corrente do molde. O alvo do re-pin é a
        // versão corrente desse molde (o pinnedTemplateId é o read-join que o backend expõe).
        const emDrift = agenteEmDrift(agent, roleTemplates);
        const driftTargetVersionId = emDrift
          ? (roleTemplates.find((template) => template.id === agent.pinnedTemplateId)?.currentVersionId ??
            null)
          : null;
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
              emDrift,
              driftTargetVersionId,
              onRepin: (agentId: string, versionId: string) => repin.mutate({ agentId, versionId }),
              onDesignarGestor: (teamId: string, agentId: string) =>
                designarGestor.mutate({ teamId, agentId }),
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
    [organograma, agentePorId, timePorId, teams, engines, statusById, roleTemplates],
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
        onNodeDragStop={() => {
          // ADR-0045: arrastar-para-reparentar MORREU com o `PATCH /reports-to`. A chefia deriva da
          // árvore de times, então soltar um agente em cima de outro não significa mais nada — o
          // layout apenas re-assenta. O gesto certo (arrastar para um TIME) é da F-ORG-H.
          arrastando.current = false;
          reassentar();
        }}
      >
        <Controls showInteractive={false} position="bottom-right" />
        {organograma.semCadeia && (
          <Panel
            position="top-center"
            className="rounded-md border border-line bg-raised px-3 py-1.5 font-mono text-[10.5px] text-faint"
          >
            nenhuma chefia ainda — designe o gestor de um time (menu ⋮ do agente) e a cadeia aparece
          </Panel>
        )}
      </ReactFlow>
    </div>
  );
}
