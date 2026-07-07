"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { listCompanies } from "@/lib/api";
import { readLastCompanySlug, writeLastCompanySlug } from "@/lib/last-company";
import { queryKeys } from "@/lib/query-keys";
import { companyInitials } from "@/lib/shell";

// A raiz do board (ADR-0013): redireciona para a última company visitada; sem
// memória (ou slug morto), vira o picker. (Link direto de next/link é
// permitido AQUI: ainda não existe company ativa a prefixar.)
export default function RootPage() {
  const router = useRouter();
  const { data: companies, isPending, isError, error } = useQuery({
    queryKey: queryKeys.companies(),
    queryFn: listCompanies,
  });

  const lastSlug = typeof window !== "undefined" ? readLastCompanySlug() : null;
  const lastCompany = companies?.find((company) => company.slug === lastSlug);

  useEffect(() => {
    if (lastCompany) {
      router.replace(`/${lastCompany.slug}`);
    }
  }, [lastCompany, router]);

  return (
    <main className="grid min-h-screen place-items-center p-8">
      <div className="w-full max-w-md">
        <header className="mb-6 flex items-center gap-3">
          <span className="grid h-8 w-8 place-items-center rounded-lg border-[1.5px] border-phosphor text-phosphor shadow-[0_0_14px_var(--phosphor-glow)]">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" className="h-3.5 w-3.5">
              <circle cx="12" cy="12" r="7.5" />
              <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
            </svg>
          </span>
          <h1 className="font-display text-sm font-semibold tracking-[0.14em]">
            ORQUESTRA<span className="text-phosphor">DOR</span>
          </h1>
        </header>

        <div className="rounded-lg border border-line bg-raised p-6">
          <h2 className="font-display text-[13px] font-semibold uppercase tracking-[0.1em] text-dim">
            Escolha a company
          </h2>

          {isPending && (
            <div className="mt-4 space-y-2" aria-busy="true">
              <div className="h-11 animate-pulse rounded-md bg-overlay" />
              <div className="h-11 animate-pulse rounded-md bg-overlay" />
            </div>
          )}

          {isError && (
            <p className="mt-4 text-[13px] text-coral">
              Não deu para falar com o backend ({error instanceof Error ? error.message : "erro"}).
              Suba o Spring em <code className="font-mono text-[12px]">:8080</code> e recarregue.
            </p>
          )}

          {companies && companies.length === 0 && (
            <p className="mt-4 text-[13px] text-dim">
              Nenhuma company ainda — crie a primeira via{" "}
              <code className="font-mono text-[12px] text-phosphor-dim">POST /api/companies</code>.
            </p>
          )}

          {companies && companies.length > 0 && (
            <ul className="mt-4 space-y-2">
              {companies.map((company) => (
                <li key={company.id}>
                  <Link
                    href={`/${company.slug}`}
                    onClick={() => writeLastCompanySlug(company.slug)}
                    className="flex items-center gap-3 rounded-md border border-line bg-overlay px-4 py-2.5 transition-all duration-150 hover:border-phosphor-dim hover:text-phosphor active:scale-[0.98]"
                  >
                    <span className="company-avatar grid h-6 w-6 place-items-center rounded-md font-display text-[10px] font-bold">
                      {companyInitials(company.name)}
                    </span>
                    <span className="text-[13.5px] font-medium">{company.name}</span>
                    <span className="ml-auto font-mono text-[11px] text-faint">/{company.slug}</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </main>
  );
}
