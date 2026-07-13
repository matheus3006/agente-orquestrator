package com.orquestrador.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRATO do {@code KnowAllService.fetchBody} (F-AT2, grill agente-como-tool D5): a leitura síncrona
 * do CORPO redigido da versão-base corrente do catálogo GLOBAL pela {@code skillKey} — o que o servidor
 * MCP do KnowAll expõe ao EXECUTOR para se autossuficientar mid-run. Chave inexistente/em branco →
 * {@code Optional.empty()} (o servidor MCP traduz numa resposta "não encontrada", nunca erro fatal).
 *
 * <p>Como {@link KnowAllSearchServiceTest}, o catálogo GLOBAL é compartilhado no mesmo contexto Spring
 * — cada método embute um {@code nonce} único na chave e busca por ele.
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowAllFetchBodyServiceTest {

    @Autowired
    private SkillIndexer indexer;
    @Autowired
    private KnowAllService knowAll;

    @TempDir
    Path globalRoot;

    private static void writeSkill(Path root, String skillKey, String body) throws IOException {
        Path dir = Files.createDirectories(root.resolve(skillKey));
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + skillKey + "\ndescription: d\n---\n" + body);
    }

    @Test
    void devolveOCorpoDaVersaoCorrenteDoCatalogoGlobalPelaChave() throws IOException {
        String nonce = "fb" + UUID.randomUUID().toString().substring(0, 8);
        String marcadorNoCorpo = "CORPO_CONTEUDO_" + nonce;
        writeSkill(globalRoot, "gamma-" + nonce, "# instruções\n" + marcadorNoCorpo + "\n");
        indexer.syncGlobalCatalog(globalRoot);

        Optional<String> body = knowAll.fetchBody("gamma-" + nonce);

        assertThat(body).isPresent();
        assertThat(body.get()).contains(marcadorNoCorpo);
    }

    @Test
    void chaveInexistenteOuEmBrancoDevolveVazio() {
        assertThat(knowAll.fetchBody("nao-existe-" + UUID.randomUUID())).isEmpty();
        assertThat(knowAll.fetchBody("   ")).isEmpty();
        assertThat(knowAll.fetchBody(null)).isEmpty();
    }
}
