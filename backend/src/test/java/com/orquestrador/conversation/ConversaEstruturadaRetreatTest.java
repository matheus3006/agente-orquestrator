package com.orquestrador.conversation;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatDispatcher;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-N3 (ADR-0039, D2): o RECUO INFORMADO da etapa 2. O flag {@code conversa-nativa=false} desliga a
 * saída estruturada SÓ do turno de CONVERSA (o Questionador — cuja {@code resposta} é a fala ao dono,
 * a prosa que o VENDO julga); a etapa 1 (Planejador/Executor, back-office) NÃO depende do flag e
 * segue estruturada. Prova que o recuo é cirúrgico: desligar a conversa não derruba o back-office.
 */
@SpringBootTest(properties = "orquestrador.saida-estruturada.conversa-nativa=false")
@ActiveProfiles("test")
class ConversaEstruturadaRetreatTest {

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

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID persona(UUID companyId, String persona, String nome) {
        Agent ceo = new Agent(UUID.randomUUID(), companyId, nome, "ceo", null);
        ceo.configureRuntime("process", "printf '%s\\n' \"$ORQ_PROMPT\"", "LOCAL");
        ceo.definePersona(persona);
        return agents.save(ceo).getId();
    }

    private String promptDoTurno(UUID companyId, String persona, String mensagem) {
        UUID conversationId = conversations.open(companyId, "Conversa").getId();
        ConversationService.TurnReceipt receipt = conversations.postMessage(
                companyId, conversationId, mensagem, ActorType.BOARD, UUID.randomUUID());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        return turns.findById(receipt.turnId()).orElseThrow().getCeoReply();
    }

    @Test
    void flagOffTiraOContratoDoQuestionadorMasMantemNoBackOffice() {
        UUID companyId = novaCompany();
        persona(companyId, "questionador", "CEO Questionador");

        // Questionador com o flag OFF: volta à prosa (regex) — sem o contrato de saída estruturada.
        String promptQuestionador = promptDoTurno(companyId, "questionador", "quero abrir uma cafeteria");
        assertThat(promptQuestionador)
                .as("etapa 2 recuada: a conversa volta à prosa").doesNotContain("Formato da saída (o motor VALIDA");
        // …mas a instrução da persona (o mundo fenced) segue de pé — nada quebrou no recuo.
        assertThat(promptQuestionador).contains("```handoff");

        // Planejador (etapa 1) IGNORA o flag: back-office segue estruturado.
        UUID companyB = novaCompany();
        persona(companyB, "planejador", "CEO Planejador");
        String promptPlanejador = promptDoTurno(companyB, "planejador", "[continuação — bastão]");
        assertThat(promptPlanejador)
                .as("etapa 1 independe do flag").contains("Formato da saída (o motor VALIDA");
    }
}
