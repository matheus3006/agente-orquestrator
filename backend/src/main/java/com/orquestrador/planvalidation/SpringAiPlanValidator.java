package com.orquestrador.planvalidation;

import com.orquestrador.org.roletemplate.RoleTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * O verificador AUXILIAR de peso menor (docs §0): confere, checagem por checagem, se o plano-PROSA
 * do arquiteto satisfaz o que o molde do papel exige. <b>Não julga qualidade</b> — só presença ou
 * ausência factual, que é o que um modelo fraco faz bem (docs §2, Inviolável §3.5).
 *
 * <p>Reusa <b>só o {@code ChatModel}</b> do starter Ollama e monta o próprio {@code ChatClient} —
 * a máquina assíncrona do inspetor de contexto ({@code SpringAiContextIntelligence}) NÃO serve de
 * template: ela é fire-and-forget sobre o pacote de contexto, e aqui o insumo é a SAÍDA do run.
 *
 * <p><b>Uma chamada por checagem</b>, de propósito: perguntar as três de uma vez devolve um bloco
 * que o modelo fraco mistura. O prompt abaixo é o que MEDIU 17/18 no harness — mexer nele sem
 * re-medir é regressão silenciosa.
 */
@Component
@ConditionalOnExpression("'${orquestrador.plan-validation.mode:off}' != 'off'")
public class SpringAiPlanValidator implements PlanValidator {

    private static final Logger log = LoggerFactory.getLogger(SpringAiPlanValidator.class);

    /** VERBATIM do harness: esta redação mediu 17/18. Alterar exige re-medir. */
    private static final String SYSTEM_PROMPT = """
            Responda apenas SIM ou NÃO, com base exclusivamente no texto fornecido.
            SIM = o texto satisfaz o que a pergunta pede. NÃO = não satisfaz.
            Depois do SIM/NÃO, cite entre aspas o trecho do texto que embasa sua resposta.
            """;

    private final ChatClient chatClient;
    private final PlanValidationProperties properties;

    public SpringAiPlanValidator(ChatModel chatModel, PlanValidationProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        // Os setters do builder do Ollama devolvem o tipo ERASED (AbstractBuilder), então encadear
        // até o build() não compila — daí a forma em statements sobre a variável tipada.
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
        builder.model(properties.getModel());
        builder.temperature(0.0);
        builder.numPredict(properties.getNumPredict());
        if (properties.isThink()) {
            builder.enableThinking();
        } else {
            builder.disableThinking();
        }
        this.chatClient = ChatClient.builder(Objects.requireNonNull(chatModel, "chatModel"))
                .defaultAdvisors(List.of())
                .defaultToolCallbacks(List.of())
                .defaultOptions(builder) // o ChatClient recebe o BUILDER (idem SpringAiContextIntelligence)
                .build();
    }

    @Override
    public List<PlanCheckVerdict> validate(String plan, List<RoleTemplateService.Check> checks) {
        String texto = capped(plan);
        List<PlanCheckVerdict> verdicts = new ArrayList<>();
        for (RoleTemplateService.Check check : checks) {
            PlanAnswerParser.Answer answer = ask(texto, check);
            verdicts.add(new PlanCheckVerdict(check.id(), answer.presence(), answer.evidence()));
        }
        return List.copyOf(verdicts);
    }

    /**
     * Uma checagem que explode (Ollama fora, modelo não-puxado, timeout) vira INDETERMINADO e as
     * outras seguem — a falha do local nunca contamina o veredito das irmãs nem o run (§3.6).
     */
    private PlanAnswerParser.Answer ask(String plan, RoleTemplateService.Check check) {
        try {
            return PlanAnswerParser.parse(chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("TEXTO:\n<<<\n" + plan + "\n>>>\n\nPERGUNTA: " + check.text())
                    .call()
                    .content());
        } catch (RuntimeException falhaDoLocal) {
            log.debug("validador local falhou na checagem {} — indeterminado, o run segue: {}",
                    check.id(), falhaDoLocal.toString());
            return new PlanAnswerParser.Answer(Presence.INDETERMINATE, "");
        }
    }

    /** O plano já chega capado em 20k pelo {@code RunFinalOutput}; este é o cinto de segurança. */
    private String capped(String plan) {
        String texto = plan == null ? "" : plan;
        int cap = properties.getMaxInputChars();
        return texto.length() <= cap ? texto : texto.substring(0, cap);
    }
}
