package com.orquestrador.preview.server;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.execution.RunContext;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.10 (ADR-0023 §2): o registrador conhece o contrato de lugar {@code .orq/preview/server.json},
 * o cap de 4KB e o parse ESTRITO ({"command","port"}, porta 1-65535) — e persiste a declaracao
 * POR RUN. Best-effort legivel como o capturador v1: falha vira registro, JAMAIS excecao pro
 * settle (a declaracao e observacao, nao participante da maquina de estados).
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerManifestRegistrarTest {

    @Autowired
    private PreviewServerManifestRegistrar registrar;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ActivityLogRepository activity;

    private record Cenario(UUID companyId, UUID agentId, UUID issueId) {
    }

    private Cenario cenarioLocal() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "app com servidor", null).getId();
        return new Cenario(companyId, agentId, issueId);
    }

    private void declara(Cenario cenario, String json) throws Exception {
        Path contrato = workspaces.resolve(cenario.companyId(), cenario.agentId(), cenario.issueId())
                .resolve(".orq/preview/server.json");
        Files.createDirectories(contrato.getParent());
        Files.writeString(contrato, json);
    }

    private RunContext runDa(Cenario cenario) {
        return new RunContext(cenario.companyId(), cenario.agentId(), UUID.randomUUID(), cenario.issueId());
    }

    @Test
    void manifestDeclaradoPersisteComandoEPortaPorRun() throws Exception {
        Cenario cenario = cenarioLocal();
        declara(cenario, "{\"command\": \"node server.js\", \"port\": 3000}");
        RunContext run = runDa(cenario);

        registrar.registerIfDeclared(run);

        PreviewServerManifest vigente = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(cenario.companyId(), cenario.issueId())
                .orElseThrow();
        assertThat(vigente.getCommand()).isEqualTo("node server.js");
        assertThat(vigente.getPort()).isEqualTo(3000);
        assertThat(vigente.getRunId()).isEqualTo(run.runId());
        assertThat(vigente.getAgentId()).isEqualTo(cenario.agentId());
    }

    @Test
    void nadaDeclaradoERunNormalNadaPersistidoNadaRegistrado() {
        Cenario cenario = cenarioLocal();

        registrar.registerIfDeclared(runDa(cenario));

        assertThat(manifests.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId())).isEmpty();
        assertThat(activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId()))
                .extracting(ActivityLog::getAction)
                .doesNotContain("PREVIEW_SERVER_DECLARED", "PREVIEW_SERVER_DECLARATION_FAILED");
    }

    @Test
    void parseEstritoRecusaJsonInvalidoCampoExtraPortaForaDaFaixaEComandoVazio() throws Exception {
        // Typo silenciosamente ignorado viraria servidor que nunca sobe — recusa LEGIVEL, nada
        // persistido. Cada variante e um manifest invalido de classe diferente.
        for (String invalido : List.of(
                "nao-e-json",
                "[\"command\", \"port\"]",
                "{\"command\": \"node server.js\"}",
                "{\"command\": \"node server.js\", \"port\": 3000, \"extra\": true}",
                "{\"comand\": \"node server.js\", \"port\": 3000}",
                "{\"command\": \"node server.js\", \"port\": 0}",
                "{\"command\": \"node server.js\", \"port\": 65536}",
                "{\"command\": \"node server.js\", \"port\": \"3000\"}",
                "{\"command\": \"node server.js\", \"port\": 3000.5}",
                "{\"command\": \"\", \"port\": 3000}")) {
            Cenario cenario = cenarioLocal();
            declara(cenario, invalido);

            registrar.registerIfDeclared(runDa(cenario));

            assertThat(manifests.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                    cenario.companyId(), cenario.issueId()))
                    .as("manifest invalido nao pode persistir: %s", invalido)
                    .isEmpty();
            assertThat(activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId()))
                    .as("recusa deve ser legivel: %s", invalido)
                    .anySatisfy(evento -> {
                        assertThat(evento.getAction()).isEqualTo("PREVIEW_SERVER_DECLARATION_FAILED");
                        assertThat(evento.getDetail()).contains("INVALID_MANIFEST");
                    });
        }
    }

    @Test
    void capDe4KBEstouradoRegistraFalhaLegivelENuncaLanca() throws Exception {
        Cenario cenario = cenarioLocal();
        declara(cenario, "{\"command\": \"" + "x".repeat(4 * 1024) + "\", \"port\": 3000}");

        registrar.registerIfDeclared(runDa(cenario));

        assertThat(manifests.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId())).isEmpty();
        assertThat(activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId()))
                .anySatisfy(evento -> {
                    assertThat(evento.getAction()).isEqualTo("PREVIEW_SERVER_DECLARATION_FAILED");
                    assertThat(evento.getDetail()).contains("CAP_EXCEEDED");
                });
    }

    @Test
    void runSemIssueNaoRegistra() {
        Cenario cenario = cenarioLocal();
        long antes = manifests.count();

        registrar.registerIfDeclared(new RunContext(cenario.companyId(), cenario.agentId(),
                UUID.randomUUID(), null));

        assertThat(manifests.count()).isEqualTo(antes);
    }

    @Test
    void runNovoRedeclaraSemDedupOVigenteEOUltimo() throws Exception {
        // O workspace persiste entre runs: re-declarar a MESMA coisa vira linha nova (linhas
        // minusculas, sem dedup — decisao do ADR-0023 §8); subir usa sempre o ultimo.
        Cenario cenario = cenarioLocal();
        declara(cenario, "{\"command\": \"node server.js\", \"port\": 3000}");

        registrar.registerIfDeclared(runDa(cenario));
        RunContext segundoRun = runDa(cenario);
        registrar.registerIfDeclared(segundoRun);

        assertThat(manifests.findByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                cenario.companyId(), cenario.issueId())).hasSize(2);
        assertThat(manifests.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(
                        cenario.companyId(), cenario.issueId()).orElseThrow().getRunId())
                .isEqualTo(segundoRun.runId());
    }
}
