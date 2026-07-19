package com.orquestrador.org.agent;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HIRE EXIGE TIME (ADR-0045, D-ORG-9: "nada órfão") e a exceção da raiz codificada como PORTA, não
 * como atributo (D-ORG-A.3): {@link AgentHireService#hire} — a única porta que a API HTTP e o gate
 * de aprovação alcançam — exige time; {@link AgentHireService#hireAtRoot} contrata sem time e é
 * chamada só pelo {@link CeoPersonaSeeder} no nascimento da company. Não dá pra pedir a raiz de
 * fora: não existe caminho. Por isso digitar {@code role="ceo"} no dialog NÃO fura a regra.
 */
@SpringBootTest
@ActiveProfiles("test")
class HireExigeTimeTest {

    @Autowired
    private AgentHireService hires;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamService times;

    private AgentHireService.HireCommand pedido(String nome, String role, UUID teamId) {
        return new AgentHireService.HireCommand(nome, role, teamId, null, null, null, null, null,
                null, null);
    }

    @Test
    void hireSemTime_recusado() {
        UUID companyId = UUID.randomUUID();

        assertThatThrownBy(() -> hires.hire(companyId, pedido("Orfao", "executor", null),
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("time");
    }

    /** Digitar "ceo" na role não é passe livre: a regra da raiz é a PORTA, não o texto do campo. */
    @Test
    void hireSemTime_comRoleCeo_continuaRecusado() {
        UUID companyId = UUID.randomUUID();

        assertThatThrownBy(() -> hires.hire(companyId, pedido("Falso CEO", "ceo", null),
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void hireComTime_penduraNoTimeEDerivaOChefe() {
        UUID companyId = UUID.randomUUID();
        Team dev = times.create(companyId, "Dev", "backend", null);
        Agent gestor = agents.save(new Agent(UUID.randomUUID(), companyId, "Gestor", "executor", null));
        times.assignAgent(companyId, gestor.getId(), dev.getId());
        times.setGestor(companyId, dev.getId(), gestor.getId());

        Agent contratado = hires.hire(companyId, pedido("Nix", "executor", dev.getId()),
                ActorType.BOARD, UUID.randomUUID());

        Agent salvo = agents.findById(contratado.getId()).orElseThrow();
        assertThat(salvo.getTeamId()).isEqualTo(dev.getId());
        // a chefia NASCE derivada — ninguém informou reportsTo (o campo não existe mais no pedido)
        assertThat(salvo.getReportsToId()).isEqualTo(gestor.getId());
    }

    @Test
    void hireComTimeInexistenteOuDeOutraCompany_naoEncontrado() {
        UUID companyId = UUID.randomUUID();
        Team deOutra = times.create(UUID.randomUUID(), "Dev", "backend", null);

        assertThatThrownBy(() -> hires.hire(companyId, pedido("Nix", "executor", UUID.randomUUID()),
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> hires.hire(companyId, pedido("Nix", "executor", deOutra.getId()),
                ActorType.BOARD, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    /** A porta da raiz: sem time, chefe nenhum — é assim que CEO+personas nascem (e só assim). */
    @Test
    void hireAtRoot_contrataNaRaizSemTime() {
        UUID companyId = UUID.randomUUID();

        Agent ceo = hires.hireAtRoot(companyId, pedido("CEO", "ceo", null),
                ActorType.SYSTEM, null);

        Agent salvo = agents.findById(ceo.getId()).orElseThrow();
        assertThat(salvo.getTeamId()).isNull();
        assertThat(salvo.getReportsToId()).isNull();
    }
}
