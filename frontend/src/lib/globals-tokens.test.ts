import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// #10 GUARD — a regra escrita da casa é "zero cor fora destes tokens" (globals.css :7).
// O `npm run lint` é ESLint e não enxerga CSS, então este teste é a rede: varre o
// globals.css e falha se um literal oklch() reaparecer FORA do bloco :root (dívida de
// design system voltando). Exceção única e explícita: o scrim/sombra preto
// `oklch(0 0 0 / a)` — precedente aceito (sombra não é cor de marca; reusar o mesmo
// preto não conta como cor nova). Reintroduziu cor crua? Mova para :root como --token.

const CSS = readFileSync(fileURLToPath(new URL("../app/globals.css", import.meta.url)), "utf8");

/** Índice logo após o `}` que fecha o bloco :root — que PODE conter oklch à vontade
 *  (é onde os tokens moram). Contagem de chaves: robusto a novas regras aninhadas. */
function afterRoot(css: string): number {
  const open = /:root\s*\{/.exec(css);
  if (!open) throw new Error("bloco :root não encontrado em globals.css");
  let depth = 1;
  let i = open.index + open[0].length;
  while (depth > 0 && i < css.length) {
    const c = css[i];
    if (c === "{") depth += 1;
    else if (c === "}") depth -= 1;
    i += 1;
  }
  return i;
}

function rawOklchOutsideRoot(css: string): string[] {
  const body = css.slice(afterRoot(css));
  return [...body.matchAll(/oklch\([^)]*\)/g)]
    .map((m) => m[0])
    .filter((v) => !v.startsWith("oklch(0 0 0 /"));
}

describe("globals.css — zero cor OKLCH fora dos tokens (#10)", () => {
  it("nenhum oklch() cru fora do :root, exceto o scrim preto oklch(0 0 0 / a)", () => {
    const offenders = [...new Set(rawOklchOutsideRoot(CSS))];
    expect(
      offenders,
      `oklch cru fora do :root — tokenize em :root como --token: ${offenders.join(", ")}`,
    ).toEqual([]);
  });

  it("o guard detecta reintrodução (ignora o oklch de dentro do :root e o scrim preto)", () => {
    const fake =
      ":root { --x: oklch(0.5 0.1 152); }\n" +
      ".a { box-shadow: 0 0 4px oklch(0 0 0 / 0.4); }\n" +
      ".b { color: oklch(0.7 0.2 30); }";
    expect(rawOklchOutsideRoot(fake)).toEqual(["oklch(0.7 0.2 30)"]);
  });
});
