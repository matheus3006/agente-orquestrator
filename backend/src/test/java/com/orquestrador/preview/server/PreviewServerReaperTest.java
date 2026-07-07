package com.orquestrador.preview.server;

import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
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
 * F.10 (ADR-0023 §3): TTL por INATIVIDADE — o reaper ceifa quem passou um TTL inteiro sem
 * heartbeat; o keepalive do board renova; vivo DESCONHECIDO (pos-restart: heartbeats moram em
 * memoria) e adotado com grace de um TTL. TTL de teste: 2s (producao: 900s por property).
 */
@SpringBootTest(properties = "orquestrador.preview.server.ttl-seconds=2")
@ActiveProfiles("test")
class PreviewServerReaperTest {

    @Autowired
    private PreviewServerSupervisor supervisor;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private ExecutionTargets targets;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private record Cenario(UUID companyId, UUID agentId, UUID issueId) {
    }

    private final java.util.List<Cenario> cenarios = new java.util.ArrayList<>();

    private Cenario cenarioComServidorDeclarado(int porta) {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "app com servidor", null).getId();
        manifests.save(new PreviewServerManifest(UUID.randomUUID(), companyId, issueId, agentId,
                UUID.randomUUID(), "python3 -m http.server " + porta, porta));
        Cenario cenario = new Cenario(companyId, agentId, issueId);
        cenarios.add(cenario);
        return cenario;
    }

    private int portaLivre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    void derrubaTudo() {
        cenarios.forEach(cenario -> supervisor.stopFor(cenario.companyId(), cenario.issueId(), null));
    }

    @Test
    void reaperCeifaVivoSemHeartbeatDepoisDoTtl() throws Exception {
        Cenario cenario = cenarioComServidorDeclarado(portaLivre());
        assertThat(supervisor.startFor(cenario.companyId(), cenario.issueId(), null).failed()).isFalse();

        supervisor.reapOnce();
        assertThat(supervisor.listLive(cenario.companyId()))
                .as("dentro do TTL o reaper nao toca")
                .hasSize(1);

        Thread.sleep(2_300);
        supervisor.reapOnce();

        assertThat(supervisor.listLive(cenario.companyId())).isEmpty();
    }

    @Test
    void heartbeatDoBoardRenovaEOVivoSobreviveAoReaper() throws Exception {
        Cenario cenario = cenarioComServidorDeclarado(portaLivre());
        assertThat(supervisor.startFor(cenario.companyId(), cenario.issueId(), null).failed()).isFalse();

        Thread.sleep(1_200);
        assertThat(supervisor.heartbeat(cenario.companyId(), cenario.issueId())).isTrue();
        Thread.sleep(1_200); // 2.4s desde o start, mas so 1.2s desde o heartbeat
        supervisor.reapOnce();

        assertThat(supervisor.listLive(cenario.companyId()))
                .as("keepalive do board mantem vivo alem do TTL original")
                .hasSize(1);
    }

    @Test
    void vivoDesconhecidoGanhaGraceDeUmTtlAntesDeSerCeifado() throws Exception {
        // Restart do backend: heartbeats em memoria somem, o container continua vivo. O reaper
        // ADOTA no primeiro avistamento (grace de um TTL) — nao orfa nem executa sumariamente;
        // se ninguem renovar, ceifa no ciclo pos-grace.
        Cenario cenario = cenarioComServidorDeclarado(portaLivre());
        ExecutionTarget local = targets.forType(ExecutionTarget.Type.LOCAL);
        PreviewServerManifest manifest = manifests.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId()).orElseThrow();
        // Sobe DIRETO no alvo: o supervisor nunca viu este servidor (simula o pos-restart).
        ExecutionTarget.PreviewServerStart start = local.startPreviewServer(
                new ExecutionTarget.PreviewServerSpec(
                        PreviewServerSupervisor.serverNameFor(cenario.companyId(), cenario.agentId(),
                                cenario.issueId()),
                        java.nio.file.Files.createTempDirectory("orq-preview-adote"),
                        "vol-ignorado", null, manifest.getCommand(), manifest.getPort(),
                        cenario.companyId(), cenario.agentId(), cenario.issueId(),
                        "512m", "1", "256"));
        assertThat(start.failed()).as(start.error()).isFalse();

        supervisor.reapOnce();
        assertThat(supervisor.listLive(cenario.companyId()))
                .as("primeiro avistamento adota com grace, nao ceifa")
                .hasSize(1);

        Thread.sleep(2_300);
        supervisor.reapOnce();
        assertThat(supervisor.listLive(cenario.companyId()))
                .as("grace vencido sem heartbeat: ceifado")
                .isEmpty();
    }
}
