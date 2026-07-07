package com.orquestrador.conversation;

/**
 * O ciclo de um turno de conversa: nasce {@code PENDING} (mensagem do dono aguardando o Run do
 * CEO), e o settle do Run o liquida em {@code ANSWERED} (reply capturado) ou {@code FAILED}.
 * O "em execução" não é estado do turno: é o {@code run_id} preenchido no claim + o status do
 * próprio Run (fonte única; o turno não espelha máquina de estados alheia).
 */
public enum TurnStatus {
    PENDING,
    ANSWERED,
    FAILED
}
