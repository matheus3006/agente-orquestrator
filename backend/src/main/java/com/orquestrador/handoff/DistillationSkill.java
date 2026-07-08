package com.orquestrador.handoff;

import com.orquestrador.skills.CompanySkillVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A skill de destilação (ADR-0030, D2/M4): a capacidade da PLATAFORMA de escrever o bilhete de
 * handoff. Não há tabela de "skill de sistema" nem semeadura no v1 — e não precisa: o
 * {@code SkillMaterializer} aceita qualquer {@link CompanySkillVersion} e REESCREVE
 * {@code .claude/skills} a cada Run. Então a realização ZERO-migração de D2 é injetar uma versão
 * SINTÉTICA (transiente, nunca persistida) no set do CEO a cada run — re-aplicada sempre, portanto
 * nunca "apagada na re-materialização"; invisível à biblioteca da company (correto: é capacidade da
 * plataforma, não skill que o dono gerencia). O CORPO É O CONTRATO do bilhete — o esqueleto do
 * {@code /nova-sessao} (Onde estou · O que fiz · Próximo passo · Decisões travadas · Ponteiros),
 * com refs CONCRETAS, não prosa (o default proposto no grill; veto humano no review).
 */
@Component
public class DistillationSkill {

    /** Nome da skill = chave do diretório materializado ({@code .claude/skills/distillation/SKILL.md}). */
    public static final String NAME = "distillation";

    private static final String CEO_ROLE = "ceo";

    // UUIDs fixos: identidade estável da skill sintética (desambiguação do materializer, sem DB).
    private static final UUID SYSTEM_VERSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000d158");
    private static final UUID SYSTEM_SKILL_ID = UUID.fromString("00000000-0000-0000-0000-00000000d157");

    private static final String BODY = """
            ---
            name: distillation
            description: Destila o estado de um turno num bilhete de handoff acionável — regra de ouro do handoff (ADR-0015/0030).
            ---

            # Distillation — o bilhete de handoff de contexto (ADR-0030)

            Quando a janela de contexto desta sessão se aproxima de ~50-60% (a regra de ouro,
            ADR-0015), NÃO empurre a sessão nem recarregue a conversa inteira. Em vez disso DESTILE:
            grave um bilhete curto e ACIONÁVEL no caminho que o turno indicou
            (`.orq/handoff/<conversationId>.md`) e encerre. Uma sessão NOVA retoma lendo o bilhete —
            fresca, só com o essencial.

            ## Quando usar
            - Ao sentir a janela enchendo no meio de um plano.
            - SEMPRE ao fim de um turno que ficou longo/profundo, antes de responder ao dono.

            ## O formato do bilhete (o CONTRATO — siga à risca)
            Markdown, com referências CONCRETAS (arquivos, ids, decisões) — nunca prosa vaga:

            ### Onde estou
            Uma frase: o estado vivo AGORA (o que está em andamento neste exato ponto).

            ### O que já fiz
            Bullets do que foi feito neste turno/plano — resultados concretos, não intenções.

            ### Próximo passo
            O PRIMEIRO passo que a sessão nova deve dar, sem ambiguidade.

            ### Decisões travadas (com racional)
            Cada decisão que NÃO deve ser reaberta + o PORQUÊ (o racional arrancado, ADR-0019/0020).

            ### Ponteiros (ids + arquivos)
            IDs (issue, agente, proposta…) e caminhos `arquivo:linha` — o mapa para retomar.

            ## Regras
            - Curto e denso: cabe numa olhada; não repita a conversa.
            - Concreto: `file:line`, ids, nomes — nunca "continuar o trabalho".
            - Idempotente: reescrever o bilhete substitui o anterior (a sessão nova lê o último).
            """;

    /**
     * Junta a skill de destilação ao set resolvido para um Run do CEO (role='ceo'); os demais
     * agentes seguem intactos. Devolve uma lista NOVA (não muta a de entrada).
     */
    public List<CompanySkillVersion> augment(List<CompanySkillVersion> resolved, String role) {
        if (!CEO_ROLE.equals(role)) {
            return resolved;
        }
        List<CompanySkillVersion> augmented = new ArrayList<>(resolved);
        augmented.add(version());
        return augmented;
    }

    /** A versão SINTÉTICA (transiente, nunca persistida) da skill de destilação. */
    public CompanySkillVersion version() {
        return new CompanySkillVersion(
                SYSTEM_VERSION_ID, null, SYSTEM_SKILL_ID, 1,
                "system-distillation-v1", NAME,
                "Destila o estado de um turno num bilhete de handoff acionável (ADR-0030).",
                null, BODY, "system (ADR-0030)", null);
    }
}
