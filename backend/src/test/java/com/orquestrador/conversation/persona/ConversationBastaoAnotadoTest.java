package com.orquestrador.conversation.persona;

import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationRepository;
import com.orquestrador.conversation.ConversationTurn;
import com.orquestrador.conversation.ConversationTurnRepository;
import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V30 (R22-A, decisão 4 do dono): o sistema ANOTA o endereço do entregável do bastão — o campo
 * {@code bastao} do bloco {@code handoff} deixa de existir só no texto do sentinela
 * ({@code PersonaContinuationSentinel}) e vira coluna consultável ({@code conversation.bastao_path}).
 * O NOME do arquivo varia por etapa (dossiê no Q→P, estratégia no P→E — quem batiza é o CEO):
 * a anotação é LAST-WINS — a cada bastão postado, o endereço atual da conversa é atualizado.
 * É este endereço que o compositor da issue usa para entregar a ESTRATÉGIA a quem trabalha.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConversationBastaoAnotadoTest {

    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private ConversationTurnRepository turns;
    @Autowired
    private PersonaBatonRouter router;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private UUID persona(UUID companyId, String persona, String nome) {
        Agent agent = new Agent(UUID.randomUUID(), companyId, nome, "ceo", null);
        agent.configureRuntime("process", null, "LOCAL");
        agent.definePersona(persona);
        return agents.save(agent).getId();
    }

    /** Um turno já ANSWERED da persona — o insumo que o settle entrega ao roteador. */
    private ConversationTurn turnoAnswered(UUID companyId, UUID conversationId, UUID agentId) {
        ConversationTurn turno = new ConversationTurn(UUID.randomUUID(), conversationId, companyId,
                agentId, "mensagem");
        turno.answer("ok");
        return turns.save(turno);
    }

    @Test
    void bastaoPostadoAnotaOEnderecoNaConversaComLastWins() {
        UUID companyId = novaCompany();
        UUID questionadorId = persona(companyId, "questionador", "Q");
        persona(companyId, "planejador", "P");
        persona(companyId, "executor", "E");
        Conversation conversa = conversations.save(
                new Conversation(UUID.randomUUID(), companyId, "Geral"));

        // Q→P: o bastão do dossiê (o nome desta etapa).
        ConversationTurn turnoDoQ = turnoAnswered(companyId, conversa.getId(), questionadorId);
        router.afterCeoTurnAnswered(turnoDoQ, "dossiê pronto\n```handoff\n"
                + "{\"para\": \"planejador\", \"bastao\": \".orq/conversa/" + conversa.getId()
                + "/dossie.md\", \"resumo\": \"gerar estratégia\"}\n```\n");

        assertThat(conversations.findById(conversa.getId()).orElseThrow().getBastaoPath())
                .isEqualTo(".orq/conversa/" + conversa.getId() + "/dossie.md");

        // O turno do bastão pendente é atendido (o fluxo andou)...
        List<ConversationTurn> doConv = turns.findByConversationIdAndCompanyIdOrderByCreatedAtAsc(
                conversa.getId(), companyId);
        ConversationTurn pendente = doConv.get(doConv.size() - 1);
        pendente.answer("assumido");
        turns.save(pendente);

        // ...e o P→E passa a ESTRATÉGIA: o endereço anotado é ATUALIZADO (last-wins).
        UUID planejadorId = agents.findFirstByCompanyIdAndRoleAndPersonaOrderByCreatedAtAsc(
                companyId, "ceo", "planejador").orElseThrow().getId();
        ConversationTurn turnoDoP = turnoAnswered(companyId, conversa.getId(), planejadorId);
        router.afterCeoTurnAnswered(turnoDoP, "estratégia pronta\n```handoff\n"
                + "{\"para\": \"executor\", \"bastao\": \".orq/conversa/" + conversa.getId()
                + "/estrategia.md\", \"resumo\": \"contratar org\"}\n```\n");

        assertThat(conversations.findById(conversa.getId()).orElseThrow().getBastaoPath())
                .isEqualTo(".orq/conversa/" + conversa.getId() + "/estrategia.md");
    }

    @Test
    void bastaoDescartadoPeloDedupNaoMoveOEndereco() {
        // Idempotência do poster (M10): virada persona-flow PENDENTE na conversa → o bastão novo é
        // descartado — e o endereço anotado NÃO anda (anotar sem postar mentiria o estado do fluxo).
        UUID companyId = novaCompany();
        UUID questionadorId = persona(companyId, "questionador", "Q");
        persona(companyId, "planejador", "P");
        persona(companyId, "executor", "E");
        Conversation conversa = conversations.save(
                new Conversation(UUID.randomUUID(), companyId, "Geral"));

        ConversationTurn primeiro = turnoAnswered(companyId, conversa.getId(), questionadorId);
        router.afterCeoTurnAnswered(primeiro, "```handoff\n{\"para\": \"planejador\", \"bastao\":"
                + " \"a/dossie.md\", \"resumo\": \"r\"}\n```\n");
        assertThat(conversations.findById(conversa.getId()).orElseThrow().getBastaoPath())
                .isEqualTo("a/dossie.md");

        // Sem atender o turno pendente: o segundo bastão cai no dedup do poster.
        ConversationTurn segundo = turnoAnswered(companyId, conversa.getId(), questionadorId);
        router.afterCeoTurnAnswered(segundo, "```handoff\n{\"para\": \"planejador\", \"bastao\":"
                + " \"b/outro.md\", \"resumo\": \"r\"}\n```\n");

        assertThat(conversations.findById(conversa.getId()).orElseThrow().getBastaoPath())
                .isEqualTo("a/dossie.md");
    }
}
