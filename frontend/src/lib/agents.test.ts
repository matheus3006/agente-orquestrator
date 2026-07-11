import { describe, expect, it } from "vitest";
import type { IssueSummary, MissionControlAgent, MissionControlStep } from "./api";
import { rosterDoing } from "./agents";

const step = (o: Partial<MissionControlStep> = {}): MissionControlStep => ({
  runId: "run-1",
  type: "STDOUT",
  detail: "[done] ok",
  createdAt: "2026-07-02T10:00:00Z",
  ...o,
});

const station = (o: Partial<MissionControlAgent> = {}): MissionControlAgent => ({
  agent: { id: "ag-1", name: "Atlas", role: "architect" },
  status: "IDLE",
  activeRun: null,
  lastSteps: [],
  lastPreview: null,
  ...o,
});

const issue = (o: Partial<IssueSummary> = {}): IssueSummary => ({
  id: "iss-1",
  companyId: "c1",
  seq: 1,
  title: "Refatorar pagamentos",
  description: null,
  status: "OPEN",
  riskLevel: 3,
  assigneeAgentId: "ag-1",
  executionLockRunId: null,
  createdAt: "2026-07-02T09:00:00Z",
  corrigeIssueId: null,
  corrigeSeq: null,
  conversationId: null,
  ...o,
});

describe("rosterDoing (a linha 'doing' do roster — reusa stationIssueLine, IDLE é próprio)", () => {
  it("RUNNING → 'executando' + título do run ativo", () => {
    const doing = rosterDoing(
      station({
        status: "RUNNING",
        activeRun: { id: "r", issueId: "iss-1", issueSeq: 1, issueTitle: "Refatorar pagamentos", startedAt: null, attempt: 1 },
      }),
      [],
    );
    expect(doing.verb).toBe("executando");
    expect(doing.title).toBe("Refatorar pagamentos");
  });

  it("QUEUED → 'na fila para' + título", () => {
    const doing = rosterDoing(
      station({
        status: "QUEUED",
        activeRun: { id: "r", issueId: "iss-1", issueSeq: 1, issueTitle: "Refatorar pagamentos", startedAt: null, attempt: 1 },
      }),
      [],
    );
    expect(doing.verb).toBe("na fila para");
  });

  it("BLOCKED → 'travado em' + título resolvido pela lista de issues", () => {
    const doing = rosterDoing(
      station({ status: "BLOCKED" }),
      [issue({ status: "BLOCKED" })],
    );
    expect(doing.verb).toBe("travado em");
    expect(doing.title).toBe("Refatorar pagamentos");
  });

  it("IDLE que já executou (tem lastSteps) → 'ocioso — último run concluído'", () => {
    const doing = rosterDoing(station({ status: "IDLE", lastSteps: [step()] }), []);
    expect(doing.note).toBe("ocioso — último run concluído");
    expect(doing.verb).toBeNull();
  });

  it("IDLE que nunca executou (sem lastSteps) → 'ocioso — nunca executou'", () => {
    const doing = rosterDoing(station({ status: "IDLE", lastSteps: [] }), []);
    expect(doing.note).toBe("ocioso — nunca executou");
  });

  it("agente ainda fora do snapshot do agregado → nota neutra 'sincronizando…'", () => {
    const doing = rosterDoing(undefined, []);
    expect(doing.note).toBe("sincronizando…");
    expect(doing.title).toBeNull();
  });
});
