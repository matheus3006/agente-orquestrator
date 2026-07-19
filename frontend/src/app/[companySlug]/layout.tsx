import { HydrationBoundary, dehydrate } from "@tanstack/react-query";
import { notFound } from "next/navigation";
import { BoardOffline } from "@/components/board-offline";
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

  // `companySlug` casa ESTRUTURALMENTE com qualquer caminho de um segmento —
  // só o backend sabe se esse slug é uma company de verdade. Se o fetch em si
  // falhar (backend fora do ar: rejeição de rede, não um status HTTP), não dá
  // pra decidir notFound() (podia existir) nem faz sentido deixar a exceção
  // subir pro error boundary — isso viraria 500 para QUALQUER caminho de um
  // segmento, mesmo os que nunca existiriam se o backend respondesse. Em vez
  // disso, renderiza o mesmo estado "offline" do error.tsx da raiz, mas
  // INLINE (sem depender de hidratação pra aparecer, então até um curl cru
  // vê a tela real em vez de 500).
  let companies: Awaited<ReturnType<typeof listCompanies>>;
  try {
    companies = await queryClient.fetchQuery({
      queryKey: queryKeys.companies(),
      queryFn: listCompanies,
    });
  } catch {
    return (
      <BoardOffline
        action={
          <a
            href="."
            className="mt-6 inline-flex items-center gap-2 rounded-md border border-line-hot bg-overlay px-4 py-2 text-[13px] font-medium transition-colors hover:border-phosphor-dim hover:text-phosphor active:scale-[0.98]"
          >
            ↻ Recarregar
          </a>
        }
      />
    );
  }

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
