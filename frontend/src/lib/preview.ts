import type { PreviewArtifactSummary } from "./api";
import { renderMarkdownDoc } from "./markdown-doc";

// Helpers puros do preview real (F.9/ADR-0021). A lista de versões chega do
// servidor da mais nova para a mais antiga (version desc) — assumido aqui.

/**
 * O srcDoc do PreviewFrame (emenda R29-A): o TIPO persistido roteia — html entra
 * como veio (caminho de hoje, byte a byte); markdown vira documento docs-style
 * autocontido (convertido + sanitizado). Sempre dentro do MESMO iframe sandbox.
 */
export function srcDocFor(artifact: PreviewArtifactSummary, content: string): string {
  return artifact.contentType === "markdown" ? renderMarkdownDoc(content) : content;
}

const NOVO_WINDOW_MS = 60 * 60 * 1000;

/** A tela abre na versão mais nova (primeira da lista version-desc). */
export function pickDefaultArtifact(
  artifacts: PreviewArtifactSummary[],
): PreviewArtifactSummary | null {
  return artifacts[0] ?? null;
}

/**
 * A versão EXATA capturada por um run (a Review embute a do review.runId).
 * Sem captura para o run → null: o card explica, nunca mostra outra versão.
 */
export function artifactForRun(
  artifacts: PreviewArtifactSummary[],
  runId: string,
): PreviewArtifactSummary | null {
  return artifacts.find((artifact) => artifact.runId === runId) ?? null;
}

export function versionLabel(artifact: PreviewArtifactSummary): string {
  return `v${artifact.version}`;
}

/** Badge "novo" do Mission Control: capturado na última hora (clock skew degrada para novo). */
export function isPreviewNovo(createdAt: string, nowMs: number): boolean {
  return nowMs - Date.parse(createdAt) < NOVO_WINDOW_MS;
}

/** Tamanho legível dos metadados (o cap do artefato é 2 MB). */
export function fmtSize(sizeBytes: number): string {
  if (sizeBytes < 1024) {
    return `${sizeBytes} B`;
  }
  if (sizeBytes < 1024 * 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}
