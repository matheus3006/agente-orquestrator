"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Suspense, useEffect, useMemo, useState } from "react";
import { ActivityFeed } from "@/components/activity-feed";
import { CeoConversa } from "@/components/ceo-conversa";
import { StepTraceDrawer, type TranscriptRequest } from "@/components/step-trace-drawer";
import { TimesObservados } from "@/components/times-observados";
import { EmptyState } from "@/components/ui/empty-state";
import {
  getActivity,
  getMissionControl,
  listAgents,
  listIssues,
  listTeams,
  type MissionControlAgent,
} from "@/lib/api";
import type { FeedLookups } from "@/lib/feed";
import {
  cardRunId,
  estadoOcioso,
  issuesListBehindAggregate,
  issuesListBehindIdleSettle,
  stationIssueLine,
} from "@/lib/mission-control";
import { queryKeys } from "@/lib/query-keys";
import { agruparEstacoes } from "@/lib/team-groups";
import { useActiveCompany } from "@/lib/use-active-company";
import { useNow } from "@/lib/use-now";

// Mission Control DOCADA (T.3-FE, mockup aprovado): duas zonas — cards vivos
// dos times à ESQUERDA (somente-leitura; a cadeia de comando é imposta pela
// UI) · conversa do CEO à DIREITA (o único composer do board). Observar a
// empresa rodar e dirigir o CEO sem trocar de tela. Mesmo transporte da
// Central clássica: 1 poll de 2s no agregado; issues/roster/times em
// staleTime 30s só para resolver nomes; poll pausa em aba oculta (ADR-0013).

const FEED_FETCH_LIMIT = 50;
const FEED_VISIBLE_ROWS = 10;

// #12 (P2-C): fôlego anti-flicker do aviso de board parado. 8s = 4 ticks do
// poll de 2s — some no ato ao virar ativo, mas só ENTRA depois de parado de
// verdade (não pipoca entre runs encadeados). Ajustável VENDO.
const FOLEGO_MS = 8000;

