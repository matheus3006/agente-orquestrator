package com.orquestrador.governance.parecer;

import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * O canal de emissão do PARECER (F-ORG-C/ADR-0048): o bloco {@code ```parecer```} na saída do run
 * do GESTOR, roteado pelo {@code gestor_status} do braço — ESTÁGIO grava parecer consultivo,
 * PROMOVIDO decide o ciclo. Irmão do {@code DirigirGate}, mesmo molde F-AT1: pendura no ponto de
 * saída que já existe (nenhum canal vivo novo, D-ORG-8), CAP por run, e todo descarte vira aviso
 * VISÍVEL no StepTrace (anti-silêncio) em vez de sumir.
 *
 * <p>O gestor NÃO precisa saber o próprio status (D-DSG-1 replicada): o mesmo bloco serve os dois
 * ramos, e quem escolhe é a árvore. O limite é o mesmo nos dois: só dentro da SUBÁRVORE dele
 * (D-ORG-3), medida pelo time de quem ENTREGOU a issue.
 *
 * <p>O guard aqui é fail-SOFT (aviso + segue) porque um bloco malformado não pode derrubar o run
 * do gestor; o {@link ParecerService} repete as mesmas cercas fail-CLOSED, porque segurança por
 * convenção não é segurança (ADR-0047 hardening item 2).
 */
@Component
public class ParecerGate {

    private static final Logger log = LoggerFactory.getLogger(ParecerGate.class);

    static final int CAP_PARECERES_POR_RUN = 5;

    private final ParecerScanner scanner;
    private final ParecerService pareceres;
    private final TeamRepository teams;
    private final IssueRepository issues;
    private final AgentRepository agents;
    private final ReviewRepository reviews;
    private final HeartbeatRunStore store;
    private final HeartbeatRunRepository runs;

    public ParecerGate(ParecerScanner scanner, ParecerService pareceres, TeamRepository teams,
                       IssueRepository issues, AgentRepository agents, ReviewRepository reviews,
                       HeartbeatRunStore store, HeartbeatRunRepository runs) {
        this.scanner = scanner;
        this.pareceres = pareceres;
        this.teams = teams;
        this.issues = issues;
        this.agents = agents;
        this.reviews = reviews;
        this.store = store;
        this.runs = runs;
    }

    public void handle(UUID companyId, UUID agentId, UUID runId, String reply) {
        ParecerScanner.Scan scan = scanner.scan(reply);
        if (scan.pareceres().isEmpty() && scan.descartados() == 0) {
            return;
        }
        List<String> avisos = new ArrayList<>();
        if (scan.descartados() > 0) {
            avisos.add(scan.descartados() + " bloco(s) `parecer` malformado(s), sem o porquê"
                    + " (obrigatório) ou com decisão fora de APROVAR/REJEITAR");
        }
        // O fail-SOFT declarado em :33-35 agora vale de VERDADE, e não só para o que se pré-checa
        // (hardening do PR #50). Exceção que escapasse daqui subiria até o AdapterRunExecutor, que
        // chama runToOutcome ANTES de settleAfterRun ⇒ PULARIA o settle, deixando a issue
        // IN_PROGRESS travada num execution_lock_run_id morto — e não existe reaper para essa
        // coluna: impegável para sempre. O finally garante que os avisos já acumulados cheguem ao
        // StepTrace em vez de sumirem junto com a exceção.
        try {
            avaliarPareceres(companyId, agentId, runId, scan, avisos);
        } catch (RuntimeException e) {
            avisos.add("gate de parecer interrompido: " + e);
        } finally {
            if (!avisos.isEmpty()) {
                // A gravação do aviso também pode falhar (recordStep desce em
                // findById().orElseThrow()) — e uma exceção no FINALLY substitui a original e sobe,
                // que é o dano que este bloco existe para evitar (conferência dos PRs #51/#52, 🔵).
                // Mesmo remédio do semDerrubarOSettle: último recurso é o log.
                try {
                    store.recordStep(runId, "PARECER_DISCARDED", String.join("; ", avisos));
                } catch (RuntimeException aoRegistrar) {
                    log.error("gate de parecer do run {}: os avisos {} nao chegaram ao StepTrace"
                            + " ({})", runId, avisos, aoRegistrar.toString());
                }
            }
        }
    }

