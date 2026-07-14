package com.orquestrador.memory;

import com.orquestrador.identity.company.CompanyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A busca por trás das DUAS tools de memória (F-C3, C10/C10.b): {@code memory_search} (metadados,
 * livre) e {@code memory_fetch} (corpo, capado no bridge). O invariante que esta classe guarda é a
 * FRONTEIRA (C10.b): o trilho serve o DURÁVEL SELADO — princípios (qualquer tipo) + direções — e o
 * log CRU {@code OPERACIONAL}/{@code TECNICA} segue BOARD-ONLY, fora do alcance de qualquer agente.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemoryQueryTest {

    @Autowired
    private MemoryQuery memoryQuery;
    @Autowired
    private DecisionLog decisionLog;
    @Autowired
    private CompanyService companies;

    private UUID novaCompany() {
        return companies.create("C", "co-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID()).getId();
    }

    private Decision decisao(UUID companyId, String area, String oQueMudou, String racional) {
        return new Decision(UUID.randomUUID(), companyId, area, DecisionLog.tipoFor(area),
                DecisionOrigem.DONO_NO_GATE, oQueMudou, racional, RacionalTipo.LOGICO, false);
    }

    /**
     * A fronteira do C10.b, provada pelo caminho do ATAQUE: as 4 Decisões compartilham o mesmo termo
     * de busca — só as 2 do tier durável selado voltam. O racional OPERACIONAL/TECNICO é material do
     * dono (o {@code DecisionController} é board-only) e não vaza pro contexto de nenhum agente.
     */
    @Test
    void buscaSoAlcancaODuravelSelado_oLogCruSegueBoardOnly() {
        UUID companyId = novaCompany();
        // Direção do dono (tipo=DIRECAO, grau=DECISAO) — dentro (é o material da carta).
        Decision direcao = decisionLog.record(
                decisao(companyId, "direcao-produto", "cobrar por assento no plano lupa", "o CAC fecha"));
        // Princípio de área NÃO-direção (grau=PRINCIPIO, tipo=OPERACIONAL) — dentro pelo GRAU (C3:
        // o eixo do ALCANCE é ortogonal ao do ASSUNTO; um princípio técnico/operacional é princípio).
        Decision principio = decisionLog.record(
                decisao(companyId, "growth", "sempre validar canal com lupa antes de escalar", "3 casos")
                        .comGrau(DecisionGrau.PRINCIPIO));
        // O LOG CRU — board-only, jamais alcançável pelo trilho (o racional é do dono).
        Decision operacional = decisionLog.record(
                decisao(companyId, "backlog", "priorizar a lupa da landing", "RACIONAL_SIGILOSO_OPERACIONAL"));
        Decision tecnica = decisionLog.record(
                decisao(companyId, "arquitetura", "trocar a lupa do parser", "RACIONAL_SIGILOSO_TECNICO"));

        List<MemoryQuery.Metadados> achados = memoryQuery.search(companyId, "lupa", 20);

        assertThat(achados).extracting(MemoryQuery.Metadados::seq)
                .as("só o durável selado: a direção e o princípio")
                .containsExactlyInAnyOrder(direcao.getSeq(), principio.getSeq())
                .doesNotContain(operacional.getSeq(), tecnica.getSeq());
        assertThat(achados).allSatisfy(achado ->
                assertThat(achado.oQueMudou()).doesNotContain("RACIONAL_SIGILOSO"));
    }

    /**
     * O agente não busca por substring exata — ele pergunta em LINGUAGEM NATURAL. Achado do /verify
     * (F-C3, 2026-07-14): com casamento de frase inteira, o codex mandou "a decisao original de
     * cobranca por assento", não casou NADA ("a busca por texto não retornou índice direto") e caiu
     * de volta no princípio que já tinha no sempre-on — respondeu o #N errado. A busca casa por
     * TERMOS (quem casa mais termos vem antes), como a busca do KnowAll que o agente já usa.
     */
    @Test
    void buscaCasaPorTermos_naoPorFraseInteira() {
        UUID companyId = novaCompany();
        Decision original = decisionLog.record(
                decisao(companyId, "direcao-produto", "cobrar por assento no plano lupa", "o CAC fecha"));
        Decision outra = decisionLog.record(
                decisao(companyId, "direcao-produto", "suporte 24h para enterprise", "churn e resposta lenta"));

        List<MemoryQuery.Metadados> achados =
                memoryQuery.search(companyId, "qual foi a decisao original de cobranca por assento?", 20);

        assertThat(achados).isNotEmpty();
        assertThat(achados.get(0).seq())
                .as("quem casa mais termos ('cobrar/cobranca'… 'assento') vem primeiro")
                .isEqualTo(original.getSeq());
        assertThat(achados).extracting(MemoryQuery.Metadados::seq).doesNotContain(outra.getSeq());
    }

    /** Termos curtos e vazios não podem arrastar o log inteiro pra dentro do contexto do agente. */
    @Test
    void termosDeRuidoNaoCasamTudo() {
        UUID companyId = novaCompany();
        decisionLog.record(decisao(companyId, "direcao-produto", "cobrar por assento", "o CAC fecha"));

        assertThat(memoryQuery.search(companyId, "de a o e", 20)).isEmpty();
        assertThat(memoryQuery.search(companyId, "   ", 20)).isEmpty();
    }

    /** Company-scoping: o espaço de endereços #N é por company — a memória de uma nunca vaza na outra. */
    @Test
    void buscaNuncaAtravessaAFronteiraDaCompany() {
        UUID companyId = novaCompany();
        decisionLog.record(decisao(companyId, "direcao-produto", "cobrar por assento", "o CAC fecha"));

        assertThat(memoryQuery.search(novaCompany(), "assento", 20)).isEmpty();
    }

    /**
     * O CORPO (C10): é ele que o {@code memory_fetch} entrega sob o teto — o RACIONAL (a resposta do
     * "por que decidimos #7?"), a EVIDÊNCIA (a maiêutica/linhagem de graduação) e o CLUSTER (as irmãs
     * da mesma área, em METADADO — a vizinhança que faz o agente navegar sem gastar outro slot).
     */
    @Test
    void fetchEntregaRacionalEvidenciaLinhagemECluster() {
        UUID companyId = novaCompany();
        Decision antiga = decisionLog.record(
                decisao(companyId, "direcao-produto", "cobrar por uso", "o uso media valor"));
        Decision irma = decisionLog.record(
                decisao(companyId, "direcao-produto", "plano anual com desconto", "trava receita"));
        // A nova SUPERSEDE a antiga (D11) e carrega evidência maiêutica.
        Decision nova = decisionLog.record(
                decisao(companyId, "direcao-produto", "cobrar por assento", "o CAC fecha em 3 meses")
                        .superseding(antiga.getId())
                        .withEvidencia("o dono viu o churn cair quando o time inteiro entrou"));

        MemoryQuery.Corpo corpo = memoryQuery.fetch(companyId, nova.getSeq()).orElseThrow();

        assertThat(corpo.seq()).isEqualTo(nova.getSeq());
        assertThat(corpo.racional()).isEqualTo("o CAC fecha em 3 meses");
        assertThat(corpo.racionalTipo()).isEqualTo(RacionalTipo.LOGICO);
        assertThat(corpo.evidencia()).contains("churn cair");
        assertThat(corpo.vigente()).isTrue();
        assertThat(corpo.supersede()).as("a linhagem D11: esta substituiu a #" + antiga.getSeq())
                .isEqualTo(antiga.getSeq());
        assertThat(corpo.supersedidaPor()).isNull();
        assertThat(corpo.cluster()).extracting(MemoryQuery.Metadados::seq)
                .as("a vizinhança da área, sem a própria")
                .containsExactlyInAnyOrder(antiga.getSeq(), irma.getSeq());

        // A ponta velha sabe que foi substituída — e por qual #N (o log é a história, D11).
        MemoryQuery.Corpo corpoDaAntiga = memoryQuery.fetch(companyId, antiga.getSeq()).orElseThrow();
        assertThat(corpoDaAntiga.vigente()).isFalse();
        assertThat(corpoDaAntiga.supersedidaPor()).isEqualTo(nova.getSeq());
    }

    /**
     * A MESMA fronteira do C10.b na perna do fetch: um {@code #N} do log cru é INALCANÇÁVEL mesmo
     * com o endereço em mãos (o agente pode ter visto o "#12" numa citação e tentar a sorte). Vazio
     * aqui = o bridge devolve "fora do escopo" e NÃO consome o teto (nada entrou no contexto).
     */
    @Test
    void fetchDeEnderecoDoLogCruNaoAlcanca_nemComOEnderecoEmMaos() {
        UUID companyId = novaCompany();
        Decision operacional = decisionLog.record(
                decisao(companyId, "backlog", "priorizar a landing", "RACIONAL_SIGILOSO_OPERACIONAL"));
        Decision tecnica = decisionLog.record(
                decisao(companyId, "arquitetura", "trocar o parser", "RACIONAL_SIGILOSO_TECNICO"));

        assertThat(memoryQuery.fetch(companyId, operacional.getSeq())).isEmpty();
        assertThat(memoryQuery.fetch(companyId, tecnica.getSeq())).isEmpty();
    }

    /** Endereço inexistente e endereço de OUTRA company: vazio (o #N é company-scoped, C4). */
    @Test
    void fetchDeEnderecoInexistenteOuDeOutraCompanyNaoAlcanca() {
        UUID companyId = novaCompany();
        Decision direcao = decisionLog.record(
                decisao(companyId, "direcao-produto", "cobrar por assento", "o CAC fecha"));

        assertThat(memoryQuery.fetch(companyId, 999)).isEmpty();
        assertThat(memoryQuery.fetch(novaCompany(), direcao.getSeq())).isEmpty();
    }
}
