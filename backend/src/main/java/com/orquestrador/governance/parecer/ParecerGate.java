package com.orquestrador.governance.parecer;

import com.orquestrador.execution.HeartbeatRunStore;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.ChainOfCommandService;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
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

    static final int CAP_PARECERES_POR_RUN = 5;

    private final ParecerScanner scanner;
    private final ParecerService pareceres;
    private final TeamRepository teams;
    private final IssueRepository issues;
    private final AgentRepository agents;
    private final ReviewRepository reviews;
    private final ChainOfCommandService chain;
    private final HeartbeatRunStore store;

    public ParecerGate(ParecerScanner scanner, ParecerService pareceres, TeamRepository teams,
                       IssueRepository issues, AgentRepository agents, ReviewRepository reviews,
                       ChainOfCommandService chain, HeartbeatRunStore store) {
        this.scanner = scanner;
        this.pareceres = pareceres;
        this.teams = teams;
        this.issues = issues;
        this.agents = agents;
        this.reviews = reviews;
        this.chain = chain;
        this.store = store;
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
        List<Team> bracos = teams.findByCompanyIdAndGestorAgentId(companyId, agentId);
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
            if (agentId.equals(issue.getAssigneeAgentId())) {
                avisos.add(etiqueta + " é entrega do PRÓPRIO gestor — ninguém se auto-revisa");
                continue;
            }
            UUID timeDoEntregador = issue.getAssigneeAgentId() == null ? null
                    : agents.findById(issue.getAssigneeAgentId()).map(Agent::getTeamId).orElse(null);
            Team braco = timeDoEntregador == null ? null : bracos.stream()
                    .filter(time -> chain.pertenceASubarvore(time.getId(), timeDoEntregador))
                    .findFirst().orElse(null);
            if (braco == null) {
                avisos.add("issue " + etiqueta + " está FORA do braço do gestor (D-ORG-3)"
                        + " — nada gravado");
                continue;
            }
            aceitos++;
            ParecerService.PedidoDeParecer pedido = new ParecerService.PedidoDeParecer(
                    issue.getId(), ciclo.getId(), braco.getId(), runId, parecer.decisao(),
                    parecer.texto());
            if (braco.gestorPromovido()) {
                pareceres.decidirReviewComoGestor(companyId, agentId, pedido);
            } else if (!pareceres.registrarParecerDoGestor(companyId, agentId, pedido)) {
                avisos.add(etiqueta + " já tinha parecer neste ciclo — mantido o primeiro");
            }
        }

        if (!avisos.isEmpty()) {
            store.recordStep(runId, "PARECER_DISCARDED", String.join("; ", avisos));
        }
    }
}
