package com.orquestrador.execution.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * ONDE um Run executa (ortogonal ao QUE executa, que e o AgentAdapter). LOCAL = subprocesso
 * no host (dev); DOCKER = container isolado (canonico). Mesma interface; o stdout e entregue
 * linha-a-linha para virar StepTrace.
 */
public interface ExecutionTarget {

    Type type();

    /**
     * A postura de isolamento deste alvo (F.11/ADR-0025). Default FAIL-SAFE = {@code HOST}: alvo
     * que nao declarar nada pede self-sandbox ao motor — bypass jamais acontece por omissao.
     */
    default IsolationPosture posture() {
        return IsolationPosture.HOST;
    }

    Result run(Spec spec, UUID runId, Consumer<String> onStdoutLine);

    /**
     * MATA o Run em andamento (F-N4/ADR-0040, D4) — process GROUP, nao so o processo raiz: a
     * sonda R6-B provou que os dois motores deixam o processo-filho de uma tool call (build,
     * subprocesso do codex, servidor) ORFAO a um SIGKILL so-do-pai. Kill+resume nativo se sustenta
     * sem checkpoint proprio (a sessao sobrevive no JSONL, ADR-0038) — este metodo so precisa
     * garantir que NADA fica para tras. {@code true} = achou um Run vivo com este runId e matou;
     * {@code false} = nao havia nada rodando (corrida terminou antes) ou o alvo nao participa
     * (DEFAULT: fakes de teste seguem intactos — mudanca ADITIVA, molde de {@link #screenshotServer}).
     */
    default boolean interrupt(UUID runId) {
        return false;
    }

    /**
     * Le UM arquivo do workspace (F.9/ADR-0021) — o mesmo eixo de variacao do run: LOCAL le o
     * diretorio real (com real-path check anti-symlink), DOCKER passa por um container efemero
     * hermetico ({@code --network none}, volume {@code :ro}, sem credenciais). O {@code relativePath}
     * e uma CONSTANTE do pipeline (contrato de lugar), nunca input do agente. {@code maxBytes} e a
     * guarda mecanica contra OOM — estourou = falha legivel, nunca truncamento.
     */
    WorkspaceFileRead readWorkspaceFile(Spec spec, String relativePath, long maxBytes);

    /**
     * Lista os NOMES (basename) dos arquivos regulares de um diretorio do workspace — raso, sem
     * recursao, sem subdiretorios, sem symlink (emenda R29-A da ADR-0021: e o que permite capturar
     * o entregavel de pesquisa {@code *.md} de nome livre). O {@code relativeDir} e uma CONSTANTE
     * do pipeline; os nomes DEVOLVIDOS podem ser escolha do agente — quem os consumir num
     * {@code readWorkspaceFile} esta protegido pelas guardas do proprio leitor (real-path check no
     * LOCAL; argv exec-form + volume-only no DOCKER). Diretorio inexistente = listagem VAZIA (nada
     * publicado = run normal). Default FAIL-SAFE: alvo que nao declarar suporte responde falha
     * legivel, nunca lista vazia fingida (molde do {@link #interrupt}: fakes de teste intactos).
     */
    default WorkspaceDirListing listWorkspaceDir(Spec spec, String relativeDir) {
        return WorkspaceDirListing.failure("alvo " + type() + " nao suporta listagem de workspace");
    }

    /**
     * ESCREVE no workspace do Run os subtrees que os materializers deixaram em {@code workDir}
     * (par do leitor acima; mesmo eixo de variacao): LOCAL e IDENTIDADE (o workDir E o workspace
     * — ja esta tudo no lugar), DOCKER atravessa a arvore para o volume nomeado via container
     * efemero hermetico ({@code --network none}, sem credenciais, imagem do proprio adapter).
     * Semantica de REPLACE por subtree — o estado do host VENCE: presente no workDir substitui,
     * ausente no workDir e REMOVIDO do workspace (a idempotencia dos materializers atravessa).
     * Cada {@code relativePath} e uma CONSTANTE do pipeline (contrato de lugar), nunca input do
     * agente. Falha e do RUN (legivel), nao best-effort: rodar sem a arvore seria divergencia
     * silenciosa de comportamento (o bug do smoke F.9d).
     */
    WorkspaceFilesWrite writeWorkspaceFiles(Spec spec, List<String> relativePaths);

