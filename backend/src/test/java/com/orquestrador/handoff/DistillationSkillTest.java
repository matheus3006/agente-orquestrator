package com.orquestrador.handoff;

import com.orquestrador.skills.CompanySkillVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DistillationSkillTest {

    private final DistillationSkill distillation = new DistillationSkill();

    @Test
    void oCeoGanhaASkillDeDestilacaoComOContratoDoBilhete() {
        List<CompanySkillVersion> ceoSet = distillation.augment(List.of(), "ceo");

        assertThat(ceoSet).hasSize(1);
        CompanySkillVersion skill = ceoSet.get(0);
        assertThat(skill.getName()).isEqualTo("distillation");
        // O corpo É o contrato do bilhete (o esqueleto do /nova-sessao — D2/M4).
        assertThat(skill.getBody())
                .contains("### Onde estou")
                .contains("### Próximo passo")
                .contains("### Decisões travadas")
                .contains(".orq/handoff/");
    }

    @Test
    void osDemaisAgentesNaoRecebemADestilacao() {
        List<CompanySkillVersion> base = List.of();
        // Devolve o MESMO set (sem cópia, sem destilação) para não-CEOs.
        assertThat(distillation.augment(base, "executor")).isSameAs(base);
        assertThat(distillation.augment(base, "architect")).isSameAs(base);
    }
}
