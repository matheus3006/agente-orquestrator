package com.orquestrador.org.roletemplate;

import com.orquestrador.common.error.ConflictException;
import com.orquestrador.common.error.NotFoundException;
import com.orquestrador.org.agent.Agent;
import com.orquestrador.org.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Molde (role-template) GLOBAL versionado (ADR-0017): espelha o savelog de {@code skill}/{@code skill_version}.
 * Cobre o contrato do módulo profundo {@link RoleTemplateService}: cria global+v1 · editar vira NOVA versão
 * (savelog: v1 intacta, {@code current}=v2, número por {@code max(version)+1}) · pin + {@code resolvePreset}
 * devolvem o SNAPSHOT certo · os dois tipos RESERVADOS (Revisão/QA, Suporte) são aceitos no enum.
 */
@SpringBootTest
@ActiveProfiles("test")
class RoleTemplateServiceTest {

    @Autowired
    private RoleTemplateService service;
    @Autowired
    private RoleTemplateRepository templates;
    @Autowired
    private RoleTemplateVersionRepository versions;
    @Autowired
    private AgentRepository agents;

    private String role() {
        return "arch-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void criaMoldeGlobalComVersao1EPromoveCurrent() {
        UUID board = UUID.randomUUID();
        RoleTemplateService.Preset preset = new RoleTemplateService.Preset(
                "claude-code", "opus", "ultracode", List.of("tdd", "codebase-design"), "PLANEJAMENTO_ARQUITETO");

        RoleTemplate template = service.createTemplate(
                TipoFuncao.PLANEJAMENTO_ARQUITETO, role(), preset, "molde inicial", board);

        assertThat(template.getCompanyId()).isNull(); // v1 é sempre GLOBAL (company_id NULL)
        assertThat(template.getCurrentVersionId()).isNotNull();
        RoleTemplateVersion v1 = versions.findById(template.getCurrentVersionId()).orElseThrow();
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getRoleTemplateId()).isEqualTo(template.getId());