    /**
     * SOBE o servidor de preview declarado pelo manifest (F.10/ADR-0023) — mesmo eixo de variacao
     * do run, do leitor e do escritor (um eixo, um seam — 3ª aplicacao). DOCKER: 4º container da
     * familia, desanexado, SEM credencial POR CONSTRUCAO (o {@link PreviewServerSpec} nem carrega
     * mounts), volume {@code :ro} + tmpfs em {@code /tmp}, rede {@code orq-preview} sem saida,
     * porta efemera no loopback (o SO aloca; a lida de volta vem no resultado). LOCAL: spawn no
     * workDir (dev-only, mesma classe de risco do run LOCAL) — a porta do host E a do manifest.
     * Subir NAO espera a porta responder: o poll TCP e do modulo preview.server, nao do alvo.
     */
    PreviewServerStart startPreviewServer(PreviewServerSpec spec);

    /** DERRUBA por nome — IDEMPOTENTE: parar o que nao existe e OK (teardown best-effort). */
    PreviewServerStop stopPreviewServer(String serverName);

    /**
     * Os servidores de preview VIVOS deste alvo — runtime como fonte da verdade (DOCKER: {@code
     * docker ps} por label; LOCAL: registro em memoria). Falha de listagem degrada para vazio
     * com log (a superficie e de supervisao, nao de correcao).
     */
    List<PreviewServerLive> listPreviewServers();

    /** Ultimas linhas do output do servidor — a materia-prima da falha LEGIVEL de subida. */
    String previewServerLogTail(String serverName, int maxLines);

    /**
     * FOTOGRAFA o servidor de preview VIVO (F.13/ADR-0027) — mesmo eixo LOCAL×DOCKER do run/leitor/
     * escritor/servidor, mas o que varia aqui e a REACHABILITY do shot: um servidor DOCKER e
     * alcancavel por um container irmao na rede {@code orq-preview} pelo NOME do container na porta
     * do manifest ({@code http://<serverName>:<containerPort>/}); um servidor LOCAL binda o loopback
     * ({@code 127.0.0.1:<hostPort>}), inalcancavel de dentro de um container — exige o Chromium do
     * HOST. Por isso o shot NAO e um seam paralelo (seria a 4ª recusa do dispatch): e uma operacao
     * ADITIVA deste seam. DOCKER: 5º container da familia, hermetico e efemero ({@code --rm}), sem
     * credencial, renderiza a URL e devolve os bytes do PNG pelo stdout (molde do leitor da 0021).
     * LOCAL: spawn de Chromium do host (dev-only; ausente -> {@code RENDER_FAILED} best-effort).
     * DEFAULT {@code UNREACHABLE}: alvo que nao participa do shot nada fotografa (os fakes de teste
     * seguem intactos — mudanca ADITIVA, ADR-0025/0026). O shot NUNCA atrasa nem quebra a subida —
     * e observacao pura, best-effort (o capturador trata a falha, ADR-0027 §B5).
     */
    default ThumbnailCapture screenshotServer(ScreenshotSpec spec) {
        return ThumbnailCapture.unreachable("alvo nao fotografa servidores de preview");
    }

