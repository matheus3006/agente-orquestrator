import { describe, expect, it } from "vitest";
import type { Engine, RoleTemplate, RoleTemplateVersion } from "./api";
import {
  isMoldeDeSistema,
  isTipoReservado,
  linhasDeConhecimento,
  motorDaVersao,
  reportsToDefaultLabel,
  TIPOS_FUNCAO,
  tipoFuncaoLabel,
  versaoCorrente,
  versionMotorLabel,
} from "./role-templates";

// Helpers puros dos moldes (ADR-0017): rótulo do tipo de função, marca dos reservados, e o rótulo do
// motor de uma versão resolvido contra o catálogo /engines (reusa engineLabel, null-aware).

const CATALOG: Engine[] = [
  { adapterType: "claude-code", model: "opus", effort: "ultracode", contextWindow: 200000, label: "Claude · Opus 4.8 · ultra" },
  { adapterType: "codex", model: "gpt-5.5", effort: "xhigh", contextWindow: 256000, label: "GPT · 5.5 · xhigh" },
];

const version = (o: Partial<RoleTemplateVersion> = {}): RoleTemplateVersion => ({
  id: "v", roleTemplateId: "t", version: 1, adapterType: "claude-code", model: "opus", effort: "ultracode",
  skillKeys: [], reportsToDefault: null, status: "APPROVED", createdAt: "2026-07-04T00:00:00Z", ...o,
});

describe("tipoFuncaoLabel", () => {
  it("mapeia os 6 tipos para rótulos legíveis", () => {
    expect(tipoFuncaoLabel("PLANEJAMENTO_ARQUITETO")).toBe("Planejamento / Arquiteto");
    expect(tipoFuncaoLabel("GESTAO_PO")).toBe("Gestão / PO");
    expect(tipoFuncaoLabel("LIDERANCA")).toBe("Liderança");
  });
});

describe("isTipoReservado", () => {
  it("REVISAO_QA e SUPORTE são reservados; os outros não", () => {
    expect(isTipoReservado("REVISAO_QA")).toBe(true);
    expect(isTipoReservado("SUPORTE")).toBe(true);
    expect(isTipoReservado("EXECUCAO")).toBe(false);
    expect(isTipoReservado("LIDERANCA")).toBe(false);
  });
});

describe("TIPOS_FUNCAO", () => {
  it("tem os 6 tipos, reservados por último", () => {
    expect(TIPOS_FUNCAO).toHaveLength(6);
    expect(TIPOS_FUNCAO.slice(-2)).toEqual(["REVISAO_QA", "SUPORTE"]);
  });
});

describe("versionMotorLabel", () => {
  it("resolve o motor da versão pelo catálogo (trio exato)", () => {
    expect(versionMotorLabel(version(), CATALOG)).toBe("Claude · Opus 4.8 · ultra");
    expect(versionMotorLabel(version({ adapterType: "codex", model: "gpt-5.5", effort: "xhigh" }), CATALOG))
      .toBe("GPT · 5.5 · xhigh");
  });

  it("trio todo-null → 'sem motor'", () => {
    expect(versionMotorLabel(version({ adapterType: null, model: null, effort: null }), CATALOG))
      .toBe("sem motor");
  });

  it("motor fora do catálogo → sintetiza model · effort (não some)", () => {
    expect(versionMotorLabel(version({ adapterType: "claude-code", model: "haiku", effort: "medium" }), CATALOG))
      .toBe("haiku · medium");
  });
});

describe("motorDaVersao", () => {
  it("versão com motor → trio para o MotorSelect; todo-null → null (sem motor)", () => {
    expect(motorDaVersao(version())).toEqual({
      adapterType: "claude-code",
      model: "opus",
      effort: "ultracode",
    });
    expect(motorDaVersao(version({ adapterType: null, model: null, effort: null }))).toBeNull();
  });
});

/* ---------- savelog real (GET /versions) ---------- */

const ARCHITECT_ID = "0e1a0000-0000-4000-8000-000000000001";

const template = (o: Partial<RoleTemplate> = {}): RoleTemplate => ({
  id: ARCHITECT_ID,
  tipoFuncao: "PLANEJAMENTO_ARQUITETO",
  roleEspecifica: "architect",
  currentVersionId: "v2",
  createdAt: "2026-07-04T10:00:00Z",
  ...o,
});

describe("isMoldeDeSistema", () => {
  it("os 4 moldes semeados pela V14 são de sistema; um criado pelo board não é", () => {
    expect(isMoldeDeSistema(template())).toBe(true);
    expect(isMoldeDeSistema(template({ id: "0e1a0000-0000-4000-8000-000000000004" }))).toBe(true);
    expect(isMoldeDeSistema(template({ id: "t-custom" }))).toBe(false);
  });
});

describe("versaoCorrente", () => {
  const savelog = [
    version({ id: "v2", version: 2, skillKeys: ["tdd"] }),
    version({ id: "v1", version: 1, skillKeys: ["tdd", "diagnose"] }),
  ];

  it("acha a corrente pelo currentVersionId do molde", () => {
    expect(versaoCorrente(template(), savelog)?.skillKeys).toEqual(["tdd"]);
    expect(versaoCorrente(template({ currentVersionId: "v1" }), savelog)?.version).toBe(1);
  });

  it("corrente fora do savelog (ou molde sem corrente) → null", () => {
    expect(versaoCorrente(template({ currentVersionId: "v-fantasma" }), savelog)).toBeNull();
    expect(versaoCorrente(template({ currentVersionId: null }), savelog)).toBeNull();
    expect(versaoCorrente(template(), [])).toBeNull();
  });
});

describe("reportsToDefaultLabel", () => {
  it("nome do enum vira rótulo; texto livre passa; null → raiz da pirâmide", () => {
    expect(reportsToDefaultLabel("LIDERANCA")).toBe("Liderança");
    expect(reportsToDefaultLabel("tech-lead da tribo")).toBe("tech-lead da tribo");
    expect(reportsToDefaultLabel(null)).toBe("ninguém (raiz da pirâmide)");
  });
});

describe("linhasDeConhecimento", () => {
  it("uma por linha, aparadas, sem vazios nem duplicadas (case-insensitive)", () => {
    expect(linhasDeConhecimento("  revisar contratos \n\nescrever specs\nRevisar Contratos\n")).toEqual([
      "revisar contratos",
      "escrever specs",
    ]);
    expect(linhasDeConhecimento("")).toEqual([]);
  });
});
