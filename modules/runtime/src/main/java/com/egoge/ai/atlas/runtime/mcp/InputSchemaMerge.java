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
    private static final String K_ALL_OF = "allOf";

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
            Map.entry(K_ALL_OF, STRING_TYPES),
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
                    mergeKeyword(toolName, property, target, keyword.getKey(), keyword.getValue());
                } else {
                    log.warn("AI-ATLAS: MCP tool '{}': constraint keyword '{}' on property '{}' does not apply "
                            + "to its {}, so it is left out of the input schema; Bean Validation still enforces "
                            + "it when method validation is present", toolName, keyword.getKey(), property,
                            types.isEmpty() ? "derived schema without a type"
                                    : "derived type '" + String.join(", ", types) + "'");
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

    /**
     * Adds one generated constraint keyword to a derived property without losing what the derived
     * property says: generated {@code allOf} sub-schemas are appended to a derived {@code allOf}; a
     * keyword the derived property already has with another value keeps the derived value, and the
     * generated one moves into an {@code allOf} entry, so both apply.
     */
    private static void mergeKeyword(String toolName, String property, ObjectNode target, String keyword,
                                     JsonNode value) {
        JsonNode existing = target.get(keyword);
        if (existing == null) {
            target.set(keyword, value.deepCopy());
        } else if (K_ALL_OF.equals(keyword)) {
            ArrayNode allOf = allOf(target);
            value.forEach(subschema -> allOf.add(subschema.deepCopy()));
        } else if (!sameValue(existing, value)) {
            allOf(target).addObject().set(keyword, value.deepCopy());
            log.debug("AI-ATLAS: MCP tool '{}': property '{}' already has '{}' {} in its derived schema; the "
                    + "generated {} is added under allOf, so both apply", toolName, property, keyword, existing,
                    value);
        }
    }

    /** The property's {@code allOf} array, created when absent. */
    private static ArrayNode allOf(ObjectNode target) {
        if (target.get(K_ALL_OF) instanceof ArrayNode allOf) {
            return allOf;
        }
        return target.putArray(K_ALL_OF);
    }

    /** Equal values, numbers compared by value ({@code 5} and {@code 5.0} are the same bound). */
    private static boolean sameValue(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
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