    /**
     * O MUNDO de workspaces por-Issue deste alvo (F.12/ADR-0026) — mesmo eixo de variacao do run:
     * DOCKER lista os volumes {@code orq_} filtrados ao formato de workspace por-Issue; LOCAL
     * deriva da arvore do root. Fonte da verdade e o MUNDO (como {@link #listPreviewServers}); o
     * {@code WorkspaceReaper} intersecta cada ref com o esperado derivado do banco. Cada ref sai no
     * vocabulario de {@link WorkspaceResolver#refFor} deste alvo. DEFAULT vazio: alvo que nao
     * participa do GC nao tem mundo (os fakes de teste seguem intactos — mudanca ADITIVA, ADR-0025).
     */
    default List<String> listIssueWorkspaces(WorkspaceResolver resolver) {
        return List.of();
    }

    /**
     * REMOVE o workspace por-Issue (F.12/ADR-0026): DOCKER {@code docker volume rm}; LOCAL delete
     * recursivo com GUARD de path canonicalizado sob o root. Sincrono, IDEMPOTENTE (sumido =
     * {@link IssueWorkspaceRemoval#ABSENT}), em-uso vira {@link IssueWorkspaceRemoval#IN_USE}
     * (o reaper pula e o proximo ciclo tenta). DEFAULT {@code ABSENT}: alvo sem mundo nada tem a
     * remover (fakes intactos — ADITIVO, ADR-0025).
     */
    default IssueWorkspaceRemoval removeIssueWorkspace(WorkspaceResolver resolver, UUID companyId,
                                                       UUID agentId, UUID issueId) {
        return IssueWorkspaceRemoval.ABSENT;
    }

    enum Type {
        LOCAL,
        DOCKER
    }

    /**
     * O resultado da coleta de UM workspace (F.12/ADR-0026) — os 4 estados que o reaper distingue:
     * {@code REMOVED} (coletado, audita), {@code ABSENT} (sumiu entre listar e remover — no-op),
     * {@code IN_USE} (montado por um PreviewServer vivo — WARN + skip, o proximo ciclo tenta) e
     * {@code FAILED} (motivo legivel no log — WARN + skip; o sweep nunca aborta por 1 candidato).
     */
    enum IssueWorkspaceRemoval {
        REMOVED,
        ABSENT,
        IN_USE,
        FAILED
    }

    /**
     * A POSTURA DE ISOLAMENTO do ambiente onde o run executa (F.11/ADR-0025) — propriedade do
     * ALVO, agnostica de motor: a gramatica de cada adapter a consome para decidir os flags de
     * sandbox do proprio CLI. {@code EXTERNALLY_SANDBOXED} = o ambiente JA e um sandbox externo
     * (container isolado, ADR-0023) e o motor pode dispensar o self-sandbox; {@code HOST} =
     * processo direto no host (dev-only, ADR-0016) e o motor DEVE se auto-sandboxar.
     */
    enum IsolationPosture {
        EXTERNALLY_SANDBOXED,
        HOST
    }

    /**
     * {@code credentialMounts} = volumes de credencial do PLANO montados ALÉM do workspace
     * (F.8/ADR-0016): o adapter declara os seus, o DOCKER monta, o LOCAL ignora. É a montagem
     * seletiva que isola um plano do outro. Nunca nulo (normalizado para vazio).
     */
    record Spec(Path workDir, List<String> command, Map<String, String> env, String image, String volumeName,
                List<VolumeMount> credentialMounts) {
        public Spec {
            credentialMounts = (credentialMounts == null) ? List.of() : List.copyOf(credentialMounts);
        }
    }

    /** Um volume nomeado montado num caminho do container ({@code -v volumeName:containerPath}). */
    record VolumeMount(String volumeName, String containerPath) {
    }

    /**
     * A receita de UM servidor de preview (F.10/ADR-0023): {@code command} e {@code port} vem do
     * manifest PERSISTIDO (o que se declara e o que executa); {@code serverName} e deterministico
     * ({@code orq_preview_<company>_<agent>_<issue>} — restart-safe); os ids viram labels (o reaper
     * varre por elas); os caps vem de property (politica do modulo preview.server, nao do alvo).
     * SEM credentialMounts POR CONSTRUCAO — o 4º container nunca ve credencial.
     */
    record PreviewServerSpec(String serverName, Path workDir, String volumeName, String image,
                             String command, int port, UUID companyId, UUID agentId, UUID issueId,
                             String memory, String cpus, String pidsLimit) {
    }

