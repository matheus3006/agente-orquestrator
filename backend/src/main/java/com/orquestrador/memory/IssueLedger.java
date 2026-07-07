package com.orquestrador.memory;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEvent;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunFinalOutput;
import com.orquestrador.execution.RunStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * O LEDGER por-Issue (ADR-0019/D3): o "report .md" de uma Issue é a SAÍDA FINAL do run concluinte
 * — READ-MODEL derivado do StepTrace ({@link RunFinalOutput}, a mesma regra do reply de turno),
 * capado e datado; nada é duplicado em storage (D1: índice-não-conteúdo; a profundidade segue no
 * StepTrace). Residual 1 do ADR-0019 fechado na recomendação: o AGENTE da Issue escreve (a saída
 * final do run DELE é o report); o CEO gradua para cima (tiers duráveis) — nunca escreve aqui.
 */
@Service
public class IssueLedger {

    private final HeartbeatRunRepository runs;
    private final HeartbeatRunEventRepository events;

    public IssueLedger(HeartbeatRunRepository runs, HeartbeatRunEventRepository events) {
        this.runs = runs;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<IssueReport> reportFor(UUID companyId, UUID issueId) {
        Optional<HeartbeatRun> concluinte = runs
                .findByIssueIdAndCompanyIdOrderByCreatedAtDesc(issueId, companyId).stream()
                .filter(run -> run.getStatus() == RunStatus.SUCCEEDED)
                .findFirst();
        if (concluinte.isEmpty()) {
            return Optional.empty();
        }
        HeartbeatRun run = concluinte.get();
        RunFinalOutput output = new RunFinalOutput();
        for (HeartbeatRunEvent event : events.findByRunIdOrderByCreatedAtAsc(run.getId())) {
            output.accept(event.getType(), event.getDetail());
        }
        return Optional.of(new IssueReport(issueId, run.getId(), run.getFinishedAt(), output.text()));
    }

    /** O report datado: a saída final do run concluinte, capada (o StepTrace é a profundidade). */
    public record IssueReport(UUID issueId, UUID runId, Instant concludedAt, String report) {
    }
}
