"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, Square } from "lucide-react";
import { useState } from "react";
import { PreviewServerFrame } from "@/components/preview-server-frame";
import { PreviewServerThumbnail } from "@/components/preview-server-thumbnail";
import {
  fetchPreviewServerStatus,
  startPreviewServer,
  stopPreviewServer,
} from "@/lib/api";
import { fmtTtl, splitStartFailure, workspaceAvancou } from "@/lib/preview-server";
import { queryKeys } from "@/lib/query-keys";

// As duas apresentações do preview VIVO (F.10/ADR-0023 §7) sobre a mesma
// query de status: a seção da Issue e o embed do card de Review (o reviewer
// não sai do card no momento aprovar-vendo). Ambas degradam sem ruído: issue
// sem manifest = seção/botão ausentes. O servidor só sobe por CLIQUE do
// operador — nunca automático (sob demanda, ADR-0023 §3).

function usePreviewServerStatus(companyId: string | undefined, issueId: string) {
  return useQuery({
    queryKey: queryKeys.previewServer(companyId ?? "", issueId),
    queryFn: () => fetchPreviewServerStatus(companyId as string, issueId),
    enabled: !!companyId,
    // vivo/TTL mudam por fora (reaper, teardown): revalida em janela curta
    staleTime: 15_000,
    refetchInterval: 30_000,
  });
}

/**
 * O miolo compartilhado: botão Rodar/Parar + falha legível (motivo + tail) +
 * frame vivo com keepalive. Quem decide QUANDO mostrar é cada superfície.
 */
function ServerControls({
  companyId,
  issueId,
  liveUrl,
  ttlRemainingSeconds,
}: {
  companyId: string;
  issueId: string;
  liveUrl: string | null;
  ttlRemainingSeconds: number | null;
}) {
  const queryClient = useQueryClient();
  const [startError, setStartError] = useState<string | null>(null);

  const invalidateServerQueries = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.previewServer(companyId, issueId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.previewServers(companyId) });
  };

  const startMutation = useMutation({
    mutationFn: () => startPreviewServer(companyId, issueId),
    onSuccess: (result) => {
      setStartError(result.started ? null : result.error);
      invalidateServerQueries();
    },
    onError: (error: Error) => setStartError(error.message),
  });

  const stopMutation = useMutation({
    mutationFn: () => stopPreviewServer(companyId, issueId),
    onSuccess: () => {
      setStartError(null);
      invalidateServerQueries();
    },
  });

  const failure = startError ? splitStartFailure(startError) : null;

  return (
    <div className="server-controls">
      <div className="server-actions">
        {liveUrl ? (
          <button
            type="button"
            className="btn server-stop"
            disabled={stopMutation.isPending}
            onClick={() => stopMutation.mutate()}
          >
            <Square size={12} strokeWidth={2} />
            {stopMutation.isPending ? "parando…" : "parar servidor"}
          </button>
        ) : (
          <button
            type="button"
            className="btn primary"
            disabled={startMutation.isPending}
            onClick={() => startMutation.mutate()}
          >
            <Play size={12} strokeWidth={2} />
            {startMutation.isPending ? "subindo…" : "rodar app"}
          </button>
        )}
        {liveUrl && ttlRemainingSeconds !== null && (
          <span className="server-ttl mono" title="TTL por inatividade — a tela aberta renova">
            ttl {fmtTtl(ttlRemainingSeconds)}
          </span>
        )}
      </div>

      {failure && (
        <div className="server-failure" role="alert">
          <span className="text-coral">{failure.reason}</span>
          {failure.tail && <pre className="server-tail mono">{failure.tail}</pre>}
        </div>
      )}

      {liveUrl && (
        <PreviewServerFrame companyId={companyId} issueId={issueId} url={liveUrl} />
      )}
    </div>
  );
}

/** Seção "Servidor" da página da Issue: manifest vigente + rodar/parar/ver. */
export function IssueServerSection({
  companyId,
  issueId,
}: {
  companyId: string | undefined;
  issueId: string;
}) {
  const statusQuery = usePreviewServerStatus(companyId, issueId);

  const status = statusQuery.data;
  if (!companyId || !status?.manifest) {
    return null; // issue sem servidor declarado: a seção não existe (zero ruído)
  }

  return (
    <>
      <h2 className="section-label">Servidor</h2>
      <div className="server-declared mono">
        $ {status.manifest.command} <span className="server-port">:{status.manifest.port}</span>
      </div>
      <ServerControls
        companyId={companyId}
        issueId={issueId}
        liveUrl={status.live?.url ?? null}
        ttlRemainingSeconds={status.live?.ttlRemainingSeconds ?? null}
      />
      {/* F.13 (ADR-0027): o registro DURÁVEL — o <img> aparece quando o shot já
          fotografou, e CONTINUA aqui depois que o GC coletou o workspace (o
          "Rodar app" morre, o PNG fica). Null até a primeira captura (zero ruído). */}
      <PreviewServerThumbnail companyId={companyId} issueId={issueId} />
    </>
  );
}

/**
 * O embed do card de Review: "Rodar app" quando o run SOB REVISÃO declarou
 * servidor. Manifest de run posterior = aviso "workspace avançou" (ADR-0023
 * §6) — o botão continua (o operador decide), mas a honestidade vem antes.
 */
export function ReviewServerEmbed({
  companyId,
  issueId,
  runId,
}: {
  companyId: string | undefined;
  issueId: string;
  runId: string;
}) {
  const statusQuery = usePreviewServerStatus(companyId, issueId);

  const status = statusQuery.data;
  if (!companyId || !status?.manifest) {
    return null; // run sem servidor declarado: card segue leve
  }
  const avancou = workspaceAvancou(status.manifest.runId, runId);

  return (
    <div className="review-server">
      {avancou && (
        <div className="server-warning mono">
          ⚠ workspace avançou desde este run — o servidor roda a declaração mais nova
        </div>
      )}
      <ServerControls
        companyId={companyId}
        issueId={issueId}
        liveUrl={status.live?.url ?? null}
        ttlRemainingSeconds={status.live?.ttlRemainingSeconds ?? null}
      />
    </div>
  );
}
