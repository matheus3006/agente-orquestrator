package com.orquestrador.work.issue;

import java.util.UUID;

/**
 * Suporte de teste: ancora uma issue a uma conversa. Em producao a ancoragem so acontece dentro
 * da absorcao ({@code AbsorcaoDeIssueRouter}, ADR-0037) via o package-private
 * {@link Issue#ancorarConversa}; este utilitario vive no MESMO pacote para alcanca-lo sem abrir a
 * API de producao.
 */
public final class IssueConversaTestSupport {

    private IssueConversaTestSupport() {
    }

    public static void ancorar(IssueRepository issues, UUID companyId, UUID issueId, UUID conversationId) {
        Issue issue = issues.findByIdAndCompanyId(issueId, companyId).orElseThrow();
        issue.ancorarConversa(conversationId);
        issues.save(issue);
    }
}
