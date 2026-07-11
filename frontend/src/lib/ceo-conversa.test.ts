import { describe, expect, it } from "vitest";
import type { Approval, ConversationTurn } from "./api";
import {
  acaoLegivel,
  chavesDaThread,
  destinoDaIssueProposta,
  entrandoNaThread,
  hireDaProposta,
  issueDaApproval,
  pendentesPorConversa,
  projetarThread,
  racionalDoTimeProposto,
  splitReply,
  type ThreadItem,
} from "./ceo-conversa";

const turno = (o: Partial<ConversationTurn> = {}): ConversationTurn => ({
  id: "t1",
  ownerMessage: "Atlas, qual o próximo passo?",
  ceoReply: "Proponho abrir a issue de nicho.",
  status: "ANSWERED",
  runId: "run-1",
  createdAt: "2026-07-04T10:00:00Z",
  answeredAt: "2026-07-04T10:00:01Z",
  ...o,
});

const approval = (o: Partial<Approval> = {}): Approval => ({
  id: "ap1",
  requestedByAgentId: "ceo-1",
  conversationId: "conv-1",
  runId: "run-1",
  actionType: "CREATE_ISSUE",
  targetDetail: '{"title": "Definir nicho do produto"}',
  area: "direcao-roadmap",
  resumo: "Issue de direção: nicho",
  status: "PENDING",
  racional: null,
  racionalTipo: "LOGICO",
  racionalArrancado: false,
  grillRounds: 0,
  decisionReason: null,
  requestedAt: "2026-07-04T10:00:02Z",
  decidedAt: null,
  ...o,
});

// As projeções puras da coluna docada do CEO (T.3-FE, ADR-0018): a thread
// mostra prosa legível — o bloco ```proposta é MECANISMO (vira Approval no
// backend); a bolha nunca despeja o JSON cru (spec do mockup §4).

describe("splitReply (prosa da bolha vs bloco ```proposta)", () => {
  it("remove o bloco ```proposta da prosa e marca que houve proposta", () => {
    const reply =
      "Entendido: proponho abrir a issue de nicho antes de contratar.\n" +
      "```proposta\n" +
      '{"acao": "CREATE_ISSUE", "alvo": {"title": "Definir nicho do produto"}}\n' +
      "```";

    const parte = splitReply(reply);

    expect(parte.prosa).toBe("Entendido: proponho abrir a issue de nicho antes de contratar.");
    expect(parte.temProposta).toBe(true);
  });

  it("resposta sem bloco passa intacta", () => {
    const parte = splitReply("Os times estão rodando. Nada pendente.");

    expect(parte.prosa).toBe("Os times estão rodando. Nada pendente.");
    expect(parte.temProposta).toBe(false);
  });

  it("resposta que é SÓ o bloco fica com prosa vazia (a bolha mostra só o marcador)", () => {
    const parte = splitReply('```proposta\n{"acao": "HIRE_AGENT"}\n```');

    expect(parte.prosa).toBe("");
    expect(parte.temProposta).toBe(true);
  });

  it("remove os blocos de bastão (```handoff/```needs-grilling) da prosa — mecanismo, não conversa", () => {
    const comHandoff = splitReply(
      "Dossiê carimbado — seguindo para a estratégia.\n```handoff\n" +
        '{"para": "planejador", "bastao": ".orq/conversa/x/dossie.md", "resumo": "gerar estratégia"}\n' +
        "```",
    );
    const comKickback = splitReply(
      '```needs-grilling\n{"lacuna": "qual o orçamento?"}\n```\nPreciso do dono aqui.',
    );

    expect(comHandoff.prosa).toBe("Dossiê carimbado — seguindo para a estratégia.");
    expect(comHandoff.temProposta).toBe(false);
    expect(comKickback.prosa).toBe("Preciso do dono aqui.");
  });
});

