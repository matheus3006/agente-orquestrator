"use client";

import { Eye, Maximize2, Target } from "lucide-react";
import { useState } from "react";
import { CompanyLink } from "@/components/company-link";
import type { IssueSummary, MissionControlAgent, MissionControlStep } from "@/lib/api";
import { fmtClock, fmtElapsed, shortId } from "@/lib/format";
import { cardRunId, stationIssueLine, statusClass, statusWord } from "@/lib/mission-control";
import { isPreviewNovo } from "@/lib/preview";
import { lineKind, stepText } from "@/lib/transcript";
import { cn } from "@/lib/utils";

// O card do Mission Control — 1 por agente, portado 1:1 do protótipo:
// head (LED + nome + role + status word), linha de issue, mini-terminal
// (P1 #2: COLAPSADO por padrão — última linha + cursor; o hover no card abre
// a cauda, e o terminal cheio vive no drawer) e foot (elapsed/attempt + badge
// de preview F.9 — só ícone/texto, o clique leva à Issue).

export function AgentCard({
  station,
  issues,
  nowMs,
  onOpenTranscript,
}: {
  station: MissionControlAgent;
  issues: IssueSummary[];
  nowMs: number;
  onOpenTranscript: (station: MissionControlAgent) => void;
}) {
  const cls = statusClass(station.status);
  const line = stationIssueLine(station, issues);

  return (
    <article
      className={`agent-card ${cls}`}
      tabIndex={0}
      role="button"
      aria-label={`Transcript de ${station.agent.name}`}
      onClick={() => onOpenTranscript(station)}
      onKeyDown={(event) => {
        if (event.key === "Enter") {
          onOpenTranscript(station);
        }
      }}
    >
      <div className="agent-card-head">
        <span className={`led ${cls}`} />
        <span className="agent-name">{station.agent.name}</span>
        <span className="agent-role">{station.agent.role}</span>
        <span className={`agent-status-word ${cls}`}>{statusWord(station.status)}</span>
      </div>

      {line.note ? (
        <div className="agent-issue-line idle-note">{line.note}</div>
      ) : (
        <div className="agent-issue-line">
          <Target size={12} strokeWidth={2} />
          <span className="issue-title">
            <span className="text-faint">{line.verb}</span> {line.title}
          </span>
        </div>
      )}

      <MiniTerm steps={station.lastSteps} running={station.status === "RUNNING"} />

      <div className="agent-card-foot">
        <CardFootStatus station={station} nowMs={nowMs} />
        <span className="spacer" />
        {station.lastPreview && (
          <CompanyLink
            href={`/issues/${station.lastPreview.issueId}`}
            className={cn(
              "preview-badge",
              isPreviewNovo(station.lastPreview.createdAt, nowMs) && "novo",
            )}
            aria-label={`Preview v${station.lastPreview.version} — abrir a issue`}
            // o card inteiro abre o transcript; o badge navega — não deixa o clique vazar
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => event.stopPropagation()}
          >
            <Eye size={11} strokeWidth={2} /> preview v{station.lastPreview.version}
          </CompanyLink>
        )}
        <span className="peek">
          <Maximize2 size={11} strokeWidth={2} /> transcript
        </span>
      </div>
    </article>
  );
}

function CardFootStatus({ station, nowMs }: { station: MissionControlAgent; nowMs: number }) {
  if (station.status === "RUNNING" && station.activeRun?.startedAt) {
    return (
      <>
        <span className="elapsed">
          {fmtElapsed(nowMs - Date.parse(station.activeRun.startedAt))}
        </span>
        <span>· attempt {station.activeRun.attempt}</span>
      </>
    );
  }
  if (station.status === "QUEUED") {
    return <span>aguardando dispatch</span>;
  }
  const runId = cardRunId(station);
  return <span>{runId ? `run ${shortId(runId)}` : "—"}</span>;
}

const stepKey = (step: MissionControlStep) =>
  `${step.runId}:${step.createdAt}:${step.type}:${step.detail ?? ""}`;

function MiniTerm({ steps, running }: { steps: MissionControlStep[]; running: boolean }) {
  const tail = steps.slice(-5);
  const keys = tail.map(stepKey);
  // O snapshot inicial NÃO anima (spec do protótipo: só steps APPENDADOS
  // entram com line-in) — o initializer congela as chaves da primeira pintura.
  const [initialKeys] = useState(() => new Set(keys));

  return (
    <div className={cn("mini-term", running && "running")}>
      {tail.length === 0 ? (
        <div className="term-empty">sem execuções ainda — acorde o agente para ver o trace aqui</div>
      ) : (
        tail.map((step, index) => {
          const kind = lineKind(step.type, step.detail);
          return (
            <div
              key={keys[index]}
              className={cn("term-line", kind !== "plain" && kind)}
              style={initialKeys.has(keys[index]) ? { animation: "none" } : undefined}
            >
              <span className="t">{fmtClock(step.createdAt)}</span>
              {stepText(step)}
            </div>
          );
        })
      )}
      {/* #2: colapsado mostra a última linha; o cursor fósforo (ancorado no
          canto pelo CSS) é o indicador de "trabalho vivo" — fora do fluxo, então
          nunca é cortado pela elipse de uma linha longa. Terminal cheio: drawer. */}
      {running && tail.length > 0 && <span className="term-cursor" aria-hidden />}
    </div>
  );
}
