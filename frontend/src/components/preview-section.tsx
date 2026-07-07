"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PreviewFrame } from "@/components/preview-frame";
import { listPreviewArtifacts } from "@/lib/api";
import { fmtAgo, shortId } from "@/lib/format";
import { artifactForRun, fmtSize, pickDefaultArtifact, versionLabel } from "@/lib/preview";
import { queryKeys } from "@/lib/query-keys";
import { cn } from "@/lib/utils";

// As duas apresentações do preview (F.9/ADR-0021 §5) sobre a MESMA query de
// versões: a seção da Issue (âncora primária, histórico navegável) e o embed
// do card da Review (momento-demo: a versão EXATA do review.runId — nunca
// outra). Ambas degradam sem ruído: issue sem captura = seção/embed ausentes.

function useIssuePreviews(companyId: string | undefined, issueId: string) {
  return useQuery({
    queryKey: queryKeys.issuePreviews(companyId ?? "", issueId),
    queryFn: () => listPreviewArtifacts(companyId as string, issueId),
    enabled: !!companyId,
    staleTime: 30_000,
  });
}

/** Seção "Preview" da página da Issue: versões append-only, a mais nova aberta. */
export function IssuePreviewSection({
  companyId,
  issueId,
  nowMs,
}: {
  companyId: string | undefined;
  issueId: string;
  nowMs: number;
}) {
  const previewsQuery = useIssuePreviews(companyId, issueId);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const versions = previewsQuery.data ?? [];
  if (!companyId || versions.length === 0) {
    return null; // issue sem protótipo publicado: a seção não existe (zero ruído)
  }
  const selected = versions.find((artifact) => artifact.id === selectedId)
    ?? pickDefaultArtifact(versions);
  if (!selected) {
    return null;
  }

  return (
    <>
      <h2 className="section-label">Preview</h2>
      <div className="preview-versions">
        {versions.map((artifact) => (
          <button
            key={artifact.id}
            type="button"
            className={cn("version-pill", artifact.id === selected.id && "on")}
            aria-pressed={artifact.id === selected.id}
            onClick={() => setSelectedId(artifact.id)}
          >
            {versionLabel(artifact)}
          </button>
        ))}
        <span className="preview-meta mono">
          capturada {fmtAgo(selected.createdAt, nowMs)} · {fmtSize(selected.sizeBytes)} · run{" "}
          {shortId(selected.runId)}
        </span>
      </div>
      <PreviewFrame companyId={companyId} artifact={selected} />
    </>
  );
}

/**
 * O embed do card da Review: "aprovar → VER" literal. Mostra SÓ a versão do
 * run sob revisão; issue com capturas mas run sem publicação ganha a nota
 * (o motivo detalhado vive na atividade da Issue).
 */
export function ReviewPreviewEmbed({
  companyId,
  issueId,
  runId,
}: {
  companyId: string | undefined;
  issueId: string;
  runId: string;
}) {
  const previewsQuery = useIssuePreviews(companyId, issueId);

  if (!companyId || !previewsQuery.data || previewsQuery.data.length === 0) {
    return null; // review sem preview (ex.: feature de backend): card segue leve
  }
  const exact = artifactForRun(previewsQuery.data, runId);
  if (!exact) {
    return (
      <div className="review-no-preview">
        o run sob revisão não publicou preview — o histórico vive na issue
      </div>
    );
  }
  return (
    <div className="review-preview">
      <PreviewFrame companyId={companyId} artifact={exact} />
    </div>
  );
}
