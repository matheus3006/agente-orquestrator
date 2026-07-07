"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Zap } from "lucide-react";
import { useState } from "react";
import { Dialog, DialogClose, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { ApiError, createIssue } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";
import { cn } from "@/lib/utils";

// "Nova issue" (F.4): title + description + riskLevel 1-5 (o gatilho do
// review gate, ADR-0012). Visual composto 100% do vocabulário congelado
// (dialog + risk badges como seletor). 422/400 do servidor aparece no dialog.

const RISK_LEVELS = [1, 2, 3, 4, 5] as const;

export function NewIssueDialog({ companyId }: { companyId: string | undefined }) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [riskLevel, setRiskLevel] = useState<number>(1);
  const queryClient = useQueryClient();

  const creation = useMutation({
    mutationFn: () =>
      createIssue(companyId as string, {
        title,
        description: description.trim() ? description : undefined,
        riskLevel,
      }),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      pushToast(`Issue criada — ${created.title}`);
      setOpen(false);
      setTitle("");
      setDescription("");
      setRiskLevel(1);
    },
  });

  const failure =
    creation.error instanceof ApiError
      ? creation.error.message
      : creation.isError
        ? "não deu para falar com o backend"
        : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        creation.reset();
        if (!next) {
          // fechar descarta o rascunho — reabrir começa limpo (achado da revisão)
          setTitle("");
          setDescription("");
          setRiskLevel(1);
        }
      }}
    >
      <DialogTrigger className="btn primary" disabled={!companyId}>
        <Zap size={13} strokeWidth={2} /> Nova issue
      </DialogTrigger>
      <DialogContent aria-describedby={undefined}>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!creation.isPending) {
              creation.mutate();
            }
          }}
        >
          <div className="dialog-body">
            <DialogTitle className="dialog-title">Nova issue</DialogTitle>
            <p className="dialog-text">
              O risco (R1–R5) decide o review gate: no limiar, o resultado estaciona para
              decisão do board em vez de concluir direto.
            </p>
            <div className="flex flex-col gap-3">
              <input
                type="text"
                placeholder="título — o que precisa ser feito"
                value={title}
                onChange={(event) => setTitle(event.target.value)}
                autoFocus
              />
              <textarea
                placeholder="descrição (opcional) — contexto que o agente vai ler"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
              <div className="flex items-center gap-2">
                <span className="prop-key">risco</span>
                {/* radiogroup real: roving tabindex + setas (achado da revisão) */}
                <div
                  className="flex gap-1.5"
                  role="radiogroup"
                  aria-label="Nível de risco"
                  onKeyDown={(event) => {
                    const step =
                      event.key === "ArrowRight" || event.key === "ArrowDown"
                        ? 1
                        : event.key === "ArrowLeft" || event.key === "ArrowUp"
                          ? -1
                          : 0;
                    if (step === 0) {
                      return;
                    }
                    event.preventDefault();
                    const next = Math.min(5, Math.max(1, riskLevel + step));
                    setRiskLevel(next);
                    (event.currentTarget.children[next - 1] as HTMLElement | undefined)?.focus();
                  }}
                >
                  {RISK_LEVELS.map((level) => (
                    <button
                      key={level}
                      type="button"
                      role="radio"
                      aria-checked={riskLevel === level}
                      tabIndex={riskLevel === level ? 0 : -1}
                      onClick={() => setRiskLevel(level)}
                      className={cn(
                        "risk-badge cursor-pointer transition-transform duration-150 ease-out-strong",
                        `risk-${level}`,
                        riskLevel === level
                          ? "scale-110 outline outline-1 outline-line-hot"
                          : "opacity-45 hover:opacity-90",
                      )}
                    >
                      R{level}
                    </button>
                  ))}
                </div>
              </div>
              {failure && <p className="m-0 text-[12.5px] text-coral">{failure}</p>}
            </div>
          </div>
          <div className="dialog-actions">
            <DialogClose className="btn ghost" type="button">
              Cancelar
            </DialogClose>
            <button className="btn primary" type="submit" disabled={creation.isPending}>
              {creation.isPending ? "Criando…" : "Criar issue"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
