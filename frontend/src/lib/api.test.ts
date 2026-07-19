import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  apiFetch,
  batchDecideApprovals,
  decideApproval,
  createTeam,
  hireAgent,
  interruptRun,
  reparentTeam,
  setTeamGestor,
  streamRunSteps,
} from "./api";

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

describe("batchDecideApprovals (o lote do gate — ADR-0035/D10)", () => {
  it("POSTa decisão + racional + motivo + itens e devolve a contagem do loop", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        decided: 2,
        skipped: [{ approvalId: "ap3", motivo: "Approval ja decidida (APPROVED): ap3" }],
        results: [{ id: "ap1", status: "REJECTED" }, { id: "ap2", status: "REJECTED" }],
      }),
    );

    const outcome = await batchDecideApprovals("c1", {
      decision: "REJECT",
      racional: "time acima do caixa",
      motivo: "sem orcamento neste trimestre",
      items: [{ approvalId: "ap1" }, { approvalId: "ap2", observacao: "esse menos ainda" }],
    });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend-teste:8080/api/companies/c1/approvals/batch-decision");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      decision: "REJECT",
      racional: "time acima do caixa",
      motivo: "sem orcamento neste trimestre",
      items: [{ approvalId: "ap1" }, { approvalId: "ap2", observacao: "esse menos ainda" }],
    });
    expect(outcome.decided).toBe(2);
    expect(outcome.skipped).toHaveLength(1);
  });

  it("400 (REJECT sem motivo) segue o contrato de erro da casa: ApiError", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, { error: "bad_request", message: "Recusa em lote exige o motivo comum" }),
    );

    const failure = await batchDecideApprovals("c1", {
      decision: "REJECT",
      items: [{ approvalId: "ap1" }],
    }).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(400);
  });
});

describe("interruptRun (F-N4/ADR-0040: o botão parar)", () => {
  it("POSTa com o motivo quando informado", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { runId: "r1", status: "INTERRUPTED", processKilled: true }),
    );

    const result = await interruptRun("c1", "r1", "dono pediu parar");

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend-teste:8080/api/companies/c1/runs/r1/interrupt");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ reason: "dono pediu parar" });
    expect(result).toEqual({ runId: "r1", status: "INTERRUPTED", processKilled: true });
  });

  it("sem motivo, POSTa sem corpo (o backend aplica o default legível)", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { runId: "r1", status: "INTERRUPTED", processKilled: false }),
    );

    await interruptRun("c1", "r1");

    const [, init] = fetchMock.mock.calls[0];
    expect(init.body).toBeUndefined();
  });

  it("409 (run que já não está rodando) segue o contrato de erro da casa", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(409, { error: "conflict", message: "Run nao esta em execucao" }),
    );

    const failure = await interruptRun("c1", "r1").catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).status).toBe(409);
  });
});

describe("streamRunSteps (F-N4/ADR-0040: transporte SSE via fetch — EventSource não manda headers)", () => {
  function sseResponse(chunks: string[]): Response {
    const encoder = new TextEncoder();
    let index = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (index < chunks.length) {
          controller.enqueue(encoder.encode(chunks[index++]));
        } else {
          controller.close();
        }
      },
    });
    return new Response(body, { status: 200 });
  }

  it("monta a URL com offset e injeta X-Board-User (mesmo canal de auth do apiFetch)", async () => {
    fetchMock.mockResolvedValue(sseResponse([]));

    streamRunSteps("c1", "r1", 42, {});

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend-teste:8080/api/companies/c1/runs/r1/steps/stream?offset=42");
    expect(new Headers(init.headers).get("X-Board-User")).toBe(
      "b0a7d000-0000-4000-8000-000000000001",
    );
  });

  it("consome eventos `step` em ordem e devolve os steps parseados", async () => {
    fetchMock.mockResolvedValue(
      sseResponse([
        'event: step\ndata: {"type":"STDOUT","detail":"linha-1","createdAt":"2026-01-01T00:00:00Z"}\n\n',
        'event: step\ndata: {"type":"STDOUT","detail":"linha-2","createdAt":"2026-01-01T00:00:01Z"}\n\n',
      ]),
    );
    const steps: unknown[] = [];

    streamRunSteps("c1", "r1", 0, { onStep: (step) => steps.push(step) });

    await vi.waitFor(() => expect(steps).toHaveLength(2));
    expect(steps[0]).toEqual({ type: "STDOUT", detail: "linha-1", createdAt: "2026-01-01T00:00:00Z" });
    expect(steps[1]).toEqual({ type: "STDOUT", detail: "linha-2", createdAt: "2026-01-01T00:00:01Z" });
  });

  it("evento `status` chega via onStatus (o sinal que fecha o drawer/desliga o poll)", async () => {
    fetchMock.mockResolvedValue(sseResponse(["event: status\ndata: SUCCEEDED\n\n"]));
    const statuses: string[] = [];

    streamRunSteps("c1", "r1", 0, { onStatus: (status) => statuses.push(status) });

    await vi.waitFor(() => expect(statuses).toEqual(["SUCCEEDED"]));
  });

  it("um evento partido em dois chunks de rede ainda parseia (buffer acumula até a linha em branco)", async () => {
    fetchMock.mockResolvedValue(
      sseResponse(['event: step\ndata: {"typ', 'e":"STDOUT","detail":"junto","createdAt":"x"}\n\n']),
    );
    const steps: unknown[] = [];

    streamRunSteps("c1", "r1", 0, { onStep: (step) => steps.push(step) });

    await vi.waitFor(() => expect(steps).toHaveLength(1));
    expect(steps[0]).toEqual({ type: "STDOUT", detail: "junto", createdAt: "x" });
  });

  it("close() aborta a leitura em andamento (o signal do fetch fica aborted)", async () => {
    let capturedSignal: AbortSignal | undefined;
    fetchMock.mockImplementation((_url: string, init: RequestInit) => {
      capturedSignal = init.signal as AbortSignal;
      return new Promise(() => {}); // nunca resolve — simula uma conexão viva
    });

    const stream = streamRunSteps("c1", "r1", 0, {});

    await vi.waitFor(() => expect(capturedSignal).toBeDefined());
    stream.close();
    expect(capturedSignal?.aborted).toBe(true);
  });

  it("resposta de erro (403/404) vira onError, nunca lança sem handler", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(404, { error: "not_found", message: "Run nao encontrado nesta company" }),
    );
    const errors: unknown[] = [];

    streamRunSteps("c1", "r1", 0, { onError: (error) => errors.push(error) });

    await vi.waitFor(() => expect(errors).toHaveLength(1));
    expect(errors[0]).toBeInstanceOf(ApiError);
    expect((errors[0] as ApiError).status).toBe(404);
  });
});