describe("hireDaProposta (A3/A4 — o menu do gate lê o pedido protocolado)", () => {
  it("expõe o motor sugerido e o racional estruturado do alvo (skills seguem somente-leitura)", () => {
    const hire = hireDaProposta(
      approval({
        actionType: "HIRE_AGENT",
        targetDetail:
          '{"name":"Vega","role":"executor","adapterType":"claude-code","model":"opus",' +
          '"effort":"high","racional":"skills tdd; motor claude-code; time de produto"}',
      }),
    );

    expect(hire).toEqual({
      motor: { adapterType: "claude-code", model: "opus", effort: "high" },
      racional: "skills tdd; motor claude-code; time de produto",
    });
  });

  it("hire sem motor/racional no alvo vem com campos nulos (o dono preenche se quiser)", () => {
    const hire = hireDaProposta(
      approval({ actionType: "HIRE_AGENT", targetDetail: '{"name":"Nix","role":"executor"}' }),
    );

    expect(hire).toEqual({
      motor: { adapterType: null, model: null, effort: null },
      racional: null,
    });
  });

  it("não-hire ou alvo quebrado → null (fail-soft, nunca derruba a tela)", () => {
    expect(hireDaProposta(approval())).toBeNull(); // CREATE_ISSUE
    expect(
      hireDaProposta(approval({ actionType: "HIRE_AGENT", targetDetail: "não é json" })),
    ).toBeNull();
  });
});

describe("projetarThread (approval ANCORADA no ponto do tempo — depois do turno que a gerou)", () => {
  it("intercala: turno → suas approvals (por runId) → próximo turno", () => {
    const t1 = turno({ id: "t1", runId: "run-1" });
    const t2 = turno({ id: "t2", runId: "run-2", createdAt: "2026-07-04T11:00:00Z" });
    const a1 = approval({ id: "ap1", runId: "run-1" });

    const itens = projetarThread([t1, t2], [a1]);

    expect(itens.map((item) => item.kind)).toEqual(["turno", "approval", "turno"]);
    expect(itens[1].kind === "approval" && itens[1].approval.id).toBe("ap1");
  });

  it("duas approvals do mesmo turno saem em ordem de chegada (requestedAt)", () => {
    const t1 = turno({ id: "t1", runId: "run-1" });
    const tarde = approval({ id: "ap-tarde", requestedAt: "2026-07-04T10:00:09Z" });
    const cedo = approval({ id: "ap-cedo", requestedAt: "2026-07-04T10:00:02Z" });

    const itens = projetarThread([t1], [tarde, cedo]);

    expect(
      itens.filter((item) => item.kind === "approval").map((item) => item.approval.id),
    ).toEqual(["ap-cedo", "ap-tarde"]);
  });

  it("approval órfã (runId sem turno na thread) não some — vai pro fim", () => {
    const t1 = turno({ id: "t1", runId: "run-1" });
    const orfa = approval({ id: "ap-orfa", runId: "run-fantasma" });

    const itens = projetarThread([t1], [orfa]);

    expect(itens.at(-1)).toMatchObject({ kind: "approval", approval: { id: "ap-orfa" } });
  });
});

// A identidade que a animação da thread congela na 1ª pintura completa: o turno
// leva o STATUS na chave (PENDING→ANSWERED conta como item novo — trava do design
// de hoje); a approval leva só o id. Espelho do MiniTerm: só o que o polling
// APENDA (chave fora do congelado) anima.

describe("chavesDaThread (a chave de animação de cada item da thread)", () => {
  it("turno vira t:<id>:<status> — o status faz parte da identidade", () => {
    const chaves = chavesDaThread([
      { kind: "turno", turno: turno({ id: "t7", status: "ANSWERED" }) },
    ]);

    expect(chaves).toEqual(["t:t7:ANSWERED"]);
  });

  it("approval vira a:<id> — sem status, a identidade é o id", () => {
    const chaves = chavesDaThread([{ kind: "approval", approval: approval({ id: "ap9" }) }]);

    expect(chaves).toEqual(["a:ap9"]);
  });

  it("preserva a ordem da projeção (turno → approval ancorada → turno)", () => {
    const itens: ThreadItem[] = [
      { kind: "turno", turno: turno({ id: "t1", status: "ANSWERED" }) },
      { kind: "approval", approval: approval({ id: "ap1" }) },
      { kind: "turno", turno: turno({ id: "t2", status: "PENDING" }) },
    ];

    expect(chavesDaThread(itens)).toEqual(["t:t1:ANSWERED", "a:ap1", "t:t2:PENDING"]);
  });
});

// O contrato da animação (comentário l.173-175 do componente): a 1ª pintura
// COMPLETA congela as chaves; daí em diante só o que caiu FORA do congelado anima.
// Antes de congelar (congelado null) nada anima — carga inicial e troca de aba
// entram prontas, sem re-animar a história. O índice i responde pelo item i.

