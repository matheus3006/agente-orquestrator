import { cn } from "@/lib/utils";

// O invólucro de TODA superfície do board — o `.view` do protótipo:
// max-width 1240, padding 28/36/80 e a entrada view-enter (240ms ease-out).

export function View({
  className,
  children,
}: {
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "mx-auto max-w-[1240px] px-9 pb-20 pt-7",
        "animate-[view-enter_240ms_var(--ease-out)]",
        className,
      )}
    >
      {children}
    </div>
  );
}

/** O cabeçalho de view do protótipo: título display + subtítulo mono. */
export function ViewHead({
  title,
  sub,
  children,
}: {
  title: string;
  sub?: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <header className="mb-[22px] flex items-end justify-between gap-5">
      <div>
        <h1 className="m-0 font-display text-[21px] font-semibold uppercase tracking-[0.06em]">
          {title}
        </h1>
        {sub !== undefined && (
          <p className="mb-0 mt-[3px] font-mono text-[12.5px] text-faint">{sub}</p>
        )}
      </div>
      {children}
    </header>
  );
}

// (P2 #17/#18: o SurfacePlaceholder das fatias F.3→F.6 morreu — todas as
// superfícies existem; zero usos no código. O vazio de verdade é o
// EmptyState de ui/empty-state.tsx.)
