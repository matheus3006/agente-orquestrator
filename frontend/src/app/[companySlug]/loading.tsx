// Skeleton do segmento (ADR-0013): além do feedback, o loading.tsx habilita
// o prefetch parcial de rota dinâmica no App Router. Renderiza DENTRO do
// <main> do shell (F.2), então tem o shape de uma view — sem <main> próprio.
export default function CompanyLoading() {
  return (
    <div className="mx-auto max-w-[1240px] px-9 pb-20 pt-7" aria-busy="true">
      <div className="h-6 w-56 animate-pulse rounded bg-overlay" />
      <div className="mt-2 h-3 w-40 animate-pulse rounded bg-raised" />
      <div className="mt-8 grid grid-cols-[repeat(auto-fill,minmax(340px,1fr))] gap-3.5">
        {Array.from({ length: 6 }).map((_, index) => (
          <div key={index} className="h-56 animate-pulse rounded-lg border border-line-soft bg-raised" />
        ))}
      </div>
    </div>
  );
}
