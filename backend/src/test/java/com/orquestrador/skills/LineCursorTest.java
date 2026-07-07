package com.orquestrador.skills;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O seam por onde os parsers de SKILL.md andam pelas linhas. O contrato importante: consumir um
 * bloco de continuacao avanca o MESMO cursor, entao o laco externo continua depois do bloco sem
 * ressincronizar indice nenhum (o antigo {@code lineIndex = blockLineIndex - 1} deixou de existir).
 */
class LineCursorTest {

    @Test
    void tomaLinhasEmOrdemPreservandoVazias() {
        LineCursor cursor = new LineCursor("a\n\nb");

        assertThat(cursor.hasLine()).isTrue();
        assertThat(cursor.take()).isEqualTo("a");
        assertThat(cursor.take()).isEmpty();
        assertThat(cursor.take()).isEqualTo("b");
        assertThat(cursor.hasLine()).isFalse();
    }

    @Test
    void textoVazioTemUmaLinhaVazia() {
        LineCursor cursor = new LineCursor("");
        assertThat(cursor.hasLine()).isTrue();
        assertThat(cursor.take()).isEmpty();
        assertThat(cursor.hasLine()).isFalse();
    }

    @Test
    void takeBlockWhileConsomeORunEParaNoTerminador() {
        LineCursor cursor = new LineCursor("chave:\n  um\n  dois\nfim");

        assertThat(cursor.take()).isEqualTo("chave:");
        List<String> block = cursor.takeBlockWhile(line -> line.startsWith("  "));

        assertThat(block).containsExactly("  um", "  dois");
        // cursor parou NO terminador, sem consumi-lo — o laco externo o pega no proximo take()
        assertThat(cursor.take()).isEqualTo("fim");
        assertThat(cursor.hasLine()).isFalse();
    }

    @Test
    void takeBlockWhileDevolveVazioQuandoNadaPertenceAoBloco() {
        LineCursor cursor = new LineCursor("chave: valor\noutra: coisa");

        assertThat(cursor.take()).isEqualTo("chave: valor");
        assertThat(cursor.takeBlockWhile(line -> line.startsWith("  "))).isEmpty();
        // nao avancou: a proxima linha continua sendo a "outra"
        assertThat(cursor.take()).isEqualTo("outra: coisa");
    }

    @Test
    void lacoExternoContinuaAposOBlocoSemRessincronizar() {
        LineCursor cursor = new LineCursor("k1:\n  v1\nk2:\n  v2");

        assertThat(cursor.take()).isEqualTo("k1:");
        assertThat(cursor.takeBlockWhile(line -> line.startsWith("  "))).containsExactly("  v1");
        // sem aritmetica de indice: a proxima linha ja e a k2
        assertThat(cursor.take()).isEqualTo("k2:");
        assertThat(cursor.takeBlockWhile(line -> line.startsWith("  "))).containsExactly("  v2");
        assertThat(cursor.hasLine()).isFalse();
    }
}
