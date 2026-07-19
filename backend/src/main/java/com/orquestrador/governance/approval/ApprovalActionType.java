package com.orquestrador.governance.approval;

/**
 * As ações que o CEO pode PROPOR no v1 (ADR-0018 — o APPROVE dispara a ação real com
 * ator=aprovador): contratar, criar Issue, acordar um agente, criar time, criar molde, pedir
 * skill. O vocabulário cresce por fatia — enum fechado é o deny-by-default. {@code CREATE_TEAM} é
 * o alargamento DELIBERADO da ADR-0037 (D7): time + arquiteto atômico. {@code CREATE_ROLE_TEMPLATE}
 * é o alargamento da R10-A (propose-then-commit de molde, ADR-0017 emendada): o CEO propõe um
 * preset de cargo GLOBAL e o dono aprova — gate por ALCANCE (mexer no catálogo global = gate). A
 * versão do molde nasce só no APPROVE (proposedBy=CEO / approvedBy=dono); enquanto PENDING nada
 * persiste (o gate É o teto, G5). Editar molde existente segue mão-soberana-direta (G6, v1.1 pro CEO).
 * {@code SKILL_INSTALL_REQUEST} é o alargamento da F-AT1 (grill agente-como-tool, ADR-0003
 * emendada): o EXECUTOR de uma Issue pede uma skill fora do seu set pelo canal de saída (bloco
 * {@code pedido-skill}); o APPROVE anexa via {@code SkillSetService.attachToIssue} (install-on-
 * attach reusado, ADR-0003) — 1º consumidor real da Approval-de-ação-de-agente (ADR-0006).
 * {@code GRADUATE_PRINCIPLE} é a 7ª ação (Frente C/F-C2, C6 — ADR-0020/D12 emendada): o ritual
 * de graduação É o gate — o CEO propõe a FRASE do princípio (C2: a entrada do Playbook é
 * AUTORADA) e o APPROVE fabrica a Decisão {@code grau=PRINCIPIO} pela fábrica única; herda
 * propose-then-commit, o grill do racional (D6), a fila e o lote (ADR-0035) de graça — o
 * "graduation-badge" com endpoint próprio foi DERRUBADO como módulo raso.
 * {@code DIRECT_ISSUE} é a 8ª ação (ADR-0047, D-DSG-3/8): o GESTOR dirige uma issue pro próprio
 * braço via bloco {@code ```dirigir```}. É a única ação com DOIS ciclos de vida, roteados pelo
 * {@code gestor_status} do braço: ESTÁGIO → PENDING via {@code propose()} normal (lote no gate);
 * PROMOVIDO → a Approval NASCE {@code APPROVED} ({@code decidedBy} = a própria gestora) — a
 * primeira da casa que não passa por {@code propose()}+{@code decide()} humano.
 */
public enum ApprovalActionType {
    HIRE_AGENT,
    CREATE_ISSUE,
    WAKEUP_AGENT,
    CREATE_TEAM,
    CREATE_ROLE_TEMPLATE,
    SKILL_INSTALL_REQUEST,
    GRADUATE_PRINCIPLE,
    DIRECT_ISSUE
}
