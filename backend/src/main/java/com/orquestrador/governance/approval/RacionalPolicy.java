package com.orquestrador.governance.approval;

import com.orquestrador.memory.RacionalTipo;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * O regime do racional (ADR-0020, D6–D11) como MECANISMO, não flags soltas (ADR-0010):
 * <ul>
 *   <li><b>D9</b> — a classificação lógico|intuição é DERIVADA da ÁREA do artefato (área criativa
 *       → intuição), nunca de julgamento subjetivo;</li>
 *   <li><b>D6/D7</b> — presença obrigatória: vazio e "porque quis" (confabulação-nula) são
 *       grelhados nos DOIS tipos;</li>
 *   <li><b>D8</b> — LOGICO bloqueia até razão substantiva; INTUICAO commita com preferência
 *       legítima ("me agrada o tom" passa; "porque quis" não);</li>
 *   <li><b>D10</b> — o probe roda em ambos: no lógico é o bloqueio; na intuição é MAIÊUTICO e
 *       não-bloqueante (vira evidência da Decisão).</li>
 * </ul>
 * O CEO é escriba-que-insiste, nunca juiz de QUALIDADE: nada além de presença/anti-confabulação
 * é validado (regime de presença — buracos ficam auditáveis, não impedidos).
 */
@Component
public class RacionalPolicy {

    /** Marcadores de área criativa (D9): a comparação é sobre o texto normalizado sem acento. */
    private static final List<String> CREATIVE_AREA_MARKS = List.of(
            "visual", "criativo", "criativa", "design", "video", "imagem", "marca", "estetica", "copy");

    /** As formas de não-razão (D7): recusa de dizer, não razão nem preferência. */
    private static final List<String> CONFAB_FORMS = List.of(
            "porque quis", "porque sim", "porque eu quis", "sem razao", "sem motivo", "so porque sim");

    public String areaFor(ApprovalActionType actionType, String declaredArea) {
        if (declaredArea != null && !declaredArea.isBlank()) {
            return declaredArea.strip();
        }
        return switch (actionType) {
            case HIRE_AGENT, CREATE_TEAM, CREATE_ROLE_TEMPLATE -> "estrutura-org";
            case CREATE_ISSUE -> "backlog";
            case WAKEUP_AGENT -> "operacao";
            case SKILL_INSTALL_REQUEST -> "skills";
            // F-C2: o CEO declara a área graduada (o detector C5 é por-área); sem declaração,
            // um princípio governa casos futuros — cai na área de direção geral.
            case GRADUATE_PRINCIPLE -> "direcao-geral";
        };
    }

    public RacionalTipo deriveTipo(String area) {
        String normalized = normalize(area);
        return CREATIVE_AREA_MARKS.stream().anyMatch(normalized::contains)
                ? RacionalTipo.INTUICAO
                : RacionalTipo.LOGICO;
    }

    /** Presente = o commit está BLOQUEADO e este é o probe do grill; empty = commita. */
    public Optional<String> grillProbe(RacionalTipo tipo, String racional) {
        if (racional == null || racional.isBlank()) {
            return Optional.of(tipo == RacionalTipo.INTUICAO
                    ? "Registro obrigatório (ADR-0020): o que te agradou (ou incomodou) aqui?"
                    : "Registro obrigatório (ADR-0020): qual é a razão desta decisão?");
        }
        String normalized = normalize(racional);
        if (CONFAB_FORMS.stream().anyMatch(normalized::contains)) {
            return Optional.of("\"Porque quis\" não é razão nem intuição (ADR-0020/D7). "
                    + (tipo == RacionalTipo.INTUICAO
                    ? "Diga a preferência real: o que te agradou?"
                    : "Diga a razão substantiva: que premissa sustenta isso?"));
        }
        return Optional.empty(); // presença satisfeita — qualidade NUNCA é julgada (D6)
    }

    /** O probe maiêutico da intuição (D10) — registrado como evidência, nunca bloqueia. */
    public String maieuticProbe() {
        return "Probe maiêutico (ADR-0020/D10): o que exatamente te agrada aqui? "
                + "O que você notou que o agente não viu?";
    }

    /**
     * C14 (triviabilidade = silêncio-após-perguntado): a pergunta NÃO-bloqueante do approve na
     * review — "perguntar sempre, bloquear nunca" (D6, as duas metades). A política é dona das
     * PALAVRAS; a UI é dona do MOMENTO. No criativo é a própria maiêutica (D10); no não-criativo
     * pesca o "risco aceito-sem-mudar" (D5) — o registro que evapora primeiro por inércia.
     * Pulou a pergunta → sem Decisão (a regra mora na {@code GateDecisionFactory}).
     */
    public String reviewProbe(RacionalTipo tipo) {
        return tipo == RacionalTipo.INTUICAO
                ? maieuticProbe()
                : "Alguma ressalva ou risco que valha registrar? (não bloqueia o approve; "
                        + "sem resposta, nada vira memória)";
    }

    private static String normalize(String text) {
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
