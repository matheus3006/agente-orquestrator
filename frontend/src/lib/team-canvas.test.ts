import { describe, expect, it } from "vitest";
import type { AgentSummary } from "./api";
import { montarOrganograma, ORG_NODE_H, ORG_NODE_W } from "./team-canvas";

// Layout puro do organograma: determinístico (irmãos/raízes por nome), pai centrado sobre os
// filhos, órfão vira raiz, ciclo não derruba a montagem. As POSIÇÕES são contrato — o React Flow
// só desenha o que sai daqui.

const agent = (o: Partial<AgentSummary> & { id: string; name: string }): AgentSummary => ({
  companyId: "c1",
  role: "executor",
  persona: null,
  reportsToId: null,
  adapterType: "process",
  executionTarget: "LOCAL",
  model: null,
  effort: null,
  pinnedTemplateVersionId: null,
  pinnedTemplateId: null,
  teamId: null,
  createdAt: "2026-07-04T00:00:00Z",
  ...o,
});

describe("montarOrganograma", () => {
  it("cadeia simples: líder em cima, report embaixo, aresta ligando", () => {
    const org = montarOrganograma([
      agent({ id: "lider", name: "Atlas" }),
      agent({ id: "dev", name: "Nix", reportsToId: "lider" }),
    ]);
    const [atlas, nix] = [org.nos.find((n) => n.agentId === "lider")!, org.nos.find((n) => n.agentId === "dev")!];
    expect(atlas.profundidade).toBe(0);
    expect(nix.profundidade).toBe(1);
    expect(nix.y).toBeGreaterThan(atlas.y);
    expect(org.arestas).toEqual([{ id: "lider->dev", managerId: "lider", reportId: "dev" }]);
    expect(org.semCadeia).toBe(false);
  });

  it("pai centraliza sobre dois filhos; irmãos em ordem alfabética", () => {
    const org = montarOrganograma([
      agent({ id: "lider", name: "Atlas" }),
      agent({ id: "b", name: "Vega", reportsToId: "lider" }),
      agent({ id: "a", name: "Echo", reportsToId: "lider" }),
    ]);
    const pos = Object.fromEntries(org.nos.map((n) => [n.agentId, n]));
    expect(pos.a.x).toBeLessThan(pos.b.x); // Echo antes de Vega
    const centroFilhos = (pos.a.x + pos.b.x + ORG_NODE_W) / 2;
    expect(pos.lider.x + ORG_NODE_W / 2).toBeCloseTo(centroFilhos, 5);
    expect(pos.a.y).toBe(ORG_NODE_H + 64);
  });

  it("reports_to apontando para fora do roster → agente vira raiz (não some)", () => {
    const org = montarOrganograma([agent({ id: "x", name: "Rune", reportsToId: "fantasma" })]);
    expect(org.nos).toHaveLength(1);
    expect(org.nos[0].profundidade).toBe(0);
    expect(org.arestas).toEqual([]);
  });

  it("roster plano (ninguém reporta) → todas raízes e a dica semCadeia acende", () => {
    const org = montarOrganograma([agent({ id: "a", name: "Atlas" }), agent({ id: "b", name: "Nix" })]);
    expect(org.nos.every((n) => n.profundidade === 0)).toBe(true);
    expect(org.semCadeia).toBe(true);
  });

  it("ciclo (defensivo) não derruba: menor nome vira raiz e todos aparecem", () => {
    const org = montarOrganograma([
      agent({ id: "a", name: "Atlas", reportsToId: "b" }),
      agent({ id: "b", name: "Nix", reportsToId: "a" }),
    ]);
    expect(org.nos).toHaveLength(2);
    const atlas = org.nos.find((n) => n.agentId === "a")!;
    expect(atlas.profundidade).toBe(0); // Atlas < Nix
    expect(org.arestas).toHaveLength(2);
  });

  it("roster vazio → organograma vazio sem dica", () => {
    const org = montarOrganograma([]);
    expect(org.nos).toEqual([]);
    expect(org.semCadeia).toBe(false);
  });
});
