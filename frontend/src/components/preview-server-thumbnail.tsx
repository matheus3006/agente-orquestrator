"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo } from "react";
import { fetchPreviewServerThumbnailBlob, listPreviewServerThumbnails } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";

// O PNG DURÁVEL do app v2 (F.13/ADR-0027 §B7). Um PNG é INERTE — serving simples:
// image/png + nosniff, direto num <img>, SEM sandbox/iframe (o que o HTML da foto
// v1 exige e o PNG dispensa). O <img src> não manda header custom, então buscamos
// o blob pelo MESMO fetcher autenticado (o padrão da foto v1) e criamos a object
// URL local (revogada no unmount). O momento matador: depois do GC, quando o
// "Rodar app" já morreu (workspace coletado), ESTE <img> é o registro que fica.

export function PreviewServerThumbnail({
  companyId,
  issueId,
  variant = "full",
}: {
  companyId: string | undefined;
  issueId: string;
  variant?: "full" | "compact";
}) {
  // Metadados (a versão vigente = a mais nova). O shot chega ~1 ciclo do sweep
  // depois do server-up: revalida em janela curta até a primeira aparecer.
  const metaQuery = useQuery({
    queryKey: queryKeys.previewServerThumbnails(companyId ?? "", issueId),
    queryFn: () => listPreviewServerThumbnails(companyId as string, issueId),
    enabled: !!companyId,
    staleTime: 20_000,
    refetchInterval: 30_000,
  });
  const newest = metaQuery.data?.[0] ?? null;

  // Os bytes por id — imutável (staleTime Infinity), o browser ainda cacheia pelo
  // Cache-Control: immutable do endpoint. Blob no cache; a object URL vive local.
  const blobQuery = useQuery({
    queryKey: queryKeys.previewServerThumbnailContent(companyId ?? "", newest?.id ?? ""),
    queryFn: () => fetchPreviewServerThumbnailBlob(companyId as string, (newest as { id: string }).id),
    enabled: !!companyId && !!newest,
    staleTime: Number.POSITIVE_INFINITY,
  });

  // A object URL é DERIVADA do blob (não é estado) — recalcula só quando o blob
  // muda de referência (o React Query mantém a referência estável entre refetches).
  const objectUrl = useMemo(
    () => (blobQuery.data ? URL.createObjectURL(blobQuery.data) : null),
    [blobQuery.data],
  );
  // Efeito só de LIMPEZA: revoga a URL anterior quando ela muda / no unmount.
  useEffect(() => {
    if (!objectUrl) {
      return;
    }
    return () => URL.revokeObjectURL(objectUrl);
  }, [objectUrl]);

  if (!newest || !objectUrl) {
    return null; // sem thumbnail ainda: zero ruído (a seção degrada sem placeholder)
  }

  return (
    <figure className={variant === "compact" ? "preview-thumb compact" : "preview-thumb"}>
      {/* eslint-disable-next-line @next/next/no-img-element -- blob URL autenticado: next/image não serve blobs, e o PNG é inerte (ADR-0027 §B7) */}
      <img src={objectUrl} alt="registro do app v2 (thumbnail durável)" loading="lazy" />
      {variant === "full" && (
        <figcaption className="preview-thumb-cap mono">
          registro durável · sobrevive ao GC do workspace
        </figcaption>
      )}
    </figure>
  );
}
