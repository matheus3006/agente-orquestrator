package com.orquestrador.memory;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Leitura board-only da CARTA corrente (ADR-0018/0019): o que o CEO carrega como
 * {@code .claude/CLAUDE.md} a cada Run — o board inspeciona a mesma projeção (transparência da
 * memória derivada; a escrita continua exclusiva do consolidador).
 */
@RestController
@RequestMapping("/api/companies/{companyId}/memory")
public class CompanyMemoryController {

    private final CompanyMemoryRepository memories;
    private final CompanyAccessGuard guard;

    public CompanyMemoryController(CompanyMemoryRepository memories, CompanyAccessGuard guard) {
        this.memories = memories;
        this.guard = guard;
    }

    @GetMapping("/carta")
    public MemoryResponse carta(@PathVariable UUID companyId) {
        guard.assertBoardMember(companyId);
        CompanyMemory carta = memories
                .findFirstByCompanyIdAndKindOrderByVersionDesc(companyId, MemoryKind.CARTA)
                .orElseThrow(() -> new NotFoundException(
                        "A company ainda nao tem carta consolidada (nenhuma Decisao de direcao)"));
        return new MemoryResponse(carta.getId(), carta.getVersion(), carta.getBody(), carta.getCreatedAt());
    }

    public record MemoryResponse(UUID id, int version, String body, Instant createdAt) {
    }
}
