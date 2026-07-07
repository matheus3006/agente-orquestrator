"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { ApiError, unblockIssue, type UnblockDecision } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";

// O recovery da issue BLOCKED (ADR-0011), com a confirmação do protótipo:
// REOPEN devolve a OPEN mantendo o assignee; CANCEL encerra de vez.

export function UnblockIssueDialog({
  companyId,
  issueId,
  issueTitle,
  decision,
  onClose,
}: {
  companyId: string | undefined;
  issueId: string;
  issueTitle: string;
  decision: UnblockDecision | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const unblock = useMutation({
    mutationFn: (confirmed: UnblockDecision) =>
      unblockIssue(companyId as string, issueId, confirmed),
    onSuccess: (_issue, confirmed) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      pushToast(
        confirmed === "REOPEN" ? "Issue reaberta — assignee mantido" : "Issue cancelada",
        confirmed === "REOPEN" ? "good" : "bad",
      );
      onClose();
    },
    onError: (error) => {
      pushToast(error instanceof ApiError ? error.message : "não deu para falar com o backend", "bad");
      // 409 = o cache está ERRADO (a issue já não está BLOCKED — outro ator
      // decidiu): ressincroniza em vez de deixar botões que sempre falham.
      if (error instanceof ApiError && error.status === 409) {
        queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      }
      onClose();
    },
  });

  const isCancel = decision === "CANCEL";

  return (
    <Dialog open={decision !== null} onOpenChange={(next) => !next && onClose()}>
      <DialogContent aria-describedby={undefined}>
        <div className="dialog-body">
          <DialogTitle className="dialog-title">
            {isCancel ? "Cancelar issue" : "Reabrir issue"}
          </DialogTitle>
          <p className="dialog-text">
            <b>{issueTitle}</b>
            <br />
            {isCancel
              ? "CANCEL encerra a issue de vez (CANCELLED)."
              : "REOPEN devolve a issue para OPEN mantendo o assignee — o próximo wakeup re-executa com o mesmo agente."}
          </p>
        </div>
        <div className="dialog-actions">
          <DialogClose className="btn ghost" type="button">
            Voltar
          </DialogClose>
          <button
            type="button"
            className={isCancel ? "btn danger" : "btn primary"}
            disabled={unblock.isPending || !decision}
            onClick={() => decision && unblock.mutate(decision)}
          >
            {isCancel ? "Cancelar issue" : "Reabrir (REOPEN)"}
          </button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
