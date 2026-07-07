package com.orquestrador.preview.server;

import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.issue.IssueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.10 (ADR-0023 §3b): checkout de run NOVO na mesma Issue derruba o servidor vivo — o workspace
 * vai mutar e o reviewer nao pode ver um alvo movel. Best-effort: a falha do teardown jamais
 * mata o run (o executor toca este ponto como toca o settle: de fora da maquina de estados).
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerTeardownOnCheckoutTest {

    @Autowired
    private PreviewServerSupervisor supervisor;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private UUID companyId;
    private UUID issueId;

    @AfterEach
    void derrubaTudo() {
        if (companyId != null && issueId != null) {
            supervisor.stopFor(companyId, issueId, null);
        }
    }

    private int portaLivre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void checkoutDeRunNovoNaMesmaIssueDerrubaOServidorVivo() throws Exception {
        companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        issueId = issues.create(companyId, "app com servidor", null).getId();
        int porta = portaLivre();
        manifests.save(new PreviewServerManifest(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), "python3 -m http.server " + porta, porta));
        assertThat(supervisor.startFor(companyId, issueId, null).failed()).isFalse();
        assertThat(supervisor.listLive(companyId)).hasSize(1);

        wakeups.enqueue(companyId, agentId, issueId, "melhorar o app");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issues.get(companyId, issueId).getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(supervisor.listLive(companyId))
                .as("o run novo mutou o workspace: o preview vivo do estado anterior morre")
                .isEmpty();
    }
}
