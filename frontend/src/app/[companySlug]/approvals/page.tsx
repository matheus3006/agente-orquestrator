"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Stamp, X } from "lucide-react";
import { useState } from "react";
import { Termo } from "@/components/termo";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { View, ViewHead } from "@/components/view";
import {
  ApiError,
  batchDecideApprovals,
  listAgents,
  listApprovals,
  type Approval,
  type ApprovalStatus,
} from "@/lib/api";
import {
  FILTRO_TIPOS,
  alternarSelecaoTotal,
  filtrarPorTipo,
  itensDoLote,
  loteHabilitado,
  resumoDoLote,
  type FiltroTipo,
} from "@/lib/approvals";
import { acaoLegivel } from "@/lib/ceo-conversa";
import { fmtAgo } from "@/lib/format";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";
import { useActiveCompany } from "@/lib/use-active-company";
import { useNow } from "@/lib/use-now";
import { cn } from "@/lib/utils";

// A fila de Aprovações (ADR-0035, D1–D3): a MESMA fila que a thread projeta
// como cards ancorados, agregada por company — nascida da cascata real de 27
// HIRE_AGENT PENDING. Checkbox + selecionar tudo + agir nos SELECIONADOS (D3);
// o lote só habilita com filtro de tipo ativo (D2) e NUNCA carrega override de
// motor (D12 — o escape é a thread, onde A4 já vale). Recusa leva motivo comum
// + observação por item (D8) e volta ao CEO como kickback agregado por
// conversa (D5–D7). Pulada não é erro: a contagem do toast sai da resposta (D9).

const STATUS_TABS = ["PENDING", "APPROVED", "REJECTED"] as const;

interface LoteAberto {
  decision: "APPROVE" | "REJECT";
  itens: Approval[];
}

