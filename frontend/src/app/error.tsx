"use client";

import { useRouter } from "next/navigation";
import { startTransition, useEffect } from "react";
import { BoardOffline } from "@/components/board-offline";

// Error boundary da RAIZ (App Router): rede de segurança para qualquer erro
// INESPERADO lançado pelos filhos do root layout (ex.: um bug de render no
// BoardShell). O `fetchQuery` do [companySlug]/layout.tsx NÃO cai mais aqui —
// ele captura a própria falha de rede internamente (ver o try/catch no
// layout) e renderiza o mesmo <BoardOffline/> sem depender de hidratação, o
// que também evita o 500 que um erro não-tratado geraria para QUALQUER
// caminho de um segmento quando o backend está fora do ar.
export default function BoardBootError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Mensagem técnica (connection refused), sem PII (ADR-0009).
    console.error("board boot falhou:", error);
  }, [error]);

  // reset() sozinho re-renderiza o boundary mas NÃO reconsulta o backend: o
  // shell é um server component, então o refresh reexecuta o fetch. Juntos,
  // dentro de startTransition, retomam o board sem travar a UI.
  const router = useRouter();
  const retry = () =>
    startTransition(() => {
      router.refresh();
      reset();
    });

  return (
    <BoardOffline
      action={
        <button
          type="button"
          onClick={retry}
          className="mt-6 inline-flex items-center gap-2 rounded-md border border-line-hot bg-overlay px-4 py-2 text-[13px] font-medium transition-colors hover:border-phosphor-dim hover:text-phosphor active:scale-[0.98]"
        >
          ↻ Recarregar
        </button>
      }
    />
  );
}
