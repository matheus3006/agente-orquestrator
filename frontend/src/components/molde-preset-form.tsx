"use client";

import { KnowallCompositor } from "@/components/knowall-compositor";
import { MotorSelect } from "@/components/motor-select";
import { Termo } from "@/components/termo";
import { TipoFuncaoSelect } from "@/components/tipo-funcao-select";
import type { Engine, RoleTemplateVersion, TipoFuncao } from "@/lib/api";
import type { Motor } from "@/lib/engines";
import { isTipoFuncao, motorDaVersao } from "@/lib/role-templates";

// O RASCUNHO de preset que os dois dialogs da biblioteca editam (criar molde / nova versão): motor
// como trio (null = sem motor → hire cai no default do plano), reports_to sugerido (v1 = dropdown do
// enum; texto livre fica pro futuro — residual 4 do handoff) e o skillset composto via KnowAll.

export interface PresetDraft {
  motor: Motor | null;
  reportsTo: TipoFuncao | null;
  skillKeys: string[];
  changeDescription: string;
}

export function presetDraftVazio(): PresetDraft {
  return { motor: null, reportsTo: null, skillKeys: [], changeDescription: "" };
}

/** Pré-preenche o rascunho de "nova versão" com a versão corrente do savelog. */
export function presetDraftDaVersao(versao: RoleTemplateVersion): PresetDraft {
  return {
    motor: motorDaVersao(versao),
    // reports_to em texto livre não é representável no dropdown v1 — degrada para "ninguém".
    reportsTo:
      versao.reportsToDefault && isTipoFuncao(versao.reportsToDefault)
        ? versao.reportsToDefault
        : null,
    skillKeys: [...versao.skillKeys],
    changeDescription: "",
  };
}

/** O rascunho como campos do corpo dos POSTs de molde (create/versions compartilham o shape). */
export function corpoDoPresetDraft(draft: PresetDraft): {
  adapterType?: string;
  model?: string;
  effort?: string;
  skillKeys: string[];
  reportsToDefault?: string;
  changeDescription?: string;
} {
  return {
    adapterType: draft.motor?.adapterType,
    model: draft.motor?.model ?? undefined,
    effort: draft.motor?.effort ?? undefined,
    skillKeys: draft.skillKeys,
    reportsToDefault: draft.reportsTo ?? undefined,
    changeDescription: draft.changeDescription.trim() || undefined,
  };
}

function Campo({ rotulo, children }: { rotulo: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-faint">{rotulo}</span>
      {children}
    </div>
  );
}

export function MoldePresetForm({
  companyId,
  lenteNome,
  engines,
  enginesLoading,
  draft,
  onChange,
  disabled = false,
}: {
  companyId: string | undefined;
  lenteNome: string | undefined;
  engines: Engine[];
  enginesLoading: boolean;
  draft: PresetDraft;
  onChange: (draft: PresetDraft) => void;
  disabled?: boolean;
}) {
  return (
    <>
      <Campo rotulo="motor do preset">
        <MotorSelect
          engines={engines}
          value={draft.motor}
          loading={enginesLoading}
          disabled={disabled}
          onSelect={(engine) =>
            onChange({
              ...draft,
              motor: { adapterType: engine.adapterType, model: engine.model, effort: engine.effort },
            })
          }
          onClear={() => onChange({ ...draft, motor: null })}
        />
      </Campo>
      <Campo rotulo="reporta a (sugestão de cadeia)">
        <TipoFuncaoSelect
          value={draft.reportsTo}
          onSelect={(tipo) => onChange({ ...draft, reportsTo: tipo })}
          ariaLabel="Tipo de função a quem o agente deste molde reporta"
          allowNone
          disabled={disabled}
        />
      </Campo>
      <Campo rotulo={<>skillset por conhecimento (<Termo id="knowall">KnowAll</Termo>)</>}>
        <KnowallCompositor
          companyId={companyId}
          lenteNome={lenteNome}
          skillKeys={draft.skillKeys}
          onChange={(skillKeys) => onChange({ ...draft, skillKeys })}
          disabled={disabled}
        />
      </Campo>
      <Campo rotulo="descrição da mudança (savelog)">
        <input
          type="text"
          placeholder="por que esta versão existe — vai pro histórico"
          value={draft.changeDescription}
          disabled={disabled}
          onChange={(event) => onChange({ ...draft, changeDescription: event.target.value })}
        />
      </Campo>
    </>
  );
}
