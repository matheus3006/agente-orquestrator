"use client";

import { useQuery } from "@tanstack/react-query";
import { Monitor, Smartphone } from "lucide-react";
import { useState } from "react";
import { fetchPreviewArtifactContent, type PreviewArtifactSummary } from "@/lib/api";
import { srcDocFor, versionLabel } from "@/lib/preview";
import { queryKeys } from "@/lib/query-keys";
import { cn } from "@/lib/utils";

// O ÚNICO lugar onde HTML de agente renderiza (F.9/ADR-0021; CONTEXT.md:
// PreviewFrame). O módulo profundo da tela: por trás de (companyId, artifact)
// ele esconde o fetch autenticado (o iframe NÃO manda header — por isso
// srcdoc, nunca src=), o cache imutável por id, o sandbox e a moldura de
// device. Segurança inegociável: sandbox="allow-scripts" E NADA MAIS — sem
// allow-same-origin (letal com scripts: origin opaco é o que impede o JS do
// artefato de agir como o board), sem popups/forms/top-navigation.

type PreviewDevice = "desktop" | "mobile";

export function PreviewFrame({
  companyId,
  artifact,
}: {
  companyId: string;
  artifact: PreviewArtifactSummary;
}) {
  const [device, setDevice] = useState<PreviewDevice>("desktop");

  // Imutável por id (append-only): staleTime Infinity — uma versão nunca refaz fetch.
  const contentQuery = useQuery({
    queryKey: queryKeys.previewContent(companyId, artifact.id),
    queryFn: () => fetchPreviewArtifactContent(companyId, artifact.id),
    staleTime: Infinity,
    gcTime: 10 * 60 * 1000,
    retry: 1,
  });

  return (
    <div className="preview-frame">
      <div className="preview-frame-bar">
        <span className="preview-frame-dots" aria-hidden>
          <i /><i /><i />
        </span>
        <span className="preview-frame-label mono">
          preview {versionLabel(artifact)}
          {artifact.contentType === "markdown" && ` · ${artifact.sourceFilename}`} · sandbox
        </span>
        <span className="spacer" />
        <div className="preview-device-toggle" role="group" aria-label="Dispositivo do preview">
          <button
            type="button"
            className={cn("device-btn", device === "desktop" && "on")}
            aria-pressed={device === "desktop"}
            onClick={() => setDevice("desktop")}
          >
            <Monitor size={12} strokeWidth={2} /> desktop
          </button>
          <button
            type="button"
            className={cn("device-btn", device === "mobile" && "on")}
            aria-pressed={device === "mobile"}
            onClick={() => setDevice("mobile")}
          >
            <Smartphone size={12} strokeWidth={2} /> mobile
          </button>
        </div>
      </div>

      <div className={cn("preview-stage", device)}>
        {contentQuery.isPending && (
          <div className="preview-wait" aria-busy="true">
            <span className="term-cursor" /> carregando o protótipo…
          </div>
        )}
        {contentQuery.isError && (
          <div className="preview-wait">
            <span className="text-coral">não deu para carregar o conteúdo do preview</span>
            <button type="button" className="btn" onClick={() => contentQuery.refetch()}>
              tentar de novo
            </button>
          </div>
        )}
        {contentQuery.data !== undefined && (
          <div className={cn("preview-canvas", device)}>
            {/* sandbox="allow-scripts" PURO — nunca adicionar allow-same-origin */}
            <iframe
              key={artifact.id}
              title={`Preview ${versionLabel(artifact)} do protótipo`}
              sandbox="allow-scripts"
              referrerPolicy="no-referrer"
              srcDoc={srcDocFor(artifact, contentQuery.data)}
            />
          </div>
        )}
      </div>
    </div>
  );
}
