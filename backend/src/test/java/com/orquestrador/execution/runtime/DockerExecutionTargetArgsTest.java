package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valida a construcao do `docker run` sem precisar do daemon (que esta fora nesta maquina).
 * E o mesmo caminho de captura do LocalExecutionTarget, so muda o launcher.
 */
class DockerExecutionTargetArgsTest {

    @Test
    void posturaEhExternamenteSandboxada() {
        // F.11 (ADR-0025): o container E o sandbox externo (ADR-0023) — a gramatica do motor
        // consome isto para dispensar o self-sandbox. O default da interface e HOST (fail-safe).
        assertThat(new DockerExecutionTarget().posture())
                .isEqualTo(ExecutionTarget.IsolationPosture.EXTERNALLY_SANDBOXED);
    }

    @Test
    void montaOArgvDoDockerRun() {
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                Path.of("/w"),
                List.of("sh", "-c", "echo hi"),
                Map.of("K", "V"),
                "alpine:3",
                "vol1",
                List.of());
        UUID runId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        List<String> argv = target.buildArgs(spec, runId);

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "--name", "orq_run_" + runId, "-w", "/workspace",
                "-v", "vol1:/workspace",
                "-e", "K=V",
                "alpine:3", "sh", "-c", "echo hi");
    }

    @Test
    void leitorDeWorkspaceEHermeticoPorConstrucao() {
        // F.9 (ADR-0021): o leitor roda SEM rede (--network none), com o volume :ro e SEM os
        // volumes de credencial — mesmo quando o Spec os carrega. A imagem e a do proprio adapter
        // (garantidamente presente na maquina: o run acabou de rodar nela).
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                Path.of("/w"),
                List.of(),
                Map.of("K", "V"),
                "orq-claude-code:2.1.201",
                "vol1",
                List.of(new ExecutionTarget.VolumeMount("orq_claude_creds", "/home/node")));

        List<String> argv = target.buildReadArgs(spec, ".orq/preview/index.html");

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "--network", "none",
                "-v", "vol1:/workspace:ro",
                "orq-claude-code:2.1.201",
                "cat", "/workspace/.orq/preview/index.html");
    }

    @Test
    void listadorDeWorkspaceEHermeticoERaso() {
        // Emenda R29-A: mesmo molde do leitor (SEM rede, volume :ro, imagem do adapter);
        // find raso (-maxdepth 1 -type f) = so arquivos regulares do diretorio-contrato.
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                java.nio.file.Path.of("/tmp/x"),
                List.of(),
                Map.of("K", "V"),
                "orq-claude-code:2.1.201",
                "vol1",
                List.of(new ExecutionTarget.VolumeMount("orq_claude_creds", "/home/node")));

        List<String> argv = target.buildListArgs(spec, ".orq/preview");

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "--network", "none",
                "-v", "vol1:/workspace:ro",
                "orq-claude-code:2.1.201",
                "find", "/workspace/.orq/preview", "-maxdepth", "1", "-type", "f");
    }

    @Test
    void escritorDeWorkspaceEHermeticoEExtraiAArvoreMaterializadaNoVolume() {
        // Escritor (par do leitor F.9/ADR-0021): SEM rede, SEM credencial (mesmo quando o Spec a
        // carrega), imagem do proprio adapter. Replace por subtree: o rm e o extract rodam no MESMO
        // sh — se o extract falhar depois do rm, o exit != 0 vira falha legivel do run (nunca um
        // workspace pela metade rodando em silencio). O -i abre o stdin para o tar do host.
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                Path.of("/w"),
                List.of(),
                Map.of("K", "V"),
                "orq-claude-code:2.1.201",
                "vol1",
                List.of(new ExecutionTarget.VolumeMount("orq_claude_creds", "/home/node")));

        List<String> argv = target.buildWriteArgs(spec,
                List.of(".claude/skills", ".claude/CLAUDE.md"), true);

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "-i", "--network", "none",
                "-v", "vol1:/workspace",
                "orq-claude-code:2.1.201",
                "sh", "-c",
                "rm -rf /workspace/.claude/skills /workspace/.claude/CLAUDE.md"
                        + " && tar -xf - -C /workspace");
    }

    @Test
    void escritorComAUniaoDasConvencoesRemoveOCerebroVelhoDoVolume() {
        // ADR-0032 (D6/P2): o executor lista a UNIÃO das convenções conhecidas — a ativa vem na
        // árvore do tar; a inativa, ausente no host, vira SÓ rm no volume (a faxina do DOCKER de
        // graça). Num volume limpo o rm extra é no-op; num workspace pré-G3 ele apaga o espelho
        // .claude/ que o codex nunca leu.
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                Path.of("/w"), List.of(), Map.of(), "orq-codex:0.142.5", "vol1", List.of());

        List<String> argv = target.buildWriteArgs(spec,
                List.of(".claude/skills", ".codex/skills", ".claude/CLAUDE.md", "AGENTS.md"), true);

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "-i", "--network", "none",
                "-v", "vol1:/workspace",
                "orq-codex:0.142.5",
                "sh", "-c",
                "rm -rf /workspace/.claude/skills /workspace/.codex/skills"
                        + " /workspace/.claude/CLAUDE.md /workspace/AGENTS.md"
                        + " && tar -xf - -C /workspace");
    }

    @Test
    void escritorSemArvoreMaterializadaSoRemoveOsSubtreesDoVolume() {
        // SkillSet vazio / carta removida: o host nao tem a arvore — o replace vira SO remocao
        // (a skill removida do conjunto some do volume, como some do workDir no LOCAL). Sem
        // payload nao ha stdin: nem -i, nem tar.
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                Path.of("/w"), List.of(), Map.of(), "orq-claude-code:2.1.201", "vol1", List.of());

        List<String> argv = target.buildWriteArgs(spec, List.of(".claude/skills"), false);

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "--network", "none",
                "-v", "vol1:/workspace",
                "orq-claude-code:2.1.201",
                "sh", "-c",
                "rm -rf /workspace/.claude/skills");
    }

    @Test
    void tarDoHostEmpacotaAArvoreMaterializadaRelativaAoWorkDir() {
        // O lado host do escritor: empacota SO os subtrees que existem no workDir (o filtro de
        // existencia e do chamador — tar com membro ausente falharia o pipe inteiro).
        DockerExecutionTarget target = new DockerExecutionTarget();

        List<String> argv = target.buildTarArgs(Path.of("/w"), List.of(".claude/skills"));

        assertThat(argv).containsExactly("tar", "-cf", "-", "-C", "/w", ".claude/skills");
    }

    @Test
    void tarDoHostDesligaOsMetadadosAppleDouble() {
        // Achado do smoke com daemon real: sem COPYFILE_DISABLE=1 o bsdtar do macOS (host de
        // dev) empacota ._SKILL.md e afins — lixo que apareceria DENTRO do workspace do agente
        // a cada run. No GNU tar do Linux o env e ignorado (portavel).
        DockerExecutionTarget target = new DockerExecutionTarget();

        ProcessBuilder hostTar = target.hostTar(Path.of("/w"), List.of(".claude/skills"));

        assertThat(hostTar.command()).containsExactly("tar", "-cf", "-", "-C", "/w", ".claude/skills");
        assertThat(hostTar.environment()).containsEntry("COPYFILE_DISABLE", "1");
    }

    @Test
    void servidorDePreviewSobeDesanexadoHermeticoComCapsEPortaEfemeraNoLoopback() {
        // F.10 (ADR-0023): o 4º container da familia — desanexado (-d, o start nao bloqueia),
        // nome deterministico + labels (restart-safe: o reaper varre por elas), rede orq-preview
        // (porta funciona, internet nao), volume :ro + tmpfs /tmp (o que roda e o que o run
        // liquidou), caps por property, porta EFEMERA no loopback (-p 127.0.0.1::porta — o SO
        // aloca, nunca LAN). SEM credencial POR CONSTRUCAO: o PreviewServerSpec nem carrega mounts.
        DockerExecutionTarget target = new DockerExecutionTarget();
        UUID companyId = UUID.fromString("142f1ac5-0000-0000-0000-000000000001");
        UUID agentId = UUID.fromString("6fcaff70-0000-0000-0000-000000000002");
        UUID issueId = UUID.fromString("c408ecc2-0000-0000-0000-000000000003");

        List<String> argv = target.buildServerArgs(new ExecutionTarget.PreviewServerSpec(
                "orq_preview_142f1ac5_6fcaff70_c408ecc2", Path.of("/w"), "vol1",
                "orq-claude-code:2.1.201", "node server.js", 3000,
                companyId, agentId, issueId, "512m", "1", "256"));

        assertThat(argv).containsExactly(
                "docker", "run", "-d", "--name", "orq_preview_142f1ac5_6fcaff70_c408ecc2",
                "--label", "orq.preview=true",
                "--label", "orq.company=" + companyId,
                "--label", "orq.agent=" + agentId,
                "--label", "orq.issue=" + issueId,
                "--network", "orq-preview",
                "-v", "vol1:/workspace:ro",
                "--tmpfs", "/tmp",
                "-w", "/workspace",
                "--memory", "512m", "--cpus", "1", "--pids-limit", "256",
                "-p", "127.0.0.1::3000",
                "orq-claude-code:2.1.201",
                "sh", "-c", "node server.js");
    }

    @Test
    void portaEfemeraELidaDeVoltaDoDaemon() {
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildServerPortArgs("orq_preview_a_b_c", 3000))
                .containsExactly("docker", "port", "orq_preview_a_b_c", "3000/tcp");
        // O daemon responde "127.0.0.1:55001" (a porta que o SO alocou no loopback).
        assertThat(DockerExecutionTarget.parseHostPort("127.0.0.1:55001")).isEqualTo(55001);
        // Daemon com IPv6 habilitado devolve duas linhas — a do loopback IPv4 e a que vale.
        assertThat(DockerExecutionTarget.parseHostPort("127.0.0.1:55001\n[::1]:55001")).isEqualTo(55001);
        assertThat(DockerExecutionTarget.parseHostPort("lixo")).isNull();
    }

    @Test
    void stopDerrubaForcadoPorNome() {
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildServerStopArgs("orq_preview_a_b_c"))
                .containsExactly("docker", "rm", "-f", "orq_preview_a_b_c");
    }

    @Test
    void tailDeLogsAlimentaAFalhaLegivelDeSubida() {
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildServerLogTailArgs("orq_preview_a_b_c", 20))
                .containsExactly("docker", "logs", "--tail", "20", "orq_preview_a_b_c");
    }

    @Test
    void vivosSaemDoDaemonPorLabelComIdentidadeEPortas() {
        // Runtime como fonte da verdade (ADR-0023 §8): vivo/porta saem de docker ps por label —
        // tabela de vivos no banco = estado especular, rejeitada.
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildServerListArgs()).containsExactly(
                "docker", "ps", "--filter", "label=orq.preview=true",
                "--format",
                "{{.Names}}\t{{.Label \"orq.company\"}}\t{{.Label \"orq.agent\"}}\t{{.Label \"orq.issue\"}}\t{{.Ports}}");

        UUID companyId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID issueId = UUID.randomUUID();
        List<ExecutionTarget.PreviewServerLive> vivos = DockerExecutionTarget.parseServerList(
                "orq_preview_a_b_c\t" + companyId + "\t" + agentId + "\t" + issueId
                        + "\t127.0.0.1:55001->3000/tcp\n"
                        + "linha-lixo-sem-tabs\n");

        assertThat(vivos).containsExactly(new ExecutionTarget.PreviewServerLive(
                "orq_preview_a_b_c", companyId, agentId, issueId, 55001));
    }

    @Test
    void redeDePreviewNasceSemMasqueradePortaFuncionaInternetNao() {
        // ADR-0023 §4: o meio-termo real entre --network none (nao publica porta) e bridge
        // aberta (beacon-avel por minutos) — NAT desligado: host<->container e subnet local.
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildNetworkInspectArgs())
                .containsExactly("docker", "network", "inspect", "orq-preview");
        assertThat(target.buildNetworkCreateArgs()).containsExactly(
                "docker", "network", "create",
                "-o", "com.docker.network.bridge.enable_ip_masquerade=false",
                "orq-preview");
    }

    @Test
    void montaCadaCredencialDoPlanoComoVolumeAlemDoWorkspace() {
        // F.8 (ADR-0016): a montagem seletiva acontece AQUI — o Spec declara os volumes de
        // credencial e o argv os carrega; um plano que não está no Spec não entra no container.
        DockerExecutionTarget target = new DockerExecutionTarget();
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                Path.of("/w"),
                List.of("claude", "-p", "oi"),
                Map.of(),
                "orq-claude-code:2.1.201",
                "vol1",
                List.of(new ExecutionTarget.VolumeMount("orq_claude_creds", "/home/node")));
        UUID runId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        List<String> argv = target.buildArgs(spec, runId);

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "--name", "orq_run_" + runId, "-w", "/workspace",
                "-v", "vol1:/workspace",
                "-v", "orq_claude_creds:/home/node",
                "orq-claude-code:2.1.201", "claude", "-p", "oi");
    }

    // ===== F-N4 (ADR-0040): interrupt — mesmo molde do stop de preview server =====

    @Test
    void interruptDerrubaForcadoPeloNomeDeterministicoDoRun() {
        // Molde EXATO de stopDerrubaForcadoPorNome (preview server): rm -f mata e remove num so
        // passo. Nao ha processo de HOST a rastrear — o nome do container e a chave inteira.
        DockerExecutionTarget target = new DockerExecutionTarget();
        UUID runId = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

        assertThat(target.buildInterruptArgs(runId))
                .containsExactly("docker", "rm", "-f", "orq_run_" + runId);
    }

    // ===== F.12 (ADR-0026): GC de workspaces — argv/parse/classify puros, sem daemon =====

    @Test
    void listaOsVolumesDoMundoFiltradosPeloNamespaceOrq() {
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildVolumeListArgs()).containsExactly(
                "docker", "volume", "ls", "--filter", "name=orq_", "--format", "{{.Name}}");
    }

    @Test
    void parseDoMundoMantemSoOFormatoDeWorkspacePorIssue() {
        // Denylist ESTRUTURAL: creds/pgdata/workspace-de-agente nao casam o formato -> nem candidato
        // nem orfao. O orfao sintetico (orq_deadbeef_...) casa o formato -> entra no mundo (WARN depois).
        WorkspaceResolver resolver = new WorkspaceResolver("/x");
        String saidaDoDaemon = String.join("\n",
                "orq_142f1ac5_deadbeef_67aa95e5",
                "orq_deadbeef_cafebabe_12345678",
                "orq_claude_creds",
                "orq_codex_creds",
                "orq_142f1ac5_deadbeef",
                "",
                "  orq_b92cb312_5f207cc0_96c1d2ea  ");

        assertThat(DockerExecutionTarget.parseIssueVolumes(saidaDoDaemon, resolver::isIssueVolumeName))
                .containsExactly(
                        "orq_142f1ac5_deadbeef_67aa95e5",
                        "orq_deadbeef_cafebabe_12345678",
                        "orq_b92cb312_5f207cc0_96c1d2ea");
    }

    @Test
    void removeVolumePorNome() {
        DockerExecutionTarget target = new DockerExecutionTarget();

        assertThat(target.buildVolumeRemoveArgs("orq_142f1ac5_deadbeef_67aa95e5"))
                .containsExactly("docker", "volume", "rm", "orq_142f1ac5_deadbeef_67aa95e5");
    }

    @Test
    void classificaOResultadoDaRemocaoDoVolume() {
        // Os 4 estados que o reaper distingue: coletado / sumiu / em-uso (skip) / falha legivel.
        assertThat(DockerExecutionTarget.classifyVolumeRemoval(0, "orq_142f1ac5_deadbeef_67aa95e5"))
                .isEqualTo(ExecutionTarget.IssueWorkspaceRemoval.REMOVED);
        assertThat(DockerExecutionTarget.classifyVolumeRemoval(1,
                "Error response from daemon: no such volume: orq_x"))
                .isEqualTo(ExecutionTarget.IssueWorkspaceRemoval.ABSENT);
        assertThat(DockerExecutionTarget.classifyVolumeRemoval(1,
                "Error response from daemon: remove orq_x: volume is in use - [abc123]"))
                .isEqualTo(ExecutionTarget.IssueWorkspaceRemoval.IN_USE);
        assertThat(DockerExecutionTarget.classifyVolumeRemoval(1,
                "Error response from daemon: something else broke"))
                .isEqualTo(ExecutionTarget.IssueWorkspaceRemoval.FAILED);
    }

    // ===== F.13 (ADR-0027): shot — o 5º container fotografa o servidor VIVO, argv/classify puros =====

    @Test
    void shotFotografaOServidorIrmaoNaOrqPreviewPeloNomeDoContainerNaPortaDoManifest() {
        // ADR-0027 §B4: o shot VARIA por reachability — um servidor DOCKER e alcancavel por um
        // container irmao na rede orq-preview pelo NOME do container na porta DO MANIFEST
        // (containerPort=3000), nunca a efemera do host (hostPort=55001). Hermetico e efemero (--rm),
        // SEM credencial, imagem-shot PINADA; o PNG volta pelo stdout (molde do leitor da 0021: ... && cat).
        DockerExecutionTarget target = new DockerExecutionTarget();

        List<String> argv = target.buildScreenshotArgs(new ExecutionTarget.ScreenshotSpec(
                "orq_preview_142f1ac5_6fcaff70_c408ecc2", 3000, 55001, "orq-preview-shot:alpine3.21",
                1280, 800, 5000, 2 * 1024 * 1024));

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "--network", "orq-preview",
                "orq-preview-shot:alpine3.21", "sh", "-c",
                "chromium --headless --no-sandbox --disable-gpu --disable-dev-shm-usage --hide-scrollbars"
                        + " --window-size=1280,800 --virtual-time-budget=5000 --screenshot=/tmp/s.png"
                        + " http://orq_preview_142f1ac5_6fcaff70_c408ecc2:3000/ && cat /tmp/s.png");
    }

    @Test
    void classificaOResultadoDoShotNosQuatroEstados() {
        // Os 4 estados que o capturador distingue: capturado / cap estourado / inalcancavel / falha.
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

        ExecutionTarget.ThumbnailCapture capturado =
                ExecutionTarget.ThumbnailCapture.classify(0, png, "", false);
        assertThat(capturado.outcome()).isEqualTo(ExecutionTarget.ThumbnailCapture.Outcome.CAPTURED);
        assertThat(capturado.bytes()).isEqualTo(png);

        // cap estourou: o leitor devolveu null SEM truncar -> CAP_EXCEEDED
        assertThat(ExecutionTarget.ThumbnailCapture.classify(0, null, "", true).outcome())
                .isEqualTo(ExecutionTarget.ThumbnailCapture.Outcome.CAP_EXCEEDED);

        // exit != 0 com erro de conexao no stderr -> UNREACHABLE (o servidor sumiu)
        assertThat(ExecutionTarget.ThumbnailCapture.classify(1, null,
                "[0705/...] net::ERR_CONNECTION_REFUSED", false).outcome())
                .isEqualTo(ExecutionTarget.ThumbnailCapture.Outcome.UNREACHABLE);

        // browser ausente/crashado (exit != 0, sem bytes, sem erro de rede) -> RENDER_FAILED
        assertThat(ExecutionTarget.ThumbnailCapture.classify(127, null, "chromium: not found", false).outcome())
                .isEqualTo(ExecutionTarget.ThumbnailCapture.Outcome.RENDER_FAILED);

        // exit 0 mas stdout vazio (pagina em branco) -> RENDER_FAILED, nunca um CAPTURED de zero bytes
        assertThat(ExecutionTarget.ThumbnailCapture.classify(0, new byte[0], "", false).outcome())
                .isEqualTo(ExecutionTarget.ThumbnailCapture.Outcome.RENDER_FAILED);
    }
}
