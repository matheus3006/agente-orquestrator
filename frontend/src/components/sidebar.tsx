"use client";

import { Bot, Check, ChevronsUpDown, Radar, ServerCog, ShieldCheck, Stamp, Target } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSyncExternalStore } from "react";
import { CompanyLink } from "@/components/company-link";
import { GlossarioDialog } from "@/components/glossario-dialog";
import { readApiLatency, subscribeApiLatency } from "@/lib/api-latency";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Company } from "@/lib/api";
import { glossa } from "@/lib/glossario";
import { writeLastCompanySlug } from "@/lib/last-company";
import { activeSurfaceId, companyInitials, type SurfaceId } from "@/lib/shell";
import { useActiveCompany } from "@/lib/use-active-company";
import { cn } from "@/lib/utils";

// A sidebar do board — portada 1:1 do protótipo aprovado (P.1). As 4 entradas
// de navegação usam CompanyLink (regra ADR-0013); os contadores da nav nascem
// omitidos e ligam na F.3+ quando os dados reais chegarem.
//
// P0 #1: os ganchos `board-*` são só ISSO — pontos de ancoragem pra CSS do
// `.board-shell` colapsar esta MESMA <nav> em trilho (só-ícone) ou drawer. A
// lógica de "o que some em cada largura" mora inteira na CSS (um seam), não
// aqui — o markup só rotula rótulo, meta e rodapé pra ela poder mirar.

const SURFACES: ReadonlyArray<{
  id: SurfaceId;
  label: string;
  href: string;
  Icon: typeof Radar;
}> = [
  { id: "mission-control", label: "Mission Control", href: "/", Icon: Radar },
  { id: "issues", label: "Issues", href: "/issues", Icon: Target },
  { id: "reviews", label: "Review", href: "/reviews", Icon: ShieldCheck },
  // ADR-0035: a fila do gate agregada por company — o lote vive aqui, não na thread.
  { id: "approvals", label: "Aprovações", href: "/approvals", Icon: Stamp },
  { id: "agents", label: "Agents", href: "/agents", Icon: Bot },
  // F.10 (ADR-0023 §7): a superfície de supervisão dos servidores de preview.
  { id: "servers", label: "Servidores", href: "/servers", Icon: ServerCog },
];

export function Sidebar() {
  const pathname = usePathname();
  const activeSurface = activeSurfaceId(pathname);
  const { company, companies } = useActiveCompany();

  return (
    <nav
      id="board-nav"
      aria-label="Navegação principal"
      className="board-sidebar flex flex-col gap-1 border-r border-line bg-background-deep px-3 pb-3.5 pt-[18px]"
    >
      <div className="board-brand flex select-none items-center gap-2.5 px-2.5 pb-4 pt-0.5">
        <span aria-hidden className="wordmark-glyph">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4">
            <circle cx="12" cy="12" r="7.5" />
            <circle cx="12" cy="12" r="1.4" fill="currentColor" stroke="none" />
          </svg>
        </span>
        <span className="board-brand-text font-display text-[13px] font-semibold tracking-[0.14em] text-foreground">
          ORQUESTRA<em className="not-italic text-phosphor">DOR</em>
        </span>
      </div>

      <CompanySwitcher company={company} companies={companies} />

      <div className="board-section-label px-2.5 pb-1.5 pt-2.5 font-display text-[10px] font-semibold uppercase tracking-[0.18em] text-faint">
        Operação
      </div>

      {SURFACES.map(({ id, label, href, Icon }) => {
        const isActive = id === activeSurface;
        return (
          <CompanyLink
            key={id}
            href={href}
            title={label}
            aria-current={isActive ? "page" : undefined}
            className={cn(
              "board-nav-item relative flex items-center gap-2.5 rounded-md border border-transparent px-2.5 py-[7px]",
              "text-[13.5px] font-[450] no-underline",
              "transition-colors duration-150 ease-out-strong",
              isActive
                ? "border-phosphor/20 bg-phosphor-bg text-phosphor"
                : "text-dim hover:bg-raised hover:text-foreground",
            )}
          >
            {isActive && (
              <span
                aria-hidden
                className="board-active-bar absolute -left-3 bottom-2 top-2 w-[2px] rounded-[2px] bg-phosphor shadow-[0_0_8px_var(--phosphor-glow)]"
              />
            )}
            <Icon size={16} strokeWidth={2} className="shrink-0" />
            <span className="board-nav-label">{label}</span>
          </CompanyLink>
        );
      })}

      <div className="board-section-label px-2.5 pb-1.5 pt-4 font-display text-[10px] font-semibold uppercase tracking-[0.18em] text-faint">
        Catálogo
      </div>

      {/* Rota GLOBAL (fora do company-switcher): Link cru é a 4ª exceção marcada — a biblioteca
          de moldes vale para todas as companies, então o CompanyLink não se aplica. Em /moldes
          a sidebar segue presente (P2 #16), então o item também carrega o estado ativo. */}
      <Link
        href="/moldes"
        title={glossa("molde")}
        aria-current={activeSurface === "moldes" ? "page" : undefined}
        className={cn(
          "board-nav-item relative flex items-center gap-2.5 rounded-md border border-transparent px-2.5 py-[7px]",
          "text-[13.5px] font-[450] no-underline",
          "transition-colors duration-150 ease-out-strong",
          activeSurface === "moldes"
            ? "border-phosphor/20 bg-phosphor-bg text-phosphor"
            : "text-dim hover:bg-raised hover:text-foreground",
        )}
      >
        {activeSurface === "moldes" && (
          <span
            aria-hidden
            className="board-active-bar absolute -left-3 bottom-2 top-2 w-[2px] rounded-[2px] bg-phosphor shadow-[0_0_8px_var(--phosphor-glow)]"
          />
        )}
        <Stamp size={16} strokeWidth={2} className="shrink-0" />
        <span className="board-nav-label">Moldes</span>
        <span className="board-moldes-tag ml-auto font-mono text-[9.5px] uppercase tracking-[0.1em] text-faint">
          global
        </span>
      </Link>

      {/* Glossário reencontrável (#11b): NÃO é rota — é um Dialog controlado. Por
          isso entra como <button> (dentro do próprio componente), sem active-bar
          nem aria-current; a superfície ativa segue sendo a rota real. */}
      <GlossarioDialog />

      <div className="board-sysfoot mt-auto flex flex-col gap-[7px] border-t border-line-soft px-2.5 pb-0.5 pt-3">
        <SysLine>
          <span className="led running h-1.5 w-1.5" />
          poll <strong className="font-medium text-phosphor-dim">2.0s</strong>
          <LatencySample />
        </SysLine>
        <SysLine className="opacity-70">
          pg16 · v11 · board <strong className="font-medium text-phosphor-dim">matheus</strong>
        </SysLine>
      </div>
    </nav>
  );
}

