import type { ActivityEvent } from "./api";
import { shortId } from "./format";

// O feed de atividade nasce do shape CRU do activity_log (colunas tipadas +
// detail só com IDs, ADR-0009). Este módulo resolve os IDs em nomes/títulos
// (via lookups vindos das queries já na tela) e monta a frase do protótipo em
// SEGMENTOS tipados — o componente só renderiza, nunca interpreta.

export type FeedTone = "good" | "bad" | "warn" | "info";

export type FeedIcon =
  | "blocked"
  | "reopened"
  | "cancelled"
  | "review"
  | "approved"
  | "rejected"
  | "skill"
  | "generic";

export interface FeedSegment {
  kind: "text" | "strong" | "mono";
  text: string;
}

export interface FeedEntry {
  id: string;
  createdAt: string;
  tone: FeedTone;
  icon: FeedIcon;
  segments: FeedSegment[];
}

/** Resolvedores de ID→nome vindos das queries da tela (agregado + issues). */
export interface FeedLookups {
  agentName(agentId: string | null | undefined): string | null;
  issueTitle(issueId: string | null | undefined): string | null;
  /** O #N humano da issue (ADR-0033); null = issue fora dos lookups da tela. */
  issueSeq(issueId: string | null | undefined): number | null;
  issueRisk(issueId: string | null | undefined): number | null;
}

const strong = (text: string): FeedSegment => ({ kind: "strong", text });
const text = (value: string): FeedSegment => ({ kind: "text", text: value });
const mono = (value: string): FeedSegment => ({ kind: "mono", text: value });

export function buildFeedEntry(event: ActivityEvent, lookups: FeedLookups): FeedEntry {
  // Rótulo do ator, uma fatia por tipo: AGENT resolve pelo nome (fallback
  // "agente"); SYSTEM é o próprio orquestrador ("sistema") — a coleta de rows/
  // workspace roda sem humano (ADR-0028 B7 / incidente Atlas ADR-0026), então
  // não pode aparecer como "board"; o resto (board humano) é "board".
  const actor =
    event.actorType === "AGENT"
      ? (lookups.agentName(event.actorId) ?? "agente")
      : event.actorType === "SYSTEM"
        ? "sistema"
        : "board";
  const detail = event.detail ?? {};
  const detailId = (key: string): string | null =>
    typeof detail[key] === "string" ? (detail[key] as string) : null;

  /**
   * A identidade humana da issue em segmentos: `#12 Título` (#N mono, título strong —
   * ADR-0033, o MESMO número do board). Sem título resolvido o #12 fica sozinho; issue
   * fora dos lookups (lista ainda carregando) degrada para o id curto mono — nunca quebra.
   */
  const issueSegments = (issueId: string | null): FeedSegment[] => {
    const seq = lookups.issueSeq(issueId);
    const title = lookups.issueTitle(issueId);
    if (seq === null) {
      return [mono(shortId(issueId))];
    }
    return title ? [mono(`#${seq}`), text(" "), strong(title)] : [mono(`#${seq}`)];
  };

  const build = (tone: FeedTone, icon: FeedIcon, segments: FeedSegment[]): FeedEntry => ({
    id: event.id,
    createdAt: event.createdAt,
    tone,
    icon,
    segments,
  });

  switch (event.action) {
    case "ISSUE_BLOCKED": {
      const runId = detailId("runId");
      return build("bad", "blocked", [
        strong(actor),
        text(" travou em "),
        ...issueSegments(event.entityId),
        ...(runId ? [text(" — "), mono(`run ${shortId(runId)}`)] : []),
      ]);
    }
    case "ISSUE_REOPENED":
      return build("info", "reopened", [strong(actor), text(" reabriu "), ...issueSegments(event.entityId)]);
    // ADR-0049: NÃO é o mesmo fato que ISSUE_REOPENED (aquele é o unblock do §7). Este desfaz um
    // APPROVE — possivelmente o de um gestor promovido, que fechou a issue sem humano nenhum ver.
    // É a válvula que a D-ORG-2 exige em troca de delegar a decisão; o feed é onde ela aparece.
    case "ISSUE_REABERTA":
      return build("info", "reopened", [
        strong(actor),
        text(" reabriu a entrega aprovada de "),
        ...issueSegments(event.entityId),
      ]);
    case "ISSUE_CANCELLED":
      return build("info", "cancelled", [strong(actor), text(" cancelou "), ...issueSegments(event.entityId)]);
    case "ISSUE_SUBMITTED_FOR_REVIEW": {
      const risk = lookups.issueRisk(event.entityId);
      const reviewId = detailId("reviewId");
      const anchor = risk
        ? [text(" — "), mono(`risco ${risk} ≥ limiar`)]
        : reviewId
          ? [text(" — "), mono(`ciclo ${shortId(reviewId)}`)]
          : [];
      return build("warn", "review", [
        strong(actor),
        text(" estacionou "),
        ...issueSegments(event.entityId),
        text(" para review"),
        ...anchor,
      ]);
    }
    case "REVIEW_APPROVED":
    case "REVIEW_REJECTED": {
      const approved = event.action === "REVIEW_APPROVED";
      return build(approved ? "good" : "bad", approved ? "approved" : "rejected", [
        strong(actor),
        text(approved ? " aprovou o ciclo " : " rejeitou o ciclo "),
        mono(shortId(detailId("reviewId"))),
        text(" de "),
        ...issueSegments(event.entityId),
      ]);
    }
    case "SKILL_ATTACHED": {
      const agentName = lookups.agentName(detailId("agentId"));
      return build("info", "skill", [
        strong(actor),
        text(" anexou uma skill à "),
        ...issueSegments(detailId("issueId")),
        ...(agentName ? [text(" para "), strong(agentName)] : []),
      ]);
    }
    case "SKILL_INSTALLED": {
      const skillKey = detailId("skillKey");
      return build("info", "skill", [
        strong(actor),
        text(" instalou "),
        skillKey ? mono(skillKey) : text("uma skill"),
        text(" do catálogo"),
      ]);
    }
    case "SKILL_IMPORTED": {
      const count = (key: string) => (typeof detail[key] === "number" ? (detail[key] as number) : 0);
      return build("info", "skill", [
        strong(actor),
        text(" importou o catálogo de skills — "),
        mono(`${count("created")} novas · ${count("updated")} atualizadas · ${count("unchanged")} iguais`),
      ]);
    }
    default:
      return build("info", "generic", [strong(actor), text(" "), mono(event.action)]);
  }
}
