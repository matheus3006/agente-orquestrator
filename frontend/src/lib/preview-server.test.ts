import { describe, expect, it } from "vitest";
import {
  fmtTtl,
  splitStartFailure,
  ttlRemainingNow,
  workspaceAvancou,
} from "./preview-server";

// Helpers puros do preview vivo (F.10/ADR-0023). O runtime mora no backend;
// aqui só a apresentação: TTL legível, aviso "workspace avançou" e a falha
// de subida separada em motivo + tail.

describe("fmtTtl", () => {
  it("formata segundos como mm:ss", () => {
    expect(fmtTtl(899)).toBe("14:59");
    expect(fmtTtl(60)).toBe("1:00");
    expect(fmtTtl(5)).toBe("0:05");
  });

  it("nunca fica negativo (reaper prestes a ceifar)", () => {
    expect(fmtTtl(0)).toBe("0:00");
    expect(fmtTtl(-30)).toBe("0:00");
  });
});

describe("ttlRemainingNow", () => {
  it("desconta o tempo desde o fetch do TTL reportado", () => {
    // fetch reportou 600s faltando, 90s atrás → 510s agora.
    expect(ttlRemainingNow(600, 90_000)).toBe(510);
  });

  it("chega a zero e para (nunca negativo)", () => {
    expect(ttlRemainingNow(30, 45_000)).toBe(0);
  });
});

describe("workspaceAvancou", () => {
  it("manifest de run POSTERIOR ao run sob revisão = workspace avançou", () => {
    expect(workspaceAvancou("run-novo", "run-revisado")).toBe(true);
  });

  it("manifest do PRÓPRIO run sob revisão = tudo coerente", () => {
    expect(workspaceAvancou("run-revisado", "run-revisado")).toBe(false);
  });
});

describe("splitStartFailure", () => {
  it("separa o motivo do tail quando o backend mandou as últimas linhas", () => {
    const error =
      "servidor morreu ao subir (porta 3000 nunca respondeu)\n--- ultimas linhas ---\nEADDRINUSE :3000\nnode exited";
    expect(splitStartFailure(error)).toEqual({
      reason: "servidor morreu ao subir (porta 3000 nunca respondeu)",
      tail: "EADDRINUSE :3000\nnode exited",
    });
  });

  it("falha sem tail vira só o motivo", () => {
    expect(splitStartFailure("nenhum servidor declarado para esta Issue (sem manifest)")).toEqual({
      reason: "nenhum servidor declarado para esta Issue (sem manifest)",
      tail: null,
    });
  });
});