    /**
     * Resultado da subida — 2 estados: {@code started} (com a porta DO HOST onde o app responde:
     * efemera no DOCKER, a do manifest no LOCAL) ou {@code failed} (motivo legivel).
     */
    record PreviewServerStart(Integer hostPort, String error) {

        public static PreviewServerStart started(int hostPort) {
            return new PreviewServerStart(hostPort, null);
        }

        public static PreviewServerStart failure(String error) {
            return new PreviewServerStart(null, error);
        }

        public boolean failed() {
            return error != null;
        }
    }

    /** Resultado da derrubada — OK ou falha com motivo legivel; "nao existe" E OK (idempotente). */
    record PreviewServerStop(String error) {

        public static final PreviewServerStop OK = new PreviewServerStop(null);

        public static PreviewServerStop failure(String error) {
            return new PreviewServerStop(error);
        }

        public boolean failed() {
            return error != null;
        }
    }

    /** Um servidor de preview VIVO — identidade pelas labels/registro + porta do host. */
    record PreviewServerLive(String serverName, UUID companyId, UUID agentId, UUID issueId,
                             int hostPort) {
    }

    /**
     * A receita de UM shot (F.13/ADR-0027): o alvo fotografa o servidor VIVO. Os DOIS enderecos vem
     * juntos e cada adapter escolhe o seu por REACHABILITY — DOCKER usa {@code serverName:containerPort}
     * (o nome do container irmao na {@code orq-preview}, na porta DO MANIFEST, nao a efemera do host);
     * LOCAL usa {@code 127.0.0.1:hostPort} (o loopback do operador). {@code shotImage} e a imagem-shot
     * PINADA; {@code windowWidth}/{@code windowHeight} + {@code virtualTimeBudgetMs} sao a politica de
     * captura (viewport canonico + "esperar a SPA renderizar" sem Playwright); {@code maxBytes} e a
     * guarda mecanica (estourou = {@code CAP_EXCEEDED}, nunca trunca).
     */
    record ScreenshotSpec(String serverName, int containerPort, int hostPort, String shotImage,
                          int windowWidth, int windowHeight, int virtualTimeBudgetMs, long maxBytes) {
    }

    /**
     * O resultado do shot — 4 estados: {@code CAPTURED} (os bytes do PNG), {@code UNREACHABLE} (o
     * servidor nao respondeu na URL — o alvo pode nao ter subido ainda), {@code RENDER_FAILED} (o
     * browser falhou ou nao existe no host) e {@code CAP_EXCEEDED} (o PNG passou de {@code maxBytes}).
     * Molde do {@link WorkspaceFileRead}: bytes so em CAPTURED; nos demais um erro LEGIVEL que vai pro
     * log (o evento {@code THUMBNAIL_CAPTURE_FAILED} carrega so o enum do estado — detail so-IDs,
     * ADR-0009). O capturador persiste em CAPTURED e audita a falha nos demais (best-effort).
     */
    record ThumbnailCapture(byte[] bytes, Outcome outcome, String error) {

        public enum Outcome {
            CAPTURED,
            UNREACHABLE,
            RENDER_FAILED,
            CAP_EXCEEDED
        }

        public static ThumbnailCapture captured(byte[] bytes) {
            return new ThumbnailCapture(bytes, Outcome.CAPTURED, null);
        }

        public static ThumbnailCapture unreachable(String error) {
            return new ThumbnailCapture(null, Outcome.UNREACHABLE, error);
        }

        public static ThumbnailCapture renderFailed(String error) {
            return new ThumbnailCapture(null, Outcome.RENDER_FAILED, error);
        }

