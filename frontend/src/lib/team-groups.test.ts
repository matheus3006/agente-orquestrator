import { describe, expect, it } from "vitest";
import type { AgentSummary, MissionControlAgent, Team } from "./api";
import { agruparEstacoes, contarVisiveis } from "./team-groups";

// A projeção da zona ESQUERDA da Central docada (T.3-FE, mockup §6): as
// estações do agregado agrupadas por TIME, com Liderança no topo (o CEO,
// topo da cadeia) e o gate do arquiteto espelhado no cabeçalho.

const estacao = (o: {
  id: string;
  name: string;
  role?: string;
  status?: MissionControlAgent["status"];
}): MissionControlAgent => ({
  agent: { id: o.id, name: o.name, role: o.role ?? "executor" },
  status: o.status ?? "IDLE",
  activeRun: null,
  lastSteps: [],
  lastPreview: null,
});

const detalhe = (o: Partial<AgentSummary> & { id: string }): AgentSummary => ({
  companyId: "c1",
  name: "Agente",
  role: "executor",
  persona: null,
  reportsToId: null,
  adapterType: "process",
  executionTarget: "LOCAL",
  model: null,
  effort: null,
  pinnedTemplateVersionId: null,
  teamId: null,
  createdAt: "2026-07-01T00:00:00Z",
  ...o,
});

const time = (o: Partial<Team> & { id: string }): Team => ({
  companyId: "c1",
  dominio: "Dev",
  foco: "frontend",
  arquitetoAgentId: null,
  createdAt: "2026-07-01T00:00:00Z",
  ...o,
});

describe("agruparEstacoes (Liderança → times → sem time; o gate do arquiteto no cabeçalho)", () => {
  it("CEO vai pra Liderança; membros vão pro grupo do seu time; resto cai em 'sem time'", () => {
    const estacoes = [
      estacao({ id: "ceo-1", name: "Atlas", role: "ceo" }),
      estacao({ id: "ag-1", name: "Lyra", status: "RUNNING" }),
      estacao({ id: "ag-2", name: "Echo" }),
    ];
    const detalhes = [
      detalhe({ id: "ceo-1", role: "ceo" }),
      detalhe({ id: "ag-1", teamId: "team-fe" }),
      detalhe({ id: "ag-2" }),
    ];
    const times = [time({ id: "team-fe", dominio: "Dev", foco: "frontend" })];

    const grupos = agruparEstacoes(estacoes, detalhes, times);

    expect(grupos.map((grupo) => grupo.kind)).toEqual(["lideranca", "time", "sem-time"]);
    expect(grupos[0].estacoes.map((e) => e.agent.name)).toEqual(["Atlas"]);
    expect(grupos[1].nome).toBe("Dev / frontend");
    expect(grupos[1].estacoes.map((e) => e.agent.name)).toEqual(["Lyra"]);
    expect(grupos[1].rodando).toBe(1);
    expect(grupos[2].estacoes.map((e) => e.agent.name)).toEqual(["Echo"]);
  });

  it("time com arquiteto designado mostra o nome; sem arquiteto marca o gate travado", () => {
    const estacoes = [estacao({ id: "ag-1", name: "Lyra" }), estacao({ id: "ag-2", name: "Nix" })];
    const detalhes = [
      detalhe({ id: "ag-1", name: "Lyra", teamId: "team-fe" }),
      detalhe({ id: "ag-2", name: "Nix", teamId: "team-smoke" }),
    ];
    const times = [
      time({ id: "team-fe", foco: "frontend", arquitetoAgentId: "ag-1" }),
      time({ id: "team-smoke", foco: "smoke" }),
    ];

    const grupos = agruparEstacoes(estacoes, detalhes, times);

    expect(grupos[0]).toMatchObject({ arquitetoNome: "Lyra", semArquiteto: false });
    expect(grupos[1]).toMatchObject({ arquitetoNome: null, semArquiteto: true });
  });

  it("time sem nenhum agente APARECE vazio (empty-state 'peça ao CEO pra montar')", () => {
    const times = [time({ id: "team-mkt", dominio: "Marketing", foco: "lançamento" })];

    const grupos = agruparEstacoes([], [], times);

    expect(grupos).toHaveLength(1);
    expect(grupos[0]).toMatchObject({ kind: "time", nome: "Marketing / lançamento", estacoes: [] });
  });

  it("Liderança e 'sem time' somem quando vazios — nunca grupo fantasma", () => {
    const estacoes = [estacao({ id: "ag-1", name: "Lyra" })];
    const detalhes = [detalhe({ id: "ag-1", teamId: "team-fe" })];
    const times = [time({ id: "team-fe" })];

    const grupos = agruparEstacoes(estacoes, detalhes, times);

    expect(grupos.map((grupo) => grupo.kind)).toEqual(["time"]);
  });
});

describe("contarVisiveis (o contador reage ao que os chips deixam visível)", () => {
  it("soma grupos e agentes só dos grupos ligados", () => {
    const estacoes = [
      estacao({ id: "ceo-1", name: "Atlas", role: "ceo" }),
      estacao({ id: "ag-1", name: "Lyra" }),
    ];
    const detalhes = [
      detalhe({ id: "ceo-1", role: "ceo" }),
      detalhe({ id: "ag-1", teamId: "team-fe" }),
    ];
    const grupos = agruparEstacoes(estacoes, detalhes, [time({ id: "team-fe" })]);

    const total = contarVisiveis(grupos.filter((grupo) => grupo.kind !== "lideranca"));

    expect(total).toEqual({ grupos: 1, agentes: 1 });
  });
});
