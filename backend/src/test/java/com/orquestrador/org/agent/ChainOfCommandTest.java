package com.orquestrador.org.agent;

import com.orquestrador.org.team.Team;
import com.orquestrador.org.team.TeamRepository;
import com.orquestrador.org.team.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * CHEFIA DERIVADA (ADR-0045, D-ORG-9): o {@code reports_to_id} deixou de ser fonte e virou ESPELHO
 * da árvore de times. Uma verdade, duas leituras — {@link ChainOfCommandService#chefeDe} deriva e
 * {@code rederivar*} escreve o espelho. As três regras cobertas aqui:
 * <ol>
 *   <li>agente comum → GESTOR do próprio time;</li>
 *   <li>o gestor de um time → gestor do time-PAI (senão reportaria a si mesmo — a única exceção);</li>
 *   <li>time sem gestor → SOBE procurando (pai, avô…); chegou na raiz sem achar → {@code null}
 *       (= raiz, onde moram CEO+personas).</li>
 * </ol>
 * O espelho é reescrito em TODA mutação estrutural: mover agente, trocar gestor, mover time.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChainOfCommandTest {

    @Autowired
    private ChainOfCommandService chain;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TeamService times;
    @Autowired
    private TeamRepository teams;

    private Agent novoAgente(UUID companyId, String nome) {
        return agents.save(new Agent(UUID.randomUUID(), companyId, nome, "executor", null));
    }

    /** Agente no time, com o gestor designado: o chefe é o gestor DO PRÓPRIO time. */
    @Test
    void chefeDeAgenteComum_ehOGestorDoProprioTime() {
        UUID companyId = UUID.randomUUID();
        Team marketing = times.create(companyId, "Marketing", "growth", null);
        Agent gestor = novoAgente(companyId, "Gestor de Marketing");
        Agent redator = novoAgente(companyId, "Redator");
        times.assignAgent(companyId, gestor.getId(), marketing.getId());
        times.assignAgent(companyId, redator.getId(), marketing.getId());
        times.setGestor(companyId, marketing.getId(), gestor.getId());

        assertThat(chain.chefeDe(agents.findById(redator.getId()).orElseThrow()))
                .isEqualTo(gestor.getId());
        // e o ESPELHO no agente foi reescrito (não é só cálculo em memória)
        assertThat(agents.findById(redator.getId()).orElseThrow().getReportsToId())
                .isEqualTo(gestor.getId());
    }

    /** A ÚNICA exceção: o gestor de um time não reporta a si mesmo — sobe pro gestor do time-PAI. */
    @Test
    void gestorDeUmTime_reportaAoGestorDoTimePai() {
        UUID companyId = UUID.randomUUID();
        Team growth = times.create(companyId, "Marketing", "growth", null);
        Agent gestorMkt = novoAgente(companyId, "Gestor de Marketing");
        times.assignAgent(companyId, gestorMkt.getId(), growth.getId());
        times.setGestor(companyId, growth.getId(), gestorMkt.getId());

        Team conteudo = times.create(companyId, "Marketing", "conteudo", growth.getId());
        Agent editorChefe = novoAgente(companyId, "Editor-chefe");
        times.assignAgent(companyId, editorChefe.getId(), conteudo.getId());
        times.setGestor(companyId, conteudo.getId(), editorChefe.getId());

        // o gestor do sub-time responde ao gestor do time-pai
        assertThat(chain.chefeDe(agents.findById(editorChefe.getId()).orElseThrow()))
                .isEqualTo(gestorMkt.getId());
        // e o gestor do time-raiz responde à RAIZ (null) — acima dele só o CEO
        assertThat(chain.chefeDe(agents.findById(gestorMkt.getId()).orElseThrow())).isNull();
    }

    /** Time sem gestor não deixa o agente órfão: a busca SOBE até achar; sem achar, raiz (null). */
    @Test
    void timeSemGestor_sobeAteAchar_eSemAcharViraRaiz() {
        UUID companyId = UUID.randomUUID();
        Team growth = times.create(companyId, "Marketing", "growth", null);
        Agent gestorMkt = novoAgente(companyId, "Gestor de Marketing");
        times.assignAgent(companyId, gestorMkt.getId(), growth.getId());
        times.setGestor(companyId, growth.getId(), gestorMkt.getId());

        // sub-time SEM gestor: o membro dele pula um degrau e responde ao gestor do avô
        Team conteudo = times.create(companyId, "Marketing", "conteudo", growth.getId());
        Agent redator = novoAgente(companyId, "Redator junior");
        times.assignAgent(companyId, redator.getId(), conteudo.getId());
        assertThat(chain.chefeDe(agents.findById(redator.getId()).orElseThrow()))
                .isEqualTo(gestorMkt.getId());

        // árvore inteira sem gestor nenhum: todo mundo pendura na raiz (o estado de uma company nova)
        Team semGestor = times.create(companyId, "Dev", "backend", null);
        Agent solto = novoAgente(companyId, "Dev");
        times.assignAgent(companyId, solto.getId(), semGestor.getId());
        assertThat(chain.chefeDe(agents.findById(solto.getId()).orElseThrow())).isNull();
    }

    /** CEO + personas moram na raiz (sem time): chefe nenhum — é o topo. */
    @Test
    void agenteSemTime_ehRaiz() {
        UUID companyId = UUID.randomUUID();
        Agent ceo = novoAgente(companyId, "CEO");

        assertThat(chain.chefeDe(agents.findById(ceo.getId()).orElseThrow())).isNull();
    }

    /** Mudou de time = mudou de chefe NUM ATO SÓ (D-ORG-9) — sem PATCH de chefia nenhum. */
    @Test
    void moverDeTime_rederivaOChefeNoMesmoAto() {
        UUID companyId = UUID.randomUUID();
        Team marketing = times.create(companyId, "Marketing", "growth", null);
        Team dev = times.create(companyId, "Dev", "backend", null);
        Agent gestorMkt = novoAgente(companyId, "Gestor de Marketing");
        Agent gestorDev = novoAgente(companyId, "Gestor de Dev");
        times.assignAgent(companyId, gestorMkt.getId(), marketing.getId());
        times.assignAgent(companyId, gestorDev.getId(), dev.getId());
        times.setGestor(companyId, marketing.getId(), gestorMkt.getId());
        times.setGestor(companyId, dev.getId(), gestorDev.getId());

        Agent andarilho = novoAgente(companyId, "Andarilho");
        times.assignAgent(companyId, andarilho.getId(), marketing.getId());
        assertThat(agents.findById(andarilho.getId()).orElseThrow().getReportsToId())
                .isEqualTo(gestorMkt.getId());

        times.assignAgent(companyId, andarilho.getId(), dev.getId());
        assertThat(agents.findById(andarilho.getId()).orElseThrow().getReportsToId())
                .isEqualTo(gestorDev.getId());

        // e sair para NENHUM time devolve o agente à raiz
        times.assignAgent(companyId, andarilho.getId(), null);
        assertThat(agents.findById(andarilho.getId()).orElseThrow().getReportsToId()).isNull();
    }

    /** Trocar o GESTOR do time reescreve o espelho de todos os membros — não só de quem entrou. */
    @Test
    void trocarOGestor_rederivaOsMembrosDoTime() {
        UUID companyId = UUID.randomUUID();
        Team dev = times.create(companyId, "Dev", "backend", null);
        Agent primeiro = novoAgente(companyId, "Gestor 1");
        Agent segundo = novoAgente(companyId, "Gestor 2");
        Agent membro = novoAgente(companyId, "Membro");
        times.assignAgent(companyId, primeiro.getId(), dev.getId());
        times.assignAgent(companyId, segundo.getId(), dev.getId());
        times.assignAgent(companyId, membro.getId(), dev.getId());

        times.setGestor(companyId, dev.getId(), primeiro.getId());
        assertThat(agents.findById(membro.getId()).orElseThrow().getReportsToId())
                .isEqualTo(primeiro.getId());

        times.setGestor(companyId, dev.getId(), segundo.getId());
        assertThat(agents.findById(membro.getId()).orElseThrow().getReportsToId())
                .isEqualTo(segundo.getId());
        // o gestor ANTIGO virou membro comum: agora ele responde ao novo gestor
        assertThat(agents.findById(primeiro.getId()).orElseThrow().getReportsToId())
                .isEqualTo(segundo.getId());
        // e limpar o posto devolve o time à busca-pra-cima (raiz, aqui)
        times.setGestor(companyId, dev.getId(), null);
        assertThat(agents.findById(membro.getId()).orElseThrow().getReportsToId()).isNull();
    }

    /** Mover um TIME re-deriva a SUBÁRVORE inteira: quem estava embaixo muda de chefe junto. */
    @Test
    void moverOTime_rederivaASubarvoreInteira() {
        UUID companyId = UUID.randomUUID();
        Team marketing = times.create(companyId, "Marketing", "growth", null);
        Team dev = times.create(companyId, "Dev", "backend", null);
        Agent gestorMkt = novoAgente(companyId, "Gestor de Marketing");
        Agent gestorDev = novoAgente(companyId, "Gestor de Dev");
        times.assignAgent(companyId, gestorMkt.getId(), marketing.getId());
        times.assignAgent(companyId, gestorDev.getId(), dev.getId());
        times.setGestor(companyId, marketing.getId(), gestorMkt.getId());
        times.setGestor(companyId, dev.getId(), gestorDev.getId());

        // sub-time SEM gestor pendurado em Marketing: o membro dele responde ao gestor de Marketing
        Team conteudo = times.create(companyId, "Marketing", "conteudo", marketing.getId());
        Agent redator = novoAgente(companyId, "Redator");
        times.assignAgent(companyId, redator.getId(), conteudo.getId());
        assertThat(agents.findById(redator.getId()).orElseThrow().getReportsToId())
                .isEqualTo(gestorMkt.getId());

        // rependura o SUB-TIME debaixo de Dev → o membro passa a responder ao gestor de Dev
        times.reparentTeam(companyId, conteudo.getId(), dev.getId());
        assertThat(agents.findById(redator.getId()).orElseThrow().getReportsToId())
                .isEqualTo(gestorDev.getId());
    }

    /** Dado pré-corrompido com ciclo na árvore não trava o walk (visited-set, como no guard antigo). */
    @Test
    void cicloPreexistenteNaArvore_naoTravaOWalk() {
        UUID companyId = UUID.randomUUID();
        Team a = times.create(companyId, "Dev", "a", null);
        Team b = times.create(companyId, "Dev", "b", a.getId());
        // ciclo A↔B plantado DIRETO no repo (o reparentTeam nunca produziria isso)
        Team recarregadoA = teams.findById(a.getId()).orElseThrow();
        recarregadoA.pendurarEm(b.getId());
        teams.save(recarregadoA);

        Agent preso = novoAgente(companyId, "Preso");
        times.assignAgent(companyId, preso.getId(), a.getId());

        // nenhum gestor em lugar nenhum + ciclo: o walk para no visited-set e devolve raiz
        assertThatCode(() -> chain.chefeDe(agents.findById(preso.getId()).orElseThrow()))
                .doesNotThrowAnyException();
        assertThat(chain.chefeDe(agents.findById(preso.getId()).orElseThrow())).isNull();
    }
}
