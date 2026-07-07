package com.orquestrador.board;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * F.0: o transcript INCREMENTAL de um run — heartbeat_run_event é append-only, então o offset
 * por contagem é estável: o poll de 2s repete o nextOffset anterior e recebe só o delta.
 * O runStatus vai junto para o front encerrar o poll sem consultar outro endpoint.
 * Board-only (StepTrace é stdout cru do operador); run company-led → fora da company é 404.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/runs/{runId}/steps")
public class RunStepsController {

    private static final int DEFAULT_PAGE = 200;
    private static final int MAX_PAGE = 500;

    private final HeartbeatRunRepository runs;
    private final HeartbeatRunEventRepository events;
    private final CompanyAccessGuard guard;

    public RunStepsController(HeartbeatRunRepository runs, HeartbeatRunEventRepository events,
                              CompanyAccessGuard guard) {
        this.runs = runs;
        this.events = events;
        this.guard = guard;
    }

    @GetMapping
    public StepsPage steps(@PathVariable UUID companyId, @PathVariable UUID runId,
                           @RequestParam(defaultValue = "0") int offset,
                           @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit) {
        guard.assertBoardMember(companyId);
        HeartbeatRun run = runs.findByIdAndCompanyId(runId, companyId)
                .orElseThrow(() -> new NotFoundException("Run nao encontrado nesta company"));
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.clamp(limit, 1, MAX_PAGE);
        List<Step> steps = events.findStepsPage(runId, safeOffset, safeLimit).stream()
                .map(event -> new Step(event.getType(), event.getDetail(), event.getCreatedAt()))
                .toList();
        return new StepsPage(runId, run.getStatus().name(), steps, safeOffset + steps.size());
    }

    public record StepsPage(UUID runId, String runStatus, List<Step> steps, int nextOffset) {
    }

    public record Step(String type, String detail, Instant createdAt) {
    }
}