export default function ApprovalsPage() {
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const queryClient = useQueryClient();
  const nowMs = useNow(1000);

  const [status, setStatus] = useState<ApprovalStatus>("PENDING");
  const [filtro, setFiltro] = useState<FiltroTipo>("todos");
  const [selecionadas, setSelecionadas] = useState<ReadonlySet<string>>(new Set());
  const [lote, setLote] = useState<LoteAberto | null>(null);
  const [falhaDoLote, setFalhaDoLote] = useState<string | null>(null);

  const filaQuery = useQuery({
    queryKey: queryKeys.approvalsQueue(companyId ?? "", status),
    queryFn: () => listApprovals(companyId as string, status),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  // Contagem do tab PENDING visível em qualquer aba (mesma queryKey ⇒ um fetch só).
  const pendingCountQuery = useQuery({
    queryKey: queryKeys.approvalsQueue(companyId ?? "", "PENDING"),
    queryFn: () => listApprovals(companyId as string, "PENDING"),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  // ADR-0047 (item 12): resolve o NOME de quem decidiu — o cartão nasce-decidido do gestor
  // promovido mostra "liberado por <gestor>" (decidedByActorType=AGENT). Mesmo mapa/queryKey
  // que a lista de issues já usa pro AssigneeChip.
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents(companyId ?? ""),
    queryFn: () => listAgents(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  // undefined = agents ainda não resolvidos (não afirma nome); null = sem correspondência.
  const nomeDoAgente = (agentId: string | null): string | null | undefined =>
    agentId
      ? agentsQuery.data
        ? (agentsQuery.data.find((agent) => agent.id === agentId)?.name ?? null)
        : undefined
      : null;

  const fila = filaQuery.data ?? [];
  const visiveis = filtrarPorTipo(fila, filtro);
  const selecionadasVisiveis = visiveis.filter((approval) => selecionadas.has(approval.id));
  const pendente = status === "PENDING";
  const habilitado = loteHabilitado(filtro, selecionadasVisiveis.length);
  const tudoMarcado =
    visiveis.length > 0 && visiveis.every((approval) => selecionadas.has(approval.id));

  const decidirLote = useMutation({
    mutationFn: (input: {
      decision: "APPROVE" | "REJECT";
      racional: string;
      motivo: string;
      observacoes: Record<string, string>;
    }) =>
      batchDecideApprovals(companyId as string, {
        decision: input.decision,
        racional: input.racional,
        motivo: input.motivo || undefined,
        items: itensDoLote(visiveis, selecionadas, input.observacoes),
      }),
    onSuccess: (outcome, input) => {
      setLote(null);
      setFalhaDoLote(null);
      setSelecionadas(new Set());
      pushToast(
        resumoDoLote(outcome, input.decision),
        outcome.skipped.length > 0 ? "bad" : "good",
      );
      // O lote pode criar issue/agente/wakeup, fabrica Decisões, consolida a
      // carta e posta kickback na conversa — invalida o que projeta tudo isso.
      queryClient.invalidateQueries({ queryKey: queryKeys.approvalsScope(companyId as string) });
      queryClient.invalidateQueries({ queryKey: queryKeys.issues(companyId as string) });
      queryClient.invalidateQueries({ queryKey: queryKeys.agents(companyId as string) });
      queryClient.invalidateQueries({ queryKey: queryKeys.activity(companyId as string) });
      queryClient.invalidateQueries({ queryKey: queryKeys.carta(companyId as string) });
    },
    onError: (error) => {
      setFalhaDoLote(
        error instanceof ApiError ? error.message : "não deu para falar com o backend",
      );
    },
  });

  const trocarStatus = (tab: ApprovalStatus) => {
    setStatus(tab);
    setSelecionadas(new Set());
  };
  const trocarFiltro = (novo: FiltroTipo) => {
    setFiltro(novo);
    setSelecionadas(new Set()); // o lote é homogêneo por tipo (D2): seleção não cruza filtro
  };
  const alternarUma = (id: string) => {
    const proxima = new Set(selecionadas);
    if (proxima.has(id)) {
      proxima.delete(id);
    } else {
      proxima.add(id);
    }
    setSelecionadas(proxima);
  };
  const abrirLote = (decision: "APPROVE" | "REJECT") => {
    setFalhaDoLote(null);
    setLote({ decision, itens: selecionadasVisiveis });
  };

  const contagemDoTipo = (tipo: FiltroTipo) =>
    tipo === "todos" ? fila.length : fila.filter((approval) => approval.actionType === tipo).length;
  const pendingCount = pendingCountQuery.data?.length;

  return (
    <View>
      <ViewHead
        title="Fila de Aprovações"
        sub={
          <>
            o gate pré-hoc agregado por company · recusa volta ao CEO com o motivo · sem
            override de <Termo id="motor">motor</Termo> em lote (decida na thread para isso)
          </>
        }
      />

      <div className="filter-tabs">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab}
            type="button"
            className={cn("filter-tab", tab === status && "on")}
            onClick={() => trocarStatus(tab)}
          >
            {tab}
            {tab === "PENDING" && pendingCount !== undefined ? ` (${pendingCount})` : ""}
          </button>
        ))}
      </div>

      <div className="filter-tabs" aria-label="Filtro por tipo de ação">
        {FILTRO_TIPOS.map(({ id, label }) => (
          <button
            key={id}
            type="button"
            className={cn("filter-tab", id === filtro && "on")}
            onClick={() => trocarFiltro(id)}
          >
            {label}
            {filaQuery.data ? ` (${contagemDoTipo(id)})` : ""}
          </button>
        ))}
      </div>

      {pendente && (
        <div className="review-card !grid-cols-1">
          <div className="flex flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-[12.5px] text-dim">
              <input
                type="checkbox"
                className="size-[14px] accent-[var(--phosphor)]"
                checked={tudoMarcado}
                disabled={visiveis.length === 0}
                onChange={() => setSelecionadas(alternarSelecaoTotal(visiveis, selecionadas))}
              />
              selecionar tudo
            </label>
            <span className="text-[12px] text-faint">
              {selecionadasVisiveis.length} de {visiveis.length} selecionada
              {selecionadasVisiveis.length === 1 ? "" : "s"}
            </span>
            <span className="flex-1" />
            <button
              type="button"
              className="btn primary"
              disabled={!habilitado}
              onClick={() => abrirLote("APPROVE")}
            >
              <Check size={13} strokeWidth={2} /> Aprovar selecionados
            </button>
            <button
              type="button"
              className="btn danger"
              disabled={!habilitado}
              onClick={() => abrirLote("REJECT")}
            >
              <X size={13} strokeWidth={2} /> Recusar selecionados
            </button>
          </div>
          {filtro === "todos" && selecionadasVisiveis.length > 0 && (
            <p className="m-0 text-[12px] text-faint">
              escolha um tipo acima para agir em lote — misturar contratação com issue num
              clique só, não (D2)
            </p>
          )}
        </div>
      )}

      {filaQuery.isError && (
        <EmptyState tone="erro">
          Não deu para carregar a fila — recarregue ou confira o backend.
        </EmptyState>
      )}

      {filaQuery.isPending && !filaQuery.isError && (
        <div className="flex flex-col gap-3" aria-busy="true">
          {Array.from({ length: 3 }).map((_, index) => (
            <div key={index} className="h-20 animate-pulse rounded-lg bg-raised" />
          ))}
        </div>
      )}

      {filaQuery.data && visiveis.length === 0 && (
        <EmptyState icon={<Stamp />}>
          fila limpa — nenhuma aprovação {status.toLowerCase()}
          {filtro !== "todos" ? " deste tipo" : ""} no momento
        </EmptyState>
      )}

      {visiveis.map((approval) => {
        const acao = acaoLegivel(approval);
        return (
          <div key={approval.id} className="review-card">
            <div className="flex items-start gap-3">
              {pendente && (
                <input
                  type="checkbox"
                  className="mt-[3px] size-[14px] shrink-0 accent-[var(--phosphor)]"
                  aria-label={`Selecionar ${approval.resumo ?? acao.verbo}`}
                  checked={selecionadas.has(approval.id)}
                  onChange={() => alternarUma(approval.id)}
                />
              )}
              <div>
                <h3 className="review-title">
                  {acao.verbo}
                  {acao.alvo && (
                    <>
                      {" — "}
                      <b>{acao.alvo}</b>
                    </>
                  )}
                </h3>
                {approval.resumo && (
                  <div className="text-[12.5px] text-dim">{approval.resumo}</div>
                )}
                <div className="review-meta">
                  <span>{approval.area}</span>
                  {approval.racionalTipo === "INTUICAO" && <span>área criativa</span>}
                  {pendente ? (
                    <>
                      {/* Hardening ADR-0047 (item 3): quem propôs é a informação que o §2 promete
                          no cartão — gestor trocado/rebaixado entre propor e aprovar é visível
                          AQUI, não bloqueio. Vale pra todo tipo, não só DIRECT_ISSUE. */}
                      <span>proposto por {nomeDoAgente(approval.requestedByAgentId) ?? "—"}</span>
                      <span className="waiting-clock">{fmtAgo(approval.requestedAt, nowMs)}</span>
                    </>
                  ) : (
                    <span>
                      {approval.status === "APPROVED"
                        ? approval.decidedByActorType === "AGENT"
                          ? `✓ liberado por ${nomeDoAgente(approval.decidedByActorId) ?? "—"}`
                          : "✓ aprovada"
                        : "✕ recusada"}
                      {approval.decidedAt ? ` ${fmtAgo(approval.decidedAt, nowMs)}` : ""}
                    </span>
                  )}
                </div>
              </div>
            </div>

            {!pendente && (approval.racional || approval.decisionReason) && (
              <div className="max-w-[340px] text-[12px] text-dim">
                {approval.decisionReason && <div>“{approval.decisionReason}”</div>}
                {approval.racional && (
                  <div className="review-meta mt-1">racional: {approval.racional}</div>
                )}
              </div>
            )}
          </div>
        );
      })}

      <LoteDialog
        lote={lote}
        pending={decidirLote.isPending}
        errorMessage={falhaDoLote}
        onCancel={() => {
          if (!decidirLote.isPending) {
            setLote(null);
            setFalhaDoLote(null);
          }
        }}
        onConfirm={(racional, motivo, observacoes) =>
          lote && decidirLote.mutate({ decision: lote.decision, racional, motivo, observacoes })
        }
      />
    </View>
  );
}

/**
 * O menu do lote: racional compartilhado (o grill barra item a item sem ele),
 * motivo comum obrigatório no REJECT (D8 — é o que o kickback leva VERBATIM ao
 * CEO) e observação opcional por item, distinta dentro do turno síntese.
 */
function LoteDialog({
  lote,
  pending,
  errorMessage,
  onCancel,
  onConfirm,
}: {
  lote: LoteAberto | null;
  pending: boolean;
  errorMessage: string | null;
  onCancel: () => void;
  onConfirm: (racional: string, motivo: string, observacoes: Record<string, string>) => void;
}) {
  return (
    <Dialog open={lote !== null} onOpenChange={(next) => !next && onCancel()}>
      <DialogContent aria-describedby={undefined} className="max-w-[640px]">
        {/* montado só com lote ⇒ racional/motivo/observações nascem limpos a cada abertura */}
        {lote && (
          <LoteForm
            lote={lote}
            pending={pending}
            errorMessage={errorMessage}
            onConfirm={onConfirm}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function LoteForm({
  lote,
  pending,
  errorMessage,
  onConfirm,
}: {
  lote: LoteAberto;
  pending: boolean;
  errorMessage: string | null;
  onConfirm: (racional: string, motivo: string, observacoes: Record<string, string>) => void;
}) {
  const [racional, setRacional] = useState("");
  const [motivo, setMotivo] = useState("");
  const [observacoes, setObservacoes] = useState<Record<string, string>>({});
  const isReject = lote.decision === "REJECT";
  const pronto = racional.trim() !== "" && (!isReject || motivo.trim() !== "");

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        if (!pending && pronto) {
          onConfirm(racional.trim(), motivo.trim(), observacoes);
        }
      }}
    >
      <div className="dialog-body">
        <DialogTitle className="dialog-title">
          {isReject ? "Recusar" : "Aprovar"} {lote.itens.length} selecionada
          {lote.itens.length === 1 ? "" : "s"}
        </DialogTitle>
        <p className="dialog-text">
          {isReject
            ? "A recusa volta ao CEO como UM kickback por conversa, com o motivo verbatim — é o que deixa o racional dele melhorar."
            : "Cada item dispara a ação real na hora (ator: você), com o motor protocolado na proposta."}
        </p>
        <textarea
          value={racional}
          onChange={(event) => setRacional(event.target.value)}
          maxLength={4000}
          rows={2}
          placeholder="racional compartilhado do lote — sem ele o grill pula todos os itens"
          aria-label="Racional do lote"
          autoFocus
        />
        {isReject && (
          <textarea
            value={motivo}
            onChange={(event) => setMotivo(event.target.value)}
            maxLength={2000}
            rows={2}
            placeholder="motivo comum (obrigatório) — vai verbatim ao CEO na conversa de origem"
            aria-label="Motivo comum da recusa"
          />
        )}
        <div className="flex max-h-[220px] flex-col gap-1.5 overflow-y-auto">
          {lote.itens.map((approval) => {
            const acao = acaoLegivel(approval);
            return (
              <div key={approval.id} className="flex items-center gap-2">
                <span className="min-w-0 flex-1 truncate text-[12px] text-dim">
                  {approval.resumo ?? `${acao.verbo}${acao.alvo ? ` — ${acao.alvo}` : ""}`}
                </span>
                <input
                  className="w-[240px] rounded border border-edge bg-transparent px-1.5 py-0.5 text-[11px]"
                  aria-label={`Observação sobre ${approval.resumo ?? acao.verbo}`}
                  placeholder="observação (opcional)"
                  maxLength={2000}
                  value={observacoes[approval.id] ?? ""}
                  onChange={(event) =>
                    setObservacoes({ ...observacoes, [approval.id]: event.target.value })
                  }
                />
              </div>
            );
          })}
        </div>
        {errorMessage && <p className="m-0 text-[12.5px] text-coral">{errorMessage}</p>}
      </div>
      <div className="dialog-actions">
        <DialogClose className="btn ghost" type="button">
          Cancelar
        </DialogClose>
        <button
          className={isReject ? "btn danger" : "btn primary"}
          type="submit"
          disabled={pending || !pronto}
        >
          {pending
            ? "Enviando…"
            : isReject
              ? `Recusar ${lote.itens.length}`
              : `Aprovar ${lote.itens.length}`}
        </button>
      </div>
    </form>
  );
}
