"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Users } from "lucide-react";
import { useState } from "react";
import { Dialog, DialogClose, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { ApiError, createTeam, type Team } from "@/lib/api";
import { glossa } from "@/lib/glossario";
import { queryKeys } from "@/lib/query-keys";
import { teamHue } from "@/lib/team-canvas";
import { pushToast } from "@/lib/toaster";
import { cn } from "@/lib/utils";

// A régua de TIMES da visão organograma (T.2, ADR-0017): um chip por time com o LED do GATE —
// verde = arquiteto designado (dispatch livre); âmbar = SEM arquiteto (o backend trava wakeup com
// 409; o chip só espelha). Criar time nasce sem arquiteto de propósito — montar aos poucos.

export function TeamToolbar({
  companyId,
  teams,
  carregando,
}: {
  companyId: string | undefined;
  teams: Team[];
  carregando: boolean;
}) {
  return (
    <div className="team-rail">
      {carregando && <span className="font-mono text-[10.5px] text-faint">carregando times…</span>}
      {!carregando && teams.length === 0 && (
        <span className="font-mono text-[10.5px] text-faint">
          nenhum time ainda — o dispatch segue livre para agentes sem time
        </span>
      )}
      {teams.map((team) => {
        const travado = !team.arquitetoAgentId;
        return (
          <span
            key={team.id}
            className={cn("team-chip", travado && "travado")}
            style={{ borderLeftColor: teamHue(team.id) }}
            title={glossa("dispatch")}
          >
            <span className={cn("led", travado ? "queued" : "running")} aria-hidden />
            {team.dominio} / {team.foco}
            {travado && <span className="team-chip-gate">sem arquiteto</span>}
          </span>
        );
      })}
      <NovoTimeDialog companyId={companyId} />
    </div>
  );
}

function NovoTimeDialog({ companyId }: { companyId: string | undefined }) {
  const [open, setOpen] = useState(false);
  const [dominio, setDominio] = useState("");
  const [foco, setFoco] = useState("");
  const queryClient = useQueryClient();

  const criacao = useMutation({
    mutationFn: () => createTeam(companyId as string, dominio.trim(), foco.trim()),
    onSuccess: (criado) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.teams(companyId as string) });
      pushToast(`Time criado — ${criado.dominio}/${criado.foco} (sem arquiteto: dispatch travado)`);
      setOpen(false);
      setDominio("");
      setFoco("");
    },
  });

  const falha =
    criacao.error instanceof ApiError
      ? criacao.error.message
      : criacao.isError
        ? "não deu para falar com o backend"
        : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        criacao.reset();
        if (!next) {
          setDominio("");
          setFoco("");
        }
      }}
    >
      <DialogTrigger className="btn" disabled={!companyId}>
        <Users size={13} strokeWidth={2} /> Novo time
      </DialogTrigger>
      <DialogContent aria-describedby={undefined}>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!criacao.isPending) {
              criacao.mutate();
            }
          }}
        >
          <div className="dialog-body">
            <DialogTitle className="dialog-title">Novo time</DialogTitle>
            <p className="dialog-text">
              Nasce <b>sem arquiteto</b> (monte aos poucos) — mas um time só <b>dispara trabalho</b>{" "}
              depois de designar o arquiteto (trava no dispatch, ADR-0017).
            </p>
            <div className="flex flex-col gap-3">
              <input
                type="text"
                placeholder="domínio — macro, ex.: Dev, Marketing"
                value={dominio}
                onChange={(event) => setDominio(event.target.value)}
                autoFocus
              />
              <input
                type="text"
                placeholder="foco — livre, ex.: frontend, SEO"
                value={foco}
                onChange={(event) => setFoco(event.target.value)}
              />
              {falha && <p className="m-0 text-[12.5px] text-coral">{falha}</p>}
            </div>
          </div>
          <div className="dialog-actions">
            <DialogClose className="btn ghost" type="button">
              Cancelar
            </DialogClose>
            <button
              className="btn primary"
              type="submit"
              disabled={criacao.isPending || !dominio.trim() || !foco.trim()}
            >
              {criacao.isPending ? "Criando…" : "Criar time"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
