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
 * <p><b>Limites conhecidos — são DOIS, não um.</b> Nos dois o efeito é o mesmo: workspace
 * REMOVIDO e evento inexistente ⇒ esta régua devolve vazio ⇒ a cerca deixa passar uma reabertura
 * que vai encontrar workspace vazio, que é a perda silenciosa que a D-FP-1 veio matar.
 *
 * <ol>
 *   <li><b>Remoção FORA do reaper</b> (um {@code docker volume rm} na mão) não produz evento. É o
 *       preço da escolha acima e é o lado barato de errar: deixa passar uma reabertura contra
 *       workspace vazio (o estado de hoje, sem cerca nenhuma), em vez de bloquear reaberturas
 *       legítimas sempre que o daemon tossir.</li>
 *   <li><b>Falha de auditoria DENTRO de casa</b> (achado da conferência do PR #53, por dois
 *       verificadores independentes; não estava listado aqui). O
 *       {@code WorkspaceReaper.recordCollected} envolve o {@code activityLog.record} num
 *       {@code try/catch} que ENGOLE a exceção DE PROPÓSITO — *"falha aqui não desfaz a remoção"* —
 *       e segue, registrando o problema em {@code log.error}. A escolha é defensável para o GC
 *       (a remoção já aconteceu; derrubar o sweep dos demais seria pior), mas ela nasceu quando o
 *       evento era só rastro. <b>Desde a D-FP-1 o evento é LOAD-BEARING</b>: é a fonte desta régua,
 *       e portanto das duas portas guardadas por ela. Nesse caminho o workspace some e a cerca fica
 *       cega — <b>a cegueira continua</b> (não há como enxergar um evento que não foi gravado), mas
 *       desde o endurecimento dos resíduos dos PRs #52–#55 ela <b>não é mais silenciosa</b>.</li>
 * </ol>
 *
 * <p><b>O segundo limite está documentado aqui E sinalizado no PRODUTOR.</b> A metade que faltava —
 * a nota no {@code WorkspaceReaper.recordCollected} de que aquele {@code catch} virou load-bearing
 * para duas portas — <b>foi escrita</b>: o javadoc do método diz que o evento é a fonte desta régua
 * e o ERROR nomeia a consequência (cerca de reabertura cega naquela Issue), em vez de relatar só o
 * sintoma. Quem lê o produtor não vê mais um GC defensivo sem saber o que está engolindo. O que
 * NÃO mudou, de propósito: a remoção segue idempotente — falha de auditoria não a desfaz.
 * Comportamento pinado em {@code ReaberturaComWorkspaceColetadoTest.auditoriaQueFALHA_*}.
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
