"use client";

import * as DropdownMenuPrimitive from "@radix-ui/react-dropdown-menu";
import { cn } from "@/lib/utils";

// Copy-in shadcn ENXUTO e retematizado (regra do handoff: zero shadcn default
// sem retematizar) — só as partes que o board usa; itens novos entram por
// demanda nas fatias seguintes.

export const DropdownMenu = DropdownMenuPrimitive.Root;
export const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;

export function DropdownMenuContent({
  className,
  sideOffset = 6,
  collisionPadding = 12,
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Content>) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        className={cn(
          // z-[55] fica ACIMA do dialog-panel (z-51) — sem isso o menu era ocultado
          // pelo painel; abaixo do toast (z-60). max-h à altura disponível + scroll
          // evita a lista transbordar pra fora do dialog e cobrir o roster.
          "z-[55] max-h-[var(--radix-dropdown-menu-content-available-height)] overflow-y-auto rounded-lg border border-line-hot bg-overlay p-1",
          "shadow-[0_16px_44px_oklch(0_0_0_/_0.45)]",
          "animate-[dialog-in_200ms_var(--ease-out)]",
          className,
        )}
        {...props}
      />
    </DropdownMenuPrimitive.Portal>
  );
}

export function DropdownMenuItem({
  className,
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Item>) {
  return (
    <DropdownMenuPrimitive.Item
      className={cn(
        "flex cursor-pointer select-none items-center gap-2.5 rounded-md px-2.5 py-2 text-[13px] outline-none",
        "transition-colors duration-100 data-[highlighted]:bg-raised data-[highlighted]:text-foreground",
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuLabel({
  className,
  ...props
}: React.ComponentProps<typeof DropdownMenuPrimitive.Label>) {
  return (
    <DropdownMenuPrimitive.Label
      className={cn(
        "px-2.5 pb-1 pt-1.5 font-mono text-[10px] uppercase tracking-[0.16em] text-faint",
        className,
      )}
      {...props}
    />
  );
}
