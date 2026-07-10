"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Termo } from "@/components/termo";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import {
  ApiError,
  decideApproval,
  getIssueReport,
  type Approval,
  type IssueSummary,
} from "@/lib/api";
import { acaoLegivel, issueDaApproval } from "@/lib/ceo-conversa";
import { fmtClock, shortId } from "@/lib/format";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";

// O card de Approval ANCORADO na thread (T.3-FE, mockup §4): o marco no ponto
// do tempo em que surgiu, corpo LEGÍVEL (verbo + alvo + resumo — nunca JSON
// cru), decisão na própria thread. O 422 é o GRILL (ADR-0020), não erro: o
// probe vira painel de re-pergunta — lógico bloqueia até razão substantiva;
// intuição commita com preferência. A fonte da verdade é a linha approval no
// backend; este card só projeta e pede.

interface Grelha {
  decision: "APPROVE" | "REJECT";
  probe: string;
  rounds: number;
}

export function ApprovalThreadCard({
  companyId,
  approval,
  issues,
  onOpenTrace,
}: {
  companyId: string;
  approval: Approval;
  issues: IssueSummary[];
  onOpenTrace: (runId: string) => void;
}) {
  const [grelha, setGrelha] = useState<Grelha | null>(null);
  const [racional, setRacional] = useState("");
  const [falha, setFalha] = useState<string | null>(null);
  const racionalRef = useRef<HTMLTextAreaElement>(null);

  const queryClient = useQueryClient();
  const decisao = useMutation({
    mutationFn: (input: { decision: "APPROVE" | "REJECT"; racional?: string }) =>
      decideApproval(companyId, approval.id, input),
    onSuccess: (outcome, input) => {
      if (outcome.kind === "grilled") {
        // O grill: o commit NÃO aconteceu; a rodada ficou contada. A tela
        // re-pergunta com o probe do escriba-que-insiste.
        setGrelha({ decision: input.decision, probe: outcome.probe, rounds: outcome.rounds });
        setFalha(null);
        return;
      }
      setGrelha(null);
      setRacional("");
      setFalha(null);
      pushToast(
        outcome.approval.status === "APPROVED"
          ? "Aprovada — a ação foi disparada (ator: você)."
          : "Rejeitada — registrada como decisão do gate.",
      );
      // Decidir mexe na fila (2 projeções), pode criar issue/agente/wakeup,
      // fabrica Decisão e pode consolidar a carta — invalida o que projeta.
      queryClient.invalidateQueries({ queryKey: queryKeys.approvalsScope(companyId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.issues(companyId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.agents(companyId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.activity(companyId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.carta(companyId) });
    },
    onError: (error: unknown) => {
      setFalha(error instanceof ApiError ? error.message : "não deu para falar com o backend");
    },
  });

  // Foco DEPOIS do commit do React (rAF pode disparar antes do form montar —
  // visto na verificação viva): o probe muda a cada rodada e re-foca.
  useEffect(() => {
    if (grelha) {
      racionalRef.current?.focus();
    }
  }, [grelha]);

  const decidir = (decision: "APPROVE" | "REJECT") => {
    if (decisao.isPending) {
      return;
    }
    const texto = racional.trim();
    decisao.mutate(texto ? { decision, racional: texto } : { decision });
  };

  const acao = acaoLegivel(approval);
  const pendente = approval.status === "PENDING";
  const issueCriada = issueDaApproval(approval, issues);

  return (
    <article className="aprovacao" data-status={approval.status} aria-label="Aprovação">
      <div className="aprovacao-head">
        <span className="aprovacao-k">◆ Aprovação</span>
        <span className="aprovacao-titulo">{approval.resumo ?? `${acao.verbo} — proposta`}</span>
        <span className="aprovacao-area">{approval.area}</span>
      </div>

      <div className="aprovacao-corpo">
        <div className="aprovacao-acao">
          {acao.verbo}
          {acao.alvo && (
            <>
              {" — "}
              <b>{acao.alvo}</b>
            </>
          )}
        </div>
        {approval.resumo && <div className="aprovacao-resumo">{approval.resumo}</div>}
        <div className="aprovacao-meta">
          proposta do CEO · {fmtClock(approval.requestedAt)}
          {approval.racionalTipo === "INTUICAO" && " · área criativa (intuição commita)"}
        </div>
      </div>

      {pendente && (
        <div className="aprovacao-async">
          ◈ escalação assíncrona — os outros times continuam rodando; trava só este marco.
        </div>
      )}

      {pendente && !grelha && (
        <div className="aprovacao-acoes">
          <button
            className="btn primary"
            type="button"
            disabled={decisao.isPending}
            onClick={() => decidir("APPROVE")}
          >
            {decisao.isPending ? "Enviando…" : "Aprovar"}
          </button>
          <button
            className="btn danger"
            type="button"
            disabled={decisao.isPending}
            onClick={() => decidir("REJECT")}
          >
            Rejeitar
          </button>
          {approval.runId && (
            <button
              type="button"
              className="link-trace"
              onClick={() => onOpenTrace(approval.runId as string)}
            >
              ▸ abrir transcript
            </button>
          )}
        </div>
      )}

      {pendente && grelha && (
        <form
          className="grill"
          onSubmit={(event) => {
            event.preventDefault();
            decidir(grelha.decision);
          }}
        >
          <span className="grill-rodada">
            <Termo id="grill">grill</Termo> · rodada {grelha.rounds} ·{" "}
            {grelha.decision === "APPROVE" ? "aprovando" : "rejeitando"}
          </span>
          <p className="grill-probe">{grelha.probe}</p>
          <textarea
            ref={racionalRef}
            value={racional}
            onChange={(event) => setRacional(event.target.value)}
            maxLength={4000}
            rows={2}
            placeholder={
              approval.racionalTipo === "INTUICAO"
                ? "a preferência real — o que te agradou (ou incomodou)?"
                : "a razão substantiva — que premissa sustenta isso?"
            }
            aria-label="Racional da decisão"
          />
          {falha && <p className="m-0 text-[12px] text-coral">{falha}</p>}
          <div className="grill-acoes">
            <button
              className={grelha.decision === "APPROVE" ? "btn primary" : "btn danger"}
              type="submit"
              disabled={decisao.isPending || !racional.trim()}
            >
              {decisao.isPending
                ? "Enviando…"
                : grelha.decision === "APPROVE"
                  ? "Registrar racional e aprovar"
                  : "Registrar racional e rejeitar"}
            </button>
            <button
              className="btn ghost"
              type="button"
              onClick={() => {
                setGrelha(null);
                setFalha(null);
              }}
            >
              Deixar pra depois
            </button>
          </div>
        </form>
      )}

      {!pendente && (
        <div className="aprovacao-decidida">
          <span className="aprovacao-veredito">
            {approval.status === "APPROVED" ? "✓ aprovada" : "✕ rejeitada"}
            {approval.racionalArrancado &&
              ` — racional arrancado (${approval.grillRounds} ${
                approval.grillRounds === 1 ? "rodada" : "rodadas"
              } de grill)`}
            {approval.decidedAt && ` · ${fmtClock(approval.decidedAt)}`}
          </span>
          {approval.racional && (
            <span className="aprovacao-racional">
              <b>racional</b> {approval.racional}
            </span>
          )}
          <div className="flex items-center gap-3">
            {issueCriada && <ReportDaIssue companyId={companyId} issue={issueCriada} />}
            {approval.runId && (
              <button
                type="button"
                className="link-trace"
                onClick={() => onOpenTrace(approval.runId as string)}
              >
                ▸ abrir transcript
              </button>
            )}
          </div>
        </div>
      )}
    </article>
  );
}

/**
 * Disclosure do Ledger (mockup §5): o report da Issue criada pelo APPROVE — a
 * profundidade a 1 clique, nunca despejada na conversa. 404 = nenhum run
 * concluiu ainda (estado legítimo, não erro).
 */
function ReportDaIssue({ companyId, issue }: { companyId: string; issue: IssueSummary }) {
  const [aberto, setAberto] = useState(false);

  const reportQuery = useQuery({
    queryKey: queryKeys.issueReport(companyId, issue.id),
    queryFn: () => getIssueReport(companyId, issue.id),
    enabled: aberto,
    staleTime: 30_000,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });

  const semReport = reportQuery.error instanceof ApiError && reportQuery.error.status === 404;

  return (
    <>
      <button type="button" className="link-trace" onClick={() => setAberto(true)}>
        ▸ report da issue
      </button>
      <Dialog open={aberto} onOpenChange={setAberto}>
        <DialogContent aria-describedby={undefined} className="max-w-[640px]">
          <div className="dialog-body">
            <DialogTitle className="dialog-title">
              Report — <span className="mono text-faint">#{issue.seq}</span> {issue.title}
            </DialogTitle>
            {reportQuery.data && (
              <>
                <p className="dialog-text font-mono text-[11px]">
                  run {shortId(reportQuery.data.runId)} · concluído{" "}
                  {fmtClock(reportQuery.data.concludedAt)} · a saída final do run concluinte
                  (Ledger por-Issue)
                </p>
                <div className="memoria-corpo">{reportQuery.data.report}</div>
              </>
            )}
            {semReport && (
              <p className="dialog-text">
                A issue ainda não tem run concluído — o Ledger escreve o report quando a primeira
                execução liquidar.
              </p>
            )}
            {reportQuery.isPending && aberto && (
              <div className="h-24 animate-pulse rounded-lg bg-raised" aria-busy="true" />
            )}
            {reportQuery.isError && !semReport && (
              <p className="dialog-text text-coral">não deu para carregar o report — feche e tente de novo</p>
            )}
          </div>
          <div className="dialog-actions">
            <DialogClose className="btn ghost" type="button">
              Fechar
            </DialogClose>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
