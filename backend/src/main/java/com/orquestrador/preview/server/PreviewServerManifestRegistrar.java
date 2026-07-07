package com.orquestrador.preview.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.RunContext;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O registrador da DECLARACAO de servidor (F.10/ADR-0023 §2): conhece o contrato de lugar
 * {@code .orq/preview/server.json}, o cap de 4KB e o parse ESTRITO — e persiste por run no
 * settle, pelo MESMO leitor hermetico do capturador v1. Subir o servidor usa o comando
 * PERSISTIDO aqui: o que se declara e o que executa.
 *
 * <p>Mesmas regras de comportamento do irmao {@code PreviewArtifactCapturer}: SO apos run
 * SUCCEEDED (condicao do chamador) · FORA de transacao, ANTES do settleAfterRun · best-effort
 * LEGIVEL (evento {@code PREVIEW_SERVER_DECLARATION_FAILED} + log), JAMAIS excecao pro settle.
 */
@Component
public class PreviewServerManifestRegistrar {

    /** O contrato de lugar do v2: o agente anuncia o servidor ESCREVENDO aqui (ADR-0001). */
    static final String CONTRACT_RELATIVE_PATH = ".orq/preview/server.json";

    /** Cap do manifest — {"command","port"} e minusculo; 4KB ja e generoso. Estourou = falha legivel. */
    static final long MAX_MANIFEST_BYTES = 4 * 1024;

    private static final Logger log = LoggerFactory.getLogger(PreviewServerManifestRegistrar.class);

    private final AgentRepository agents;
    private final ExecutionTargets targets;
    private final AdapterRegistry adapters;
    private final WorkspaceResolver workspaces;
    private final PreviewServerManifestRepository manifests;
    private final ActivityLogService activityLog;
    private final ObjectMapper json;

    public PreviewServerManifestRegistrar(AgentRepository agents, ExecutionTargets targets,
                                          AdapterRegistry adapters, WorkspaceResolver workspaces,
                                          PreviewServerManifestRepository manifests,
                                          ActivityLogService activityLog, ObjectMapper json) {
        this.agents = agents;
        this.targets = targets;
        this.adapters = adapters;
        this.workspaces = workspaces;
        this.manifests = manifests;
        this.activityLog = activityLog;
        this.json = json;
    }

    /** Best-effort: NUNCA lanca — qualquer falha vira registro legivel e o settle segue intocado. */
    public void registerIfDeclared(RunContext runContext) {
        try {
            register(runContext);
        } catch (RuntimeException exception) {
            log.warn("registro do manifest de servidor falhou inesperadamente (run {}): {}",
                    runContext.runId(), exception.getMessage());
            recordFailure(runContext, "READ_FAILED");
        }
    }

    private void register(RunContext runContext) {
        if (runContext.issueId() == null) {
            return;
        }
        Agent agent = agents.findById(runContext.agentId()).orElse(null);
        if (agent == null) {
            log.warn("registro do manifest pulado: agente {} nao encontrado (run {})",
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

        ExecutionTarget.WorkspaceFileRead read =
                target.readWorkspaceFile(readerSpec, CONTRACT_RELATIVE_PATH, MAX_MANIFEST_BYTES);
        if (read.absent()) {
            return; // nada declarado = run normal
        }
        if (read.failed()) {
            log.warn("leitura do manifest de servidor falhou (run {}, issue {}): {}",
                    runContext.runId(), runContext.issueId(), read.error());
            recordFailure(runContext, read.overCap() ? "CAP_EXCEEDED" : "READ_FAILED");
            return;
        }

        DeclaredServer declared = parseStrict(new String(read.bytes(), StandardCharsets.UTF_8));
        if (declared == null) {
            log.warn("manifest de servidor invalido (run {}, issue {}): parse estrito recusou",
                    runContext.runId(), runContext.issueId());
            recordFailure(runContext, "INVALID_MANIFEST");
            return;
        }
        persist(runContext, declared);
    }

    /**
     * Parse ESTRITO do minimo irredutivel {@code {"command","port"}}: objeto JSON com EXATAMENTE
     * esses campos, command string nao-vazia, port inteiro 1-65535. Campo a mais, tipo errado ou
     * porta fora da faixa = recusa (typo silenciosamente ignorado viraria servidor que nunca sobe).
     */
    private DeclaredServer parseStrict(String content) {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException exception) {
            return null;
        }
        if (root == null || !root.isObject() || root.size() != 2) {
            return null;
        }
        for (Iterator<String> fields = root.fieldNames(); fields.hasNext(); ) {
            String field = fields.next();
            if (!field.equals("command") && !field.equals("port")) {
                return null;
            }
        }
        JsonNode command = root.get("command");
        JsonNode port = root.get("port");
        if (command == null || !command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        if (port == null || !port.isIntegralNumber() || !port.canConvertToInt()) {
            return null;
        }
        int declaredPort = port.intValue();
        if (declaredPort < 1 || declaredPort > 65535) {
            return null;
        }
        return new DeclaredServer(command.asText().trim(), declaredPort);
    }

    /** Fase de PERSISTENCIA com rotulo proprio (a auditoria nao mente sobre a fase que falhou). */
    private void persist(RunContext runContext, DeclaredServer declared) {
        try {
            PreviewServerManifest manifest = manifests.save(new PreviewServerManifest(
                    UUID.randomUUID(), runContext.companyId(), runContext.issueId(),
                    runContext.agentId(), runContext.runId(), declared.command(), declared.port()));
            recordDeclared(runContext, manifest);
        } catch (RuntimeException exception) {
            log.warn("persistencia do manifest de servidor falhou (run {}, issue {}): {}",
                    runContext.runId(), runContext.issueId(), exception.getMessage());
            recordFailure(runContext, "PERSIST_FAILED");
        }
    }

    /** O manifest JA esta persistido: falha do evento nao pode virar DECLARATION_FAILED. */
    private void recordDeclared(RunContext runContext, PreviewServerManifest manifest) {
        try {
            activityLog.record(runContext.companyId(), ActorType.AGENT, runContext.agentId(),
                    ActivityAction.PREVIEW_SERVER_DECLARED, "issue", runContext.issueId(),
                    Map.of("runId", runContext.runId(), "manifestId", manifest.getId(),
                            "port", manifest.getPort()));
        } catch (RuntimeException exception) {
            log.warn("manifest persistido, mas o evento PREVIEW_SERVER_DECLARED falhou (run {}): {}",
                    runContext.runId(), exception.getMessage());
        }
    }

    private void recordFailure(RunContext runContext, String reason) {
        try {
            activityLog.record(runContext.companyId(), ActorType.AGENT, runContext.agentId(),
                    ActivityAction.PREVIEW_SERVER_DECLARATION_FAILED, "issue", runContext.issueId(),
                    Map.of("runId", runContext.runId(), "reason", reason));
        } catch (RuntimeException exception) {
            log.warn("registro de falha de declaracao tambem falhou (run {}): {}",
                    runContext.runId(), exception.getMessage());
        }
    }

    private record DeclaredServer(String command, int port) {
    }
}