describe("entrandoNaThread (só o apendado pós-congelamento anima)", () => {
  it("carga inicial / troca de aba: o congelado é a thread inteira → nada anima", () => {
    const chaves = ["t:t1:ANSWERED", "a:ap1", "t:t2:PENDING"];
    const congelado = new Set(chaves);

    expect(entrandoNaThread(chaves, congelado)).toEqual([false, false, false]);
  });

  it("append do polling: só a chave nova (fora do congelado) recebe entrando", () => {
    const congelado = new Set(["t:t1:ANSWERED", "a:ap1"]);
    const chavesDepoisDoPoll = ["t:t1:ANSWERED", "a:ap1", "t:t2:PENDING"];

    expect(entrandoNaThread(chavesDepoisDoPoll, congelado)).toEqual([false, false, true]);
  });

  it("antes da 1ª pintura completa (congelado null): nada anima", () => {
    const chaves = ["t:t1:PENDING", "t:t2:PENDING"];

    expect(entrandoNaThread(chaves, null)).toEqual([false, false]);
  });

  it("turno que MUDA de status re-anima — a chave carrega o status (trava do design de hoje)", () => {
    const congeladoNoPending = new Set(
      chavesDaThread([{ kind: "turno", turno: turno({ id: "t1", status: "PENDING" }) }]),
    );
    const chavesDepoisDoPoll = chavesDaThread([
      { kind: "turno", turno: turno({ id: "t1", status: "ANSWERED" }) },
    ]);

    expect(entrandoNaThread(chavesDepoisDoPoll, congeladoNoPending)).toEqual([true]);
  });
});

describe("acaoLegivel (o corpo do card é legível — o targetDetail nunca aparece cru)", () => {
  it("CREATE_ISSUE vira 'Abrir issue' com o título do alvo", () => {
    const acao = acaoLegivel(
      approval({ actionType: "CREATE_ISSUE", targetDetail: '{"title": "Definir nicho"}' }),
    );

    expect(acao).toEqual({ verbo: "Abrir issue", alvo: "Definir nicho" });
  });

  it("HIRE_AGENT vira 'Contratar agente' com nome · role", () => {
    const acao = acaoLegivel(
      approval({ actionType: "HIRE_AGENT", targetDetail: '{"name": "Nina", "role": "executor"}' }),
    );

    expect(acao).toEqual({ verbo: "Contratar agente", alvo: "Nina · executor" });
  });

  it("WAKEUP_AGENT vira 'Acordar agente' com o id encurtado", () => {
    const acao = acaoLegivel(
      approval({
        actionType: "WAKEUP_AGENT",
        targetDetail: '{"agentId": "d07f1545-d3eb-44ca-b01f-1293f73485c2"}',
      }),
    );

    expect(acao).toEqual({ verbo: "Acordar agente", alvo: "d07f1545" });
  });

  it("alvo malformado degrada para alvo null (o card cai no resumo) — nunca lança", () => {
    const acao = acaoLegivel(approval({ actionType: "CREATE_ISSUE", targetDetail: "{quebrado" }));

    expect(acao).toEqual({ verbo: "Abrir issue", alvo: null });
  });

  it("CREATE_TEAM vira 'Criar time' com dominio / foco e o arquiteto que nasce junto (ADR-0037)", () => {
    const acao = acaoLegivel(
      approval({
        actionType: "CREATE_TEAM",
        targetDetail:
          '{"dominio": "Marketing", "foco": "Tráfego", "arquiteto": {"name": "Aqua", "role": "arquiteto"}}',
      }),
    );

    expect(acao).toEqual({ verbo: "Criar time", alvo: "Marketing / Tráfego · arquiteto Aqua" });
  });

  it("CREATE_TEAM sem dominio/foco degrada para alvo null", () => {
    const acao = acaoLegivel(
      approval({ actionType: "CREATE_TEAM", targetDetail: '{"arquiteto": {"name": "Aqua"}}' }),
    );

    expect(acao).toEqual({ verbo: "Criar time", alvo: null });
  });
});

