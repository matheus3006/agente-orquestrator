package com.orquestrador.org.agent;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cadeia de comando (ADR-0017, T.2 "ligar o reports_to"): reparent liga o líder direto; a árvore
 * proíbe SÓ ciclo (self incluso) — nunca QUEM reporta (residual 5 do fechado). O guard sobe a
 * cadeia a partir do novo líder com visited-set: dado pré-corrompido com ciclo não trava o walk.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChainOfCommandTest {

    @Autowired
    private ChainOfCommandService chain;
    @Autowired
    private AgentRepository agents;

    private Agent novoAgente(UUID companyId, String nome, UUID reportsToId) {
        return agents.save(new Agent(UUID.randomUUID(), companyId, nome, "executor", reportsToId));
    }

    @Test
    void reparentLigaOLiderDireto_eNullViraRaiz() {
        UUID companyId = UUID.randomUUID();
        Agent lider = novoAgente(companyId, "Atlas", null);
        Agent dev = novoAgente(companyId, "Nix", null);

        Agent ligado = chain.reparent(companyId, dev.getId(), lider.getId());
        assertThat(ligado.getReportsToId()).isEqualTo(lider.getId());

        Agent raiz = chain.reparent(companyId, dev.getId(), null);
        assertThat(raiz.getReportsToId()).isNull();
    }

    @Test
    void reportarASiMesmoLancaConflict() {
        UUID companyId = UUID.randomUUID();
        Agent agente = novoAgente(companyId, "Atlas", null);

        assertThatThrownBy(() -> chain.reparent(companyId, agente.getId(), agente.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cicloLancaConflict_subirDoNovoLiderPassaPeloProprioAgente() {
        UUID companyId = UUID.randomUUID();
        Agent a = novoAgente(companyId, "A", null);
        Agent b = novoAgente(companyId, "B", a.getId());
        Agent c = novoAgente(companyId, "C", b.getId());

        // A ← B ← C; pendurar A debaixo de C fecharia o ciclo A→C→B→A
        assertThatThrownBy(() -> chain.reparent(companyId, a.getId(), c.getId()))
                .isInstanceOf(ConflictException.class);
        // e o legítimo continua passando (mover C para debaixo de A direto)
        assertThatCode(() -> chain.reparent(companyId, c.getId(), a.getId())).doesNotThrowAnyException();
    }

    @Test
    void liderDeOutraCompanyOuInexistenteNaoEncontrado() {
        UUID companyId = UUID.randomUUID();
        Agent dev = novoAgente(companyId, "Nix", null);
        Agent alheio = novoAgente(UUID.randomUUID(), "Intruso", null);

        assertThatThrownBy(() -> chain.reparent(companyId, dev.getId(), alheio.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chain.reparent(companyId, dev.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chain.reparent(companyId, UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cicloPreexistenteNoDadoNaoTravaOWalk() {
        UUID companyId = UUID.randomUUID();
        // dado corrompido A↔B plantado DIRETO no repo (o reparent nunca produziria isso)
        Agent a = novoAgente(companyId, "A", null);
        Agent b = novoAgente(companyId, "B", a.getId());
        Agent recarregadoA = agents.findById(a.getId()).orElseThrow();
        recarregadoA.reassignReportsTo(b.getId());
        agents.save(recarregadoA);

        // pendurar um TERCEIRO debaixo de A: o walk A→B→A para no visited-set e o reparent liga
        Agent c = novoAgente(companyId, "C", null);
        assertThatCode(() -> chain.reparent(companyId, c.getId(), a.getId())).doesNotThrowAnyException();
        assertThat(agents.findById(c.getId()).orElseThrow().getReportsToId()).isEqualTo(a.getId());
    }
}
