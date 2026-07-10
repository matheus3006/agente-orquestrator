"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ExternalLink, Play, Square } from "lucide-react";
import { CompanyLink } from "@/components/company-link";
import { PreviewServerThumbnail } from "@/components/preview-server-thumbnail";
import { EmptyState } from "@/components/ui/empty-state";
import { View, ViewHead } from "@/components/view";
import { listPreviewServers, startPreviewServer, stopPreviewServer } from "@/lib/api";
import { fmtAgo, shortId } from "@/lib/format";
import { fmtTtl } from "@/lib/preview-server";
import { queryKeys } from "@/lib/query-keys";
import { useActiveCompany } from "@/lib/use-active-company";
import { useNow } from "@/lib/use-now";

// A tela dedicada "Servidores" (F.10/ADR-0023 §7): a superfície de SUPERVISÃO
// do operador. Vivos (issue/agente/comando/porta/TTL) com matar/abrir;
// candidatas (Issues com manifest declarado) com iniciar. Ver a lista NÃO
// renova TTL — só o frame montado manda keepalive (heartbeat é do frame).

export default function ServersPage() {
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const queryClient = useQueryClient();
  const nowMs = useNow(1000);

  const serversQuery = useQuery({
    queryKey: queryKeys.previewServers(companyId ?? ""),
    queryFn: () => listPreviewServers(companyId as string),
    enabled: !!companyId,
    // runtime muda por fora (reaper/TTL/teardown): janela curta
    refetchInterval: 15_000,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.previewServers(companyId ?? "") });
  };

  const startMutation = useMutation({
    mutationFn: (issueId: string) => startPreviewServer(companyId as string, issueId),
    onSettled: invalidate,
  });
  const stopMutation = useMutation({
    mutationFn: (issueId: string) => stopPreviewServer(companyId as string, issueId),
    onSettled: invalidate,
  });

  const screen = serversQuery.data;
  const startFailure = startMutation.data && !startMutation.data.started
    ? startMutation.data.error
    : null;

  return (
    <View>
      <ViewHead
        title="Servidores"
        sub="apps vivas do preview — sob demanda, TTL por inatividade"
      />

      <h2 className="section-label">Vivos</h2>
      {screen && screen.live.length === 0 && (
        <EmptyState compact className="mb-[22px]">
          nenhum servidor vivo — inicie por uma candidata abaixo, pela Issue ou pelo card de Review
        </EmptyState>
      )}
      {screen && screen.live.length > 0 && (
        <ul className="servers-list">
          {screen.live.map((vivo) => (
            <li key={vivo.serverName} className="server-row">
              <span className="server-led live" aria-hidden />
              <PreviewServerThumbnail
                companyId={companyId}
                issueId={vivo.issueId}
                variant="compact"
              />
              <div className="server-row-main">
                <CompanyLink href={`/issues/${vivo.issueId}`} className="server-issue">
                  {vivo.issueSeq !== null && (
                    <span className="mono text-faint">#{vivo.issueSeq}</span>
                  )}{" "}
                  {vivo.issueTitle ?? (vivo.issueSeq === null ? shortId(vivo.issueId) : "")}
                </CompanyLink>
                <span className="server-row-meta mono">
                  {vivo.agentName ?? "—"} · $ {vivo.command ?? "?"} · {vivo.url.replace("http://", "")}
                </span>
              </div>
              <span className="server-ttl mono" title="TTL por inatividade">
                ttl {fmtTtl(vivo.ttlRemainingSeconds)}
              </span>
              <a
                className="btn"
                href={vivo.url}
                target="_blank"
                rel="noopener noreferrer"
              >
                <ExternalLink size={12} strokeWidth={2} /> abrir
              </a>
              <button
                type="button"
                className="btn server-stop"
                disabled={stopMutation.isPending}
                onClick={() => stopMutation.mutate(vivo.issueId)}
              >
                <Square size={12} strokeWidth={2} /> matar
              </button>
            </li>
          ))}
        </ul>
      )}

      <h2 className="section-label">Candidatas</h2>
      {screen && screen.candidates.length === 0 && (
        <EmptyState compact className="mb-[22px]">
          nenhuma Issue declarou servidor ainda — o agente publica .orq/preview/server.json no run
        </EmptyState>
      )}
      {screen && screen.candidates.length > 0 && (
        <ul className="servers-list">
          {screen.candidates.map((candidata) => (
            <li key={candidata.issueId} className="server-row">
              <span className={candidata.live ? "server-led live" : "server-led"} aria-hidden />
              <PreviewServerThumbnail
                companyId={companyId}
                issueId={candidata.issueId}
                variant="compact"
              />
              <div className="server-row-main">
                <CompanyLink href={`/issues/${candidata.issueId}`} className="server-issue">
                  {candidata.issueSeq !== null && (
                    <span className="mono text-faint">#{candidata.issueSeq}</span>
                  )}{" "}
                  {candidata.issueTitle ?? (candidata.issueSeq === null ? shortId(candidata.issueId) : "")}
                </CompanyLink>
                <span className="server-row-meta mono">
                  $ {candidata.command} :{candidata.port} · declarado{" "}
                  {fmtAgo(candidata.createdAt, nowMs)} · run {shortId(candidata.runId)}
                </span>
              </div>
              {!candidata.live && (
                <button
                  type="button"
                  className="btn primary"
                  disabled={startMutation.isPending}
                  onClick={() => startMutation.mutate(candidata.issueId)}
                >
                  <Play size={12} strokeWidth={2} />
                  {startMutation.isPending ? "subindo…" : "iniciar"}
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      {startFailure && (
        <div className="server-failure" role="alert">
          <span className="text-coral">{startFailure}</span>
        </div>
      )}
    </View>
  );
}
