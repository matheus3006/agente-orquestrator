package com.orquestrador.governance.parecer;

import com.orquestrador.activitylog.ActivityAction;
import com.orquestrador.activitylog.ActivityLogService;
import com.orquestrador.common.security.ActorType;
import com.orquestrador.execution.HeartbeatRun;
import com.orquestrador.execution.HeartbeatRunRepository;
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
 *   <li>{@link #exigirGestorTitularComAlvoNoBraco} roda <b>antes de qualquer EFEITO</b> e antes do
 *       dedupe nas duas entradas públicas — o service REVALIDA sozinho, sem confiar no chamador
 *       (a lição do hardening item 2 do PR #49: guard só no gate é seguro por CONVENÇÃO, não por
 *       construção). <b>Não é a primeira LINHA</b>, e a distinção importa para quem grepar isto
 *       amanhã: desde o PR #52 ele roda em SEGUNDO ({@code :91-92} e {@code :138-139}), depois de
 *       {@link #cicloAbertoAncorado}, porque a cerca de auto-review passou a mirar em QUEM
 *       ENTREGOU e o entregador só se resolve a partir do ciclo. O que precede o guard é leitura
 *       pura + {@code throw}, de efeito ZERO — a propriedade de segurança (nenhum efeito antes da
 *       autorização) continua valendo; o que mudou foi só QUAL exceção aflora quando o chamador é
 *       não-titular E não há ciclo aberto: hoje vence "não tem ciclo de review aberto";</li>
 *   <li>{@link ActorType#AGENT} entra como <b>literal fixo</b> no único call site do decide,
 *       nunca como parâmetro. Um {@code decidirReviewComo(ActorType, ...)} parametrizável seria
 *       bypass PERMANENTE do {@code requireBoard} pra QUALQUER ator — proibido como consequência
 *       silenciosa desta fatia; generalizar exige decisão nova com revisão de segurança própria;</li>
 *   <li>asserts defensivos amarram guard e ato (pré: o ciclo decidido é o MESMO que passou pelo
 *       guard) e conferem o carimbo (pós: quem ficou gravado como revisor é o gestor, como AGENT);</li>
 *   <li>call site único de produção: o {@link ParecerGate}.</li>
 * </ul>
 *
 * <p><b>Dedupe: identidade de LINHA, arbitrada pelo BANCO nos dois ramos</b> (ADR-0048 §4, emendada
 * no hardening do PR #50). O parecer é 1-para-1 com o CICLO, então prosa reformulada pelo LLM não
 * fura nenhum dos dois ramos — mas "sai de graça" era FALSO no ramo ESTÁGIO: ele era read-then-write
 * puro ({@code temParecer()} e depois {@code save}), e sob concorrência real seis settles
 * simultâneos gravavam os seis, cada um sobrescrevendo o anterior, todos devolvendo "gravei". O
 * teste que "provava" o dedupe chamava o gate 2× EM SEQUÊNCIA — provava o zumbi sequencial, não o
 * split-brain.
 *
 * <p><b>Os dois ramos guardam o CICLO DE VIDA, não só a duplicata</b> (conferência dos PRs
 * #51/#52, 🟠 1). A forma anterior desta frase — "hoje os dois ramos têm CAS de banco: PROMOVIDO
 * por {@code where status='PENDING_REVIEW'}, ESTÁGIO por {@code where parecer is null}" —
 * apresentava como SIMÉTRICOS dois guards que guardavam coisas diferentes: o promovido barrava
 * ciclo já decidido, o estágio só barrava parecer repetido. Era a mesma forma de over-claim do
 * "sai de graça" que esta classe veio corrigir, um nível abaixo. O UPDATE do estágio hoje carrega
 * as DUAS cercas ({@code and status = 'PENDING'} + {@code and parecer is null}), então a simetria
 * afirmada aqui existe de fato — ver {@link ReviewRepository#tryRegistrarParecer}.
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
    private final HeartbeatRunRepository runs;

    public ParecerService(ReviewRepository reviews, IssueRepository issues, IssueService issueService,
                          AgentRepository agents, TeamRepository teams, ChainOfCommandService chain,
                          ActivityLogService activityLog, HeartbeatRunRepository runs) {
        this.reviews = reviews;
        this.issues = issues;
        this.issueService = issueService;
        this.agents = agents;
        this.teams = teams;
        this.chain = chain;
        this.activityLog = activityLog;
        this.runs = runs;
    }

    /**
     * Ramo ESTÁGIO: grava o parecer CONSULTIVO no ciclo. Não toca {@code status},
     * {@code reviewerActorType} nem {@code decisionReason} — a autoridade não muda, o dono
     * continua decidindo vendo o parecer ao lado (D-ORG-2).
     *
     * @return {@code false} se o ciclo JÁ tinha parecer (primeiro-vence, arbitrado pelo BANCO) —
     *         o gate transforma isso em aviso contado, nunca em silêncio.
     */
    @Transactional
    public boolean registrarParecerDoGestor(UUID companyId, UUID gestorAgentId, PedidoDeParecer pedido) {
        Review ciclo = cicloAbertoAncorado(companyId, pedido);
        exigirGestorTitularComAlvoNoBraco(companyId, gestorAgentId, pedido, ciclo);
        UUID cicloId = ciclo.getId();
        // UPDATE guardado (`and parecer is null`), não read-then-write: o read-then-write anterior
        // era lost update sob concorrência real — 6 settles simultâneos gravavam os 6, cada um
        // sobrescrevendo o anterior e todos devolvendo "gravei" (hardening do PR #50, achado 🔴 2).
        int rows = reviews.tryRegistrarParecer(cicloId, companyId, gestorAgentId,
                textoDoParecer(pedido));
        if (rows == 0) {
            return false;
        }
        activityLog.record(companyId, ActorType.AGENT, gestorAgentId,
                ActivityAction.REVIEW_PARECER_REGISTRADO, "issue", pedido.issueId(),
                Map.of("reviewId", cicloId, "bracoTeamId", pedido.bracoTeamId(),
                        "runId", pedido.runId()));
        return true;
    }

    /**
     * Ramo PROMOVIDO: o gestor DECIDE o ciclo, com {@code reviewerActorType=AGENT} (o dado já
     * aceita isso desde a ADR-0012). Passa pelo MESMO {@code IssueService.review} do dono, então
     * herda tudo que a review humana respeita: REJECT exige motivo, transição guardada por
     * {@code PENDING_REVIEW} e a fábrica de Decisão.
     *
     * <p><b>Reversível pelo dono nos DOIS lados</b> (ADR-0049, decisão do dono de 2026-07-20).
     * REJECT → BLOCKED sempre foi reabrível pelo {@code unblock}; APPROVE → DONE <b>não era</b> —
     * {@code DONE} não aparecia como estado-ORIGEM em UPDATE guardado nenhum, então era SINK, e
     * esta classe afirmava o contrário. O {@code IssueService.reabrirEntregaAprovada} fecha a
     * assimetria: é ele, não o {@code unblock}, que desfaz um APPROVE.
     *
     * <p><b>O que a âncora {@code expectedReviewId} protege AQUI, com precisão</b> (corrigido no
     * hardening do PR #50): ela guarda a janela entre a resolução do ciclo neste service e a
     * resolução dentro do {@code IssueService.review} — se o dono decidir no meio, o UPDATE guardado
     * casa 0 linhas ou a âncora diverge, e nada é decidido. Ela <b>NÃO</b> é o que é no caminho
     * humano: lá a âncora vem de FORA (a UI do dono viu o ciclo X), aqui ela é auto-gerada pelo
     * gate a partir da mesma query. <b>Nada carrega a identidade do ciclo sobre o qual o LLM
     * raciocinou</b> — o bloco ```parecer``` só traz o {@code #N} da issue. A ADR-0048 §3 dizia que
     * este ramo "herda de graça a âncora": herda a CHAMADA, não a proteção.
     *
     * <p>Amarrar identidade de raciocínio foi considerado e RECUSADO: a única âncora disponível
     * sem exigir do LLM um UUID que ele não tem seria temporal ("o ciclo já existia quando o run do
     * gestor começou"), e ela passa a produzir recusa FALSA assim que a F-ORG-E/F-ORG-G derem ao
     * gestor tools que listam review DURANTE o run — seria construir um guard que a próxima fatia
     * teria de remover. Fica registrado como limite conhecido, não como proteção que não protege.
     */
    @Transactional
    public void decidirReviewComoGestor(UUID companyId, UUID gestorAgentId, PedidoDeParecer pedido) {
        Review ciclo = cicloAbertoAncorado(companyId, pedido);
        exigirGestorTitularComAlvoNoBraco(companyId, gestorAgentId, pedido, ciclo);
        exigirBracoPromovido(pedido.bracoTeamId(), companyId);
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
     * <p><b>A cerca mira em QUEM ENTREGOU, não no assignee ATUAL</b> (hardening do PR #50): o
     * artefato sob revisão é o output do run que o CICLO aponta ({@code Review.runId}), e é o autor
     * DELE que não pode se auto-aprovar. Os dois coincidem hoje, mas por invariante que nenhum
     * UPDATE guardado afirma — com um endpoint de reatribuição, mirar no assignee viraria
     * LAVANDERIA (entregar, reatribuir a um subordinado, aprovar a própria entrega). A cerca do
     * assignee fica DE PÉ ao lado, como cinto: quem é o dono atual da issue também não a julga.
     *
     * <p>Entregador irresolvível (run sumido) é recusado, e issue sem assignee também: o default
     * silencioso seria justamente o furo.
     */
    private void exigirGestorTitularComAlvoNoBraco(UUID companyId, UUID gestorAgentId,
                                                   PedidoDeParecer pedido, Review ciclo) {
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
        UUID entregador = entregadorDoCiclo(ciclo);
        if (gestorAgentId.equals(entregador)) {
            throw new IllegalStateException("parecer recusado: " + gestorAgentId
                    + " é quem ENTREGOU o artefato do ciclo " + ciclo.getId()
                    + " — gestor não revisa a própria entrega (ADR-0048)");
        }
        UUID timeDoEntregador = entregador == null ? null
                : agents.findById(entregador).map(Agent::getTeamId).orElse(null);
        if (timeDoEntregador == null || !chain.pertenceASubarvore(braco.getId(), timeDoEntregador)) {
            throw new IllegalStateException("parecer recusado: a issue " + issue.getId()
                    + " está FORA da subárvore do braço " + braco.getId() + " (D-ORG-3/ADR-0048)");
        }
    }

    /**
     * QUEM PRODUZIU o artefato sob revisão: o agente do run que o ciclo aponta. É a identidade
     * certa para as duas cercas (auto-review e subárvore) — o assignee é proxy que pode divergir.
     */
    private UUID entregadorDoCiclo(Review ciclo) {
        return runs.findById(ciclo.getRunId()).map(HeartbeatRun::getAgentId).orElse(null);
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
