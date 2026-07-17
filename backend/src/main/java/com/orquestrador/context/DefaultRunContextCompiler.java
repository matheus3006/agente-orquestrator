package com.orquestrador.context;

import com.orquestrador.execution.adapter.WorkspaceConvention;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.memory.CompanyMemoryMaterializer;
import com.orquestrador.skills.HookMaterializer;
import com.orquestrador.skills.SkillMaterializer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Implementação profunda que esconde seleção de paths, faxina, publicação, hashes e manifesto. */
@Component
public class DefaultRunContextCompiler implements RunContextCompiler {

    private static final int SCHEMA_VERSION = 1;
    private static final String MATERIALIZATION_VERSION = "materialization-v1";

    private final SkillMaterializer skills;
    private final HookMaterializer hooks;
    private final CompanyMemoryMaterializer memories;
    private final ContextPolicy policy;
    private final ContextManifestStore manifests;

    public DefaultRunContextCompiler(SkillMaterializer skills, HookMaterializer hooks,
                                     CompanyMemoryMaterializer memories, ContextPolicy policy,
                                     ContextManifestStore manifests) {
        this.skills = skills;
        this.hooks = hooks;
        this.memories = memories;
        this.policy = policy;
        this.manifests = manifests;
    }

    @Override
    public ContextPackage compile(Request request) {
        List<String> publishedPaths = publishedPaths();
        policy.validatePublishedPaths(publishedPaths);

        materializeSkills(request);
        materializeHooks(request);
        materializeMemory(request);

        List<ArtifactBytes> files = readArtifacts(request.workDir(), publishedPaths);
        List<MaterializedArtifact> artifacts = files.stream()
                .map(file -> new MaterializedArtifact(file.relativePath(), sha256(file.bytes()), file.bytes().length))
                .toList();
        List<ContextSource> sources = artifacts.stream().map(DefaultRunContextCompiler::sourceOf).toList();
        long totalBytes = artifacts.stream().mapToLong(MaterializedArtifact::bytes).sum();
        String finalHash = finalHash(files);

        ExecutionTarget.WorkspaceFilesWrite write = request.target().writeWorkspaceFiles(
                new ExecutionTarget.Spec(request.workDir(), List.of(), java.util.Map.of(), request.image(),
                        request.volumeName(), List.of()), publishedPaths);
        if (write.failed()) {
            throw new ContextCompilationException("falha ao escrever o workspace do run: " + write.error());
        }

        ContextManifest manifest = new ContextManifest(
                request.runContext().runId(), request.runContext().companyId(), request.runContext().agentId(),
                request.runContext().issueId(), request.audience(), request.target().type(),
                conventionName(request.convention()), SCHEMA_VERSION, Instant.now(),
                ContextManifest.Status.COMPILED, finalHash, totalBytes, estimateTokens(totalBytes), sources);
        try {
            manifests.save(manifest);
        } catch (RuntimeException exception) {
            throw new ContextCompilationException("falha ao persistir o manifesto de contexto", exception);
        }
        return new ContextPackage(request.workDir(), request.audience(), request.target().type(), publishedPaths,
                artifacts, request.prompt(), request.outputSchema(), manifest);
    }

    private void materializeSkills(Request request) {
        try {
            skills.materialize(request.workDir(), request.convention().skillsSubdir(), request.skills());
            for (WorkspaceConvention inactive : WorkspaceConvention.KNOWN) {
                if (!inactive.equals(request.convention())) {
                    cleanInactive(request.workDir(), inactive.skillsSubdir());
                }
            }
        } catch (RuntimeException exception) {
            throw new ContextCompilationException("falha ao materializar o SkillSet: " + exception.getMessage(),
                    exception);
        }
    }

    private void materializeHooks(Request request) {
        try {
            hooks.materialize(request.workDir(), request.convention());
            for (WorkspaceConvention inactive : WorkspaceConvention.KNOWN) {
                if (!inactive.equals(request.convention())) {
                    cleanInactive(request.workDir(), inactive.hooksFile());
                    cleanInactive(request.workDir(), inactive.hooksScript());
                }
            }
        } catch (RuntimeException exception) {
            throw new ContextCompilationException(
                    "falha ao materializar os hooks PreToolUse: " + exception.getMessage(), exception);
        }
    }