// A ÁRVORE-ORGANOGRAMA no cliente (ADR-0045, F-ORG-A). Três fatos de contrato que a tela depende
// e que só o cliente prova: o hire CARREGA o time (e não carrega chefe — chefia é derivada); o
// posto de GESTOR e o PAI do time têm sub-recurso próprio (null é valor legal nos dois: limpar o
// posto / subir pra raiz). O `reparentAgent` (PATCH /reports-to) deixou de existir.
describe("árvore-organograma (ADR-0045)", () => {
  function corpoDaChamada(): Record<string, unknown> {
    return JSON.parse(fetchMock.mock.calls[0][1].body as string) as Record<string, unknown>;
  }

  it("hireAgent manda o teamId no corpo e NENHUM reportsToId (a chefia deriva do time)", async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "a1" }));

    await hireAgent("c1", { name: "Nix", role: "executor", teamId: "t1" });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend-teste:8080/api/companies/c1/agents");
    expect(init.method).toBe("POST");
    expect(corpoDaChamada()).toMatchObject({ name: "Nix", role: "executor", teamId: "t1" });
    expect(corpoDaChamada()).not.toHaveProperty("reportsToId");
  });

  it("createTeam pendura no pai quando informado, e na RAIZ por omissão", async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "t2" }));
    await createTeam("c1", "Marketing", "conteudo", "t1");
    expect(corpoDaChamada()).toMatchObject({ dominio: "Marketing", foco: "conteudo", parentTeamId: "t1" });

    fetchMock.mockReset();
    fetchMock.mockResolvedValue(jsonResponse(201, { id: "t3" }));
    await createTeam("c1", "Dev", "backend");
    expect(corpoDaChamada()).toMatchObject({ parentTeamId: null });
  });

  it("setTeamGestor bate no sub-recurso do time e aceita null (limpar o posto)", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: "t1", gestorAgentId: null }));

    await setTeamGestor("c1", "t1", null);

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend-teste:8080/api/companies/c1/teams/t1/gestor");
    expect(init.method).toBe("PATCH");
    expect(corpoDaChamada()).toEqual({ agentId: null });
  });

  it("reparentTeam move o time na árvore e aceita null (sobe pra raiz)", async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: "t2", parentTeamId: null }));

    await reparentTeam("c1", "t2", null);

    expect(fetchMock.mock.calls[0][0]).toBe("http://backend-teste:8080/api/companies/c1/teams/t2/parent");
    expect(corpoDaChamada()).toEqual({ parentTeamId: null });
  });

  it("um 409 do backend (ciclo na árvore) vira ApiError com a mensagem acionável", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(409, { error: "conflict", message: "mover fecharia um ciclo na arvore de times" }),
    );

    await expect(reparentTeam("c1", "t1", "t9")).rejects.toBeInstanceOf(ApiError);
  });
});
