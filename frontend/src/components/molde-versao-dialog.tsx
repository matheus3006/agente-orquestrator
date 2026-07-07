"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { History } from "lucide-react";
import { useState } from "react";
import {
  MoldePresetForm,
  corpoDoPresetDraft,
  presetDraftDaVersao,
  presetDraftVazio,
  type PresetDraft,
} from "@/components/molde-preset-form";
import { Dialog, DialogClose, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import {
  addRoleTemplateVersion,
  type AddRoleTemplateVersionInput,
  ApiError,
  listEngines,
  type RoleTemplate,
  type RoleTemplateVersion,
} from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";

// "Nova versão" (T.1-FE): editar um molde É anexar no savelog (append-only) — a corrente move,
// quem pinou versão antiga NÃO é afetado. Pré-preenche do preset da versão corrente (GET /versions);
// corrente ainda carregando → rascunho vazio.

export function MoldeVersaoDialog({
  companyId,
  lenteNome,
  template,
  corrente,
}: {
  companyId: string | undefined;
  lenteNome: string | undefined;
  template: RoleTemplate;
  corrente: RoleTemplateVersion | null;
}) {
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<PresetDraft>(presetDraftVazio());
  const queryClient = useQueryClient();

  const enginesQuery = useQuery({
    queryKey: queryKeys.engines(companyId ?? ""),
    queryFn: () => listEngines(companyId as string),
    enabled: !!companyId && open,
    staleTime: 5 * 60_000,
  });

  const anexo = useMutation({
    mutationFn: (input: AddRoleTemplateVersionInput) =>
      addRoleTemplateVersion(companyId as string, template.id, input),
    onSuccess: (versao) => {
      // currentVersionId moveu — refaz catálogo E savelogs em todas as lentes (mesmo prefixo de chave).
      queryClient.invalidateQueries({
        predicate: (query) => query.queryKey[2] === "role-templates",
      });
      pushToast(`Nova versão — ${template.roleEspecifica} v${versao.version}`);
      setOpen(false);
    },
  });

  const falha =
    anexo.error instanceof ApiError
      ? anexo.error.message
      : anexo.isError
        ? "não deu para falar com o backend"
        : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        anexo.reset();
        if (next) {
          // Abrir SEMEIA o rascunho do preset corrente (edição começa do que está valendo).
          setDraft(corrente ? presetDraftDaVersao(corrente) : presetDraftVazio());
        }
      }}
    >
      <DialogTrigger className="btn" disabled={!companyId}>
        <History size={13} strokeWidth={2} /> Nova versão
      </DialogTrigger>
      <DialogContent aria-describedby={undefined} className="molde-dialog">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!anexo.isPending) {
              anexo.mutate(corpoDoPresetDraft(draft));
            }
          }}
        >
          <div className="dialog-body">
            <DialogTitle className="dialog-title">
              Nova versão — {template.roleEspecifica}
            </DialogTitle>
            <p className="dialog-text">
              Anexa no <b>savelog</b> e vira a corrente; hires futuros pinam ela.{" "}
              <b>Quem já pinou não é afetado.</b>
            </p>
            <div className="flex flex-col gap-3">
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
            <button className="btn primary" type="submit" disabled={anexo.isPending}>
              {anexo.isPending ? "Anexando…" : "Anexar versão"}
            </button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