    private void materializeMemory(Request request) {
        try {
            memories.materialize(request.workDir(), request.convention().memoryFile(),
                    request.runContext().companyId(), request.audience(), request.runContext().issueId());
            for (WorkspaceConvention inactive : WorkspaceConvention.KNOWN) {
                if (!inactive.equals(request.convention())) {
                    cleanInactive(request.workDir(), inactive.memoryFile());
                }
            }
        } catch (RuntimeException exception) {
            throw new ContextCompilationException(
                    "falha ao materializar a memória durável: " + exception.getMessage(), exception);
        }
    }

    private static List<String> publishedPaths() {
        List<String> paths = new ArrayList<>();
        WorkspaceConvention.KNOWN.forEach(convention -> paths.add(convention.skillsSubdir()));
        WorkspaceConvention.KNOWN.forEach(convention -> {
            paths.add(convention.hooksFile());
            paths.add(convention.hooksScript());
        });
        WorkspaceConvention.KNOWN.forEach(convention -> paths.add(convention.memoryFile()));
        return List.copyOf(paths);
    }

    private static List<ArtifactBytes> readArtifacts(Path workDir, List<String> publishedPaths) {
        List<ArtifactBytes> artifacts = new ArrayList<>();
        try {
            for (String publishedPath : publishedPaths) {
                Path root = workDir.resolve(publishedPath);
                if (Files.isRegularFile(root)) {
                    artifacts.add(readOne(workDir, root));
                } else if (Files.isDirectory(root)) {
                    try (Stream<Path> walk = Files.walk(root)) {
                        for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                            artifacts.add(readOne(workDir, file));
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new ContextCompilationException("falha ao calcular hashes do contexto", exception);
        }
        artifacts.sort(Comparator.comparing(ArtifactBytes::relativePath));
        return List.copyOf(artifacts);
    }

    private static ArtifactBytes readOne(Path workDir, Path file) throws IOException {
        return new ArtifactBytes(workDir.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/"),
                Files.readAllBytes(file));
    }

    private static ContextSource sourceOf(MaterializedArtifact artifact) {
        String opaqueAddress = "workspace:" + sha256(artifact.relativePath().getBytes(StandardCharsets.UTF_8));
        return new ContextSource("MATERIALIZED_ARTIFACT", opaqueAddress, MATERIALIZATION_VERSION,
                artifact.sha256(), artifact.bytes(), ContextPolicy.Classification.CANONICAL,
                ContextSource.Inclusion.INCLUDED, "RUN_CONTEXT");
    }

    private static String finalHash(List<ArtifactBytes> artifacts) {
        MessageDigest digest = digest();
        for (ArtifactBytes artifact : artifacts) {
            byte[] path = artifact.relativePath().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(path.length).array());
            digest.update(path);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(artifact.bytes().length).array());
            digest.update(artifact.bytes());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }

    private static long estimateTokens(long bytes) {
        return (bytes + 3L) / 4L;
    }

    private static String conventionName(WorkspaceConvention convention) {
        return convention.equals(WorkspaceConvention.codex()) ? "CODEX" : "CLAUDE";
    }

    private static void cleanInactive(Path workDir, String relativePath) {
        Path target = workDir.resolve(relativePath);
        deleteRecursively(target);
        Path parent = target.getParent();
        while (parent != null && !parent.equals(workDir)) {
            try {
                if (!Files.deleteIfExists(parent)) {
                    break;
                }
            } catch (IOException notEmpty) {
                break;
            }
            parent = parent.getParent();
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (IOException exception) {
            throw new UncheckedIOException("falha na faxina de convenção inativa em " + root, exception);
        }
    }

    private record ArtifactBytes(String relativePath, byte[] bytes) {
    }
}
