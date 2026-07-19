package com.orquestrador.conversation;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 (ADR-0031): a projeção de funcionários-por-área do estado vivo — o {@code ### Equipe} sai
 * AGRUPADO por time ({@code dominio/foco}, V15), determinístico e ZERO LLM no caminho de escrita
 * (ADR-0019). É o insumo do {@code being-ceo}: o Planejador enxerga as áreas para propor times.
 */
@SpringBootTest
@ActiveProfiles("test")
class LiveStateProjectorTest {

    @Autowired
    private LiveStateProjector projector;
    @Autowired
    private CompanyService companies;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamRepository teams;

    @Test
    void equipeSaiAgrupadaPorAreaDeterministica() {
        UUID companyId = companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
        Team produto = teams.save(new Team(UUID.randomUUID(), companyId, "produto", "checkout", null));
        Team marketing = teams.save(new Team(UUID.randomUUID(), companyId, "marketing", "aquisicao", null));

        Agent ceo = new Agent(UUID.randomUUID(), companyId, "Atlas", "ceo", null);
        agents.save(ceo);
        Agent dev = new Agent(UUID.randomUUID(), companyId, "Dev", "executor", null);
        dev.assignTeam(produto.getId());
        agents.save(dev);
        Agent arquiteta = new Agent(UUID.randomUUID(), companyId, "Lyra", "architect", null);
        arquiteta.assignTeam(produto.getId());
        agents.save(arquiteta);
        Agent social = new Agent(UUID.randomUUID(), companyId, "Nova", "executor", null);
        social.assignTeam(marketing.getId());
        agents.save(social);

        String estadoVivo = projector.project(companyId);

        // agrupado por área, na ordem do catálogo de times (dominio asc, foco asc), com o
        // fora-de-time por último — e o total segue no cabeçalho
        assertThat(estadoVivo).contains("### Equipe (4)");
        int areaMarketing = estadoVivo.indexOf("#### marketing/aquisicao (1)");
        int areaProduto = estadoVivo.indexOf("#### produto/checkout (2)");
        int semTime = estadoVivo.indexOf("#### (sem time) (1)");
        assertThat(areaMarketing).isPositive();
        assertThat(areaProduto).isGreaterThan(areaMarketing);
        assertThat(semTime).isGreaterThan(areaProduto);
        // os agentes caem na SUA área
        assertThat(estadoVivo.substring(areaProduto, semTime)).contains("Dev").contains("Lyra");
        assertThat(estadoVivo.substring(areaMarketing, areaProduto)).contains("Nova");
        assertThat(estadoVivo.substring(semTime)).contains("Atlas (ceo)");
    }
}
