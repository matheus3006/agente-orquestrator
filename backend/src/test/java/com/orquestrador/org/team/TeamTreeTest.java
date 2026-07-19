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
 * A ÁRVORE DE TIMES = O ORGANOGRAMA (ADR-0045, D-ORG-9). A empresa é a RAIZ e não é um time:
 * {@code parent_team_id} nulo = pendurado direto na empresa (o "departamento"). Cobre os
 * invariantes estruturais: raiz permitida · pai validado · SEM CICLO (auto-pai e ancestral) ·
 * gestor é MEMBRO do time (espelha o arquiteto) · sair do time limpa o posto de gestor ·
 * e a fronteira que mais importa: <b>o gestor NÃO mexe na trava do dispatch</b>, que segue sendo
 * do ARQUITETO (ADR-0017) — são dois postos com dois propósitos.
 */
@SpringBootTest
@ActiveProfiles("test")
class TeamTreeTest {

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
    void timeNasceNaRaizComParentNulo_ouPenduradoNoPai() {
        UUID companyId = UUID.randomUUID();

        Team departamento = service.create(companyId, "Marketing", "growth", null);
        assertThat(departamento.getParentTeamId()).isNull(); // raiz = a empresa

        Team subTime = service.create(companyId, "Marketing", "conteudo", departamento.getId());
        assertThat(teams.findById(subTime.getId()).orElseThrow().getParentTeamId())
                .isEqualTo(departamento.getId());
    }

    @Test
    void paiInexistenteOuDeOutraCompany_naoEncontrado() {
        UUID companyId = UUID.randomUUID();
        Team deOutra = service.create(UUID.randomUUID(), "Dev", "backend", null);

        assertThatThrownBy(() -> service.create(companyId, "Dev", "frontend", UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(companyId, "Dev", "frontend", deOutra.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void arvoreSemCiclo_autoPaiEAncestralRecusados() {
        UUID companyId = UUID.randomUUID();
        Team avo = service.create(companyId, "Dev", "avo", null);
        Team pai = service.create(companyId, "Dev", "pai", avo.getId());
        Team filho = service.create(companyId, "Dev", "filho", pai.getId());

        // ser pai de si mesmo
        assertThatThrownBy(() -> service.reparentTeam(companyId, pai.getId(), pai.getId()))
                .isInstanceOf(ConflictException.class);
        // pendurar o AVÔ debaixo do NETO fecharia o ciclo avo→filho→pai→avo
        assertThatThrownBy(() -> service.reparentTeam(companyId, avo.getId(), filho.getId()))
                .isInstanceOf(ConflictException.class);
        // o legítimo passa: subir o filho um degrau (vira irmão do pai)
        assertThatCode(() -> service.reparentTeam(companyId, filho.getId(), avo.getId()))
                .doesNotThrowAnyException();
        // e soltar na raiz também
        assertThatCode(() -> service.reparentTeam(companyId, filho.getId(), null))
                .doesNotThrowAnyException();
        assertThat(teams.findById(filho.getId()).orElseThrow().getParentTeamId()).isNull();
    }

    @Test
    void gestorTemQueSerMembroDoTime_espelhaOArquiteto() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "frontend", null);
        Agent deFora = novoAgente(companyId, "Nix");

        assertThatThrownBy(() -> service.setGestor(companyId, time.getId(), deFora.getId()))
                .isInstanceOf(ConflictException.class);

        service.assignAgent(companyId, deFora.getId(), time.getId());
        Team comGestor = service.setGestor(companyId, time.getId(), deFora.getId());
        assertThat(comGestor.getGestorAgentId()).isEqualTo(deFora.getId());
    }

    @Test
    void sairDoTimeLimpaOPostoDeGestor_nuncaPonteiroStale() {
        UUID companyId = UUID.randomUUID();
        Team antigo = service.create(companyId, "Dev", "frontend", null);
        Team novo = service.create(companyId, "Dev", "backend", null);
        Agent gestor = novoAgente(companyId, "Lyra");
        service.assignAgent(companyId, gestor.getId(), antigo.getId());
        service.setGestor(companyId, antigo.getId(), gestor.getId());

        service.assignAgent(companyId, gestor.getId(), novo.getId());
        assertThat(teams.findById(antigo.getId()).orElseThrow().getGestorAgentId()).isNull();

        // e sair para NENHUM time também limpa
        service.setGestor(companyId, novo.getId(), gestor.getId());
        service.assignAgent(companyId, gestor.getId(), null);
        assertThat(teams.findById(novo.getId()).orElseThrow().getGestorAgentId()).isNull();
    }

    /**
     * A FRONTEIRA da D-ORG-A.1: gestão e arquitetura são postos SEPARADOS. Designar gestor não
     * destrava o dispatch (só o arquiteto destrava), e um time com arquiteto e SEM gestor dispara
     * normalmente — senão a árvore nova pararia toda company existente no dia da migração.
     */
    @Test
    void gestorNaoMexeNaTravaDoDispatch_quemDestravaEhOArquiteto() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "frontend", null);
        Agent membro = novoAgente(companyId, "Echo");
        service.assignAgent(companyId, membro.getId(), time.getId());

        // só com GESTOR o dispatch continua travado (gestor não é arquiteto)
        service.setGestor(companyId, time.getId(), membro.getId());
        assertThat(service.dispatchBlocked(agents.findById(membro.getId()).orElseThrow())).isTrue();

        // com ARQUITETO destrava — mesmo sem gestor nenhum
        service.setGestor(companyId, time.getId(), null);
        service.setArquiteto(companyId, time.getId(), membro.getId());
        assertThat(service.dispatchBlocked(agents.findById(membro.getId()).orElseThrow())).isFalse();
    }

    /** O mesmo agente pode acumular os dois postos (time pequeno) — nada no desenho proíbe. */
    @Test
    void omesmoAgentePodeSerGestorEArquiteto() {
        UUID companyId = UUID.randomUUID();
        Team time = service.create(companyId, "Dev", "fullstack", null);
        Agent faztudo = novoAgente(companyId, "Faz-tudo");
        service.assignAgent(companyId, faztudo.getId(), time.getId());

        service.setGestor(companyId, time.getId(), faztudo.getId());
        service.setArquiteto(companyId, time.getId(), faztudo.getId());

        Team salvo = teams.findById(time.getId()).orElseThrow();
        assertThat(salvo.getGestorAgentId()).isEqualTo(faztudo.getId());
        assertThat(salvo.getArquitetoAgentId()).isEqualTo(faztudo.getId());
    }
}
