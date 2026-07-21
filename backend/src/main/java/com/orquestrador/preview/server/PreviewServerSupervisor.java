package com.orquestrador.preview.server;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ColetaDeWorkspace;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O modulo profundo do servidor de preview (F.10/ADR-0023): sabe subir SOB DEMANDA o comando
 * PERSISTIDO do manifest (1 por Issue, start idempotente), esperar a porta responder (poll TCP),
 * transformar morte na subida em falha LEGIVEL (tail do log, espirito CredentialFailureSignal),
 * renovar vida por heartbeat do board (TTL por INATIVIDADE), ceifar sem heartbeat (reaper por
 * label, com grace pos-restart) e derrubar no teardown (checkout de run novo / stop manual) —
 * tudo atras de {@code startFor/stopFor/heartbeat/listLive}. O executor toca UM ponto (teardown
 * no checkout); o resto e superficie do operador (board-only).
 *
 * <p>Estado: declaracao no BANCO (manifest V21) · runtime no DOCKER/registro do alvo (fonte da
 * verdade: {@code listPreviewServers}) · heartbeats EM MEMORIA — pos-restart, vivo desconhecido
 * ganha grace de um TTL inteiro ({@code putIfAbsent} no primeiro avistamento; keepalives do
 * board repovoam sozinhos).
 */
@Component
public class PreviewServerSupervisor {

    private static final Logger log = LoggerFactory.getLogger(PreviewServerSupervisor.class);

    private static final int LOG_TAIL_LINES = 20;

    private final PreviewServerManifestRepository manifests;
    private final AgentRepository agents;
    private final ExecutionTargets targets;
    private final AdapterRegistry adapters;
    private final WorkspaceResolver workspaces;
    private final ActivityLogService activityLog;
    private final ColetaDeWorkspace coletaDeWorkspace;
    private final Duration ttl;
    private final Duration startPoll;
    private final String memory;
    private final String cpus;
    private final String pidsLimit;

    /** Ultimo sinal de vida por serverName — o TTL e por INATIVIDADE, renovado pelo board. */
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();

    public PreviewServerSupervisor(PreviewServerManifestRepository manifests, AgentRepository agents,
                                   ExecutionTargets targets, AdapterRegistry adapters,
                                   WorkspaceResolver workspaces, ActivityLogService activityLog,
                                   ColetaDeWorkspace coletaDeWorkspace,
                                   @Value("${orquestrador.preview.server.ttl-seconds:900}") long ttlSeconds,
                                   @Value("${orquestrador.preview.server.start-poll-ms:10000}") long startPollMs,
                                   @Value("${orquestrador.preview.server.memory:512m}") String memory,
                                   @Value("${orquestrador.preview.server.cpus:1}") String cpus,
                                   @Value("${orquestrador.preview.server.pids-limit:256}") String pidsLimit) {
        this.manifests = manifests;
        this.agents = agents;
        this.targets = targets;
        this.adapters = adapters;
        this.workspaces = workspaces;
        this.activityLog = activityLog;
        this.coletaDeWorkspace = coletaDeWorkspace;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.startPoll = Duration.ofMillis(startPollMs);
        this.memory = memory;
        this.cpus = cpus;
        this.pidsLimit = pidsLimit;
    }

    /** Resultado da subida para o board: URL viva OU motivo legivel (com tail quando morreu ao subir). */
    public record StartResult(String url, Integer hostPort, String error) {

        static StartResult started(int hostPort) {
            return new StartResult("http://127.0.0.1:" + hostPort, hostPort, null);
        }

        static StartResult failure(String error) {
            return new StartResult(null, null, error);
        }

        public boolean failed() {
            return error != null;
        }
    }

    /** Um vivo para a tela Servidores: identidade + porta + quanto TTL resta sem novo heartbeat. */
    public record LiveServer(String serverName, UUID companyId, UUID agentId, UUID issueId,
                             int hostPort, String url, long ttlRemainingSeconds) {
    }

