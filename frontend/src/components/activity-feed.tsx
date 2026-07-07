"use client";

import { Ban, Check, RotateCcw, ShieldCheck, Star, X, Zap, type LucideIcon } from "lucide-react";
import { Fragment, useMemo, useState } from "react";
import { EmptyState } from "@/components/ui/empty-state";
import type { ActivityEvent } from "@/lib/api";
import { buildFeedEntry, type FeedIcon, type FeedLookups } from "@/lib/feed";
import { fmtAgo } from "@/lib/format";
import { cn } from "@/lib/utils";

// O feed de atividade do Mission Control: shape cru do activity_log →
// buildFeedEntry (helpers testados) → segmentos renderizados. Entrada NOVA
// (id que não estava na primeira carga) monta com .fresh = flash verde 1.4s.

const FEED_ICONS: Record<FeedIcon, LucideIcon> = {
  blocked: Ban,
  reopened: RotateCcw,
  cancelled: X,
  review: ShieldCheck,
  approved: Check,
  rejected: X,
  skill: Star,
  generic: Zap,
};

export function ActivityFeed({
  events,
  lookups,
  nowMs,
  emptyNote = "sem atividade registrada ainda",
}: {
  events: ActivityEvent[];
  lookups: FeedLookups;
  nowMs: number;
  emptyNote?: string;
}) {
  // Ids presentes na PRIMEIRA carga não dão flash — só entrada nova anima
  // (o componente monta junto com o primeiro dado; o initializer congela).
  const [initialIds] = useState(() => new Set(events.map((event) => event.id)));

  const entries = useMemo(
    () => events.map((event) => buildFeedEntry(event, lookups)),
    [events, lookups],
  );

  if (entries.length === 0) {
    // O feed vive DENTRO de uma seção (Mission Control / detail da issue) —
    // vazio de seção é compact (P2 #18), não o painel cheio de superfície.
    return <EmptyState compact>{emptyNote}</EmptyState>;
  }

  return (
    <div className="feed">
      {entries.map((entry) => {
        const Icon = FEED_ICONS[entry.icon];
        const fresh = !initialIds.has(entry.id);
        return (
          <div key={entry.id} className={cn("feed-row", fresh && "fresh")}>
            <span className="feed-time">{fmtAgo(entry.createdAt, nowMs)}</span>
            <span className={`feed-dot ${entry.tone}`}>
              <Icon size={11} strokeWidth={2} />
            </span>
            <span className="feed-body">
              {entry.segments.map((segment, index) =>
                segment.kind === "strong" ? (
                  <b key={index}>{segment.text}</b>
                ) : segment.kind === "mono" ? (
                  <span key={index} className="mono">
                    {segment.text}
                  </span>
                ) : (
                  <Fragment key={index}>{segment.text}</Fragment>
                ),
              )}
            </span>
          </div>
        );
      })}
    </div>
  );
}
