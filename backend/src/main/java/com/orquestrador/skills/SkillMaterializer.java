package com.orquestrador.skills;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Materializa o SkillSet de um Run no workspace como {@code <workDir>/.claude/skills/<skill_key>/SKILL.md}
 * (layout canônico do Claude Code — Fatia 6, ADR-0007 revisado). IDEMPOTENTE: reescreve a pasta inteira
 * a cada Run — uma skill removida do conjunto some do disco (não há append/cache). Grava só o body
 * REDIGIDO. v1: só skill de 1 arquivo (SKILL.md); multi-arquivo é fatia futura.
 *
 * <p>Recebe o CONJUNTO de versões já resolvido: na Fatia 6 é o set base do agente; a Fatia 7 soma os
 * anexos por-Issue ao MESMO conjunto — a assinatura não muda.
 */
@Component
public class SkillMaterializer {

    /**
     * O subtree do contrato. E EXATAMENTE isto que o executor escreve no workspace do Run pelo
     * {@code ExecutionTarget.writeWorkspaceFiles} — no DOCKER o workDir do host e so a area de
     * montagem; o volume nomeado e o que o agente ve.
     */
    public static final String SKILLS_SUBDIR = ".claude/skills";

    public void materialize(Path workDir, Collection<CompanySkillVersion> versions) {
        Path skillsRoot = workDir.resolve(SKILLS_SUBDIR);
        try {
            deleteRecursively(skillsRoot);
            if (versions.isEmpty()) {
                return;
            }
            Files.createDirectories(skillsRoot);
            Path base = skillsRoot.normalize();
            Set<Path> writtenDirs = new HashSet<>();
            for (CompanySkillVersion version : versions) {
                Path dir = skillsRoot.resolve(sanitizeKey(version.getName())).normalize();
                if (writtenDirs.contains(dir)) {
                    // Nomes distintos que colapsam na mesma chave apos sanitize: desambigua com o
                    // companySkillId para NAO perder uma skill (last-write-wins silencioso / hijack).
                    dir = skillsRoot.resolve(sanitizeKey(version.getName()) + "-" + shorten(version.getCompanySkillId()))
                            .normalize();
                }
                if (!dir.startsWith(base) || dir.equals(base)) {
                    continue; // guard anti-traversal (skill_key deve ficar sob .claude/skills)
                }
                writtenDirs.add(dir);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("SKILL.md"), version.getBody(), StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao materializar SkillSet em " + skillsRoot, exception);
        }
    }

    private static String sanitizeKey(String name) {
        String key = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        return (key.isBlank() || key.equals(".") || key.equals("..")) ? "skill" : key;
    }

    private static String shorten(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path); // tolera remocao concorrente benigna (ex.: run base _agent)
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }
}
