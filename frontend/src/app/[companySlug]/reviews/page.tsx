"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ShieldCheck, X } from "lucide-react";
import { useState } from "react";
import { CompanyLink, useCompanyNav } from "@/components/company-link";
import { DecisionDialog, type DecisionTarget } from "@/components/decision-dialog";
import { AssigneeChip, RiskBadge } from "@/components/issue-bits";
import { ReviewPreviewEmbed } from "@/components/preview-section";
import { ReviewServerEmbed } from "@/components/preview-server-section";
import { StepTraceDrawer, type TranscriptRequest } from "@/components/step-trace-drawer";
import { Termo } from "@/components/termo";
import { EmptyState } from "@/components/ui/empty-state";
import { View, ViewHead } from "@/components/view";
import {
  ApiError,
  decideReview,
  listAgents,
  listIssues,
  listReviews,
  type ReviewDecision,
} from "@/lib/api";
import { fmtAgo, shortId } from "@/lib/format";
import { queryKeys } from "@/lib/query-keys";
import { resolveReviewCard } from "@/lib/reviews";
import { pushToast } from "@/lib/toaster";
import { useActiveCompany } from "@/lib/use-active-company";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";

// Review (F.5): fila PENDING default com tabs de status. A ReviewResponse é
// enxuta (resolveReviewCard resolve título/risco/assignee pela lista de issues/
// agentes). Decisão SEMPRE ancorada no reviewId (R.3); o card sai animado
// (.leaving ~320ms) ANTES de invalidar companyScope. runStatus REAL vem do
// drawer (latente R.2). Sem poll: staleTime 30s + refetch on focus + invalidação.

const STATUS_TABS = ["PENDING", "APPROVED", "REJECTED"] as const;
const LEAVING_MS = 320;

