package com.orquestrador.execution.runtime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * O relogio do BANCO (F.12/ADR-0026): o {@code now()} do banco como {@link Instant}. O
 * {@code WorkspaceReaper} compara a ancora durável ({@code issue.updated_at}) SEMPRE contra este
 * relogio — nunca contra o clock/memoria do processo (defesa contra clock skew entre app e banco e
 * contra reset no restart).
 *
 * <p>Modulo profundo: le {@code now()} pela MESMA coercao de tipo do Hibernate que carimba e le as
 * colunas Instant das entidades ({@code createNativeQuery(sql, Instant.class)}) — assim o relogio e
 * a ancora {@code updated_at} vivem no MESMO frame e o offset de fuso cancela na subtracao da
 * retencao. (Coagir o escalar na mao descasava do frame das entidades — o H2 do teste devolve
 * {@code now()} zone-naive; o Hibernate le a coluna em UTC.)
 */
@Component
public class DatabaseClock {

    @PersistenceContext
    private EntityManager entityManager;

    public Instant now() {
        Object value = entityManager.createNativeQuery("select now()", Instant.class).getSingleResult();
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException(
                "relogio do banco devolveu tipo inesperado: "
                        + (value == null ? "null" : value.getClass().getName()));
    }
}
