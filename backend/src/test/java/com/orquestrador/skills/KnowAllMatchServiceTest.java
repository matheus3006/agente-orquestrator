package com.orquestrador.skills;

import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO do "casa conhecimento→skill" do KnowAll (T.1.b, ADR-0017/A.3): dado o conjunto de conhecimentos
 * requeridos por um molde, casa CADA um com o {@code skill_key} do catálogo GLOBAL (top-1, anotado com o
 * estado de install da company) e SINALIZA o que falta (gap → mão soberana de importar fecha o loop).
 * Espelha o {@link KnowAllSearchServiceTest} (catálogo global compartilhado no contexto → nonce por-método).
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowAllMatchServiceTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private KnowAllService knowAll;

    @TempDir
    Path installedRoot;
    @TempDir
    Path globalOnlyRoot;

    private static void writeSkill(Path root, String skillKey, String description, String body) throws IOException {
        Path dir = Files.createDirectories(root.resolve(skillKey));
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + skillKey + "\ndescription: " + description + "\n---\n" + body);
    }

    @Test
    void casaCadaConhecimentoAnotaInstall_sinalizaGap_deduplicaKeys_pulaEmBranco() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        String nonce = "kn" + UUID.randomUUID().toString().substring(0, 8);

        // alpha INSTALADA (Camada 2), gamma só no catálogo GLOBAL. Token distinto por skill → casa exatamente 1.
        writeSkill(installedRoot, "alpha-" + nonce, "conhece alphaknow" + nonce, "# corpo\n");
        indexer.index(companyId, installedRoot);
        writeSkill(globalOnlyRoot, "gamma-" + nonce, "conhece gammaknow" + nonce, "# corpo bodysecret" + nonce + "\n");
        indexer.syncGlobalCatalog(globalOnlyRoot);

        // 5 conhecimentos: casa alpha (INSTALLED) · casa gamma (AVAILABLE) · casa alpha DE NOVO (dup) ·
        // não casa (gap) · em branco (pulado, nem match nem gap).
        List<String> conhecimentos = List.of(
                "alphaknow" + nonce, "gammaknow" + nonce, "ALPHAKNOW" + nonce, "naoexiste" + nonce, "   ");
        KnowAllService.KnowledgeMatchResult result = knowAll.matchKnowledge(companyId, conhecimentos);

        // 4 entradas (a de branco foi pulada), em ordem de entrada
        assertThat(result.matches()).extracting(KnowAllService.KnowledgeMatch::knowledge)
                .containsExactly("alphaknow" + nonce, "gammaknow" + nonce, "ALPHAKNOW" + nonce, "naoexiste" + nonce);

        KnowAllService.KnowledgeMatch alpha = result.matches().get(0);
        assertThat(alpha.matched()).isTrue();
        assertThat(alpha.skillKey()).isEqualTo("alpha-" + nonce);
        assertThat(alpha.installState()).isEqualTo(KnowAllService.InstallState.INSTALLED);

        KnowAllService.KnowledgeMatch gamma = result.matches().get(1);
        assertThat(gamma.matched()).isTrue();
        assertThat(gamma.skillKey()).isEqualTo("gamma-" + nonce);
        assertThat(gamma.installState()).isEqualTo(KnowAllService.InstallState.AVAILABLE);

        // case-insensitive: ALPHAKNOW casa a mesma alpha (herda do search)
        assertThat(result.matches().get(2).skillKey()).isEqualTo("alpha-" + nonce);

        // gap: sem skill no catálogo → matched=false, skillKey null, sinalizado em gaps
        KnowAllService.KnowledgeMatch gap = result.matches().get(3);
        assertThat(gap.matched()).isFalse();
        assertThat(gap.skillKey()).isNull();
        assertThat(result.gaps()).containsExactly("naoexiste" + nonce);

        // skillset pronto pro molde: keys DISTINTAS, só as casadas, em ordem (alpha dedup, gamma)
        assertThat(result.matchedSkillKeys()).containsExactly("alpha-" + nonce, "gamma-" + nonce);

        // NUNCA o body: um conhecimento que só existe no corpo da gamma não casa (vira gap)
        KnowAllService.KnowledgeMatchResult bodyProbe =
                knowAll.matchKnowledge(companyId, List.of("bodysecret" + nonce));
        assertThat(bodyProbe.matches().get(0).matched()).isFalse();
        assertThat(bodyProbe.matchedSkillKeys()).isEmpty();
    }

    @Test
    void listaVaziaOuNulaDevolveResultadoVazio() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        assertThat(knowAll.matchKnowledge(companyId, List.of()).matches()).isEmpty();
        assertThat(knowAll.matchKnowledge(companyId, null).gaps()).isEmpty();
    }
}
