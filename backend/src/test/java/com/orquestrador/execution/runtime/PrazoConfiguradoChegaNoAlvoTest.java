package com.orquestrador.execution.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * O prazo CONFIGURADO tem de chegar ao alvo que o Spring injeta — não só ao que o teste constrói.
 *
 * <p>Este teste existe por causa de um defeito real que a prova viva pegou e a suíte inteira deixou
 * passar: com DOIS construtores e nenhum anotado, o Spring escolhe o SEM ARGUMENTO. O alvo do
 * contexto nascia com {@code PrazosDeExecucao.PADRAO} e <b>ignorava a property em silêncio</b> — run
 * com teto de 2h onde a configuração pedia segundos. Todo teste de unidade passava, porque todos
 * chamam o construtor de prazos na mão; o buraco era exatamente a fiação que nenhum deles
 * atravessa.
 *
 * <p>Por isso a asserção é COMPORTAMENTAL e não sobre o valor do bean: ler
 * {@code PrazosDeExecucao.getRun()} provaria que a property foi bindada, não que o alvo a recebeu —
 * e era justamente aí que estava o defeito.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "orquestrador.execution.timeout.run=700ms")
class PrazoConfiguradoChegaNoAlvoTest {

    @Autowired
    private LocalExecutionTarget alvoDoContexto;

    @Test
    void oAlvoInjetadoPeloSpringHonraOPrazoConfigurado(@org.junit.jupiter.api.io.TempDir Path workDir) {
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(
                workDir, List.of("sh", "-c", "sleep 3600"), Map.of(), null, null, List.of());

        // Com a fiação errada este alvo teria o teto de 2h do PADRAO e a espera abaixo estouraria.
        ExecutionTarget.Result resultado = assertTimeoutPreemptively(Duration.ofSeconds(15),
                () -> alvoDoContexto.run(spec, UUID.randomUUID(), linha -> { }));

        assertThat(resultado.error())
                .as("o alvo do contexto tem de ter usado o prazo da property, nao o default de 2h")
                .contains("PT0.7S");
    }
}