        public static ThumbnailCapture capExceeded(String error) {
            return new ThumbnailCapture(null, Outcome.CAP_EXCEEDED, error);
        }

        public boolean captured() {
            return outcome == Outcome.CAPTURED;
        }

        /**
         * Classifica a saida CRUA do browser nos 4 estados — compartilhada por DOCKER e LOCAL (a
         * fiacao muda, o veredicto nao). Ordem: cap estourado (o leitor devolveu {@code null} sem
         * truncar) vence; senao exit 0 com bytes = CAPTURED; senao erro de conexao no stderr =
         * UNREACHABLE (o servidor sumiu entre listar e fotografar); qualquer outro = RENDER_FAILED
         * (browser ausente/crashado, pagina vazia). Pura e testavel sem daemon nem browser.
         */
        public static ThumbnailCapture classify(int exitCode, byte[] pngBytes, String stderr, boolean overCap) {
            if (overCap) {
                return capExceeded("PNG excede o cap — nunca truncado");
            }
            if (exitCode == 0 && pngBytes != null && pngBytes.length > 0) {
                return captured(pngBytes);
            }
            String lower = stderr == null ? "" : stderr.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("err_connection") || lower.contains("err_name_not_resolved")
                    || lower.contains("err_address_unreachable") || lower.contains("err_socket_not_connected")) {
                return unreachable("servidor de preview nao respondeu na URL (exit " + exitCode + ")");
            }
            return renderFailed("shot falhou (exit " + exitCode + ")");
        }
    }

    record Result(int exitCode, String error) {
    }

    /**
     * Resultado da leitura de workspace — 4 estados: {@code found} (bytes), {@code absent}
     * (arquivo nao existe = run normal, nao e falha), {@code overCap} (excedeu {@code maxBytes} —
     * modo de erro DISTINTO, o chamador reage diferente) e {@code failed} (motivo legivel).
     */
    record WorkspaceFileRead(byte[] bytes, String error, boolean overCap) {

        /** Arquivo nao existe = run normal, nao e falha (contrato de lugar: nada publicado). */
        public static final WorkspaceFileRead ABSENT = new WorkspaceFileRead(null, null, false);

        public static WorkspaceFileRead found(byte[] bytes) {
            return new WorkspaceFileRead(bytes, null, false);
        }

        public static WorkspaceFileRead failure(String error) {
            return new WorkspaceFileRead(null, error, false);
        }

        public static WorkspaceFileRead capExceeded(String error) {
            return new WorkspaceFileRead(null, error, true);
        }

        public boolean found() {
            return bytes != null;
        }

        public boolean absent() {
            return bytes == null && error == null;
        }

        public boolean failed() {
            return error != null;
        }
    }

    /**
     * Resultado da listagem de diretorio do workspace — 2 estados: nomes (vazio = nada publicado,
     * run normal) ou falha com motivo legivel. Nomes sao basenames de arquivos REGULARES, nunca
     * caminhos: o consumidor resolve contra o diretorio-contrato que ele mesmo listou.
     */
    record WorkspaceDirListing(List<String> filenames, String error) {

        public static WorkspaceDirListing of(List<String> filenames) {
            return new WorkspaceDirListing(List.copyOf(filenames), null);
        }

        public static WorkspaceDirListing failure(String error) {
            return new WorkspaceDirListing(null, error);
        }

        public boolean failed() {
            return error != null;
        }
    }

    /** Resultado da escrita de workspace — 2 estados: OK ou falha com motivo legivel. */
    record WorkspaceFilesWrite(String error) {

        public static final WorkspaceFilesWrite OK = new WorkspaceFilesWrite(null);

        public static WorkspaceFilesWrite failure(String error) {
            return new WorkspaceFilesWrite(error);
        }

        public boolean failed() {
            return error != null;
        }
    }
}
