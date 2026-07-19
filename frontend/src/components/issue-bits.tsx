"use client";

import { AgentDot } from "@/components/agent-dot";
import { isTerminalIssueStatus } from "@/lib/issues";
import { cn } from "@/lib/utils";

// Peças pequenas e compartilhadas da superfície Issues (lista, detail e a
// fila de review da F.5): risk badge e assignee chip — spec do protótipo.

// #14: `status` é opcional — informado, o badge muta a cor de risco quando a issue
// é terminal (DONE/CANCELLED): risco fala de trabalho PENDENTE. Sem `status` (ex.:
// fila de review, sempre PENDING_REVIEW) o risco segue vivo, exatamente como antes.
export function RiskBadge({
  riskLevel,
  status,
  className,
}: {
  riskLevel: number;
  status?: string;
  className?: string;
}) {
  const muted = status !== undefined && isTerminalIssueStatus(status);
  return (
    <span className={cn("risk-badge", `risk-${riskLevel}`, muted && "risk-muted", className)}>
      R{riskLevel}
    </span>
  );
}

/**
 * O selo da task corretiva (caso B, ADR-0034): a reincidência VISÍVEL — a
 * corretiva declara na superfície qual entrega ela corrige. `null` = sem selo
 * (task normal, ou alvo colhido — a coluna segue auditável no backend).
 */
export function CorrigeSelo({ selo }: { selo: string | null }) {
  if (selo === null) {
    return null;
  }
  return <span className="corrige-selo">{selo}</span>;
}

/**
 * O selo da issue dirigida SEM GATE (ADR-0047, D-DSG-6): "dirigida · <gestor>" na
 * lista do board. `null` = sem selo (issue normal, ou do ramo estágio — que passou
 * pelo gate e por decisão consciente NÃO carrega a marca). Mesmo tom quieto do
 * CorrigeSelo: a marca é informação, não perigo.
 */
export function DirigidaSelo({ selo }: { selo: string | null }) {
  if (selo === null) {
    return null;
  }
  return <span className="corrige-selo">{selo}</span>;
}

/**
 * `null` = SEM assignee (afirma "— livre"); `undefined` = nome ainda não
 * resolvível (agents carregando/falhou) — mostra só "—", nunca afirma
 * "livre" para issue designada (achado da revisão).
 */
export function AssigneeChip({ name }: { name: string | null | undefined }) {
  if (name === undefined) {
    return <span className="assignee-chip text-faint">—</span>;
  }
  if (name === null) {
    return <span className="assignee-chip text-faint">— livre</span>;
  }
  return (
    <span className="assignee-chip">
      <AgentDot name={name} />
      {name}
    </span>
  );
}
