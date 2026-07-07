package com.orquestrador.execution.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolve o Workspace de um Run: um diretorio duravel por (company, agent, issue).
 * No alvo LOCAL e um diretorio real; no DOCKER e montado como volume nomeado (ADR-0002:
 * estado entre heartbeats vive no Workspace).
 *
 * <p>Dono UNICO da nomeacao (ADR-0010): {@link #volumeName}/{@link #pathFor} batizam, e o
 * {@code WorkspaceReaper} (F.12/ADR-0026) DERIVA o ref esperado de cada Issue por aqui para
 * intersectar com o mundo (world-driven, zero parsing — o nome usa UUID encurtado, a inversao
 * nome->banco e impossivel por construcao). O reconhecimento do formato de workspace por-Issue
 * ({@link #isIssueVolumeName}) e a denylist ESTRUTURAL que exclui creds/pgdata/workspace-de-agente.
 */
@Component
public class WorkspaceResolver {

    /** Formato do volume por-Issue: {@code orq_<c8>_<a8>_<i8>}, cada segmento 8 hex do UUID encurtado. */
    private static final Pattern ISSUE_VOLUME = Pattern.compile("orq_[0-9a-f]{8}_[0-9a-f]{8}_[0-9a-f]{8}");

    private final Path root;

    public WorkspaceResolver(
            @Value("${orquestrador.workspace.root:#{systemProperties['user.home']}/.orquestrador/workspaces}") String root) {
        this.root = Path.of(root);
    }

    public Path resolve(UUID companyId, UUID agentId, UUID issueId) {
        Path dir = pathFor(companyId, agentId, issueId);
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new UncheckedIOException("Nao foi possivel criar o workspace: " + dir, exception);
        }
        return dir;
    }

    /** O caminho do workspace SEM materializar (par puro do {@link #resolve}) — o sweep nunca cria. */
    public Path pathFor(UUID companyId, UUID agentId, UUID issueId) {
        return root.resolve(companyId.toString())
                .resolve(agentId.toString())
                .resolve(issueId != null ? issueId.toString() : "_agent");
    }

    /** A raiz de todos os workspaces — o teto do GUARD de path do delete no alvo LOCAL. */
    public Path root() {
        return root;
    }

    public String volumeName(UUID companyId, UUID agentId, UUID issueId) {
        String suffix = issueId != null ? "_" + shorten(issueId) : "";
        return "orq_" + shorten(companyId) + "_" + shorten(agentId) + suffix;
    }

    /**
     * O ref do workspace por-Issue no vocabulario de CADA alvo (F.12/ADR-0026): DOCKER = nome de
     * volume; LOCAL = caminho absoluto. O reaper compara o mundo listado pelo alvo com este ref —
     * os dois saem identicos por construcao (ambos derivam daqui).
     */
    public String refFor(ExecutionTarget.Type type, UUID companyId, UUID agentId, UUID issueId) {
        return switch (type) {
            case DOCKER -> volumeName(companyId, agentId, issueId);
            case LOCAL -> pathFor(companyId, agentId, issueId).toString();
        };
    }

    /**
     * Reconhece o FORMATO de um volume workspace por-Issue ({@code orq_<8hex>_<8hex>_<8hex>}) —
     * denylist ESTRUTURAL (F.12/ADR-0026): {@code orq_claude_creds}/{@code orq_codex_creds},
     * {@code pgdata} e o workspace de agente ({@code orq_<8hex>_<8hex>}, 2 segmentos) NAO casam,
     * logo nem viram candidato nem viram orfao. NAO extrai IDs — so testa a forma.
     */
    public boolean isIssueVolumeName(String name) {
        return name != null && ISSUE_VOLUME.matcher(name).matches();
    }

    private static String shorten(UUID id) {
        return id.toString().substring(0, 8);
    }
}
