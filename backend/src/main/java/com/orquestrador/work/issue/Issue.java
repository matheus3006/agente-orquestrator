package com.orquestrador.work.issue;

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
 * Unidade de trabalho. Single-assignee + execution lock: o {@code executionLockRunId}
 * e a trava que garante que so um Run executa a Issue por vez. A transicao de posse
 * acontece via UPDATE guardado atomico (ver {@link IssueRepository#tryCheckout}),
 * nunca por read-modify-write em memoria.
 */
@Entity
@Table(name = "issue", uniqueConstraints =
        @UniqueConstraint(name = "uq_issue_company_seq", columnNames = {"company_id", "seq"}))
public class Issue {

    @Id
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    // Identidade sequencial humana (#N por company, ADR-0033): denso, imutavel, comeca em 1.
    // Alocado em IssueService.create sob o lock da linha da company; a unique e backstop.
    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IssueStatus status = IssueStatus.OPEN;

    @Column(name = "assignee_agent_id")
    private UUID assigneeAgentId;

    @Column(name = "execution_lock_run_id")
    private UUID executionLockRunId;

    // Caso B (ADR-0034, V26): a task que esta corrige (self-FK, 1:1). Vazia em task normal;
    // preenchida so na corretiva — a linhagem (correcao de correcao) e a corrente ate a raiz.
    @Column(name = "corrige_issue_id")
    private UUID corrigeIssueId;

    // Absorção (ADR-0037, V27): a conversa DIRECIONADA da issue (1:1, assunto "#N — <título>").
    // Nula em issue não-absorvida (gate/corretiva/legado) — o trilho da generalização futura.
    @Column(name = "conversation_id")
    private UUID conversationId;

    // Dirigida sem gate (ADR-0047, D-DSG-6, V37): o GESTOR PROMOVIDO que dirigiu esta issue pro
    // próprio braço sem passar pelo gate humano. Nula em issue normal E na issue do ramo ESTÁGIO
    // (aprovada em lote — passou pelo gate como qualquer outra; decisão consciente do dono).
    @Column(name = "dirigida_por_gestor_agent_id")
    private UUID dirigidaPorGestorAgentId;

    // Escala 1-5 do review gate (ADR-0012): 1 = sem risco; risco >= limiar → PENDING_REVIEW no settle.
    @Column(name = "risk_level", nullable = false)
    private int riskLevel = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Issue() {
    }

    public Issue(UUID id, UUID companyId, int seq, String title, String description, int riskLevel) {
        this.id = id;
        this.companyId = companyId;
        this.seq = seq;
        this.title = title;
        this.description = description;
        this.status = IssueStatus.OPEN;
        this.riskLevel = riskLevel;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public int getSeq() {
        return seq;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public IssueStatus getStatus() {
        return status;
    }

    public UUID getAssigneeAgentId() {
        return assigneeAgentId;
    }

    public UUID getExecutionLockRunId() {
        return executionLockRunId;
    }

    public UUID getCorrigeIssueId() {
        return corrigeIssueId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    /**
     * A ancoragem da absorção (ADR-0037, D4): liga a issue à SUA conversa direcionada. Chamada
     * uma vez, na criação pelo dono (mesma tx) — quem garante o 1:1 é o assunto único da
     * conversa ("#N — <título>") + o disparo único do gatilho (D9.1).
     */
    void ancorarConversa(UUID conversationId) {
        this.conversationId = conversationId;
    }

    /**
     * O nascimento da CORRETIVA (caso B, ADR-0034): ligada a task que corrige e JA COM DONO —
     * o assignee pre-setado entrega a task na mesa do arquiteto; o guard do
     * {@link IssueRepository#tryCheckout} ({@code assignee is null or = :agentId}) garante que
     * so ele a executa. So faz sentido antes do primeiro save (e o caminho de nascer, nao um update).
     */
    void nascerCorrigindo(UUID corrigeIssueId, UUID assigneeAgentId) {
        this.corrigeIssueId = corrigeIssueId;
        this.assigneeAgentId = assigneeAgentId;
    }

    /**
     * O nascimento-com-dono da 2ª porta (ADR-0037, D2): a issue aprovada no gate já sai com o
     * arquiteto do time-alvo — "nada que o CEO cria nasce sem dono". Como o da corretiva, só
     * faz sentido antes do primeiro save.
     */
    void nascerAtribuida(UUID assigneeAgentId) {
        this.assigneeAgentId = assigneeAgentId;
    }

    /**
     * O selo da issue dirigida SEM GATE (ADR-0047, D-DSG-6): marca qual gestor promovido a
     * dirigiu — a superfície mostra "dirigida · <gestor>" sem abrir nada. SÓ o caminho
     * nasce-decidida sela; o ramo ESTÁGIO nunca chama isto. Como os demais nascimentos,
     * só faz sentido antes do primeiro save.
     */
    void selarDirigidaPor(UUID gestorAgentId) {
        this.dirigidaPorGestorAgentId = gestorAgentId;
    }

    public UUID getDirigidaPorGestorAgentId() {
        return dirigidaPorGestorAgentId;
    }

    public int getRiskLevel() {
        return riskLevel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
