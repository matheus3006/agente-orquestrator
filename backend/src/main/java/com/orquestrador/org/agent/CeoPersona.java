package com.orquestrador.org.agent;

import java.util.Locale;
import java.util.Optional;

/**
 * As 3 personas do CEO (ADR-0031, D1): agentes DURÁVEIS {@code role='ceo'} distintos pelo
 * discriminador {@code agent.persona} — instituições independentes e harmônicas (trias politica),
 * inspecionáveis e substituíveis uma a uma. A ordem declarada É a ordem do bastão (Q → P → E).
 *
 * <p>Vocabulário único da persona: o valor persistido ({@link #dbValue}), a role do molde-de-sistema
 * ({@link #moldeRole}, V25) e o nome semeado ({@link #nomeSemeado}) derivam todos daqui — nenhum
 * outro módulo re-declara essas strings.
 */
public enum CeoPersona {

    /** O balcão único (D2): a ÚNICA persona que fala com o dono; grelha e mantém o dossiê. */
    QUESTIONADOR,
    /** Back-office: recebe o dossiê e produz a ESTRATÉGIA de orquestração (D12). */
    PLANEJADOR,
    /** Back-office: protocola o pedido de contratação (gate T3) e opera pós-approve (A2). */
    EXECUTOR;

    /** A role dos agentes-persona ({@code role='ceo'}): o resolvedor combina role + persona. */
    public static final String CEO_ROLE = "ceo";

    /** O valor da coluna {@code agent.persona} (texto livre, V24). */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A {@code role_especifica} do molde-de-sistema desta persona (V25, catálogo global). */
    public String moldeRole() {
        return "ceo-" + dbValue();
    }

    /** O nome do agente semeado na criação da company (D9). */
    public String nomeSemeado() {
        return "CEO " + name().charAt(0) + dbValue().substring(1);
    }

    /** Parse tolerante do valor persistido/emitido em bloco; desconhecido/nulo → empty (fail-soft). */
    public static Optional<CeoPersona> fromDbValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (CeoPersona persona : values()) {
            if (persona.dbValue().equals(value.strip().toLowerCase(Locale.ROOT))) {
                return Optional.of(persona);
            }
        }
        return Optional.empty();
    }
}
