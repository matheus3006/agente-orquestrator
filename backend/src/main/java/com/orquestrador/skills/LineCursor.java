package com.orquestrador.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Cursor de avanço-único sobre as linhas de um texto (split por {@code '\n'}, preservando as vazias).
 * Esconde toda a aritmética de índice/limite que os parsers de SKILL.md faziam à mão: em vez de dois
 * índices manualmente ressincronizados (o antigo {@code lineIndex = blockLineIndex - 1}), há UMA única
 * posição interna. Tomar a linha corrente E consumir o bloco de continuação abaixo dela avançam o
 * MESMO cursor — então o laço externo simplesmente continua depois do bloco, sem ressincronizar nada.
 *
 * <p>Somente-leitura: não reescreve o texto. Quem transforma (ex.: {@link SecretRedactor}) monta a
 * própria saída a partir das linhas que o cursor devolve.
 */
final class LineCursor {

    private final String[] lines;
    private int position;

    LineCursor(String text) {
        this.lines = text.split("\n", -1);
    }

    /** Ainda há uma linha corrente para {@link #take()}? */
    boolean hasLine() {
        return position < lines.length;
    }

    /** Devolve a linha corrente e avança o cursor. Requer {@link #hasLine()}. */
    String take() {
        return lines[position++];
    }

    /**
     * Consome e devolve, em ordem, a sequência de linhas SEGUINTES enquanto {@code belongsToBlock}
     * for verdadeiro — o bloco de continuação da linha recém-tomada. Ao retornar, o cursor para na
     * primeira linha que não pertence ao bloco (ou no fim), pronta para o próximo {@link #take()}.
     * Devolve lista vazia se nenhuma linha seguinte pertence ao bloco.
     */
    List<String> takeBlockWhile(Predicate<String> belongsToBlock) {
        List<String> block = new ArrayList<>();
        while (position < lines.length && belongsToBlock.test(lines[position])) {
            block.add(lines[position++]);
        }
        return block;
    }
}
