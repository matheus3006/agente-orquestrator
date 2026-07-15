package com.orquestrador.governance.approval;

import com.orquestrador.memory.RacionalTipo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O regime lógico|intuição do ADR-0020 (D6–D10) como tabela-verdade executável. */
class RacionalPolicyTest {

    private final RacionalPolicy policy = new RacionalPolicy();

    @Test
    void areaEDerivadaDaAcaoQuandoOCeoNaoDeclara() {
        assertThat(policy.areaFor(ApprovalActionType.HIRE_AGENT, null)).isEqualTo("estrutura-org");
        assertThat(policy.areaFor(ApprovalActionType.CREATE_ISSUE, null)).isEqualTo("backlog");
        assertThat(policy.areaFor(ApprovalActionType.WAKEUP_AGENT, null)).isEqualTo("operacao");
        assertThat(policy.areaFor(ApprovalActionType.CREATE_ISSUE, "roadmap")).isEqualTo("roadmap");
    }

    @Test
    void tipoEDerivadoDaAreaNaoDeJulgamento() {
        // D9: área criativa/visual → intuição; estrutura/custo/prazo → lógico.
        assertThat(policy.deriveTipo("estrutura-org")).isEqualTo(RacionalTipo.LOGICO);
        assertThat(policy.deriveTipo("backlog")).isEqualTo(RacionalTipo.LOGICO);
        assertThat(policy.deriveTipo("design-visual")).isEqualTo(RacionalTipo.INTUICAO);
        assertThat(policy.deriveTipo("marketing-criativo")).isEqualTo(RacionalTipo.INTUICAO);
        assertThat(policy.deriveTipo("video de lancamento")).isEqualTo(RacionalTipo.INTUICAO);
        // Cinto da carona (item 2 lista H): as CREATIVE_AREA_MARKS têm de ficar SEM acento — o
        // matcher compara contra texto normalizado; acentuar "estetica" cegaria esta detecção.
        assertThat(policy.deriveTipo("estetica pura")).isEqualTo(RacionalTipo.INTUICAO);
    }

    @Test
    void vazioEGrelhadoNosDoisTipos() {
        assertThat(policy.grillProbe(RacionalTipo.LOGICO, "  ")).isPresent();
        assertThat(policy.grillProbe(RacionalTipo.INTUICAO, null)).isPresent();
    }

    @Test
    void porqueQuisERejeitadoNosDoisTipos() {
        // D7: "porque quis" não é razão nem intuição — é recusa de dizer.
        assertThat(policy.grillProbe(RacionalTipo.LOGICO, "porque quis")).isPresent();
        assertThat(policy.grillProbe(RacionalTipo.LOGICO, "Porque sim.")).isPresent();
        assertThat(policy.grillProbe(RacionalTipo.INTUICAO, "porque eu quis")).isPresent();
        // Cinto da carona: as CONFAB_FORMS também são comparadas sem acento — acentuar "sem razao"
        // ou "so porque sim" na constante cegaria o grill a essas formas de não-razão (D7).
        assertThat(policy.grillProbe(RacionalTipo.LOGICO, "sem razao")).isPresent();
        assertThat(policy.grillProbe(RacionalTipo.INTUICAO, "so porque sim")).isPresent();
    }

    /**
     * F-C1 (C14): a pergunta não-bloqueante da review nas palavras da POLÍTICA — criativo é a
     * própria maiêutica (D10); não-criativo pesca o "risco aceito-sem-mudar" (D5).
     */
    @Test
    void reviewProbeEDaPoliticaEDiferePorTipo() {
        assertThat(policy.reviewProbe(RacionalTipo.INTUICAO)).isEqualTo(policy.maieuticProbe());
        assertThat(policy.reviewProbe(RacionalTipo.LOGICO)).contains("ressalva").contains("risco");
    }

    @Test
    void logicoExigeSubstanciaIntuicaoCommitaComPreferencia() {
        assertThat(policy.grillProbe(RacionalTipo.LOGICO,
                "runway de 6 meses exige MVP antes de contratar mais")).isEmpty();
        // D8: preferência legítima commita NA HORA na intuição.
        assertThat(policy.grillProbe(RacionalTipo.INTUICAO, "me agradou o tom da campanha")).isEmpty();
        // O probe maiêutico existe para acompanhar a intuição (D10), nunca para bloquear.
        assertThat(policy.maieuticProbe()).isNotBlank();
    }
}
