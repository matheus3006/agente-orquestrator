"use client";

import { useQuery } from "@tanstack/react-query";
import { Bot, Stamp } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { MoldeNovoDialog } from "@/components/molde-novo-dialog";
import { MoldeVersaoDialog } from "@/components/molde-versao-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { View, ViewHead } from "@/components/view";
import {
  ApiError,
  type Engine,
  listCompanies,
  listEngines,
  listRoleTemplates,
  listRoleTemplateVersions,
  type RoleTemplate,
} from "@/lib/api";
import { useLastCompanySlug } from "@/lib/last-company";
import { queryKeys } from "@/lib/query-keys";
import {
  isMoldeDeSistema,
  reportsToDefaultLabel,
  TIPOS_FUNCAO,
  tipoFuncaoLabel,
  versaoCorrente,
  versionMotorLabel,
} from "@/lib/role-templates";
import { shortId } from "@/lib/format";
import { cn } from "@/lib/utils";

// BIBLIOTECA DE MOLDES (T.1-FE, ADR-0017 §"Decisão-produto") — rota GLOBAL, fora do
// company-switcher: o catálogo vale para todas as companies do dono; os 4 moldes de SISTEMA
// (architect/executor/product-owner/leader) são 1ª classe — o diferencial #6 "time pronto de
// prateleira" visível. Master-detail: lista por tipo de função à esquerda; preset da versão
// corrente + savelog à direita. A "LENTE" é a última company visitada (as rotas do backend são
// company-scoped só p/ gatear; o install-state do KnowAll é relativo a ela).

export default function MoldesPage() {
  // useSearchParams exige Suspense no app router (a seleção é deep-linked via ?molde=).
  return (
    <Suspense fallback={null}>
      <BibliotecaDeMoldes />
    </Suspense>
  );
}

