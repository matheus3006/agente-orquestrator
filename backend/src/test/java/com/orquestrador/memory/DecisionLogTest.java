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

    /**
     * F-C1 (C4, clone da ADR-0033): toda Decisão nasce com endereço humano {@code #N} — denso
     * por company (1, 2, 3…), alocado no chokepoint único ({@code record}) sob o lock da linha
     * da company. Sem endereço estável, D11 (citar o que supersede) e o fetch JIT são impossíveis.
     */
    @Test
    void enderecoSequencialNasceDensoPorCompanyNoChokepoint() {
        UUID companyId = novaCompany();
        Decision primeira = decisionLog.record(decisao(companyId, "a", DecisionTipo.OPERACIONAL, "r1"));
        Decision segunda = decisionLog.record(decisao(companyId, "b", DecisionTipo.TECNICA, "r2"));
        Decision terceira = decisionLog.record(decisao(companyId, "c", DecisionTipo.DIRECAO, "r3"));

        assertThat(primeira.getSeq()).isEqualTo(1);
        assertThat(segunda.getSeq()).isEqualTo(2);
        assertThat(terceira.getSeq()).isEqualTo(3);

        // O #N é POR COMPANY (espaço de endereços próprio — nunca vaza entre companies).
        UUID outraCompany = novaCompany();
        assertThat(decisionLog.record(decisao(outraCompany, "a", DecisionTipo.OPERACIONAL, "r1"))
                .getSeq()).isEqualTo(1);
    }

    /** F-C1 (C3): o eixo do ALCANCE nasce em todo registro — o default do átomo é DECISAO. */
    @Test
    void grauDefaultDoAtomoEDecisao() {
        UUID companyId = novaCompany();
        Decision registrada = decisionLog.record(decisao(companyId, "a", DecisionTipo.OPERACIONAL, "r1"));
        assertThat(registrada.getGrau()).isEqualTo(DecisionGrau.DECISAO);
    }

    /** F-C1 (C4/C12): o endereço #N citado resolve pro id de máquina; #N fantasma → 409 acionável. */
    @Test
    void enderecoCitadoResolveProIdEEnderecoFantasmaE409() {
        UUID companyId = novaCompany();
        Decision alvo = decisionLog.record(decisao(companyId, "a", DecisionTipo.OPERACIONAL, "r1"));

        assertThat(decisionLog.idDoEndereco(companyId, 1)).isEqualTo(alvo.getId());
        assertThatThrownBy(() -> decisionLog.idDoEndereco(companyId, 99))
                .isInstanceOf(com.orquestrador.common.error.ConflictException.class)
                .hasMessageContaining("#99");
        // Endereço de OUTRA company não resolve aqui (espaço de endereços é company-scoped).
        UUID outraCompany = novaCompany();
        assertThatThrownBy(() -> decisionLog.idDoEndereco(outraCompany, 1))
                .isInstanceOf(com.orquestrador.common.error.ConflictException.class);
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
