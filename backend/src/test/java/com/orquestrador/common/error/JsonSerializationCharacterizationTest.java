package com.orquestrador.common.error;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Caracteriza os bytes do contrato HTTP antes da migração Jackson 2 → Jackson 3. */
@SpringBootTest
@ActiveProfiles("test")
class JsonSerializationCharacterizationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void apiErrorMantemNomesOrdemEAcentuacaoUtf8() throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(new ApiError("CONFLICT", "decisão já existe"));

        assertThat(json).isEqualTo(
                "{\"error\":\"CONFLICT\",\"message\":\"decisão já existe\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
