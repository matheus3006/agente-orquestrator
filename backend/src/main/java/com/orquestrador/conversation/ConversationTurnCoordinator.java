package com.orquestrador.conversation;

import com.orquestrador.conversation.persona.ConversationBaton;
import com.orquestrador.conversation.persona.PersonaBatonRouter;
import com.orquestrador.execution.AgentSessionStore;
import com.orquestrador.execution.WakeupService;
import com.orquestrador.governance.approval.ApprovalService;
import com.orquestrador.governance.approval.ProposalScanner;
import com.orquestrador.handoff.HandoffNote;
import com.orquestrador.org.agent.AgentRepository;
import com.orquestrador.org.agent.CeoPersona;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnCoordinator.class);

    private final ConversationTurnRepository turns;
    private final ConversationRepository conversations;
    private final LiveStateProjector liveState;
    private final WakeupService wakeups;
    private final ProposalScanner proposalScanner;
    private final ApprovalService approvals;
    private final AgentRepository agents;
    private final PersonaBatonRouter personaRouter;
    private final ProposalDescarteNotice descarteNotice;
    private final AgentSessionStore agentSessions;
    private final TurnOutputSchema turnSchema;

    /**
     * F-N3 (ADR-0039, D2), ETAPA 2: a saída estruturada nativa dos turnos de CONVERSA (o
     * Questionador, cujo {@code resposta} é a fala ao dono). Back-office (Planejador/Executor) é
     * etapa 1 e NÃO depende deste flag — ligado sempre (sem prosa ao dono, risco baixo). Este flag
     * é o recuo informado do D2: se a prosa degradar ao VENDO, desliga (etapa 1 continua de pé).
     */
    private final boolean conversaEstruturadaNativa;

    public ConversationTurnCoordinator(ConversationTurnRepository turns, ConversationRepository conversations,
                                       LiveStateProjector liveState, WakeupService wakeups,
                                       ProposalScanner proposalScanner, ApprovalService approvals,
                                       AgentRepository agents, PersonaBatonRouter personaRouter,
                                       ProposalDescarteNotice descarteNotice, AgentSessionStore agentSessions,
                                       TurnOutputSchema turnSchema,
                                       @Value("${orquestrador.saida-estruturada.conversa-nativa:true}")
                                       boolean conversaEstruturadaNativa) {
        this.turns = turns;
        this.conversations = conversations;
        this.liveState = liveState;
        this.wakeups = wakeups;
        this.proposalScanner = proposalScanner;
        this.approvals = approvals;
        this.agents = agents;
        this.personaRouter = personaRouter;
        this.descarteNotice = descarteNotice;
        this.agentSessions = agentSessions;
        this.turnSchema = turnSchema;
        this.conversaEstruturadaNativa = conversaEstruturadaNativa;
    }

    /**
     * Esta persona emite a saída em schema nativo (F-N3/ADR-0039, D2)? Etapa 1: Planejador e Executor
     * (back-office, sempre). Etapa 2: Questionador (config {@code conversa-nativa}, VENDO-gated).
     * CEO legado (persona nula): NÃO — segue no regex (fora de escopo; o cinto de bloco fica).
     */
    private boolean usaSaidaEstruturada(CeoPersona persona) {
        if (persona == null) {
            return false;
        }
        return switch (persona) {
            case PLANEJADOR, EXECUTOR -> true;
            case QUESTIONADOR -> conversaEstruturadaNativa;
        };
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
        // F-N1 (ADR-0038, D1): a continuidade nativa por CONVERSA. O handle gravado no turno anterior
        // (chave (agent, conversation), ADR-0002) volta como priorSession — o executor liga o --resume
        // no caminho NORMAL (antes: null literal). Handle presente ⟹ turno-2+ numa sessão que já lembra:
        // o prompt entra em DIETA (o modelo não relê o que a sessão carrega). Conversa nova = sem handle.
        String priorSession = agentSessions.currentHandle(agentId, turno.getConversationId()).orElse(null);
        // F-N3 (ADR-0039, D2): a saída estruturada nativa. Persona migrada ⟹ o schema por-persona
        // (o motor prende {resposta, blocos[]} na fonte); persona não migrada ⟹ null (saída em prosa,
        // o caminho regex). O prompt sabe se é estruturado para instruir o formato certo.
        boolean estruturada = usaSaidaEstruturada(persona);
        String outputSchema = estruturada ? turnSchema.forPersona(persona) : null;
        String prompt = composePrompt(persona, subject, liveState.project(companyId), turno.getOwnerMessage(),
                turno.getConversationId(), priorSession != null, estruturada);
        return Optional.of(new TurnAssignment(turno.getId(), turno.getConversationId(), prompt, priorSession,
                outputSchema));
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
        CeoPersona persona = agents.findById(turno.getAgentId())
                .flatMap(agent -> CeoPersona.fromDbValue(agent.getPersona()))
                .orElse(null);
        // F-N3 (ADR-0039, D2): persona migrada ⟹ a saída é o JSON validado {resposta, blocos[]}. O
        // reply da thread vira o CAMPO `resposta` (mata o last-wins do reply codex) e os scanners
        // consomem `blocos[]` SEM regex — o caso C morre na fonte (o schema nem tem o bloco errado).
        if (usaSaidaEstruturada(persona)) {
            TurnOutputSchema.ParsedTurnOutput saida = turnSchema.parse(reply);
            if (!saida.malformado()) {
                turno.answer(saida.resposta());
                turns.save(turno);
                entregarPropostasAoGate(turno, proposalScanner.scanStructured(saida.blocos()));
                personaRouter.afterCeoTurnAnsweredStructured(turno, saida.blocos());
                drainQueue(turno);
                return;
            }
            // Malformado (schema ignorado pelo claude <2.1.205, ou motor cuspiu prosa): NUNCA
            // silêncio (D2) — WARN alto e CAI no caminho regex sobre o texto cru (o cinto que já
            // existe segue valendo; a remoção dos cintos é fatia de faxina própria, futura).
            log.warn("saída estruturada do turno {} veio malformada — fallback regex sobre o texto cru",
                    turnId);
        }
        turno.answer(reply);
        turns.save(turno);
        entregarPropostasAoGate(turno, proposalScanner.scan(reply));
        // ADR-0031 (M5/D6): a virada de persona é DETECÇÃO DE ENTREGÁVEL na mesma saída — o
        // scanner-irmão roteia ```handoff/```needs-grilling; o ```proposta acima segue pro T3.
        personaRouter.afterCeoTurnAnswered(turno, reply);
        drainQueue(turno);
    }

    /**
     * O canal de PROPOSTA é a SAÍDA do CEO (ADR-0018, emendada pela ADR-0036): blocos ```proposta
     * viram Approval PENDING ancorada na conversa/run. Cinto de emissor (espelho do ```correcao```):
     * só o EXECUTOR (protocolo 1:1 com a estratégia, A2) e o CEO legado (persona nula, caminho
     * pré-Fatia 2) propõem — Questionador/Planejador emitindo é proposta SEM estratégia. Todo
     * descarte (cinto ou shape fora do contrato) AVISA o dono na thread — anti-silêncio.
     */
    private void entregarPropostasAoGate(ConversationTurn turno, ProposalScanner.Scan colheita) {
        CeoPersona emissor = agents.findById(turno.getAgentId())
                .flatMap(agent -> CeoPersona.fromDbValue(agent.getPersona()))
                .orElse(null);
        boolean emissorAutorizado = emissor == null || emissor == CeoPersona.EXECUTOR;
        if (emissorAutorizado) {
            for (ProposalScanner.Proposal proposal : colheita.propostas()) {
                approvals.propose(turno.getCompanyId(), turno.getAgentId(), turno.getConversationId(),
                        turno.getRunId(), proposal);
            }
        } else if (!colheita.propostas().isEmpty()) {
            log.warn("proposta: persona '{}' emitiu {} bloco(s) ```proposta``` — só o Executor"
                    + " protocola o gate (descartado, fail-soft)", emissor.dbValue(),
                    colheita.propostas().size());
        }
        boolean emissorBarrado = !emissorAutorizado && !colheita.propostas().isEmpty();
        int descartadas = colheita.descartadas()
                + (emissorAutorizado ? 0 : colheita.propostas().size());
        if (descartadas > 0) {
            descarteNotice.post(turno, descartadas, emissorBarrado, emissor);
        }
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
                                        String ownerMessage, UUID conversationId, boolean resuming,
                                        boolean saidaEstruturada) {
        // F-N1 (ADR-0038, D1): DIETA na sessão continuada. Turno 2+ (resuming) roda numa sessão nativa
        // que JÁ carrega o protocolo desta persona (visto no turno 1) — re-injetar os blocos estáticos
        // paga contexto em dobro (a sessão lembra E o prompt repete). O estado vivo SEGUE (é dinâmico:
        // o board mudou), mas os ~75 linhas de instrução estática saem e entra um marcador curto. O
        // recorte fino do estado vivo (delta por linha) fica como otimização futura — a repetição
        // estática é o grosso do custo dobrado. Turno 1 (fresco) mantém o prompt inteiro.
        if (resuming) {
            return composePromptDietado(subject, estadoVivo, ownerMessage, saidaEstruturada);
        }
        StringBuilder prompt = new StringBuilder()
                .append("# Conversa com o dono — chat \"").append(subject).append("\"\n\n")
                .append(estadoVivo)
                .append("\n## Mensagem do dono\n").append(ownerMessage).append("\n\n")
                .append("## Instruções do turno\n");
        // F-N3 (ADR-0039, D2): quando a saída é estruturada nativa, o contrato do formato vem PRIMEIRO
        // e é autoritativo — remapeia toda "emita o bloco ```X```" das instruções abaixo para um item
        // de `blocos`, sem tocar na prosa SEMÂNTICA (afinada em muitos grills). O schema do motor valida.
        if (saidaEstruturada) {
            prompt.append(blocoContratoSaida(persona));
        }
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

    /**
     * O prompt DIETADO do turno continuado (F-N1/ADR-0038, D1): a sessão nativa já carrega o
     * protocolo desta persona (visto no turno 1) — re-enviá-lo seria o "contexto em dobro". Fica só
     * o que a sessão NÃO tem: o estado vivo ATUALIZADO (dinâmico) + a mensagem nova do dono + um
     * marcador curto de continuidade. Os blocos estáticos de persona e a regra-de-ouro do bilhete
     * saem daqui: a regra-de-ouro é reforçada pela REDE pós-settle (ADR-0030), não pela re-injeção.
     */
    private static String composePromptDietado(String subject, String estadoVivo, String ownerMessage,
                                               boolean saidaEstruturada) {
        // F-N3 (ADR-0039, D2): o schema é RE-APLICADO em todo turno (o argv sempre o carrega), então
        // a dieta reforça o contrato do formato numa linha — o resto do protocolo já vive na sessão.
        String contratoSaida = saidaEstruturada
                ? "- Formato da saída (o motor VALIDA): UM objeto JSON `{\"resposta\": \"...\","
                        + " \"blocos\": [...]}` — `resposta` é o seu texto, `blocos` as suas viradas"
                        + " tipadas (cada uma com `tipo`); nada de cercas ```.\n"
                : "";
        return "# Conversa com o dono — chat \"" + subject + "\" (sessão contínua)\n\n"
                + estadoVivo
                + "\n## Mensagem do dono\n" + ownerMessage + "\n\n"
                + "## Instruções do turno\n"
                + contratoSaida
                + "- Continue ESTA conversa na mesma linha do que já discutimos NESTA sessão: o"
                + " protocolo do seu papel e as instruções desta persona já estão aqui — use-os, não"
                + " os repita.\n"
                + "- Responda com a SUA voz, direto e curto. O \"Estado vivo\" acima é o board"
                + " ATUALIZADO (o que pode ter mudado desde o seu último turno) — não é uma conversa nova.\n";
    }

    /**
     * F-N3 (ADR-0039, D2): o contrato AUTORITATIVO da saída estruturada. Vem antes das instruções
     * de persona e as REMAPEIA: onde elas mandam "emita o bloco ```X```" (o mundo regex), aqui isso
     * é UM ITEM de {@code blocos} com {@code tipo:"X"}. A prosa semântica das personas fica intacta
     * (afinada em muitos grills) — só o FORMATO muda, e o motor o valida na fonte.
     */
    private static String blocoContratoSaida(CeoPersona persona) {
        String respostaEh = (persona == null || persona == CeoPersona.QUESTIONADOR)
                ? "a sua FALA AO DONO (direta e curta) — é o que vira a thread do chat"
                : "a sua narração back-office do turno (o registro do que você fez)";
        return "- Formato da saída (o motor VALIDA — obrigatório): sua saída é UM objeto JSON"
                + " `{\"resposta\": \"...\", \"blocos\": [...]}` e NADA além dele.\n"
                + "  - `resposta`: " + respostaEh + ".\n"
                + "  - `blocos`: a lista das suas viradas/entregáveis tipados (VAZIA `[]` se não"
                + " houver). Cada item tem um campo `tipo` e os campos daquele tipo.\n"
                + "  - As instruções abaixo mostram os blocos como cercas ```proposta / ```handoff /"
                + " etc.: isso é doc dos CAMPOS. Emita cada um como um ITEM de `blocos` com"
                + " `\"tipo\":\"<o nome da cerca>\"` — NUNCA escreva a cerca ``` nem repita o JSON na"
                + " `resposta`. A disciplina de coluna-0/indentação NÃO se aplica (não há cercas).\n";
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
                // Caso B (ADR-0034): correção de task ENTREGUE — payload mínimo deliberado (D1/D3):
                // as palavras do dono NUNCA viajam no bloco; o backend copia o turno byte a byte.
                // A prosa não nomeia destinatário (ADR-0036, achado do caso B: o opus chutou
                // "encaminho pro Feynman" enquanto o registro roteou, correto, à arquiteta).
                + "- Correção de task ENTREGUE (ex.: \"a task #12 ficou ruim — ...\"): responda"
                + " confirmando o encaminhamento — SEM nomear time/agente destinatário, quem roteia"
                + " é o sistema; cite só a #N — e emita o bloco `correcao` com SÓ o número da task"
                + " — NUNCA transcreva as palavras do dono (o sistema copia a mensagem do turno"
                + " byte a byte para a task corretiva). Emita o bloco no turno em que a reclamação"
                + " está COMPLETA; se veio espalhada em vários turnos, peça ao dono: \"me resume"
                + " numa mensagem só\" — e emita só no turno da mensagem consolidada.\n"
                + "  ```correcao\n"
                + "  {\"issue\": <o número da task, ex.: 12>}\n"
                + "  ```\n"
                // Caso C (ADR-0036): contratação pedida DIRETO ao balcão — o furo da Prova R3-A:
                // sem trilho, o motor inventou um shape de proposta que o gate descartou em silêncio.
                + "- Pedido de contratação EXPLÍCITO do dono (ex.: \"contrata um dev backend e um"
                + " designer\"): NUNCA emita bloco `proposta` — o protocolo do gate é papel do"
                + " Executor, depois da estratégia do Planejador. Registre o pedido no dossiê"
                + " (palavras EXATAS do dono) e emita o `handoff` para o `planejador` NO MESMO"
                + " turno: o pedido explícito JÁ É o \"sim\" — não re-pergunte \"posso seguir?\"."
                + " Só pergunte antes (UMA vez) se houver lacuna GRANDE: sem ela você chutaria o"
                + " negócio errado.\n"
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
                // ADR-0037 (M10): a triagem da absorção — o critério do D6 no prompt. O exemplo
                // viaja INDENTADO de propósito: coluna-0 é emissão, indentado é citação.
                + "- Absorção de issue do board: se a mensagem do turno é um gatilho \"[continuação"
                + " — absorção da issue #N …]\", trie a issue contra o \"### Equipe\" do estado"
                + " vivo e aponte o TIME num bloco `absorcao` (nunca uma pessoa — o sistema"
                + " resolve time→arquiteto e atribui). Cada time aparece lá como"
                + " `#### <dominio>/<foco>`: copie os DOIS valores EXATOS, separados, para os"
                + " campos do bloco (ex.: `#### Dev/Landing` → \"dominio\": \"Dev\", \"foco\":"
                + " \"Landing\"). Atribua quando um time EXISTENTE cobre o domínio da issue;"
                + " quando NENHUM cobre, emita o bloco com o time que você RECOMENDA criar"
                + " (dominio/foco novos) e, no racional, os cargos que ele precisa (ex.: \"um"
                + " arquiteto de aquisição + um executor de mídia paga\") — NUNCA atribua \"no"
                + " menos pior\"; na dúvida entre dois times plausíveis, emita `needs-grilling`"
                + " e PARE o turno.\n"
                + "  ```absorcao\n"
                + "  {\"issue\": <o número da issue, ex.: 7>, \"dominio\": \"<domínio do time>\","
                + " \"foco\": \"<foco do time>\", \"racional\": \"<por que esse time (ou os cargos"
                + " recomendados) — 1 linha>\"}\n"
                + "  ```\n"
                + blocoBlocosTipados();
    }

    /** O EXECUTOR (A2/A3 + D3/D10): protocola SEM MEXER; E→time é o gate T3; autonomia pós-approve. */
    private static String blocoExecutor(UUID conversationId) {
        return "- Você é o CEO EXECUTOR (back-office): você NUNCA fala com o dono — a \"Mensagem do"
                + " dono\" acima é um BASTÃO. Leia a estratégia que ele aponta.\n"
                + "- PROTOCOLE o pedido de contratação SEM MEXER (1:1 com a estratégia do Planejador):"
                + " para CADA agente proposto, emita um bloco `proposta` de HIRE_AGENT carregando"
                + " name, role, o motor sugerido (adapterType/model/effort) e o campo `racional`"
                + " estruturado (skills + motor + porquê) — é o que o dono lê no menu do gate."
                + " A cerca do bloco vai em COLUNA 0 (indentado é citação, não emissão).\n"
                + "  ```proposta\n"
                + "  {\"acao\": \"HIRE_AGENT\", \"alvo\": {\"name\": \"<nome>\", \"role\": \"<role>\","
                + " \"adapterType\": \"<motor>\", \"model\": \"<model>\", \"effort\": \"<effort>\","
                + " \"racional\": \"<skills + motor + porquê>\"}, \"resumo\": \"<uma linha>\","
                + " \"area\": \"<área>\"}\n"
                + "  ```\n"
                + "- Buraco na estratégia → emita `needs-grilling` (nunca conserto silencioso): quem"
                + " re-grelha o dono é o Questionador.\n"
                // Auto-kickback entre personas (rodada 9, D-motor-nativo §2): calibração inline,
                // igual à do needs-grilling do Planejador — needs-grilling é INTENÇÃO do dono;
                // retrabalho é DEFEITO no entregável de um par (nunca passa pelo dono).
                + "- Defeito no que você RECEBEU do Planejador (não é dúvida sobre o NEGÓCIO — isso é"
                + " `needs-grilling` — é ALGO ERRADO na estratégia: contradição interna, agente sem"
                + " role definido, etc.): emita `retrabalho` — devolve o bastão a quem entregou, SEM"
                + " passar pelo dono.\n"
                + "  ```retrabalho\n"
                + "  {\"lacuna\": \"<o que está errado ou faltando no entregável, 1 linha>\"}\n"
                + "  ```\n"
                // ADR-0037 (D7): o protocolo do time NOVO — time + arquiteto atômicos no gate.
                + "- Time NOVO autorizado pelo dono (ex.: absorção de issue sem time que cubra):"
                + " emita um bloco `proposta` de CREATE_TEAM carregando o time E o arquiteto JUNTOS"
                + " (todo time nasce com arquiteto); os executores vêm DEPOIS, como HIRE_AGENT"
                + " individuais.\n"
                + "  ```proposta\n"
                + "  {\"acao\": \"CREATE_TEAM\", \"alvo\": {\"dominio\": \"<domínio>\", \"foco\":"
                + " \"<foco>\", \"arquiteto\": {\"name\": \"<nome>\", \"role\": \"<role>\","
                + " \"adapterType\": \"<motor>\", \"model\": \"<model>\", \"effort\":"
                + " \"<effort>\"}, \"racional\": \"<por que esse time + arquiteto>\"}, \"resumo\":"
                + " \"<uma linha>\", \"area\": \"estrutura-org\"}\n"
                + "  ```\n"
                // R10-A (propose-then-commit de molde, ADR-0017 emendada): o CEO propõe um MOLDE
                // NOVO (preset de cargo reutilizável, GLOBAL) — SÓ criar; editar molde existente é
                // mão-soberana-direta do dono na tela, fora do gate (G6).
                + "- Molde (preset de cargo REUTILIZÁVEL) pedido pelo dono/estratégia: quando a"
                + " intenção é um PRESET reaproveitável (ex.: \"cria um molde de revisor-de-contratos"
                + " que eu reuso ao contratar\"), e NÃO um agente específico, emita um bloco"
                + " `proposta` de CREATE_ROLE_TEMPLATE. O molde é GLOBAL (vale para TODAS as suas"
                + " empresas) — por isso passa pelo gate. `tipoFuncao` é UM dos 6: LIDERANCA,"
                + " GESTAO_PO, PLANEJAMENTO_ARQUITETO, EXECUCAO, REVISAO_QA, SUPORTE. (Só CRIAR molde"
                + " novo; para EDITAR um molde que já existe, o dono usa a tela de moldes.)\n"
                + "  ```proposta\n"
                + "  {\"acao\": \"CREATE_ROLE_TEMPLATE\", \"alvo\": {\"tipoFuncao\": \"<um dos 6>\","
                + " \"roleEspecifica\": \"<nome da role, ex.: revisor-de-contratos>\","
                + " \"adapterType\": \"<motor>\", \"model\": \"<model>\", \"effort\": \"<effort>\","
                + " \"skillKeys\": [\"<skill>\"], \"reportsToDefault\": \"<tipo/role sugerido>\","
                + " \"changeDescription\": \"<o que é o molde>\", \"racional\": \"<por que esse"
                + " preset>\"}, \"resumo\": \"<uma linha>\", \"area\": \"estrutura-org\"}\n"
                + "  ```\n"
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

    /**
     * O turno claimado por este Run + o prompt por-Run que vai ao CLI + o handle de sessão a resumir
     * (F-N1/ADR-0038: null no turno 1 da conversa, o handle gravado do turno anterior a partir do 2)
     * + o schema de saída estruturada (F-N3/ADR-0039: null na persona não migrada — saída em prosa).
     */
    public record TurnAssignment(UUID turnId, UUID conversationId, String prompt, String priorSession,
                                 String outputSchema) {
    }
}
