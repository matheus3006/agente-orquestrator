// A projeção da zona ESQUERDA da Central docada (T.3-FE, mockup §6): estações
// do agregado agrupadas por TIME — Liderança (o CEO, topo da cadeia) primeiro,
// times na ordem do catálogo, avulsos por último. O gate do arquiteto (T.2)
// aparece no CABEÇALHO do grupo: quem impõe é o backend; aqui só espelha.

import type { AgentSummary, MissionControlAgent, Team } from "./api";
import { teamHue } from "./team-canvas";

export interface GrupoDeEstacoes {
  /** Identidade do chip/grupo: "lideranca" | id do time | "sem-time". */
  key: string;
  kind: "lideranca" | "time" | "sem-time";
  nome: string;
  /** Nome do arquiteto designado (só kind=time; null = posto vago). */
  arquitetoNome: string | null;
  /** Gate espelhado (ADR-0017): time sem arquiteto não dispara trabalho. */
  semArquiteto: boolean;
  /** Cor determinística do time (a mesma listra do organograma de T.2). */
  hue: string | null;
  estacoes: MissionControlAgent[];
  rodando: number;
}

export function agruparEstacoes(
  estacoes: MissionControlAgent[],
  detalhes: AgentSummary[],
  times: Team[],
): GrupoDeEstacoes[] {
  const detalhePorId = new Map(detalhes.map((agente) => [agente.id, agente]));
  const ehCeo = (estacao: MissionControlAgent) =>
    (detalhePorId.get(estacao.agent.id)?.role ?? estacao.agent.role).toLowerCase() === "ceo";

  const lideranca: MissionControlAgent[] = [];
  const porTime = new Map<string, MissionControlAgent[]>(times.map((t) => [t.id, []]));
  const avulsos: MissionControlAgent[] = [];

  for (const estacao of estacoes) {
    if (ehCeo(estacao)) {
      lideranca.push(estacao);
      continue;
    }
    const teamId = detalhePorId.get(estacao.agent.id)?.teamId ?? null;
    const doTime = teamId !== null ? porTime.get(teamId) : undefined;
    if (doTime) {
      doTime.push(estacao);
    } else {
      avulsos.push(estacao);
    }
  }

  const rodando = (grupo: MissionControlAgent[]) =>
    grupo.filter((estacao) => estacao.status === "RUNNING").length;

  const grupos: GrupoDeEstacoes[] = [];
  if (lideranca.length > 0) {
    grupos.push({
      key: "lideranca",
      kind: "lideranca",
      nome: "Liderança",
      arquitetoNome: null,
      semArquiteto: false,
      hue: null,
      estacoes: lideranca,
      rodando: rodando(lideranca),
    });
  }
  for (const team of times) {
    const doTime = porTime.get(team.id) ?? [];
    const arquiteto =
      team.arquitetoAgentId !== null ? (detalhePorId.get(team.arquitetoAgentId) ?? null) : null;
    grupos.push({
      key: team.id,
      kind: "time",
      nome: `${team.dominio} / ${team.foco}`,
      arquitetoNome: arquiteto?.name ?? null,
      semArquiteto: team.arquitetoAgentId === null,
      hue: teamHue(team.id),
      estacoes: doTime,
      rodando: rodando(doTime),
    });
  }
  if (avulsos.length > 0) {
    grupos.push({
      key: "sem-time",
      kind: "sem-time",
      nome: "Sem time",
      arquitetoNome: null,
      semArquiteto: false,
      hue: null,
      estacoes: avulsos,
      rodando: rodando(avulsos),
    });
  }
  return grupos;
}

export interface ContagemVisivel {
  grupos: number;
  agentes: number;
}

/** O contador do cabeçalho reage ao que os chips deixam visível (mockup §6). */
export function contarVisiveis(grupos: GrupoDeEstacoes[]): ContagemVisivel {
  return {
    grupos: grupos.length,
    agentes: grupos.reduce((soma, grupo) => soma + grupo.estacoes.length, 0),
  };
}
