package com.orquestrador.preview.server;

import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.WorkspaceResolver;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.10 (ADR-0023 §3/§7/§8): o modulo profundo do servidor de preview — sobe SOB DEMANDA o comando
 * PERSISTIDO no manifest (1 por Issue, idempotente), espera a porta responder (poll TCP), falha
 * LEGIVEL com tail quando o processo morre ao subir, e derruba manual/por teardown. LOCAL real
 * (python3 http.server, sem credito) — o DOCKER so muda o adapter do seam, provado no smoke F.10d.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewServerSupervisorTest {

    @Autowired
    private PreviewServerSupervisor supervisor;
    @Autowired
    private PreviewServerManifestRepository manifests;
    @Autowired
    private ExecutionTargets targets;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;

    private record Cenario(UUID companyId, UUID agentId, UUID issueId) {
    }

    private final java.util.List<Cenario> cenarios = new java.util.ArrayList<>();

    private Cenario cenarioLocal() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "app com servidor", null).getId();
        Cenario cenario = new Cenario(companyId, agentId, issueId);
        cenarios.add(cenario);
        return cenario;
    }

    private int portaLivre() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void declaraManifestPersistido(Cenario cenario, String command, int port) {
        manifests.save(new PreviewServerManifest(UUID.randomUUID(), cenario.companyId(),
                cenario.issueId(), cenario.agentId(), UUID.randomUUID(), command, port));
    }

    @AfterEach
    void derrubaTudo() {
        cenarios.forEach(cenario -> supervisor.stopFor(cenario.companyId(), cenario.issueId(), null));
    }

    @Test
    void startForSobeOComandoPersistidoEAPortaRespondeComOConteudoDoWorkspace() throws Exception {
        // A garantia inteira num teste: o comando vem do MANIFEST PERSISTIDO, roda no workspace
        // do run, e a URL devolvida serve o que o run liquidou.
        Cenario cenario = cenarioLocal();
        Path workspace = workspaces.resolve(cenario.companyId(), cenario.agentId(), cenario.issueId());
        Files.writeString(workspace.resolve("index.html"), "<h1>app viva do run</h1>");
        int porta = portaLivre();
        declaraManifestPersistido(cenario, "python3 -m http.server " + porta, porta);

        PreviewServerSupervisor.StartResult resultado =
                supervisor.startFor(cenario.companyId(), cenario.issueId(), null);

        assertThat(resultado.failed()).as(resultado.error()).isFalse();
        assertThat(resultado.url()).isEqualTo("http://127.0.0.1:" + porta);
        HttpResponse<String> resposta = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(resultado.url() + "/index.html")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.body()).contains("app viva do run");
    }

    @Test
    void semManifestEFalhaLegivelSemSubirNada() {
        Cenario cenario = cenarioLocal();

        PreviewServerSupervisor.StartResult resultado =
                supervisor.startFor(cenario.companyId(), cenario.issueId(), null);

        assertThat(resultado.failed()).isTrue();
        assertThat(resultado.error()).contains("nenhum servidor declarado");
        assertThat(supervisor.listLive(cenario.companyId())).isEmpty();
    }

    @Test
    void startEIdempotentePorIssueVivoEReusadoNuncaDuplicado() throws Exception {
        Cenario cenario = cenarioLocal();
        int porta = portaLivre();
        declaraManifestPersistido(cenario, "python3 -m http.server " + porta, porta);

        PreviewServerSupervisor.StartResult primeiro =
                supervisor.startFor(cenario.companyId(), cenario.issueId(), null);
        PreviewServerSupervisor.StartResult segundo =
                supervisor.startFor(cenario.companyId(), cenario.issueId(), null);

        assertThat(primeiro.failed()).as(primeiro.error()).isFalse();
        assertThat(segundo.failed()).as(segundo.error()).isFalse();
        assertThat(segundo.hostPort()).isEqualTo(primeiro.hostPort());
        assertThat(supervisor.listLive(cenario.companyId()))
                .filteredOn(vivo -> vivo.issueId().equals(cenario.issueId()))
                .hasSize(1);
    }

    @Test
    void comandoQueMorreAoSubirEFalhaLegivelComTailENadaFicaVivo() {
        // O espirito CredentialFailureSignal (ADR-0023 §7): porta nunca respondeu -> "morreu ao
        // subir" + ultimas linhas do log — o operador le o PORQUE, nao um spinner eterno.
        Cenario cenario = cenarioLocal();
        declaraManifestPersistido(cenario, "echo EADDRINUSE porta ja ocupada; exit 7", 39999);

        PreviewServerSupervisor.StartResult resultado =
                supervisor.startFor(cenario.companyId(), cenario.issueId(), null);

        assertThat(resultado.failed()).isTrue();
        assertThat(resultado.error()).contains("servidor morreu ao subir");
        assertThat(resultado.error()).contains("EADDRINUSE porta ja ocupada");
        assertThat(supervisor.listLive(cenario.companyId())).isEmpty();
    }

    @Test
    void stopForDerrubaOVivoDaIssueEEIdempotente() throws Exception {
        Cenario cenario = cenarioLocal();
        int porta = portaLivre();
        declaraManifestPersistido(cenario, "python3 -m http.server " + porta, porta);
        assertThat(supervisor.startFor(cenario.companyId(), cenario.issueId(), null).failed()).isFalse();

        assertThat(supervisor.stopFor(cenario.companyId(), cenario.issueId(), null)).isTrue();

        assertThat(supervisor.listLive(cenario.companyId())).isEmpty();
        assertThat(supervisor.stopFor(cenario.companyId(), cenario.issueId(), null))
                .as("derrubar o que nao existe e OK (best-effort)")
                .isFalse();
    }

    @Test
    void listLiveTrazIdentidadePortaETtlRestanteDaCompany() throws Exception {
        Cenario cenario = cenarioLocal();
        int porta = portaLivre();
        declaraManifestPersistido(cenario, "python3 -m http.server " + porta, porta);
        supervisor.startFor(cenario.companyId(), cenario.issueId(), null);

        List<PreviewServerSupervisor.LiveServer> vivos = supervisor.listLive(cenario.companyId());

        assertThat(vivos).hasSize(1);
        PreviewServerSupervisor.LiveServer vivo = vivos.get(0);
        assertThat(vivo.issueId()).isEqualTo(cenario.issueId());
        assertThat(vivo.agentId()).isEqualTo(cenario.agentId());
        assertThat(vivo.hostPort()).isEqualTo(porta);
        assertThat(vivo.url()).isEqualTo("http://127.0.0.1:" + porta);
        assertThat(vivo.ttlRemainingSeconds()).isGreaterThan(0);
        // Company alheia nao enxerga (deny-by-default na propria superficie de supervisao).
        assertThat(supervisor.listLive(UUID.randomUUID())).isEmpty();
    }

    @Test
    void heartbeatSoRenovaQuemEstaVivo() throws Exception {
        Cenario cenario = cenarioLocal();
        assertThat(supervisor.heartbeat(cenario.companyId(), cenario.issueId()))
                .as("sem vivo nao ha o que renovar")
                .isFalse();
        int porta = portaLivre();
        declaraManifestPersistido(cenario, "python3 -m http.server " + porta, porta);
        supervisor.startFor(cenario.companyId(), cenario.issueId(), null);

        assertThat(supervisor.heartbeat(cenario.companyId(), cenario.issueId())).isTrue();
    }
}
