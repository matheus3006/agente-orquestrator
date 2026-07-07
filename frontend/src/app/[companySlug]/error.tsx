"use client";

import { useRouter } from "next/navigation";
import { startTransition, useEffect } from "react";

// Error boundary do segmento de company: captura erros de RENDER das PÁGINAS
// internas (issue/agent detail etc.) — não do próprio layout, que sobe para o
// error.tsx da raiz. As views são client components que tratam o próprio
// isError coral; este boundary é a rede pós-inesperado. Renderiza DENTRO do
// <main> do shell (F.2), como o not-found e o loading do segmento, então a
// sidebar continua de pé — mesma linguagem coral dos ramos isError das views
// (regra ADR-0010: um só estado de erro em todo o board).
export default function CompanySurfaceError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("superfície da company falhou:", error);
  }, [error]);

  const router = useRouter();
  const retry = () =>
    startTransition(() => {
      router.refresh();
      reset();
    });

  return (
    <div className="grid min-h-full place-items-center p-8">
      <div className="max-w-md rounded-lg border border-dashed border-line-hot bg-raised p-10 text-center">
        <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-coral">
          erro · superfície interrompida
        </p>
        <h1 className="mt-3 font-display text-xl font-semibold tracking-wide">
          Essa tela não conseguiu carregar
        </h1>
        <p className="mt-2 text-[13px] text-dim">
          Algo falhou ao montar a view. Recarregue — se persistir, confira o backend em{" "}
          <code className="font-mono text-[12px] text-phosphor-dim">:8080</code>.
        </p>
        <button
          type="button"
          onClick={retry}
          className="mt-6 inline-flex items-center gap-2 rounded-md border border-line-hot bg-overlay px-4 py-2 text-[13px] font-medium transition-colors hover:border-phosphor-dim hover:text-phosphor active:scale-[0.98]"
        >
          ↻ Recarregar
        </button>
      </div>
    </div>
  );
}