    private void avaliarPareceres(UUID companyId, UUID agentId, UUID runId,
                                  ParecerScanner.Scan scan, List<String> avisos) {
        List<Team> bracos = teams.findByCompanyIdAndGestorAgentIdOrderById(companyId, agentId);
        Set<ParecerScanner.Parecer> vistos = new HashSet<>();
        int aceitos = 0;

        for (ParecerScanner.Parecer parecer : scan.pareceres()) {
            String etiqueta = "#" + parecer.issueSeq();
            // Eco do LLM: bloco byte-idêntico 2× no MESMO reply vira um parecer só (o acompanhante
            // do hardening item 1 da ADR-0047 — lá a identidade posicional exigia isto; aqui o
            // dedupe já é por ciclo, mas colapsar cedo evita um aviso enganoso de "já tinha").
            if (!vistos.add(parecer)) {
                avisos.add(etiqueta + " repetida IDÊNTICA no mesmo reply — colapsada em um parecer só");
                continue;
            }
            if (aceitos >= CAP_PARECERES_POR_RUN) {
                avisos.add(etiqueta + " além do teto (" + CAP_PARECERES_POR_RUN + "/run)");
                continue;
            }
            if (bracos.isEmpty()) {
                avisos.add(etiqueta + " descartado: o agente não é gestor titular de nenhum time");
                continue;
            }
            Issue issue = issues.findByCompanyIdAndSeq(companyId, parecer.issueSeq()).orElse(null);
            if (issue == null) {
                avisos.add("issue " + etiqueta + " não existe nesta company — nada gravado");
                continue;
            }
            // Sem ciclo PENDING não há o que opinar — e é AQUI que o settle reexecutado do ramo
            // PROMOVIDO dedupa de graça: na 2ª passada o ciclo já foi decidido, some da busca.
            Review ciclo = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, issue.getId(),
                    ReviewStatus.PENDING).orElse(null);
            if (ciclo == null) {
                avisos.add("issue " + etiqueta + " não tem ciclo de review aberto — nada gravado");
                continue;
            }
            // Espelha a cerca do service: quem ENTREGOU é o agente do run que o CICLO aponta, não
            // o assignee atual (hardening do PR #50) — mirar só no assignee viraria lavanderia se
            // um dia existir reatribuição. O assignee segue checado ao lado, como cinto.
            UUID entregador = runs.findById(ciclo.getRunId())
                    .map(HeartbeatRun::getAgentId).orElse(null);
            if (agentId.equals(issue.getAssigneeAgentId()) || agentId.equals(entregador)) {
                avisos.add(etiqueta + " é entrega do PRÓPRIO gestor — ninguém se auto-revisa");
                continue;
            }
            UUID timeDoEntregador = entregador == null ? null
                    : agents.findById(entregador).map(Agent::getTeamId).orElse(null);
            // Multi-braço: vence o MENOS PRIVILEGIADO (hardening do PR #50). A régua mora no
            // SERVICE desde a conferência do PR #53 (🟡) e é a MESMA chamada que o guard de lá usa
            // para conferir a escolha — uma régua, duas cercas. Enquanto for uma chamada só, gate
            // e service não podem divergir sobre qual braço se aplica; a cópia inline que vivia
            // aqui era justamente o que permitia divergir.
            Team braco = pareceres.bracoDeMenorPrivilegio(companyId, agentId, timeDoEntregador)
                    .orElse(null);
            if (braco == null) {
                avisos.add("issue " + etiqueta + " está FORA do braço do gestor (D-ORG-3)"
                        + " — nada gravado");
                continue;
            }
            aceitos++;
            ParecerService.PedidoDeParecer pedido = new ParecerService.PedidoDeParecer(
                    issue.getId(), ciclo.getId(), braco.getId(), runId, parecer.decisao(),
                    parecer.texto());
            // O TOCTOU dono×gestor mora AQUI: o dono pode decidir o ciclo entre a resolução acima e
            // o ato lá dentro, e o service (fail-CLOSED, como deve ser) levanta. Do ponto de vista
            // deste canal isso é RECUSA NORMAL — o gestor chegou tarde —, não falha do run: vira
            // aviso contado e o lote SEGUE para os próximos pareceres.
            try {
                if (braco.gestorPromovido()) {
                    pareceres.decidirReviewComoGestor(companyId, agentId, pedido);
                } else {
                    ParecerService.ResultadoDoParecer resultado =
                            pareceres.registrarParecerDoGestor(companyId, agentId, pedido);
                    if (resultado != ParecerService.ResultadoDoParecer.GRAVADO) {
                        avisos.add(etiqueta + " " + motivoDaRecusa(resultado));
                    }
                }
            } catch (RuntimeException e) {
                avisos.add(etiqueta + " recusado pelo service: " + e.getMessage());
            }
        }
    }

    /**
     * O aviso que o operador lê, UM por causa (conferência do PR #53, 🟡 2). A forma anterior era
     * uma frase só — <i>"já tinha parecer neste ciclo — mantido o primeiro"</i> — para as DUAS
     * causas de {@code rows == 0}. Na causa nova (o dono decidiu no meio) ela afirmava duas coisas
     * falsas de uma vez: que existe um parecer e que ele foi mantido. O operador ia caçar uma
     * duplicata que não existe, quando o fato é uma corrida com o dono.
     */
    static String motivoDaRecusa(ParecerService.ResultadoDoParecer resultado) {
        return switch (resultado) {
            case JA_TINHA_PARECER -> "já tinha parecer neste ciclo — mantido o primeiro";
            case DONO_DECIDIU_NO_MEIO -> "o ciclo foi DECIDIDO antes do parecer chegar — o gestor"
                    + " chegou tarde e nada foi gravado (não há duplicata)";
            case CICLO_MUDOU -> "o ciclo não aceitou o parecer e a re-leitura não explica por quê"
                    + " — nada gravado";
            case GRAVADO -> throw new IllegalStateException("GRAVADO não é recusa");
        };
    }
}
