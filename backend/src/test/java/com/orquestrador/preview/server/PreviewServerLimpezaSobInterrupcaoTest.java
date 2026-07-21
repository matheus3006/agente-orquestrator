package com.orquestrador.preview.server;

import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.runtime.ColetaDeWorkspace;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O GÊMEO do 🔴 que o PR #57 fechou uma camada abaixo — mesma mecânica, outro módulo.
 *
 * <p>{@code waitPortAlive} faz exatamente o que a fatia anterior condenou: repõe a flag de
 * interrupção e volta. E o ramo de falha de {@code startFor} faz a limpeza DEPOIS, com a flag já
 * posta — e toda limpeza aqui é subprocesso ({@code docker logs}, {@code docker rm -f} pelo nome,
 * ambos via {@code exec} → {@code ProcessoComPrazo.aguardar}). Espera por subprocesso com a flag
 * posta lança NA HORA: o tail vira {@code "null"} na mensagem devolvida ao board, e o
 * {@code rm -f} devolve {@code exit -1} com o <b>container de preview vivo e órfão</b> — cujo
 * retorno {@code PreviewServerStop.failure} o {@code startFor} ainda por cima ignorava.
 *
 * <p>Alcançável: a thread de requisição do Tomcat leva {@code shutdownNow} no shutdown, e a janela é
 * o {@code start-poll-ms} inteiro (10s por default) de um preview que falha ao subir. O
 * {@code reapOnce} MITIGA, não fecha — o órfão só é ceifado no próximo boot, depois do TTL, e só se
 * o backend voltar.
 *
 * <p><b>A asserção é o EFEITO, não a chamada</b> — foi aí que o #56 caiu: o alvo roteirizado faz uma
 * derrubada de verdade (subprocesso real, espera real) e o teste cobra o {@code exit} REAL que ela
 * colheu. Um double que só marcasse "fui chamado" ficaria verde dos dois lados do conserto.
 */
class PreviewServerLimpezaSobInterrupcaoTest {

    /** O que o {@code docker rm -f} devolve quando a derrubada roda até o fim — literal independente. */
    private static final int EXIT_DA_DERRUBADA_COMPLETA = 9;

    @Test
    void interrupcaoNoPollDaPortaNaoMataALimpezaDoPreviewQueFalhouAoSubir() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();

