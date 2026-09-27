/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The merge of a generated tool input schema into Spring AI's derived one (FR-018). It logs under
 * {@link AgenticMcpConfiguration}'s logger, which registers the merged schemas.
 */
final class InputSchemaMerge {

    private static final Logger log = LoggerFactory.getLogger(AgenticMcpConfiguration.class);

    private static final String K_SCHEMA = "$schema";
    private static final String K_TYPE = "type";
    private static final String K_PROPERTIES = "properties";
    private static final String K_REQUIRED = "required";

    private static final Set<String> NUMERIC_TYPES = Set.of("integer", "number");
    private static final Set<String> STRING_TYPES = Set.of("string");
    private static final Set<String> ARRAY_TYPES = Set.of("array");
    /** The constraint keywords merged from a tool specification, each with the derived types it applies to. */
    private static final Map<String, Set<String>> CONSTRAINT_KEYWORDS = Map.ofEntries(
            Map.entry("minimum", NUMERIC_TYPES),
            Map.entry("maximum", NUMERIC_TYPES),
            Map.entry("exclusiveMinimum", NUMERIC_TYPES),
            Map.entry("exclusiveMaximum", NUMERIC_TYPES),
            Map.entry("minLength", STRING_TYPES),
            Map.entry("maxLength", STRING_TYPES),
            Map.entry("pattern", STRING_TYPES),
            Map.entry("allOf", STRING_TYPES),
            Map.entry("minItems", ARRAY_TYPES),
            Map.entry("maxItems", ARRAY_TYPES));

    private static final ObjectMapper JSON = new ObjectMapper();

    private InputSchemaMerge() {
    }

    /**
     * Merges a tool specification's input schema into Spring AI's derived one (FR-018). The derived
     * schema is kept whole, including every property's {@code type}; each listed property gains the
     * listed constraint keywords that apply to its derived type, and its membership in
     * {@code required} follows the listing. A keyword that does not apply, or a listed property the
     * derived schema does not have, is left out with a WARNING.
     *
     * @return the merged schema, declaring JSON Schema 2020-12
     */
    static ObjectNode mergeInputSchema(String toolName, String derivedSchema, JsonNode generatedSchema) {
        ObjectNode derived;
        try {
            derived = (ObjectNode) JSON.readTree(derivedSchema);
        } catch (IOException e) {
            throw new UncheckedIOException("AI-ATLAS: Unreadable derived input schema of MCP tool '"
                    + toolName + "'", e);
        }
        JsonNode derivedProperties = derived.path(K_PROPERTIES);
        Set<String> required = new LinkedHashSet<>();
        derived.path(K_REQUIRED).forEach(name -> required.add(name.asText()));
        Set<String> generatedRequired = new LinkedHashSet<>();
        generatedSchema.path(K_REQUIRED).forEach(name -> generatedRequired.add(name.asText()));

        Iterator<Map.Entry<String, JsonNode>> generatedProperties = generatedSchema.path(K_PROPERTIES).fields();
        while (generatedProperties.hasNext()) {
            Map.Entry<String, JsonNode> generated = generatedProperties.next();
            String property = generated.getKey();
            if (!(derivedProperties.get(property) instanceof ObjectNode target)) {
                log.warn("AI-ATLAS: MCP tool '{}': property '{}' of {} is not in the derived input schema, so "
                        + "it is not added and its constraints are not published", toolName, property,
                        AgenticMcpConfiguration.TOOL_SPECIFICATIONS);
                continue;
            }
            Set<String> types = types(target.get(K_TYPE));
            Iterator<Map.Entry<String, JsonNode>> keywords = generated.getValue().fields();
            while (keywords.hasNext()) {
                Map.Entry<String, JsonNode> keyword = keywords.next();
                Set<String> appliesTo = CONSTRAINT_KEYWORDS.get(keyword.getKey());
                if (appliesTo == null) {
                    continue;
                }
                if (types.stream().anyMatch(appliesTo::contains)) {
                    target.set(keyword.getKey(), keyword.getValue().deepCopy());
                } else {
                    log.warn("AI-ATLAS: MCP tool '{}': constraint keyword '{}' on property '{}' does not apply "
                            + "to its derived type '{}', so it is left out of the input schema; Bean Validation "
                            + "still enforces it", toolName, keyword.getKey(), property, String.join(", ", types));
                }
            }
            if (generatedRequired.contains(property)) {
                required.add(property);
            } else {
                required.remove(property);
            }
        }

        ObjectNode merged = JSON.createObjectNode();
        merged.put(K_SCHEMA, AgenticMcpConfiguration.JSON_SCHEMA_2020_12);
        derived.fields().forEachRemaining(field -> {
            if (!K_SCHEMA.equals(field.getKey())) {
                merged.set(field.getKey(), field.getValue());
            }
        });
        ArrayNode requiredNode = merged.putArray(K_REQUIRED);
        required.forEach(requiredNode::add);
        return merged;
    }

    /** The derived {@code type}: one name, a list of names, or none. */
    private static Set<String> types(JsonNode type) {
        Set<String> types = new LinkedHashSet<>();
        if (type == null) {
            return types;
        }
        if (type.isArray()) {
            type.forEach(t -> types.add(t.asText()));
        } else {
            types.add(type.asText());
        }
        return types;
    }
}