        RoleTemplateService.Preset resolved = service.resolvePreset(template.getCurrentVersionId());
        assertThat(resolved.adapterType()).isEqualTo("claude-code");
        assertThat(resolved.model()).isEqualTo("opus");
        assertThat(resolved.effort()).isEqualTo("ultracode");
        assertThat(resolved.skillKeys()).containsExactly("tdd", "codebase-design");
        assertThat(resolved.reportsToDefault()).isEqualTo("PLANEJAMENTO_ARQUITETO");
    }

    @Test
    void editarMoldeCriaNovaVersaoSemMexerNaAnterior_currentApontaAUltima() {
        UUID board = UUID.randomUUID();
        RoleTemplateService.Preset v1Preset = new RoleTemplateService.Preset(
                "claude-code", "sonnet", "high", List.of("tdd"), "LIDERANCA");
        RoleTemplate template = service.createTemplate(TipoFuncao.EXECUCAO, role(), v1Preset, "v1", board);
        UUID v1Id = template.getCurrentVersionId();

        RoleTemplateService.Preset v2Preset = new RoleTemplateService.Preset(
                "codex", "gpt-5.5", "xhigh", List.of("tdd", "diagnose"), "LIDERANCA");
        RoleTemplateVersion v2 = service.newVersion(template.getId(), v2Preset, "manual", "trocou o motor", board);

        assertThat(v2.getVersion()).isEqualTo(2); // max(version)+1, não count
        // v1 intacta (savelog imutável)
        RoleTemplateVersion v1 = versions.findById(v1Id).orElseThrow();
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(service.resolvePreset(v1Id).model()).isEqualTo("sonnet");
        // current aponta à ÚLTIMA
        RoleTemplate reloaded = templates.findById(template.getId()).orElseThrow();
        assertThat(reloaded.getCurrentVersionId()).isEqualTo(v2.getId());
        assertThat(service.resolvePreset(v2.getId()).model()).isEqualTo("gpt-5.5");

        // uma 3ª edição continua max+1 (sequência sem count-drift)
        RoleTemplateVersion v3 = service.newVersion(template.getId(), v1Preset, "manual", "revert", board);
        assertThat(v3.getVersion()).isEqualTo(3);
    }

    @Test
    void savelogDevolveDescComSkillsetLegivel_semMisturarMoldes() {
        UUID board = UUID.randomUUID();
        RoleTemplate molde = service.createTemplate(TipoFuncao.EXECUCAO, role(),
                new RoleTemplateService.Preset("claude-code", "sonnet", "high", List.of("tdd"), "LIDERANCA"),
                "v1", board);
        service.newVersion(molde.getId(),
                new RoleTemplateService.Preset("codex", "gpt-5.5", "xhigh", List.of("tdd", "diagnose"), null),
                "manual", "v2", board);
        // outro molde no mesmo catálogo — o savelog de um NUNCA vaza no do outro
        RoleTemplate vizinho = service.createTemplate(TipoFuncao.EXECUCAO, role(),
                new RoleTemplateService.Preset("process", null, null, List.of(), null), "v1", board);

        List<RoleTemplateVersion> savelog = service.savelog(molde.getId());
        assertThat(savelog).extracting(RoleTemplateVersion::getVersion).containsExactly(2, 1);
        assertThat(savelog).allMatch(version -> molde.getId().equals(version.getRoleTemplateId()));
        // o skillset jsonb sai LEGÍVEL pelo seam do módulo (o cru nunca atravessa o controller)
        assertThat(service.skillKeys(savelog.get(0))).containsExactly("tdd", "diagnose");
        assertThat(service.skillKeys(savelog.get(1))).containsExactly("tdd");
        assertThat(service.savelog(vizinho.getId())).hasSize(1);

        assertThatThrownBy(() -> service.savelog(UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void pinForAgentGravaAVersaoPinada_reversivel() {
        UUID board = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        Agent agent = agents.save(new Agent(UUID.randomUUID(), companyId, "Lyra", "architect", null));

        RoleTemplate template = service.createTemplate(TipoFuncao.PLANEJAMENTO_ARQUITETO, role(),
                new RoleTemplateService.Preset("claude-code", "opus", "high", List.of(), null), "v1", board);
        UUID v1Id = template.getCurrentVersionId();
        RoleTemplateVersion v2 = service.newVersion(template.getId(),
                new RoleTemplateService.Preset("codex", "gpt-5.5", "high", List.of(), null), "manual", "v2", board);

        service.pinForAgent(companyId, agent.getId(), v1Id);
        assertThat(agents.findById(agent.getId()).orElseThrow().getPinnedTemplateVersionId()).isEqualTo(v1Id);

        // re-pin explícito para a v2 (reversível)
        service.pinForAgent(companyId, agent.getId(), v2.getId());
        assertThat(agents.findById(agent.getId()).orElseThrow().getPinnedTemplateVersionId()).isEqualTo(v2.getId());
    }

    @Test
    void duasRolesGlobaisIguaisLancaConflict_travaOPreCheckQueOH2NaoForca() {
        // O índice parcial (where company_id is null) é Postgres-only; no H2 (schema das entidades) a
        // ÚNICA linha de defesa é o pré-check do service. Este teste TRAVA esse guard: se um refactor o
        // remover, o H2 aceitaria a duplicata silenciosamente (false-green) enquanto o Postgres daria 500
        // em vez de 409 limpo. Lição da casa: o que a entidade não força, o teste força.
        UUID board = UUID.randomUUID();
        String sameRole = role();
        RoleTemplateService.Preset preset =
                new RoleTemplateService.Preset("process", null, null, List.of(), null);

        service.createTemplate(TipoFuncao.PLANEJAMENTO_ARQUITETO, sameRole, preset, "primeiro", board);
        assertThatThrownBy(() ->
                service.createTemplate(TipoFuncao.EXECUCAO, sameRole, preset, "duplicado", board))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void tiposReservadosSaoAceitosNoEnum() {
        UUID board = UUID.randomUUID();
        RoleTemplateService.Preset preset =
                new RoleTemplateService.Preset("process", null, null, List.of(), null);

        RoleTemplate qa = service.createTemplate(TipoFuncao.REVISAO_QA, role(), preset, "reservado", board);
        RoleTemplate suporte = service.createTemplate(TipoFuncao.SUPORTE, role(), preset, "reservado", board);

        assertThat(templates.findById(qa.getId()).orElseThrow().getTipoFuncao()).isEqualTo(TipoFuncao.REVISAO_QA);
        assertThat(templates.findById(suporte.getId()).orElseThrow().getTipoFuncao()).isEqualTo(TipoFuncao.SUPORTE);
    }

    @Test
    void checksDeUmaVersaoRoundTripPelaColuna_resolvidosComoListaDeChecks() {
        // `checks` é metadado de briefing POR-VERSÃO (Uso B, docs §2/§4.b) — FORA do Preset, escrito
        // direto na coluna jsonb nova. Round-trip SEMÂNTICO (o json do H2/pg pode reformatar o cru) pelo
        // seam do módulo: guarda a lista {id,text} e resolve de volta a MESMA lista. Prova a coluna nova
        // no H2 create-drop; o pg (jsonb real) é provado no /verify (Postgres, mesma coluna).
        UUID templateId = UUID.randomUUID();
        String checksJson = "[{\"id\":\"seam\",\"text\":\"aponta o encaixe/seam?\"},"
                + "{\"id\":\"file-line\",\"text\":\"cita arquivo:linha?\"}]";
        RoleTemplateVersion versao = versions.save(new RoleTemplateVersion(
                UUID.randomUUID(), templateId, 1, "claude-code", "opus", "ultracode",
                "[\"tdd\"]", "LIDERANCA", "inicial", "com checks", null, null, "APPROVED", checksJson));

        assertThat(service.checksForVersion(versao.getId())).containsExactly(
                new RoleTemplateService.Check("seam", "aponta o encaixe/seam?"),
                new RoleTemplateService.Check("file-line", "cita arquivo:linha?"));
    }

    @Test
    void versaoSemChecks_resolveListaVazia() {
        // Fail-soft: molde sem checks (todo papel que não o arquiteto; e o arquiteto ANTES do backfill)
        // resolve vazio — o gate do briefing ("tem checks?") auto-escopa sem hard-code de "arquiteto".
        UUID templateId = UUID.randomUUID();
        RoleTemplateVersion semChecks = versions.save(new RoleTemplateVersion(
                UUID.randomUUID(), templateId, 1, "claude-code", "sonnet", "high",
                "[\"tdd\"]", "LIDERANCA", "inicial", "sem checks", null, null, "APPROVED", null));

        assertThat(service.checksForVersion(semChecks.getId())).isEmpty();
    }

    @Test
    void versaoInexistente_resolveListaVazia_naoDerrubaOBriefing() {
        // Pin obsoleto (versão sumida) NÃO pode derrubar o run — mesmo fail-soft do drift-badge
        // (AgentController:132 faz .orElse(null)). Resolve vazio → briefing sem seção, run segue.
        assertThat(service.checksForVersion(UUID.randomUUID())).isEmpty();
    }

    @Test
    void versaoCriadaPeloService_nasceComChecksVazio() {
        // O Preset NÃO carrega checks (metadado de briefing, não snapshot de hire): toda versão criada
        // pelo service nasce com checklist vazio. As checks do arquiteto entram por SEED/backfill (V32),
        // nunca pelo createTemplate/newVersion — trava que o service não passe a "vazar" checks.
        UUID board = UUID.randomUUID();
        RoleTemplate molde = service.createTemplate(TipoFuncao.EXECUCAO, role(),
                new RoleTemplateService.Preset("claude-code", "sonnet", "high", List.of("tdd"), "LIDERANCA"),
                "v1", board);

        assertThat(service.checksForVersion(molde.getCurrentVersionId())).isEmpty();
    }
}
