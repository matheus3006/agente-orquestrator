package com.orquestrador.work.issue;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>A PROCEDÊNCIA do {@code runId}</b> (Fatia I-ter): um agente só age em nome de um Run que é
 * <b>dele</b>, <b>desta company</b> e <b>desta Issue</b> — nos dois endpoints que carimbam e
 * descarimbam o {@code execution_lock_run_id}.
 *
 * <p><b>Por que isto é um teste só para dois endpoints.</b> {@code /checkout} e {@code /complete}
 * tinham o MESMO defeito com duas caras. O {@code /checkout} lia o corpo como opcional e, sem ele,
 * <b>fabricava</b> {@code UUID.randomUUID()}: um lock que nomeia um Run que não existe. O
 * {@code /complete} exigia ator {@code AGENT} e <b>descartava a identidade</b> — o guard era só o
 * lock, que a listagem de issues publica. Somados, qualquer chave de agente da company liquidava
 * qualquer Issue em nome de qualquer {@code runId}. A cerca é uma pergunta só, feita nos dois
 * lugares, e por isso mora no {@code IssueService} (o chokepoint) e não nos controllers.
 *
 * <p><b>O caminho FELIZ é asserção de primeira classe aqui, não cortesia.</b> Uma cerca que recusa
 * tudo passaria em todos os casos negativos. A lição é da Fatia I-bis: quem prova a FIAÇÃO é o
 * caminho que tem de continuar funcionando.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProcedenciaDoRunHttpTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private IssueService issues;
    @Autowired
    private HeartbeatRunRepository runs;

    /**
     * <b>O {@code /checkout} sem corpo não fabrica mais lock sem procedência</b> (item A1).
     *
     * <p>Este era o pior produtor de lock órfão da casa, e o único alcançável em UMA chamada sem
     * hostilidade nenhuma: o serviço gerava {@code UUID.randomUUID()}, commitava a Issue
     * {@code IN_PROGRESS} com esse lock sintético e <b>devolvia o runId ao cliente</b> — que o
     * usaria depois no {@code /complete}. Ninguém criava linha de {@code heartbeat_run}, então em
     * ≤5 s o {@code LockOrfaoReaper} via "nenhum Run com futuro segura este lock" e liquidava a
     * <b>Issue viva</b> em {@code BLOCKED}.
     *
     * <p>As duas formas de "sem corpo" caem em ramos DIFERENTES do {@code ApiExceptionHandler}, e
     * as duas estão aqui de propósito: requisição sem corpo nenhum é
     * {@code HttpMessageNotReadableException} (400); corpo {@code &#123;&#125;} passa o parser e morre
     * no {@code @NotNull} do record, que é {@code MethodArgumentNotValidException} (422). Assertar
     * só uma deixaria a outra livre para regredir em silêncio.
     */
    @Test
    void checkoutSemCorpoNaoFabricaMaisLockSemProcedencia() throws Exception {
        Cenario cenario = cenario();

        int semCorpoNenhum = mvc.perform(post(checkoutUrl(cenario))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cenario.chave()))
                .andReturn().getResponse().getStatus();
        int corpoVazio = mvc.perform(post(checkoutUrl(cenario))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cenario.chave())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();

        Issue depois = issues.get(cenario.companyId(), cenario.issueId());
        assertSoftly(softly -> {
            softly.assertThat(semCorpoNenhum)
                    .as("requisicao sem corpo nenhum: o parser recusa antes da validacao")
                    .isEqualTo(400);
            softly.assertThat(corpoVazio)
                    .as("corpo {} passa o parser e morre no @NotNull do runId")
                    .isEqualTo(422);
            softly.assertThat(depois.getStatus())
                    .as("a Issue nao pode ter sido carimbada por uma chamada recusada")
                    .isEqualTo(IssueStatus.OPEN);
            softly.assertThat(depois.getExecutionLockRunId())
                    .as("e nenhum lock sintetico pode ter nascido")
                    .isNull();
        });
    }

    /**
     * <b>A cerca DISCRIMINA</b> (itens A1 e A5): as quatro maneiras de o {@code runId} do corpo não
     * ter procedência, e — no MESMO bloco — o caminho que tem de continuar passando.
     *
     * <p><b>Os cinco casos vivem juntos de propósito.</b> Uma cerca que recusasse tudo passaria nos
     * quatro negativos; uma que aceitasse tudo passaria no positivo. Só o conjunto prova que ela
     * <b>discrimina</b>, e o {@code assertSoftly} garante isso numa rodada — mas a garantia é
     * POR BLOCO, então o controle positivo está DENTRO dele, não depois.
     *
     * <p><b>O 3º caso é o 6º produtor de lock órfão, intra-company</b> (item A5), e era o mais
     * difícil de enxergar: a cerca do reaper correlaciona por {@code r.id} + {@code r.companyId} —
     * <b>não</b> por {@code r.issueId}. Carimbar a Issue Y com o {@code runId} de um Run VIVO da
     * Issue Z da MESMA company produzia um lock que nomeia um Run "com futuro", e por isso
     * <b>a rede nunca o tocava</b>: Y ficava refém do ciclo de vida de Z, para sempre e em silêncio.
     * É o único caso que a rede, por construção, não podia curar — e é a componente
     * {@code issueId} desta cerca que o fecha, na fonte.
     *
     * <p>Todos os negativos devolvem o MESMO 403, sem distinguir "não existe" de "não é seu": um
     * código diferente por motivo transformaria o endpoint num oráculo de existência de runs.
     */
    @Test
    void checkoutExigeRunDESTEagenteDESTAissueEDESTAcompany() throws Exception {
        Cenario cenario = cenario();
        UUID outroAgente = createAgent(cenario.board(), cenario.companyId(), "vizinho");
        UUID outraIssue = createIssue(cenario.board(), cenario.companyId(), "a issue Z");
        UUID companyAlheia = createCompany(UUID.randomUUID());

        int semLinha = tentarCheckout(cenario, UUID.randomUUID());
        int deOutroAgente = tentarCheckout(cenario,
                runReal(cenario.companyId(), outroAgente, cenario.issueId(), RunStatus.RUNNING));
        int deOutraIssueViva = tentarCheckout(cenario,
                runReal(cenario.companyId(), cenario.agentId(), outraIssue, RunStatus.RUNNING));
        int deOutraCompany = tentarCheckout(cenario,
                runReal(companyAlheia, cenario.agentId(), cenario.issueId(), RunStatus.RUNNING));
        Issue aposOsQuatro = issues.get(cenario.companyId(), cenario.issueId());

        UUID runProprio = runReal(cenario.companyId(), cenario.agentId(), cenario.issueId(),
                RunStatus.RUNNING);
        int comProcedencia = tentarCheckout(cenario, runProprio);
        Issue aposOproprio = issues.get(cenario.companyId(), cenario.issueId());

        assertSoftly(softly -> {
            softly.assertThat(semLinha)
                    .as("runId sem linha em heartbeat_run: o lock orfao que a I-bis mediu")
                    .isEqualTo(403);
            softly.assertThat(deOutroAgente)
                    .as("run REAL, mas de outro agente: agir em nome de run alheio")
                    .isEqualTo(403);
            softly.assertThat(deOutraIssueViva)
                    .as("A5 — run VIVO da issue Z: a rede nunca tocaria este lock")
                    .isEqualTo(403);
            softly.assertThat(deOutraCompany)
                    .as("run de outra company: cross-tenant pela porta do corpo")
                    .isEqualTo(403);
            softly.assertThat(aposOsQuatro.getStatus())
                    .as("nenhuma das quatro recusas pode ter carimbado a Issue")
                    .isEqualTo(IssueStatus.OPEN);
            softly.assertThat(aposOsQuatro.getExecutionLockRunId())
                    .as("nem deixado lock para tras")
                    .isNull();
            softly.assertThat(comProcedencia)
                    .as("CONTROLE POSITIVO: uma cerca que recusa tudo passaria nos quatro acima")
                    .isEqualTo(200);
            softly.assertThat(aposOproprio.getExecutionLockRunId())
                    .as("e o lock que nasce e o do run REAL do proprio agente")
                    .isEqualTo(runProprio);
        });
    }

    /**
     * <b>O {@code /complete} deixa de aceitar qualquer chave de agente da company</b> (item A2).
     *
     * <p>O defeito tinha uma forma quase invisível na leitura: o {@code IssueController.complete}
     * chamava {@code requireAgent(...)} <b>sem atribuição</b> — autenticava e jogava a identidade
     * fora — enquanto o {@code checkout}, 13 linhas acima, fazia {@code Actor agent = requireAgent(...)}.
     * O único guard restante era {@code execution_lock_run_id = :runId}, e o {@code executionLockRunId}
     * <b>é publicado na listagem de issues</b>. Somando: qualquer agente da company lia o lock de
     * qualquer Issue e a liquidava em nome do run alheio — inclusive levando-a a {@code DONE},
     * entrega falsa no board.
     *
     * <p>O controle positivo está no MESMO bloco: o dono do run precisa continuar concluindo, ou a
     * cerca teria consertado a segurança quebrando o produto.
     */
    @Test
    void completeSoAceitaOagenteDONOdoRun() throws Exception {
        Cenario cenario = cenario();
        UUID runProprio = runReal(cenario.companyId(), cenario.agentId(), cenario.issueId(),
                RunStatus.RUNNING);
        tentarCheckout(cenario, runProprio);

        UUID intruso = createAgent(cenario.board(), cenario.companyId(), "intruso");
        String chaveDoIntruso = mintKey(cenario.board(), cenario.companyId(), intruso);

        int peloIntruso = tentarComplete(cenario, chaveDoIntruso, runProprio);
        Issue aposOintruso = issues.get(cenario.companyId(), cenario.issueId());

        int peloDono = tentarComplete(cenario, cenario.chave(), runProprio);
        Issue aposOdono = issues.get(cenario.companyId(), cenario.issueId());

        assertSoftly(softly -> {
            softly.assertThat(peloIntruso)
                    .as("chave de agente da company NAO e autorizacao sobre o run alheio")
                    .isEqualTo(403);
            softly.assertThat(aposOintruso.getStatus())
                    .as("a Issue da vitima segue em execucao — nada de entrega falsa")
                    .isEqualTo(IssueStatus.IN_PROGRESS);
            softly.assertThat(aposOintruso.getExecutionLockRunId())
                    .as("e o lock do dono continua de pe")
                    .isEqualTo(runProprio);
            softly.assertThat(peloDono)
                    .as("CONTROLE POSITIVO: o dono do run conclui, ou a cerca quebrou o produto")
                    .isEqualTo(200);
            softly.assertThat(aposOdono.getStatus())
                    .as("risco baixo dispensa review (ADR-0012): o funil leva a DONE")
                    .isEqualTo(IssueStatus.DONE);
        });
    }

    /**
     * <b>Cunhar chave de agente é ato do OPERADOR</b> (item A3).
     *
     * <p>O {@code AgentApiKeyController.mint} chamava só {@code assertMember} e devolvia o segredo
     * em claro. Como o {@code ActorResolutionFilter} aceita a chave como credencial de agente, um
     * AGENTE autenticado podia cunhar a chave de <b>qualquer outro agente da company</b> — uma
     * escalada lateral que transforma "sou o agente A" em "sou quem eu quiser". Somada ao furo do
     * {@code /complete}, era a segunda metade do mesmo assalto descrito na
     * {@code docs/ficha-seguranca-complete.md}.
     *
     * <p>{@code assertBoardMember} já existia e é exatamente esta porta — a mesma da fila de
     * revisão e do StepTrace. O controle positivo (o board segue cunhando) está no mesmo bloco:
     * sem ele, esta cerca poderia ter quebrado todo o provisionamento de agentes.
     */
    @Test
    void mintDeChaveDeAgenteEhDoBoard_naoDeQualquerMembro() throws Exception {
        Cenario cenario = cenario();
        UUID alvo = createAgent(cenario.board(), cenario.companyId(), "alvo-da-escalada");
        String urlDoAlvo = "/api/companies/" + cenario.companyId() + "/agents/" + alvo + "/api-keys";

        int peloAgente = mvc.perform(post(urlDoAlvo)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cenario.chave()))
                .andReturn().getResponse().getStatus();
        int peloBoard = mvc.perform(post(urlDoAlvo)
                        .header("X-Board-User", cenario.board().toString()))
                .andReturn().getResponse().getStatus();

        assertSoftly(softly -> {
            softly.assertThat(peloAgente)
                    .as("agente cunhando a chave de outro agente e escalada lateral")
                    .isEqualTo(403);
            softly.assertThat(peloBoard)
                    .as("CONTROLE POSITIVO: o operador segue provisionando agentes")
                    .isEqualTo(201);
        });
    }

    // ---------------------------------------------------------------- cenário

    /** Company + time + agente com chave + Issue OPEN — o mínimo para exercer os dois endpoints. */
    private record Cenario(UUID board, UUID companyId, UUID agentId, String chave, UUID issueId) {
    }

    private Cenario cenario() throws Exception {
        UUID board = UUID.randomUUID();
        UUID companyId = createCompany(board);
        UUID agentId = createAgent(board, companyId, "dono-do-run");
        return new Cenario(board, companyId, agentId, mintKey(board, companyId, agentId),
                createIssue(board, companyId, "tarefa"));
    }

    private String checkoutUrl(Cenario cenario) {
        return "/api/companies/" + cenario.companyId() + "/issues/" + cenario.issueId() + "/checkout";
    }

    /** Um {@code POST /checkout} com o {@code runId} dado; devolve só o status HTTP. */
    private int tentarCheckout(Cenario cenario, UUID runId) throws Exception {
        return mvc.perform(post(checkoutUrl(cenario))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cenario.chave())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    /** Um {@code POST /complete} com a chave e o {@code runId} dados; devolve só o status HTTP. */
    private int tentarComplete(Cenario cenario, String chave, UUID runId) throws Exception {
        return mvc.perform(post("/api/companies/" + cenario.companyId() + "/issues/"
                        + cenario.issueId() + "/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"" + runId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    /** Um Run REAL do motor — a linha em {@code heartbeat_run} que dá procedência ao lock. */
    private UUID runReal(UUID companyId, UUID agentId, UUID issueId, RunStatus status) {
        return runs.save(new HeartbeatRun(UUID.randomUUID(), companyId, agentId, issueId, status)).getId();
    }

    // ---------------------------------------------------------------- helpers HTTP

    private UUID idFrom(String json) {
        return UUID.fromString(om.readTree(json).get("id").asText());
    }

    private UUID createCompany(UUID board) throws Exception {
        String body = mvc.perform(post("/api/companies")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Produto\",\"slug\":\"co-"
                                + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }

    /** Contratar exige time (ADR-0045); o agente vira arquiteto do próprio time (ADR-0017). */
    private UUID createAgent(UUID board, UUID companyId, String name) throws Exception {
        String timeBody = mvc.perform(post("/api/companies/" + companyId + "/teams")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dominio\":\"Dev\",\"foco\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID teamId = idFrom(timeBody);
        String body = mvc.perform(post("/api/companies/" + companyId + "/agents")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"worker\",\"teamId\":\""
                                + teamId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID agentId = idFrom(body);
        mvc.perform(patch("/api/companies/" + companyId + "/teams/" + teamId + "/arquiteto")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\"}"))
                .andExpect(status().isOk());
        return agentId;
    }

    private String mintKey(UUID board, UUID companyId, UUID agentId) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/agents/" + agentId + "/api-keys")
                        .header("X-Board-User", board.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("apiKey").asText();
    }

    private UUID createIssue(UUID board, UUID companyId, String title) throws Exception {
        String body = mvc.perform(post("/api/companies/" + companyId + "/issues")
                        .header("X-Board-User", board.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idFrom(body);
    }
}
