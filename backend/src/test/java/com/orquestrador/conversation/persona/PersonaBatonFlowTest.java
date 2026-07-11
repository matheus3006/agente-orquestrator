package com.orquestrador.conversation.persona;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationService;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.conversation.ProposalDescarteNotice;
import com.orquestrador.conversation.TurnStatus;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.governance.approval.Approval;
import com.orquestrador.governance.approval.ApprovalRepository;
import com.orquestrador.governance.approval.ApprovalStatus;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.issue.AbsorcaoDeIssueRouter;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O bastão de ponta a ponta pelo seam REAL do motor (ADR-0031, M5/M6/M9 + D5/D6/D7): a persona
 * emite o bloco tipado na RESPOSTA do run; o settle roteia — ```handoff``` vira continuação
 * (invisível) da persona-alvo; ```needs-grilling``` vira kickback VISÍVEL do Questionador;
 * ```proposta``` segue intocado pro gate T3; sem bloco, NADA acontece (confirma-antes fica idle);
 * no teto M9 a origem prossegue-e-sinaliza.
 */
@SpringBootTest
@ActiveProfiles("test")
class PersonaBatonFlowTest {

    @Autowired
    private ConversationService conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private HeartbeatDispatcher dispatcher;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private WakeupService wakeups;
    @Autowired
    private ApprovalRepository approvals;
    @Autowired
    private IssueService issues;
    @Autowired
    private IssueRepository issueRepo;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private ProposalDescarteNotice descarteNotice;
    @Autowired
    private AbsorcaoDeIssueRouter absorcoes;

