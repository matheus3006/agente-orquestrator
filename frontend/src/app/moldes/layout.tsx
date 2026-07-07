import { HydrationBoundary, dehydrate } from "@tanstack/react-query";
import { BoardShell } from "@/components/board-shell";
import { ToastZone } from "@/components/toast-zone";
import { listCompanies } from "@/lib/api";
import { getQueryClient } from "@/lib/get-query-client";
import { queryKeys } from "@/lib/query-keys";

// O catálogo GLOBAL dentro do MESMO shell do board (P2 #16): sair da company
// não deve parecer sair do app — a sidebar segue presente, operando pela
// LENTE (última company visitada). Espelho do layout de company SEM o
// notFound de slug: /moldes vale para todas as companies do dono. O prefetch
// usa prefetchQuery (engole erro): backend fora não derruba o catálogo — a
// página mostra a própria falha e a sidebar refaz a busca no client.
export default async function MoldesLayout({ children }: { children: React.ReactNode }) {
  const queryClient = getQueryClient();
  await queryClient.prefetchQuery({
    queryKey: queryKeys.companies(),
    queryFn: listCompanies,
  });

  return (
    <HydrationBoundary state={dehydrate(queryClient)}>
      <BoardShell>{children}</BoardShell>
      <ToastZone />
    </HydrationBoundary>
  );
}
