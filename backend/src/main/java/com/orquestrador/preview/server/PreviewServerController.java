package com.orquestrador.preview.server;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A superficie board-only do servidor de preview (F.10/ADR-0023 §7): start/stop/heartbeat por
 * Issue (o "Rodar app" e o keepalive), status para a Issue e o card de Review (manifest vigente
 * + vivo — o front compara {@code manifest.runId} com {@code review.runId} para o aviso
 * "workspace avancou"), e a tela Servidores (vivos + candidatas). O acesso ao APP nao passa por
 * aqui: o iframe fala DIRETO com a porta no loopback (origin proprio, sem proxy — ADR-0023 §5).
 */
@RestController
@RequestMapping("/api/companies/{companyId}")
public class PreviewServerController {

    private final PreviewServerSupervisor supervisor;
    private final PreviewServerManifestRepository manifests;
    private final IssueRepository issues;
    private final AgentRepository agents;
    private final CompanyAccessGuard guard;

    public PreviewServerController(PreviewServerSupervisor supervisor,
                                   PreviewServerManifestRepository manifests, IssueRepository issues,
                                   AgentRepository agents, CompanyAccessGuard guard) {
        this.supervisor = supervisor;
        this.manifests = manifests;
        this.issues = issues;
        this.agents = agents;
        this.guard = guard;
    }

    @PostMapping("/issues/{issueId}/preview-server/start")
    public StartResponse start(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        assertBoardAndIssue(companyId, issueId);
        PreviewServerSupervisor.StartResult resultado =
                supervisor.startFor(companyId, issueId, guard.currentActor().id());
        return new StartResponse(!resultado.failed(), resultado.url(), resultado.hostPort(),
                resultado.error());
    }

    @PostMapping("/issues/{issueId}/preview-server/stop")
    public StopResponse stop(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        assertBoardAndIssue(companyId, issueId);
        return new StopResponse(supervisor.stopFor(companyId, issueId, guard.currentActor().id()));
    }

    /** O keepalive do board (~60s com o frame montado) — renova o TTL por inatividade. */
    @PostMapping("/issues/{issueId}/preview-server/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        assertBoardAndIssue(companyId, issueId);
        return new HeartbeatResponse(supervisor.heartbeat(companyId, issueId));
    }

    /** Status para a Issue e o card de Review: a declaracao VIGENTE + o vivo (se houver). */
    @GetMapping("/issues/{issueId}/preview-server")
    public StatusResponse status(@PathVariable UUID companyId, @PathVariable UUID issueId) {
        assertBoardAndIssue(companyId, issueId);
        ManifestSummary manifest = manifests
                .findTopByCompanyIdAndIssueIdOrderByCreatedAtDesc(companyId, issueId)
                .map(ManifestSummary::from).orElse(null);
        LiveSummary live = supervisor.listLive(companyId).stream()
                .filter(vivo -> issueId.equals(vivo.issueId()))
                .findFirst().map(LiveSummary::from).orElse(null);
        return new StatusResponse(manifest, live);
    }

    /** A tela Servidores: vivos (matar/abrir) + candidatas (Issues com manifest; iniciar). */
    @GetMapping("/preview-servers")
    public ServersResponse servers(@PathVariable UUID companyId) {
        guard.assertBoardMember(companyId);
        List<PreviewServerSupervisor.LiveServer> vivos = supervisor.listLive(companyId);
        Map<UUID, PreviewServerManifest> vigentePorIssue = new LinkedHashMap<>();
        for (PreviewServerManifest manifest : manifests.findByCompanyIdOrderByCreatedAtDesc(companyId)) {
            vigentePorIssue.putIfAbsent(manifest.getIssueId(), manifest); // mais novo primeiro
        }
        Map<UUID, Issue> issuePorId = new HashMap<>();
        Map<UUID, String> agentePorId = new HashMap<>();

        List<LiveEntry> live = vivos.stream().map(vivo -> {
            Issue issue = issueFor(companyId, vivo.issueId(), issuePorId);
            return new LiveEntry(
                    vivo.serverName(), vivo.issueId(),
                    issue != null ? issue.getSeq() : null,
                    issue != null ? issue.getTitle() : null,
                    vivo.agentId(), agentName(vivo.agentId(), agentePorId),
                    vigentePorIssue.containsKey(vivo.issueId())
                            ? vigentePorIssue.get(vivo.issueId()).getCommand() : null,
                    vivo.hostPort(), vivo.url(), vivo.ttlRemainingSeconds());
        }).toList();

        List<CandidateEntry> candidates = vigentePorIssue.values().stream()
                .map(manifest -> {
                    Issue issue = issueFor(companyId, manifest.getIssueId(), issuePorId);
                    return new CandidateEntry(
                            manifest.getIssueId(),
                            issue != null ? issue.getSeq() : null,
                            issue != null ? issue.getTitle() : null,
                            manifest.getCommand(), manifest.getPort(), manifest.getRunId(),
                            manifest.getCreatedAt(),
                            vivos.stream().anyMatch(vivo -> manifest.getIssueId().equals(vivo.issueId())));
                })
                .toList();
        return new ServersResponse(live, candidates);
    }

    private void assertBoardAndIssue(UUID companyId, UUID issueId) {
        guard.assertBoardMember(companyId);
        // Issue company-led -> fora da company e 404 (padrao da casa), nunca acao em Issue alheia.
        issues.findByIdAndCompanyId(issueId, companyId)
                .orElseThrow(() -> new NotFoundException("Issue nao encontrada nesta company"));
    }

    /** A identidade humana inteira (#N + titulo, ADR-0033) sai do MESMO lookup por issue. */
    private Issue issueFor(UUID companyId, UUID issueId, Map<UUID, Issue> cache) {
        return cache.computeIfAbsent(issueId, id ->
                issues.findByIdAndCompanyId(id, companyId).orElse(null));
    }

    private String agentName(UUID agentId, Map<UUID, String> cache) {
        return cache.computeIfAbsent(agentId, id ->
                agents.findById(id).map(com.orquestrador.org.agent.Agent::getName).orElse(null));
    }

    public record StartResponse(boolean started, String url, Integer hostPort, String error) {
    }

    public record StopResponse(boolean stopped) {
    }

    public record HeartbeatResponse(boolean live) {
    }

    public record StatusResponse(ManifestSummary manifest, LiveSummary live) {
    }

    public record ManifestSummary(UUID id, UUID runId, String command, int port, Instant createdAt) {

        static ManifestSummary from(PreviewServerManifest manifest) {
            return new ManifestSummary(manifest.getId(), manifest.getRunId(), manifest.getCommand(),
                    manifest.getPort(), manifest.getCreatedAt());
        }
    }

    public record LiveSummary(String url, int hostPort, long ttlRemainingSeconds) {

        static LiveSummary from(PreviewServerSupervisor.LiveServer vivo) {
            return new LiveSummary(vivo.url(), vivo.hostPort(), vivo.ttlRemainingSeconds());
        }
    }

    public record LiveEntry(String serverName, UUID issueId, Integer issueSeq, String issueTitle,
                            UUID agentId, String agentName, String command, int hostPort, String url,
                            long ttlRemainingSeconds) {
    }

    public record CandidateEntry(UUID issueId, Integer issueSeq, String issueTitle, String command,
                                 int port, UUID runId, Instant createdAt, boolean live) {
    }

    public record ServersResponse(List<LiveEntry> live, List<CandidateEntry> candidates) {
    }
}
