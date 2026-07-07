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
 * SkillIndexer (2 camadas — ADR-0008): catálogo GLOBAL (identidade + savelog) + biblioteca por-company
 * (install copia da base global). Cobre criação, idempotência, nova versão, revert (em ambas as camadas),
 * redação de segredo, identidade global compartilhada com bibliotecas isoladas, skip e sync-só-global.
 * Chaves distintas por teste — o H2 é compartilhado no contexto e a identidade de skill é global.
 */
@SpringBootTest
@ActiveProfiles("test")
class SkillIndexerTest {

    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private CompanyService companies;
    @Autowired
    private SkillRepository skills;
    @Autowired
    private SkillVersionRepository skillVersions;
    @Autowired
    private CompanySkillRepository companySkills;
    @Autowired
    private CompanySkillVersionRepository companySkillVersions;

    @TempDir
    Path root;

    private UUID newCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private void writeSkill(String dir, String content) throws IOException {
        Path d = Files.createDirectories(root.resolve(dir));
        Files.writeString(d.resolve("SKILL.md"), content);
    }

    private static String skillMd(String name, String description, String body) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body;
    }

    @Test
    void importaCriaCatalogoEReimportarEhIdempotente() throws IOException {
        UUID company = newCompany();
        writeSkill("imp-tdd", skillMd("imp-tdd", "Test driven development", "# TDD\nciclo red-green\n"));
        writeSkill("imp-pdf", skillMd("imp-pdf", "Le e escreve PDF", "# PDF\n"));

        IndexResult first = indexer.index(company, root);
        assertThat(first.created()).isEqualTo(2);
        assertThat(first.updated()).isZero();
        assertThat(first.skipped()).isEmpty();
        assertThat(companySkills.findByCompanyIdOrderByCreatedAt(company)).hasSize(2);
        // catálogo global recebeu as identidades
        assertThat(skills.findBySkillKey("imp-tdd")).isPresent();
        assertThat(skills.findBySkillKey("imp-pdf")).isPresent();

        IndexResult second = indexer.index(company, root);
        assertThat(second.created()).isZero();
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(companySkills.findByCompanyIdOrderByCreatedAt(company)).hasSize(2);
    }

    @Test
    void conteudoMudadoGeraNovaVersaoNasDuasCamadas() throws IOException {
        UUID company = newCompany();
        writeSkill("chg-tdd", skillMd("chg-tdd", "v1", "# TDD\noriginal\n"));
        indexer.index(company, root);

        writeSkill("chg-tdd", skillMd("chg-tdd", "v2", "# TDD\nrevisado\n"));
        IndexResult res = indexer.index(company, root);

        assertThat(res.updated()).isEqualTo(1);
        UUID skillId = skills.findBySkillKey("chg-tdd").orElseThrow().getId();
        assertThat(skillVersions.maxVersion(skillId)).isEqualTo(2);  // savelog global tem 2 versões
        CompanySkill cs = companySkills.findByCompanyIdAndSkillId(company, skillId).orElseThrow();
        assertThat(companySkillVersions.countByCompanyIdAndCompanySkillId(company, cs.getId())).isEqualTo(2);
        assertThat(companySkillVersions.findByCompanyIdAndCompanySkillIdAndContentHash(company, cs.getId(), cs.getContentHash()))
                .isPresent();
    }

    @Test
    void revertDeConteudoNaoQuebraNemDuplicaVersao() throws IOException {
        UUID company = newCompany();
        writeSkill("rev-tdd", skillMd("rev-tdd", "v1", "# TDD\nA\n"));
        indexer.index(company, root);
        writeSkill("rev-tdd", skillMd("rev-tdd", "v2", "# TDD\nB\n"));
        indexer.index(company, root);
        writeSkill("rev-tdd", skillMd("rev-tdd", "v1", "# TDD\nA\n"));  // reverte para H1 (já existe)
        IndexResult res = indexer.index(company, root);

        assertThat(res.updated()).isEqualTo(1);  // não lança; conta como update
        UUID skillId = skills.findBySkillKey("rev-tdd").orElseThrow().getId();
        assertThat(skillVersions.maxVersion(skillId)).isEqualTo(2);  // savelog global não duplicou H1
        CompanySkill cs = companySkills.findByCompanyIdAndSkillId(company, skillId).orElseThrow();
        assertThat(companySkillVersions.countByCompanyIdAndCompanySkillId(company, cs.getId())).isEqualTo(2);
        CompanySkillVersion current = companySkillVersions
                .findByCompanyIdAndCompanySkillIdAndContentHash(company, cs.getId(), cs.getContentHash()).orElseThrow();
        assertThat(current.getVersion()).isEqualTo(1);  // corrente reaponta a versão 1 (H1)
    }

    @Test
    void segredoNaoVazaEmNenhumaCamada() throws IOException {
        UUID company = newCompany();
        String secretApiKey = "sk-LIVEsecretABCDEFGHIJ";
        String secretToken = "ghp_ABCDEFGHIJKLMNOPQRSTUVWX1234";
        writeSkill("sec-deploy", skillMd("sec-deploy", "Deploy", "# Deploy\napi_key: " + secretApiKey
                + "\nexport GH=" + secretToken + "\n"));

        indexer.index(company, root);

        // camada global
        Skill skill = skills.findBySkillKey("sec-deploy").orElseThrow();
        SkillVersion globalV = skillVersions.findById(skill.getCurrentVersionId()).orElseThrow();
        assertThat(globalV.getBody()).doesNotContain(secretApiKey).doesNotContain(secretToken)
                .contains(SecretRedactor.MASK);
        // camada company
        CompanySkill cs = companySkills.findByCompanyIdAndSkillId(company, skill.getId()).orElseThrow();
        CompanySkillVersion companyV = companySkillVersions
                .findByCompanyIdAndCompanySkillIdAndContentHash(company, cs.getId(), cs.getContentHash()).orElseThrow();
        assertThat(companyV.getBody()).doesNotContain(secretApiKey).doesNotContain(secretToken)
                .contains(SecretRedactor.MASK);
        // rastreabilidade: a versão da company aponta a versão global de origem
        assertThat(companyV.getSourceGlobalVersionId()).isEqualTo(globalV.getId());
    }

    @Test
    void identidadeGlobalUnicaComBibliotecasIsoladas() throws IOException {
        UUID companyA = newCompany();
        UUID companyB = newCompany();
        writeSkill("shared-tdd", skillMd("shared-tdd", "compartilhada", "# TDD\n"));

        indexer.index(companyA, root);
        indexer.index(companyB, root);

        // UMA identidade global para a mesma skill_key
        Skill global = skills.findBySkillKey("shared-tdd").orElseThrow();
        // DUAS instalações isoladas (uma por company), apontando a mesma identidade global
        CompanySkill csA = companySkills.findByCompanyIdAndSkillId(companyA, global.getId()).orElseThrow();
        CompanySkill csB = companySkills.findByCompanyIdAndSkillId(companyB, global.getId()).orElseThrow();
        assertThat(csA.getId()).isNotEqualTo(csB.getId());
        assertThat(companySkills.findByCompanyIdOrderByCreatedAt(companyA)).hasSize(1);
        assertThat(companySkills.findByCompanyIdOrderByCreatedAt(companyB)).hasSize(1);
    }

    @Test
    void pulaSkillSemNomeMasImportaAsValidas() throws IOException {
        UUID company = newCompany();
        writeSkill("ok-boa", skillMd("ok-boa", "valida", "# Boa\n"));
        writeSkill("ok-ruim", "---\ndescription: sem name\n---\n# Ruim\n");

        IndexResult res = indexer.index(company, root);

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.skipped()).anyMatch(s -> s.startsWith("ok-ruim"));
        assertThat(skills.findBySkillKey("ok-boa")).isPresent();
    }

    @Test
    void syncGlobalCatalogPopulaGlobalSemInstalarEmCompany() throws IOException {
        writeSkill("sync-only", skillMd("sync-only", "só global", "# Sync\n"));

        IndexResult res = indexer.syncGlobalCatalog(root);

        assertThat(res.created()).isEqualTo(1);
        Skill global = skills.findBySkillKey("sync-only").orElseThrow();
        assertThat(skillVersions.maxVersion(global.getId())).isEqualTo(1);
        // KnowAll conhece a skill, mas nenhuma company a instalou
        assertThat(companySkills.existsBySkillId(global.getId())).isFalse();
    }

    @Test
    void importSeletivoInstalaApenasOsDiretoriosPedidos() throws IOException {
        UUID company = newCompany();
        writeSkill("sel-alvo", skillMd("sel-alvo", "escolhida", "# Alvo\n"));
        writeSkill("sel-fora", skillMd("sel-fora", "nao pedida", "# Fora\n"));

        IndexResult res = indexer.index(company, root, List.of("sel-alvo"));

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.skipped()).isEmpty();
        assertThat(skills.findBySkillKey("sel-alvo")).isPresent();
        // a skill fora da seleção não é tocada em NENHUMA camada (nem global, nem company)
        assertThat(skills.findBySkillKey("sel-fora")).isEmpty();
        assertThat(companySkills.findByCompanyIdOrderByCreatedAt(company)).hasSize(1);
    }

    @Test
    void importSeletivoReportaChaveNaoEncontradaSemResolverCaminho() throws IOException {
        UUID company = newCompany();
        writeSkill("sel-unica", skillMd("sel-unica", "existe", "# Unica\n"));
        // diretório-isca FORA da raiz: chave com traversal não pode alcançá-lo (compara nome, não resolve caminho)
        Path outsideRoot = Files.createDirectories(root.getParent().resolve("sel-isca-fora"));
        Files.writeString(outsideRoot.resolve("SKILL.md"), skillMd("sel-isca-fora", "isca", "# Isca\n"));

        IndexResult res = indexer.index(company, root,
                List.of("sel-unica", "sel-inexistente", "../sel-isca-fora"));

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.skipped()).anyMatch(s -> s.startsWith("sel-inexistente"));
        assertThat(res.skipped()).anyMatch(s -> s.startsWith("../sel-isca-fora"));
        assertThat(skills.findBySkillKey("sel-unica")).isPresent();
        assertThat(skills.findBySkillKey("sel-isca-fora")).isEmpty();
    }

    @Test
    void raizInexistenteNaoQuebra() {
        UUID company = newCompany();
        IndexResult res = indexer.index(company, root.resolve("nao-existe"));
        assertThat(res.total()).isZero();
        assertThat(res.skipped()).isNotEmpty();
    }
}
