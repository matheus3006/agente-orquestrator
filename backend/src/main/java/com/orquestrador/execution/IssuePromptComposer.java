package com.orquestrador.execution;

import com.orquestrador.conversation.Conversation;
import com.orquestrador.conversation.ConversationRepository;
import com.orquestrador.execution.runtime.ExecutionTarget;
import com.orquestrador.execution.runtime.WorkspaceResolver;
import com.orquestrador.work.issue.Issue;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * O compositor do lado da ISSUE (R22-A, ADR-0037 §2 emendada) — o par simétrico do
 * {@code composePrompt} de turno ({@code ConversationTurnCoordinator}): <i>"a abertura de uma
 * issue nada mais é do que uma conversation, só que uma direcionada"</i> (verbatim do dono). Se a
 * issue é conversa direcionada, ela INSTRUI — antes deste compositor o run de issue recebia o
 * {@code runtime_command} estático e o agente contratado pelo gate nascia mudo (bug #1 do roadmap
 * de ajustes: run de 58ms, zero motor, DONE fantasma).
 *
 * <p>O briefing carrega a IDENTIDADE ({@code #N}, ADR-0033), título, descrição e a ESTRATÉGIA do
 * Planejador: a issue absorvida aponta a sua conversa (V27) e a conversa anota o endereço do
 * último bastão (V30, last-wins) — o arquivo mora no workspace DA CONVERSA, alcançado pelo mesmo
 * {@code readWorkspaceFile} provado no loop de handoff (LOCAL e DOCKER). Sem estratégia → só a
 * issue, nunca null; anotada-mas-ilegível → a lacuna é SINALIZADA no próprio briefing
 * (anti-silêncio), nunca engolida. O pedido de report explícito é ajuste de prompt, não
 * arquitetura: "report = saída final do run concluinte" (ADR-0010/0019) segue intacto.
 */
@Component
public class IssuePromptComposer {

    /** Teto de leitura da estratégia — espelho do teto do bilhete de handoff (ADR-0030). */
    static final long ESTRATEGIA_MAX_BYTES = 256L * 1024L;

    private final ConversationRepository conversations;
    private final WorkspaceResolver workspaces;

    public IssuePromptComposer(ConversationRepository conversations, WorkspaceResolver workspaces) {
        this.conversations = conversations;
        this.workspaces = workspaces;
    }

    /**
     * O briefing por-run da issue. {@code target}/{@code engineImage} são a plumbing do leitor de
     * workspace (a estratégia vive no workspace da CONVERSA — outro chão, mesmo seam do handoff).
     */
    public String compose(Issue issue, ExecutionTarget target, String engineImage) {
        StringBuilder prompt = new StringBuilder()
                .append("# Issue #").append(issue.getSeq()).append(" — ").append(issue.getTitle())
                .append("\n\n")
                .append("Você é o agente responsável por EXECUTAR esta issue agora. Ela é a sua"
                        + " instrução de trabalho (issue = conversa direcionada, ADR-0037); o seu"
                        + " papel diz quem você é, a issue diz o que fazer.\n\n")
                .append("## Descrição\n")
                .append(issue.getDescription() != null && !issue.getDescription().isBlank()
                        ? issue.getDescription()
                        : "(sem descrição — o título é a instrução)")
                .append("\n\n");
        appendEstrategia(prompt, issue, target, engineImage);
        prompt.append("## Report (a sua saída final)\n")
                .append("A sua SAÍDA FINAL é o report deste run: termine com um resumo curto do que"
                        + " fez, o que produziu no workspace e como verificar.\n");
        return prompt.toString();
    }

    /**
     * A ESTRATÉGIA de quem planejou chega em quem trabalha (decisão 3 do dono): issue → conversa
     * (V27) → endereço anotado do bastão (V30) → arquivo no workspace da conversa. Cada elo
     * ausente degrada para "só a issue"; elo anotado que NÃO abre vira sinal legível no briefing.
     */
    private void appendEstrategia(StringBuilder prompt, Issue issue, ExecutionTarget target,
                                  String engineImage) {
        if (issue.getConversationId() == null) {
            return; // issue sem conversa (não-absorvida): o trilho V27 honesto — só a issue
        }
        String bastaoPath = conversations.findById(issue.getConversationId())
                .map(Conversation::getBastaoPath)
                .orElse(null);
        if (bastaoPath == null || bastaoPath.isBlank()) {
            return; // conversa sem bastão anotado (nenhum handoff ainda): só a issue
        }
        Path conversaDir = workspaces.resolveConversation(issue.getCompanyId(), issue.getConversationId());
        String conversaVolume = workspaces.conversationVolumeName(issue.getCompanyId(), issue.getConversationId());
        ExecutionTarget.Spec spec = new ExecutionTarget.Spec(conversaDir, List.of(), Map.of(),
                engineImage, conversaVolume, List.of());
        ExecutionTarget.WorkspaceFileRead estrategia =
                target.readWorkspaceFile(spec, bastaoPath, ESTRATEGIA_MAX_BYTES);
        if (estrategia.found()) {
            prompt.append("## Estratégia (do Planejador — `").append(bastaoPath).append("`)\n")
                    .append(new String(estrategia.bytes(), StandardCharsets.UTF_8).strip())
                    .append("\n\n");
            return;
        }
        prompt.append("## Estratégia\n")
                .append("⚠️ A estratégia desta issue foi anotada em `").append(bastaoPath)
                .append("` mas não pôde ser lida")
                .append(estrategia.error() != null
                        ? " (" + estrategia.error() + ")"
                        : " (não encontrada no workspace da conversa)")
                .append(" — siga com a issue e SINALIZE esta lacuna no seu report.\n\n");
    }
}
