package com.orquestrador.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillMaterializer: escreve o conjunto de versões como .claude/skills/<key>/SKILL.md, idempotente
 * (reescreve a pasta inteira), só o body, resistente a traversal. Unit puro (sem Spring).
 */
class SkillMaterializerTest {

    private final SkillMaterializer materializer = new SkillMaterializer();

    @TempDir
    Path workDir;

    private static CompanySkillVersion version(String name, String body) {
        return new CompanySkillVersion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "hash-" + name, name, "desc", null, body, "test", UUID.randomUUID());
    }

    @Test
    void escreveOConjuntoNoLayoutCanonico() throws IOException {
        materializer.materialize(workDir, List.of(
                version("tdd", "# TDD\ncorpo tdd\n"),
                version("pdf", "# PDF\ncorpo pdf\n")));

        Path tdd = workDir.resolve(".claude/skills/tdd/SKILL.md");
        Path pdf = workDir.resolve(".claude/skills/pdf/SKILL.md");
        assertThat(Files.exists(tdd)).isTrue();
        assertThat(Files.readString(tdd)).isEqualTo("# TDD\ncorpo tdd\n");
        assertThat(Files.exists(pdf)).isTrue();
    }

    @Test
    void idempotenteReescreveAPastaInteira() throws IOException {
        materializer.materialize(workDir, List.of(version("tdd", "a"), version("pdf", "b")));
        // segundo Run só tem 'tdd' — 'pdf' (desanexada) deve sumir do disco
        materializer.materialize(workDir, List.of(version("tdd", "a2")));

        assertThat(Files.exists(workDir.resolve(".claude/skills/tdd/SKILL.md"))).isTrue();
        assertThat(Files.readString(workDir.resolve(".claude/skills/tdd/SKILL.md"))).isEqualTo("a2");
        assertThat(Files.exists(workDir.resolve(".claude/skills/pdf"))).isFalse();
    }

    @Test
    void conjuntoVazioNaoDeixaSkills() throws IOException {
        materializer.materialize(workDir, List.of(version("tdd", "a")));
        materializer.materialize(workDir, List.of());

        Path skillsRoot = workDir.resolve(".claude/skills");
        assertThat(Files.exists(skillsRoot)).isFalse();
    }

    @Test
    void nomeMaliciosoNaoEscapaDoWorkspace() {
        materializer.materialize(workDir, List.of(version("../../evil", "x")));

        // nada escreve fora do workspace
        assertThat(Files.exists(workDir.getParent().resolve("evil"))).isFalse();
        assertThat(Files.exists(workDir.resolveSibling("evil"))).isFalse();
    }

    @Test
    void nomesQueColidemAoSanitizarNaoPerdemSkill() throws IOException {
        // 'my skill' e 'my-skill' sanitizam para a mesma chave — AMBAS devem sobreviver (sem overwrite)
        materializer.materialize(workDir, List.of(version("my skill", "corpo-A"), version("my-skill", "corpo-B")));

        List<String> bodies = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(workDir.resolve(".claude/skills"))) {
            for (Path skillFile : walk.filter(p -> p.getFileName().toString().equals("SKILL.md")).toList()) {
                bodies.add(Files.readString(skillFile));
            }
        }
        assertThat(bodies).containsExactlyInAnyOrder("corpo-A", "corpo-B");
    }
}
