import { describe, expect, it } from "vitest";
import type { PreviewArtifactSummary } from "./api";
import { artifactForRun, fmtSize, isPreviewNovo, pickDefaultArtifact, versionLabel } from "./preview";

// Helpers puros do preview (F.9/ADR-0021). A lista chega do servidor da mais
// nova para a mais antiga (version desc) — os helpers assumem essa ordem.

function artifact(overrides: Partial<PreviewArtifactSummary>): PreviewArtifactSummary {
  return {
    id: "pa-1",
    version: 1,
    runId: "run-1",
    agentId: "agent-1",
    contentHash: "hash-1",
    sizeBytes: 1024,
    createdAt: "2026-07-05T12:00:00Z",
    ...overrides,
  };
}

describe("pickDefaultArtifact (a tela abre na versão mais nova)", () => {
  it("primeira da lista (version desc)", () => {
    const versoes = [artifact({ id: "pa-3", version: 3 }), artifact({ id: "pa-2", version: 2 })];
    expect(pickDefaultArtifact(versoes)?.id).toBe("pa-3");
  });

  it("lista vazia → null", () => {
    expect(pickDefaultArtifact([])).toBeNull();
  });
});

describe("artifactForRun (a Review embute a versão EXATA do run revisado)", () => {
  const versoes = [
    artifact({ id: "pa-3", version: 3, runId: "run-c" }),
    artifact({ id: "pa-2", version: 2, runId: "run-b" }),
  ];

  it("acha pela igualdade de runId", () => {
    expect(artifactForRun(versoes, "run-b")?.version).toBe(2);
  });

  it("run sem captura → null (o card mostra o motivo, nunca outra versão)", () => {
    expect(artifactForRun(versoes, "run-x")).toBeNull();
  });
});

describe("versionLabel", () => {
  it("v + ordinal", () => {
    expect(versionLabel(artifact({ version: 7 }))).toBe("v7");
  });
});

describe("isPreviewNovo (badge 'novo' do Mission Control)", () => {
  const now = Date.parse("2026-07-05T12:00:00Z");

  it("capturado há menos de 1h é novo", () => {
    expect(isPreviewNovo("2026-07-05T11:30:00Z", now)).toBe(true);
  });

  it("capturado há mais de 1h não é novo", () => {
    expect(isPreviewNovo("2026-07-05T10:59:59Z", now)).toBe(false);
  });

  it("clock skew (futuro) ainda é novo, nunca negativo-esquisito", () => {
    expect(isPreviewNovo("2026-07-05T12:00:05Z", now)).toBe(true);
  });
});

describe("fmtSize (metadados da versão)", () => {
  it("bytes abaixo de 1 KB", () => {
    expect(fmtSize(512)).toBe("512 B");
  });

  it("KB com uma casa", () => {
    expect(fmtSize(12_648)).toBe("12.4 KB");
  });

  it("MB com uma casa (o cap é 2 MB)", () => {
    expect(fmtSize(1_572_864)).toBe("1.5 MB");
  });
});
