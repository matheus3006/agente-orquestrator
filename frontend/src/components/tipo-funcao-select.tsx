"use client";

import { ChevronDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { TipoFuncao } from "@/lib/api";
import { glossa } from "@/lib/glossario";
import { isTipoReservado, TIPOS_FUNCAO, tipoFuncaoLabel } from "@/lib/role-templates";
import { cn } from "@/lib/utils";

// Seletor do TIPO DE FUNÇÃO (o eixo FIXO dos moldes, ADR-0017) — mesma família visual do
// MotorSelect. Serve dois campos da biblioteca: o tipo do molde novo e o reports_to_default
// (`allowNone` — sugestão de "quem manda" pode ser ninguém = raiz da pirâmide). Os tipos
// RESERVADOS no v1 (revisor = board; suporte = KnowAll) aparecem marcados, não escondidos.

export function TipoFuncaoSelect({
  value,
  onSelect,
  ariaLabel,
  placeholder = "escolher tipo de função",
  allowNone = false,
  noneLabel = "ninguém (raiz da pirâmide)",
  disabled = false,
}: {
  value: TipoFuncao | null;
  onSelect: (tipo: TipoFuncao | null) => void;
  ariaLabel: string;
  placeholder?: string;
  allowNone?: boolean;
  noneLabel?: string;
  disabled?: boolean;
}) {
  const chosen = value ? tipoFuncaoLabel(value) : allowNone ? noneLabel : null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn("motor-trigger", !value && !allowNone && "is-placeholder")}
        disabled={disabled}
        aria-label={ariaLabel}
      >
        <span className="motor-trigger-label">
          {value && <span className="led running" aria-hidden />}
          <span className="truncate">{chosen ?? placeholder}</span>
        </span>
        <ChevronDown size={13} strokeWidth={2} className="motor-trigger-chevron" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="motor-menu" align="start">
        {allowNone && (
          <DropdownMenuItem
            className={cn("motor-item", !value && "on")}
            aria-current={!value ? "true" : undefined}
            onSelect={() => onSelect(null)}
          >
            <span className="motor-dot" aria-hidden />
            <span className="motor-item-label text-faint">{noneLabel}</span>
          </DropdownMenuItem>
        )}
        {TIPOS_FUNCAO.map((tipo) => {
          const on = value === tipo;
          return (
            <DropdownMenuItem
              key={tipo}
              className={cn("motor-item", on && "on")}
              aria-current={on ? "true" : undefined}
              onSelect={() => onSelect(tipo)}
              title={tipo === "PLANEJAMENTO_ARQUITETO" ? glossa("arquiteto") : undefined}
            >
              <span className="motor-dot" aria-hidden />
              <span className="motor-item-label">{tipoFuncaoLabel(tipo)}</span>
              {isTipoReservado(tipo) && <span className="motor-item-ctx">reservado no v1</span>}
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
