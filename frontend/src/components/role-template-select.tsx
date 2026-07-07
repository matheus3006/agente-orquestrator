"use client";

import { ChevronDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { RoleTemplate } from "@/lib/api";
import { tipoFuncaoLabel } from "@/lib/role-templates";
import { cn } from "@/lib/utils";

// O seletor de MOLDE no hire (Teams T.1, ADR-0017) — clona o MotorSelect (a divergência justificada da
// P.1). Abre o catálogo GLOBAL de moldes; escolher um pina a versão corrente + o backend copia o snapshot
// (motor + skillset). O item "sem molde" limpa a escolha (hire avulso). Presentacional: o dono (hire
// dialog) mantém o estado e reage ao onSelect.

export function RoleTemplateSelect({
  templates,
  value,
  onSelect,
  loading = false,
  disabled = false,
}: {
  templates: RoleTemplate[];
  value: RoleTemplate | null;
  onSelect: (template: RoleTemplate | null) => void;
  loading?: boolean;
  disabled?: boolean;
}) {
  const chosen = value ? value.roleEspecifica : null;
  const triggerText = loading ? "carregando moldes…" : (chosen ?? "sem molde (avulso)");

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn("motor-trigger", !chosen && "is-placeholder")}
        disabled={disabled || loading}
        aria-label="Molde do agente"
      >
        <span className="motor-trigger-label">
          {chosen && <span className="led running" aria-hidden />}
          <span className="truncate">{triggerText}</span>
        </span>
        <ChevronDown size={13} strokeWidth={2} className="motor-trigger-chevron" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="motor-menu" align="start">
        <DropdownMenuItem
          className={cn("motor-item", !value && "on")}
          aria-current={!value ? "true" : undefined}
          onSelect={() => onSelect(null)}
        >
          <span className="motor-dot" aria-hidden />
          <span className="motor-item-label text-faint">sem molde (avulso)</span>
        </DropdownMenuItem>
        {templates.map((template) => {
          const on = value?.id === template.id;
          return (
            <DropdownMenuItem
              key={template.id}
              className={cn("motor-item", on && "on")}
              aria-current={on ? "true" : undefined}
              onSelect={() => onSelect(template)}
            >
              <span className="motor-dot" aria-hidden />
              <span className="motor-item-label">{template.roleEspecifica}</span>
              <span className="motor-item-ctx">{tipoFuncaoLabel(template.tipoFuncao)}</span>
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
