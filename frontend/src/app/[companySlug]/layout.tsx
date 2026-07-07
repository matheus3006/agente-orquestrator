import { HydrationBoundary, dehydrate } from "@tanstack/react-query";
import { notFound } from "next/navigation";
import { BoardShell } from "@/components/board-shell";
import { LembrarCompanyVisitada } from "@/components/lembrar-company-visitada";
import { ToastZone } from "@/components/toast-zone";
import { listCompanies } from "@/lib/api";
import { getQueryClient } from "@/lib/get-query-client";
import { queryKeys } from "@/lib/query-keys";

// O layout do tenant (ADR-0013, padrão Dub): carrega a company pelo slug no
// SERVIDOR e chama notFound() para slug inválido — nunca uma tela de company
// errada. A lista prefetched hidrata o cache do browser (switcher/picker
// nascem quentes via HydrationBoundary).
//
// F.2 + P0 #1: o layout também É o shell, agora RESPONSIVO. Delega o chrome ao
// BoardShell (client): a MESMA sidebar de 232px vira trilho só-ícone <1200px e
// drawer off-canvas <768px, e a altura passa a min-h-dvh (não mais h-screen fixo,
// que cortava no mobile). As páginas seguem rendendo só o conteúdo da view.
export default async function CompanyLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ companySlug: string }>;
}) {
  const { companySlug } = await params;
  const queryClient = getQueryClient();
  const companies = await queryClient.fetchQuery({
    queryKey: queryKeys.companies(),
    queryFn: listCompanies,
  });

  const company = companies.find((candidate) => candidate.slug === companySlug);
  if (!company) {
    notFound();
  }

  return (
    <HydrationBoundary state={dehydrate(queryClient)}>
      {/* P2 #16: a visita grava a lente — a sidebar de /moldes opera pela company de onde se veio. */}
      <LembrarCompanyVisitada slug={companySlug} />
      <BoardShell>{children}</BoardShell>
      <ToastZone />
    </HydrationBoundary>
  );
}