    /** A fila do dispatcher é GLOBAL no H2 compartilhado: drenar antes/depois isola as ondas. */
    @BeforeEach
    @AfterEach
    void drenaFilaGlobal() {
        while (dispatcher.dispatchOnce() > 0) {
            // esvazia
        }
    }

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID persona(UUID companyId, String persona, String nome, String runtimeCommand) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, nome, "ceo", null);
        agent.configureRuntime("process", runtimeCommand, "LOCAL");
        agent.definePersona(persona);
        return agents.save(agent).getId();
    }

    /** Um turno interno do fluxo (bastão/kickback) postado direto no seam do repositório+wakeup. */
    private UUID turnoPara(UUID companyId, UUID conversationId, UUID agentId, String ownerMessage) {
        ConversationTurn turno = turns.save(new ConversationTurn(
                UUID.randomUUID(), conversationId, companyId, agentId, ownerMessage));
        wakeups.enqueue(companyId, agentId, null, "test:" + turno.getId());
        return turno.getId();
    }

    private List<ConversationTurn> thread(UUID companyId, UUID conversationId) {
        return turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(conversationId, companyId);
    }

    /** Um arquiteto designado no seu time — a ponta da corrente que o caso B resolve. */
    private UUID agenteEmTime(UUID companyId, String nome) {
        Team time = teams.save(new Team(UUID.randomUUID(), companyId, "Dev", "Landing"));
        Agent arquiteto = new Agent(UUID.randomUUID(), companyId, nome, "executor", null);
        arquiteto.assignTeam(time.getId());
        UUID arquitetoId = agents.save(arquiteto).getId();
        time.designarArquiteto(arquitetoId);
        teams.save(time);
        return arquitetoId;
    }

    /** Uma Issue ENTREGUE (checkout + complete = DONE de verdade) pelo agente dado. */
    private Issue entreguePor(UUID companyId, UUID agenteId, String titulo) {
        Issue issue = issues.create(companyId, titulo, "entregável original");
        UUID runId = UUID.randomUUID();
        issues.checkout(companyId, issue.getId(), agenteId, runId);
        issues.complete(companyId, issue.getId(), runId);
        return issue;
    }

    @Test
    void handoffDoQuestionadorViraContinuacaoDoPlanejador_invisivelNaThread() {
        UUID companyId = novaCompany();
        UUID questionadorId = persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' 'Dossiê carimbado.' '```handoff' "
                        + "'{\"para\": \"planejador\", \"bastao\": \".orq/conversa/x/dossie.md\","
                        + " \"resumo\": \"gerar estratégia\"}' '```'");
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' \"$ORQ_PROMPT\"");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        conversations.postMessage(companyId, negocio.getId(), "sim, pode seguir",
                ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1); // o run do Questionador emite o handoff

        // D6: UMA continuação do Planejador, com o bastão na sentinela — P e E nunca veem o dono.
        List<ConversationTurn> doConv = thread(companyId, negocio.getId());
        assertThat(doConv).hasSize(2);
        ConversationTurn continuacao = doConv.get(1);
        assertThat(continuacao.getAgentId()).isEqualTo(planejadorId);
        assertThat(continuacao.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(continuacao.getOwnerMessage())
                .startsWith("[continuação — bastão de questionador para planejador")
                .contains(".orq/conversa/x/dossie.md");

        // D4: invisível na thread do board (uma voz, uma conversa)…
        assertThat(conversations.thread(companyId, negocio.getId()))
                .noneMatch(turno -> turno.getId().equals(continuacao.getId()));

        // …e o run do Planejador atende a continuação SEM o dono no meio (bastão autônomo).
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        ConversationTurn atendido = turns.findById(continuacao.getId()).orElseThrow();
        assertThat(atendido.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(atendido.getCeoReply()).contains(".orq/conversa/x/dossie.md"); // o bastão chegou no prompt
        assertThat(atendido.getAgentId()).isNotEqualTo(questionadorId);
    }

    @Test
    void needsGrillingDoPlanejadorViraKickbackVisivelDoQuestionador() {
        UUID companyId = novaCompany();
        UUID questionadorId = persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' \"$ORQ_PROMPT\"");
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' 'Não dá para chutar.' '```needs-grilling' "
                        + "'{\"lacuna\": \"qual o orçamento mensal aceitável?\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        turnoPara(companyId, negocio.getId(), planejadorId,
                PersonaContinuationSentinel.bastao(CeoPersona.QUESTIONADOR, CeoPersona.PLANEJADOR,
                        ".orq/conversa/x/dossie.md", "gerar estratégia"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1); // o run do Planejador trava na lacuna

        // D7: kickback cirúrgico — turno do QUESTIONADOR, VISÍVEL (a re-grelha é para o dono ler),
        // carregando origem + lacuna (o run fresco do Q não tem outra memória do desvio).
        List<ConversationTurn> doConv = thread(companyId, negocio.getId());
        assertThat(doConv).hasSize(2);
        ConversationTurn kickback = doConv.get(1);
        assertThat(kickback.getAgentId()).isEqualTo(questionadorId);
        assertThat(kickback.getOwnerMessage())
                .startsWith("Re-grelha")
                .contains("planejador")
                .contains("qual o orçamento mensal aceitável?");
        assertThat(conversations.thread(companyId, negocio.getId()))
                .anyMatch(turno -> turno.getId().equals(kickback.getId()));
    }

    /** M9: kickbacks seguidos SEM o dono no meio batem o teto → a ORIGEM prossegue-e-sinaliza. */
    @Test
    void tetoDeKickbacksViraProssigaComAMelhorPremissa() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador", "printf '%s\\n' \"$ORQ_PROMPT\"");
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' 'Ainda não dá.' '```needs-grilling' "
                        + "'{\"lacuna\": \"segue sem resposta\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        // o tail já tem MAX kickbacks seguidos (ping-pong autônomo, o dono nunca respondeu)
        for (int i = 0; i < PersonaContinuationPoster.MAX_KICKBACKS_SEGUIDOS; i++) {
            ConversationTurn kickback = new ConversationTurn(UUID.randomUUID(), negocio.getId(),
                    companyId, planejadorId, KickbackTurn.ownerMessage(CeoPersona.PLANEJADOR, "lacuna " + i));
            kickback.answer("re-grelha " + i);
            turns.save(kickback);
        }
        turnoPara(companyId, negocio.getId(), planejadorId,
                PersonaContinuationSentinel.bastao(CeoPersona.QUESTIONADOR, CeoPersona.PLANEJADOR,
                        ".orq/conversa/x/dossie.md", "tentar de novo"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        List<ConversationTurn> doConv = thread(companyId, negocio.getId());
        ConversationTurn ultimo = doConv.get(doConv.size() - 1);
        assertThat(ultimo.getAgentId()).isEqualTo(planejadorId); // volta pra ORIGEM, não pro Q
        assertThat(ultimo.getStatus()).isEqualTo(TurnStatus.PENDING);
        assertThat(ultimo.getOwnerMessage()).startsWith("[continuação — prossiga");
        // e NÃO nasceu um novo kickback
        long kickbacks = doConv.stream()
                .filter(turno -> KickbackTurn.isKickback(turno.getOwnerMessage())).count();
        assertThat(kickbacks).isEqualTo(PersonaContinuationPoster.MAX_KICKBACKS_SEGUIDOS);
    }

    /** M9, reincidência: kickback DEPOIS do prossiga vira CORTE (turno visível, sem novo Run). */
    @Test
    void kickbackAposProssigaViraCorteSemNovoRun() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador", "printf '%s\\n' \"$ORQ_PROMPT\"");
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' 'Ainda não dá.' '```needs-grilling' "
                        + "'{\"lacuna\": \"segue sem resposta\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        for (int i = 0; i < PersonaContinuationPoster.MAX_KICKBACKS_SEGUIDOS; i++) {
            ConversationTurn kickback = new ConversationTurn(UUID.randomUUID(), negocio.getId(),
                    companyId, planejadorId, KickbackTurn.ownerMessage(CeoPersona.PLANEJADOR, "lacuna " + i));
            kickback.answer("re-grelha " + i);
            turns.save(kickback);
        }
        // o prossiga JÁ aconteceu e o Planejador travou DE NOVO na mesma lacuna
        ConversationTurn prossiga = new ConversationTurn(UUID.randomUUID(), negocio.getId(), companyId,
                planejadorId, PersonaContinuationSentinel.prossiga("segue sem resposta"));
        prossiga.answer("tentei — travei de novo");
        turns.save(prossiga);
        turnoPara(companyId, negocio.getId(), planejadorId,
                PersonaContinuationSentinel.bastao(CeoPersona.QUESTIONADOR, CeoPersona.PLANEJADOR,
                        ".orq/conversa/x/dossie.md", "de novo"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        List<ConversationTurn> doConv = thread(companyId, negocio.getId());
        ConversationTurn corte = doConv.get(doConv.size() - 1);
        assertThat(corte.getStatus()).isEqualTo(TurnStatus.ANSWERED); // ANSWERED direto: sem Run novo
        assertThat(corte.getOwnerMessage()).startsWith("Bastão parado");
        assertThat(corte.getCeoReply()).contains("segue sem resposta");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(0); // o ping-pong morreu aqui
    }

    /**
     * Caso B ponta a ponta (ADR-0034): o dono aponta a task ENTREGUE que ficou ruim; o
     * Questionador emite ```correcao``` com SÓ o número; o settle cria a corretiva com as
     * palavras do dono BYTE A BYTE e confirma VISÍVEL na thread — zero LLM em registro permanente.
     */
    @Test
    void correcaoDoQuestionadorProtocolaOCasoBPontaAPonta() {
        UUID companyId = novaCompany();
        String reclamacao = "a task #1 ficou ruim — o formulário não valida e-mail";
        persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' 'Encaminho a correção da #1.' '```correcao' '{\"issue\": 1}' '```'");
        UUID arquitetoId = agenteEmTime(companyId, "Ana Arquiteta");
        Issue entregue = entreguePor(companyId, arquitetoId, "Landing page");

        Conversation negocio = conversations.open(companyId, "Negócio");
        conversations.postMessage(companyId, negocio.getId(), reclamacao,
                ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1); // o run do Q emite o bloco

        Issue corretiva = issueRepo.findByCompanyIdAndSeq(companyId, 2).orElseThrow();
        assertThat(corretiva.getDescription())
                .as("verbatim: cópia mecânica do banco, nunca do bloco").isEqualTo(reclamacao);
        assertThat(corretiva.getCorrigeIssueId()).isEqualTo(entregue.getId());
        // confirmação VISÍVEL (diferente do bastão): o dono lê o protocolo na própria thread
        List<ConversationTurn> visiveis = conversations.thread(companyId, negocio.getId());
        ConversationTurn confirmacao = visiveis.get(visiveis.size() - 1);
        assertThat(confirmacao.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(confirmacao.getCeoReply()).contains("task #2").contains("Ana Arquiteta");
        assertThat(dispatcher.dispatchOnce()).as("nenhum run novo nasce da confirmação").isEqualTo(0);
    }

    /** M3: só o QUESTIONADOR roteia ```correcao``` — P/E emitindo = log + descarte (fail-soft). */
    @Test
    void correcaoDePlanejadorEDescartadaSemNada() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador", "printf '%s\\n' \"$ORQ_PROMPT\"");
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' 'Corrijo eu.' '```correcao' '{\"issue\": 1}' '```'");
        UUID arquitetoId = agenteEmTime(companyId, "Ana Arquiteta");
        entreguePor(companyId, arquitetoId, "Landing page");

        Conversation negocio = conversations.open(companyId, "Negócio");
        turnoPara(companyId, negocio.getId(), planejadorId,
                PersonaContinuationSentinel.bastao(CeoPersona.QUESTIONADOR, CeoPersona.PLANEJADOR,
                        ".orq/conversa/x/dossie.md", "gerar estratégia"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issueRepo.findByCompanyIdAndSeq(companyId, 2))
                .as("nenhuma corretiva nasce de bloco fora do balcão").isEmpty();
        assertThat(thread(companyId, negocio.getId()))
                .as("nenhum turno de protocolo/aviso além do bastão atendido").hasSize(1);
    }

    /**
     * Absorção ponta a ponta pelo seam REAL do motor (ADR-0037, M2/M4): a issue do dono nasce
     * órfã COM conversa ancorada e gatilho sentinela; o run do Planejador emite ```absorcao```
     * apontando o TIME; o settle resolve time→arquiteto, atribui e confirma VISÍVEL na thread —
     * sem wakeup do arquiteto (a issue nasce na mesa dele; o disparo segue os caminhos normais).
     */
    @Test
    void absorcaoDoPlanejadorAtribuiPontaAPonta() {
        UUID companyId = novaCompany();
        persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' 'Triei a #1.' '```absorcao' '{\"issue\": 1, \"dominio\": \"Dev\","
                        + " \"foco\": \"Landing\", \"racional\": \"é trabalho de frontend\"}' '```'");
        UUID arquitetoId = agenteEmTime(companyId, "Ana Arquiteta");

        Issue orfa = absorcoes.aoNascerDoDono(companyId, "Landing page nova", "hero novo", null);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1); // o run do Planejador emite o bloco

        assertThat(issueRepo.findById(orfa.getId()).orElseThrow().getAssigneeAgentId())
                .as("a issue nasce na mesa do arquiteto do time apontado").isEqualTo(arquitetoId);
        // confirmação VISÍVEL na thread da issue, com o racional do Planejador; gatilho invisível
        List<ConversationTurn> visiveis = conversations.thread(companyId, orfa.getConversationId());
        assertThat(visiveis).hasSize(1);
        assertThat(visiveis.get(0).getCeoReply())
                .contains("Dev / Landing").contains("Ana Arquiteta").contains("é trabalho de frontend");
        assertThat(dispatcher.dispatchOnce())
                .as("SEM wakeup automático do arquiteto (D5)").isEqualTo(0);
    }

    /** M4: só o PLANEJADOR roteia ```absorcao``` — Q/E emitindo = log + descarte (fail-soft). */
    @Test
    void absorcaoDoQuestionadorEDescartadaSemNada() {
        UUID companyId = novaCompany();
        UUID questionadorId = persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' 'Absorvo eu.' '```absorcao' '{\"issue\": 1, \"dominio\": \"Dev\","
                        + " \"foco\": \"Landing\", \"racional\": \"eu decido\"}' '```'");
        agenteEmTime(companyId, "Ana Arquiteta");
        Issue orfa = issues.create(companyId, "Órfã do board", null);

        Conversation negocio = conversations.open(companyId, "Negócio");
        conversations.postMessage(companyId, negocio.getId(), "cuida da #1",
                ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(issueRepo.findById(orfa.getId()).orElseThrow().getAssigneeAgentId())
                .as("bloco fora do Planejador nunca atribui").isNull();
        // nada além da pergunta atendida: nem protocolo, nem aviso — descarte com log
        assertThat(thread(companyId, negocio.getId())).hasSize(1);
        assertThat(questionadorId).isNotNull();
    }

    /** D3/A2: o ```proposta``` do Executor segue INTOCADO pro gate T3 — nada de virada. */
    @Test
    void propostaDoExecutorSegueProGateT3() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador", "printf '%s\\n' \"$ORQ_PROMPT\"");
        UUID executorId = persona(companyId, "executor", "CEO Executor",
                "printf '%s\\n' 'Protocolado 1:1.' '```proposta' "
                        + "'{\"acao\": \"HIRE_AGENT\", \"alvo\": {\"name\": \"Dev Backend\","
                        + " \"role\": \"executor\", \"racional\": \"skills tdd; motor claude-code;"
                        + " time de produto\"}, \"resumo\": \"contratar dev backend\","
                        + " \"area\": \"engenharia\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        turnoPara(companyId, negocio.getId(), executorId,
                PersonaContinuationSentinel.bastao(CeoPersona.PLANEJADOR, CeoPersona.EXECUTOR,
                        ".orq/conversa/x/estrategia.md", "protocolar"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        List<Approval> pendentes = approvals.findByCompanyIdAndStatusOrderByRequestedAt(
                companyId, ApprovalStatus.PENDING);
        assertThat(pendentes).hasSize(1);
        assertThat(pendentes.get(0).getTargetDetail()).contains("Dev Backend").contains("racional");
        assertThat(pendentes.get(0).getRequestedByAgentId()).isEqualTo(executorId);
        // nenhum turno novo além do atendido: proposta NÃO é virada de persona
        assertThat(thread(companyId, negocio.getId())).hasSize(1);
    }

    /**
     * ADR-0036 (caso C, cinto de emissor): o protocolo do gate é do EXECUTOR — o Questionador
     * emitindo ```proposta``` (mesmo canônica, shape perfeito) NÃO enche o gate: seria proposta
     * sem estratégia do Planejador, racional improvisado no menu (A2/A3 furados por dentro).
     */
    @Test
    void propostaDoQuestionadorNaoEncheOGate() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' 'Contrato eu mesmo.' '```proposta' "
                        + "'{\"acao\": \"HIRE_AGENT\", \"alvo\": {\"name\": \"Dev Backend\","
                        + " \"role\": \"executor\"}, \"resumo\": \"contratar dev backend\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        conversations.postMessage(companyId, negocio.getId(), "contrata um dev backend",
                ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(approvals.findByCompanyIdAndStatusOrderByRequestedAt(companyId, ApprovalStatus.PENDING))
                .as("proposta fora do Executor não vira Approval — o caminho é Q→P→E")
                .isEmpty();
    }

    /**
     * ADR-0036 (anti-silêncio): o REPLAY da Prova R3-A — o dono pede contratação direto, o
     * Questionador inventa o shape (`{"tipo":"contratacao",…}`), o gate fica vazio (fail-soft
     * intacto)… mas o SILÊNCIO morreu: aviso ANSWERED visível na thread, sem nenhum Run novo.
     */
    @Test
    void shapeInventadoNaoEncheOGateMasAvisaVisivelNaThread() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' 'Protocolo a contratação.' '```proposta' "
                        + "'{\"tipo\": \"contratacao\", \"papel\": \"dev backend\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Prova R3-A");
        conversations.postMessage(companyId, negocio.getId(), "contrata um dev backend",
                ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(approvals.findByCompanyIdAndStatusOrderByRequestedAt(companyId, ApprovalStatus.PENDING))
                .as("deny-by-default intacto: shape fora do contrato não vira Approval")
                .isEmpty();
        // O aviso é VISÍVEL (o dono precisa ver o que aconteceu) e já nasce ANSWERED (zero Run).
        List<ConversationTurn> visiveis = conversations.thread(companyId, negocio.getId());
        ConversationTurn aviso = visiveis.get(visiveis.size() - 1);
        assertThat(aviso.getStatus()).isEqualTo(TurnStatus.ANSWERED);
        assertThat(aviso.getOwnerMessage()).startsWith("Proposta descartada");
        assertThat(aviso.getCeoReply()).contains("gate");
        assertThat(dispatcher.dispatchOnce()).as("nenhum run nasce do aviso").isEqualTo(0);
    }

    /** ADR-0036: o settle reexecutado pelo reaper não duplica o aviso (padrão [gatilho] do caso B). */
    @Test
    void avisoDeDescarteEIdempotenteSobSettleReexecutado() {
        UUID companyId = novaCompany();
        UUID questionadorId = persona(companyId, "questionador", "CEO Questionador",
                "printf '%s\\n' \"$ORQ_PROMPT\"");
        Conversation negocio = conversations.open(companyId, "Negócio");
        ConversationTurn gatilho = turns.save(new ConversationTurn(UUID.randomUUID(),
                negocio.getId(), companyId, questionadorId, "contrata um dev backend"));

        descarteNotice.post(gatilho, 1, false, CeoPersona.QUESTIONADOR);
        descarteNotice.post(gatilho, 1, false, CeoPersona.QUESTIONADOR);

        long avisos = thread(companyId, negocio.getId()).stream()
                .filter(turno -> ProposalDescarteNotice.isAviso(turno.getOwnerMessage())).count();
        assertThat(avisos).isEqualTo(1);
    }

    /**
     * ADR-0036 × M9: o aviso de descarte NO MEIO do ping-pong não zera a régua de kickbacks —
     * senão um needs-grilling + proposta quebrada na MESMA resposta giraria para sempre
     * (cada rodada planta um aviso que "parece o dono de volta").
     */
    @Test
    void avisoDeDescarteNaoZeraAReguaDeKickbacks() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador", "printf '%s\\n' \"$ORQ_PROMPT\"");
        UUID planejadorId = persona(companyId, "planejador", "CEO Planejador",
                "printf '%s\\n' 'Ainda não dá.' '```needs-grilling' "
                        + "'{\"lacuna\": \"segue sem resposta\"}' '```'");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        for (int i = 0; i < PersonaContinuationPoster.MAX_KICKBACKS_SEGUIDOS; i++) {
            ConversationTurn kickback = new ConversationTurn(UUID.randomUUID(), negocio.getId(),
                    companyId, planejadorId, KickbackTurn.ownerMessage(CeoPersona.PLANEJADOR, "lacuna " + i));
            kickback.answer("re-grelha " + i);
            turns.save(kickback);
        }
        // o aviso plantado no tail (uma rodada descartou proposta) — NÃO é o dono de volta
        ConversationTurn aviso = new ConversationTurn(UUID.randomUUID(), negocio.getId(), companyId,
                planejadorId, "Proposta descartada — nada chegou ao gate [gatilho " + UUID.randomUUID() + "]");
        aviso.answer("1 bloco `proposta` foi descartado");
        turns.save(aviso);
        turnoPara(companyId, negocio.getId(), planejadorId,
                PersonaContinuationSentinel.bastao(CeoPersona.QUESTIONADOR, CeoPersona.PLANEJADOR,
                        ".orq/conversa/x/dossie.md", "tentar de novo"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        ConversationTurn ultimo = thread(companyId, negocio.getId())
                .get(thread(companyId, negocio.getId()).size() - 1);
        assertThat(ultimo.getOwnerMessage())
                .as("a régua continuou valendo: teto → prossiga, não um novo kickback")
                .startsWith("[continuação — prossiga");
    }

    /** D5: o confirma-antes fica IDLE — resposta sem bloco (a pergunta ao dono) não vira nada. */
    @Test
    void respostaSemBlocoNaoViraContinuacao_confirmaAntesIdle() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador", "printf '%s\\n' \"$ORQ_PROMPT\"");
        persona(companyId, "planejador", "CEO Planejador", "printf '%s\\n' \"$ORQ_PROMPT\"");

        Conversation negocio = conversations.open(companyId, "Novo negócio");
        conversations.postMessage(companyId, negocio.getId(), "quero abrir uma cafeteria",
                ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        // o prompt ecoado carrega os EXEMPLOS INDENTADOS dos blocos — e mesmo assim nada roteia
        // (cerca só vale em coluna 0): o backend nunca interpreta a prosa; a persona decide.
        assertThat(thread(companyId, negocio.getId())).hasSize(1);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(0);
    }
}