    /**
     * Sobe o servidor da Issue pelo comando PERSISTIDO (o que se declara e o que executa) —
     * IDEMPOTENTE: vivo = renova heartbeat e reusa (1 servidor por Issue). {@code boardUserId}
     * nulo = acao de sistema (sem evento de auditoria; log apenas).
     */
    public StartResult startFor(UUID companyId, UUID issueId, UUID boardUserId) {
        // F.12 (ADR-0026, B4): workspace coletado pelo GC nao volta (DONE nao reabre). Preflight
        // pelo evento duravel: subir aqui montaria um volume VAZIO auto-criado pelo daemon (:ro) —
        // pior que o aviso. O erro viaja o caminho existente (StartResult.failure), front intocado.
        StartResult collected = workspaceCollectedNotice(companyId, issueId);
        if (collected != null) {
            return collected;
        }
        PreviewServerManifest manifest = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId).orElse(null);
        if (manifest == null) {
            return StartResult.failure("nenhum servidor declarado para esta Issue (sem manifest)");
        }
        Agent agent = agents.findById(manifest.getAgentId()).orElse(null);
        if (agent == null) {
            return StartResult.failure("agente do manifest nao existe mais: " + manifest.getAgentId());
        }
        ExecutionTarget target = targets.forType(ExecutionTargets.parse(agent.getExecutionTarget()));

        ExecutionTarget.PreviewServerLive vivo = liveFor(target, companyId, issueId);
        if (vivo != null) {
            lastSeen.put(vivo.serverName(), Instant.now());
            return StartResult.started(vivo.hostPort());
        }

        String serverName = serverNameFor(companyId, manifest.getAgentId(), issueId);
        ExecutionTarget.PreviewServerSpec spec = new ExecutionTarget.PreviewServerSpec(
                serverName,
                workspaces.resolve(companyId, manifest.getAgentId(), issueId),
                workspaces.volumeName(companyId, manifest.getAgentId(), issueId),
                adapters.forType(agent.getAdapterType()).engineContainer().image(),
                manifest.getCommand(), manifest.getPort(),
                companyId, manifest.getAgentId(), issueId,
                memory, cpus, pidsLimit);

