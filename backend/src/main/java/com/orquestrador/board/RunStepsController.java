package com.orquestrador.board;

import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.common.security.CompanyAccessGuard;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunEventRepository;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.RunStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F.0: o transcript INCREMENTAL de um run — heartbeat_run_event é append-only, então o offset
 * por contagem é estável: o poll de 2s repete o nextOffset anterior e recebe só o delta.
 * O runStatus vai junto para o front encerrar o poll sem consultar outro endpoint.
 * Board-only (StepTrace é stdout cru do operador); run company-led → fora da company é 404.
 *
 * <p>F-N4 (ADR-0040, D4): {@link #stream} é o MESMO transcript por SSE — um push por conexão em
 * vez do cliente repetir o offset a cada 2s. O guard roda UMA vez, na inscrição (o poll de fundo
 * não tem requisição HTTP para reautenticar); a paginação é a MESMA de {@link #steps}.
 */
@RestController
@RequestMapping("/api/companies/{companyId}/runs/{runId}/steps")
public class RunStepsController {

    private static final int DEFAULT_PAGE = 200;
    private static final int MAX_PAGE = 500;
    private static final long STREAM_POLL_MS = 500;

    private final HeartbeatRunRepository runs;
    private final HeartbeatRunEventRepository events;
    private final CompanyAccessGuard guard;
    private final ScheduledExecutorService streamPolls = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "run-steps-stream");
        thread.setDaemon(true);
        return thread;
    });

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

    /**
     * NOTA (achado desta fatia): {@code CompanyScopeInterceptor} loga um falso-positivo
     * "DENY-BY-DEFAULT VIOLADO" para ESTE endpoint — o tripwire confere {@code ActorContext} (um
     * ThreadLocal) no {@code afterCompletion} do dispatch async de CONCLUSÃO, que o servlet
     * container pode rodar num worker thread DIFERENTE do que executou o {@code assertBoardMember}
     * abaixo. A proteção real independe disso: o guard roda SÍNCRONO, na mesma thread da
     * requisição, ANTES do {@link SseEmitter} sequer nascer — um ator sem acesso nunca chega a
     * iniciar o processamento async (prova: {@code streamForaDaCompanyOuInexistenteDa404EBoardOnly403}).
     * Limitação estrutural do tripwire para qualquer endpoint async/SSE — não uma falha DESTE
     * endpoint; consertar o tripwire em si (trocar o ThreadLocal por algo que atravesse o
     * re-dispatch) é fora do escopo desta fatia (infra transversal, não o feature).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID companyId, @PathVariable UUID runId,
                             @RequestParam(defaultValue = "0") int offset) {
        guard.assertBoardMember(companyId);
        runs.findByIdAndCompanyId(runId, companyId)
                .orElseThrow(() -> new NotFoundException("Run nao encontrado nesta company"));

        SseEmitter emitter = new SseEmitter(0L); // sem timeout: fecha por status terminal ou erro
        AtomicInteger cursor = new AtomicInteger(Math.max(0, offset));
        AtomicBoolean done = new AtomicBoolean(false);
        ScheduledFuture<?> poll = streamPolls.scheduleWithFixedDelay(
                () -> pushOnce(emitter, companyId, runId, cursor, done),
                0, STREAM_POLL_MS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> poll.cancel(false));
        emitter.onTimeout(() -> {
            poll.cancel(false);
            emitter.complete();
        });
        emitter.onError(exception -> poll.cancel(false));
        return emitter;
    }

    /** Uma passada do poll de fundo: manda o delta e fecha quando o status vira terminal. */
    private void pushOnce(SseEmitter emitter, UUID companyId, UUID runId, AtomicInteger cursor, AtomicBoolean done) {
        if (done.get()) {
            return;
        }
        try {
            HeartbeatRun run = runs.findByIdAndCompanyId(runId, companyId).orElse(null);
            if (run == null) {
                done.set(true);
                emitter.complete();
                return;
            }
            List<Step> novos = events.findStepsPage(runId, cursor.get(), DEFAULT_PAGE).stream()
                    .map(event -> new Step(event.getType(), event.getDetail(), event.getCreatedAt()))
                    .toList();
            for (Step step : novos) {
                emitter.send(SseEmitter.event().name("step").data(step, MediaType.APPLICATION_JSON));
            }
            cursor.addAndGet(novos.size());
            if (isTerminal(run.getStatus())) {
                done.set(true);
                emitter.send(SseEmitter.event().name("status").data(run.getStatus().name(), MediaType.TEXT_PLAIN));
                emitter.complete();
                return;
            }
            if (novos.isEmpty()) {
                // Keep-alive (achado do VENDO): uma run sem STDOUT novo por um tempo fica com a
                // conexao ZERO BYTES por ciclo — proxies/LBs com timeout de socket ocioso (o
                // rewrite dev do Next.js incluso, ECONNRESET em ~5s) derrubam isso como conexao
                // morta. Comentario SSE (":"): o cliente ignora o conteudo, so mantem o socket vivo.
                emitter.send(SseEmitter.event().comment("keep-alive"));
            }
        } catch (Exception exception) {
            done.set(true);
            emitter.completeWithError(exception);
        }
    }

    /** RUNNING/QUEUED/RECOVERING seguem em voo; os 4 terminais fecham o stream (INTERRUPTED incluso). */
    static boolean isTerminal(RunStatus status) {
        return switch (status) {
            case SUCCEEDED, FAILED, CANCELLED, INTERRUPTED -> true;
            case QUEUED, RUNNING, RECOVERING -> false;
        };
    }

    @PreDestroy
    void shutdown() {
        streamPolls.shutdownNow();
    }

    public record StepsPage(UUID runId, String runStatus, List<Step> steps, int nextOffset) {
    }

    public record Step(String type, String detail, Instant createdAt) {
    }
}