function BibliotecaDeMoldes() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const companiesQuery = useQuery({ queryKey: queryKeys.companies(), queryFn: listCompanies });
  // P2 #16: a lente via useLastCompanySlug (snapshot de servidor null) — a
  // MESMA resolução da sidebar, sem o if de window que arriscava hidratação.
  const lastSlug = useLastCompanySlug();
  const lente =
    companiesQuery.data?.find((company) => company.slug === lastSlug) ?? companiesQuery.data?.[0];

  const templatesQuery = useQuery({
    queryKey: queryKeys.roleTemplates(lente?.id ?? ""),
    queryFn: () => listRoleTemplates(lente?.id as string),
    enabled: !!lente,
    staleTime: 5 * 60_000,
  });
  const enginesQuery = useQuery({
    queryKey: queryKeys.engines(lente?.id ?? ""),
    queryFn: () => listEngines(lente?.id as string),
    enabled: !!lente,
    staleTime: 5 * 60_000,
  });

  const templates = templatesQuery.data ?? [];
  // Seleção: o deep-link ?molde= vence; sem (ou inválido), o primeiro do catálogo.
  const selecionadoId = searchParams.get("molde");
  const selecionado = templates.find((t) => t.id === selecionadoId) ?? templates[0] ?? null;

  function selecionar(template: RoleTemplate) {
    router.replace(`/moldes?molde=${template.id}`, { scroll: false });
  }

  const falhaCatalogo =
    templatesQuery.error instanceof ApiError && templatesQuery.error.status === 403
      ? "o catálogo de moldes é board-only — este usuário não é board member da lente"
      : templatesQuery.isError
        ? "não deu para carregar o catálogo — confira o backend em :8080"
        : null;

  // P2 #16: a página é uma VIEW do board como as outras (View/ViewHead) — o
  // chrome (sidebar, topbar, toasts) vem do layout de /moldes via BoardShell.
  // O header próprio com wordmark + "← board" morreu: a continuidade é a nav.
  return (
    <View>
      <ViewHead
        title="Biblioteca de moldes"
        sub={
          <>
            catálogo global · presets versionados que carimbam agentes
            {lente && (
              <>
                {" "}
                · lente <span className="text-phosphor-dim">{lente.name}</span>
              </>
            )}
          </>
        }
      />

      {falhaCatalogo && <EmptyState tone="erro">{falhaCatalogo}</EmptyState>}

      {!falhaCatalogo && (
        <div className="moldes-grid">
          <aside className="flex flex-col gap-1">
            <div className="mb-3">
              <MoldeNovoDialog companyId={lente?.id} lenteNome={lente?.name} onCreated={selecionar} />
            </div>

            {(templatesQuery.isPending || companiesQuery.isPending) && (
              <div className="flex flex-col gap-2" aria-busy="true">
                {Array.from({ length: 5 }).map((_, index) => (
                  <div key={index} className="h-9 animate-pulse rounded-md bg-raised" />
                ))}
              </div>
            )}

            {templatesQuery.data && templates.length === 0 && (
              <EmptyState compact>nenhum molde no catálogo — crie o primeiro</EmptyState>
            )}

            {TIPOS_FUNCAO.map((tipo) => {
              const doTipo = templates.filter((t) => t.tipoFuncao === tipo);
              if (doTipo.length === 0) {
                return null;
              }
              return (
                <div key={tipo} className="mb-2.5">
                  <div className="section-label">{tipoFuncaoLabel(tipo)}</div>
                  {doTipo.map((template) => {
                    const on = template.id === selecionado?.id;
                    return (
                      <button
                        key={template.id}
                        type="button"
                        className={cn("molde-item", on && "on")}
                        aria-current={on ? "true" : undefined}
                        onClick={() => selecionar(template)}
                      >
                        <span className="truncate">{template.roleEspecifica}</span>
                        {isMoldeDeSistema(template) && (
                          <span className="selo-sistema">
                            <Stamp size={9} strokeWidth={2.2} /> sistema
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
              );
            })}
          </aside>

          <section>
            {selecionado ? (
              <MoldeDetalhe
                template={selecionado}
                companyId={lente?.id}
                lenteNome={lente?.name}
                lenteSlug={lente?.slug}
                engines={enginesQuery.data ?? []}
              />
            ) : (
              templatesQuery.data && (
                <EmptyState>escolha um molde à esquerda para ver o preset</EmptyState>
              )
            )}
          </section>
        </div>
      )}
    </View>
  );
}

function MoldeDetalhe({
  template,
  companyId,
  lenteNome,
  lenteSlug,
  engines,
}: {
  template: RoleTemplate;
  companyId: string | undefined;
  lenteNome: string | undefined;
  lenteSlug: string | undefined;
  engines: Engine[];
}) {
  // O savelog REAL do molde (GET /versions, desc) — preset da corrente e histórico saem da mesma query.
  const savelogQuery = useQuery({
    queryKey: queryKeys.roleTemplateSavelog(companyId ?? "", template.id),
    queryFn: () => listRoleTemplateVersions(companyId as string, template.id),
    enabled: !!companyId,
    staleTime: 5 * 60_000,
  });
  const versoes = savelogQuery.data ?? [];
  const corrente = versaoCorrente(template, versoes);

  return (
    <div key={template.id} className="animate-[view-enter_200ms_var(--ease-out)]">
      <header className="mb-4 flex items-start gap-3.5">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <h2 className="m-0 truncate font-display text-[21px] font-semibold tracking-[0.02em]">
              {template.roleEspecifica}
            </h2>
            {isMoldeDeSistema(template) && (
              <span className="selo-sistema grande">
                <Stamp size={10} strokeWidth={2.2} /> molde de sistema
              </span>
            )}
          </div>
          <p className="m-0 mt-1 font-mono text-[11.5px] text-faint">
            {tipoFuncaoLabel(template.tipoFuncao)} · criado em {template.createdAt.slice(0, 10)} ·
            corrente {template.currentVersionId ? shortId(template.currentVersionId) : "—"}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <MoldeVersaoDialog
            companyId={companyId}
            lenteNome={lenteNome}
            template={template}
            corrente={corrente}
          />
          {lenteSlug && (
            <Link href={`/${lenteSlug}/agents`} className="btn ghost no-underline">
              <Bot size={13} strokeWidth={2} /> contratar no board
            </Link>
          )}
        </div>
      </header>

      {corrente ? (
        <div className="kv-panel">
          <div className="prop">
            <span className="prop-key">motor</span>
            <span className="prop-val">{versionMotorLabel(corrente, engines)}</span>
          </div>
          <div className="prop items-start">
            <span className="prop-key pt-1">skillset</span>
            {corrente.skillKeys.length > 0 ? (
              <span className="chips justify-end">
                {corrente.skillKeys.map((key) => (
                  <span key={key} className="skill-chip">
                    {key}
                  </span>
                ))}
              </span>
            ) : (
              <span className="prop-val mono">sem skillset — o hire copia nada</span>
            )}
          </div>
          <div className="prop">
            <span className="prop-key">reporta a</span>
            <span className="prop-val">{reportsToDefaultLabel(corrente.reportsToDefault)}</span>
          </div>
          <div className="prop">
            <span className="prop-key">versão corrente</span>
            <span className="prop-val mono">
              <span className="version-badge">v{corrente.version}</span>
              {corrente.status}
            </span>
          </div>
        </div>
      ) : savelogQuery.isPending ? (
        <div className="kv-panel" aria-busy="true">
          <div className="h-24 animate-pulse rounded-lg bg-raised" />
        </div>
      ) : (
        <div className="kv-panel border-dashed">
          <p className="m-0 text-[12.5px] text-dim">
            {savelogQuery.isError
              ? "não deu para carregar o savelog do molde — confira o backend em :8080"
              : "molde sem versão corrente no savelog"}
          </p>
        </div>
      )}

      <div className="section-label mt-6">Histórico de versões (savelog)</div>
      {savelogQuery.isPending && (
        <div className="flex flex-col gap-2" aria-busy="true">
          {Array.from({ length: 2 }).map((_, index) => (
            <div key={index} className="h-9 animate-pulse rounded-md bg-raised" />
          ))}
        </div>
      )}
      {versoes.map((versao) => {
        const ehCorrente = versao.id === template.currentVersionId;
        return (
          <div key={versao.id} className={cn("savelog-row", ehCorrente && "corrente")}>
            <span className="version-badge">v{versao.version}</span>
            <span className="min-w-0 flex-1 truncate text-[12.5px] text-dim">
              {versionMotorLabel(versao, engines)} · {versao.skillKeys.length} skill
              {versao.skillKeys.length === 1 ? "" : "s"} · reporta a{" "}
              {reportsToDefaultLabel(versao.reportsToDefault)}
            </span>
            {ehCorrente && <span className="agent-status-word running">CORRENTE</span>}
            <span className="savelog-when">{versao.createdAt.slice(0, 10)}</span>
          </div>
        );
      })}
      {!savelogQuery.isPending && !savelogQuery.isError && versoes.length === 0 && (
        <p className="m-0 text-[12.5px] text-faint">sem versões no savelog</p>
      )}
    </div>
  );
}
