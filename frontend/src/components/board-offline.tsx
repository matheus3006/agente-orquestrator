// Estado "sala de máquinas offline": o board não conseguiu falar com o Spring. Compartilhado
// entre o layout de company (falha ANTES de saber se o slug existe — nunca vira notFound()) e o
// error boundary da raiz (rede de segurança para qualquer outro erro inesperado do segmento).
export function BoardOffline({ action }: { action: React.ReactNode }) {
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
          {action}
        </div>
      </div>
    </main>
  );
}
