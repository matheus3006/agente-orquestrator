package com.orquestrador.org.agent;

import com.orquestrador.common.security.ActorType;
import com.orquestrador.org.roletemplate.RoleTemplate;
import com.orquestrador.org.roletemplate.RoleTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Auto-seed das 3 personas de CEO na criação da company (ADR-0031, D9): as personas pré-existem
 * "como a Constituição institui os poderes antes do governo" — nascem do SISTEMA, não do gate.
 *
 * <p>Módulo profundo atrás de um seam de um método: reusa o hire da ADR-0017 (motor do preset +
 * copySkillSet + pin) e só então marca a {@code persona}. DIRIGIDO PELOS MOLDES e fail-soft por
 * persona: molde {@code ceo-<persona>} ausente no catálogo global (banco sem a V25 — ex.: o H2
 * dos testes, sem Flyway) é pulado em silêncio, espelhando o copySkillSet que pula skill_key
 * sem corpo (a autoria dos SKILL.md corre na faixa R1-D).
 */
@Service
public class CeoPersonaSeeder {

    private static final Logger log = LoggerFactory.getLogger(CeoPersonaSeeder.class);

    private final RoleTemplateRepository templates;
    private final AgentHireService hires;
    private final AgentRepository agents;

    public CeoPersonaSeeder(RoleTemplateRepository templates, AgentHireService hires, AgentRepository agents) {
        this.templates = templates;
        this.hires = hires;
        this.agents = agents;
    }

    @Transactional
    public void seedForNewCompany(UUID companyId, ActorType actorType, UUID actorId) {
        for (CeoPersona persona : CeoPersona.values()) {
            RoleTemplate molde = templates.findByCompanyIdIsNullAndRoleEspecifica(persona.moldeRole())
                    .orElse(null);
            if (molde == null || molde.getCurrentVersionId() == null) {
                log.debug("seed de persona pulado (molde global {} ausente)", persona.moldeRole());
                continue;
            }
            Agent hired = hires.hire(companyId, new AgentHireService.HireCommand(
                    persona.nomeSemeado(), "ceo", null, null, null, null, null, null,
                    molde.getCurrentVersionId(), null), actorType, actorId);
            hired.definePersona(persona.dbValue());
            agents.save(hired);
        }
    }
}
