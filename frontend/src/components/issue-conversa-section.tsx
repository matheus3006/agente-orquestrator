"use client";

import { useQuery } from "@tanstack/react-query";
import { MessageSquare } from "lucide-react";
import { CompanyLink } from "@/components/company-link";
import { EmptyState } from "@/components/ui/empty-state";
import { listTurns } from "@/lib/api";
import { fmtClock } from "@/lib/format";
import { queryKeys } from "@/lib/query-keys";

// A thread da issue no DETALHE do card (ADR-0037, D4/M11): issue = conversa
// direcionada — o board segue o menu de observação e a conversa mora AQUI, a um
// clique do trabalho que ela governa. Só os turnos VISÍVEIS chegam (o backend
// esconde gatilho/retoma pelo filtro da ADR-0030); a resposta do dono entra
// pelo balcão único normal — o chat "#N — <título>" na tela de conversas.

export function IssueConversaSection({
  companyId,
  conversationId,
  issueSeq,
}: {
  companyId: string | undefined;
  conversationId: string;
  issueSeq: number;
}) {
  const turnosQuery = useQuery({
    queryKey: queryKeys.conversationTurns(companyId ?? "", conversationId),
    queryFn: () => listTurns(companyId as string, conversationId),
    enabled: !!companyId,
    refetchInterval: 5000,
    refetchIntervalInBackground: false,
  });

  return (
    <>
      <h2 className="section-label">Conversa da issue</h2>
      <div className="issue-conversa">
        {turnosQuery.data && turnosQuery.data.length > 0 ? (
          <>
            {turnosQuery.data.map((turno) => (
              <div key={turno.id} className="issue-conversa-turno">
                <div className="issue-conversa-msg">
                  <span className="issue-conversa-quem">você</span> {turno.ownerMessage}
                </div>
                {turno.ceoReply !== null && (
                  <div className="issue-conversa-msg">
                    <span className="issue-conversa-quem">CEO</span> {turno.ceoReply}
                  </div>
                )}
                <span className="issue-conversa-hora mono">{fmtClock(turno.createdAt)}</span>
              </div>
            ))}
            <CompanyLink href="/" className="link-trace">
              ▸ responder no chat &quot;#{issueSeq}&quot;
            </CompanyLink>
          </>
        ) : turnosQuery.isPending ? (
          <div className="h-12 animate-pulse rounded-md bg-raised" aria-busy="true" />
        ) : turnosQuery.isError ? (
          <EmptyState compact tone="erro">
            não deu para carregar a conversa — recarregue
          </EmptyState>
        ) : (
          <EmptyState compact icon={<MessageSquare />}>
            o CEO ainda está triando — a confirmação da absorção aparece aqui
          </EmptyState>
        )}
      </div>
    </>
  );
}
