import { describe, expect, it } from "vitest";
import { agentHue } from "./agent-hue";

describe("agentHue (identidade visual do agente — hues do protótipo + fallback determinístico)", () => {
  it("os 6 nomes da demo mantêm os hues EXATOS aprovados no protótipo", () => {
    expect(agentHue("Atlas")).toBe("oklch(0.8 0.17 152)");
    expect(agentHue("Nix")).toBe("oklch(0.75 0.16 28)");
    expect(agentHue("Echo")).toBe("oklch(0.78 0.1 235)");
    expect(agentHue("Vega")).toBe("oklch(0.8 0.13 300)");
    expect(agentHue("Rune")).toBe("oklch(0.82 0.14 80)");
    expect(agentHue("Lyra")).toBe("oklch(0.8 0.1 195)");
  });

  it("nome desconhecido ganha hue determinístico dentro do formato", () => {
    const hue = agentHue("Zephyr");
    expect(hue).toBe(agentHue("Zephyr"));
    expect(hue).toMatch(/^oklch\(0\.78 0\.13 \d+\)$/);
  });

  it("nomes diferentes tendem a hues diferentes", () => {
    expect(agentHue("Zephyr")).not.toBe(agentHue("Quill"));
  });
});
