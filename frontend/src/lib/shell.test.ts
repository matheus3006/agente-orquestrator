import { describe, expect, it } from "vitest";
import { activeSurfaceId, companyInitials } from "./shell";

describe("activeSurfaceId", () => {
  it("raiz da company é o Mission Control", () => {
    expect(activeSurfaceId("/demo-14262")).toBe("mission-control");
  });

  it("lista de issues ativa issues", () => {
    expect(activeSurfaceId("/demo-14262/issues")).toBe("issues");
  });

  it("detail de issue continua em issues (prefixo, não igualdade)", () => {
    expect(activeSurfaceId("/demo-14262/issues/0d9f3a10-aaaa-4bbb-8ccc-000000000001")).toBe("issues");
  });

  it("reviews e agents ativam as próprias superfícies", () => {
    expect(activeSurfaceId("/demo-14262/reviews")).toBe("reviews");
    expect(activeSurfaceId("/demo-14262/agents")).toBe("agents");
    expect(activeSurfaceId("/demo-14262/agents/abc")).toBe("agents");
  });

  it("servidores (F.10) ativa a própria superfície", () => {
    expect(activeSurfaceId("/demo-14262/servers")).toBe("servers");
  });

  it("tolera trailing slash", () => {
    expect(activeSurfaceId("/demo-14262/issues/")).toBe("issues");
  });

  it("segmento desconhecido cai no Mission Control (default do protótipo)", () => {
    expect(activeSurfaceId("/demo-14262/whatever")).toBe("mission-control");
    expect(activeSurfaceId("/")).toBe("mission-control");
  });

  it("catálogo global /moldes ativa moldes, não o Mission Control (P2 #16)", () => {
    expect(activeSurfaceId("/moldes")).toBe("moldes");
    expect(activeSurfaceId("/moldes/")).toBe("moldes");
  });

  it("company navegando não confunde moldes com a rota global (2º segmento segue mandando)", () => {
    expect(activeSurfaceId("/demo-14262/moldes")).toBe("mission-control");
  });
});

describe("companyInitials", () => {
  it("duas palavras → iniciais das duas (Demo Fixtures → DF, como no protótipo)", () => {
    expect(companyInitials("Demo Fixtures")).toBe("DF");
  });

  it("uma palavra → duas primeiras letras", () => {
    expect(companyInitials("Acme")).toBe("AC");
  });

  it("ignora espaços extras e palavras além da segunda", () => {
    expect(companyInitials("  demo   fixtures  corp ")).toBe("DF");
  });

  it("nome vazio degrada para ? (nunca string vazia no avatar)", () => {
    expect(companyInitials("   ")).toBe("?");
  });
});
