package com.orquestrador.conversation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.orquestrador.org.agent.CeoPersona;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * O CONTRATO da saída estruturada nativa (F-N3/ADR-0039, D2 do grill motor-nativo): constrói o
 * JSON Schema que PRENDE a saída de um turno de persona a {@code {resposta, blocos[]}} e valida a
 * volta. Módulo profundo atrás de um seam pequeno (regra de ouro ADR-0010): as regras chatas do
 * strict do OpenAI (codex exige {@code additionalProperties:false} em TODO objeto + todo campo em
 * {@code required}) moram só aqui; o coordinator pede {@link #forPersona} e {@link #parse}, os
 * adapters só carregam a string ao CLI (claude {@code --json-schema} inline · codex
 * {@code --output-schema <arquivo>}).
 *
 * <p>Por que schema por-PERSONA e não um contrato único: a {@code blocos[]} de cada persona é o
 * {@code anyOf} SÓ dos blocos que ELA emite — então o caso C (ADR-0036: o Questionador inventou uma
 * {@code proposta}) vira ESTRUTURALMENTE IMPOSSÍVEL (o schema do Questionador nem contém
 * {@code proposta}). A mecânica das flags foi provada ao vivo nos 2 motores antes deste código.
 */
@Component
public class TurnOutputSchema {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * O schema do turno DESTA persona: {@code {resposta, blocos:[anyOf(blocos-da-persona)]}}. A
     * saída passa pela própria {@link #requireWellFormed} antes de sair — um bug do builder falha
     * ALTO aqui, nunca vira schema silenciosamente ignorado pelo claude &lt;2.1.205 (o pino é 2.1.201).
     */
    public String forPersona(CeoPersona persona) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.set("required", mapper.createArrayNode().add("resposta").add("blocos"));
        ObjectNode props = root.putObject("properties");
        props.putObject("resposta").put("type", "string");
        ObjectNode blocos = props.putObject("blocos");
        blocos.put("type", "array");
        blocos.set("items", itemsFor(persona));
        String json = root.toString();
        requireWellFormed(json);
        return json;
    }

    /** A união {@code anyOf} dos blocos que a persona emite (o roteamento restringe o resto). */
    private ObjectNode itemsFor(CeoPersona persona) {
        ArrayNode anyOf = mapper.createArrayNode();
        switch (persona) {
            case QUESTIONADOR -> {
                anyOf.add(handoffVariant());
                anyOf.add(needsGrillingVariant());
                anyOf.add(correcaoVariant());
            }
            case PLANEJADOR -> {
                anyOf.add(handoffVariant());
                anyOf.add(needsGrillingVariant());
                anyOf.add(absorcaoVariant());
            }
            case EXECUTOR -> {
                anyOf.add(propostaHireVariant());
                anyOf.add(propostaCreateTeamVariant());
                anyOf.add(propostaCreateRoleTemplateVariant());
                anyOf.add(needsGrillingVariant());
                anyOf.add(retrabalhoVariant());
            }
        }
        ObjectNode items = mapper.createObjectNode();
        items.set("anyOf", anyOf);
        return items;
    }

    private ObjectNode handoffVariant() {
        ObjectNode block = strictObject("handoff", "para", "bastao", "resumo");
        ObjectNode props = (ObjectNode) block.get("properties");
        putString(props, "para");
        putString(props, "bastao");
        putString(props, "resumo");
        return block;
    }

    private ObjectNode needsGrillingVariant() {
        ObjectNode block = strictObject("needs-grilling", "lacuna");
        putString((ObjectNode) block.get("properties"), "lacuna");
        return block;
    }

    private ObjectNode correcaoVariant() {
        ObjectNode block = strictObject("correcao", "issue");
        ((ObjectNode) block.get("properties")).putObject("issue").put("type", "integer");
        return block;
    }

    /**
     * Auto-kickback entre personas (rodada 9): só o Executor emite (é quem recebe entregável de
     * outra persona back-office); mesma forma do {@code needs-grilling} — o destino nunca é campo
     * do schema, o roteador o deriva do bastão que o turno está respondendo.
     */
    private ObjectNode retrabalhoVariant() {
        ObjectNode block = strictObject("retrabalho", "lacuna");
        putString((ObjectNode) block.get("properties"), "lacuna");
        return block;
    }

    private ObjectNode absorcaoVariant() {
        ObjectNode block = strictObject("absorcao", "issue", "dominio", "foco", "racional");
        ObjectNode props = (ObjectNode) block.get("properties");
        props.putObject("issue").put("type", "integer");
        putString(props, "dominio");
        putString(props, "foco");
        putString(props, "racional");
        return block;
    }

    private ObjectNode propostaHireVariant() {
        ObjectNode block = strictObject("proposta", "acao", "alvo", "resumo", "area");
        ObjectNode props = (ObjectNode) block.get("properties");
        putEnum(props, "acao", "HIRE_AGENT");
        props.set("alvo", strictObjectWithStrings("name", "role", "adapterType", "model", "effort", "racional"));
        putString(props, "resumo");
        putString(props, "area");
        return block;
    }

    /**
     * R10-A/D8 (propose-then-commit de molde, ADR-0017 emendada): o Executor propõe um MOLDE novo. O
     * alvo é o preset: identidade (tipoFuncao+roleEspecifica) + motor-trio + {@code skillKeys[]} +
     * reports_to sugerido + changeDescription + racional. {@code skillKeys} é o único campo ARRAY (os
     * outros são strings); só CRIAR (editar molde existente segue mão-soberana-direta, fora do schema).
     */
    private ObjectNode propostaCreateRoleTemplateVariant() {
        ObjectNode block = strictObject("proposta", "acao", "alvo", "resumo", "area");
        ObjectNode props = (ObjectNode) block.get("properties");
        putEnum(props, "acao", "CREATE_ROLE_TEMPLATE");
        ObjectNode alvo = strictObjectWithStrings("tipoFuncao", "roleEspecifica", "adapterType",
                "model", "effort", "reportsToDefault", "changeDescription", "racional");
        ((ArrayNode) alvo.get("required")).add("skillKeys");
        putStringArray((ObjectNode) alvo.get("properties"), "skillKeys");
        props.set("alvo", alvo);
        putString(props, "resumo");
        putString(props, "area");
        return block;
    }

    private ObjectNode propostaCreateTeamVariant() {
        ObjectNode block = strictObject("proposta", "acao", "alvo", "resumo", "area");
        ObjectNode props = (ObjectNode) block.get("properties");
        putEnum(props, "acao", "CREATE_TEAM");
        ObjectNode alvo = strictObjectWithStrings("dominio", "foco", "racional");
        ((ArrayNode) alvo.get("required")).add("arquiteto");
        ((ObjectNode) alvo.get("properties")).set("arquiteto",
                strictObjectWithStrings("name", "role", "adapterType", "model", "effort"));
        props.set("alvo", alvo);
        putString(props, "resumo");
        putString(props, "area");
        return block;
    }

    /**
     * Um objeto strict do OpenAI (codex exige): {@code additionalProperties:false} + TODO campo em
     * {@code required}. O discriminador {@code tipo} vira o 1º campo com {@code enum:[tipoValue]}.
     */
    private ObjectNode strictObject(String tipoValue, String... campos) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("type", "object");
        obj.put("additionalProperties", false);
        ArrayNode required = obj.putArray("required");
        required.add("tipo");
        obj.putObject("properties");
        putEnum((ObjectNode) obj.get("properties"), "tipo", tipoValue);
        for (String campo : campos) {
            required.add(campo);
        }
        return obj;
    }

    /** Um objeto strict cujos campos são TODOS strings obrigatórias (molde do {@code alvo}). */
    private ObjectNode strictObjectWithStrings(String... campos) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("type", "object");
        obj.put("additionalProperties", false);
        ArrayNode required = obj.putArray("required");
        ObjectNode props = obj.putObject("properties");
        for (String campo : campos) {
            required.add(campo);
            putString(props, campo);
        }
        return obj;
    }

    /** {@code "<campo>": {"type":"string"}} — o molde de campo mais comum. */
    private void putString(ObjectNode props, String campo) {
        props.putObject(campo).put("type", "string");
    }

    /** {@code "<campo>": {"type":"array","items":{"type":"string"}}} — lista de strings (skillKeys). */
    private void putStringArray(ObjectNode props, String campo) {
        ObjectNode array = props.putObject(campo);
        array.put("type", "array");
        array.putObject("items").put("type", "string");
    }

    /** {@code "<campo>": {"type":"string","enum":["<value>"]}} — discriminador const. */
    private void putEnum(ObjectNode props, String campo, String value) {
        props.putObject(campo).put("type", "string").set("enum", mapper.createArrayNode().add(value));
    }

    /**
     * O CINTO DA VOLTA (D2: "formato errado vira barulhento, nunca descarte silencioso"): lê a
     * saída estruturada {@code {resposta, blocos[]}} que o motor devolve (o campo {@code result}
     * do claude / o {@code agent_message} do codex, provado ao vivo). Com schema nativo isto NUNCA
     * deveria desviar do contrato — então quando desvia (JSON inválido, campo faltando: um schema
     * silenciosamente ignorado pelo claude &lt;2.1.205, ou motor sem schema), o resultado é MARCADO
     * {@code malformado} e a {@code resposta} cai no texto CRU (o dono ainda vê algo; o coordinator
     * avisa alto). Nunca lança: a saída do turno não pode sumir por um parse.
     */
    public ParsedTurnOutput parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return new ParsedTurnOutput("", List.of(), true);
        }
        JsonNode root;
        try {
            root = mapper.readTree(reply);
        } catch (Exception naoJson) {
            return new ParsedTurnOutput(reply, List.of(), true);
        }
        JsonNode resposta = root.path("resposta");
        JsonNode blocos = root.path("blocos");
        if (!root.isObject() || !resposta.isTextual() || !blocos.isArray()) {
            return new ParsedTurnOutput(reply, List.of(), true);
        }
        List<JsonNode> lista = new ArrayList<>();
        for (JsonNode bloco : blocos) {
            lista.add(bloco);
        }
        return new ParsedTurnOutput(resposta.asText(), List.copyOf(lista), false);
    }

    /**
     * A saída de turno já validada: a {@code resposta} (vira a thread do chat) + os {@code blocos}
     * (os scanners consomem, sem regex). {@code malformado} = a volta não bateu o contrato — o
     * coordinator trata alto (anti-silêncio do D2), a resposta traz o texto cru como salva-vidas.
     */
    public record ParsedTurnOutput(String resposta, List<JsonNode> blocos, boolean malformado) {
    }

    /**
     * O CINTO OBRIGATÓRIO antes de enviar (gotcha carimbado do D2): claude &lt;2.1.205 IGNORA um
     * schema inválido EM SILÊNCIO e o pino da imagem é 2.1.201 — então um schema mal-formado tem de
     * FALHAR ALTO aqui, no backend, nunca degradar calado no CLI. Checagem estrutural (o builder é
     * quem construímos): JSON válido + objeto strict com o contrato {@code {resposta, blocos[]}}.
     */
    public void requireWellFormed(String schema) {
        JsonNode root;
        try {
            root = mapper.readTree(schema);
        } catch (Exception naoJson) {
            throw new IllegalStateException("schema de turno não é JSON válido: " + naoJson.getMessage());
        }
        if (!root.isObject() || !"object".equals(root.path("type").asText())) {
            throw new IllegalStateException("schema de turno: raiz não é um objeto JSON Schema");
        }
        if (!root.path("additionalProperties").isBoolean() || root.path("additionalProperties").asBoolean()) {
            throw new IllegalStateException("schema de turno: raiz sem additionalProperties:false (strict do codex)");
        }
        JsonNode props = root.path("properties");
        if (!props.path("resposta").path("type").asText().equals("string")
                || !props.path("blocos").path("type").asText().equals("array")) {
            throw new IllegalStateException("schema de turno: contrato {resposta:string, blocos:array} ausente");
        }
    }
}
