package com.orquestrador.governance.parecer;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.ChainOfCommandService;
import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueRepository;
import com.orquestrador.work.issue.IssueService;
import com.orquestrador.work.review.Review;
import com.orquestrador.work.review.ReviewDecision;
import com.orquestrador.work.review.ReviewRepository;
import com.orquestrador.work.review.ReviewStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * As DUAS portas do gestor no gate de Review (F-ORG-C/ADR-0048), roteadas pelo {@code
 * gestor_status} do braço — parecer consultivo em ESTÁGIO, decisão em PROMOVIDO.
 *
 * <p><b>Escopo de segurança (molde da ADR-0047 §3, replicado elemento por elemento).</b> O ramo
 * PROMOVIDO é a SEGUNDA porta de autorização não-humana da casa: ele decide um ciclo de review
 * sem passar pelos dois {@code requireBoard} (IssueController/ReviewController). Por isso:
 * <ul>
 *   <li>{@link #exigirGestorTitularComAlvoNoBraco} roda como <b>PRIMEIRA linha</b> das duas
 *       entradas públicas, ANTES de qualquer efeito e antes do dedupe — o service REVALIDA
 *       sozinho, sem confiar no chamador (a lição do hardening item 2 do PR #49: guard só no gate
 *       é seguro por CONVENÇÃO, não por construção);</li>
 *   <li>{@link ActorType#AGENT} entra como <b>literal fixo</b> no único call site do decide,
 *       nunca como parâmetro. Um {@code decidirReviewComo(ActorType, ...)} parametrizável seria
 *       bypass PERMANENTE do {@code requireBoard} pra QUALQUER ator — proibido como consequência
 *       silenciosa desta fatia; generalizar exige decisão nova com revisão de segurança própria;</li>
 *   <li>asserts defensivos amarram guard e ato (pré: o ciclo decidido é o MESMO que passou pelo
 *       guard) e conferem o carimbo (pós: quem ficou gravado como revisor é o gestor, como AGENT);</li>
 *   <li>call site único de produção: o {@link ParecerGate}.</li>
 * </ul>
 *
 * <p><b>Dedupe é ESTRUTURAL e sai de graça</b> (ADR-0048 §4) — sem fingerprint, ao contrário do
 * caminho sem-gate da ADR-0047. O parecer é 1-para-1 com o CICLO: no zumbi do lease-reaper (mesmo
 * {@code runId} re-executado, com chamada REAL nova ao LLM que reformula a prosa) o ESTÁGIO
 * reencontra o ciclo já com parecer e o PROMOVIDO não reencontra ciclo PENDING nenhum. Identidade
 * de LINHA, não de texto: prosa reformulada não fura nenhum dos dois ramos.
 */
@Service
public class ParecerService {

    private final ReviewRepository reviews;
    private final IssueRepository issues;
    private final IssueService issueService;
    private final AgentRepository agents;
    private final TeamRepository teams;
    private final ChainOfCommandService chain;
    private final ActivityLogService activityLog;

    public ParecerService(ReviewRepository reviews, IssueRepository issues, IssueService issueService,
                          AgentRepository agents, TeamRepository teams, ChainOfCommandService chain,
                          ActivityLogService activityLog) {
        this.reviews = reviews;
        this.issues = issues;
        this.issueService = issueService;
        this.agents = agents;
        this.teams = teams;
        this.chain = chain;
        this.activityLog = activityLog;
    }

    /**
     * Ramo ESTÁGIO: grava o parecer CONSULTIVO no ciclo. Não toca {@code status},
     * {@code reviewerActorType} nem {@code decisionReason} — a autoridade não muda, o dono
     * continua decidindo vendo o parecer ao lado (D-ORG-2).
     *
     * @return {@code false} se o ciclo JÁ tinha parecer (primeiro-vence: o dedupe estrutural do
     *         settle reexecutado) — o gate transforma isso em aviso contado, nunca em silêncio.
     */
    @Transactional
    public boolean registrarParecerDoGestor(UUID companyId, UUID gestorAgentId, PedidoDeParecer pedido) {
        exigirGestorTitularComAlvoNoBraco(companyId, gestorAgentId, pedido);
        Review ciclo = cicloAbertoAncorado(companyId, pedido);
        if (ciclo.temParecer()) {
            return false;
        }
        ciclo.registrarParecer(gestorAgentId, textoDoParecer(pedido));
        reviews.save(ciclo);
        activityLog.record(companyId, ActorType.AGENT, gestorAgentId,
                ActivityAction.REVIEW_PARECER_REGISTRADO, "issue", pedido.issueId(),
                Map.of("reviewId", ciclo.getId(), "bracoTeamId", pedido.bracoTeamId(),
                        "runId", pedido.runId()));
        return true;
    }

    /**
     * Ramo PROMOVIDO: o gestor DECIDE o ciclo, com {@code reviewerActorType=AGENT} (o dado já
     * aceita isso desde a ADR-0012). Passa pelo MESMO {@code IssueService.review} do dono, então
     * herda tudo que a review humana respeita: REJECT exige motivo, âncora {@code expectedReviewId},
     * transição guardada por {@code PENDING_REVIEW} e a fábrica de Decisão. O dono continua podendo
     * reabrir pelo {@code unblock} — nada aqui é irreversível.
     */
    @Transactional
    public void decidirReviewComoGestor(UUID companyId, UUID gestorAgentId, PedidoDeParecer pedido) {
        exigirGestorTitularComAlvoNoBraco(companyId, gestorAgentId, pedido);
        exigirBracoPromovido(pedido.bracoTeamId(), companyId);
        Review ciclo = cicloAbertoAncorado(companyId, pedido);
        // Pré-condição: o ciclo que vai ser decidido é o MESMO que passou pelo guard acima.
        // Amarra guard e ato — sem isto, um refactor que reordenasse a resolução abriria janela
        // pra decidir um ciclo diferente do que foi autorizado.
        if (!ciclo.getIssueId().equals(pedido.issueId())) {
            throw new IllegalStateException("parecer recusado: o ciclo " + ciclo.getId()
                    + " não é da issue " + pedido.issueId() + " (ADR-0048)");
        }
        // ActorType.AGENT é LITERAL, nunca parâmetro (ADR-0047 §3 replicada): um decisor
        // parametrizável aqui seria bypass permanente do requireBoard pra qualquer ator.
        issueService.review(companyId, pedido.issueId(), pedido.decisao(), textoDoParecer(pedido),
                ciclo.getId(), ActorType.AGENT, gestorAgentId);
        // Pós-condição: o compilador não pega troca de literal nem refactor que ignore o ator
        // recebido; o assert pega. Confere o carimbo que de fato ficou gravado no ciclo.
        Review carimbado = reviews.findById(ciclo.getId()).orElseThrow();
        if (carimbado.getReviewerActorType() != ActorType.AGENT
                || !gestorAgentId.equals(carimbado.getReviewerActorId())) {
            throw new IllegalStateException("parecer recusado: o ciclo " + ciclo.getId()
                    + " não ficou carimbado como decidido pelo gestor " + gestorAgentId
                    + " (ADR-0048) — decisão delegada sem rastro é pior que decisão nenhuma");
        }
    }

    /**
     * O guard de braço, fail-closed, replicando {@code exigirGestoraPromovidaComAlvoNoBraco}
     * (ADR-0047 hardening item 2). CINCO cercas, todas {@link IllegalStateException}: braço existe
     * na company · o ator é o gestor TITULAR dele · a issue existe na company · o gestor NÃO é o
     * assignee · o time de quem ENTREGOU pertence à subárvore do braço — reuso de
     * {@link ChainOfCommandService#pertenceASubarvore} (uma régua, duas cercas), nunca BFS novo.
     *
     * <p>A cerca do AUTO-REVIEW não tem irmã na ADR-0047 e é desta fatia: {@code pertenceASubarvore}
     * inclui a raiz nela mesma, então o gestor está DENTRO do próprio braço — sem esta linha, um
     * gestor promovido assignado a uma issue do próprio time aprovaria a PRÓPRIA entrega, que é
     * exatamente o que o review gate existe pra impedir. Vale nos DOIS ramos: opinar sobre a
     * própria entrega também não é julgar o time (D-ORG-1).
     *
     * <p>Issue sem assignee é recusada: sem quem entregou não há braço a que ela pertença, e o
     * default silencioso seria justamente o furo.
     */
    private void exigirGestorTitularComAlvoNoBraco(UUID companyId, UUID gestorAgentId,
                                                   PedidoDeParecer pedido) {
        Team braco = teams.findById(pedido.bracoTeamId())
                .filter(time -> companyId.equals(time.getCompanyId()))
                .orElseThrow(() -> new IllegalStateException("parecer recusado: o braço "
                        + pedido.bracoTeamId() + " não existe nesta company (ADR-0048)"));
        if (!gestorAgentId.equals(braco.getGestorAgentId())) {
            throw new IllegalStateException("parecer recusado: " + gestorAgentId
                    + " não é o gestor titular do braço " + braco.getId() + " (ADR-0048)");
        }
        Issue issue = issues.findByIdAndCompanyId(pedido.issueId(), companyId)
                .orElseThrow(() -> new IllegalStateException("parecer recusado: a issue "
                        + pedido.issueId() + " não existe nesta company (ADR-0048)"));
        if (gestorAgentId.equals(issue.getAssigneeAgentId())) {
            throw new IllegalStateException("parecer recusado: " + gestorAgentId
                    + " é o ASSIGNEE da issue " + issue.getId() + " — gestor não se auto-revisa"
                    + " (ADR-0048)");
        }
        UUID timeDoEntregador = issue.getAssigneeAgentId() == null ? null
                : agents.findById(issue.getAssigneeAgentId()).map(Agent::getTeamId).orElse(null);
        if (timeDoEntregador == null || !chain.pertenceASubarvore(braco.getId(), timeDoEntregador)) {
            throw new IllegalStateException("parecer recusado: a issue " + issue.getId()
                    + " está FORA da subárvore do braço " + braco.getId() + " (D-ORG-3/ADR-0048)");
        }
    }

    /** A cerca EXTRA do ramo que decide: só PROMOVIDO decide, ESTÁGIO só opina. */
    private void exigirBracoPromovido(UUID bracoTeamId, UUID companyId) {
        Team braco = teams.findById(bracoTeamId)
                .filter(time -> companyId.equals(time.getCompanyId()))
                .orElseThrow(() -> new IllegalStateException("parecer recusado: o braço "
                        + bracoTeamId + " não existe nesta company (ADR-0048)"));
        if (!braco.gestorPromovido()) {
            throw new IllegalStateException("parecer recusado: o gestor do braço " + braco.getId()
                    + " está em ESTÁGIO — decidir review é só PROMOVIDO (ADR-0048)");
        }
    }

    /** O ciclo PENDING da issue, conferido contra a âncora que o gate resolveu (R.3). */
    private Review cicloAbertoAncorado(UUID companyId, PedidoDeParecer pedido) {
        Review ciclo = reviews.findByCompanyIdAndIssueIdAndStatus(companyId, pedido.issueId(),
                        ReviewStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("parecer recusado: a issue "
                        + pedido.issueId() + " não tem ciclo de review aberto (ADR-0048)"));
        if (!ciclo.getId().equals(pedido.reviewId())) {
            throw new IllegalStateException("parecer recusado: ancorado no ciclo "
                    + pedido.reviewId() + " mas o ciclo aberto é " + ciclo.getId() + " (ADR-0048)");
        }
        return ciclo;
    }

    /**
     * A POSIÇÃO do gestor e o porquê num texto só — de propósito.
     *
     * <p>Não existe coluna legível-por-máquina com o voto (D-ORG-2, decisão 4 do kickoff: "parecer
     * não é voto"). Com um enum ao lado do texto, pré-selecionar o botão do dono a partir dele
     * viraria uma linha de UI que ninguém revisa; sem ele, a UI NÃO TEM como pré-selecionar —
     * a proibição fica valendo por construção, não por disciplina. A composição é do SERVICE
     * (determinística), não do LLM: o dono lê sempre o mesmo formato.
     */
    private static String textoDoParecer(PedidoDeParecer pedido) {
        String posicao = pedido.decisao() == ReviewDecision.APPROVE ? "APROVARIA" : "REJEITARIA";
        return posicao + " — " + pedido.texto();
    }

    /**
     * O pedido já RESOLVIDO pelo gate: #N virou {@code issueId}, o ciclo aberto virou
     * {@code reviewId} e o braço que contém o alvo virou {@code bracoTeamId}. Mesma forma do
     * {@code OrdemDirigida} — o service revalida tudo isso, não confia em nenhum deles.
     */
    public record PedidoDeParecer(UUID issueId, UUID reviewId, UUID bracoTeamId, UUID runId,
                                  ReviewDecision decisao, String texto) {
    }
}
