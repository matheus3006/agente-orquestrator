import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch, decideApproval } from "./api";

// O fetcher é o ÚNICO ponto de auth e de tradução de erro do app (ADR-0013):
// X-Board-User entra aqui (trocar por cookie = tocar 1 arquivo) e o formato
// {error, message} do ApiExceptionHandler vira ApiError tipado.

const fetchMock = vi.fn();

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  vi.stubEnv("NEXT_PUBLIC_DEV_BOARD_USER", "b0a7d000-0000-4000-8000-000000000001");
  vi.stubEnv("BACKEND_ORIGIN", "http://backend-teste:8080");
});

afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("apiFetch", () => {
  it("no servidor, prefixa o BACKEND_ORIGIN e injeta o X-Board-User", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [{ id: "c1" }]));

    const body = await apiFetch<Array<{ id: string }>>("/api/companies");

    expect(body).toEqual([{ id: "c1" }]);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend-teste:8080/api/companies");
    expect(new Headers(init.headers).get("X-Board-User")).toBe(
      "b0a7d000-0000-4000-8000-000000000001",
    );
  });

  it("no browser, usa caminho same-origin (o rewrite /api leva ao Spring)", async () => {
    vi.stubGlobal("window", {});
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }));

    await apiFetch("/api/companies/c1/mission-control");

    expect(fetchMock.mock.calls[0][0]).toBe("/api/companies/c1/mission-control");
  });

  it("POST com body ganha Content-Type json e serializa o payload", async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "novo" }));

    await apiFetch("/api/companies", {
      method: "POST",
      body: { name: "Demo", slug: "demo" },
    });

    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ name: "Demo", slug: "demo" }));
    expect(new Headers(init.headers).get("Content-Type")).toBe("application/json");
  });

  it("resposta {error, message} vira ApiError com status + campos do backend", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(409, { error: "conflict", message: "Issue ja esta em execucao" }),
    );

    const failure = await apiFetch("/api/x").catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    const apiError = failure as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.error).toBe("conflict");
    expect(apiError.message).toBe("Issue ja esta em execucao");
  });

  it("erro sem corpo JSON degrada para http_error sem lançar parse", async () => {
    fetchMock.mockResolvedValue(new Response("Bad Gateway", { status: 502 }));

    const failure = await apiFetch("/api/x").catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    const apiError = failure as ApiError;
    expect(apiError.status).toBe(502);
    expect(apiError.error).toBe("http_error");
  });

  it("204 (e corpo vazio) resolve como undefined", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(apiFetch("/api/x")).resolves.toBeUndefined();
  });
});

describe("decideApproval (o 422 do grill é RETORNO, não exceção — ADR-0020)", () => {
  it("422 {grilled, probe, rounds} vira outcome 'grilled' com o probe legível", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(422, {
        grilled: true,
        probe: "Registro obrigatorio (ADR-0020): qual e a razao desta decisao?",
        rounds: 1,
      }),
    );

    const outcome = await decideApproval("c1", "ap1", { decision: "APPROVE" });

    expect(outcome).toEqual({
      kind: "grilled",
      probe: "Registro obrigatorio (ADR-0020): qual e a razao desta decisao?",
      rounds: 1,
    });
  });

  it("200 com a approval decidida vira outcome 'committed'", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { id: "ap1", status: "APPROVED", racional: "nicho validado" }),
    );

    const outcome = await decideApproval("c1", "ap1", {
      decision: "APPROVE",
      racional: "nicho validado",
    });

    expect(outcome.kind).toBe("committed");
    if (outcome.kind === "committed") {
      expect(outcome.approval.status).toBe("APPROVED");
    }
  });

  it("erros que NÃO são grill (409 já decidida) seguem virando ApiError", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(409, { error: "conflict", message: "Approval ja decidida" }),
    );

    const failure = await decideApproval("c1", "ap1", { decision: "REJECT" }).catch(
      (error: unknown) => error,
    );

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(409);
  });
});
