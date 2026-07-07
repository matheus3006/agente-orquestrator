"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot } from "lucide-react";
import { useState } from "react";
import { MotorSelect } from "@/components/motor-select";
import { RoleTemplateSelect } from "@/components/role-template-select";
import { Termo } from "@/components/termo";
import { Dialog, DialogClose, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { ApiError, type Engine, hireAgent, listEngines, listRoleTemplates, type RoleTemplate } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";

// "Contratar agente" (F.6 + F.7 + Teams T.1): MOLDE + nome + role + MOTOR. Escolher um molde (ADR-0017)
// pina a versão e o backend COPIA o snapshot (motor + skillset). O Motor vira OVERRIDE opcional — se o
// operador escolher um, VENCE o preset e não propaga ao molde. Sem molde e sem motor → `process` demo
// (o default que deixa o agente aparecer vivo no roster). v1 é LOCAL e NÃO isola (aviso explícito).

export function HireAgentDialog({ companyId }: { companyId: string | undefined }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [role, setRole] = useState("");
  const [template, setTemplate] = useState<RoleTemplate | null>(null);
  const [motor, setMotor] = useState<Engine | null>(null);
  const queryClient = useQueryClient();

  const enginesQuery = useQuery({
    queryKey: queryKeys.engines(companyId ?? ""),
    queryFn: () => listEngines(companyId as string),
    enabled: !!companyId && open,
    staleTime: 5 * 60_000,
  });
  const engines = enginesQuery.data ?? [];

  const templatesQuery = useQuery({
    queryKey: queryKeys.roleTemplates(companyId ?? ""),
    queryFn: () => listRoleTemplates(companyId as string),
    enabled: !!companyId && open,
    staleTime: 5 * 60_000,
  });
  const templates = templatesQuery.data ?? [];

  function reset() {
    setName("");
    setRole("");
    setTemplate(null);
    setMotor(null);
  }

  // Escolher um molde adota a role dele quando o campo está vazio (o molde É a role); o operador edita depois.
  function chooseTemplate(chosen: RoleTemplate | null) {
    setTemplate(chosen);
    if (chosen && !role.trim()) {
      setRole(chosen.roleEspecifica);
    }
  }

  const hire = useMutation({
    mutationFn: () =>
      hireAgent(companyId as string, {
        name,
        role,
        roleTemplateVersionId: template?.currentVersionId ?? undefined,
        // Motor: override do operador vence; sem override, o preset do molde aplica; sem molde e sem
        // override, cai no `process` demo (mantém o comportamento atual do hire avulso).
        adapterType: motor ? motor.adapterType : template ? undefined : "process",
        model: motor?.model ?? undefined,
        effort: motor?.effort ?? undefined,
        executionTarget: "LOCAL",
      }),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      pushToast(`Agente contratado — ${created.name}`);
      setOpen(false);
      reset();
    },
  });

  const failure =
    hire.error instanceof ApiError
      ? hire.error.message
      : hire.isError
        ? "não deu para falar com o backend"
        : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        hire.reset();
        if (!next) {
          reset();
        }
      }}
    >
      <DialogTrigger className="btn primary" disabled={!companyId}>
        <Bot size={13} strokeWidth={2} /> Contratar agente
      </DialogTrigger>
      <DialogContent aria-describedby={undefined}>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!hire.isPending) {
              hire.mutate();
            }
          }}
        >
          <div className="dialog-body">
            <DialogTitle className="dialog-title">Contratar agente</DialogTitle>
            <p className="dialog-text">
              Entra no time desta company. Um <Termo id="molde"><b>molde</b></Termo> copia um preset pronto
              (motor + skills); o <Termo id="motor"><b>motor</b></Termo> é o cérebro do agente (plano + modelo +
              esforço) e sobrescreve o do molde. Roda{" "}
              <span className="font-mono">LOCAL</span> — <span className="text-amber">sem isolamento</span> no v1.
            </p>
            <div className="flex flex-col gap-3">
              <input
                type="text"
                placeholder="nome — como o agente aparece no board"
                value={name}
                onChange={(event) => setName(event.target.value)}
                autoFocus
              />
              <div className="flex flex-col gap-1.5">
                <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-faint">molde</span>
                <RoleTemplateSelect
                  templates={templates}
                  value={template}
                  onSelect={chooseTemplate}
                  loading={templatesQuery.isPending}
                />
                {template && (
                  <p className="m-0 text-[11.5px] text-faint">
                    Copia o preset de <span className="font-mono">{template.roleEspecifica}</span> — motor e
                    skillset. O motor abaixo, se escolhido, sobrescreve.
                  </p>
                )}
              </div>
              <input
                type="text"
                placeholder="role — ex.: architect, executor, reviewer"
                value={role}
                onChange={(event) => setRole(event.target.value)}
              />
              <div className="flex flex-col gap-1.5">
                <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-faint">
                  motor {template && <span className="text-faint normal-case tracking-normal">(override do molde)</span>}
                </span>
                <MotorSelect
                  engines={engines}
                  value={motor ? { adapterType: motor.adapterType, model: motor.model, effort: motor.effort } : null}
                  onSelect={setMotor}
                  loading={enginesQuery.isPending}
                />
              </div>
              {failure && <p className="m-0 text-[12.5px] text-coral">{failure}</p>}
            </div>
          </div>
          <div className="dialog-actions">
            <DialogClose className="btn ghost" type="button">
              Cancelar
            </DialogClose>
            <button className="btn primary" type="submit" disabled={hire.isPending || !name.trim() || !role.trim()}>
              {hire.isPending ? "Contratando…" : "Contratar"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
