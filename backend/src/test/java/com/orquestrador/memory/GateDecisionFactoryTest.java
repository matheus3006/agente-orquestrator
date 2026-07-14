package com.orquestrador.memory;

import com.orquestrador.identity.company.CompanyService;
import com.orquestrador.memory.GateDecisionFactory.Anchor;
import com.orquestrador.memory.GateDecisionFactory.GatePassage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fábrica ÚNICA de Decisões do gate (C15 — extração do {@code recordGateDecision}): as portas
 * (approval hoje, review nesta fatia, caso B amanhã) são adapters finos sobre UM módulo profundo
 * colado no {@code DecisionLog.record}. A regra da triviabilidade (C14, "sem substância → sem
 * Decisão") mora AQUI DENTRO — nenhuma porta pode esquecê-la; a maiêutica (D10) idem.
 */
@SpringBootTest
@ActiveProfiles("test")
class GateDecisionFactoryTest {

    @Autowired
    private GateDecisionFactory factory;
    @Autowired
    private DecisionRepository decisions;
    @Autowired
    private CompanyMemoryRepository memories;
    @Autowired
    private CompanyService companies;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private GatePassage passagem(UUID companyId, String area, String racional) {
        return new GatePassage(companyId, area, DecisionOrigem.DONO_NO_GATE, "[APPROVE] alvo",
                racional, RacionalTipo.LOGICO, false, null, DecisionGrau.DECISAO, null,
                new Anchor.NaApproval(UUID.randomUUID()));
    }

    @Test
    void passagemComSubstanciaFabricaDecisaoEnderecadaEAncorada() {
        UUID companyId = novaCompany();
        UUID approvalId = UUID.randomUUID();

        Decision decisao = factory.fabricate(new GatePassage(companyId, "backlog",
                DecisionOrigem.PROPOSTA_CEO, "[APPROVE CREATE_ISSUE] Landing nova",
                "a landing atual nao converte", RacionalTipo.LOGICO, true, null,
                DecisionGrau.DECISAO, null, new Anchor.NaApproval(approvalId))).orElseThrow();

        assertThat(decisao.getSeq()).isEqualTo(1);                          // endereço nasce (C4)
        assertThat(decisao.getGrau()).isEqualTo(DecisionGrau.DECISAO);      // alcance da porta (C3)
        assertThat(decisao.getTipo()).isEqualTo(DecisionTipo.OPERACIONAL);  // derivado da área
        assertThat(decisao.getApprovalId()).isEqualTo(approvalId);
        assertThat(decisao.isRacionalArrancado()).isTrue();
        assertThat(decisions.findById(decisao.getId())).isPresent();
    }

    /** C14: sem substância → sem Decisão. A regra mora DENTRO da fábrica — porta nenhuma escapa. */
    @Test
    void passagemSemSubstanciaNaoFabricaNada() {
        UUID companyId = novaCompany();

        assertThat(factory.fabricate(passagem(companyId, "backlog", null))).isEmpty();
        assertThat(factory.fabricate(passagem(companyId, "backlog", "   "))).isEmpty();
        assertThat(decisions.findByCompanyIdOrderByDecidedAtDesc(companyId)).isEmpty();
    }

    /** D10 dentro da fábrica: INTUICAO carrega o probe maiêutico como evidência em QUALQUER porta. */
    @Test
    void intuicaoCarregaAMaieuticaComoEvidenciaEmQualquerPorta() {
        UUID companyId = novaCompany();
        UUID issueId = UUID.randomUUID();

        Decision daReview = factory.fabricate(new GatePassage(companyId, "design-visual",
                DecisionOrigem.DONO_NO_GATE, "[APPROVE REVIEW] #1 home nova",
                "me agradou o tom leve", RacionalTipo.INTUICAO, false, null,
                DecisionGrau.DECISAO, null,
                new Anchor.NaReview(UUID.randomUUID(), issueId))).orElseThrow();

        assertThat(daReview.getEvidencia()).contains("maieutico");
        assertThat(daReview.getReviewId()).isNotNull();  // a coluna morta da V18 acorda (D5)
        assertThat(daReview.getIssueId()).isEqualTo(issueId);
    }

    /**
     * F-C2 (C2/C3): a passagem de GRADUAÇÃO — {@code grau=PRINCIPIO} vem da PORTA, nunca julgado —
     * fabrica o princípio com a linhagem como evidência (C5: "graduado de #N…", documentação
     * resolvível) e o PLAYBOOK materializa na MESMA tx (a 2ª projeção, gêmea da carta).
     */
    @Test
    void passagemDeGraduacaoFabricaPrincipioEMaterializaOPlaybook() {
        UUID companyId = novaCompany();
        UUID approvalId = UUID.randomUUID();

        Decision principio = factory.fabricate(new GatePassage(companyId, "growth",
                DecisionOrigem.PROPOSTA_CEO, "Toda campanha nasce com meta de CAC explicita",
                "3 decisoes de growth repetiram o mesmo criterio", RacionalTipo.LOGICO, false, null,
                DecisionGrau.PRINCIPIO, "Graduado de #1, #2, #3 (C5)",
                new Anchor.NaApproval(approvalId))).orElseThrow();

        assertThat(principio.getGrau()).isEqualTo(DecisionGrau.PRINCIPIO);
        assertThat(principio.getEvidencia()).contains("Graduado de #1, #2, #3");
        assertThat(principio.getApprovalId()).isEqualTo(approvalId); // proveniência de graça (C6)

        String playbook = memories
                .findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.PLAYBOOK)
                .orElseThrow().getBody();
        assertThat(playbook).contains("#1").contains("Toda campanha nasce com meta de CAC explicita");
    }

    /** A passagem carrega a linhagem (D11) e direção consolida a carta na MESMA tx. */
    @Test
    void direcaoComSupersedeConsolidaACartaAutoCurada() {
        UUID companyId = novaCompany();
        Decision velha = factory.fabricate(new GatePassage(companyId, "direcao",
                DecisionOrigem.DONO_NO_GATE, "focar no dental", "dor aguda", RacionalTipo.LOGICO,
                false, null, DecisionGrau.DECISAO, null, new Anchor.NaApproval(UUID.randomUUID())))
                .orElseThrow();
        assertThat(cartaCorrente(companyId)).contains("focar no dental");

        Optional<Decision> nova = factory.fabricate(new GatePassage(companyId, "direcao",
                DecisionOrigem.DONO_NO_GATE, "pivotar pra veterinaria", "o dental saturou",
                RacionalTipo.LOGICO, false, velha.getId(), DecisionGrau.DECISAO, null,
                new Anchor.NaApproval(UUID.randomUUID())));

        assertThat(nova.orElseThrow().getSupersedesId()).isEqualTo(velha.getId());
        String carta = cartaCorrente(companyId);
        assertThat(carta).contains("pivotar pra veterinaria");
        assertThat(carta).doesNotContain("focar no dental"); // auto-cura do leitor (D11)
    }

    /**
     * C5 (a carona no commit do gate): TODA passagem re-projeta — a 3ª decisão de uma área
     * acende a nudge de candidata na carta sem nenhum refresh manual, mesmo a área não sendo
     * de direção (o detector anda de carona na fábrica, não num scheduler).
     */
    @Test
    void terceiraDecisaoDeUmaAreaAcendeNudgeDeCandidataPelaCaronaDoGate() {
        UUID companyId = novaCompany();
        factory.fabricate(new GatePassage(companyId, "direcao", DecisionOrigem.DONO_NO_GATE,
                "MVP enxuto primeiro", "runway curto", RacionalTipo.LOGICO, false, null,
                DecisionGrau.DECISAO, null, new Anchor.NaApproval(UUID.randomUUID())));
        for (int i = 1; i <= 3; i++) {
            factory.fabricate(passagem(companyId, "growth", "racional " + i));
        }

        assertThat(cartaCorrente(companyId))
                .contains("Candidata a graduação")
                .contains("growth");
    }

    private String cartaCorrente(UUID companyId) {
        return memories.findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow().getBody();
    }
}
