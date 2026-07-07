"use client";

import { useState } from "react";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { type ReviewDecision } from "@/lib/api";
import { shortId } from "@/lib/format";

// A decisão de um ciclo de review (F.5), com os textos congelados do
// openDecisionDialog. Controlado: a PÁGINA é dona da mutation porque o card
// precisa sair animado (.leaving) ANTES da invalidação — o dialog só coleta o
// reason e devolve a confirmação. reason ≤2000 (o server valida; 422 volta como
// errorMessage inline, sem fechar). APPROVE→DONE, REJECT→BLOCKED.

export interface DecisionTarget {
  reviewId: string;
  issueId: string;
  title: string;
  runId: string;
  decision: ReviewDecision;
}

export function DecisionDialog({
  target,
  pending,
  errorMessage,
  onCancel,
  onConfirm,
}: {
  target: DecisionTarget | null;
  pending: boolean;
  errorMessage: string | null;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}) {
  return (
    <Dialog open={target !== null} onOpenChange={(next) => !next && onCancel()}>
      <DialogContent aria-describedby={undefined}>
        {/* montado só com target ⇒ o reason nasce limpo a cada abertura */}
        {target && (
          <DecisionForm
            target={target}
            pending={pending}
            errorMessage={errorMessage}
            onConfirm={onConfirm}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function DecisionForm({
  target,
  pending,
  errorMessage,
  onConfirm,
}: {
  target: DecisionTarget;
  pending: boolean;
  errorMessage: string | null;
  onConfirm: (reason: string) => void;
}) {
  const [reason, setReason] = useState("");
  const isReject = target.decision === "REJECT";

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (!pending) {
          onConfirm(reason.trim());
        }
      }}
    >
      <div className="dialog-body">
        <DialogTitle className="dialog-title">
          {isReject ? "Rejeitar" : "Aprovar"} ciclo de review
        </DialogTitle>
        <p className="dialog-text">
          <b>{target.title}</b>
          <br />
          <span className="font-mono">
            âncora {target.reviewId} · run {shortId(target.runId)}
          </span>
          <br />
          {isReject
            ? "REJECT estaciona a issue em BLOCKED — o recovery é o unblock (REOPEN/CANCEL)."
            : "APPROVE conclui a issue (DONE)."}
        </p>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={2000}
          placeholder={`reason ${
            isReject ? "(recomendado no reject)" : "(opcional)"
          } — fica na tabela review, nunca no activity log`}
          autoFocus
        />
        {errorMessage && <p className="m-0 text-[12.5px] text-coral">{errorMessage}</p>}
      </div>
      <div className="dialog-actions">
        <DialogClose className="btn ghost" type="button">
          Cancelar
        </DialogClose>
        <button className={isReject ? "btn danger" : "btn primary"} type="submit" disabled={pending}>
          {pending ? "Enviando…" : isReject ? "Rejeitar ciclo" : "Aprovar ciclo"}
        </button>
      </div>
    </form>
  );
}
