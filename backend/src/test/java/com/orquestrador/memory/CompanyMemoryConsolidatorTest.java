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

    private Decision decisao(UUID companyId, String area, String oQueMudou) {
        return new Decision(UUID.randomUUID(), companyId, area, DecisionLog.tipoFor(area),
                DecisionOrigem.DONO_NO_GATE, oQueMudou, "racional de teste", RacionalTipo.LOGICO, false);
    }

    private String cartaCorrente(UUID companyId) {
        return memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow().getBody();
    }

    private String playbookCorrente(UUID companyId) {
        return memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK)
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

    /**
     * C7/C8 (o cap, a assinatura): a carta renderiza NO MÁXIMO as 12 direções mais recentes —
     * hard por construção. O excedente NUNCA é descartado nem comprimido: segue no log e vira
     * nudge de estouro citando os {@code #N} (o "cair-pra-JIT" concreto: o endereço fica).
     */
    @Test
    void cartaCapaEm12MaisRecentesEEstouroViraNudgeComOsEnderecos() {
        UUID companyId = novaCompany();
        for (int i = 1; i <= 13; i++) {
            decisionLog.record(direcao(companyId, "rumo n" + String.format("%02d", i), "racional " + i));
        }

        consolidator.refresh(companyId);

        String carta = cartaCorrente(companyId);
        assertThat(carta).contains("rumo n02").contains("rumo n13"); // as 12 mais recentes
        assertThat(carta).as("a 13ª mais antiga fica fora da janela").doesNotContain("rumo n01");
        assertThat(carta).as("o estouro vira nudge com o endereço do excedente")
                .contains("Estouro do cap").contains("(#1)");
    }

    /**
     * C5 (o detector determinístico): N=3 decisões numa área DESDE o último princípio daquela
     * área viram nudge de CANDIDATA na carta — com os {@code #N}, que são a linhagem que o CEO
     * cita ao propor. Graduar ZERA o contador da área (o "desde" recomeça no princípio selado).
     */
    @Test
    void tresDecisoesNumaAreaViramNudgeDeCandidataEGraduarZeraOContador() {
        UUID companyId = novaCompany();
        decisionLog.record(direcao(companyId, "MVP enxuto primeiro", "runway curto"));  // #1
        decisionLog.record(decisao(companyId, "growth", "teste de canal pago"));        // #2
        decisionLog.record(decisao(companyId, "growth", "campanha com CAC alvo"));      // #3
        consolidator.refresh(companyId);
        assertThat(cartaCorrente(companyId)).as("2 é coincidência, ainda não tendência")
                .doesNotContain("Candidata");

        decisionLog.record(decisao(companyId, "growth", "corte de canal sem CAC"));     // #4
        consolidator.refresh(companyId);
        assertThat(cartaCorrente(companyId))
                .contains("Candidata a graduação")
                .contains("growth")
                .contains("#2, #3, #4");

        // o dono gradua growth → o contador da área zera na hora; a nudge apaga sozinha
        decisionLog.record(decisao(companyId, "growth", "Toda acao de growth nasce com CAC alvo")
                .comGrau(DecisionGrau.PRINCIPIO));                                      // #5
        consolidator.refresh(companyId);
        assertThat(cartaCorrente(companyId)).doesNotContain("Candidata a graduação");
    }

    /**
     * C7 (cap gêmeo, 12/12): o playbook renderiza no máximo os 12 princípios mais recentes;
     * o estouro vira nudge citando os {@code #N} — supersede/destile, nunca descarte.
     */
    @Test
    void playbookCapaEm12ENudgeDeEstouroCitaOExcedente() {
        UUID companyId = novaCompany();
        for (int i = 1; i <= 13; i++) {
            decisionLog.record(decisao(companyId, "growth", "principio n" + String.format("%02d", i))
                    .comGrau(DecisionGrau.PRINCIPIO));
        }

        consolidator.refresh(companyId);

        String playbook = playbookCorrente(companyId);
        assertThat(playbook).contains("principio n02").contains("principio n13");
        assertThat(playbook).as("o 13º mais antigo fica fora da janela").doesNotContain("principio n01");
        assertThat(playbook).contains("Estouro do cap").contains("(#1)");
    }

    /** D11 gêmea: um princípio supersedido por um novo SAI do playbook sozinho (auto-cura). */
    @Test
    void principioSupersedidoSaiDoPlaybookSozinho() {
        UUID companyId = novaCompany();
        Decision velho = decisionLog.record(decisao(companyId, "growth", "CAC alvo em toda campanha")
                .comGrau(DecisionGrau.PRINCIPIO));
        consolidator.refresh(companyId);
        assertThat(playbookCorrente(companyId)).contains("CAC alvo em toda campanha");

        decisionLog.record(decisao(companyId, "growth", "CAC alvo e payback menor que 3 meses")
                .comGrau(DecisionGrau.PRINCIPIO).superseding(velho.getId()));
        consolidator.refresh(companyId);

        String playbook = playbookCorrente(companyId);
        assertThat(playbook).contains("payback");
        assertThat(playbook).doesNotContain("CAC alvo em toda campanha");
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
