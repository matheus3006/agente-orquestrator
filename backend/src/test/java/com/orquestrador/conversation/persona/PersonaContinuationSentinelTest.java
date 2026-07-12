package com.orquestrador.conversation.persona;

import com.orquestrador.org.agent.CeoPersona;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As sentinelas do fluxo de personas (ADR-0031, M6) + o retrabalho (rodada 9, auto-kickback entre
 * personas): mesmo prefixo {@code [continuação} das demais (invisível na thread, D4), e o
 * {@link PersonaContinuationSentinel#origemDoBastao} que deriva — NUNCA confia num campo do bloco —
 * quem entregou o bastão que este turno está respondendo.
 */
class PersonaContinuationSentinelTest {

    @Test
    void retrabalhoCarregaOrigemEDestinoEALacuna() {
        String mensagem = PersonaContinuationSentinel.retrabalho(CeoPersona.EXECUTOR,
                CeoPersona.PLANEJADOR, "o agente 'Dev Backend' não tem role definido");

        assertThat(mensagem).startsWith("[continuação — retrabalho")
                .contains("executor").contains("planejador")
                .contains("o agente 'Dev Backend' não tem role definido");
        assertThat(PersonaContinuationSentinel.isRetrabalho(mensagem)).isTrue();
        assertThat(PersonaContinuationSentinel.isPersonaFlow(mensagem)).isTrue();
    }

    @Test
    void origemDoBastaoDerivaQuemEntregou() {
        String bastao = PersonaContinuationSentinel.bastao(CeoPersona.PLANEJADOR, CeoPersona.EXECUTOR,
                ".orq/conversa/x/estrategia.md", "protocolar");

        assertThat(PersonaContinuationSentinel.origemDoBastao(bastao))
                .contains(CeoPersona.PLANEJADOR);
    }

    @Test
    void origemDoBastaoEVazioForaDeUmaContinuacaoDeBastao() {
        assertThat(PersonaContinuationSentinel.origemDoBastao(null)).isEmpty();
        assertThat(PersonaContinuationSentinel.origemDoBastao("mensagem qualquer do dono")).isEmpty();
        assertThat(PersonaContinuationSentinel.origemDoBastao(PersonaContinuationSentinel.prossiga("l")))
                .as("prossiga não é um bastão — sem origem para derivar").isEmpty();
        assertThat(PersonaContinuationSentinel.origemDoBastao(
                PersonaContinuationSentinel.retrabalho(CeoPersona.EXECUTOR, CeoPersona.PLANEJADOR, "l")))
                .as("retrabalho não é um bastão — sem origem para derivar").isEmpty();
    }
}
