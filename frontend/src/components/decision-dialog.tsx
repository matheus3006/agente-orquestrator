"use client";

import { useState } from "react";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { type ReviewDecision } from "@/lib/api";
import { shortId } from "@/lib/format";

// A decisão de um ciclo de review (F.5 + F-C1/C14). Controlado: a PÁGINA é dona
// da mutation porque o card precisa sair animado (.leaving) ANTES da invalidação
// — o dialog só coleta o texto e devolve a confirmação. reason ≤2000 (o server
// valida; 422 volta como errorMessage inline, sem fechar). APPROVE→DONE,
// REJECT→BLOCKED.
//
// C14 ("perguntar sempre, bloquear nunca"): no APPROVE o prompt é o racionalProbe
// — a pergunta NÃO-bloqueante nas PALAVRAS da política (criativo = maiêutica D10;
// não-criativo pesca o risco aceito); responder fabrica a Decisão, pular deixa só
// o histórico. No REJECT o motivo é OBRIGATÓRIO (ele É a Decisão do registro) —
// o botão trava sem texto; o server é o backstop (400).

export interface DecisionTarget {
  reviewId: string;
  issueId: string;
  title: string;
  runId: string;
  decision: ReviewDecision;
  /** A pergunta do approve nas palavras da política (C14); null degrada pro texto padrão. */
  racionalProbe: string | null;
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
  const motivoAusente = isReject && reason.trim() === "";

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (!pending && !motivoAusente) {
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
            ? "REJECT estaciona a issue em BLOCKED — o motivo é obrigatório e vira a Decisão do registro."
            : "APPROVE conclui a issue (DONE). Responder vira Decisão no log; pular deixa só o histórico."}
        </p>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={2000}
          placeholder={
            isReject
              ? "motivo (obrigatório) — o que barrou a entrega?"
              : (target.racionalProbe ?? "alguma ressalva ou risco que valha registrar? (opcional)")
          }
          autoFocus
        />
        {errorMessage && <p className="m-0 text-[12.5px] text-coral">{errorMessage}</p>}
      </div>
      <div className="dialog-actions">
        <DialogClose className="btn ghost" type="button">
          Cancelar
        </DialogClose>
        <button
          className={isReject ? "btn danger" : "btn primary"}
          type="submit"
          disabled={pending || motivoAusente}
        >
          {pending ? "Enviando…" : isReject ? "Rejeitar ciclo" : "Aprovar ciclo"}
        </button>
      </div>
    </form>
  );
}
