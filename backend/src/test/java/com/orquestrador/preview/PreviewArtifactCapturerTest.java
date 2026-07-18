package com.orquestrador.preview;

import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import com.orquestrador.execution.RunContext;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.9 (ADR-0021): o capturador conhece o contrato de lugar {@code .orq/preview/index.html} e o
 * transforma em PreviewArtifact versionado por Issue — dedup por content_hash contra a ULTIMA
 * versao, cap 2MB com falha legivel (nunca trunca), best-effort que JAMAIS lanca (a captura e
 * observacao, nao participante da maquina de estados).
 */
@SpringBootTest
@ActiveProfiles("test")
class PreviewArtifactCapturerTest {

    /** sha-256 de {@code <!doctype html><h1>preview v1</h1>} calculado FORA do codigo (shasum). */
    private static final String HASH_V1 = "6a1786344f62592071af8c3b2aeb9471dd347a3f45fe502ce63c90a498750426";

    @Autowired
    private PreviewArtifactCapturer capturer;
    @Autowired
    private PreviewArtifactRepository previews;
    @Autowired
    private WorkspaceResolver workspaces;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private IssueService issues;
    @Autowired
    private ActivityLogRepository activity;

    private record Cenario(UUID companyId, UUID agentId, UUID issueId) {
    }

