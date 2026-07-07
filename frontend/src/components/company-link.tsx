"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback } from "react";
import { useLastCompanySlug } from "@/lib/last-company";

// O ÚNICO caminho de navegação do board (ADR-0013, lição do Paperclip): um
// wrapper FINO estilo next-intl que auto-prefixa o companySlug ativo. Regra:
// páginas NUNCA importam next/link ou useRouter direto — sem o shim, um href
// absoluto escapa da company e cai em 404 escopado.
//
// O wrapper NÃO reimplementa prefetch: prefetch/replace/scroll etc. passam
// direto pro next/link via spread.

function useActiveCompanySlug(): string {
  const params = useParams<{ companySlug?: string }>();
  // Rota GLOBAL (P2 #16, /moldes): sem segmento de company o prefixo vem da
  // LENTE — última company visitada — e a sidebar segue navegável fora dela.
  const lastCompanySlug = useLastCompanySlug();
  return params.companySlug ?? lastCompanySlug ?? "";
}

function prefixHref(href: string, companySlug: string): string {
  if (!companySlug || !href.startsWith("/")) {
    return href;
  }
  return `/${companySlug}${href === "/" ? "" : href}`;
}

type CompanyLinkProps = React.ComponentProps<typeof Link> & { href: string };

export function CompanyLink({ href, ...rest }: CompanyLinkProps) {
  const companySlug = useActiveCompanySlug();
  return <Link href={prefixHref(href, companySlug)} {...rest} />;
}

/** Navegação imperativa prefixada (dialogs, pós-mutação, hover-intent). */
export function useCompanyNav() {
  const router = useRouter();
  const companySlug = useActiveCompanySlug();

  const push = useCallback(
    (href: string) => router.push(prefixHref(href, companySlug)),
    [router, companySlug],
  );
  const replace = useCallback(
    (href: string, options?: { scroll?: boolean }) =>
      router.replace(prefixHref(href, companySlug), options),
    [router, companySlug],
  );
  // Hover-intent (F.4): linhas de lista usam prefetch={false} + onMouseEnter
  // — pré-carrega só o que o operador mira, não a lista inteira.
  const prefetch = useCallback(
    (href: string) => router.prefetch(prefixHref(href, companySlug)),
    [router, companySlug],
  );

  return { push, replace, prefetch, companySlug };
}
