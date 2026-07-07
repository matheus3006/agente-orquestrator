// Identidade visual do agente (avatar/agent-dot). Os 6 nomes da demo mantêm
// os hues EXATOS aprovados no protótipo; nomes novos ganham um hue
// determinístico no mesmo registro (chroma/luz da família).

const PROTOTYPE_HUES: Record<string, string> = {
  Atlas: "oklch(0.8 0.17 152)",
  Nix: "oklch(0.75 0.16 28)",
  Echo: "oklch(0.78 0.1 235)",
  Vega: "oklch(0.8 0.13 300)",
  Rune: "oklch(0.82 0.14 80)",
  Lyra: "oklch(0.8 0.1 195)",
};

export function agentHue(name: string): string {
  const approved = PROTOTYPE_HUES[name];
  if (approved) {
    return approved;
  }
  let hash = 0;
  for (const char of name) {
    hash = (hash * 31 + (char.codePointAt(0) ?? 0)) % 360;
  }
  return `oklch(0.78 0.13 ${hash})`;
}
