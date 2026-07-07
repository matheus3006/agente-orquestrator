"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { listCompanies, type Company } from "@/lib/api";
import { useLastCompanySlug } from "@/lib/last-company";
import { queryKeys } from "@/lib/query-keys";

/**
 * A company ativa do board: slug da rota + lista já hidratada pelo layout do
 * segmento (cache quente → sem refetch no mount). O companyId de TODA query
 * nova das fatias F.3+ nasce daqui.
 *
 * Rota GLOBAL (P2 #16, /moldes): sem segmento de company, a ativa é a LENTE —
 * última company visitada válida na lista, senão a primeira — a mesma regra
 * do catálogo de moldes.
 */
export function useActiveCompany(): {
  companySlug: string;
  company: Company | undefined;
  companies: Company[] | undefined;
} {
  const params = useParams<{ companySlug?: string }>();
  const lastCompanySlug = useLastCompanySlug();
  const { data: companies } = useQuery({
    queryKey: queryKeys.companies(),
    queryFn: listCompanies,
  });
  const company = params.companySlug
    ? companies?.find((candidate) => candidate.slug === params.companySlug)
    : (companies?.find((candidate) => candidate.slug === lastCompanySlug) ?? companies?.[0]);
  return {
    companySlug: params.companySlug ?? company?.slug ?? "",
    company,
    companies,
  };
}
