"use client";

import { cn } from "@/lib/utils";

// O estado vazio ÚNICO do board (P2 #18) — ícone + frase + ação sugerida.
// Substitui as variantes que divergiam por tela (.empty-state ad-hoc,
// .grupo-vazio, .servers-empty, o ícone solto da Review) pela MESMA peça em
// duas densidades da sala de máquinas:
//
// - cheio (default): a superfície inteira está vazia (lista/detalhe sem dados);
// - compact: vazio DENTRO de zona/grupo/seção (o antigo .grupo-vazio);
// - tone="erro": os "não deu para carregar" (tinta coral, sem <p> ad-hoc);
// - action: a ação sugerida (link/botão) abaixo da frase.
type EmptyStateProps = React.ComponentProps<"div"> & {
  icon?: React.ReactNode;
  compact?: boolean;
  tone?: "erro";
  action?: React.ReactNode;
};

export function EmptyState({
  icon,
  compact,
  tone,
  action,
  className,
  children,
  ...props
}: EmptyStateProps) {
  return (
    <div
      className={cn("empty-state", compact && "compact", tone === "erro" && "erro", className)}
      {...props}
    >
      {icon}
      <p className="m-0">{children}</p>
      {action != null && <div className="empty-state-action">{action}</div>}
    </div>
  );
}
