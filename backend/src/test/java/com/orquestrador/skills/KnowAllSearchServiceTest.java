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
 * CONTRATO da busca do KnowAll (Fatia 7b) no H2 (impl substring): contém/não-contém, top-N,
 * case-insensitive, busca só METADADOS (nunca o body) e anota o estado de install por-company.
 *
 * <p>O catálogo GLOBAL é compartilhado entre os testes do mesmo contexto Spring — por isso cada método
 * embute um {@code nonce} único nos metadados e busca por ele, isolando-se das skills de outros testes.
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowAllSearchServiceTest {

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
    void buscaGlobalAnotaInstallRespeitaTopNCaseInsensitiveENuncaOBody() throws IOException {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        String nonce = "kw" + UUID.randomUUID().toString().substring(0, 8);

        // Duas skills INSTALADAS na company (global + Camada 2) e uma só no catálogo GLOBAL (não instalada).
        // Todas carregam o nonce na description; a global-only tem um token secreto SÓ no body.
        writeSkill(installedRoot, "alpha-" + nonce, "primeira skill " + nonce, "# corpo alpha\n");
        writeSkill(installedRoot, "beta-" + nonce, "segunda skill " + nonce, "# corpo beta\n");
        indexer.index(companyId, installedRoot);

        writeSkill(globalOnlyRoot, "gamma-" + nonce, "terceira skill " + nonce, "# corpo bodyonly" + nonce + "\n");
        indexer.syncGlobalCatalog(globalOnlyRoot);

        // Busca pelo nonce: casa as 3 (metadados). Ordem determinística por skill_key (alpha < beta < gamma).
        List<KnowAllService.SearchResult> all = knowAll.search(companyId, nonce, 20);
        assertThat(all).extracting(KnowAllService.SearchResult::skillKey)
                .containsExactly("alpha-" + nonce, "beta-" + nonce, "gamma-" + nonce);

        // Estado de install anotado por-company: alpha/beta INSTALLED, gamma AVAILABLE.
        assertThat(all).filteredOn(result -> result.skillKey().equals("gamma-" + nonce))
                .singleElement()
                .satisfies(result -> assertThat(result.installState()).isEqualTo(KnowAllService.InstallState.AVAILABLE));
        assertThat(all).filteredOn(result -> !result.skillKey().equals("gamma-" + nonce))
                .allSatisfy(result -> assertThat(result.installState()).isEqualTo(KnowAllService.InstallState.INSTALLED));

        // top-N: limit=2 corta para os 2 primeiros (alpha, beta).
        assertThat(knowAll.search(companyId, nonce, 2)).extracting(KnowAllService.SearchResult::skillKey)
                .containsExactly("alpha-" + nonce, "beta-" + nonce);

        // case-insensitive: buscar em CAIXA ALTA acha o mesmo conjunto.
        assertThat(knowAll.search(companyId, nonce.toUpperCase(), 20)).hasSize(3);

        // busca por name específico -> só a alpha.
        assertThat(knowAll.search(companyId, "alpha-" + nonce, 20))
                .extracting(KnowAllService.SearchResult::skillKey).containsExactly("alpha-" + nonce);

        // NUNCA o body: o token que só existe no corpo da gamma não é encontrado.
        assertThat(knowAll.search(companyId, "bodyonly" + nonce, 20)).isEmpty();

        // query em branco -> vazio (sem varrer o catálogo inteiro).
        assertThat(knowAll.search(companyId, "   ", 20)).isEmpty();
    }
}
