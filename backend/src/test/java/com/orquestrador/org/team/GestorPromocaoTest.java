package com.orquestrador.org.team;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O marcador de autoridade delegada do gestor (ADR-0046, D-ORG-2): todo time com gestor nasce
 * ESTAGIO; só um ato explícito e board-only do dono promove pra PROMOVIDO, com rastro de
 * quem/quando. Transição só ESTAGIO→PROMOVIDO (sem rebaixar); reseta pra ESTAGIO toda vez que o
 * TITULAR do posto troca (mini-grill da fatia, Pergunta 2) — reatribuir o MESMO agente não conta.
 */
@SpringBootTest
@ActiveProfiles("test")
class GestorPromocaoTest {

    @Autowired
    private TeamService service;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private AgentRepository agents;

    private Agent novoAgente(UUID companyId, String nome) {
        return agents.save(new Agent(UUID.randomUUID(), companyId, nome, "executor", null));
    }

    private Agent novoGestor(UUID companyId, Team time, String nome) {
        Agent agente = novoAgente(companyId, nome);
        service.assignAgent(companyId, agente.getId(), time.getId());
        service.setGestor(companyId, time.getId(), agente.getId());
        return agente;
    }

    @Test
    void designarGestorNasceEmEstagio() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);

        novoGestor(companyId, time, "Ana");

        Team salvo = teams.findById(time.getId()).orElseThrow();
        assertThat(salvo.getGestorStatus()).isEqualTo(GestorStatus.ESTAGIO);
        assertThat(salvo.gestorPromovido()).isFalse();
        assertThat(salvo.getGestorPromovidoEm()).isNull();
    }

    @Test
    void promoverExigeGestorDesignado() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);

        assertThatThrownBy(() -> service.promoverGestor(companyId, time.getId(), ActorType.BOARD,
                UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void promoverGravaStatusEQuemEQuando() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);
        novoGestor(companyId, time, "Ana");
        UUID dono = UUID.randomUUID();

        Team promovido = service.promoverGestor(companyId, time.getId(), ActorType.BOARD, dono);

        assertThat(promovido.getGestorStatus()).isEqualTo(GestorStatus.PROMOVIDO);
        assertThat(promovido.gestorPromovido()).isTrue();
        assertThat(promovido.getGestorPromovidoEm()).isNotNull();
        assertThat(promovido.getGestorPromovidoPorActorType()).isEqualTo(ActorType.BOARD);
        assertThat(promovido.getGestorPromovidoPorActorId()).isEqualTo(dono);
    }

    @Test
    void promoverDuasVezesEhConflito_naoHaRebaixarNemRepromover() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);
        novoGestor(companyId, time, "Ana");
        service.promoverGestor(companyId, time.getId(), ActorType.BOARD, UUID.randomUUID());

        assertThatThrownBy(() -> service.promoverGestor(companyId, time.getId(), ActorType.BOARD,
                UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void trocarOTitularDoPostoResetaParaEstagio() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);
        Agent ana = novoGestor(companyId, time, "Ana");
        service.promoverGestor(companyId, time.getId(), ActorType.BOARD, UUID.randomUUID());

        Agent bruno = novoAgente(companyId, "Bruno");
        service.assignAgent(companyId, bruno.getId(), time.getId());
        Team comBruno = service.setGestor(companyId, time.getId(), bruno.getId());

        assertThat(comBruno.getGestorAgentId()).isEqualTo(bruno.getId());
        assertThat(comBruno.getGestorStatus()).isEqualTo(GestorStatus.ESTAGIO);
        assertThat(comBruno.getGestorPromovidoEm()).isNull();
        assertThat(comBruno.getGestorPromovidoPorActorType()).isNull();
        assertThat(comBruno.getGestorPromovidoPorActorId()).isNull();

        // e a Ana, se voltar a gerenciar (mesmo este ou outro time), começa do zero de novo — o
        // reset não guarda histórico por pessoa (candidato A do mini-grill, não o B).
        assertThat(ana.getId()).isNotEqualTo(bruno.getId());
    }

    @Test
    void limparOPostoTambemReseta_agentIdNuloEhTroca() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);
        novoGestor(companyId, time, "Ana");
        service.promoverGestor(companyId, time.getId(), ActorType.BOARD, UUID.randomUUID());

        Team semGestor = service.setGestor(companyId, time.getId(), null);

        assertThat(semGestor.getGestorAgentId()).isNull();
        assertThat(semGestor.getGestorStatus()).isEqualTo(GestorStatus.ESTAGIO);
    }

    @Test
    void reatribuirOMesmoAgenteNaoResetaAPromocao() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Marketing", "growth", null);
        Agent ana = novoGestor(companyId, time, "Ana");
        service.promoverGestor(companyId, time.getId(), ActorType.BOARD, UUID.randomUUID());

        // PATCH redundante com o MESMO titular — não é uma troca, não deveria apagar confiança
        Team ainda = service.setGestor(companyId, time.getId(), ana.getId());

        assertThat(ainda.getGestorAgentId()).isEqualTo(ana.getId());
        assertThat(ainda.getGestorStatus()).isEqualTo(GestorStatus.PROMOVIDO);
        assertThat(ainda.getGestorPromovidoEm()).isNotNull();
    }
}
