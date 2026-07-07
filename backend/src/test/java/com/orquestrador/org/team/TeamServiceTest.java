package com.orquestrador.org.team;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Time = entidade real (ADR-0017): domínio (macro) + foco (livre) + arquiteto designado. Cobre o
 * contrato do módulo profundo {@link TeamService}: criar SEM arquiteto (a trava é no DISPATCH, não
 * na criação — montar aos poucos) · unicidade (company, domínio, foco) · arquiteto tem que ser
 * MEMBRO do time · sair do time limpa o posto de arquiteto (invariante por construção, nunca
 * ponteiro stale) · a trava do dispatch em si (time sem arquiteto não dispara trabalho).
 */
@SpringBootTest
@ActiveProfiles("test")
class TeamServiceTest {

    @Autowired
    private TeamService service;
    @Autowired
    private TeamRepository teams;
    @Autowired
    private AgentRepository agents;

    private Agent novoAgente(UUID companyId, String nome) {
        return agents.save(new Agent(UUID.randomUUID(), companyId, nome, "executor", null));
    }

    @Test
    void criaTimeSemArquiteto_aTravaEhNoDispatchNaoNaCriacao() {
        UUID companyId = UUID.randomUUID();

        Team time = service.create(companyId, "Dev", "frontend");

        Team salvo = teams.findById(time.getId()).orElseThrow();
        assertThat(salvo.getCompanyId()).isEqualTo(companyId);
        assertThat(salvo.getDominio()).isEqualTo("Dev");
        assertThat(salvo.getFoco()).isEqualTo("frontend");
        assertThat(salvo.getArquitetoAgentId()).isNull();
    }

    @Test
    void dominioMaisFocoSaoUnicosPorCompany_mesmaDuplaEmOutraCompanyPassa() {
        UUID companyId = UUID.randomUUID();
        service.create(companyId, "Dev", "frontend");

        assertThatThrownBy(() -> service.create(companyId, "Dev", "frontend"))
                .isInstanceOf(ConflictException.class);
        // "vários times por domínio" = focos diferentes; e a mesma dupla vale em OUTRA company
        assertThatCode(() -> service.create(companyId, "Dev", "backend")).doesNotThrowAnyException();
        assertThatCode(() -> service.create(UUID.randomUUID(), "Dev", "frontend")).doesNotThrowAnyException();
    }

    @Test
    void setArquitetoExigeQueOAgenteSejaMembroDoTime() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "frontend");
        Agent deFora = novoAgente(companyId, "Nix");

        // agente da company mas FORA do time → o arquiteto é de dentro (composição do time)
        assertThatThrownBy(() -> service.setArquiteto(companyId, time.getId(), deFora.getId()))
                .isInstanceOf(ConflictException.class);

        service.assignAgent(companyId, deFora.getId(), time.getId());
        Team comArquiteto = service.setArquiteto(companyId, time.getId(), deFora.getId());
        assertThat(comArquiteto.getArquitetoAgentId()).isEqualTo(deFora.getId());
    }

    @Test
    void setArquitetoNullLimpaOPosto_eAgenteDeOutraCompanyNaoEncontrado() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "frontend");
        Agent membro = novoAgente(companyId, "Lyra");
        service.assignAgent(companyId, membro.getId(), time.getId());
        service.setArquiteto(companyId, time.getId(), membro.getId());

        Team limpo = service.setArquiteto(companyId, time.getId(), null);
        assertThat(limpo.getArquitetoAgentId()).isNull();

        Agent alheio = novoAgente(UUID.randomUUID(), "Intruso");
        assertThatThrownBy(() -> service.setArquiteto(companyId, time.getId(), alheio.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void sairDoTimeLimpaOArquitetoDoTimeAntigo_nuncaPonteiroStale() {
        UUID companyId = UUID.randomUUID();
        Team antigo = service.create(companyId, "Dev", "frontend");
        Team novo = service.create(companyId, "Dev", "backend");
        Agent arquiteto = novoAgente(companyId, "Lyra");
        service.assignAgent(companyId, arquiteto.getId(), antigo.getId());
        service.setArquiteto(companyId, antigo.getId(), arquiteto.getId());

        // mover para OUTRO time limpa o posto no time antigo (o arquiteto é sempre membro)
        service.assignAgent(companyId, arquiteto.getId(), novo.getId());
        assertThat(teams.findById(antigo.getId()).orElseThrow().getArquitetoAgentId()).isNull();
        assertThat(agents.findById(arquiteto.getId()).orElseThrow().getTeamId()).isEqualTo(novo.getId());

        // e sair para NENHUM time (null) também limpa
        service.setArquiteto(companyId, novo.getId(), arquiteto.getId());
        service.assignAgent(companyId, arquiteto.getId(), null);
        assertThat(teams.findById(novo.getId()).orElseThrow().getArquitetoAgentId()).isNull();
        assertThat(agents.findById(arquiteto.getId()).orElseThrow().getTeamId()).isNull();
    }

    @Test
    void assignAgentValidaTimeECompanyDoAgente() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "frontend");
        Agent agente = novoAgente(companyId, "Echo");

        assertThatThrownBy(() -> service.assignAgent(companyId, agente.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class); // time inexistente
        Team deOutra = service.create(UUID.randomUUID(), "Dev", "frontend");
        assertThatThrownBy(() -> service.assignAgent(companyId, agente.getId(), deOutra.getId()))
                .isInstanceOf(NotFoundException.class); // time de outra company
        assertThatThrownBy(() -> service.assignAgent(UUID.randomUUID(), agente.getId(), time.getId()))
                .isInstanceOf(NotFoundException.class); // agente fora da company do caller
    }

    @Test
    void travaDoDispatch_semTimePassa_timeComArquitetoPassa_timeSemArquiteto409() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "frontend");
        Agent solto = novoAgente(companyId, "Solto");
        Agent noTime = novoAgente(companyId, "Echo");
        service.assignAgent(companyId, noTime.getId(), time.getId());

        // agente sem time: dispatch livre (a trava é DO TIME)
        assertThatCode(() -> service.assertDispatchAllowed(agents.findById(solto.getId()).orElseThrow()))
                .doesNotThrowAnyException();
        // time sem arquiteto: trava dura (ADR-0017 — um time não dispara trabalho sem 1 arquiteto)
        assertThatThrownBy(() -> service.assertDispatchAllowed(agents.findById(noTime.getId()).orElseThrow()))
                .isInstanceOf(ConflictException.class);
        assertThat(service.dispatchBlocked(agents.findById(noTime.getId()).orElseThrow())).isTrue();

        service.setArquiteto(companyId, time.getId(), noTime.getId());
        assertThatCode(() -> service.assertDispatchAllowed(agents.findById(noTime.getId()).orElseThrow()))
                .doesNotThrowAnyException();
        assertThat(service.dispatchBlocked(agents.findById(noTime.getId()).orElseThrow())).isFalse();
    }
}
