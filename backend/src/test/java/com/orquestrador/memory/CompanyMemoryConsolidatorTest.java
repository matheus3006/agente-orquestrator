package com.orquestrador.memory;

import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projeção da carta (ADR-0019, emendada pela Frente C): cada direção vigente sai ENDEREÇADA
 * pelo #N (C4 — é o que o CEO cita ao supersedar e o que o fetch JIT busca), e uma direção
 * SUPERSEDIDA (D11) sai da projeção sozinha — a carta é o vigente, o log é a história.
 */
@SpringBootTest
@ActiveProfiles("test")
class CompanyMemoryConsolidatorTest {

    @Autowired
    private CompanyMemoryConsolidator consolidator;
    @Autowired
    private DecisionLog decisionLog;
    @Autowired
    private CompanyMemoryRepository memories;
    @Autowired
    private CompanyService companies;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private Decision direcao(UUID companyId, String oQueMudou, String racional) {
        return new Decision(UUID.randomUUID(), companyId, "direcao", DecisionTipo.DIRECAO,
                DecisionOrigem.DONO_NO_GATE, oQueMudou, racional, RacionalTipo.LOGICO, false);
    }

    private String cartaCorrente(UUID companyId) {
        return memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow().getBody();
    }

    /** C4: a linha da carta carrega o endereço — "#N (área) o_que_mudou — racional: …". */
    @Test
    void cartaEnderecaCadaDirecaoPeloSeuNumero() {
        UUID companyId = novaCompany();
        decisionLog.record(direcao(companyId, "focar no nicho dental", "dor aguda, ticket alto"));
        decisionLog.record(direcao(companyId, "MVP enxuto primeiro", "runway curto"));

        consolidator.refresh(companyId);

        String carta = cartaCorrente(companyId);
        assertThat(carta).contains("#1").contains("focar no nicho dental");
        assertThat(carta).contains("#2").contains("MVP enxuto primeiro");
    }

    /** D11 (auto-cura): a nova direção aponta a velha em supersedes → a velha SAI da carta sozinha. */
    @Test
    void direcaoSupersedidaSaiDaCartaSozinha() {
        UUID companyId = novaCompany();
        Decision velha = decisionLog.record(direcao(companyId, "focar no nicho dental", "dor aguda"));
        consolidator.refresh(companyId);
        assertThat(cartaCorrente(companyId)).contains("focar no nicho dental");

        decisionLog.record(direcao(companyId, "pivotar para clinicas veterinarias",
                "o dental saturou; a premissa caiu").superseding(velha.getId()));
        consolidator.refresh(companyId);

        String carta = cartaCorrente(companyId);
        assertThat(carta).contains("pivotar para clinicas veterinarias");
        assertThat(carta).doesNotContain("focar no nicho dental"); // a carta é o vigente
    }
}
