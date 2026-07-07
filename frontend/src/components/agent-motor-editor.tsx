"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { MotorSelect } from "@/components/motor-select";
import { ApiError, type Engine, updateAgentEngine } from "@/lib/api";
import type { Motor } from "@/lib/engines";
import { queryKeys } from "@/lib/query-keys";
import { pushToast } from "@/lib/toaster";

// Promover o motor de um agente no detail (F.7/Q6): o MESMO dropdown do hire, mas
// cada escolha PATCHA o trio e invalida o escopo. onError revalida (o cache pode
// estar errado) e avisa.

export function AgentMotorEditor({
  companyId,
  agentId,
  motor,
  engines,
  loading = false,
}: {
  companyId: string | undefined;
  agentId: string;
  motor: Motor;
  engines: Engine[];
  loading?: boolean;
}) {
  const queryClient = useQueryClient();

  const promote = useMutation({
    mutationFn: (engine: Engine) =>
      updateAgentEngine(companyId as string, agentId, {
        adapterType: engine.adapterType,
        model: engine.model ?? undefined,
        effort: engine.effort ?? undefined,
      }),
    onSuccess: (updated) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      pushToast(`Motor promovido — ${updated.name}`);
    },
    onError: (error) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.companyScope(companyId as string) });
      pushToast(error instanceof ApiError ? error.message : "não deu para promover o motor", "bad");
    },
  });

  return (
    <MotorSelect
      engines={engines}
      value={motor}
      onSelect={(engine) => promote.mutate(engine)}
      loading={loading}
      disabled={promote.isPending || !companyId}
    />
  );
}
