"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Stamp } from "lucide-react";
import { useState } from "react";
import {
  MoldePresetForm,
  corpoDoPresetDraft,
  presetDraftVazio,
  type PresetDraft,
} from "@/components/molde-preset-form";
import { TipoFuncaoSelect } from "@/components/tipo-funcao-select";
import { Dialog, DialogClose, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import {
  ApiError,
  type CreateRoleTemplateInput,
  createRoleTemplate,
  listEngines,
  type RoleTemplate,
  type TipoFuncao,
} from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";

// "Novo molde" (T.1-FE, ADR-0017): identidade (tipo de função + role específica) + preset v1.
// Este é o caminho DIRETO do dono (mão soberana, nasce APPROVED) — ele é o próprio aprovador. O
// propose-then-commit (R10-A, ADR-0017 emendada) é o caminho do CEO: ele PROPÕE um molde novo pelo
// gate de Approval e o dono aprova; o drift-badge (agente numa versão velha) mora no organograma.
// O catálogo é GLOBAL: a criação vale para todas as companies do dono.

export function MoldeNovoDialog({
  companyId,
  lenteNome,
  onCreated,
}: {
  companyId: string | undefined;
  lenteNome: string | undefined;
  onCreated: (template: RoleTemplate) => void;
}) {
  const [open, setOpen] = useState(false);
  const [roleEspecifica, setRoleEspecifica] = useState("");
  const [tipoFuncao, setTipoFuncao] = useState<TipoFuncao | null>(null);
  const [draft, setDraft] = useState<PresetDraft>(presetDraftVazio());
  const queryClient = useQueryClient();

  const enginesQuery = useQuery({
    queryKey: queryKeys.engines(companyId ?? ""),
    queryFn: () => listEngines(companyId as string),
    enabled: !!companyId && open,
    staleTime: 5 * 60_000,
  });

  function reset() {
    setRoleEspecifica("");
    setTipoFuncao(null);
    setDraft(presetDraftVazio());
  }

  const criacao = useMutation({
    mutationFn: (input: CreateRoleTemplateInput) => createRoleTemplate(companyId as string, input),
    onSuccess: (criado) => {
      // O catálogo é global mas o cache é por-company — invalida TODAS as lentes de uma vez
      // (o savelog do molde novo nasce no primeiro GET /versions ao selecioná-lo).
      queryClient.invalidateQueries({
        predicate: (query) => query.queryKey[2] === "role-templates",
      });
      pushToast(`Molde criado — ${criado.roleEspecifica} v1`);
      setOpen(false);
      reset();
      onCreated(criado);
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
          reset();
        }
      }}
    >
      <DialogTrigger className="btn primary" disabled={!companyId}>
        <Stamp size={13} strokeWidth={2} /> Novo molde
      </DialogTrigger>
      <DialogContent aria-describedby={undefined} className="molde-dialog">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!criacao.isPending && tipoFuncao) {
              criacao.mutate({
                tipoFuncao,
                roleEspecifica: roleEspecifica.trim(),
                ...corpoDoPresetDraft(draft),
              });
            }
          }}
        >
          <div className="dialog-body">
            <DialogTitle className="dialog-title">Novo molde</DialogTitle>
            <p className="dialog-text">
              Um preset <b>global e versionado</b> que carimba agentes no hire: motor + skillset +
              cadeia sugerida. Nasce como <span className="font-mono">v1 APPROVED</span> — quem
              contratar dele pina a versão.
            </p>
            <div className="flex flex-col gap-3">
              <input
                type="text"
                placeholder="role específica — ex.: revisor-de-contratos"
                value={roleEspecifica}
                onChange={(event) => setRoleEspecifica(event.target.value)}
                autoFocus
              />
              <div className="flex flex-col gap-1.5">
                <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-faint">
                  tipo de função (eixo fixo)
                </span>
                <TipoFuncaoSelect
                  value={tipoFuncao}
                  onSelect={setTipoFuncao}
                  ariaLabel="Tipo de função do molde"
                />
              </div>
              <MoldePresetForm
                companyId={companyId}
                lenteNome={lenteNome}
                engines={enginesQuery.data ?? []}
                enginesLoading={enginesQuery.isPending}
                draft={draft}
                onChange={setDraft}
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
              disabled={criacao.isPending || !roleEspecifica.trim() || !tipoFuncao}
            >
              {criacao.isPending ? "Criando…" : "Criar molde"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
