package com.orquestrador.preview;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.common.security.ApiKeyHasher;
import com.orquestrador.execution.RunContext;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O modulo profundo do preview (F.9/ADR-0021; bounded context nº 7 da ARQUITETURA §4): conhece o
 * contrato de lugar {@code .orq/preview/index.html}, o cap de 2MB, o sha-256, o dedup por Issue e
 * a persistencia versionada — o executor toca UM ponto ({@link #captureIfPublished}).
 *
 * <p>Regras de comportamento (fechado §A.6): captura SO apos run SUCCEEDED (condicao do chamador) ·
 * roda FORA de transacao, ANTES do settleAfterRun (quando a review nasce, o artefato ja existe) ·
 * falha de captura e um registro LEGIVEL (evento {@code PREVIEW_CAPTURE_FAILED} + log), JAMAIS
 * excecao pro settle — a captura e observacao, nao participante da maquina de estados. Run sem
 * Issue nao captura (o CEO e delegador puro, ADR-0018).
 */
@Component
public class PreviewArtifactCapturer {

    /** O diretorio-contrato ({@code .orq/} = namespace do orquestrador). */
    static final String PREVIEW_DIR = ".orq/preview";

    /** O contrato de lugar: o agente anuncia ESCREVENDO aqui. */
    static final String CONTRACT_RELATIVE_PATH = PREVIEW_DIR + "/index.html";

    /** O contrato do entregavel de PESQUISA (emenda R29-A): so e tentado quando index.html esta ausente. */
    static final String MARKDOWN_CONTRACT_RELATIVE_PATH = PREVIEW_DIR + "/index.md";

    static final String CONTENT_TYPE_HTML = "html";
    static final String CONTENT_TYPE_MARKDOWN = "markdown";

    /** Cap do artefato (protege banco e srcdoc). Estourou = falha legivel; NUNCA trunca. */
    static final long MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(PreviewArtifactCapturer.class);

    private final AgentRepository agents;
    private final ExecutionTargets targets;
    private final AdapterRegistry adapters;
    private final WorkspaceResolver workspaces;
    private final PreviewArtifactRepository previews;
    private final ActivityLogService activityLog;

    public PreviewArtifactCapturer(AgentRepository agents, ExecutionTargets targets, AdapterRegistry adapters,
                                   WorkspaceResolver workspaces, PreviewArtifactRepository previews,
                                   ActivityLogService activityLog) {
        this.agents = agents;
        this.targets = targets;
        this.adapters = adapters;
        this.workspaces = workspaces;
        this.previews = previews;
        this.activityLog = activityLog;
    }

    /** Best-effort: NUNCA lanca — qualquer falha vira registro legivel e o settle segue intocado. */
    public void captureIfPublished(RunContext runContext) {
        try {
            capture(runContext);
        } catch (RuntimeException exception) {
            log.warn("captura de preview falhou inesperadamente (run {}): {}",
                    runContext.runId(), exception.getMessage());
            recordFailure(runContext, "READ_FAILED");
        }
    }

    private void capture(RunContext runContext) {
        if (runContext.issueId() == null) {
            return;
        }
        Agent agent = agents.findById(runContext.agentId()).orElse(null);
        if (agent == null) {
            log.warn("captura de preview pulada: agente {} nao encontrado (run {})",
                    runContext.agentId(), runContext.runId());
            return;
        }

        ExecutionTarget target = targets.forType(ExecutionTargets.parse(agent.getExecutionTarget()));
        String readerImage = adapters.forType(agent.getAdapterType()).engineContainer().image();
        ExecutionTarget.Spec readerSpec = new ExecutionTarget.Spec(
                workspaces.resolve(runContext.companyId(), runContext.agentId(), runContext.issueId()),
                List.of(), Map.of(), readerImage,
                workspaces.volumeName(runContext.companyId(), runContext.agentId(), runContext.issueId()),
                List.of());

        Publicacao publicacao = lerPublicacao(target, readerSpec, runContext);
        if (publicacao == null) {
            return; // nada publicado (run normal) ou falha ja registrada legivel
        }
        ExecutionTarget.WorkspaceFileRead read = publicacao.read();
        String contentType = publicacao.contentType();
        String sourceFilename = publicacao.sourceFilename();
        if (read.absent()) {
            return; // corrida inocua: o arquivo listado sumiu antes da leitura — nada publicado
        }
        if (read.failed()) {
            log.warn("captura de preview falhou (run {}, issue {}): {}",
                    runContext.runId(), runContext.issueId(), read.error());
            recordFailure(runContext, read.overCap() ? "CAP_EXCEEDED" : "READ_FAILED");
            return;
        }

        // Decode ESTRITO: binario publicado no contrato (ex.: cp logo.png) nao pode virar sopa
        // de U+FFFD — transmutaria a foto, furaria o cap no re-encode e colidiria o dedup entre
        // binarios distintos. Conteudo invalido = falha legivel, nada persistido.
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(read.bytes()))
                    .toString();
        } catch (CharacterCodingException exception) {
            log.warn("captura de preview recusada (run {}, issue {}): conteudo nao e UTF-8",
                    runContext.runId(), runContext.issueId());
            recordFailure(runContext, "INVALID_CONTENT");
            return;
        }
        if (content.indexOf('\0') >= 0) {
            // UTF-8 valido, mas o text do Postgres rejeita NUL — mesmo veredicto: nao e HTML texto.
            log.warn("captura de preview recusada (run {}, issue {}): conteudo com byte NUL",
                    runContext.runId(), runContext.issueId());
            recordFailure(runContext, "INVALID_CONTENT");
            return;
        }

        String contentHash = ApiKeyHasher.sha256Hex(content);
        Foto foto = new Foto(content, contentHash, contentType, sourceFilename);
        PreviewArtifact ultima = previews
                .findTopByCompanyIdAndIssueIdOrderByVersionDesc(runContext.companyId(), runContext.issueId())
                .orElse(null);
        if (ultima != null && ultima.getContentHash().equals(contentHash)) {
            return; // o workspace persiste entre runs: mesma foto nao vira versao nova (dedup por hash)
        }
        persistVersion(runContext, foto, ultima != null ? ultima.getVersion() + 1 : 1);
    }

    /** A foto pronta para persistir: conteudo + hash + tipo + proveniencia viajam JUNTOS. */
    private record Foto(String content, String contentHash, String contentType, String sourceFilename) {
    }

    /** O que o run publicou no contrato de lugar: a leitura + o tipo + a proveniencia. */
    private record Publicacao(ExecutionTarget.WorkspaceFileRead read, String contentType,
                              String sourceFilename) {
    }

    /**
     * A precedencia do contrato (grill R29-A): 1) {@code index.html} — prototipo pronto vence
     * anotacao solta, caminho do app byte a byte intacto; 2) {@code index.md} — a convencao do
     * entregavel de pesquisa; 3) o UNICO {@code *.md} do diretorio — a realidade medida na R27-A
     * (nome livre); 2+ sem convencao = ambiguidade LEGIVEL ({@code AMBIGUOUS_MARKDOWN}), nunca
     * escolha silenciosa. {@code null} = nada publicado ou falha ja registrada.
     */
    private Publicacao lerPublicacao(ExecutionTarget target, ExecutionTarget.Spec readerSpec,
                                     RunContext runContext) {
        ExecutionTarget.WorkspaceFileRead html =
                target.readWorkspaceFile(readerSpec, CONTRACT_RELATIVE_PATH, MAX_CONTENT_BYTES);
        if (!html.absent()) {
            return new Publicacao(html, CONTENT_TYPE_HTML, "index.html");
        }
        ExecutionTarget.WorkspaceFileRead indexMd =
                target.readWorkspaceFile(readerSpec, MARKDOWN_CONTRACT_RELATIVE_PATH, MAX_CONTENT_BYTES);
        if (!indexMd.absent()) {
            return new Publicacao(indexMd, CONTENT_TYPE_MARKDOWN, "index.md");
        }
        ExecutionTarget.WorkspaceDirListing listagem = target.listWorkspaceDir(readerSpec, PREVIEW_DIR);
        if (listagem.failed()) {
            log.warn("captura de preview falhou (run {}, issue {}): {}",
                    runContext.runId(), runContext.issueId(), listagem.error());
            recordFailure(runContext, "READ_FAILED");
            return null;
        }
        List<String> markdowns = listagem.filenames().stream()
                .filter(nome -> nome.endsWith(".md"))
                .toList();
        if (markdowns.isEmpty()) {
            return null; // nada publicado = run normal
        }
        if (markdowns.size() > 1) {
            log.warn("captura de preview recusada (run {}, issue {}): {} arquivos .md sem index.md — "
                            + "convencione .orq/preview/index.md ({})",
                    runContext.runId(), runContext.issueId(), markdowns.size(), markdowns);
            recordFailure(runContext, "AMBIGUOUS_MARKDOWN");
            return null;
        }
        String nome = markdowns.get(0);
        return new Publicacao(
                target.readWorkspaceFile(readerSpec, PREVIEW_DIR + "/" + nome, MAX_CONTENT_BYTES),
                CONTENT_TYPE_MARKDOWN, nome);
    }

    /**
     * Fase de PERSISTENCIA com rotulo proprio: falha aqui e PERSIST_FAILED, nunca READ_FAILED
     * (a leitura funcionou — auditoria nao pode mentir sobre a fase). O conflito de ordinal
     * cobre o zumbi do lease reaper re-executando o MESMO runId noutro no (o execution_lock e
     * idempotente por runId, ADR-0005/0011): rele a ultima versao e tenta UMA vez.
     */
    private void persistVersion(RunContext runContext, Foto foto, int version) {
        try {
            try {
                recordCaptured(runContext, saveVersion(runContext, foto, version));
            } catch (DataIntegrityViolationException conflito) {
                PreviewArtifact atual = previews.findTopByCompanyIdAndIssueIdOrderByVersionDesc(
                        runContext.companyId(), runContext.issueId()).orElse(null);
                if (atual != null && atual.getContentHash().equals(foto.contentHash())) {
                    return; // o outro lado ja gravou a MESMA foto — dedup tardio, nada a fazer
                }
                recordCaptured(runContext, saveVersion(runContext, foto,
                        atual != null ? atual.getVersion() + 1 : 1));
            }
        } catch (RuntimeException exception) {
            log.warn("persistencia do preview falhou (run {}, issue {}): {}",
                    runContext.runId(), runContext.issueId(), exception.getMessage());
            recordFailure(runContext, "PERSIST_FAILED");
        }
    }

    private PreviewArtifact saveVersion(RunContext runContext, Foto foto, int version) {
        return previews.save(new PreviewArtifact(UUID.randomUUID(), runContext.companyId(),
                runContext.issueId(), runContext.agentId(), runContext.runId(), version,
                foto.content(), foto.contentHash(), foto.contentType(), foto.sourceFilename()));
    }

    /** O artefato JA esta persistido: falha do evento nao pode virar PREVIEW_CAPTURE_FAILED. */
    private void recordCaptured(RunContext runContext, PreviewArtifact artefato) {
        try {
            activityLog.record(runContext.companyId(), ActorType.AGENT, runContext.agentId(),
                    ActivityAction.PREVIEW_CAPTURED, "issue", runContext.issueId(),
                    Map.of("runId", runContext.runId(), "previewArtifactId", artefato.getId(),
                            "version", artefato.getVersion()));
        } catch (RuntimeException exception) {
            log.warn("preview v{} persistido, mas o evento PREVIEW_CAPTURED falhou (run {}): {}",
                    artefato.getVersion(), runContext.runId(), exception.getMessage());
        }
    }

    private void recordFailure(RunContext runContext, String reason) {
        try {
            activityLog.record(runContext.companyId(), ActorType.AGENT, runContext.agentId(),
                    ActivityAction.PREVIEW_CAPTURE_FAILED, "issue", runContext.issueId(),
                    Map.of("runId", runContext.runId(), "reason", reason));
        } catch (RuntimeException exception) {
            log.warn("registro de falha de captura tambem falhou (run {}): {}",
                    runContext.runId(), exception.getMessage());
        }
    }
}