/** Latência REAL do último request do fetcher (F.3) — no lugar do fake do protótipo. */
function LatencySample() {
  const latencyMs = useSyncExternalStore(subscribeApiLatency, readApiLatency, () => null);
  if (latencyMs === null) {
    return null;
  }
  return (
    <>
      · <strong className="font-medium text-phosphor-dim">{latencyMs}ms</strong>
    </>
  );
}

function SysLine({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div
      className={cn(
        "flex items-center gap-2 whitespace-nowrap font-mono text-[10.5px] tracking-[0.02em] text-faint",
        className,
      )}
    >
      {children}
    </div>
  );
}

// O switcher troca ENTRE companies — navegação deliberadamente FORA do
// CompanyLink (que prefixa a company ATUAL): Link cru é a 3ª exceção marcada,
// ao lado da raiz e dos not-founds. Destino = raiz da company escolhida
// (IDs internos não são portáveis entre companies); o clique grava
// orq.lastCompanySlug — mesma regra do picker da raiz.
function CompanySwitcher({
  company,
  companies,
}: {
  company: Company | undefined;
  companies: Company[] | undefined;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label="Trocar company"
        className={cn(
          "board-switcher mb-3.5 flex w-full items-center gap-[9px] rounded-md border border-line bg-raised px-2.5 py-2",
          "text-left text-foreground transition-[border-color,transform] duration-150 ease-out-strong",
          "hover:border-line-hot active:scale-[0.98]",
        )}
      >
        <span className="company-avatar grid h-6 w-6 shrink-0 place-items-center rounded-md font-display text-[11px] font-bold">
          {company ? companyInitials(company.name) : "…"}
        </span>
        <span className="board-switcher-meta flex-1 truncate text-[13px] font-medium">
          {company?.name ?? "…"}
        </span>
        <ChevronsUpDown size={13} strokeWidth={2} className="board-switcher-meta shrink-0 text-faint" />
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="w-[206px]">
        <DropdownMenuLabel>Companies</DropdownMenuLabel>
        {(companies ?? []).map((candidate) => (
          <DropdownMenuItem key={candidate.id} asChild>
            <Link href={`/${candidate.slug}`} onClick={() => writeLastCompanySlug(candidate.slug)}>
              <span className="company-avatar grid h-5 w-5 shrink-0 place-items-center rounded-sm font-display text-[9px] font-bold">
                {companyInitials(candidate.name)}
              </span>
              <span className="flex-1 truncate">{candidate.name}</span>
              <span className="font-mono text-[10.5px] text-faint">/{candidate.slug}</span>
              {candidate.id === company?.id && (
                <Check size={13} strokeWidth={2.5} className="shrink-0 text-phosphor" />
              )}
            </Link>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
