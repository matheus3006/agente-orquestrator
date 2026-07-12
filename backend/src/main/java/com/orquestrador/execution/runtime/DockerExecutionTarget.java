package com.orquestrador.execution.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Executa num container Docker isolado (alvo canonico). Monta o Workspace como volume em
 * /workspace. A construcao do argv ({@link #buildArgs}) e testada sem precisar do daemon.
 */
@Component
public class DockerExecutionTarget implements ExecutionTarget {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutionTarget.class);

    @Override
    public Type type() {
        return Type.DOCKER;
    }

    /** O container E o sandbox externo (ADR-0023) — o motor pode dispensar o self-sandbox (F.11/ADR-0025). */
    @Override
    public IsolationPosture posture() {
        return IsolationPosture.EXTERNALLY_SANDBOXED;
    }

    /**
     * O nome DETERMINISTICO do container de um Run (F-N4/ADR-0040): endereca o {@code docker kill}/
     * {@code rm -f} do interrupt sem depender de rastrear o processo do HOST (o cliente `docker run`
     * ignora SIGKILL — nao repassa ao container; so o nome alcanca o container de verdade).
     */
    static String containerName(UUID runId) {
        return "orq_run_" + runId;
    }

    /** argv do `docker run` — puro e testavel sem executar o daemon. */
    public List<String> buildArgs(Spec spec, UUID runId) {
        List<String> argv = new ArrayList<>(
                List.of("docker", "run", "--rm", "--name", containerName(runId), "-w", "/workspace"));
        argv.add("-v");
        argv.add(spec.volumeName() + ":/workspace");
        for (VolumeMount credentialMount : spec.credentialMounts()) {
            argv.add("-v");
            argv.add(credentialMount.volumeName() + ":" + credentialMount.containerPath());
        }
        if (spec.env() != null) {
            spec.env().forEach((envName, envValue) -> {
                argv.add("-e");
                argv.add(envName + "=" + envValue);
            });
        }
        argv.add(spec.image() != null ? spec.image() : "alpine:3");
        argv.addAll(spec.command());
        return argv;
    }

    /**
     * argv do leitor de workspace (F.9/ADR-0021) — hermetico POR CONSTRUCAO: {@code --network none},
     * volume {@code :ro} e NENHUM volume de credencial (mesmo que o Spec os carregue). A imagem e a
     * do proprio adapter (garantidamente presente: o run acabou de rodar nela). Puro e testavel
     * sem daemon, como {@link #buildArgs}.
     */
    public List<String> buildReadArgs(Spec spec, String relativePath) {
        return List.of("docker", "run", "--rm", "--network", "none",
                "-v", spec.volumeName() + ":/workspace:ro",
                spec.image() != null ? spec.image() : "alpine:3",
                "cat", "/workspace/" + relativePath);
    }

    /**
     * argv do escritor de workspace — espelho do leitor ({@link #buildReadArgs}): hermetico POR
     * CONSTRUCAO ({@code --network none}, NENHUM volume de credencial mesmo que o Spec os carregue),
     * imagem do proprio adapter. REPLACE por subtree: remove cada contrato do volume e, quando ha
     * arvore materializada no host, extrai o tar recebido no stdin ({@code -i}) — rm e extract no
     * MESMO sh, entao extract falho depois do rm vira exit != 0 (falha legivel do run), nunca um
     * workspace pela metade rodando em silencio. Puro e testavel sem daemon.
     */
    public List<String> buildWriteArgs(Spec spec, List<String> relativePaths, boolean hasMaterializedTree) {
        StringBuilder replaceShell = new StringBuilder("rm -rf");
        for (String relativePath : relativePaths) {
            replaceShell.append(" /workspace/").append(relativePath);
        }
        if (hasMaterializedTree) {
            replaceShell.append(" && tar -xf - -C /workspace");
        }
        List<String> argv = new ArrayList<>(List.of("docker", "run", "--rm"));
        if (hasMaterializedTree) {
            argv.add("-i");
        }
        argv.addAll(List.of("--network", "none",
                "-v", spec.volumeName() + ":/workspace",
                spec.image() != null ? spec.image() : "alpine:3",
                "sh", "-c", replaceShell.toString()));
        return argv;
    }

    /** O lado host do escritor: empacota os subtrees EXISTENTES relativos ao workDir (stdout = tar). */
    public List<String> buildTarArgs(Path workDir, List<String> materializedPaths) {
        List<String> argv = new ArrayList<>(List.of("tar", "-cf", "-", "-C", workDir.toString()));
        argv.addAll(materializedPaths);
        return argv;
    }

    /**
     * O processo do tar do host, pronto para iniciar — alem do argv ({@link #buildTarArgs}),
     * carrega {@code COPYFILE_DISABLE=1}: sem ele o bsdtar do macOS (host de dev) empacota
     * metadados AppleDouble ({@code ._SKILL.md}…) que sujariam o workspace do agente a cada run
     * (achado do smoke desta fatia). No GNU tar do Linux o env e inocuo.
     */
    public ProcessBuilder hostTar(Path workDir, List<String> materializedPaths) {
        ProcessBuilder hostTar = new ProcessBuilder(buildTarArgs(workDir, materializedPaths));
        hostTar.environment().put("COPYFILE_DISABLE", "1");
        return hostTar;
    }

    /**
     * 1 container efemero de escrita por Run ({@link #buildWriteArgs}), alimentado pelo tar do
     * host ({@link #buildTarArgs}) — o filtro de existencia e AQUI: tar com membro ausente
     * falharia o pipe inteiro, e ausente-no-host significa "remover do volume" (replace).
     */
    @Override
    public WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths) {
        try {
            List<String> materialized = relativePaths.stream()
                    .filter(relativePath -> Files.exists(spec.workDir().resolve(relativePath)))
                    .toList();
            ProcessBuilder writerContainer = new ProcessBuilder(
                    buildWriteArgs(spec, relativePaths, !materialized.isEmpty()));
            writerContainer.redirectErrorStream(true);
            Process writer = writerContainer.start();
            if (!materialized.isEmpty()) {
                Process tarProcess = hostTar(spec.workDir(), materialized).start();
                try (InputStream payload = tarProcess.getInputStream();
                     OutputStream containerStdin = writer.getOutputStream()) {
                    payload.transferTo(containerStdin);
                }
                String tarError = new String(tarProcess.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (tarProcess.waitFor() != 0) {
                    writer.destroyForcibly();
                    return WorkspaceFilesWrite.failure(
                            "tar da arvore materializada falhou: " + tarError.trim());
                }
            }
            String output = new String(writer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = writer.waitFor();
            if (exitCode != 0) {
                return WorkspaceFilesWrite.failure(
                        "escritor docker falhou (exit " + exitCode + "): " + output.trim());
            }
            return WorkspaceFilesWrite.OK;
        } catch (Exception exception) {
            return WorkspaceFilesWrite.failure("escritor docker falhou: " + exception.getMessage());
        }
    }

    /**
     * 1 container efemero de leitura por captura ({@link #buildReadArgs}). stderr fica SEPARADO do
     * stdout: e ele que distingue "arquivo nao existe" (o {@code No such file} do cat = run normal)
     * de falha real (daemon fora, volume sumido — motivo legivel). O symlink-escape e inocuo aqui
     * por construcao: o container so enxerga o volume.
     */
    @Override
    public WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes) {
        try {
            Process reader = new ProcessBuilder(buildReadArgs(spec, relativePath)).start();
            byte[] bytes = readUpTo(reader.getInputStream(), maxBytes);
            if (bytes == null) {
                reader.destroyForcibly();
                return WorkspaceFileRead.capExceeded(
                        "arquivo excede o cap de " + maxBytes + " bytes — nunca truncado");
            }
            String stderr = new String(reader.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = reader.waitFor();
            if (exitCode == 0) {
                return WorkspaceFileRead.found(bytes);
            }
            if (stderr.contains("No such file or directory")) {
                return WorkspaceFileRead.ABSENT;
            }
            return WorkspaceFileRead.failure(
                    "leitor docker falhou (exit " + exitCode + "): " + stderr.trim());
        } catch (Exception exception) {
            return WorkspaceFileRead.failure("leitor docker falhou: " + exception.getMessage());
        }
    }

    /** Copia ate {@code maxBytes}; estourou = {@code null} (o chamador transforma em falha legivel). */
    private static byte[] readUpTo(InputStream stream, long maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > maxBytes) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** A rede do preview: porta publicada funciona (subnet local), internet NAO (NAT desligado). */
    static final String PREVIEW_NETWORK = "orq-preview";

    /**
     * argv do 4º container da familia (F.10/ADR-0023) — desanexado ({@code -d}), nome
     * deterministico + labels (o reaper varre por elas), rede {@code orq-preview}, volume
     * {@code :ro} + tmpfs em {@code /tmp}, caps do Spec, porta EFEMERA no loopback. SEM
     * credencial POR CONSTRUCAO: o {@link PreviewServerSpec} nem carrega mounts. Puro e
     * testavel sem daemon, como {@link #buildArgs}.
     */
    public List<String> buildServerArgs(PreviewServerSpec spec) {
        return List.of("docker", "run", "-d", "--name", spec.serverName(),
                "--label", "orq.preview=true",
                "--label", "orq.company=" + spec.companyId(),
                "--label", "orq.agent=" + spec.agentId(),
                "--label", "orq.issue=" + spec.issueId(),
                "--network", PREVIEW_NETWORK,
                "-v", spec.volumeName() + ":/workspace:ro",
                "--tmpfs", "/tmp",
                "-w", "/workspace",
                "--memory", spec.memory(), "--cpus", spec.cpus(), "--pids-limit", spec.pidsLimit(),
                "-p", "127.0.0.1::" + spec.port(),
                spec.image() != null ? spec.image() : "alpine:3",
                "sh", "-c", spec.command());
    }

    /** A lida de volta da porta efemera: o SO alocou, o daemon conta ({@code docker port}). */
    public List<String> buildServerPortArgs(String serverName, int containerPort) {
        return List.of("docker", "port", serverName, containerPort + "/tcp");
    }

    public List<String> buildServerStopArgs(String serverName) {
        return List.of("docker", "rm", "-f", serverName);
    }

    public List<String> buildServerLogTailArgs(String serverName, int maxLines) {
        return List.of("docker", "logs", "--tail", String.valueOf(maxLines), serverName);
    }

    /** O arquivo do shot dentro do container efemero — inerte, some com o {@code --rm}. */
    static final String SHOT_TMP = "/tmp/s.png";

    /**
     * argv do 5º container da familia (F.13/ADR-0027) — hermetico e efemero ({@code --rm}), SEM
     * credencial POR CONSTRUCAO (o {@link ScreenshotSpec} nem carrega mounts), na rede
     * {@code orq-preview}: alcanca o servidor irmao pelo NOME do container na porta DO MANIFEST
     * ({@code http://<serverName>:<containerPort>/}), nunca a efemera do host. O Chromium headless
     * fotografa a URL e devolve os bytes do PNG pelo STDOUT (molde do leitor da 0021: {@code ... &&
     * cat}); o {@code --virtual-time-budget} cobre "esperar a SPA renderizar" sem Playwright. Os
     * flags {@code --no-sandbox}/{@code --disable-dev-shm-usage} sao a defesa de rodar como root em
     * container (deep-research F.13). A imagem-shot reseta o ENTRYPOINT e expoe {@code chromium} no
     * PATH — por isso o argv e o mesmo {@code sh -c} do leitor. Puro e testavel sem daemon.
     */
    public List<String> buildScreenshotArgs(ScreenshotSpec spec) {
        String url = "http://" + spec.serverName() + ":" + spec.containerPort() + "/";
        String shot = "chromium --headless --no-sandbox --disable-gpu --disable-dev-shm-usage"
                + " --hide-scrollbars"
                + " --window-size=" + spec.windowWidth() + "," + spec.windowHeight()
                + " --virtual-time-budget=" + spec.virtualTimeBudgetMs()
                + " --screenshot=" + SHOT_TMP + " " + url
                + " && cat " + SHOT_TMP;
        return List.of("docker", "run", "--rm", "--network", PREVIEW_NETWORK,
                spec.shotImage(), "sh", "-c", shot);
    }

    /** Runtime como fonte da verdade (ADR-0023 §8): vivo/porta/identidade saem do daemon por label. */
    public List<String> buildServerListArgs() {
        return List.of("docker", "ps", "--filter", "label=orq.preview=true",
                "--format",
                "{{.Names}}\t{{.Label \"orq.company\"}}\t{{.Label \"orq.agent\"}}\t{{.Label \"orq.issue\"}}\t{{.Ports}}");
    }

    public List<String> buildNetworkInspectArgs() {
        return List.of("docker", "network", "inspect", PREVIEW_NETWORK);
    }

    public List<String> buildNetworkCreateArgs() {
        return List.of("docker", "network", "create",
                "-o", "com.docker.network.bridge.enable_ip_masquerade=false",
                PREVIEW_NETWORK);
    }

    /** Saida do {@code docker port} — a linha do loopback IPv4 e a que vale (IPv6 vem junto as vezes). */
    static Integer parseHostPort(String portOutput) {
        for (String line : portOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("127.0.0.1:")) {
                try {
                    return Integer.parseInt(trimmed.substring("127.0.0.1:".length()));
                } catch (NumberFormatException ignored) {
                    // linha malformada: tenta a proxima
                }
            }
        }
        return null;
    }

    /** Saida do {@code docker ps} formatado — linha que nao parseia e ignorada (lixo nao derruba a supervisao). */
    static List<PreviewServerLive> parseServerList(String listOutput) {
        List<PreviewServerLive> vivos = new ArrayList<>();
        for (String line : listOutput.split("\n")) {
            String[] fields = line.split("\t");
            if (fields.length != 5) {
                continue;
            }
            java.util.regex.Matcher hostPort =
                    java.util.regex.Pattern.compile("127\\.0\\.0\\.1:(\\d+)->").matcher(fields[4]);
            if (!hostPort.find()) {
                continue;
            }
            try {
                vivos.add(new PreviewServerLive(fields[0], java.util.UUID.fromString(fields[1]),
                        java.util.UUID.fromString(fields[2]), java.util.UUID.fromString(fields[3]),
                        Integer.parseInt(hostPort.group(1))));
            } catch (IllegalArgumentException ignored) {
                // identidade malformada: nao e um servidor nosso
            }
        }
        return vivos;
    }

    @Override
    public PreviewServerStart startPreviewServer(PreviewServerSpec spec) {
        Executed network = ensurePreviewNetwork();
        if (network != null && network.exitCode() != 0) {
            return PreviewServerStart.failure(
                    "rede " + PREVIEW_NETWORK + " indisponivel: " + network.output().trim());
        }
        Executed run = exec(buildServerArgs(spec));
        if (run.exitCode() != 0) {
            return PreviewServerStart.failure(
                    "subida do servidor falhou (exit " + run.exitCode() + "): " + run.output().trim());
        }
        Executed port = exec(buildServerPortArgs(spec.serverName(), spec.port()));
        Integer hostPort = port.exitCode() == 0 ? parseHostPort(port.output()) : null;
        if (hostPort == null) {
            // Container subiu mas a porta nao foi publicada como esperado: derruba para nao orfa.
            exec(buildServerStopArgs(spec.serverName()));
            return PreviewServerStart.failure(
                    "porta efemera nao publicada para " + spec.serverName() + ": " + port.output().trim());
        }
        return PreviewServerStart.started(hostPort);
    }

    @Override
    public PreviewServerStop stopPreviewServer(String serverName) {
        Executed stop = exec(buildServerStopArgs(serverName));
        if (stop.exitCode() == 0 || stop.output().contains("No such container")) {
            return PreviewServerStop.OK;
        }
        return PreviewServerStop.failure(
                "derrubada de " + serverName + " falhou (exit " + stop.exitCode() + "): "
                        + stop.output().trim());
    }

    @Override
    public List<PreviewServerLive> listPreviewServers() {
        Executed list = exec(buildServerListArgs());
        if (list.exitCode() != 0) {
            log.warn("listagem de servidores de preview falhou (exit {}): {}",
                    list.exitCode(), list.output().trim());
            return List.of();
        }
        return parseServerList(list.output());
    }

    @Override
    public String previewServerLogTail(String serverName, int maxLines) {
        return exec(buildServerLogTailArgs(serverName, maxLines)).output().trim();
    }

    /**
     * 1 container-shot efemero por captura ({@link #buildScreenshotArgs}). O stdout carrega o PNG
     * BINARIO — por isso o stderr fica SEPARADO (o chromium loga la; combinar corromperia os bytes),
     * drenado num thread para nao deadlockar se o browser logar muito. Cap estourado = {@code null}
     * do {@code readUpTo} -> CAP_EXCEEDED (nunca trunca). O resto e classificado a partir do exit +
     * bytes + stderr ({@link ThumbnailCapture#classify}). Best-effort: qualquer excecao vira falha
     * legivel, nunca sobe pro capturador.
     */
    @Override
    public ThumbnailCapture screenshotServer(ScreenshotSpec spec) {
        try {
            Process shot = new ProcessBuilder(buildScreenshotArgs(spec)).start();
            StringBuilder stderr = new StringBuilder();
            Thread drain = new Thread(() -> {
                try {
                    stderr.append(new String(shot.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // stderr ilegivel nao invalida um stdout com o PNG
                }
            });
            drain.start();
            byte[] png = readUpTo(shot.getInputStream(), spec.maxBytes());
            if (png == null) {
                shot.destroyForcibly();
                return ThumbnailCapture.capExceeded(
                        "PNG excede o cap de " + spec.maxBytes() + " bytes — nunca truncado");
            }
            int exitCode = shot.waitFor();
            drain.join(1000);
            return ThumbnailCapture.classify(exitCode, png, stderr.toString(), false);
        } catch (Exception exception) {
            return ThumbnailCapture.renderFailed("shot docker falhou: " + exception.getMessage());
        }
    }

    // ===== F.12 (ADR-0026): GC de workspaces — o mundo dos volumes por-Issue e a remocao =====

    /** Lista o MUNDO filtrado pelo namespace {@code orq_}; o formato por-Issue e refinado no parse. */
    public List<String> buildVolumeListArgs() {
        return List.of("docker", "volume", "ls", "--filter", "name=orq_", "--format", "{{.Name}}");
    }

    public List<String> buildVolumeRemoveArgs(String volumeName) {
        return List.of("docker", "volume", "rm", volumeName);
    }

    /**
     * Do output do {@code docker volume ls} mantem SO o que casa o formato de workspace por-Issue
     * (denylist ESTRUTURAL): creds/pgdata/workspace-de-agente caem fora; o orfao sintetico casa o
     * formato e entra (vira WARN no reaper). Linha em branco ignorada. Puro e testavel sem daemon.
     */
    static List<String> parseIssueVolumes(String listOutput, java.util.function.Predicate<String> isIssueVolume) {
        List<String> volumes = new ArrayList<>();
        for (String line : listOutput.split("\n")) {
            String name = line.trim();
            if (!name.isEmpty() && isIssueVolume.test(name)) {
                volumes.add(name);
            }
        }
        return volumes;
    }

    /**
     * Classifica o {@code docker volume rm} nos 4 estados do reaper. "no such volume" = sumiu
     * entre listar e remover (ABSENT); "volume is in use" = montado por um PreviewServer vivo
     * (IN_USE, skip); qualquer outro exit != 0 = falha legivel (FAILED). Puro, sem daemon.
     */
    static IssueWorkspaceRemoval classifyVolumeRemoval(int exitCode, String output) {
        if (exitCode == 0) {
            return IssueWorkspaceRemoval.REMOVED;
        }
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("no such volume")) {
            return IssueWorkspaceRemoval.ABSENT;
        }
        if (lower.contains("volume is in use")) {
            return IssueWorkspaceRemoval.IN_USE;
        }
        return IssueWorkspaceRemoval.FAILED;
    }

    @Override
    public List<String> listIssueWorkspaces(WorkspaceResolver resolver) {
        Executed list = exec(buildVolumeListArgs());
        if (list.exitCode() != 0) {
            log.warn("listagem de volumes de workspace falhou (exit {}): {}",
                    list.exitCode(), list.output().trim());
            return List.of();
        }
        return parseIssueVolumes(list.output(), resolver::isIssueVolumeName);
    }

    @Override
    public IssueWorkspaceRemoval removeIssueWorkspace(WorkspaceResolver resolver, java.util.UUID companyId,
                                                      java.util.UUID agentId, java.util.UUID issueId) {
        String volumeName = resolver.volumeName(companyId, agentId, issueId);
        Executed removal = exec(buildVolumeRemoveArgs(volumeName));
        IssueWorkspaceRemoval outcome = classifyVolumeRemoval(removal.exitCode(), removal.output());
        if (outcome == IssueWorkspaceRemoval.IN_USE || outcome == IssueWorkspaceRemoval.FAILED) {
            log.warn("remocao do volume {} nao concluiu ({}): {}",
                    volumeName, outcome, removal.output().trim());
        }
        return outcome;
    }

    /**
     * Bootstrap IDEMPOTENTE da rede (ADR-0023 §4): inspect achou = nada a fazer ({@code null});
     * nao achou = create — corrida com outro start e inocua ("already exists" vira sucesso).
     */
    private Executed ensurePreviewNetwork() {
        if (exec(buildNetworkInspectArgs()).exitCode() == 0) {
            return null;
        }
        Executed create = exec(buildNetworkCreateArgs());
        if (create.exitCode() != 0 && create.output().contains("already exists")) {
            return null;
        }
        return create;
    }

    private record Executed(int exitCode, String output) {
    }

    /** Roda um argv curto capturando saida combinada (stdout+stderr) — o transporte dos comandos acima. */
    private Executed exec(List<String> argv) {
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Executed(process.waitFor(), output);
        } catch (Exception exception) {
            return new Executed(-1, String.valueOf(exception.getMessage()));
        }
    }

    @Override
    public Result run(Spec spec, UUID runId, Consumer<String> onStdoutLine) {
        try {
            ProcessBuilder dockerProcess = new ProcessBuilder(buildArgs(spec, runId));
            dockerProcess.redirectErrorStream(true);
            Process process = dockerProcess.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onStdoutLine.accept(line);
                }
            }
            return new Result(process.waitFor(), null);
        } catch (Exception exception) {
            return new Result(-1, exception.getMessage());
        }
    }

    /**
     * argv do interrupt (F-N4/ADR-0040) — molde EXATO de {@link #buildServerStopArgs}: {@code rm -f}
     * mata (SIGKILL) e remove num so passo, idempotente ("no such container" tambem e OK). Nao
     * precisa rastrear processo do HOST: o PID 1 do container e o proprio motor, entao a
     * derrubada do container tira a namespace de PID inteira com ele — os filhos da tool call
     * (o requisito duro da sonda R6-B) morrem juntos, sem precisar enumerar descendentes como o
     * LOCAL faz.
     */
    public List<String> buildInterruptArgs(UUID runId) {
        return List.of("docker", "rm", "-f", containerName(runId));
    }

    @Override
    public boolean interrupt(UUID runId) {
        Executed removal = exec(buildInterruptArgs(runId));
        return removal.exitCode() == 0 || removal.output().contains("No such container");
    }
}
