"use client";

import { useState } from "react";
import { AgentCard } from "@/components/agent-card";
import { CompanyLink } from "@/components/company-link";
import { Termo } from "@/components/termo";
import { EmptyState } from "@/components/ui/empty-state";
import type { IssueSummary, MissionControlAgent } from "@/lib/api";
import type { GrupoDeEstacoes } from "@/lib/team-groups";
import { contarVisiveis } from "@/lib/team-groups";
import { cn } from "@/lib/utils";

// A zona ESQUERDA da Central docada (T.3-FE, mockup §6): os cards vivos
// agrupados por TIME — somente-leitura por decisão de produto (a cadeia de
// comando é imposta pela UI: o board fala SÓ com o CEO, na coluna ao lado).
// Chips ligam/desligam grupos; cabeçalhos recolhem; o contador reage.

export function TimesObservados({
  grupos,
  issues,
  nowMs,
  carregando,
  onOpenTranscript,
  avisoOcioso,
  children,
}: {
  grupos: GrupoDeEstacoes[];
  issues: IssueSummary[];
  nowMs: number;
  carregando: boolean;
  onOpenTranscript: (station: MissionControlAgent) => void;
  /** Afordância do board parado (#12) — 1º filho do scroll, rola junto e some
   *  no ato ao virar ativo. Distinto do `children`, que é o RODAPÉ do scroll. */
  avisoOcioso?: React.ReactNode;
  /** Rodapé do scroll da zona (a Central docada põe o feed de atividade aqui). */
  children?: React.ReactNode;
}) {
  // Desligado por clique — grupo novo nasce LIGADO (só guardamos os off).
  const [desligados, setDesligados] = useState<Set<string>>(new Set());
  const [recolhidos, setRecolhidos] = useState<Set<string>>(new Set());

  const visiveis = grupos.filter((grupo) => !desligados.has(grupo.key));
  const total = contarVisiveis(visiveis);

  const alternarChip = (key: string) =>
    setDesligados((atual) => {
      const proximo = new Set(atual);
      if (proximo.has(key)) {
        proximo.delete(key);
      } else {
        proximo.add(key);
      }
      return proximo;
    });

  const alternarGrupo = (key: string) =>
    setRecolhidos((atual) => {
      const proximo = new Set(atual);
      if (proximo.has(key)) {
        proximo.delete(key);
      } else {
        proximo.add(key);
      }
      return proximo;
    });

  return (
    <>
      <div className="zona-head">
        <h1>Times</h1>
        <span className="zona-contador">
          {total.grupos} {total.grupos === 1 ? "grupo" : "grupos"} · {total.agentes}{" "}
          {total.agentes === 1 ? "agente" : "agentes"}
        </span>
      </div>

      <div className="filtro-times" role="group" aria-label="Filtro de times">
        {grupos.map((grupo) => {
          const ligado = !desligados.has(grupo.key);
          const vazio = grupo.estacoes.length === 0;
          return (
            <button
              key={grupo.key}
              type="button"
              className={cn("filter-tab", ligado && "on", vazio && "vazio")}
              aria-pressed={ligado}
              onClick={() => alternarChip(grupo.key)}
            >
              <span className="led" aria-hidden />
              {grupo.nome}
              {vazio && " · sem agentes"}
            </button>
          );
        })}
      </div>

      <div className="zona-times-scroll">
        {avisoOcioso}

        {carregando && (
          <div className="flex flex-col gap-2" aria-busy="true">
            {Array.from({ length: 3 }).map((_, index) => (
              <div
                key={index}
                className="h-32 animate-pulse rounded-lg border border-line-soft bg-raised"
              />
            ))}
          </div>
        )}

        {!carregando && grupos.length === 0 && (
          <EmptyState
            compact
            action={
              <CompanyLink
                href="/agents"
                className="font-mono text-[11px] uppercase tracking-[0.08em] text-phosphor-dim no-underline transition-colors duration-150 ease-out-strong hover:text-phosphor"
              >
                contratar na superfície Agents →
              </CompanyLink>
            }
          >
            nenhum agente no roster ainda
          </EmptyState>
        )}

        {visiveis.map((grupo) => {
          const aberto = !recolhidos.has(grupo.key);
          return (
            <section key={grupo.key} className="grupo-time" data-aberto={aberto}>
              <button
                type="button"
                className="grupo-head"
                aria-expanded={aberto}
                onClick={() => alternarGrupo(grupo.key)}
              >
                <span className="grupo-caret" aria-hidden>
                  ▾
                </span>
                {grupo.hue && <span className="grupo-hue" style={{ background: grupo.hue }} />}
                <span className="grupo-nome">{grupo.nome}</span>
                <span className="grupo-lead">
                  {grupo.kind === "lideranca" && (
                    <>
                      · <b>topo da cadeia</b>
                    </>
                  )}
                  {grupo.kind === "time" &&
                    (grupo.arquitetoNome ? (
                      <>
                        · <Termo id="arquiteto">arquiteto</Termo>: <b>{grupo.arquitetoNome}</b>
                      </>
                    ) : (
                      <span className="team-chip-gate">sem arquiteto</span>
                    ))}
                  {grupo.kind === "sem-time" && (
                    <>
                      · <Termo id="dispatch">dispatch livre</Termo>
                    </>
                  )}
                </span>
                <span className="grupo-tally">
                  {grupo.estacoes.length}
                  {grupo.rodando > 0 ? ` · ${grupo.rodando} rodando` : " · quieto"}
                </span>
              </button>
              <div className="grupo-corpo">
                {grupo.estacoes.length === 0 ? (
                  <EmptyState compact>
                    Time sem agentes. <b>Peça ao CEO pra montar</b> — “monte o time {grupo.nome}”.
                  </EmptyState>
                ) : (
                  grupo.estacoes.map((station) => (
                    <AgentCard
                      key={station.agent.id}
                      station={station}
                      issues={issues}
                      nowMs={nowMs}
                      onOpenTranscript={onOpenTranscript}
                    />
                  ))
                )}
              </div>
            </section>
          );
        })}

        {children}
      </div>
    </>
  );
}
