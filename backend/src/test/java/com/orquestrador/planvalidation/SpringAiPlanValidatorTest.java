package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O validador local (Uso B / B-ii): UMA chamada por checagem, veredito carimbado com o
 * {@code checkId} ESTÁVEL que a B-i persistiu no molde. O modelo só responde presença/ausência
 * factual — julgamento de qualidade é da IA forte (Inviolável §3.5).
 */
class SpringAiPlanValidatorTest {

    private static final List<RoleTemplateService.Check> CHECKS = List.of(
            new RoleTemplateService.Check("seam", "O plano aponta o seam/encaixe que vai tocar?"),
            new RoleTemplateService.Check("test", "O plano tem seção ou menção explícita de teste?"));

    @Test
    void devolveUmVereditoPorCheckCarimbadoComOCheckId() {
        PlanValidator validator = validadorQueResponde(
                "SIM  \n\"`ReportService.java:142` — o método somarSaldo.\"",
                "NÃO  \nO texto não menciona teste nenhum.");

        List<PlanCheckVerdict> verdicts = validator.validate("um plano qualquer", CHECKS);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).checkId()).isEqualTo("seam");
        assertThat(verdicts.get(0).presence()).isEqualTo(Presence.PRESENT);
        assertThat(verdicts.get(0).evidence()).contains("ReportService.java:142");
        assertThat(verdicts.get(1).checkId()).isEqualTo("test");
        assertThat(verdicts.get(1).presence()).isEqualTo(Presence.ABSENT);
    }

    /**
     * Inviolável §3.6: falha do local NUNCA bloqueia. Ollama fora do ar / modelo não-puxado explode
     * na 1ª checagem — ela vira INDETERMINADO (nunca "ausente", que inventaria um buraco) e a
     * IRMÃ segue sendo conferida normalmente.
     */
    @Test
    void falhaDoLocalViraIndeterminadoSemContaminarAsOutrasChecagens() {
        AtomicInteger chamadas = new AtomicInteger();
        PlanValidator validator = new SpringAiPlanValidator(new FakeChatModel(prompt -> {
            if (chamadas.getAndIncrement() == 0) {
                throw new IllegalStateException("Connection refused: localhost:11434");
            }
            return resposta("SIM  \n\"tem teste sim.\"");
        }), new PlanValidationProperties());

        List<PlanCheckVerdict> verdicts = validator.validate("um plano qualquer", CHECKS);

        assertThat(verdicts).hasSize(2);
        assertThat(verdicts.get(0).presence()).isEqualTo(Presence.INDETERMINATE);
        assertThat(verdicts.get(0).isHole()).isFalse();
        assertThat(verdicts.get(1).presence()).isEqualTo(Presence.PRESENT);
    }

    private static PlanValidator validadorQueResponde(String... respostas) {
        Deque<String> fila = new ArrayDeque<>(List.of(respostas));
        return new SpringAiPlanValidator(
                new FakeChatModel(prompt -> resposta(fila.poll())), new PlanValidationProperties());
    }

    private static ChatResponse resposta(String texto) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(texto))));
    }

    private static final class FakeChatModel implements ChatModel {
        private final Function<Prompt, ChatResponse> behavior;

        private FakeChatModel(Function<Prompt, ChatResponse> behavior) {
            this.behavior = behavior;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return behavior.apply(prompt);
        }
    }
}