export default function MissionControlPage() {
  const { company } = useActiveCompany();
  const companyId = company?.id;
  const nowMs = useNow(1000);

  const snapshotQuery = useQuery({
    queryKey: queryKeys.missionControl(companyId ?? ""),
    queryFn: () => getMissionControl(companyId as string),
    enabled: !!companyId,
    refetchInterval: 2000,
    refetchIntervalInBackground: false,
  });
  const activityQuery = useQuery({
    queryKey: queryKeys.activity(companyId ?? ""),
    queryFn: () => getActivity(companyId as string, FEED_FETCH_LIMIT),
    enabled: !!companyId,
    refetchInterval: 2000,
    refetchIntervalInBackground: false,
  });
  const issuesQuery = useQuery({
    queryKey: queryKeys.issues(companyId ?? ""),
    queryFn: () => listIssues(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const rosterQuery = useQuery({
    queryKey: queryKeys.agents(companyId ?? ""),
    queryFn: () => listAgents(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });
  const teamsQuery = useQuery({
    queryKey: queryKeys.teams(companyId ?? ""),
    queryFn: () => listTeams(companyId as string),
    enabled: !!companyId,
    staleTime: 30_000,
  });

  const agents = snapshotQuery.data?.agents;
  const issues = useMemo(() => issuesQuery.data ?? [], [issuesQuery.data]);

  // O agregado (2s) enxerga uma transição antes da lista de issues (staleTime
  // 30s) → invalida a lista com o MESMO guard por dataUpdatedAt (impede loop,
  // achado da revisão da F.3). Dois gatilhos-espelho: BLOCKED novo (o card
  // precisa de "travado em <issue>") e board recém-parado com issue ainda
  // IN_PROGRESS (senão "entregue"/"quieto" mentiriam até um refocus — #12).
  const queryClient = useQueryClient();
  const issuesUpdatedAt = issuesQuery.dataUpdatedAt;
  const snapshotUpdatedAt = snapshotQuery.dataUpdatedAt;
  useEffect(() => {
    if (!companyId || !agents || !issuesQuery.data) {
      return;
    }
    const listaAtrasada =
      issuesListBehindAggregate(agents, issuesQuery.data) ||
      issuesListBehindIdleSettle(agents, issuesQuery.data);
    if (listaAtrasada && issuesUpdatedAt < snapshotUpdatedAt) {
      queryClient.invalidateQueries({ queryKey: queryKeys.issues(companyId) });
    }
  }, [agents, issuesQuery.data, issuesUpdatedAt, snapshotUpdatedAt, companyId, queryClient]);

  const grupos = useMemo(
    () => agruparEstacoes(agents ?? [], rosterQuery.data ?? [], teamsQuery.data ?? []),
    [agents, rosterQuery.data, teamsQuery.data],
  );

  // O CEO endereçado pela conversa: o balcão único é o QUESTIONADOR (ADR-0031/D2);
  // sem personas (company legada pré-backfill) cai no role='ceo' mais antigo, como o
  // ConversationService. null = composer avisa que falta contratar.
  const ceos = rosterQuery.data?.filter((agente) => agente.role.toLowerCase() === "ceo") ?? [];
  const ceoNome = (ceos.find((agente) => agente.persona === "questionador") ?? ceos[0])?.name ?? null;

  // #12 (P2-C): com o board parado a identidade fósforo apaga — a tela guia o
  // próximo passo canônico ("fale com o CEO"). Detecção PURA (estadoOcioso) +
  // fôlego anti-flicker REUSANDO o nowMs do useNow(1000), sem segundo interval.
  // `lastActiveAt` marca o último instante ATIVO (ou de erro): enquanto ocioso
  // ele congela e a afordância só entra quando o silêncio passa de FOLEGO_MS.
  // null = nunca visto ativo desde o mount → board que ABRE parado mostra na
  // hora (sem atraso onde não havia flicker a evitar). O registro usa "ajustar
  // estado durante o render" (padrão sancionado, cf. ceo-conversa.tsx) com guard
  // `!== nowMs` que converge — nem efeito (setState síncrono proibido) nem ref
  // (leitura no render proibida) servem aqui.
  const estado = estadoOcioso(agents, ceoNome, issues);
  const boardTemAtividade = !!agents && agents.some((station) => station.status !== "IDLE");
  const [lastActiveAt, setLastActiveAt] = useState<number | null>(null);
  if ((boardTemAtividade || snapshotQuery.isError) && lastActiveAt !== nowMs) {
    setLastActiveAt(nowMs);
  }
  const avisoOciosoEstavel =
    estado !== null && (lastActiveAt === null || nowMs - lastActiveAt > FOLEGO_MS);
  // Nunca no skeleton nem sobre o banner de erro (o react-query mantém `data`
  // stale no erro; sem o gate a afordância apareceria sobre tally velho).
  const mostrarAvisoOcioso =
    avisoOciosoEstavel && !snapshotQuery.isPending && !snapshotQuery.isError;

  // Por D-12-04-b (veto E0 no review): review pendente deixa agentes idle com
  // trabalho ENTREGUE esperando o operador — a copy aponta a pendência, não
  // implica inércia. "quieto" sem review pendente segue "nada rodando".
  const temReviewPendente = issues.some((issue) => issue.status === "PENDING_REVIEW");
  const copiaOcioso =
    estado === "primeiro-uso"
      ? "os times executam o que o CEO abre — dê a primeira direção"
      : estado === "entregue"
        ? "tudo entregue — dê a próxima direção"
        : temReviewPendente
          ? "há review aguardando você — responda na conversa"
          : "nada rodando — dê a próxima direção";

  // A ação canônica: focar o composer (o ÚNICO lugar onde o board escreve). Sem
  // conversa ativa o textarea está disabled → mirar o gatilho "+" (novo chat).
  // Seam por id no DOM — o repo não usa forwardRef (M-12-C).
  const falarComCeo = () => {
    const composer = document.getElementById("composer-do-ceo") as HTMLTextAreaElement | null;
    if (composer && !composer.disabled) {
      composer.scrollIntoView({ block: "center" });
      composer.focus({ preventScroll: true });
      return;
    }
    const novoChat = document.getElementById("novo-chat-do-ceo");
    novoChat?.scrollIntoView({ block: "center" });
    novoChat?.focus({ preventScroll: true });
  };

  const lookups = useMemo<FeedLookups>(
    () => ({
      agentName: (id) => agents?.find((station) => station.agent.id === id)?.agent.name ?? null,
      issueTitle: (id) => issues.find((issue) => issue.id === id)?.title ?? null,
      issueSeq: (id) => issues.find((issue) => issue.id === id)?.seq ?? null,
      issueRisk: (id) => issues.find((issue) => issue.id === id)?.riskLevel ?? null,
    }),
    [agents, issues],
  );

  const [trace, setTrace] = useState<TranscriptRequest | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const openTranscript = (station: MissionControlAgent) => {
    // Título no drawer SÓ quando o vínculo run↔issue é factual (F.3).
    const line = stationIssueLine(station, issues);
    setTrace({
      runId: cardRunId(station),
      issueTitle:
        station.activeRun?.issueTitle ?? (station.status === "BLOCKED" ? line.title : null),
      agentName: station.agent.name,
    });
    setDrawerOpen(true);
  };

  // Disclosure da thread (mockup §5): turno/approval abrem o StepTrace do run
  // do CEO — profundidade a 1 clique, nunca despejada na conversa.
  const openTraceDoRun = (runId: string) => {
    setTrace({ runId, issueTitle: null, agentName: ceoNome ?? "CEO" });
    setDrawerOpen(true);
  };

  return (
    <div className="central-docada">
      <section className="zona zona-times" aria-label="Times — somente leitura">
        {snapshotQuery.isError && (
          <div className="px-5 pt-4">
            <p className="m-0 text-[12.5px] text-coral">
              Não deu para falar com o backend — o poll de 2s segue tentando.
            </p>
          </div>
        )}
        <TimesObservados
          grupos={grupos}
          issues={issues}
          nowMs={nowMs}
          carregando={snapshotQuery.isPending && !snapshotQuery.isError}
          onOpenTranscript={openTranscript}
          avisoOcioso={
            mostrarAvisoOcioso ? (
              <EmptyState
                compact
                className="aviso-ocioso"
                action={
                  <button
                    type="button"
                    className="font-mono text-[11px] uppercase tracking-[0.08em] text-phosphor-dim no-underline transition-colors duration-150 ease-out-strong hover:text-phosphor"
                    onClick={falarComCeo}
                  >
                    falar com o CEO →
                  </button>
                }
              >
                {copiaOcioso}
              </EmptyState>
            ) : null
          }
        >
          <h2 className="section-label">Atividade recente</h2>
          {activityQuery.data ? (
            <ActivityFeed
              events={activityQuery.data.slice(0, FEED_VISIBLE_ROWS)}
              lookups={lookups}
              nowMs={nowMs}
            />
          ) : activityQuery.isError ? (
            <EmptyState compact tone="erro">
              não deu para carregar a atividade — recarregue
            </EmptyState>
          ) : (
            <div className="h-24 animate-pulse rounded-md bg-raised" aria-busy="true" />
          )}
        </TimesObservados>
      </section>

      <section className="zona zona-ceo" aria-label="Conversa com o CEO">
        {/* useSearchParams (deep-link ?chat=) exige Suspense no app router */}
        <Suspense fallback={null}>
          <CeoConversa
            companyId={companyId}
            ceoNome={ceoNome}
            issues={issues}
            onOpenTrace={openTraceDoRun}
          />
        </Suspense>
      </section>

      <StepTraceDrawer
        companyId={companyId}
        request={trace}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
}
