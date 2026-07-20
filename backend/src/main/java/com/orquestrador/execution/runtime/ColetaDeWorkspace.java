package com.orquestrador.execution.runtime;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLog;
import com.orquestrador.activitylog.ActivityLogRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>"O workspace desta Issue já foi COLETADO pelo GC?"</b> — a régua ÚNICA das portas que agem
 * sobre o artefato de uma entrega terminada (F.12/ADR-0026 B4; ADR-0049 §Emendas). Uma régua,
 * duas cercas — o idioma da casa (cf. {@code ChainOfCommandService.pertenceASubarvore}).
 *
 * <p><b>A fonte é o evento DURÁVEL</b> ({@code WORKSPACE_COLLECTED} no {@code activity_log}), não
 * o mundo do alvo. A escolha é deliberada e é o ponto inteiro desta classe: perguntar ao alvo
 * ("o volume ainda existe?") parece mais direto e é <b>pior</b>, porque
 * {@link ExecutionTarget#listIssueWorkspaces} devolve lista VAZIA quando a listagem FALHA —
 * daemon Docker fora do ar seria indistinguível de "nada existe", e toda porta guardada por ele
 * passaria a recusar em massa durante uma queda de infraestrutura. O evento durável não tem esse
 * modo de falha: ele só existe se a remoção REALMENTE aconteceu (o
 * {@link WorkspaceReaper} audita depois do {@code REMOVED}, nunca antes).
 *
 * <p><b>Limite conhecido, escrito de propósito:</b> workspace apagado FORA do reaper (um
 * {@code docker volume rm} na mão) não produz evento e portanto não é visto por esta régua. É o
 * preço da escolha acima, e é o lado barato de errar: deixa passar uma reabertura que vai
 * encontrar workspace vazio (o estado de hoje, sem cerca nenhuma), em vez de bloquear reaberturas
 * legítimas sempre que o daemon tossir.
 */
@Component
public class ColetaDeWorkspace {

    private static final DateTimeFormatter COLETADO_EM =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final ActivityLogRepository eventos;

    public ColetaDeWorkspace(ActivityLogRepository eventos) {
        this.eventos = eventos;
    }

    /** QUANDO o GC coletou o workspace desta Issue — vazio se nunca coletou. */
    public Optional<Instant> coletadoEm(UUID companyId, UUID issueId) {
        return eventos.findFirstByCompanyIdAndEntityIdAndActionOrderByCreatedAtDesc(
                        companyId, issueId, ActivityAction.WORKSPACE_COLLECTED.name())
                .map(ActivityLog::getCreatedAt);
    }

    /** A data da coleta como o operador lê nas duas mensagens de recusa — um formato só. */
    public static String formatado(Instant quando) {
        return COLETADO_EM.format(quando) + " UTC";
    }
}