        PreviewServerManifestRepository manifests = mock(PreviewServerManifestRepository.class);
        when(manifests.findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId))
                .thenReturn(Optional.of(new PreviewServerManifest(UUID.randomUUID(), companyId,
                        issueId, agentId, UUID.randomUUID(), "npm run dev", portaQueNinguemAtende())));

        Agent agent = new Agent(agentId, companyId, "A", "worker", null);
        agent.configureRuntime("process", "npm run dev", "DOCKER");
        AgentRepository agents = mock(AgentRepository.class);
        when(agents.findById(agentId)).thenReturn(Optional.of(agent));

        AlvoQueDerrubaDeVerdade alvo = new AlvoQueDerrubaDeVerdade(companyId, agentId, issueId);
        ExecutionTargets targets = mock(ExecutionTargets.class);
        when(targets.forType(any())).thenReturn(alvo);

        WorkspaceResolver workspaces = mock(WorkspaceResolver.class);
        when(workspaces.resolve(any(), any(), any())).thenReturn(Path.of("/tmp/ws-do-teste"));

        PreviewServerSupervisor supervisor = new PreviewServerSupervisor(manifests, agents, targets,
                mock(AdapterRegistry.class, RETURNS_DEEP_STUBS), workspaces,
                mock(ActivityLogService.class), mock(ColetaDeWorkspace.class),
                900, 10_000, "512m", "1", "256");

        PreviewServerSupervisor.StartResult resultado;
        boolean flagRestaurada;
        try {
            resultado = supervisor.startFor(companyId, issueId, null);
        } finally {
            // Medir e limpar: a thread do JUnit é reusada pelos testes seguintes.
            flagRestaurada = Thread.interrupted();
        }

        assertThat(alvo.exitDaDerrubada)
                .as("a derrubada do container de preview tem de RODAR ATÉ O FIM e colher o exit real"
                        + " — morrer na flag do chamador deixa o container vivo e órfão")
                .hasValue(EXIT_DA_DERRUBADA_COMPLETA);
        assertThat(resultado.error())
                .as("o board tem de receber as últimas linhas do log, não o 'null' de uma espera que"
                        + " morreu antes de colher a saída")
                .contains("EADDRINUSE porta ja ocupada")
                .doesNotContain("null");
        assertThat(flagRestaurada)
                .as("limpar a flag para a limpeza rodar não é engolir a interrupção: ela volta"
                        + " depois, e quem chamou continua sabendo que o shutdown está em curso")
                .isTrue();
    }

    private static int portaQueNinguemAtende() throws IOException {
        try (ServerSocket livre = new ServerSocket(0)) {
            return livre.getLocalPort();
        }
    }

    /**
     * O molde do alvo DOCKER, com as duas operações da limpeza feitas por subprocesso REAL — que é o
     * que as torna sensíveis à flag do chamador. Na 2ª listagem (a de dentro do poll da porta) ele
     * interrompe a thread: o {@code shutdownNow} chegando no meio da espera, sem corrida nenhuma.
     */
    private static final class AlvoQueDerrubaDeVerdade implements ExecutionTarget {

        private final List<PreviewServerLive> vivo;
        private final AtomicInteger listagens = new AtomicInteger();
        final AtomicInteger exitDaDerrubada = new AtomicInteger(-1);

        AlvoQueDerrubaDeVerdade(UUID companyId, UUID agentId, UUID issueId) {
            this.vivo = List.of(new PreviewServerLive(
                    PreviewServerSupervisor.serverNameFor(companyId, agentId, issueId),
                    companyId, agentId, issueId, 1));
        }

        @Override
        public Type type() {
            return Type.DOCKER;
        }

        @Override
        public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
            return PreviewServerStart.started(spec.port());
        }

        @Override
        public List<PreviewServerLive> listPreviewServers() {
            if (listagens.incrementAndGet() == 1) {
                return List.of(); // a checagem de idempotência do startFor: nada vivo ainda
            }
            // A 2ª é a de dentro do `waitPortAlive`; o servidor AINDA está no runtime (não é morte
            // precoce), e é aqui que o shutdown chega.
            Thread.currentThread().interrupt();
            return vivo;
        }

        @Override
        public String previewServerLogTail(String serverName, int maxLines) {
            try {
                Process logs = new ProcessBuilder("sh", "-c",
                        "sleep 0.2; echo EADDRINUSE porta ja ocupada").start();
                String saida = new String(logs.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                logs.waitFor();
                return saida.trim();
            } catch (InterruptedException morreuNaFlagDoChamador) {
                Thread.currentThread().interrupt();
                // O `exec` do alvo real devolve `String.valueOf(excecao.getMessage())` — literalmente
                // a string "null" para uma InterruptedException sem mensagem.
                return String.valueOf(morreuNaFlagDoChamador.getMessage());
            } catch (IOException falhaDeSpawn) {
                return "";
            }
        }

        @Override
        public PreviewServerStop stopPreviewServer(String serverName) {
            try {
                // O `docker rm -f <nome>`: um subprocesso cuja ESPERA é o que morre com a flag posta.
                // O sleep garante que ele ainda está vivo quando o `waitFor` é chamado — sem isso o
                // RED viraria corrida com o spawn.
                Process removeContainer = new ProcessBuilder("sh", "-c",
                        "sleep 0.3; exit " + EXIT_DA_DERRUBADA_COMPLETA).start();
                try {
                    exitDaDerrubada.set(removeContainer.waitFor());
                } catch (InterruptedException morreuNaFlagDoChamador) {
                    removeContainer.destroyForcibly();
                    Thread.currentThread().interrupt();
                    return PreviewServerStop.failure("derrubada de " + serverName + " falhou (exit -1)");
                }
                return PreviewServerStop.OK;
            } catch (IOException falhaDeSpawn) {
                return PreviewServerStop.failure(falhaDeSpawn.getMessage());
            }
        }

        @Override
        public Result run(Spec spec, UUID runId, Consumer<String> onStdoutLine) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
            throw new UnsupportedOperationException();
        }
    }
}