    private Cenario cenarioLocal() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Agent agent = new Agent(UUID.randomUUID(), companyId, "A", "worker", null);
        agent.configureRuntime("process", "true", "LOCAL");
        UUID agentId = agents.save(agent).getId();
        UUID issueId = issues.create(companyId, "prototipo", null).getId();
        return new Cenario(companyId, agentId, issueId);
    }

    private void publica(Cenario cenario, String html) throws Exception {
        publicaArquivo(cenario, "index.html", html);
    }

    private void publicaArquivo(Cenario cenario, String nome, String conteudo) throws Exception {
        Path arquivo = workspaces.resolve(cenario.companyId(), cenario.agentId(), cenario.issueId())
                .resolve(".orq/preview/" + nome);
        Files.createDirectories(arquivo.getParent());
        Files.writeString(arquivo, conteudo);
    }

    private RunContext runDa(Cenario cenario) {
        return new RunContext(cenario.companyId(), cenario.agentId(), UUID.randomUUID(), cenario.issueId());
    }

    @Test
    void arquivoPublicadoViraVersaoUmComHashEIds() throws Exception {
        Cenario cenario = cenarioLocal();
        publica(cenario, "<!doctype html><h1>preview v1</h1>");
        RunContext run = runDa(cenario);

        capturer.captureIfPublished(run);

        List<PreviewArtifact> versoes =
                previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        PreviewArtifact artefato = versoes.get(0);
        assertThat(artefato.getVersion()).isEqualTo(1);
        assertThat(artefato.getContent()).isEqualTo("<!doctype html><h1>preview v1</h1>");
        assertThat(artefato.getContentHash()).isEqualTo(HASH_V1);
        assertThat(artefato.getRunId()).isEqualTo(run.runId());
        assertThat(artefato.getAgentId()).isEqualTo(cenario.agentId());
    }

    @Test
    void indexHtmlCapturadoCarregaTipoHtmlEProveniencia() throws Exception {
        Cenario cenario = cenarioLocal();
        publica(cenario, "<!doctype html><h1>preview v1</h1>");

        capturer.captureIfPublished(runDa(cenario));

        PreviewArtifact artefato = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(
                cenario.companyId(), cenario.issueId(), PreviewArtifact.class).get(0);
        assertThat(artefato.getContentType()).isEqualTo("html");
        assertThat(artefato.getSourceFilename()).isEqualTo("index.html");
    }

    @Test
    void pesquisaIndexMdEhCapturadaComTipoMarkdown() throws Exception {
        // O entregável de PESQUISA (R27-A): sem index.html, o .md convencionado vira a foto.
        Cenario cenario = cenarioLocal();
        publicaArquivo(cenario, "index.md", "# Elasticidade\n\n| corte | perda |\n|---|---|\n| 10% | 2% |\n");
        RunContext run = runDa(cenario);

        capturer.captureIfPublished(run);

        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(
                cenario.companyId(), cenario.issueId(), PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        PreviewArtifact artefato = versoes.get(0);
        assertThat(artefato.getVersion()).isEqualTo(1);
        assertThat(artefato.getContentType()).isEqualTo("markdown");
        assertThat(artefato.getSourceFilename()).isEqualTo("index.md");
        assertThat(artefato.getContent()).startsWith("# Elasticidade");
        assertThat(artefato.getRunId()).isEqualTo(run.runId());
    }

    @Test
    void indexHtmlVenceQuandoCoexisteComMd() throws Exception {
        // Decisão 2 do grill R29-A: protótipo pronto vence anotação solta — o ramo .md
        // só roda quando index.html está AUSENTE.
        Cenario cenario = cenarioLocal();
        publica(cenario, "<!doctype html><h1>app</h1>");
        publicaArquivo(cenario, "notas.md", "# rascunho");

        capturer.captureIfPublished(runDa(cenario));

        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(
                cenario.companyId(), cenario.issueId(), PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        assertThat(versoes.get(0).getContentType()).isEqualTo("html");
        assertThat(versoes.get(0).getSourceFilename()).isEqualTo("index.html");
    }

    @Test
    void pesquisaMdDeNomeLivreEhCapturadaQuandoEhAUnica() throws Exception {
        // A realidade medida na R27-A: os agentes de pesquisa escreveram 1 .md de NOME LIVRE
        // (cursor-pricing.md, elasticidade.md) — sem index.md, o UNICO .md do diretorio e a foto.
        Cenario cenario = cenarioLocal();
        publicaArquivo(cenario, "elasticidade.md", "# Elasticidade de preco\n\nresultado…");

        capturer.captureIfPublished(runDa(cenario));

        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(
                cenario.companyId(), cenario.issueId(), PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        assertThat(versoes.get(0).getContentType()).isEqualTo("markdown");
        assertThat(versoes.get(0).getSourceFilename()).isEqualTo("elasticidade.md");
        assertThat(versoes.get(0).getContent()).startsWith("# Elasticidade");
    }

    @Test
    void doisMdSemConvencaoEFalhaLegivelNadaPersistido() throws Exception {
        // Decisao 1 do grill R29-A: 2+ .md sem index.md = ambiguidade — falha LEGIVEL que
        // ensina a convencao, nunca escolha silenciosa de um dos arquivos.
        Cenario cenario = cenarioLocal();
        publicaArquivo(cenario, "cursor-pricing.md", "# A");
        publicaArquivo(cenario, "ci-digest.md", "# B");

        capturer.captureIfPublished(runDa(cenario));

        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(
                cenario.companyId(), cenario.issueId(), PreviewArtifact.class)).isEmpty();
        List<ActivityLog> eventos = activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId());
        assertThat(eventos).anySatisfy(evento -> {
            assertThat(evento.getAction()).isEqualTo("PREVIEW_CAPTURE_FAILED");
            assertThat(evento.getDetail()).contains("AMBIGUOUS_MARKDOWN");
        });
    }

    @Test
    void indexMdVenceOutrosMdQuandoCoexistem() throws Exception {
        // index.md e a convencao: presente, ele vence — os demais .md sao anotacao solta.
        Cenario cenario = cenarioLocal();
        publicaArquivo(cenario, "index.md", "# Entregavel");
        publicaArquivo(cenario, "rascunho.md", "# rascunho");

        capturer.captureIfPublished(runDa(cenario));

        List<PreviewArtifact> versoes = previews.findByCompanyIdAndIssueIdOrderByVersionDesc(
                cenario.companyId(), cenario.issueId(), PreviewArtifact.class);
        assertThat(versoes).hasSize(1);
        assertThat(versoes.get(0).getSourceFilename()).isEqualTo("index.md");
    }

    @Test
    void nadaPublicadoERunNormalNadaPersistido() {
        Cenario cenario = cenarioLocal();

        capturer.captureIfPublished(runDa(cenario));

        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class))
                .isEmpty();
        assertThat(activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId()))
                .extracting(ActivityLog::getAction)
                .doesNotContain("PREVIEW_CAPTURED", "PREVIEW_CAPTURE_FAILED");
    }

    @Test
    void mesmaFotoEmRunNovoNaoViraVersaoNova() throws Exception {
        // O workspace persiste entre runs: um run que nao toca o prototipo re-captura a MESMA
        // foto — o dedup por content_hash e o anti-duplicata sem furar o :ro (ADR-0021 §3).
        Cenario cenario = cenarioLocal();
        publica(cenario, "<!doctype html><h1>preview v1</h1>");

        capturer.captureIfPublished(runDa(cenario));
        capturer.captureIfPublished(runDa(cenario));

        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class))
                .hasSize(1);
    }

    @Test
    void fotoNovaViraVersaoDoisNaMesmaIssue() throws Exception {
        Cenario cenario = cenarioLocal();
        publica(cenario, "<!doctype html><h1>preview v1</h1>");
        capturer.captureIfPublished(runDa(cenario));

        publica(cenario, "<!doctype html><h1>preview v2 melhorada</h1>");
        RunContext segundoRun = runDa(cenario);
        capturer.captureIfPublished(segundoRun);

        List<PreviewArtifact> versoes =
                previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class);
        assertThat(versoes).hasSize(2);
        assertThat(versoes.get(0).getVersion()).isEqualTo(2);
        assertThat(versoes.get(0).getContent()).contains("v2 melhorada");
        assertThat(versoes.get(0).getRunId()).isEqualTo(segundoRun.runId());
    }

    @Test
    void runSemIssueNaoCaptura() {
        // Turno de conversa do CEO: delegador puro nao produz conteudo (ADR-0018).
        Cenario cenario = cenarioLocal();
        RunContext runSemIssue = new RunContext(cenario.companyId(), cenario.agentId(), UUID.randomUUID(), null);
        long antes = previews.count();

        capturer.captureIfPublished(runSemIssue);

        assertThat(previews.count()).isEqualTo(antes);
    }

    @Test
    void capEstouradoRegistraFalhaLegivelENuncaLanca() throws Exception {
        Cenario cenario = cenarioLocal();
        publica(cenario, "x".repeat(2 * 1024 * 1024 + 1));

        capturer.captureIfPublished(runDa(cenario));

        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class))
                .isEmpty();
        List<ActivityLog> eventos = activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId());
        assertThat(eventos).anySatisfy(evento -> {
            assertThat(evento.getAction()).isEqualTo("PREVIEW_CAPTURE_FAILED");
            assertThat(evento.getDetail()).contains("CAP_EXCEEDED");
        });
    }

    @Test
    void conteudoNaoUtf8EFalhaLegivelNuncaTransmuta() throws Exception {
        // Um binario publicado no contrato (ex.: cp logo.png) NAO pode virar sopa de U+FFFD:
        // transmutaria a "foto imutavel", furaria o cap no re-encode e colidiria o dedup
        // entre binarios distintos. Decode estrito -> falha legivel, nada persistido.
        Cenario cenario = cenarioLocal();
        Path contrato = workspaces.resolve(cenario.companyId(), cenario.agentId(), cenario.issueId())
                .resolve(".orq/preview/index.html");
        Files.createDirectories(contrato.getParent());
        Files.write(contrato, new byte[] {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFE, 0x00});

        capturer.captureIfPublished(runDa(cenario));

        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class))
                .isEmpty();
        List<ActivityLog> eventos = activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId());
        assertThat(eventos).anySatisfy(evento -> {
            assertThat(evento.getAction()).isEqualTo("PREVIEW_CAPTURE_FAILED");
            assertThat(evento.getDetail()).contains("INVALID_CONTENT");
        });
    }

    @Test
    void symlinkMaliciosoNaoVazaConteudoERegistraFalha() throws Exception {
        Cenario cenario = cenarioLocal();
        Path foraDoWorkspace = Files.createTempFile("segredo", ".txt");
        Files.writeString(foraDoWorkspace, "conteudo-que-nao-pode-vazar");
        Path contrato = workspaces.resolve(cenario.companyId(), cenario.agentId(), cenario.issueId())
                .resolve(".orq/preview/index.html");
        Files.createDirectories(contrato.getParent());
        Files.createSymbolicLink(contrato, foraDoWorkspace);

        capturer.captureIfPublished(runDa(cenario));

        assertThat(previews.findByCompanyIdAndIssueIdOrderByVersionDesc(cenario.companyId(), cenario.issueId(), PreviewArtifact.class))
                .isEmpty();
        assertThat(activity.findByCompanyIdOrderByCreatedAtDesc(cenario.companyId()))
                .extracting(ActivityLog::getAction)
                .contains("PREVIEW_CAPTURE_FAILED");
    }
}
