// Helpers puros do shell (F.2): a sidebar deriva TUDO daqui — nenhuma
// lógica de rota ou de avatar vive em componente.

export type SurfaceId =
  | "mission-control"
  | "issues"
  | "reviews"
  | "approvals"
  | "agents"
  | "servers"
  | "moldes";

const SURFACE_SEGMENTS: ReadonlySet<string> = new Set([
  "issues",
  "reviews",
  "approvals",
  "agents",
  "servers",
]);

/**
 * Superfície ativa a partir do pathname: o 2º segmento (pós companySlug)
 * decide; qualquer coisa fora das superfícies conhecidas cai no Mission
 * Control — o mesmo default do protótipo. Exceção (P2 #16): /moldes é rota
 * GLOBAL sem companySlug — o 1º segmento decide, e o Mission Control não
 * pode reivindicar o ativo fora de uma company.
 */
export function activeSurfaceId(pathname: string): SurfaceId {
  const segments = pathname.split("/").filter(Boolean);
  if (segments[0] === "moldes") {
    return "moldes";
  }
  const segmentAfterSlug = segments[1] ?? "";
  return SURFACE_SEGMENTS.has(segmentAfterSlug)
    ? (segmentAfterSlug as SurfaceId)
    : "mission-control";
}

/** Iniciais do avatar da company ("Demo Fixtures" → "DF", como no protótipo). */
export function companyInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return "?";
  }
  if (words.length === 1) {
    return words[0].slice(0, 2).toUpperCase();
  }
  return (words[0][0] + words[1][0]).toUpperCase();
}