export default function ReviewsPage() {
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const nav = useCompanyNav();
  const queryClient = useQueryClient();
  const nowMs = useNow(1000);

  const [status, setStatus] = useState<string>("PENDING");
  const [target, setTarget] = useState<DecisionTarget | null>(null);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  const [leavingId, setLeavingId] = useState<string | null>(null);
  const [drawer, setDrawer] = useState<TranscriptRequest | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const reviewsQuery = useQuery({
    queryKey: queryKeys.reviews(companyId ?? "", status),
    queryFn: () => listReviews(companyId as string, status),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  // Contagem do tab PENDING visível em qualquer aba (dedup com a query acima
  // quando status === "PENDING": mesma queryKey ⇒ um fetch só).
  const pendingCountQuery = useQuery({
    queryKey: queryKeys.reviews(companyId ?? "", "PENDING"),
    queryFn: () => listReviews(companyId as string, "PENDING"),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const issuesQuery = useQuery({
    queryKey: queryKeys.issues(companyId ?? ""),
    queryFn: () => listIssues(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents(companyId ?? ""),
    queryFn: () => listAgents(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });

  const decide = useMutation({
    mutationFn: (decided: DecisionTarget & { reason: string }) =>
      decideReview(companyId as string, decided.issueId, {
        decision: decided.decision,
        reason: decided.reason || undefined,
        reviewId: decided.reviewId,
      }),
    onSuccess: (_issue, decided) => {
      setTarget(null);
      setDecisionError(null);
      setLeavingId(decided.reviewId); // card anima a saída ANTES de sumir do refetch
      const isReject = decided.decision === "REJECT";
      window.setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
        setLeavingId(null);
        pushToast(
          isReject ? "Ciclo rejeitado — issue BLOCKED (destrave no detail)" : "Ciclo aprovado — issue DONE",
          isReject ? "bad" : "good",
        );
      }, LEAVING_MS);
    },
    onError: (error) => {
      // 422 = reason > 2000: mantém o dialog aberto com o erro inline.
      if (error instanceof ApiError && error.status === 422) {
        setDecisionError(error.message);
        return;
      }
      setTarget(null);
      setDecisionError(null);
      pushToast(error instanceof ApiError ? error.message : "não deu para falar com o backend", "bad");
      // 409 = âncora stale / issue já não PENDING_REVIEW: o cache está errado
      // (outro ator decidiu), ressincroniza em vez de deixar botão que só falha.
      if (error instanceof ApiError && error.status === 409) {
        queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      }
    },
  });

  const openDecision = (card: DecisionTarget) => {
    setDecisionError(null);
    setTarget(card);
  };
  const openRun = (runId: string, issueTitle: string) => {
    setDrawer({ runId, issueTitle, agentName: null });
    setDrawerOpen(true);
  };

  const agents = agentsQuery.data;
  const issues = issuesQuery.data;
  const cards = (reviewsQuery.data ?? []).map((review) => resolveReviewCard(review, issues, agents));
  const pendingCount = pendingCountQuery.data?.length;

  return (
    <View>
      <ViewHead
        title="Fila de Review"
        sub={
          <>
            decisão <Termo id="ancorada">ancorada</Termo> no ciclo (reviewId) · reason opcional
          </>
        }
      />

      <div className="filter-tabs">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab}
            type="button"
            className={cn("filter-tab", tab === status && "on")}
            onClick={() => setStatus(tab)}
          >
            {tab}
            {tab === "PENDING" && pendingCount !== undefined ? ` (${pendingCount})` : ""}
          </button>
        ))}
      </div>

      {reviewsQuery.isError && (
        <EmptyState tone="erro">
          Não deu para carregar a fila — recarregue ou confira o backend.
        </EmptyState>
      )}

      {reviewsQuery.isPending && !reviewsQuery.isError && (
        <div className="flex flex-col gap-3" aria-busy="true">
          {Array.from({ length: 3 }).map((_, index) => (
            <div key={index} className="h-20 animate-pulse rounded-lg bg-raised" />
          ))}
        </div>
      )}

      {reviewsQuery.data && cards.length === 0 && (
        <EmptyState icon={<ShieldCheck />}>
          fila limpa — nenhum ciclo {status.toLowerCase()} no momento
        </EmptyState>
      )}

      {cards.map((card) => {
        const pending = card.status === "PENDING";
        return (
          <div key={card.reviewId} className={cn("review-card", leavingId === card.reviewId && "leaving")}>
            <div>
              <h3 className="review-title">
                <CompanyLink
                  href={`/issues/${card.issueId}`}
                  prefetch={false}
                  onMouseEnter={() => nav.prefetch(`/issues/${card.issueId}`)}
                >
                  {card.seq !== null && <span className="mono text-faint">#{card.seq}</span>}{" "}
                  {card.title}
                </CompanyLink>
              </h3>
              <div className="review-meta">
                {card.riskLevel !== null && <RiskBadge riskLevel={card.riskLevel} />}
                <AssigneeChip name={card.assigneeName} />
                <span>
                  run{" "}
                  <button
                    type="button"
                    className="text-ice"
                    style={{ font: "inherit" }}
                    onClick={() => openRun(card.runId, card.title)}
                  >
                    {shortId(card.runId)}
                  </button>
                </span>
                <span>âncora {card.reviewId}</span>
                {pending && (
                  <span className="waiting-clock">{fmtAgo(card.requestedAt, nowMs)}</span>
                )}
              </div>
            </div>

            {pending ? (
              <div className="review-actions">
                <button
                  type="button"
                  className="btn primary"
                  onClick={() =>
                    openDecision({
                      reviewId: card.reviewId,
                      issueId: card.issueId,
                      title: card.title,
                      runId: card.runId,
                      decision: "APPROVE" satisfies ReviewDecision,
                      racionalProbe: card.racionalProbe,
                    })
                  }
                >
                  <Check size={13} strokeWidth={2} /> Aprovar
                </button>
                <button
                  type="button"
                  className="btn danger"
                  onClick={() =>
                    openDecision({
                      reviewId: card.reviewId,
                      issueId: card.issueId,
                      title: card.title,
                      runId: card.runId,
                      decision: "REJECT" satisfies ReviewDecision,
                      racionalProbe: card.racionalProbe,
                    })
                  }
                >
                  <X size={13} strokeWidth={2} /> Rejeitar
                </button>
              </div>
            ) : (
              <div className="max-w-[340px] text-[12px] text-dim">
                {card.decisionReason ? `“${card.decisionReason}”` : ""}
                <div className="review-meta mt-1">
                  {card.decidedAt ? `decidida ${fmtAgo(card.decidedAt, nowMs)}` : "decidida"}
                </div>
              </div>
            )}

            {/* F.9 (ADR-0021 §5): o momento-demo — aprovar VENDO a versão EXATA
                do run sob revisão (nunca outra); decididas ficam leves. */}
            {pending && (
              <ReviewPreviewEmbed
                companyId={companyId}
                issueId={card.issueId}
                runId={card.runId}
              />
            )}

            {/* F.10 (ADR-0023 §6): o momento-demo VIVO — "Rodar app" quando o
                run sob revisão declarou servidor; manifest de run posterior
                ganha o aviso "workspace avançou". */}
            {pending && (
              <ReviewServerEmbed
                companyId={companyId}
                issueId={card.issueId}
                runId={card.runId}
              />
            )}
          </div>
        );
      })}

      <DecisionDialog
        target={target}
        pending={decide.isPending}
        errorMessage={decisionError}
        onCancel={() => {
          if (!decide.isPending) {
            setTarget(null);
            setDecisionError(null);
          }
        }}
        onConfirm={(reason) => target && decide.mutate({ ...target, reason })}
      />

      <StepTraceDrawer
        companyId={companyId}
        request={drawer}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />
    </View>
  );
}
