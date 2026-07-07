import Link from "next/link";

// 404 da RAIZ: recebe o notFound() do layout de company (slug inválido — no
// App Router, notFound() lançado num LAYOUT resolve no not-found do segmento
// PAI) e qualquer rota global inexistente. Nunca renderiza company errada.
export default function RootNotFound() {
  return (
    <main className="grid min-h-screen place-items-center p-8">
      <div className="max-w-md rounded-lg border border-dashed border-line-hot bg-raised p-10 text-center">
        <p className="font-mono text-[11px] uppercase tracking-[0.16em] text-coral">
          404 · company desconhecida
        </p>
        <h1 className="mt-3 font-display text-xl font-semibold tracking-wide">
          Esse endereço não existe neste board
        </h1>
        <p className="mt-2 text-[13px] text-dim">
          O slug não corresponde a nenhuma company. Volte e escolha uma existente.
        </p>
        <Link
          href="/"
          className="mt-6 inline-block rounded-md border border-line-hot bg-overlay px-4 py-2 text-[13px] font-medium transition-colors hover:border-phosphor-dim hover:text-phosphor"
        >
          ← Escolher company
        </Link>
      </div>
    </main>
  );
}
