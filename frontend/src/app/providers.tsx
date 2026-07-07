"use client";

import { QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/components/ui/tooltip";
import { getQueryClient } from "@/lib/get-query-client";

export function Providers({ children }: { children: React.ReactNode }) {
  const queryClient = getQueryClient();
  // Um ÚNICO TooltipProvider para todo o board (#11a): delay de 300ms — rápido o
  // bastante para "o que é este termo", sem os 700ms default do Radix.
  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider delayDuration={300}>{children}</TooltipProvider>
    </QueryClientProvider>
  );
}
