package com.orquestrador.conversation;

import com.orquestrador.conversation.persona.ConversationBaton;
import com.orquestrador.conversation.persona.PersonaBatonRouter;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.governance.approval.ApprovalService;
import com.orquestrador.governance.approval.ProposalScanner;
import com.orquestrador.handoff.HandoffNote;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * O seam do MOTOR para turnos de conversa (ADR-0018/A.8): o {@code AdapterRunExecutor} pergunta
 * "este Run atende um turno?" ({@link #claimFor}) e, se sim, recebe o turno + o PROMPT montado
 * por-Run (estado vivo + mensagem do dono — ADR-0004 emendado: o {@code command} deixa de ser o
 * {@code runtimeCommand} estático). No fim, {@link #settleAnswered}/{@link #settleFailed} liquidam
 * o turno e DRENAM a fila: se restam turnos PENDING do agente, um novo wakeup é enfileirado —
 * invariante 1 turno = 1 Run, mesmo sob coalescing (dois posts no mesmo run QUEUED).
 */
@Service
public class ConversationTurnCoordinator {

    private final ConversationTurnRepository turns;
    private final ConversationRepository conversations;
    private final LiveStateProjector liveState;
    private final WakeupService wakeups;
    private final ProposalScanner proposalScanner;
    private final ApprovalService approvals;
    private final AgentRepository agents;
    private final PersonaBatonRouter personaRouter;

    public ConversationTurnCoordinator(ConversationTurnRepository turns, ConversationRepository conversations,
                                       LiveStateProjector liveState, WakeupService wakeups,
                                       ProposalScanner proposalScanner, ApprovalService approvals,
                                       AgentRepository agents, PersonaBatonRouter personaRouter) {
        this.turns = turns;
        this.conversations = conversations;
        this.liveState = liveState;
        this.wakeups = wakeups;
        this.proposalScanner = proposalScanner;
        this.approvals = approvals;
        this.agents = agents;
        this.personaRouter = personaRouter;
    }

    /**
     * Claim guardado do turno mais antigo PENDING do agente (idempotente para o MESMO runId — o
     * reaper requeia o run com a mesma identidade). Sem turno → empty (o Run segue o caminho
     * clássico de Issue/runtimeCommand). Com turno → o prompt por-Run já composto.
     */
    @Transactional
    public Optional<TurnAssignment> claimFor(UUID companyId, UUID agentId, UUID runId) {
        if (turns.claimOldestPending(agentId, runId) == 0) {
            return Optional.empty();
        }
        ConversationTurn turno = turns.findFirstByRunIdAndStatusOrderByCreatedAtAsc(runId, TurnStatus.PENDING)
                .orElse(null);
        if (turno == null) {
            return Optional.empty();
        }
        String subject = conversations.findById(turno.getConversationId())
                .map(Conversation::getSubject)
                .orElse("(sem assunto)");
        // M7 (ADR-0031): o cabeçalho do turno é POR-PERSONA — o agente do turno decide o bloco;
        // persona nula (CEO legado / agente sem persona) mantém o prompt pós-G1/G2 intocado.
        CeoPersona persona = agents.findById(agentId)
                .flatMap(agent -> CeoPersona.fromDbValue(agent.getPersona()))
                .orElse(null);
        String prompt = composePrompt(persona, subject, liveState.project(companyId), turno.getOwnerMessage(),
                turno.getConversationId());
        return Optional.of(new TurnAssignment(turno.getId(), turno.getConversationId(), prompt));
    }

    /**
     * PEEK da conversa que o próximo claim deste agente vai atender (M4): o executor precisa do
     * conversationId ANTES do claim para resolver o workspace COMPARTILHADO da conversa — e o claim
     * pega o MESMO turno (mesmo critério de ordenação; 1 Run ativo por Agent serializa o meio).
     */
    @Transactional(readOnly = true)
    public Optional<UUID> pendingConversationOf(UUID agentId) {
        return turns.findFirstByAgentIdAndStatusOrderByCreatedAtAsc(agentId, TurnStatus.PENDING)
                .map(ConversationTurn::getConversationId);
    }

    @Transactional
    public void settleAnswered(UUID turnId, String reply) {
        ConversationTurn turno = turns.findById(turnId).orElseThrow();
        turno.answer(reply);
        turns.save(turno);
        // O canal de PROPOSTA é a SAÍDA do CEO (ADR-0018): os blocos ```proposta da resposta
        // viram Approval PENDING ancorada nesta conversa/run. Parse determinístico; malformado
        // é descartado (segue legível na thread para o dono cobrar).
        for (ProposalScanner.Proposal proposal : proposalScanner.scan(reply)) {
            approvals.propose(turno.getCompanyId(), turno.getAgentId(), turno.getConversationId(),
                    turno.getRunId(), proposal);
        }
        // ADR-0031 (M5/D6): a virada de persona é DETECÇÃO DE ENTREGÁVEL na mesma saída — o
        // scanner-irmão roteia ```handoff/```needs-grilling; o ```proposta acima segue pro T3.
        personaRouter.afterCeoTurnAnswered(turno, reply);
        drainQueue(turno);
    }

    @Transactional
    public void settleFailed(UUID turnId) {
        ConversationTurn turno = turns.findById(turnId).orElseThrow();
        turno.fail();
        turns.save(turno);
        drainQueue(turno); // um turno que falhou NÃO pode afogar os seguintes
    }

    /** Restam turnos PENDING do agente? Acorda de novo — o coalescing pode ter juntado N posts num Run só. */
    private void drainQueue(ConversationTurn settled) {
        if (turns.existsByAgentIdAndStatus(settled.getAgentId(), TurnStatus.PENDING)) {
            wakeups.enqueue(settled.getCompanyId(), settled.getAgentId(), null,
                    "conversation-drain:" + settled.getId());
        }
    }

    private static String composePrompt(CeoPersona persona, String subject, String estadoVivo,
                                        String ownerMessage, UUID conversationId) {
        StringBuilder prompt = new StringBuilder()
                .append("# Conversa com o dono — chat \"").append(subject).append("\"\n\n")
                .append(estadoVivo)
                .append("\n## Mensagem do dono\n").append(ownerMessage).append("\n\n")
                .append("## Instruções do turno\n");
        // O balcão único conversa (D2): as linhas G1 valem para o Questionador e para o CEO legado
        // (persona nula); Planejador/Executor são back-office — nunca recebem o dono, então o bloco
        // de conversa sai e entra o bloco do bastão (o adendo/design vence onde conflitaria).
        if (persona == null || persona == CeoPersona.QUESTIONADOR) {
            prompt.append(blocoConversaComODono());
        }
        if (persona != null) {
            prompt.append(switch (persona) {
                case QUESTIONADOR -> blocoQuestionador(conversationId);
                case PLANEJADOR -> blocoPlanejador(conversationId);
                case EXECUTOR -> blocoExecutor(conversationId);
            });
        }
        // D8 (ADR-0031): reset-de-contexto e virada-de-persona são gatilhos ORTOGONAIS — a regra de
        // ouro do bilhete vale POR PERSONA, em todo turno.
        prompt.append(blocoRegraDeOuroDoHandoff(HandoffNote.relativePath(conversationId)));
        return prompt.toString();
    }

    // G1 (grill 2026-07-09): "delegador puro" (ADR-0018) sempre foi sobre ENTREGÁVEIS;
    // generalizado à conversa, o modelo delegava até uma frase (run 043de730).
    private static String blocoConversaComODono() {
        return "- Você é o CEO. MANTENHA UMA CONVERSA com o dono: responda com a SUA voz, em texto"
                + " direto e curto; sua resposta vira a thread do chat.\n"
                + "- Pergunta trivial, de identidade ou de status: responda VOCÊ MESMO, imediatamente"
                + " — zero delegação, zero sub-agentes (nem os internos do seu CLI, ex.: collab/Task).\n"
                + "- Delegue (às áreas ou a sub-agentes) SOMENTE com trabalho real na mesa:"
                + " (A) criação de novo business, depois de grelhar meticulosamente com o dono;"
                + " (B) correção de task que o dono apontou — encaminhe as PALAVRAS DELE, sem"
                + " parafrasear; (C) contratação pedida explicitamente.\n"
                + "- \"Delegador puro\" (ADR-0018) vale para ENTREGÁVEIS: o conteúdo de um entregável"
                + " nunca é seu — mas a conversa é.\n";
    }

    /** O QUESTIONADOR (ADR-0031/D2/D5/D7 + A1/A3): grelha, apende o dossiê e comanda a virada. */
    private static String blocoQuestionador(UUID conversationId) {
        String dossie = ConversationBaton.dossiePath(conversationId);
        return "- Você é o CEO QUESTIONADOR — o balcão único: só VOCÊ fala com o dono; o Planejador"
                + " e o Executor são back-office e recebem trabalho por bastão.\n"
                + "- Dossiê do grill: APENDE a cada turno a pergunta+resposta em `" + dossie + "`"
                + " (crie na primeira; nunca reescreva o passado — preserve as palavras EXATAS do"
                + " dono). O dossiê é a sua memória entre turnos: releia-o antes de perguntar.\n"
                + "- Quando sentir que levantou o suficiente, pergunte ao dono: \"levantamos tudo?"
                + " posso seguir com a geração de estratégia?\" — e ESPERE. Nada de virada sem o sim.\n"
                + "- SÓ no turno seguinte ao \"sim\" do dono: carimbe o dossiê como pronto e emita o"
                + " bloco `handoff` para o `planejador`, com o bastão = o caminho do dossiê.\n"
                + "- Se a mensagem do turno começar com \"Re-grelha\", uma persona travou numa lacuna:"
                + " pergunte ao dono SÓ aquela lacuna, apende a resposta no dossiê e devolva o bastão"
                + " à persona de ORIGEM (o `para` do seu `handoff` é a origem indicada na mensagem).\n"
                + "- Antes de o dono ir ao menu de contratação: entregue NA CONVERSA o racional legível"
                + " da proposta (por que essas áreas/agentes, citando o dossiê).\n"
                + blocoBlocosTipados();
    }

    /** O PLANEJADOR (D12 + D7/D10): estratégia de ORQUESTRAÇÃO a partir do dossiê; calibração. */
    private static String blocoPlanejador(UUID conversationId) {
        String estrategia = ConversationBaton.estrategiaPath(conversationId);
        return "- Você é o CEO PLANEJADOR (back-office): você NUNCA fala com o dono — a \"Mensagem do"
                + " dono\" acima é um BASTÃO do fluxo de personas. Leia o entregável que ele aponta.\n"
                + "- Produza a ESTRATÉGIA DE ORQUESTRAÇÃO em `" + estrategia + "`: áreas, times e"
                + " agentes propostos — para CADA agente: role, skills, motor e o porquê (o racional"
                + " segue com o pedido, 1:1). Tetos: MAX 30 agentes, MAX 10 de profundidade de"
                + " pesquisa.\n"
                + "- Estratégia é ESCOPO de orquestração, não execução: quem quebra escopo→feature→"
                + "task é o arquiteto-de-domínio do time contratado (ADR-0017) — não desça a esse nível.\n"
                + "- Calibração de lacuna: PEQUENA (dá para assumir com segurança) → registre em"
                + " `## Premissas` na estratégia e siga. GRANDE (intenção do dono, você chutaria o"
                + " negócio errado) → emita `needs-grilling` e PARE o turno.\n"
                + "- Se a mensagem mandar \"prossiga com a melhor premissa\", NÃO re-emita"
                + " needs-grilling: escolha a melhor premissa, SINALIZE-a em `## Premissas (não"
                + " confirmadas)` e conclua a estratégia.\n"
                + "- Estratégia concluída → emita o bloco `handoff` para o `executor`, com o bastão ="
                + " o caminho da estratégia.\n"
                + blocoBlocosTipados();
    }

    /** O EXECUTOR (A2/A3 + D3/D10): protocola SEM MEXER; E→time é o gate T3; autonomia pós-approve. */
    private static String blocoExecutor(UUID conversationId) {
        return "- Você é o CEO EXECUTOR (back-office): você NUNCA fala com o dono — a \"Mensagem do"
                + " dono\" acima é um BASTÃO. Leia a estratégia que ele aponta.\n"
                + "- PROTOCOLE o pedido de contratação SEM MEXER (1:1 com a estratégia do Planejador):"
                + " para CADA agente proposto, emita um bloco `proposta` de HIRE_AGENT carregando"
                + " name, role, o motor sugerido (adapterType/model/effort) e o campo `racional`"
                + " estruturado (skills + motor + porquê) — é o que o dono lê no menu do gate.\n"
                + "  ```proposta\n"
                + "  {\"acao\": \"HIRE_AGENT\", \"alvo\": {\"name\": \"<nome>\", \"role\": \"<role>\","
                + " \"adapterType\": \"<motor>\", \"model\": \"<model>\", \"effort\": \"<effort>\","
                + " \"racional\": \"<skills + motor + porquê>\"}, \"resumo\": \"<uma linha>\","
                + " \"area\": \"<área>\"}\n"
                + "  ```\n"
                + "- Buraco na estratégia → emita `needs-grilling` (nunca conserto silencioso): quem"
                + " re-grelha o dono é o Questionador.\n"
                + "- Sua autonomia começa PÓS-approve: contratar, atribuir as skills a cada agente e"
                + " entregar os briefs aos times. Nada é contratado sem APPROVE do dono (gate T3).\n"
                + "- Pesquisa e apoio: use as ferramentas do PRÓPRIO run (web, sub-agentes) dentro do"
                + " turno — sem novos runs de produto.\n"
                + blocoBlocosTipados();
    }

    /**
     * O contrato dos blocos tipados INLINE (irmão do formato do bilhete, G2): nem todo motor
     * materializa skills — o formato canônico viaja no prompt. Exemplos INDENTADOS de propósito:
     * o roteador só lê cerca em COLUNA 0 (uma persona citando o formato não dispara a virada).
     */
    private static String blocoBlocosTipados() {
        return "- Blocos tipados (o contrato do roteador de personas; JSON numa cerca em coluna 0,"
                + " irmão do `proposta` — malformado é DESCARTADO em silêncio):\n"
                + "  ```handoff\n"
                + "  {\"para\": \"<persona-alvo: planejador, executor ou questionador>\","
                + " \"bastao\": \"<caminho do entregável no workspace>\", \"resumo\": \"<uma linha>\"}\n"
                + "  ```\n"
                + "  ```needs-grilling\n"
                + "  {\"lacuna\": \"<a pergunta concreta que só o dono responde>\"}\n"
                + "  ```\n";
    }

    // M5 (ADR-0030) — instrução ESTÁTICA de handoff: não depende do custo (que só existe
    // pós-run), então é dada em TODO turno. Se um turno-continuação for a leitura de "##
    // Mensagem do dono" (a sentinela), estas linhas reforçam retomar pelo mesmo bilhete.
    // G2 (grill 2026-07-09): o imperativo de gravar mora DENTRO da condição — turno
    // trivial NÃO grava bilhete (se o % cruzar sem bilhete, a rede pós-settle o garante).
    // O formato vai INLINE porque nem todo motor materializa a skill `distillation`.
    private static String blocoRegraDeOuroDoHandoff(String handoffNotePath) {
        return "- Regra de ouro do handoff (ADR-0015): SÓ SE a conversa aprofundou E a sua janela"
                + " de contexto está enchendo (~50-60%), destile e grave o bilhete de handoff em `"
                + handoffNotePath + "`; senão NÃO grave nada — nem crie o arquivo.\n"
                + "- Formato do bilhete (markdown curto, referências CONCRETAS — ids e arquivo:linha):"
                + " seções `Onde estou` · `O que já fiz` · `Próximo passo` · `Decisões travadas (com"
                + " racional)` · `Ponteiros (ids + arquivos)`; reescrever substitui o anterior. A"
                + " skill `distillation`, se disponível, detalha o contrato — apoio OPCIONAL, não"
                + " pré-requisito.\n"
                + "- Uma sessão NOVA retoma pelo bilhete em vez de recarregar a conversa inteira.\n";
    }

    /** O turno claimado por este Run + o prompt por-Run que vai ao CLI. */
    public record TurnAssignment(UUID turnId, UUID conversationId, String prompt) {
    }
}
