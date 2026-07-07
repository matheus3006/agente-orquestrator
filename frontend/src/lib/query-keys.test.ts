import { describe, expect, it } from "vitest";
import { queryKeys } from "./query-keys";

// O registro central é o contrato entre fetchers e invalidação (ADR-0013):
// toda query nova DEVE nascer aqui; invalidação por prefixo depende dos shapes.

describe("queryKeys", () => {
  it("parametriza por company: companies diferentes nunca colidem", () => {
    expect(queryKeys.missionControl("c1")).not.toEqual(queryKeys.missionControl("c2"));
    expect(queryKeys.issues("c1")).not.toEqual(queryKeys.issues("c2"));
  });

  it("é determinístico: a mesma chamada produz a mesma chave", () => {
    expect(queryKeys.runSteps("c1", "r9")).toEqual(queryKeys.runSteps("c1", "r9"));
    expect(queryKeys.reviews("c1", "PENDING")).toEqual(queryKeys.reviews("c1", "PENDING"));
  });

  it("companyScope é prefixo de TODA chave da company (invalidação em bloco)", () => {
    const scope = queryKeys.companyScope("c1");
    const scoped = [
      queryKeys.missionControl("c1"),
      queryKeys.issues("c1"),
      queryKeys.issue("c1", "i1"),
      queryKeys.issueRuns("c1", "i1"),
      queryKeys.runSteps("c1", "r1"),
      queryKeys.activity("c1"),
      queryKeys.reviews("c1", "PENDING"),
      queryKeys.agents("c1"),
      queryKeys.agentSkills("c1", "a1"),
      queryKeys.engines("c1"),
    ];
    for (const key of scoped) {
      expect(key.slice(0, scope.length)).toEqual([...scope]);
    }
  });

  it("engines fica sob o escopo da company (catálogo global, mas cache por-company)", () => {
    expect(queryKeys.engines("c1")).toEqual(["company", "c1", "engines"]);
    expect(queryKeys.engines("c1")).not.toEqual(queryKeys.engines("c2"));
  });

  it("issue detail compartilha o prefixo da lista (invalidar a lista pega o detail)", () => {
    const list = queryKeys.issues("c1");
    const detail = queryKeys.issue("c1", "i42");
    expect(detail.slice(0, list.length)).toEqual([...list]);
  });

  it("reviews distingue o filtro de status na chave", () => {
    expect(queryKeys.reviews("c1", "PENDING")).not.toEqual(queryKeys.reviews("c1", "APPROVED"));
  });

  it("companies (global, fora do escopo de company) existe para o picker da raiz", () => {
    expect(queryKeys.companies()).toEqual(queryKeys.companies());
    expect(queryKeys.companies()[0]).not.toBe(queryKeys.companyScope("c1")[0]);
  });

  it("as duas projeções da fila de approvals compartilham o prefixo approvalsScope (T.3)", () => {
    const scope = queryKeys.approvalsScope("c1");
    for (const key of [
      queryKeys.approvalsQueue("c1", "PENDING"),
      queryKeys.approvalsAnchored("c1", "conv-1"),
    ]) {
      expect(key.slice(0, scope.length)).toEqual([...scope]);
    }
  });

  it("a thread fica sob o prefixo de conversations; o report sob o de issues (T.3)", () => {
    const conversas = queryKeys.conversations("c1");
    expect(queryKeys.conversationTurns("c1", "conv-1").slice(0, conversas.length)).toEqual([
      ...conversas,
    ]);
    const issues = queryKeys.issues("c1");
    expect(queryKeys.issueReport("c1", "i1").slice(0, issues.length)).toEqual([...issues]);
  });
});
