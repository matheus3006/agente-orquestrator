package com.orquestrador.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.orquestrador.conversation.ConversationTurnCoordinator;
import com.orquestrador.context.ContextCompilationException;
import com.orquestrador.context.ContextPackage;
import com.orquestrador.context.RunContextCompiler;
import com.orquestrador.context.intelligence.ContextIntelligence;
import com.orquestrador.execution.adapter.AdapterRegistry;
import com.orquestrador.execution.adapter.AgentAdapter;
import com.orquestrador.execution.adapter.McpServerSpec;
import com.orquestrador.execution.adapter.WorkspaceConvention;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.ExecutionTargets;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.governance.approval.DirigirGate;
import com.orquestrador.governance.approval.SkillRequestGate;
import com.orquestrador.governance.budget.BudgetService;
import com.orquestrador.governance.parecer.ParecerGate;
import com.orquestrador.handoff.ContextHandoffLoop;
import com.orquestrador.handoff.DistillationSkill;
import com.orquestrador.memory.MemoryAudience;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import com.orquestrador.org.roletemplate.RoleTemplateService;
import com.orquestrador.planvalidation.PlanValidationGate;
import com.orquestrador.planvalidation.PlanValidationStore;
import com.orquestrador.preview.PreviewArtifactCapturer;
import com.orquestrador.preview.server.PreviewServerManifestRegistrar;
import com.orquestrador.preview.server.PreviewServerSupervisor;
import com.orquestrador.skills.CompanySkillVersion;
import com.orquestrador.skills.SkillSetService;
import com.orquestrador.skills.mcp.KnowAllMcpBridge;
import com.orquestrador.skills.mcp.KnowAllMcpMaterializer;
import com.orquestrador.skills.mcp.McpCallbackAddress;
import com.orquestrador.skills.mcp.McpRunScope;
import com.orquestrador.skills.mcp.McpToolProfile;
import com.orquestrador.work.issue.CheckoutResult;
import com.orquestrador.work.issue.Issue;
import com.orquestrador.work.issue.IssueService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Executor real (slice 3): resolve o Workspace, faz o checkout da Issue com o runId, invoca o
 * AgentAdapter no ExecutionTarget do agente (local/docker), grava o StepTrace e conclui a Issue.
 * Substitui o StubRunExecutor. (Fatia 6: materializa o SkillSet do agente no workDir apos resolve.)
 *
 * <p>T.3 (ADR-0018): um Run SEM Issue pode estar atendendo um TURNO de conversa — o claim no
 * {@link ConversationTurnCoordinator} decide. Com turno, o {@code command} é montado POR-RUN
 * (ADR-0004 emendado) e a resposta capturada do output liquida o turno no settle.
 */
