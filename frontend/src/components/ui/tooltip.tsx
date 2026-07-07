"use client";

import * as TooltipPrimitive from "@radix-ui/react-tooltip";
import { cn } from "@/lib/utils";

// Copy-in shadcn ENXUTO e retematizado, no mesmo padrão de dropdown-menu.tsx:
// adapter FINO sobre @radix-ui/react-tooltip. O Provider vive UMA única vez em
// app/providers.tsx (delayDuration global) — zero re-render por instância. No
// touch o Radix é no-op por design; o canônico reencontrável é o glossário.

export const TooltipProvider = TooltipPrimitive.Provider;
export const Tooltip = TooltipPrimitive.Root;
export const TooltipTrigger = TooltipPrimitive.Trigger;

export function TooltipContent({
  className,
  sideOffset = 6,
  collisionPadding = 12,
  ...props
}: React.ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      {/* .tooltip-panel (globals.css) carrega o z-[56] — ACIMA do dropdown (55) e do
          dialog-panel (51), pois "motor" mora num DropdownMenuItem do hire dialog;
          abaixo do toast (60). Prosa sans 12px text-dim sobre --overlay (gate C5). */}
      <TooltipPrimitive.Content
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
        className={cn("tooltip-panel", className)}
        {...props}
      />
    </TooltipPrimitive.Portal>
  );
}
