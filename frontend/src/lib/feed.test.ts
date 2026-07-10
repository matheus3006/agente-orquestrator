import { describe, expect, it } from "vitest";
import type { ActivityEvent } from "./api";
import { buildFeedEntry, type FeedLookups } from "./feed";

const LOOKUPS: FeedLookups = {
  agentName: (id) => ({ "agent-nix": "Nix", "agent-lyra": "Lyra" })[id ?? ""] ?? null,
  issueTitle: (id) =>
    ({ "issue-billing": "Deploy do servico de billing", "issue-schema": "Migrar schema de billing" })[
      id ?? ""
    ] ?? null,
  issueSeq: (id) => ({ "issue-billing": 7, "issue-schema": 12 })[id ?? ""] ?? null,
  issueRisk: (id) => ({ "issue-schema": 5 })[id ?? ""] ?? null,
};

function event(overrides: Partial<ActivityEvent>): ActivityEvent {
  return {
    id: "ev-1",
    actorType: "BOARD",
    actorId: "b0a7d000-0000-4000-8000-000000000001",
    action: "ISSUE_REOPENED",
    entityType: "issue",
    entityId: "issue-billing",
    detail: null,
    createdAt: "2026-07-02T17:44:48.785527Z",
    ...overrides,
  };
}

const text = (entry: ReturnType<typeof buildFeedEntry>) =>
  entry.segments.map((s) => s.text).join("");

describe("buildFeedEntry (feed do shape REAL do activity_log)", () => {
  it("ISSUE_BLOCKED: agente + título + run curto, tom bad", () => {
    const entry = buildFeedEntry(
      event({
        action: "ISSUE_BLOCKED",
        actorType: "AGENT",
        actorId: "agent-nix",
        detail: { runId: "213eb466-93a1-4dd0-9256-684ead1235a2" },
      }),
      LOOKUPS,
    );
    expect(entry.tone).toBe("bad");
    expect(entry.icon).toBe("blocked");
    // #N vem ANTES do título (ADR-0033): o mesmo número do board, em mono (chip de id)
    expect(text(entry)).toBe("Nix travou em #7 Deploy do servico de billing — run 213eb466");
    expect(entry.segments[0]).toEqual({ kind: "strong", text: "Nix" });
    expect(entry.segments).toContainEqual({ kind: "mono", text: "#7" });
    expect(entry.segments).toContainEqual({ kind: "strong", text: "Deploy do servico de billing" });
    expect(entry.segments.at(-1)).toEqual({ kind: "mono", text: "run 213eb466" });
  });

  it("ISSUE_REOPENED pelo board (detail null é normal)", () => {
    const entry = buildFeedEntry(event({}), LOOKUPS);
    expect(entry.tone).toBe("info");
    expect(entry.icon).toBe("reopened");
    expect(text(entry)).toBe("board reabriu #7 Deploy do servico de billing");
  });

  it("ISSUE_SUBMITTED_FOR_REVIEW: risco resolvido vira o mono do protótipo", () => {
    const entry = buildFeedEntry(
      event({
        action: "ISSUE_SUBMITTED_FOR_REVIEW",
        actorType: "AGENT",
        actorId: "agent-lyra",
        entityId: "issue-schema",
        detail: { reviewId: "aaaa1111-2222-3333-4444-555566667777", runId: "r" },
      }),
      LOOKUPS,
    );
    expect(entry.tone).toBe("warn");
    expect(entry.icon).toBe("review");
    expect(text(entry)).toBe("Lyra estacionou #12 Migrar schema de billing para review — risco 5 ≥ limiar");
  });

  it("ISSUE_SUBMITTED_FOR_REVIEW sem risco resolvível ancora no ciclo", () => {
    const entry = buildFeedEntry(
      event({
        action: "ISSUE_SUBMITTED_FOR_REVIEW",
        actorType: "AGENT",
        actorId: "agent-lyra",
        entityId: "issue-desconhecida",
        detail: { reviewId: "aaaa1111-2222-3333-4444-555566667777" },
      }),
      LOOKUPS,
    );
    expect(text(entry)).toContain("ciclo aaaa1111");
  });

  it("REVIEW_APPROVED: ciclo curto + título", () => {
    const entry = buildFeedEntry(
      event({
        action: "REVIEW_APPROVED",
        detail: { reviewId: "bbbb2222-3333-4444-5555-666677778888" },
      }),
      LOOKUPS,
    );
    expect(entry.tone).toBe("good");
    expect(text(entry)).toBe("board aprovou o ciclo bbbb2222 de #7 Deploy do servico de billing");
  });

  it("SKILL_ATTACHED: resolve issue e agente pelos IDs do detail", () => {
    const entry = buildFeedEntry(
      event({
        action: "SKILL_ATTACHED",
        entityType: "issue_skill_attachment",
        entityId: "attach-1",
        detail: { issueId: "issue-billing", agentId: "agent-nix", version: 4 },
      }),
      LOOKUPS,
    );
    expect(entry.icon).toBe("skill");
    expect(text(entry)).toBe("board anexou uma skill à #7 Deploy do servico de billing para Nix");
  });

  it("SKILL_IMPORTED: resumo do import no mono", () => {
    const entry = buildFeedEntry(
      event({
        action: "SKILL_IMPORTED",
        entityType: "skill_catalog",
        entityId: null,
        detail: { created: 2, updated: 1, unchanged: 80 },
      }),
      LOOKUPS,
    );
    expect(text(entry)).toBe("board importou o catálogo de skills — 2 novas · 1 atualizadas · 80 iguais");
  });

  it("issue não resolvível nos lookups degrada para id curto (nunca quebra)", () => {
    const entry = buildFeedEntry(event({ entityId: "0d9f3a10-zzzz" }), LOOKUPS);
    expect(text(entry)).toBe("board reabriu 0d9f3a10");
    // segmento do id é mono, não strong
    expect(entry.segments.at(-1)?.kind).toBe("mono");
  });

  it("ação desconhecida degrada para o nome da ação (vocabulário cresce por fatia)", () => {
    const entry = buildFeedEntry(event({ action: "NOVA_ACAO_FUTURA" }), LOOKUPS);
    expect(entry.tone).toBe("info");
    expect(entry.icon).toBe("generic");
    expect(text(entry)).toBe("board NOVA_ACAO_FUTURA");
  });

  it("agente sem nome resolvível degrada para 'agente'", () => {
    const entry = buildFeedEntry(
      event({ action: "ISSUE_BLOCKED", actorType: "AGENT", actorId: "agent-fantasma", detail: {} }),
      LOOKUPS,
    );
    expect(entry.segments[0]).toEqual({ kind: "strong", text: "agente" });
  });

  it("ator SYSTEM vira 'sistema' — coleta sem humano nunca aparece como 'board'", () => {
    const entry = buildFeedEntry(
      event({ action: "ROWS_COLLECTED", actorType: "SYSTEM", actorId: null, detail: null }),
      LOOKUPS,
    );
    expect(entry.segments[0]).toEqual({ kind: "strong", text: "sistema" });
    expect(text(entry)).toBe("sistema ROWS_COLLECTED");
  });

  it("ator BOARD segue 'board' (a fatia SYSTEM não vaza para o humano)", () => {
    const entry = buildFeedEntry(
      event({ action: "PREVIEW_SERVER_STARTED", actorType: "BOARD" }),
      LOOKUPS,
    );
    expect(entry.segments[0]).toEqual({ kind: "strong", text: "board" });
    expect(text(entry)).toBe("board PREVIEW_SERVER_STARTED");
  });
});
