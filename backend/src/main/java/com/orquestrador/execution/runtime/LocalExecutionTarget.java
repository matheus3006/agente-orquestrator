package com.orquestrador.execution.runtime;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Executa como subprocesso local no diretorio do Workspace. Driver de DEV (ADR-0001):
 * sem isolamento, mas acelera testes. Em producao o alvo canonico e o DOCKER.
 */
@Component
public class LocalExecutionTarget implements ExecutionTarget {

    @Override
    public Type type() {
        return Type.LOCAL;
    }

    /**
     * Read no diretorio real com real-path check (F.9/ADR-0021): o LOCAL roda no host, entao um
     * symlink plantado pelo agente apontando para fora do workspace (ex.: ~/.ssh) seria lido com
     * as permissoes do operador — o {@code toRealPath()} resolve a cadeia inteira e o prefixo do
     * workspace real e exigido ANTES de qualquer byte ser lido. No DOCKER o escape e inocuo por
     * construcao (o container so ve o volume).
     */
    @Override
    public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
        try {
            Path workspaceReal = spec.workDir().toRealPath();
            Path candidate = spec.workDir().resolve(relativePath);
            if (!Files.exists(candidate)) {
                return WorkspaceFileRead.ABSENT;
            }
            Path candidateReal = candidate.toRealPath();
            if (!candidateReal.startsWith(workspaceReal)) {
                return WorkspaceFileRead.failure(
                        "leitura recusada: " + relativePath + " escapa do workspace (symlink para "
                                + candidateReal + ")");
            }
            // Nao-regular (FIFO/device) bloquearia o read para sempre e penduraria settle+lock —
            // recusa legivel ANTES de qualquer byte (achado da revisao adversarial F.9).
            if (!Files.isRegularFile(candidateReal, LinkOption.NOFOLLOW_LINKS)) {
                return WorkspaceFileRead.failure(
                        "leitura recusada: " + relativePath + " nao e arquivo regular");
            }
            long size = Files.size(candidateReal);
            if (size > maxBytes) {
                return WorkspaceFileRead.capExceeded(
                        "arquivo excede o cap de " + maxBytes + " bytes (" + size + " bytes) — nunca truncado");
            }
            return WorkspaceFileRead.found(Files.readAllBytes(candidateReal));
        } catch (NoSuchFileException exception) {
            return WorkspaceFileRead.ABSENT;
        } catch (IOException exception) {
            return WorkspaceFileRead.failure("falha lendo " + relativePath + ": " + exception.getMessage());
        }
    }

    /**
     * IDENTIDADE: no LOCAL o workDir E o workspace do Run — os materializers ja escreveram no
     * lugar; nao ha travessia a fazer. (No DOCKER e onde a arvore atravessa para o volume.)
     */
    @Override
    public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
        return WorkspaceFilesWrite.OK;
    }

    /**
     * Registro EM MEMORIA dos servidores de preview (F.10/ADR-0023, ressalva LOCAL): dev-only —
     * nao sobrevive a restart do backend (sem reaper de PID orfao; {@link #destroyAllPreviewServers}
     * derruba tudo no shutdown). Entrada morta fica ate o stop: o tail pos-morte e a materia-prima
     * da falha legivel de subida.
     */
    private final Map<String, LocalPreviewServer> previewServers = new ConcurrentHashMap<>();

    private record LocalPreviewServer(Process process, PreviewServerSpec spec, Path outputFile) {
    }

    /**
     * Spawn do comando do manifest no workDir — mesma classe de risco do run LOCAL (ADR-0016,
     * dev-only). A porta do host E a do manifest (sem remapeamento: o app escuta direto no
     * loopback do operador). Nome ja vivo = falha legivel, como o conflito de nome do daemon —
     * a idempotencia (reusar o vivo) e decisao do modulo preview.server, nao do alvo.
     */
    @Override
    public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
        LocalPreviewServer existente = previewServers.get(spec.serverName());
        if (existente != null && existente.process().isAlive()) {
            return PreviewServerStart.failure("servidor ja vivo: " + spec.serverName());
        }
        try {
            Path outputFile = Files.createTempFile("orq-preview-" + spec.serverName(), ".log");
            ProcessBuilder serverProcess = new ProcessBuilder("sh", "-c", spec.command());
            serverProcess.directory(spec.workDir().toFile());
            serverProcess.redirectErrorStream(true);
            serverProcess.redirectOutput(outputFile.toFile());
            previewServers.put(spec.serverName(),
                    new LocalPreviewServer(serverProcess.start(), spec, outputFile));
            return PreviewServerStart.started(spec.port());
        } catch (IOException exception) {
            return PreviewServerStart.failure(
                    "spawn do servidor local falhou: " + exception.getMessage());
        }
    }

    @Override
    public PreviewServerStop stopPreviewServer(String serverName) {
        LocalPreviewServer servidor = previewServers.remove(serverName);
        if (servidor == null) {
            return PreviewServerStop.OK; // parar o que nao existe e OK (idempotente)
        }
        servidor.process().descendants().forEach(ProcessHandle::destroyForcibly);
        servidor.process().destroyForcibly();
        try {
            Files.deleteIfExists(servidor.outputFile());
        } catch (IOException ignored) {
            // lixo temporario: nao vale falha
        }
        return PreviewServerStop.OK;
    }

    @Override
    public List<PreviewServerLive> listPreviewServers() {
        return previewServers.values().stream()
                .filter(servidor -> servidor.process().isAlive())
                .map(servidor -> new PreviewServerLive(servidor.spec().serverName(),
                        servidor.spec().companyId(), servidor.spec().agentId(),
                        servidor.spec().issueId(), servidor.spec().port()))
                .toList();
    }

    @Override
    public String previewServerLogTail(String serverName, int maxLines) {
        LocalPreviewServer servidor = previewServers.get(serverName);
        if (servidor == null) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(servidor.outputFile(), StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - maxLines), lines.size()));
        } catch (IOException exception) {
            return "";
        }
    }

    /** Dev-only: processo local nao tem label para um reaper re-adotar — o shutdown derruba tudo. */
    @PreDestroy
    public void destroyAllPreviewServers() {
        List.copyOf(previewServers.keySet()).forEach(this::stopPreviewServer);
    }

    /** Escape-hatch para apontar o browser do host (o operador seta no macOS se o auto-detect falhar). */
    static final String LOCAL_BROWSER_PROPERTY = "orquestrador.preview.thumbnail.local-browser";

    /** Candidatos do Chromium/Chrome do host (dev-only, best-effort) — apps do macOS, depois o PATH. */
    private static final List<String> HOST_BROWSER_CANDIDATES = List.of(
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "chromium", "chromium-browser", "google-chrome", "google-chrome-stable");

    /**
     * Fotografa o servidor LOCAL com o Chromium do HOST (F.13/ADR-0027 §B4): um servidor LOCAL binda
     * {@code 127.0.0.1:<hostPort>} (loopback do operador, ADR-0023 §4) — inalcancavel de dentro de um
     * container irmao, por isso o shot roda NO HOST, nao num container (o inverso do DOCKER). Dev-only,
     * best-effort (mesma classe de risco do run/servidor LOCAL): browser ausente -> {@code RENDER_FAILED}
     * (o operador aponta o binario por {@code -D}{@value #LOCAL_BROWSER_PROPERTY}). O PNG vai para um
     * arquivo temporario e volta como bytes; a classificacao e a MESMA do DOCKER
     * ({@link ThumbnailCapture#classify}) — a fiacao muda, o veredicto nao.
     */
    @Override
    public ThumbnailCapture screenshotServer(ScreenshotSpec spec) {
        String browser = resolveHostBrowser();
        if (browser == null) {
            return ThumbnailCapture.renderFailed(
                    "Chromium/Chrome nao encontrado no host (LOCAL e dev-only, best-effort — aponte por -D"
                            + LOCAL_BROWSER_PROPERTY + ")");
        }
        Path shotFile = null;
        try {
            shotFile = Files.createTempFile("orq-shot-", ".png");
            String url = "http://127.0.0.1:" + spec.hostPort() + "/";
            ProcessBuilder browserProcess = new ProcessBuilder(browser, "--headless", "--disable-gpu",
                    "--hide-scrollbars", "--window-size=" + spec.windowWidth() + "," + spec.windowHeight(),
                    "--virtual-time-budget=" + spec.virtualTimeBudgetMs(),
                    "--screenshot=" + shotFile, url);
            // O PNG vai para o arquivo do --screenshot=, nao para o stdout — descarta o stdout para
            // nunca deadlockar; o stderr e drenado num thread (o timeout do waitFor precisa poder disparar).
            browserProcess.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process shot = browserProcess.start();
            StringBuilder stderr = new StringBuilder();
            Thread drain = new Thread(() -> {
                try {
                    stderr.append(new String(shot.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // stderr ilegivel nao invalida um arquivo de screenshot valido
                }
            });
            drain.start();
            if (!shot.waitFor(30, TimeUnit.SECONDS)) {
                shot.destroyForcibly();
                return ThumbnailCapture.renderFailed("shot local excedeu o tempo");
            }
            drain.join(1000);
            int exitCode = shot.exitValue();
            if (!Files.exists(shotFile) || Files.size(shotFile) == 0) {
                return ThumbnailCapture.classify(exitCode, null, stderr.toString(), false);
            }
            if (Files.size(shotFile) > spec.maxBytes()) {
                return ThumbnailCapture.capExceeded(
                        "PNG local excede o cap de " + spec.maxBytes() + " bytes — nunca truncado");
            }
            return ThumbnailCapture.classify(exitCode, Files.readAllBytes(shotFile), stderr.toString(), false);
        } catch (Exception exception) {
            return ThumbnailCapture.renderFailed("shot local falhou: " + exception.getMessage());
        } finally {
            if (shotFile != null) {
                try {
                    Files.deleteIfExists(shotFile);
                } catch (IOException ignored) {
                    // lixo temporario: nao vale falha
                }
            }
        }
    }

    /** O binario do browser: property explicita > apps do macOS > PATH. Ausente = {@code null}. */
    private static String resolveHostBrowser() {
        String configured = System.getProperty(LOCAL_BROWSER_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        for (String candidate : HOST_BROWSER_CANDIDATES) {
            if (candidate.startsWith("/")) {
                if (Files.isExecutable(Path.of(candidate))) {
                    return candidate;
                }
            } else if (onPath(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean onPath(String bareName) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && Files.isExecutable(Path.of(dir, bareName))) {
                return true;
            }
        }
        return false;
    }

    // ===== F.12 (ADR-0026): GC de workspaces — o mundo da arvore e a remocao GUARDADA =====

    /**
     * O MUNDO LOCAL derivado da ARVORE ({@code <root>/<company>/<agent>/<issue>}) — zero parsing de
     * nome: a estrutura de diretorios E o triplo. So workspace por-Issue (segmento folha e UUID);
     * {@code _agent} nao e UUID -> excluido (workspace de agente esta fora, ADR-0026). Cada ref sai
     * como {@link WorkspaceResolver#pathFor}{@code .toString()} — o mesmo que o reaper deriva.
     */
    @Override
    public List<String> listIssueWorkspaces(WorkspaceResolver resolver) {
        Path root = resolver.root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> workspaces = new ArrayList<>();
        try (Stream<Path> companies = Files.list(root)) {
            companies.filter(Files::isDirectory).forEach(companyDir -> {
                try (Stream<Path> agents = Files.list(companyDir)) {
                    agents.filter(Files::isDirectory).forEach(agentDir -> {
                        try (Stream<Path> issues = Files.list(agentDir)) {
                            issues.filter(Files::isDirectory)
                                    .filter(issueDir -> isUuid(issueDir.getFileName().toString()))
                                    .forEach(issueDir -> workspaces.add(issueDir.toString()));
                        } catch (IOException ignored) {
                            // arvore de UM agente ilegivel: nao derruba o sweep dos demais
                        }
                    });
                } catch (IOException ignored) {
                    // idem para UMA company
                }
            });
        } catch (IOException exception) {
            return List.of();
        }
        return workspaces;
    }

    @Override
    public IssueWorkspaceRemoval removeIssueWorkspace(WorkspaceResolver resolver, UUID companyId,
                                                      UUID agentId, UUID issueId) {
        Path candidate = resolver.pathFor(companyId, agentId, issueId);
        if (!Files.exists(candidate)) {
            return IssueWorkspaceRemoval.ABSENT;
        }
        try {
            deleteWorkspaceTree(resolver.root(), candidate);
            return IssueWorkspaceRemoval.REMOVED;
        } catch (SecurityException | IOException exception) {
            // GUARD recusou (jamais deveria, com UUIDs) ou o SO falhou: WARN + skip, nunca excecao.
            org.slf4j.LoggerFactory.getLogger(LocalExecutionTarget.class)
                    .warn("remocao do workspace {} nao concluiu: {}", candidate, exception.getMessage());
            return IssueWorkspaceRemoval.FAILED;
        }
    }

    /**
     * Delete recursivo com GUARD (F.12/ADR-0026): o caminho canonicalizado DEVE estar ESTRITAMENTE
     * sob o root canonico — defesa-em-profundidade contra {@code rm -rf} catastrofico (o root em si
     * e todo caminho que escapa dele sao RECUSADOS com {@link SecurityException}, antes de apagar
     * qualquer byte). Puro e testavel: o invariante e o teste.
     */
    static void deleteWorkspaceTree(Path root, Path candidate) throws IOException {
        Path rootReal = root.toRealPath();
        Path candidateReal = candidate.toRealPath();
        if (candidateReal.equals(rootReal) || !candidateReal.startsWith(rootReal)) {
            throw new SecurityException(
                    "recusa: " + candidateReal + " nao esta estritamente sob o root " + rootReal);
        }
        try (Stream<Path> tree = Files.walk(candidateReal)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
    }

    /** O processo NATIVO vivo de cada runId (F-N4/ADR-0040) — o que {@link #interrupt} mata. */
    private final RunProcessRegistry processes = new RunProcessRegistry();

    @Override
    public Result run(Spec spec, UUID runId, Consumer<String> onStdoutLine) {
        try {
            ProcessBuilder localProcess = new ProcessBuilder(spec.command());
            localProcess.directory(spec.workDir().toFile());
            if (spec.env() != null) {
                localProcess.environment().putAll(spec.env());
            }
            localProcess.redirectErrorStream(true);
            // E3 (F.11/ADR-0025): nenhum motor conversa por stdin (o prompt e argv) — o pipe
            // default ficava aberto e motor que le stdin (`codex exec`) travava o run inteiro.
            localProcess.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            Process process = localProcess.start();
            processes.register(runId, process);
            try {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        onStdoutLine.accept(line);
                    }
                }
                return new Result(process.waitFor(), null);
            } finally {
                processes.unregister(runId);
            }
        } catch (Exception exception) {
            return new Result(-1, exception.getMessage());
        }
    }

    /** F-N4 (ADR-0040): descendentes primeiro (o filho da tool sobrevive orfao a um SIGKILL
      * so-do-pai, sonda R6-B), depois o processo raiz — molde de {@link #stopPreviewServer}. */
    @Override
    public boolean interrupt(UUID runId) {
        return processes.interrupt(runId);
    }
}