describe("destinoDaIssueProposta (ADR-0037/D2: o card do CREATE_ISSUE mostra o time-destino)", () => {
  it("carrega dominio / foco quando o alvo tem o time", () => {
    const destino = destinoDaIssueProposta(
      approval({
        actionType: "CREATE_ISSUE",
        targetDetail: '{"title": "Landing nova", "dominio": "Dev", "foco": "Landing"}',
      }),
    );

    expect(destino).toBe("Dev / Landing");
  });

  it("sem time no alvo (proposta pré-0037) ou ação diferente → null", () => {
    expect(
      destinoDaIssueProposta(
        approval({ actionType: "CREATE_ISSUE", targetDetail: '{"title": "Órfã"}' }),
      ),
    ).toBeNull();
    expect(
      destinoDaIssueProposta(
        approval({ actionType: "HIRE_AGENT", targetDetail: '{"name": "Nina"}' }),
      ),
    ).toBeNull();
  });

  it("NÃO contamina o alvo do acaoLegivel — o issueDaApproval segue casando pelo título exato", () => {
    const aprovada = approval({
      actionType: "CREATE_ISSUE",
      targetDetail: '{"title": "Landing nova", "dominio": "Dev", "foco": "Landing"}',
    });

    expect(acaoLegivel(aprovada).alvo).toBe("Landing nova");
  });
});

describe("racionalDoTimeProposto (CREATE_TEAM: o racional do Planejador é leitura no card)", () => {
  it("carrega o racional do alvo; vazio/ausente/outra ação → null", () => {
    expect(
      racionalDoTimeProposto(
        approval({
          actionType: "CREATE_TEAM",
          targetDetail:
            '{"dominio": "Mkt", "foco": "Ads", "racional": "aquisição paga precisa de time próprio"}',
        }),
      ),
    ).toBe("aquisição paga precisa de time próprio");
    expect(
      racionalDoTimeProposto(
        approval({ actionType: "CREATE_TEAM", targetDetail: '{"dominio": "Mkt", "foco": "Ads"}' }),
      ),
    ).toBeNull();
    expect(
      racionalDoTimeProposto(
        approval({ actionType: "HIRE_AGENT", targetDetail: '{"racional": "não sou time"}' }),
      ),
    ).toBeNull();
  });
});

describe("issueDaApproval (CREATE_ISSUE aprovada → a issue criada, pro report do Ledger)", () => {
  const issue = (id: string, title: string, createdAt: string) => ({
    id,
    companyId: "c1",
    seq: 1,
    title,
    description: null,
    status: "OPEN",
    riskLevel: 3,
    assigneeAgentId: null,
    executionLockRunId: null,
    createdAt,
    corrigeIssueId: null,
    corrigeSeq: null,
    conversationId: null,
  });

  it("acha a issue pelo título exato do alvo; empate fica com a mais recente", () => {
    const aprovada = approval({
      actionType: "CREATE_ISSUE",
      status: "APPROVED",
      targetDetail: '{"title": "Definir nicho"}',
    });
    const issues = [
      issue("i-velha", "Definir nicho", "2026-07-01T00:00:00Z"),
      issue("i-nova", "Definir nicho", "2026-07-04T00:00:00Z"),
      issue("i-outra", "Outra coisa", "2026-07-04T00:00:00Z"),
    ];

    expect(issueDaApproval(aprovada, issues)?.id).toBe("i-nova");
  });

  it("PENDING, REJECTED ou outra ação não linkam issue nenhuma", () => {
    const issues = [issue("i1", "Definir nicho", "2026-07-04T00:00:00Z")];

    expect(issueDaApproval(approval({ status: "PENDING" }), issues)).toBeNull();
    expect(
      issueDaApproval(
        approval({ actionType: "HIRE_AGENT", status: "APPROVED" }),
        issues,
      ),
    ).toBeNull();
  });
});

describe("pendentesPorConversa (o badge da aba — o que espera o board, por assunto)", () => {
  it("conta só as PENDING, agrupadas pela conversa de origem", () => {
    const fila = [
      approval({ id: "a1", conversationId: "conv-1", status: "PENDING" }),
      approval({ id: "a2", conversationId: "conv-1", status: "PENDING" }),
      approval({ id: "a3", conversationId: "conv-2", status: "PENDING" }),
      approval({ id: "a4", conversationId: null, status: "PENDING" }),
    ];

    const porConversa = pendentesPorConversa(fila);

    expect(porConversa.get("conv-1")).toBe(2);
    expect(porConversa.get("conv-2")).toBe(1);
    expect(porConversa.size).toBe(2);
  });
});
