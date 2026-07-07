package com.orquestrador.skills;

import com.orquestrador.common.security.ApiKeyHasher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Importa skills do filesystem ({@code <root>/<dir>/SKILL.md}) para o banco (ADR-0007/0008), em DUAS
 * etapas: <b>(A) catálogo global</b> — upsert da identidade {@link Skill} + savelog {@link SkillVersion}
 * (o que o KnowAll conhece); <b>(B) instalação na company</b> — copia a versão-base global para a
 * biblioteca da Company ({@link CompanySkill}/{@link CompanySkillVersion}). {@link #syncGlobalCatalog}
 * faz só (A) — o operador registra skills baixadas-mas-não-instaladas; {@link #index} faz (A)+(B).
 *
 * <p>Cada skill: redige segredos PRIMEIRO (LGPD), parseia o frontmatter do body redigido, calcula o
 * hash do body redigido e versiona idempotentemente (à prova de revert em ambas as camadas). Parse
 * acontece antes de qualquer persistência: skill inválida (sem {@code name}) é pulada sem contaminar
 * a transação. {@code source_global_version_id} rastreia de qual versão global cada cópia veio.
 */
@Component
public class SkillIndexer {

    private enum Outcome { CREATED, UPDATED, UNCHANGED }

    private record GlobalUpsert(Skill skill, SkillVersion version, Outcome outcome) {
    }

    private final SkillRepository skills;
    private final SkillVersionRepository skillVersions;
    private final CompanySkillRepository companySkills;
    private final CompanySkillVersionRepository companySkillVersions;
    private final CompanyLibraryInstaller libraryInstaller;

    public SkillIndexer(SkillRepository skills, SkillVersionRepository skillVersions,
                        CompanySkillRepository companySkills, CompanySkillVersionRepository companySkillVersions,
                        CompanyLibraryInstaller libraryInstaller) {
        this.skills = skills;
        this.skillVersions = skillVersions;
        this.companySkills = companySkills;
        this.companySkillVersions = companySkillVersions;
        this.libraryInstaller = libraryInstaller;
    }

    /** Etapa A apenas: popula o catálogo GLOBAL a partir da raiz, sem instalar em nenhuma company. */
    @Transactional
    public IndexResult syncGlobalCatalog(Path root) {
        return run(root, null, null);
    }

    /** Etapas A + B: sincroniza o catálogo global e instala/atualiza na biblioteca da Company. */
    @Transactional
    public IndexResult index(UUID companyId, Path root) {
        return run(root, companyId, null);
    }

    /**
     * Etapas A + B restritas a {@code skillKeys} (nomes de diretório sob a raiz). A seleção é comparada
     * por NOME contra a listagem feita pelo servidor — chave do cliente nunca é resolvida como caminho,
     * então traversal é impossível por construção. {@code null} = sem filtro (importa a raiz inteira).
     */
    @Transactional
    public IndexResult index(UUID companyId, Path root, Collection<String> skillKeys) {
        return run(root, companyId, skillKeys);
    }

    private IndexResult run(Path root, UUID companyId, Collection<String> skillKeys) {
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        List<String> skipped = new ArrayList<>();

        if (root == null || !Files.isDirectory(root)) {
            skipped.add("raiz de import inexistente: " + root);
            return new IndexResult(0, 0, 0, skipped);
        }

        List<Path> dirs;
        try (Stream<Path> entries = Files.list(root)) {
            dirs = entries.filter(Files::isDirectory)
                    .filter(dir -> skillKeys == null || skillKeys.contains(dir.getFileName().toString()))
                    .sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("falha ao listar a raiz de skills " + root, exception);
        }

        if (skillKeys != null) {
            List<String> foundDirNames = dirs.stream().map(dir -> dir.getFileName().toString()).toList();
            skillKeys.stream().filter(key -> !foundDirNames.contains(key))
                    .forEach(key -> skipped.add(key + ": não encontrada na raiz de import"));
        }

        for (Path dir : dirs) {
            String dirName = dir.getFileName().toString();
            Path skillFile = dir.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) {
                skipped.add(dirName + ": sem SKILL.md");
                continue;
            }

            String raw;
            try {
                raw = Files.readString(skillFile, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                skipped.add(dirName + ": erro de leitura (" + exception.getMessage() + ")");
                continue;
            }

            // Redige PRIMEIRO; tudo deriva do conteúdo redigido (nenhum segredo toca o banco).
            String body = SecretRedactor.redact(raw);
            SkillFrontmatter frontmatter = SkillFrontmatter.parse(body);
            if (frontmatter.name() == null || frontmatter.name().isBlank()) {
                skipped.add(dirName + ": frontmatter sem 'name'");
                continue;
            }
            String hash = ApiKeyHasher.sha256Hex(body);

            GlobalUpsert globalUpsert = upsertGlobal(frontmatter, body, hash, dirName);
            Outcome outcome = companyId == null
                    ? globalUpsert.outcome()
                    : installIntoCompany(companyId, globalUpsert.skill(), globalUpsert.version(), frontmatter, body, hash);

            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
        }
        return new IndexResult(created, updated, unchanged, skipped);
    }

    /** Etapa A — upsert da identidade global + savelog. Retorna a skill, a versão-base e o outcome. */
    private GlobalUpsert upsertGlobal(SkillFrontmatter frontmatter, String body, String hash, String dirName) {
        Skill skill = skills.findBySkillKey(frontmatter.name()).orElse(null);
        boolean skillIsNew = skill == null;
        if (skillIsNew) {
            skill = skills.save(new Skill(UUID.randomUUID(), frontmatter.name()));
        }

        SkillVersion version = skillVersions.findBySkillIdAndContentHash(skill.getId(), hash).orElse(null);
        boolean versionIsNew = version == null;
        if (versionIsNew) {
            int next = skillVersions.maxVersion(skill.getId()) + 1;
            String change = next == 1 ? "import inicial" : "re-import (mudança automática)";
            version = skillVersions.save(new SkillVersion(UUID.randomUUID(), skill.getId(), next, hash,
                    frontmatter.name(), frontmatter.description(), frontmatter.triggers(), body, change, "LOCAL_PATH", dirName));
        }

        boolean pointerChanged = !version.getId().equals(skill.getCurrentVersionId());
        if (skillIsNew || pointerChanged) {
            skill.promoteBaseVersion(version.getId(), frontmatter.name(), frontmatter.description(), frontmatter.triggers());
            skills.save(skill);
        }

        Outcome outcome = skillIsNew ? Outcome.CREATED
                : (versionIsNew || pointerChanged ? Outcome.UPDATED : Outcome.UNCHANGED);
        return new GlobalUpsert(skill, version, outcome);
    }

    /** Etapa B — copia a versão global para a biblioteca da Company (install ou atualização). */
    private Outcome installIntoCompany(UUID companyId, Skill skill, SkillVersion globalVersion,
                                       SkillFrontmatter frontmatter, String body, String hash) {
        CompanySkill companySkill = companySkills.findByCompanyIdAndSkillId(companyId, skill.getId()).orElse(null);
        if (companySkill == null) {
            // Reusa o mecanismo de install (mesmo usado pela 7c install-on-attach): a versão global copiada
            // carrega os mesmos name/description/triggers/body/hash que o frontmatter deste import produziu.
            libraryInstaller.installFromGlobalVersion(companyId, skill, globalVersion);
            return Outcome.CREATED;
        }
        if (hash.equals(companySkill.getContentHash())) {
            return Outcome.UNCHANGED;
        }
        // Conteúdo diferente do corrente; só insere versão nova se o hash for inédito (à prova de revert).
        if (companySkillVersions.findByCompanyIdAndCompanySkillIdAndContentHash(companyId, companySkill.getId(), hash).isEmpty()) {
            saveCompanyVersion(companyId, companySkill.getId(), companySkillVersions.maxVersion(companyId, companySkill.getId()) + 1, hash,
                    frontmatter, body, "atualizado do catálogo global", globalVersion.getId());
        }
        companySkill.promoteVersion(hash, hash, globalVersion.getSourceRef());
        companySkills.save(companySkill);
        return Outcome.UPDATED;
    }

    private void saveCompanyVersion(UUID companyId, UUID companySkillId, int version, String hash,
                                    SkillFrontmatter frontmatter, String body, String changeDescription,
                                    UUID sourceGlobalVersionId) {
        companySkillVersions.save(new CompanySkillVersion(UUID.randomUUID(), companyId, companySkillId,
                version, hash, frontmatter.name(), frontmatter.description(), frontmatter.triggers(), body, changeDescription,
                sourceGlobalVersionId));
    }
}
