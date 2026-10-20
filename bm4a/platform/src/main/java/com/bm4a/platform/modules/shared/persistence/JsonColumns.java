package com.bm4a.platform.modules.shared.persistence;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts between {@code jsonb} columns (bound as text and cast in SQL with {@code CAST(:p AS jsonb)}) and Jackson
 * trees.
 */
@Component
public class JsonColumns {

    private final JsonMapper jsonMapper;

    public JsonColumns(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(JsonNode node) {
        return node == null ? null : jsonMapper.writeValueAsString(node);
    }

    public String writeValue(Object value) {
        return value == null ? null : jsonMapper.writeValueAsString(value);
    }

    public JsonNode read(String json) {
        return json == null ? null : jsonMapper.readTree(json);
    }

    public ObjectNode readObject(String json) {
        JsonNode node = read(json);
        return node instanceof ObjectNode object ? object : jsonMapper.createObjectNode();
    }

    public ObjectNode newObject() {
        return jsonMapper.createObjectNode();
    }

    public JsonMapper mapper() {
        return jsonMapper;
    }
}
