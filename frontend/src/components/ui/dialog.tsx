"use client";

import * as DialogPrimitive from "@radix-ui/react-dialog";
import { cn } from "@/lib/utils";

// Copy-in shadcn ENXUTO e retematizado com o vocabulário de dialog do
// protótipo (.dialog-scrim/.dialog-panel/.dialog-body/.dialog-actions).
// Radix dá focus-trap + ESC + aria de graça.

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;
export const DialogTitle = DialogPrimitive.Title;

export function DialogContent({
  className,
  children,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content>) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="dialog-scrim" />
      <DialogPrimitive.Content className={cn("dialog-panel", className)} {...props}>
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