        ExecutionTarget.PreviewServerStart start = target.startPreviewServer(spec);
        if (start.failed()) {
            recordStartOutcome(companyId, issueId, boardUserId, null, false);
            return StartResult.failure("servidor nao subiu: " + start.error());
        }
        if (!waitPortAlive(target, serverName, start.hostPort())) {
            // A LIMPEZA RODA COM A FLAG CONSUMIDA, e a flag volta no `finally` (ADR-0050, §A escalação
            // de morte tem de COMPLETAR). Sem isto, o poll interrompido devolvia a flag posta e as
            // duas operações daqui — ambas subprocesso, ambas com espera — morriam NA HORA: o tail
            // virava "null" na mensagem do board e o `docker rm -f` voltava exit -1 com o container
            // VIVO. Mesma mecânica do ramo de `InterruptedException` do `ProcessoComPrazo`, outro
            // módulo. O custo é limitado por construção: as duas chamadas já têm o teto do
            // `comando-curto`, então a parada da aplicação atrasa no máximo isso.
            boolean interrompidoNoPoll = Thread.interrupted();
            try {
                String tail = target.previewServerLogTail(serverName, LOG_TAIL_LINES);
                ExecutionTarget.PreviewServerStop stop = target.stopPreviewServer(serverName);
                if (stop.failed()) {
                    // O retorno era descartado: a única falha de derrubada da classe sem rastro —
                    // e é justamente a que deixa container órfão. `stopFor` e `reapOnce` já logam.
                    log.warn("derrubada do servidor {} que morreu ao subir falhou: {}",
                            serverName, stop.error());
                }
                lastSeen.remove(serverName);
                recordStartOutcome(companyId, issueId, boardUserId, null, false);
                return StartResult.failure("servidor morreu ao subir (porta " + manifest.getPort()
                        + " nunca respondeu)"
                        + (tail.isBlank() ? "" : "\n--- ultimas linhas ---\n" + tail));
            } finally {
                if (interrompidoNoPoll) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        lastSeen.put(serverName, Instant.now());
        recordStartOutcome(companyId, issueId, boardUserId, start.hostPort(), true);
        return StartResult.started(start.hostPort());
    }

    /** Derruba TODO vivo da Issue (manual ou teardown) — best-effort idempotente. */
    public boolean stopFor(UUID companyId, UUID issueId, UUID boardUserId) {
        boolean stopped = false;
        for (ExecutionTarget.Type type : ExecutionTarget.Type.values()) {
            ExecutionTarget target = targets.forType(type);
            for (ExecutionTarget.PreviewServerLive vivo : target.listPreviewServers()) {
                if (!companyId.equals(vivo.companyId()) || !issueId.equals(vivo.issueId())) {
                    continue;
                }
                ExecutionTarget.PreviewServerStop stop = target.stopPreviewServer(vivo.serverName());
                if (stop.failed()) {
                    log.warn("derrubada do servidor {} falhou: {}", vivo.serverName(), stop.error());
                    continue;
                }
                lastSeen.remove(vivo.serverName());
                stopped = true;
                if (boardUserId != null) {
                    recordStopped(companyId, issueId, boardUserId);
                }
            }
        }
        return stopped;
    }

    /**
     * Teardown no CHECKOUT de run novo da mesma Issue (ADR-0023 §3b): o workspace vai mutar — o
     * reviewer nao pode ver um alvo movel. Best-effort LEGIVEL: falha vira log, nunca excecao
     * (o run nao pode morrer porque um preview nao morreu).
     */
    public void teardownOnCheckout(UUID companyId, UUID issueId) {
        try {
            if (stopFor(companyId, issueId, null)) {
                log.info("preview server da issue {} derrubado no checkout de run novo", issueId);
            }
        } catch (RuntimeException exception) {
            log.warn("teardown do preview server no checkout falhou (issue {}): {}",
                    issueId, exception.getMessage());
        }
    }

    /** Renova o TTL do vivo da Issue (keepalive do board com o frame montado). */
    public boolean heartbeat(UUID companyId, UUID issueId) {
        for (ExecutionTarget.Type type : ExecutionTarget.Type.values()) {
            ExecutionTarget.PreviewServerLive vivo = liveFor(targets.forType(type), companyId, issueId);
            if (vivo != null) {
                lastSeen.put(vivo.serverName(), Instant.now());
                return true;
            }
        }
        return false;
    }

    /** Os vivos da company (uniao dos alvos), com quanto TTL resta — a tela Servidores. */
    public List<LiveServer> listLive(UUID companyId) {
        List<LiveServer> live = new java.util.ArrayList<>();
        Instant now = Instant.now();
        for (ExecutionTarget.Type type : ExecutionTarget.Type.values()) {
            for (ExecutionTarget.PreviewServerLive vivo : targets.forType(type).listPreviewServers()) {
                if (!companyId.equals(vivo.companyId())) {
                    continue;
                }
                Instant seen = lastSeen.computeIfAbsent(vivo.serverName(), adopted -> now);
                long remaining = Math.max(0, ttl.minus(Duration.between(seen, now)).toSeconds());
                live.add(new LiveServer(vivo.serverName(), vivo.companyId(), vivo.agentId(),
                        vivo.issueId(), vivo.hostPort(), "http://127.0.0.1:" + vivo.hostPort(),
                        remaining));
            }
        }
        return live;
    }

    /**
     * O reaper (agendado ~60s): mata quem passou um TTL inteiro sem heartbeat. Vivo DESCONHECIDO
     * (pos-restart do backend: heartbeats moram em memoria) e ADOTADO com grace de um TTL —
     * {@code putIfAbsent} no primeiro avistamento; se o board estiver olhando, o keepalive renova
     * antes do grace vencer. E o que garante "restart nao orfa container".
     */
    public void reapOnce() {
        Instant now = Instant.now();
        for (ExecutionTarget.Type type : ExecutionTarget.Type.values()) {
            ExecutionTarget target = targets.forType(type);
            for (ExecutionTarget.PreviewServerLive vivo : target.listPreviewServers()) {
                Instant seen = lastSeen.computeIfAbsent(vivo.serverName(), adopted -> now);
                if (Duration.between(seen, now).compareTo(ttl) <= 0) {
                    continue;
                }
                ExecutionTarget.PreviewServerStop stop = target.stopPreviewServer(vivo.serverName());
                if (stop.failed()) {
                    log.warn("reaper nao conseguiu derrubar {}: {}", vivo.serverName(), stop.error());
                    continue;
                }
                lastSeen.remove(vivo.serverName());
                log.info("preview server {} ceifado por inatividade (> {}s sem heartbeat)",
                        vivo.serverName(), ttl.toSeconds());
            }
        }
    }

    /** Nome deterministico e restart-safe — a MESMA regra de encurtamento do volume do workspace. */
    static String serverNameFor(UUID companyId, UUID agentId, UUID issueId) {
        return "orq_preview_" + shorten(companyId) + "_" + shorten(agentId) + "_" + shorten(issueId);
    }

    private static String shorten(UUID id) {
        return id.toString().substring(0, 8);
    }

    private ExecutionTarget.PreviewServerLive liveFor(ExecutionTarget target, UUID companyId,
                                                      UUID issueId) {
        return target.listPreviewServers().stream()
                .filter(vivo -> companyId.equals(vivo.companyId()) && issueId.equals(vivo.issueId()))
                .findFirst().orElse(null);
    }

    /**
     * Poll TCP ate a porta responder (ADR-0023 §7): sucesso = servidor de pe; sumir do runtime
     * ANTES do prazo = morte precoce (nao espera o resto); prazo estourado = falha.
     *
     * <p>Interrompido, repoe a flag e volta — nunca engole a interrupcao. Quem sabe que ainda ha
     * LIMPEZA a fazer e o chamador, e e la que a flag e consumida e devolvida em volta dela: repor a
     * flag antes da limpeza mata a limpeza (ver {@link #startFor}).
     */
    private boolean waitPortAlive(ExecutionTarget target, String serverName, int hostPort) {
        long deadline = System.currentTimeMillis() + startPoll.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", hostPort), 250);
                return true;
            } catch (IOException portaAindaFechada) {
                if (target.listPreviewServers().stream()
                        .noneMatch(vivo -> vivo.serverName().equals(serverName))) {
                    return false;
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * F.12 (ADR-0026, B4): se o workspace desta Issue foi coletado pelo GC (evento durável no
     * activity_log), devolve o aviso LEGÍVEL com a data — senão {@code null} (o start segue normal).
     *
     * <p>A CONSULTA mora na {@link ColetaDeWorkspace} desde o fecho do parecer: a mesma pergunta
     * passou a guardar também a reabertura de entrega aprovada (D-FP-1/ADR-0049), e duas cópias de
     * "o que conta como coletado" é justamente a classe de drift que aquela rodada veio limpar.
     * Uma régua, duas cercas — a MENSAGEM continua de cada porta, porque a consequência é outra:
     * aqui a app viva não volta; lá o artefato a corrigir não existe mais.
     */
    private StartResult workspaceCollectedNotice(UUID companyId, UUID issueId) {
        return coletaDeWorkspace.coletadoEm(companyId, issueId)
                .map(quando -> StartResult.failure("workspace coletado pelo GC em "
                        + ColetaDeWorkspace.formatado(quando)
                        + " — a app viva nao volta; recrie a demo com um run novo"
                        + " (a foto do preview segue eterna)"))
                .orElse(null);
    }

    private void recordStartOutcome(UUID companyId, UUID issueId, UUID boardUserId, Integer hostPort,
                                    boolean started) {
        if (boardUserId == null) {
            return;
        }
        try {
            activityLog.record(companyId, ActorType.BOARD, boardUserId,
                    started ? ActivityAction.PREVIEW_SERVER_STARTED
                            : ActivityAction.PREVIEW_SERVER_START_FAILED,
                    "issue", issueId,
                    started ? Map.of("hostPort", hostPort) : Map.of());
        } catch (RuntimeException exception) {
            log.warn("evento de start do preview server falhou (issue {}): {}",
                    issueId, exception.getMessage());
        }
    }

    private void recordStopped(UUID companyId, UUID issueId, UUID boardUserId) {
        try {
            activityLog.record(companyId, ActorType.BOARD, boardUserId,
                    ActivityAction.PREVIEW_SERVER_STOPPED, "issue", issueId, Map.of());
        } catch (RuntimeException exception) {
            log.warn("evento de stop do preview server falhou (issue {}): {}",
                    issueId, exception.getMessage());
        }
    }
}
