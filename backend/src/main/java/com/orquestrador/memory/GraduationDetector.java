package com.orquestrador.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O detector de graduação (Frente C, C5): N={@value #GATILHO} decisões numa área DESDE o último
 * princípio daquela área = candidata a graduar. Função PURA sobre o log (área, ordem do #N) —
 * zero-LLM, idempotente, sem estado próprio: a tabela de linhagem pré-precificada morreu aqui
 * (a linhagem vai em {@code evidencia}, resolvível pelos #N). Roda de CARONA no commit do gate
 * (o consolidator o consulta ao projetar) e o resultado é só uma NUDGE-LINE na carta — quem
 * gradua é o dono, pelo gate (C6). Gatilho por "N usos" ficou pro v1.1.
 */
@Component
public class GraduationDetector {

    /** 2 é coincidência, 3 é tendência — o número provado da casa. */
    public static final int GATILHO = 3;

    /**
     * As áreas com >= {@value #GATILHO} decisões (grau=DECISAO) desde o último princípio da
     * área, com os {@code #N} acumulados — a linhagem pronta pro CEO citar. Espera o log em
     * ordem de {@code seq} ascendente; devolve áreas em ordem alfabética (determinística).
     */
    public List<Candidata> candidatas(List<Decision> logPorSeq) {
        Map<String, List<Integer>> desdeOUltimoPrincipio = new HashMap<>();
        for (Decision decision : logPorSeq) {
            if (decision.getGrau() == DecisionGrau.PRINCIPIO) {
                desdeOUltimoPrincipio.remove(decision.getArea()); // o "desde" recomeça aqui
            } else {
                desdeOUltimoPrincipio
                        .computeIfAbsent(decision.getArea(), area -> new ArrayList<>())
                        .add(decision.getSeq());
            }
        }
        return desdeOUltimoPrincipio.entrySet().stream()
                .filter(porArea -> porArea.getValue().size() >= GATILHO)
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(porArea -> new Candidata(porArea.getKey(), List.copyOf(porArea.getValue())))
                .toList();
    }

    /** Uma área madura pra graduar: os {@code seqs} são a linhagem que a proposta cita (C5). */
    public record Candidata(String area, List<Integer> seqs) {
    }
}
