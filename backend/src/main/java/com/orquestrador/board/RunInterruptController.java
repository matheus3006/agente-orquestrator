package com.orquestrador.board;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.execution.RunInterruptService;
import com.orquestrador.execution.RunInterruptService.InterruptOutcome;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F-N4 (ADR-0040, D4): o botao "parar" do board — kill controlado (process group) + o Run vai a
 * INTERRUPTED. Board-only (mesmo espirito de {@link RunStepsController}: superficie do operador,
 * um agente autenticado nao para runs). A semantica de corrida (CAS antes do kill) vive inteira
 * em {@link RunInterruptService}; este controller so traduz o outcome em status HTTP.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/runs/{runId}")
public class RunInterruptController {

    private final RunInterruptService interruptService;
    private final CompanyAccessGuard guard;

    public RunInterruptController(RunInterruptService interruptService, CompanyAccessGuard guard) {
        this.interruptService = interruptService;
        this.guard = guard;
    }

    @PostMapping("/interrupt")
    public InterruptResponse interrupt(@PathVariable UUID companyId, @PathVariable UUID runId,
                                       @RequestBody(required = false) InterruptRequest request) {
        guard.assertBoardMember(companyId);
        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason() : "interrompido pelo operador";

        InterruptOutcome outcome = interruptService.interrupt(companyId, runId, reason);
        return switch (outcome.status()) {
            case INTERRUPTED -> new InterruptResponse(runId, "INTERRUPTED", outcome.processKilled());
            case NOT_FOUND -> throw new NotFoundException("Run nao encontrado nesta company");
            case NOT_RUNNING -> throw new ConflictException("Run nao esta em execucao");
        };
    }

    public record InterruptRequest(String reason) {
    }

    public record InterruptResponse(UUID runId, String status, boolean processKilled) {
    }
}