@Component
public class AdapterRunExecutor implements RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(AdapterRunExecutor.class);

    /** O env por onde o prompt do turno chega a QUALQUER runtime (o process não o aceita como command). */
    static final String PROMPT_ENV = "ORQ_PROMPT";

    private final AgentRepository agents;
    private final IssueService issues;
    private final WorkspaceResolver workspaces;
    private final ExecutionTargets targets;
    private final AdapterRegistry adapters;
    private final HeartbeatRunStore store;
    private final SkillSetService skillSets;
    private final RunContextCompiler contextCompiler;
    private final ContextIntelligence contextIntelligence;
    private final BudgetService budget;
    private final ConversationTurnCoordinator turnCoordinator;
    private final AgentSessionStore agentSessions;
    private final PreviewArtifactCapturer previews;
    private final PreviewServerManifestRegistrar serverManifests;
    private final PreviewServerSupervisor previewServers;
    private final DistillationSkill distillationSkill;
    private final ContextHandoffLoop handoffLoop;
    private final SkillRequestGate skillRequestGate;
    private final DirigirGate dirigirGate;
    private final ParecerGate parecerGate;
    private final KnowAllMcpMaterializer mcpMaterializer;
    private final KnowAllMcpBridge mcpBridge;
    private final McpCallbackAddress mcpAddress;
    private final IssuePromptComposer issueComposer;
    private final RoleTemplateService roleTemplates;
    private final PlanValidationGate planValidationGate;

    public AdapterRunExecutor(AgentRepository agents, IssueService issues, WorkspaceResolver workspaces,
                              ExecutionTargets targets, AdapterRegistry adapters, HeartbeatRunStore store,
                              SkillSetService skillSets, RunContextCompiler contextCompiler,
                              ContextIntelligence contextIntelligence, BudgetService budget,
                              ConversationTurnCoordinator turnCoordinator, AgentSessionStore agentSessions,
                              PreviewArtifactCapturer previews, PreviewServerManifestRegistrar serverManifests,
                              PreviewServerSupervisor previewServers, DistillationSkill distillationSkill,
                              ContextHandoffLoop handoffLoop, SkillRequestGate skillRequestGate,
                              DirigirGate dirigirGate, ParecerGate parecerGate,
                              KnowAllMcpMaterializer mcpMaterializer, KnowAllMcpBridge mcpBridge,
                              McpCallbackAddress mcpAddress, IssuePromptComposer issueComposer,
                              RoleTemplateService roleTemplates, PlanValidationGate planValidationGate) {
        this.agents = agents;
        this.issues = issues;
        this.workspaces = workspaces;
        this.targets = targets;
        this.adapters = adapters;
        this.store = store;
        this.skillSets = skillSets;
        this.contextCompiler = contextCompiler;
        this.contextIntelligence = contextIntelligence;
        this.budget = budget;
        this.turnCoordinator = turnCoordinator;
        this.agentSessions = agentSessions;
        this.previews = previews;
        this.serverManifests = serverManifests;
        this.previewServers = previewServers;
        this.distillationSkill = distillationSkill;
        this.handoffLoop = handoffLoop;
        this.skillRequestGate = skillRequestGate;
        this.dirigirGate = dirigirGate;
        this.parecerGate = parecerGate;
        this.mcpMaterializer = mcpMaterializer;
        this.mcpBridge = mcpBridge;
        this.mcpAddress = mcpAddress;
        this.issueComposer = issueComposer;
        this.roleTemplates = roleTemplates;
        this.planValidationGate = planValidationGate;
    }

    @Override
    public RunOutcome execute(RunContext runContext) {
        RunOutcome outcome = runToOutcome(runContext);
        // F.9 (ADR-0021): captura do preview APOS o sucesso e ANTES do settle, FORA de transação —
        // quando a review nascer (dentro do settle), o artefato já existe. Best-effort: o capturador
        // garante que nenhuma falha de captura vira exceção (o settle abaixo é obrigatório).
        if (outcome.succeeded()) {
            previews.captureIfPublished(runContext);
            // F.10 (ADR-0023): a DECLARACAO de servidor e registrada no mesmo ponto — quando a
            // review nascer, o card ja sabe que o run sob revisao declarou (run_id == run_id).
            serverManifests.registerIfDeclared(runContext);
        }
        // §7 (ADR-0011): TODA saída terminal liquida a Issue — sucesso→DONE, falha→BLOCKED — sempre
        // liberando o lock. O guard (execution_lock_run_id=runId) torna no-op as falhas ANTES do checkout
        // e os checkout-conflicts (a Issue-vítima do outro run fica intacta).
        if (runContext.issueId() != null) {
            issues.settleAfterRun(runContext.companyId(), runContext.issueId(), runContext.runId(), outcome);
        }
        return outcome;
    }

    /**
     * O cinto de ÚLTIMA INSTÂNCIA dos gates de fim-de-run (hardening do PR #50, achado 🟠 do TOCTOU
     * — a exposição HERDADA, fechada para os 4 de uma vez).
     *
     * <p>Estes gates rodam dentro do {@code runToOutcome}, que o {@link #execute} chama <b>antes</b>
     * do {@code issues.settleAfterRun}. Uma exceção que suba daqui não é "um gate que falhou": ela
     * PULA o settle e deixa a Issue em {@code IN_PROGRESS} com {@code execution_lock_run_id}
     * apontando um run morto. O {@code tryCheckout} exige essa coluna null-ou-igual e <b>não existe
     * reaper para ela</b> — a Issue fica impegável para sempre, e o dano é permanente e silencioso.
     *
     * <p>Cada gate já é fail-soft por dentro; isto é o que mantém a afirmação verdadeira se um
     * deles regredir amanhã. Não silencia: o incidente vira step VISÍVEL no StepTrace, no mesmo
     * espírito anti-silêncio dos avisos de descarte.
     *
     * <p><b>A RECUPERAÇÃO também é protegida</b> (conferência dos PRs #51/#52, 🔵). O
     * {@code recordStep} desce em {@code findById().orElseThrow()}: com o run já apagado (reaper de
     * linhas, company removida) ou o banco fora, a gravação do incidente levantava DE DENTRO do
     * catch e escapava — <b>pulando exatamente o settle que este cinto existe para garantir</b>.
     * Um cinto cuja falha reproduz o dano que ele previne não é cinto. O último recurso é o log:
     * perder a linha do StepTrace é ruim, deixar a Issue impegável para sempre é pior.
     *
     * <p>Visibilidade de PACOTE de propósito (mesmo recurso do {@code WorkspaceReaper.sweepTarget}):
     * o cinto só se prova com um {@code ato} que explode, e nenhum gate real explode sob demanda —
     * é um belt para regressão FUTURA. Sem isto, a única coisa que garante o settle é a que não
     * tinha teste.
     */
    void semDerrubarOSettle(RunContext runContext, String gate, Runnable ato) {
        try {
            ato.run();
        } catch (RuntimeException e) {
            try {
                store.recordStep(runContext.runId(), "GATE_DE_SAIDA_FALHOU", gate + ": " + e);
            } catch (RuntimeException aoRegistrar) {
                log.error("gate {} falhou no run {} e o registro do incidente TAMBEM falhou"
                                + " ({}); seguindo para o settle mesmo assim — issue travada"
                                + " seria pior que step perdido. Falha original: {}",
                        gate, runContext.runId(), aoRegistrar.toString(), e.toString());
            }
        }
    }

    /**
     * O PONTO ÚNICO por onde a saída do run de Issue chega aos 4 gates de fim-de-run — e onde a
     * POSSE do run é pré-condição de todos eles (achado do PR #49, aberto desde então).
     *
     * <p><b>A "thread tardia".</b> O nó A roda o run R sobre a issue I; A trava (pausa de GC,
     * partição), o lease expira, o reaper devolve R para a fila e o nó B executa e LIQUIDA. Aí A
     * acorda, o {@code adapter.execute()} dele finalmente retorna, e A dispara os 4 gates <b>em nome
     * de um run já liquidado por outro</b> — criando Approval, issue dirigida e parecer REAIS, que
     * um humano depois tem de desfazer. O settle de A cai no vazio porque o
     * {@code execution_lock_run_id} o guarda; os gates não tinham guard nenhum. O
     * {@code semDerrubarOSettle} do PR #53 endereçou EXCEÇÃO, não posse — são coisas diferentes.
     *
     * <p><b>A régua é a mesma do banco, de propósito:</b> {@code IssueService.runAindaDetemAIssue} é
     * o predicado do {@code where} de {@code tryBlock}/{@code tryComplete}. Reusar em vez de
     * inventar é o que impede as duas cercas de divergirem amanhã.
     *
     * <p><b>Fail-CLOSED, e o lado barato de errar é este.</b> Se a posse não puder ser confirmada,
     * os gates não rodam. Um pedido de skill perdido o agente refaz no próximo run; uma issue
     * dirigida ou um parecer criados por um run fantasma ninguém desfaz sozinho. E o descarte NUNCA
     * é mudo — vira step visível, o mesmo espírito anti-silêncio dos avisos dos próprios gates.
     *
     * <p><b>Limite conhecido, escrito para não virar alegação de completude:</b> a régua é a posse
     * da ISSUE. Se o nó B ainda estiver EXECUTANDO R (o re-checkout usa o mesmo runId, então a trava
     * volta a valer R), A passa. Fecha o caso em que alguém já liquidou — o que cria artefato
     * duplicado — não a sobreposição de dois executores do mesmo runId, que exigiria posse por DONO
     * ({@code nodeId}, hoje privado do dispatcher) e é desenho de outro tamanho.
     *
     * <p>Visibilidade de PACOTE de propósito (mesmo recurso do {@link #semDerrubarOSettle} e do
     * {@code WorkspaceReaper.sweepTarget}): a decisão que este método toma — agir ou descartar —
     * não se prova por fora sem um adapter inteiro em cena.
     */
    void entregarSaidaAosGates(RunContext runContext, Agent agent, String reply, boolean noop) {
        if (!posseConfirmada(runContext)) {
            return;
        }
        semDerrubarOSettle(runContext, "skillRequestGate", () ->
                skillRequestGate.handle(runContext.companyId(), runContext.agentId(),
                        runContext.issueId(), runContext.runId(), reply));
        // ADR-0047 (D-DSG-1): o bloco ```dirigir``` do GESTOR sai pelo MESMO canal de saída
        // (molde F-AT1) — zero canal vivo novo. O gate roteia pelo gestor_status do braço
        // (guard de subárvore no momento do ato); quem não é gestor tem os blocos descartados
        // com aviso visível.
        semDerrubarOSettle(runContext, "dirigirGate", () ->
                dirigirGate.handle(runContext.companyId(), runContext.agentId(),
                        runContext.runId(), reply));
        // ADR-0048 (F-ORG-C): o bloco ```parecer``` do GESTOR sobre um ciclo de review ABERTO
        // no braço dele, pelo mesmo canal de saída. Roteia pelo gestor_status: ESTÁGIO grava
        // parecer consultivo ao lado do card do dono, PROMOVIDO decide o ciclo. Quem não é
        // gestor (ou mira fora da subárvore) tem os blocos descartados com aviso visível.
        semDerrubarOSettle(runContext, "parecerGate", () ->
                parecerGate.handle(runContext.companyId(), runContext.agentId(),
                        runContext.runId(), reply));
        // Uso B / B-ii (ADR-0044): o MESMO texto do plano vai ao validador local — 2º consumidor
        // do reply, como o gate de skill. O no-op é pulado AQUI porque o guard de no-op do
        // executor só vem mais abaixo, depois do settle-de-falha: sem este guard o validador
        // conferiria o texto vazio do run relâmpago do bug #1. O portão é assíncrono e
        // best-effort — o que acontecer lá dentro não volta para este caminho, que é o do
        // settle da Issue (§3.6).
        if (!noop) {
            List<RoleTemplateService.Check> checks = resolveChecks(agent);
            semDerrubarOSettle(runContext, "planValidationGate", () ->
                    planValidationGate.handle(
                            new PlanValidationStore.Scope(runContext.companyId(),
                                    runContext.runId(), runContext.agentId(),
                                    runContext.issueId()),
                            reply, checks));
        }
    }

    /**
     * Os blocos de saída que os 4 gates leem, pelo NOME da cerca. Deliberadamente GROSSO: só
     * pergunta "existe cerca deste tipo?", nunca parseia o conteúdo — a gramática de dentro é de
     * cada scanner, e replicá-la aqui criaria duas versões para divergir. O que estes três
     * compartilham verbatim é o token da cerca, e é só disso que um aviso precisa.
     */
    private static final Map<String, Pattern> BLOCOS_DE_SAIDA = Map.of(
            "dirigir", cerca("dirigir"),
            "parecer", cerca("parecer"),
            "pedido-skill", cerca("pedido-skill"));

    private static Pattern cerca(String token) {
        // Coluna 0, como nos scanners: um exemplo indentado dentro do prompt não vira aviso fantasma.
        return Pattern.compile("(?m)^```" + Pattern.quote(token) + "[ \\t]*$");
    }

    /**
     * <b>Quebra o silêncio do bloco órfão</b> (B2 do endurecimento; memória
     * {@code gate-de-saida-so-dispara-com-issueid}).
     *
     * <p>Um run de wakeup SEM issue não é turno de conversa nem run de Issue: cai fora dos dois
     * ramos, os 4 gates são pulados, e um {@code ```dirigir```}/{@code ```parecer```}/
     * {@code ```pedido-skill```} emitido nele é descartado sem erro, sem aviso, sem rastro. O agente
     * exerce uma autoridade que de fato tem, e o ato evapora — o modo de falha mais caro de
     * diagnosticar, porque nada indica que houve perda.
     *
     * <p><b>Anti-silêncio, não roteamento</b> (ADR-0037 D1). Para onde mandar o bloco órfão é
     * pergunta de desenho — sob que autoridade, ancorado em qual issue, com qual precedente (a issue
     * órfã do board é absorvida pro arquiteto; um {@code parecer} não tem análogo) — e isso é grill,
     * não conserto de borda. Aqui fecha-se o pior: a perda deixa de ser muda.
     *
     * <p><b>Escopo deliberado:</b> só o run sem issue E sem turno. O turno do CEO também descarta
     * estes blocos, mas ali o canal de saída é outro (reply estruturado, com {@code proposta}
     * própria) e avisar em todo turno seria ruído — é caso distinto, não esquecimento.
     */
    private void avisarBlocosSemDestino(RunContext runContext, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        List<String> encontrados = BLOCOS_DE_SAIDA.entrySet().stream()
                .filter(bloco -> bloco.getValue().matcher(reply).find())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (encontrados.isEmpty()) {
            return;
        }
        try {
            store.recordStep(runContext.runId(), "BLOCO_DE_SAIDA_SEM_DESTINO",
                    "bloco(s) " + String.join(", ", encontrados) + " emitido(s) por um run de wakeup"
                            + " SEM issue — os gates de fim-de-run não rodam neste caminho e o(s)"
                            + " bloco(s) foram DESCARTADOS. Para que valham, acorde o agente sobre"
                            + " uma issue.");
        } catch (RuntimeException aoRegistrar) {
            log.error("run {} emitiu bloco(s) {} sem destino e o registro do aviso TAMBEM falhou ({})",
                    runContext.runId(), encontrados, aoRegistrar.toString());
        }
    }

    /**
     * A posse, consultada UMA vez para os 4 gates, com o descarte visível quando falta.
     *
     * <p>Toda a leitura é fail-soft pela mesma razão que o {@link #semDerrubarOSettle} existe: isto
     * roda ANTES do {@code settleAfterRun}, e uma exceção daqui pularia o settle e deixaria a Issue
     * impegável para sempre. Banco fora ⇒ sem posse confirmada ⇒ não age (e o run vai falhar no
     * settle logo adiante de qualquer jeito, agora por um caminho que não trava issue).
     */
    private boolean posseConfirmada(RunContext runContext) {
        boolean detem;
        try {
            detem = issues.runAindaDetemAIssue(runContext.companyId(), runContext.issueId(),
                    runContext.runId());
        } catch (RuntimeException aoConsultar) {
            log.warn("posse da issue {} nao pôde ser confirmada para o run {} ({}) — os gates de saída"
                            + " NÃO rodam: agir sem posse cria artefato que ninguém desfaz",
                    runContext.issueId(), runContext.runId(), aoConsultar.toString());
            return false;
        }
        if (detem) {
            return true;
        }
        try {
            store.recordStep(runContext.runId(), "GATE_DE_SAIDA_SEM_POSSE",
                    "os 4 gates de fim-de-run foram DESCARTADOS: este run não detém mais a issue "
                            + runContext.issueId() + " (liquidada ou repegada por outro run enquanto"
                            + " o adapter terminava). A saída do agente está no StepTrace deste run.");
        } catch (RuntimeException aoRegistrar) {
            log.error("run {} perdeu a posse da issue {} e o registro do descarte TAMBEM falhou ({})",
                    runContext.runId(), runContext.issueId(), aoRegistrar.toString());
        }
        return false;
    }

    /**
     * As CHECAGENS (texto) que o plano do agente deve satisfazer, resolvidas pela versão de molde PINADA
     * (Uso B / B-i, docs §2/§4.b): agente sem pin → vazio → o compositor omite a seção (gate = "tem
     * checks"; auto-escopa ao arquiteto sem hard-code). Hop inline como o drift-badge de
     * {@code AgentController}; a mecânica do jsonb fica no {@code RoleTemplateService} (fail-soft: pin
     * obsoleto resolve vazio, nunca derruba o run).
     */
    private List<RoleTemplateService.Check> resolveChecks(Agent agent) {
        UUID pinnedVersionId = agent.getPinnedTemplateVersionId();
        if (pinnedVersionId == null) {
            return List.of();
        }
        return roleTemplates.checksForVersion(pinnedVersionId);
    }

    /** O briefing quer só a PROSA das checagens; o validador (B-ii) quer o par id+texto. */
    private List<String> resolvePlanChecks(Agent agent) {
        return resolveChecks(agent).stream().map(RoleTemplateService.Check::text).toList();
    }

    private RunOutcome runToOutcome(RunContext runContext) {
        Agent agent = agents.findById(runContext.agentId()).orElse(null);
        if (agent == null) {
            return RunOutcome.failure("agente nao encontrado: " + runContext.agentId());
        }

        // ADR-0032 (D3/D4): o adapter resolve ANTES de qualquer preparo — a convenção de workspace
        // dele dirige os materializers, e tipo desconhecido falha AQUI, com a Issue ainda livre
        // (antes do checkout, nenhum lock preso por um run morto).
        AgentAdapter adapter = adapters.forType(agent.getAdapterType());
        WorkspaceConvention convention = adapter.workspaceConvention();

        // M4 (ADR-0031, D4): run de TURNO de uma persona de CEO usa o workspace COMPARTILHADO da
        // conversa (keyed por conversationId) — o bastão em .orq/conversa/ cruza a fronteira de
        // agente. O PEEK antecede o claim (que só ocorre após o preparo do workspace) e devolve a
        // MESMA conversa que o claim vai atender. Persona nula (CEO legado/agente comum) e run de
        // Issue seguem no chão por-agente de sempre.
        UUID conversaCompartilhada = runContext.issueId() == null
                && CeoPersona.fromDbValue(agent.getPersona()).isPresent()
                ? turnCoordinator.pendingConversationOf(runContext.agentId()).orElse(null)
                : null;

        // A seleção permanece antes do checkout: resolveForRun colapsa base∪anexos por skill com
        // ANEXO-VENCE-A-BASE. A materialização/publicação passa pela entrada única do compilador.
        Path workDir = conversaCompartilhada != null
                ? workspaces.resolveConversation(runContext.companyId(), conversaCompartilhada)
                : workspaces.resolve(runContext.companyId(), runContext.agentId(), runContext.issueId());
        List<CompanySkillVersion> skills;
        try {
            skills = distillationSkill.augment(
                    skillSets.resolveForRun(runContext.companyId(), runContext.agentId(), runContext.issueId()),
                    agent.getRole());
        } catch (RuntimeException exception) {
            return RunOutcome.failure("falha ao resolver o SkillSet: " + exception.getMessage());
        }

        // Amarração slice 1<->2<->3: o Run detem a Issue via checkout atomico com o proprio runId.
        // R22-A: a Issue do checkout é o INSUMO do compositor abaixo — quem instrui o run é ela.
        Issue issueDoRun = null;
        if (runContext.issueId() != null) {
            CheckoutResult checkout = issues.checkout(runContext.companyId(), runContext.issueId(), runContext.agentId(), runContext.runId());
            if (!checkout.acquired()) {
                return RunOutcome.failure("checkout da issue falhou (ja em execucao por outro run)");
            }
            issueDoRun = checkout.issue();
            // F.10 (ADR-0023 §3b): o run novo vai mutar o workspace — o preview vivo do estado
            // anterior morre AQUI (best-effort: o supervisor nunca lanca; o run segue).
            previewServers.teardownOnCheckout(runContext.companyId(), runContext.issueId());
        }

        ExecutionTarget target = targets.forType(ExecutionTargets.parse(agent.getExecutionTarget()));

        // T.3: Run sem Issue pode ser um TURNO de conversa (o claim decide). Com turno, o command
        // vira o prompt por-Run nos adapters ricos; o arquétipo process o recebe SÓ pelo env
        // (prosa em sh -c seria executada como shell). O claim vem DEPOIS do preparo de workspace:
        // uma falha de I/O acima não marca turno FAILED (o turno segue PENDING para o re-post).
        ConversationTurnCoordinator.TurnAssignment turno = runContext.issueId() == null
                ? turnCoordinator.claimFor(runContext.companyId(), runContext.agentId(), runContext.runId()).orElse(null)
                : null;
        // R22-A (ADR-0037 §2 emendada): a composição do prompt IÇADA acima do galho turno/issue —
        // o turno traz o prompt do coordinator; a ISSUE traz o briefing do compositor (a simetria
        // que faltava: antes o run de issue recebia só o runtime_command estático e o agente do
        // gate nascia mudo — bug #1). O prompt viaja pelos DOIS canais do seam T.3: vira o command
        // dos adapters ricos (acceptsPromptAsCommand) e chega pelo env ORQ_PROMPT no arquétipo
        // process. Decisão 2 do dono: runtime_command agora é SÓ comando de script do process —
        // adapter rico nunca mais o lê (agente de IA não precisa do campo; null do gate é correto).
        // R23-A: o prompt composto ENTRA no compilador como insumo e volta pelo pacote — daqui para
        // baixo quem carrega prompt/schema vivos é o ContextPackage (nada disso atravessa para o
        // manifesto persistido, e o hash do contexto não depende do prompt).
        MemoryAudience audience = turno != null ? MemoryAudience.CEO : MemoryAudience.EXECUTOR;
        String prompt = turno != null
                ? turno.prompt()
                : issueDoRun != null
                        ? issueComposer.compose(issueDoRun, target, adapter.engineContainer().image(),
                                resolvePlanChecks(agent))
                        : null;
        String outputSchema = turno != null ? turno.outputSchema() : null;
        String volumeName = conversaCompartilhada != null
                ? workspaces.conversationVolumeName(runContext.companyId(), conversaCompartilhada)
                : workspaces.volumeName(runContext.companyId(), runContext.agentId(), runContext.issueId());

        ContextPackage contextPackage;
        try {
            contextPackage = contextCompiler.compile(new RunContextCompiler.Request(
                    runContext, workDir, convention, skills, audience, target,
                    adapter.engineContainer().image(), volumeName, prompt, outputSchema));
        } catch (ContextCompilationException exception) {
            if (turno != null) {
                turnCoordinator.settleFailed(turno.turnId());
            }
            return RunOutcome.failure(exception.getMessage());
        }
        store.recordStep(runContext.runId(), "RUN_CONTEXT_COMPILED",
                "hash=" + shortHash(contextPackage.manifest().finalHash())
                        + " bytes=" + contextPackage.manifest().totalBytes()
                        + " sources=" + contextPackage.manifest().sources().size());
        // F-SAI1: a porta só agenda SHADOW (ou é NoOp em OFF). O adapter recebe o pacote canônico
        // original imediatamente; diagnóstico derivado nunca participa de prompt, arquivos ou settle.
        contextIntelligence.inspect(ContextIntelligence.View.from(contextPackage));

        String command = adapter.acceptsPromptAsCommand()
                ? contextPackage.prompt()
                : agent.getRuntimeCommand();
        Map<String, String> env = contextPackage.prompt() != null
                ? Map.of(PROMPT_ENV, contextPackage.prompt())
                : Map.of();
        // F-AT1 (grill agente-como-tool): SEMPRE coletado (não só em turno) — o Run de Issue não
        // tem saída "em forma de turno" (sem outputSchema), então o pedido-skill do executor é
        // lido do MESMO texto reconstruído que já serve o reply de turno e o report-por-Issue
        // (RunFinalOutput, a regra única do ADR-0010; ver IssueLedger). Custo é só memória.
        RunFinalOutput replyCollector = new RunFinalOutput();

        // F-N1/F-N2 (ADR-0038): a continuidade nativa liga AQUI — o handle gravado da conversa (turno)
        // ou da Issue (heartbeat) volta como priorSession, e o adapter religa o --resume no caminho
        // NORMAL. Era `null` literal (a regressão-mãe do laudo). Turno: o handle vem no TurnAssignment
        // (lido sob o claim). Issue: o conversation_id vem da própria Issue do checkout (V27) — só
        // quando ela tem conversa (absorvida); Issue sem conversa segue fresca (sem regressão).
        // Resolvido UMA vez (reusado no record pós-run, mais abaixo).
        UUID issueConversationId = turno == null && issueDoRun != null
                ? issueDoRun.getConversationId()
                : null;
        String priorSession = turno != null
                ? turno.priorSession()
                : issueConversationId != null
                        ? agentSessions.currentHandle(runContext.agentId(), issueConversationId).orElse(null)
                        : null;
        // Prompt e schema vivos vêm do pacote em memória; o manifesto nunca os persiste.
        // F-AT2 (D2/D6) + F-C3 (C10): o trilho MCP-stdio abre nas DUAS PONTAS em alvo LOCAL — o run de
        // EXECUTOR (turno==null && issueId!=null) e o TURNO DO CEO, que é o consumidor primário da
        // memória ("por que decidimos #7?"). Docker segue v1.1 (D7). O PERFIL diz quais tools cada
        // ponta enxerga: o corpo de skill do KnowAll continua EXCLUSIVO do executor (F-AT2/D6 —
        // "personas FORA"), a memória serve as duas. O baseUrl loopback vem do servidor web ligado;
        // ausente (WebEnvironment.MOCK, ou docker) ⟹ sem MCP e o run segue normal (o caminho pré-F-AT2).
        // O token efêmero liga o servidor ao bridge; o issueId é NULO na ponta do CEO (não há Issue).
        McpServerSpec mcpServer = null;
        String mcpToken = null;
        boolean pontaDoCeo = turno != null;
        boolean pontaDoExecutor = turno == null && runContext.issueId() != null;
        if ((pontaDoCeo || pontaDoExecutor)
                && target.type() == ExecutionTarget.Type.LOCAL && mcpAddress.baseUrl() != null) {
            try {
                mcpMaterializer.materialize(workDir);
            } catch (RuntimeException exception) {
                if (turno != null) {
                    turnCoordinator.settleFailed(turno.turnId());
                }
                return RunOutcome.failure("falha ao materializar o servidor MCP: " + exception.getMessage());
            }
            McpToolProfile profile = pontaDoCeo ? McpToolProfile.CEO : McpToolProfile.EXECUTOR;
            mcpToken = mcpBridge.open(new McpRunScope(runContext.companyId(), runContext.agentId(),
                    runContext.issueId(), runContext.runId(), profile));
            String scriptPath = workDir.resolve(KnowAllMcpMaterializer.SCRIPT_RELATIVE_PATH).toString();
            mcpServer = new McpServerSpec(KnowAllMcpMaterializer.SERVER_NAME, "node",
                    List.of(scriptPath, mcpAddress.baseUrl(), mcpToken, profile.argv()));
        }
        AgentAdapter.Context adapterContext = new AgentAdapter.Context(
                runContext.companyId(), runContext.agentId(), runContext.runId(), runContext.issueId(), workDir,
                command, env, target, volumeName, priorSession,
                agent.getModel(), agent.getEffort(), contextPackage.outputSchema(), mcpServer);

        AgentAdapter.Result result;
        try {
            result = adapter.execute(adapterContext, buildSink(runContext.runId(), replyCollector));
            // F-N2 (ADR-0038, D3): a sessão nativa a resumir sumiu (handle inválido/expirado) — fresh
            // start BARULHENTO, nunca silêncio: evento visível RUN_SESSION_LOST (espírito do
            // RUN_LEASE_LOST) + re-execução SEM priorSession. A verdade-fonte do executor é o WORKSPACE,
            // então re-derivar é o baseline barato. Um-shot: o fresh não resume ⟹ não re-cai aqui.
            if (result.sessionLost()) {
                store.recordStep(runContext.runId(), "RUN_SESSION_LOST",
                        "resume falhou (sessão nativa sumida) — reiniciando do workspace: " + result.error());
                if (replyCollector != null) {
                    replyCollector = new RunFinalOutput(); // a tentativa perdida não entra no reply
                }
                // O fresh start descarta o resume (priorSession=null) mas MANTÉM o schema — a saída
                // do turno re-derivado ainda tem de ser validada (F-N3).
                AgentAdapter.Context fresh = new AgentAdapter.Context(
                        runContext.companyId(), runContext.agentId(), runContext.runId(), runContext.issueId(),
                        workDir, command, env, target, volumeName, null, agent.getModel(), agent.getEffort(),
                        contextPackage.outputSchema(), mcpServer);
                result = adapter.execute(fresh, buildSink(runContext.runId(), replyCollector));
            }
        } catch (Exception exception) {
            if (turno != null) {
                turnCoordinator.settleFailed(turno.turnId());
            }
            return RunOutcome.failure("falha no adapter: " + exception.getMessage());
        } finally {
            // F-AT2: fecha a sessão MCP assim que o CLI (e o subprocesso MCP filho) terminou — o token
            // vira inválido, nenhum callback tardio é servido (leitura efêmera, D5). Idempotente/no-op
            // se nunca abriu (persona / docker / MOCK).
            mcpBridge.close(mcpToken);
        }

        // Motor (ADR-0014/0006, Q7): roteia os tokens REAIS emitidos pelo adapter para o ledger com
        // costCents=0 (na assinatura o hard-stop de dólares é inócuo). Sucesso E falha: tokens gastos
        // antes de terminar contam — instrumentam o handoff a 50-60% (ADR-0015). Se o adapter LANÇOU
        // (catch acima), não há Result nem emissões: um adapter que queira contabilizar deve devolver
        // Result.fail(error, costEvents), não lançar.
        recordCosts(runContext, result.costEvents());
        long actualInputTokens = result.costEvents().stream()
                .mapToLong(AgentAdapter.CostEmission::tokensInput)
                .sum();
        store.recordStep(runContext.runId(), "RUN_CONTEXT_USAGE",
                "estimated_tokens=" + contextPackage.manifest().estimatedTokens()
                        + " actual_input_tokens=" + actualInputTokens);

        // Settle do turno (T.3): a resposta capturada vira a thread; falha marca FAILED (o erro
        // detalhado vive no StepTrace). O handle de sessão é persistido por (Agent, Conversation)
        // — F-N1 (ADR-0038) o LÊ no início do próximo turno para religar o --resume nativo.
        if (turno != null) {
            if (result.succeeded()) {
                turnCoordinator.settleAnswered(turno.turnId(), replyCollector.text());
            } else {
                turnCoordinator.settleFailed(turno.turnId());
            }
            agentSessions.record(runContext.companyId(), runContext.agentId(), turno.conversationId(),
                    agent.getAdapterType(), result.session(), runContext.runId());
            // M8 (ADR-0030): pós-settle, o loop de handoff — a REDE de segurança de quando a janela
            // enche (rebaixado por F-N1; a continuidade nativa + dieta carregam a memória entre turnos).
            // Só em SUCESSO (um turno falho não tem estado coerente a continuar). Best-effort.
            if (result.succeeded()) {
                handoffLoop.afterCeoTurn(new ContextHandoffLoop.FinishedCeoTurn(
                        runContext.companyId(), runContext.agentId(), turno.conversationId(), runContext.runId(),
                        agent.getAdapterType(), agent.getModel(), agent.getEffort(), result.costEvents(),
                        adapter, target, workDir, volumeName));
            }
        } else if (issueConversationId != null) {
            // F-N2 (ADR-0038, D3): o executor GRAVA o handle escopado à Issue (via issue.conversation_id)
            // para o run N+1 do heartbeat resumir. Espelha o turno: Result.fail ⟹ session=null ⟹
            // record() no-op ⟹ o handle anterior sobrevive (mesma semântica de retry, task #3).
            agentSessions.record(runContext.companyId(), runContext.agentId(), issueConversationId,
                    agent.getAdapterType(), result.session(), runContext.runId());
        }

        // F-AT1 (grill agente-como-tool, D6): o EXECUTOR de uma Issue (turno==null) pede skill fora
        // do set pelo canal de SAÍDA — mesmo espírito do ```proposta``` do CEO, só que sobre o texto
        // cru (sem outputSchema neste caminho). Só em SUCESSO (espelha entregarPropostasAoGate).
        // O reply é colhido UMA vez: `replyCollector` é reatribuído no retry acima, então não serve
        // dentro de lambda — e os 4 consumidores liam exatamente o mesmo texto.
        if (pontaDoExecutor && result.succeeded()) {
            entregarSaidaAosGates(runContext, agent, replyCollector.text(), result.noop());
        } else if (turno == null && runContext.issueId() == null && result.succeeded()) {
            // O run que não é NENHUM dos dois: wakeup sem issue. Os gates não rodam — e até aqui um
            // bloco de autoridade emitido nele sumia sem deixar rastro.
            avisarBlocosSemDestino(runContext, replyCollector.text());
        }

        if (!result.succeeded()) {
            return RunOutcome.failure(result.error());
        }
        // R22-A (ADR-0011 emendada): o adapter sinalizou NO-OP (motor nunca invocado). Sobre uma
        // Issue isso não é sucesso — é o run relâmpago do DONE fantasma (bug #1): vira outcome
        // no-op LEGÍVEL e o settle estaciona a Issue em BLOCKED. Fora de Issue, o loop verde de
        // agente não configurado segue valendo (nada a mentir).
        if (result.noop() && runContext.issueId() != null) {
            return RunOutcome.noop("run no-op: o motor nunca foi invocado (agente '"
                    + agent.getAdapterType() + "' sem instrução executável) — a issue não fecha em"
                    + " cima de um run vazio");
        }
        return RunOutcome.success();
    }

    private static String shortHash(String hash) {
        return hash.substring(0, Math.min(12, hash.length()));
    }

    /** O sink de steps do run; embrulhado pelo coletor de reply quando o run é um turno de conversa. */
    private AgentAdapter.StepSink buildSink(UUID runId, RunFinalOutput replyCollector) {
        AgentAdapter.StepSink sink = (type, detail) -> store.recordStep(runId, type, detail);
        return replyCollector != null ? replyCollector.wrap(sink) : sink;
    }

    /** Cada emissão do adapter vira uma linha no ledger (append-only) com costCents=0 (assinatura). */
    private void recordCosts(RunContext runContext, List<AgentAdapter.CostEmission> emissions) {
        for (AgentAdapter.CostEmission emission : emissions) {
            budget.recordCost(runContext.companyId(), runContext.agentId(), runContext.runId(),
                    runContext.issueId(), emission.provider(), emission.model(), 0L,
                    emission.tokensInput(), emission.tokensOutput(), emission.offBook());
        }
    }
}
