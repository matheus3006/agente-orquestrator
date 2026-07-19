import { describe, expect, it } from "vitest";
import type { Approval } from "./api";
import {
  FILTRO_TIPOS,
  alternarSelecaoTotal,
  filtrarPorTipo,
  itensDoLote,
  loteHabilitado,
  resumoDoLote,
} from "./approvals";

// A fila dedicada de aprovação em lote (ADR-0035, D2/D3/D8/D9): a lógica que a
// tela projeta vive aqui, pura — habilitação do lote (filtro por tipo é
// OBRIGATÓRIO), selecionar-tudo sobre as visíveis, montagem dos itens do POST
// e o resumo do toast a partir da contagem que a resposta carrega.

function aprovacao(id: string, actionType: Approval["actionType"]): Approval {
  return {
    id,
    requestedByAgentId: "ceo-1",
    conversationId: null,
    runId: null,
    actionType,
    targetDetail: "{}",
    area: "backlog",
    resumo: `proposta ${id}`,
    status: "PENDING",
    racional: null,
    racionalTipo: "LOGICO",
    racionalArrancado: false,
    grillRounds: 0,
    decisionReason: null,
    decidedByActorType: null,
    decidedByActorId: null,
    requestedAt: "2026-07-10T12:00:00Z",
    decidedAt: null,
  };
}

describe("FILTRO_TIPOS (completo sobre o enum desde a ADR-0047 — tipo sem filtro não age em lote)", () => {
  it("cobre 'todos' + os 8 tipos de ação do gate", () => {
    const ids = FILTRO_TIPOS.map((tipo) => tipo.id);

    expect(ids).toEqual([
      "todos",
      "HIRE_AGENT",
      "CREATE_ISSUE",
      "WAKEUP_AGENT",
      "CREATE_TEAM",
      "CREATE_ROLE_TEMPLATE",
      "SKILL_INSTALL_REQUEST",
      "GRADUATE_PRINCIPLE",
      "DIRECT_ISSUE",
    ]);
  });
});

describe("loteHabilitado (D2: lote SÓ com filtro de tipo ativo)", () => {
  it("bloqueia com o filtro 'todos', mesmo com seleção", () => {
    expect(loteHabilitado("todos", 5)).toBe(false);
  });

  it("bloqueia com tipo ativo mas nada selecionado", () => {
    expect(loteHabilitado("HIRE_AGENT", 0)).toBe(false);
  });

  it("habilita com tipo ativo e pelo menos 1 selecionada", () => {
    expect(loteHabilitado("HIRE_AGENT", 1)).toBe(true);
  });
});

describe("filtrarPorTipo", () => {
  const fila = [aprovacao("a", "HIRE_AGENT"), aprovacao("b", "CREATE_ISSUE"), aprovacao("c", "HIRE_AGENT")];

  it("'todos' devolve a fila inteira", () => {
    expect(filtrarPorTipo(fila, "todos")).toHaveLength(3);
  });

  it("tipo ativo devolve só as daquele tipo, na ordem da fila", () => {
    expect(filtrarPorTipo(fila, "HIRE_AGENT").map((a) => a.id)).toEqual(["a", "c"]);
  });
});

describe("alternarSelecaoTotal (D3: selecionar tudo = o caso raiz 'aprovar todos')", () => {
  const visiveis = [aprovacao("a", "HIRE_AGENT"), aprovacao("b", "HIRE_AGENT")];

  it("com seleção parcial, completa para todas as visíveis", () => {
    expect(alternarSelecaoTotal(visiveis, new Set(["a"]))).toEqual(new Set(["a", "b"]));
  });

  it("com todas selecionadas, limpa", () => {
    expect(alternarSelecaoTotal(visiveis, new Set(["a", "b"]))).toEqual(new Set());
  });

  it("sem visíveis, nada a selecionar", () => {
    expect(alternarSelecaoTotal([], new Set())).toEqual(new Set());
  });
});

describe("itensDoLote (D8: observação por item só quando preenchida)", () => {
  const visiveis = [aprovacao("a", "HIRE_AGENT"), aprovacao("b", "HIRE_AGENT"), aprovacao("c", "HIRE_AGENT")];

  it("manda só as selecionadas, na ordem da fila, com a observação aparada", () => {
    const itens = itensDoLote(visiveis, new Set(["c", "a"]), {
      a: "  esse é o mais urgente  ",
      c: "   ",
    });
    expect(itens).toEqual([
      { approvalId: "a", observacao: "esse é o mais urgente" },
      { approvalId: "c" },
    ]);
  });

  it("selecionada fora das visíveis (filtro trocou) NÃO viaja — o lote é homogêneo por tipo", () => {
    const itens = itensDoLote(visiveis.slice(0, 1), new Set(["a", "z"]), {});
    expect(itens).toEqual([{ approvalId: "a" }]);
  });
});

describe("resumoDoLote (D9: a contagem sai da resposta, sem UI dedicada)", () => {
  it("tudo commitado", () => {
    expect(resumoDoLote({ decided: 3, skipped: [] }, "APPROVE")).toBe("3 aprovadas");
    expect(resumoDoLote({ decided: 1, skipped: [] }, "REJECT")).toBe(
      "1 recusada — o CEO recebe o motivo na conversa",
    );
  });

  it("com puladas, diz quantas e por quê olhar a fila", () => {
    expect(
      resumoDoLote(
        { decided: 2, skipped: [{ approvalId: "x", motivo: "grill: cade o racional" }] },
        "APPROVE",
      ),
    ).toBe("2 aprovadas · 1 pulada (já decidida, grelhada ou com alvo quebrado)");
  });
});
