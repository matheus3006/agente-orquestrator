package com.orquestrador.memory;

import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O átomo de memória (ADR-0019/D4) + o Log de Decisões filtrável (D5): registrar com racional
 * OBRIGATÓRIO (backstop do regime de presença, D6) e achar por data/área/tipo.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecisionLogTest {

    @Autowired
    private DecisionLog decisionLog;
    @Autowired
    private CompanyService companies;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private Decision decisao(UUID companyId, String area, DecisionTipo tipo, String racional) {
        return new Decision(UUID.randomUUID(), companyId, area, tipo, DecisionOrigem.DONO_NO_GATE,
                "approval:teste", racional, RacionalTipo.LOGICO, false);
    }

    @Test
    void registraEFiltraPorAreaTipoEData() {
        UUID companyId = novaCompany();
        decisionLog.record(decisao(companyId, "estrutura-org", DecisionTipo.DIRECAO, "MVP enxuto primeiro"));
        decisionLog.record(decisao(companyId, "backlog", DecisionTipo.OPERACIONAL, "primeiro a landing"));
        decisionLog.record(decisao(companyId, "backlog", DecisionTipo.TECNICA, "stack ja travada"));

        assertThat(decisionLog.query(companyId, null, null, null, null)).hasSize(3);
        assertThat(decisionLog.query(companyId, "backlog", null, null, null)).hasSize(2);
        assertThat(decisionLog.query(companyId, null, DecisionTipo.DIRECAO, null, null))
                .singleElement()
                .satisfies(d -> assertThat(d.getRacional()).isEqualTo("MVP enxuto primeiro"));
        assertThat(decisionLog.query(companyId, "backlog", DecisionTipo.TECNICA, null, null)).hasSize(1);

        // Janela de data: só o futuro-vazio.
        Instant depoisDeTudo = Instant.now().plusSeconds(60);
        assertThat(decisionLog.query(companyId, null, null, depoisDeTudo, null)).isEmpty();
        assertThat(decisionLog.query(companyId, null, null, null, depoisDeTudo)).hasSize(3);

        // Company-scoping: outra company não vê nada.
        assertThat(decisionLog.query(novaCompany(), null, null, null, null)).isEmpty();
    }

    @Test
    void decisaoSemRacionalERejeitada() {
        UUID companyId = novaCompany();
        assertThatThrownBy(() -> decisionLog.record(decisao(companyId, "x", DecisionTipo.OPERACIONAL, "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("racional");
    }

    @Test
    void ordenacaoDoLogEDaMaisRecenteParaTras() {
        UUID companyId = novaCompany();
        decisionLog.record(decisao(companyId, "a", DecisionTipo.OPERACIONAL, "r1"));
        decisionLog.record(decisao(companyId, "b", DecisionTipo.OPERACIONAL, "r2"));
        List<Decision> log = decisionLog.query(companyId, null, null, null, null);
        assertThat(log.get(0).getArea()).isEqualTo("b"); // mais recente primeiro (feed do board)
    }
}
