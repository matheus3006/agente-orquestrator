"use client";

import { ChevronDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Engine } from "@/lib/api";
import { engineLabel, fmtContextWindow, type Motor, motorMatchesEngine } from "@/lib/engines";
import { glossa } from "@/lib/glossario";
import { cn } from "@/lib/utils";

// O seletor de MOTOR (F.7, ADR-0014) — a única divergência justificada da spec
// congelada P.1, desenhada com as skills de design. Um campo que abre o catálogo
// curado de /engines; escolher um item preenche os 3 eixos por baixo. Presentacional:
// o dono (hire dialog / detail) mantém o estado e reage ao onSelect.

export function MotorSelect({
  engines,
  value,
  onSelect,
  onClear,
  clearLabel = "sem motor (default do plano)",
  loading = false,
  disabled = false,
}: {
  engines: Engine[];
  value: Motor | null;
  onSelect: (engine: Engine) => void;
  /** Presente → o menu ganha o item de limpar (molde sem motor); ausente → comportamento original. */
  onClear?: () => void;
  clearLabel?: string;
  loading?: boolean;
  disabled?: boolean;
}) {
  const chosen = value ? engineLabel(value, engines) : null;
  const placeholder = onClear ? clearLabel : "escolher motor";
  const triggerText = loading ? "carregando motores…" : (chosen ?? placeholder);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn("motor-trigger", !chosen && "is-placeholder")}
        disabled={disabled || loading || engines.length === 0}
        aria-label="Motor do agente"
        title={glossa("motor")}
      >
        <span className="motor-trigger-label">
          {chosen && <span className="led running" aria-hidden />}
          <span className="truncate">{triggerText}</span>
        </span>
        <ChevronDown size={13} strokeWidth={2} className="motor-trigger-chevron" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="motor-menu" align="start">
        {onClear && (
          <DropdownMenuItem
            className={cn("motor-item", !value && "on")}
            aria-current={!value ? "true" : undefined}
            onSelect={() => onClear()}
          >
            <span className="motor-dot" aria-hidden />
            <span className="motor-item-label text-faint">{clearLabel}</span>
          </DropdownMenuItem>
        )}
        {engines.map((engine) => {
          const on = value ? motorMatchesEngine(value, engine) : false;
          const ctx = fmtContextWindow(engine.contextWindow);
          return (
            <DropdownMenuItem
              key={`${engine.adapterType}|${engine.model ?? ""}|${engine.effort ?? ""}`}
              className={cn("motor-item", on && "on")}
              aria-current={on ? "true" : undefined}
              onSelect={() => onSelect(engine)}
            >
              <span className="motor-dot" aria-hidden />
              <span className="motor-item-label">{engine.label}</span>
              {ctx && <span className="motor-item-ctx">{ctx}</span>}
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
