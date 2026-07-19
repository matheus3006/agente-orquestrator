package com.orquestrador.org.team;

import com.orquestrador.common.security.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * TIME = entidade real (ADR-0017, T.2): domínio (macro: Dev/Marketing) + foco (livre) identificam o
 * time na company (unique declarado — H2 força). O arquiteto é DESIGNADO e pode ser nulo: a trava
 * "time sem arquiteto não dispara trabalho" vive no DISPATCH ({@link TeamService#assertDispatchAllowed}),
 * não na criação — o time monta aos poucos.
 *
 * <p><b>E o time é um NÓ DA ÁRVORE (ADR-0045, D-ORG-9): a árvore de times É o organograma.</b>
 * {@code parentTeamId} nulo = pendurado direto na EMPRESA (a raiz — que é a company, não um time
 * sintético). O {@code gestorAgentId} é o responsável ORGANIZACIONAL, de quem a chefia deriva
 * ({@code ChainOfCommandService}) — posto separado do arquiteto de propósito: o arquiteto responde
 * pela ENTREGA (e destrava o dispatch), o gestor responde pelas PESSOAS (e não trava nada).
 *
 * <p><b>E o gestor titular carrega um marcador de autoridade delegada (ADR-0046, D-ORG-2):</b>
 * {@code gestorStatus} nasce ESTAGIO e só um ato explícito do dono ({@link TeamService#promoverGestor})
 * o leva a PROMOVIDO, com rastro de quem/quando. Reseta pra ESTAGIO toda vez que o titular do posto
 * troca — a confiança é NAQUELE agente, NAQUELE braço, não um selo que atravessa a empresa.
 */
@Entity
@Table(name = "team", uniqueConstraints = {
        @UniqueConstraint(name = "uq_team_company_dominio_foco",
                columnNames = {"company_id", "dominio", "foco"})
})
public class Team {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String dominio;

    @Column(nullable = false)
    private String foco;

    // O agente designado arquiteto — SEMPRE membro do time (invariante mantido por construção no
    // TeamService: sair do time limpa o posto). Nulo = time ainda sem arquiteto (dispatch travado).
    @Column(name = "arquiteto_agent_id")
    private UUID arquitetoAgentId;

    // O PAI na árvore-organograma (ADR-0045). NULL = filho da RAIZ (= a empresa) — um
    // "departamento". Sem ciclo: o guard vive no TeamService (espelha o anti-ciclo da cadeia).
    @Column(name = "parent_team_id")
    private UUID parentTeamId;

    // O GESTOR do time (ADR-0045): responsável ORGANIZACIONAL e origem da chefia derivada. SEMPRE
    // membro do time (mesmo invariante-por-construção do arquiteto). Nulo = time sem gestor — a
    // derivação SOBE pro pai; nada trava (só o arquiteto trava, e o dispatch é outro assunto).
    @Column(name = "gestor_agent_id")
    private UUID gestorAgentId;

    // O marcador de autoridade delegada (ADR-0046, D-ORG-2): ESTAGIO por default — mesmo sem
    // gestor designado, o valor fica inerte até alguém ocupar o posto. Reseta pra ESTAGIO toda vez
    // que o TITULAR do posto troca (TeamService.setGestor) — a confiança é NAQUELE agente, NAQUELE
    // braço, não um selo que atravessa a empresa (mini-grill da fatia, Pergunta 2).
    @Enumerated(EnumType.STRING)
    @Column(name = "gestor_status", nullable = false)
    private GestorStatus gestorStatus = GestorStatus.ESTAGIO;

    @Column(name = "gestor_promovido_em")
    private Instant gestorPromovidoEm;

    @Enumerated(EnumType.STRING)
    @Column(name = "gestor_promovido_por_actor_type")
    private ActorType gestorPromovidoPorActorType;

    @Column(name = "gestor_promovido_por_actor_id")
    private UUID gestorPromovidoPorActorId;

    // T.4: quanto trabalho CONCORRENTE o time do arquiteto pode espalhar (runs ativos dos membros).
    @Column(name = "fan_out_cap", nullable = false)
    private int fanOutCap = 5;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Team() {
    }

    public Team(UUID id, UUID companyId, String dominio, String foco, UUID parentTeamId) {
        this.id = id;
        this.companyId = companyId;
        this.dominio = dominio;
        this.foco = foco;
        this.parentTeamId = parentTeamId;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getDominio() {
        return dominio;
    }

    public String getFoco() {
        return foco;
    }

    public UUID getArquitetoAgentId() {
        return arquitetoAgentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Designa (ou limpa, com null) o arquiteto. A validação de membership é do TeamService. */
    public void designarArquiteto(UUID arquitetoAgentId) {
        this.arquitetoAgentId = arquitetoAgentId;
    }

    public UUID getParentTeamId() {
        return parentTeamId;
    }

    /** Pendura o time num novo pai (null = direto na empresa/raiz). O anti-ciclo é do TeamService. */
    public void pendurarEm(UUID parentTeamId) {
        this.parentTeamId = parentTeamId;
    }

    public UUID getGestorAgentId() {
        return gestorAgentId;
    }

    /** Designa (ou limpa, com null) o GESTOR. Membership validada no TeamService, como o arquiteto. */
    public void designarGestor(UUID gestorAgentId) {
        this.gestorAgentId = gestorAgentId;
    }

    /** O time tem gestor próprio? (falso = a chefia dos membros SOBE para o time-pai) */
    public boolean temGestor() {
        return gestorAgentId != null;
    }

    public GestorStatus getGestorStatus() {
        return gestorStatus;
    }

    public Instant getGestorPromovidoEm() {
        return gestorPromovidoEm;
    }

    public ActorType getGestorPromovidoPorActorType() {
        return gestorPromovidoPorActorType;
    }

    public UUID getGestorPromovidoPorActorId() {
        return gestorPromovidoPorActorId;
    }

    /** O gestor titular já foi promovido? (a condição que F-ORG-C/D vão consultar) */
    public boolean gestorPromovido() {
        return gestorStatus == GestorStatus.PROMOVIDO;
    }

    /**
     * Promove o gestor titular pra PROMOVIDO, com rastro de quem/quando (ADR-0046). Transição só
     * ESTAGIO→PROMOVIDO — validar que há gestor e que ainda não está promovido é responsabilidade
     * do {@link TeamService} (mesmo desenho da Approval: a entidade muda o estado, o serviço
     * valida a transição).
     */
    public void promoverGestor(ActorType actorType, UUID actorId) {
        this.gestorStatus = GestorStatus.PROMOVIDO;
        this.gestorPromovidoEm = Instant.now();
        this.gestorPromovidoPorActorType = actorType;
        this.gestorPromovidoPorActorId = actorId;
    }

    /**
     * Reseta pra ESTAGIO e limpa o rastro da promoção anterior (ADR-0046, mini-grill Pergunta 2):
     * chamado pelo {@link TeamService} sempre que o TITULAR do posto de gestor troca — a confiança
     * do dono era no julgamento daquele agente específico, não um selo que sobrevive à troca.
     */
    public void resetGestorStatus() {
        this.gestorStatus = GestorStatus.ESTAGIO;
        this.gestorPromovidoEm = null;
        this.gestorPromovidoPorActorType = null;
        this.gestorPromovidoPorActorId = null;
    }

    /** O time tem arquiteto designado? (a condição da trava do dispatch) */
    public boolean temArquiteto() {
        return arquitetoAgentId != null;
    }

    public int getFanOutCap() {
        return fanOutCap;
    }

    /** Redefine o cap de fan-out do time (T.4). Não-positivo é recusado: cap zero = time morto. */
    public void definirFanOutCap(int fanOutCap) {
        if (fanOutCap <= 0) {
            throw new IllegalArgumentException("fan_out_cap tem que ser positivo");
        }
        this.fanOutCap = fanOutCap;
    }
}
