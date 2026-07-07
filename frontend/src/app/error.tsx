"use client";

import { useRouter } from "next/navigation";
import { startTransition, useEffect } from "react";

// Error boundary da RAIZ (App Router): captura QUALQUER erro lançado pelos
// FILHOS do root layout — em especial o `fetchQuery` server-side do
// [companySlug]/layout.tsx (o shell) quando o backend Spring está fora do ar
// num reload A FRIO. Um error.tsx nunca captura o layout do PRÓPRIO segmento,
// então o boundary do shell precisa morar aqui, um nível acima. Sem ele, o
// Next cai no overlay cru (dev) / 500 sem tema (prod). Aqui o shell não pode
// subir — a lista de companies é justamente o que falhou — então o estado é de
// "sala de máquinas offline" em tela cheia, na mesma linguagem do picker da
// raiz (page.tsx) e dos not-founds.
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
    <main className="grid min-h-screen place-items-center p-8">
      <div className="w-full max-w-md">
        <header className="mb-6 flex items-center gap-3">
          <span className="grid h-8 w-8 place-items-center rounded-lg border-[1.5px] border-coral text-coral shadow-[0_0_14px_var(--coral-bg)]">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" className="h-3.5 w-3.5">
              <circle cx="12" cy="12" r="7.5" />
              <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
            </svg>
          </span>
          <h1 className="font-display text-sm font-semibold tracking-[0.14em]">
            ORQUESTRA<span className="text-coral">DOR</span>
          </h1>
        </header>

        <div className="rounded-lg border border-dashed border-line-hot bg-raised p-10 text-center">
          <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-coral">
            erro · sala de máquinas offline
          </p>
          <h2 className="mt-3 font-display text-xl font-semibold tracking-wide">
            Não deu para falar com o backend
          </h2>
          <p className="mt-2 text-[13px] text-dim">
            O board não conseguiu carregar as companies. Suba o Spring em{" "}
            <code className="font-mono text-[12px] text-phosphor-dim">:8080</code> e tente de novo.
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
    </main>
  );
}
