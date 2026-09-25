/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Canonical JSON form of the {@link ContractIr} (FR-003, FR-004): a fixed key order in every
 * object, two-space indentation, {@code \n} line endings, a trailing newline, UTF-8, and every
 * key always present ({@code null} when absent), so equal documents are equal bytes.
 */
public final class IrJson {

    private static final String K_IR_VERSION = "irVersion";
    private static final String K_API_BASE_PATH = "apiBasePath";
    private static final String K_API_MAJOR = "apiMajor";
    private static final String K_ENTITIES = "entities";
    private static final String K_OPERATIONS = "operations";
    private static final String K_CLASS_NAME = "className";
    private static final String K_DTO_NAME = "dtoName";
    private static final String K_DTO_PACKAGE = "dtoPackage";
    private static final String K_DISPLAY_NAME = "displayName";
    private static final String K_DESCRIPTION = "description";
    private static final String K_INCLUDE_TYPE_INFO = "includeTypeInfo";
    private static final String K_FIELDS = "fields";
    private static final String K_NAME = "name";
    private static final String K_JAVA_TYPE = "javaType";
    private static final String K_COLLECTION_KIND = "collectionKind";
    private static final String K_ELEMENT_TYPE = "elementType";
    private static final String K_TYPE_HINT = "typeHint";
    private static final String K_REFERENCE = "reference";
    private static final String K_ENUM_TYPE = "enumType";
    private static final String K_ALLOWED_VALUES = "allowedValues";
    private static final String K_OPEN_ENUM = "openEnum";
    private static final String K_SENSITIVE = "sensitive";
    private static final String K_CHECK_CIRCULAR_REFERENCE = "checkCircularReference";
    private static final String K_LIFECYCLE = "lifecycle";
    private static final String K_SINCE_VERSION = "sinceVersion";
    private static final String K_REMOVED_IN_VERSION = "removedInVersion";
    private static final String K_DEPRECATED_SINCE_VERSION = "deprecatedSinceVersion";
    private static final String K_DEPRECATED_MESSAGE = "deprecatedMessage";
    private static final String K_ENTITY = "entity";
    private static final String K_DTO = "dto";
    private static final String K_ID = "id";
    private static final String K_SERVICE = "service";
    private static final String K_METHOD = "method";
    private static final String K_TOOL_NAME = "toolName";
    private static final String K_CHANNELS = "channels";
    private static final String K_REST = "rest";
    private static final String K_HTTP_METHOD = "httpMethod";
    private static final String K_PATH = "path";
    private static final String K_PARAMETERS = "parameters";
    private static final String K_ENUM_CONSTANTS = "enumConstants";
    private static final String K_RETURNS = "returns";
    private static final String K_RETURN_KIND = "returnKind";
    private static final String K_RETURN_TYPE = "returnType";
    private static final String K_API_SINCE = "apiSince";
    private static final String K_API_UNTIL = "apiUntil";
    private static final String K_API_DEPRECATED_SINCE = "apiDeprecatedSince";
    private static final String K_API_REPLACEMENT = "apiReplacement";

    private static final String INDENT = "  ";
    private static final char NEWLINE = '\n';

    private IrJson() {
    }

    /** A baseline that cannot be used: unreadable, not valid IR JSON, or from a newer ai-atlas. */
    public static final class IrReadException extends Exception {
        private static final long serialVersionUID = 1L;

        IrReadException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- writing

    /** Returns the canonical JSON text of {@code ir}. */
    public static String write(ContractIr ir) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(K_IR_VERSION, ir.irVersion());
        doc.put(K_API_BASE_PATH, ir.apiBasePath());
        doc.put(K_API_MAJOR, ir.apiMajor());
        doc.put(K_ENTITIES, ir.entities().stream().map(IrJson::entity).toList());
        doc.put(K_OPERATIONS, ir.operations().stream().map(IrJson::operation).toList());
        StringBuilder out = new StringBuilder();
        writeValue(out, doc, 0);
        return out.append(NEWLINE).toString();
    }

    /** Returns the canonical JSON of {@code ir} as UTF-8 bytes. */
    public static byte[] writeBytes(ContractIr ir) {
        return write(ir).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> entity(Entity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(K_CLASS_NAME, entity.className());
        map.put(K_DTO_NAME, entity.dtoName());
        map.put(K_DTO_PACKAGE, entity.dtoPackage());
        map.put(K_DISPLAY_NAME, entity.displayName());
        map.put(K_DESCRIPTION, entity.description());
        map.put(K_INCLUDE_TYPE_INFO, entity.includeTypeInfo());
        map.put(K_FIELDS, entity.fields().stream().map(IrJson::field).toList());
        return map;
    }

    private static Map<String, Object> field(Field field) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(K_NAME, field.name());
        map.put(K_DISPLAY_NAME, field.displayName());
        map.put(K_JAVA_TYPE, field.javaType());
        map.put(K_COLLECTION_KIND, field.collectionKind());
        map.put(K_ELEMENT_TYPE, field.elementType());
        map.put(K_TYPE_HINT, field.typeHint());
        map.put(K_REFERENCE, typeRef(field.reference()));
        map.put(K_ENUM_TYPE, field.enumType());
        map.put(K_ALLOWED_VALUES, field.allowedValues());
        map.put(K_OPEN_ENUM, field.openEnum());
        map.put(K_SENSITIVE, field.sensitive());
        map.put(K_CHECK_CIRCULAR_REFERENCE, field.checkCircularReference());
        map.put(K_DESCRIPTION, field.description());
        FieldLifecycle lifecycle = field.lifecycle();
        Map<String, Object> life = new LinkedHashMap<>();
        life.put(K_SINCE_VERSION, lifecycle.sinceVersion());
        life.put(K_REMOVED_IN_VERSION, lifecycle.removedInVersion());
        life.put(K_DEPRECATED_SINCE_VERSION, lifecycle.deprecatedSinceVersion());
        life.put(K_DEPRECATED_MESSAGE, lifecycle.deprecatedMessage());
        map.put(K_LIFECYCLE, life);
        return map;
    }

    private static Map<String, Object> typeRef(TypeRef ref) {
        if (ref == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(K_ENTITY, ref.entity());
        map.put(K_DTO, ref.dto());
        return map;
    }

    private static Map<String, Object> operation(Operation op) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(K_ID, op.id());
        map.put(K_SERVICE, op.service());
        map.put(K_METHOD, op.method());
        map.put(K_TOOL_NAME, op.toolName());
        map.put(K_CHANNELS, op.channels());
        map.put(K_DESCRIPTION, op.description());
        Map<String, Object> rest = null;
        if (op.rest() != null) {
            rest = new LinkedHashMap<>();
            rest.put(K_HTTP_METHOD, op.rest().httpMethod());
            rest.put(K_PATH, op.rest().path());
        }
        map.put(K_REST, rest);
        List<Map<String, Object>> params = new ArrayList<>();
        for (Parameter param : op.parameters()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(K_NAME, param.name());
            p.put(K_JAVA_TYPE, param.javaType());
            p.put(K_DESCRIPTION, param.description());
            p.put(K_ENUM_CONSTANTS, param.enumConstants());
            params.add(p);
        }
        map.put(K_PARAMETERS, params);
        Map<String, Object> returns = new LinkedHashMap<>();
        returns.put(K_JAVA_TYPE, op.returns().javaType());
        returns.put(K_RETURN_KIND, op.returns().returnKind());
        returns.put(K_RETURN_TYPE, op.returns().returnType());
        returns.put(K_REFERENCE, typeRef(op.returns().reference()));
        map.put(K_RETURNS, returns);
        OperationLifecycle lifecycle = op.lifecycle();
        Map<String, Object> life = new LinkedHashMap<>();
        life.put(K_API_SINCE, lifecycle.apiSince());
        life.put(K_API_UNTIL, lifecycle.apiUntil());
        life.put(K_API_DEPRECATED_SINCE, lifecycle.apiDeprecatedSince());
        life.put(K_API_REPLACEMENT, lifecycle.apiReplacement());
        map.put(K_LIFECYCLE, life);
        return map;
    }

    private static void writeValue(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Integer || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map, depth);
        } else if (value instanceof List<?> list) {
            writeArray(out, list, depth);
        } else {
            throw new IllegalArgumentException("Not a plain JSON value: " + value.getClass());
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{').append(NEWLINE);
        int i = 0;
        for (var entry : map.entrySet()) {
            indent(out, depth + 1);
            writeString(out, (String) entry.getKey());
            out.append(": ");
            writeValue(out, entry.getValue(), depth + 1);
            out.append(++i < map.size() ? "," : "").append(NEWLINE);
        }
        indent(out, depth);
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list, int depth) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[').append(NEWLINE);
        for (int i = 0; i < list.size(); i++) {
            indent(out, depth + 1);
            writeValue(out, list.get(i), depth + 1);
            out.append(i + 1 < list.size() ? "," : "").append(NEWLINE);
        }
        indent(out, depth);
        out.append(']');
    }

    private static void indent(StringBuilder out, int depth) {
        out.append(INDENT.repeat(depth));
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads a baseline file.
     *
     * @param file the baseline, named in error messages as given
     * @return the document, migrated in memory to {@link ContractIr#IR_VERSION}
     * @throws IrReadException if the file cannot be read, is not valid IR JSON, or carries an
     *                         {@code irVersion} above the supported one
     */
    public static ContractIr read(Path file) throws IrReadException {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw malformed(file.toString(), "cannot be read (" + e.getMessage() + ")");
        }
        return parse(content, file.toString());
    }

    /**
     * Parses IR JSON text.
     *
     * @param json   the document text
     * @param source names the document in error messages, e.g. the baseline path
     * @return the document, migrated in memory to {@link ContractIr#IR_VERSION}
     * @throws IrReadException if the text is not valid IR JSON or carries an {@code irVersion}
     *                         above the supported one
     */
    public static ContractIr parse(String json, String source) throws IrReadException {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw malformed(source, e.getOriginalMessage());
        }
        try {
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("the document is not a JSON object");
            }
            int irVersion = integer(root, K_IR_VERSION);
            if (irVersion > ContractIr.IR_VERSION) {
                throw new IrReadException("Contract baseline " + source + " has irVersion " + irVersion
                        + ", but this ai-atlas supports irVersion " + ContractIr.IR_VERSION
                        + " at most — the baseline was written by a newer ai-atlas. Upgrade ai-atlas,"
                        + " or re-accept the baseline with atlasAccept");
            }
            if (irVersion < 1) {
                throw new IllegalArgumentException("'" + K_IR_VERSION + "' must be at least 1, got "
                        + irVersion);
            }
            return new ContractIr(irVersion, string(root, K_API_BASE_PATH), integer(root, K_API_MAJOR),
                    list(root, K_ENTITIES, IrJson::readEntity), list(root, K_OPERATIONS, IrJson::readOperation));
        } catch (IllegalArgumentException e) {
            throw malformed(source, e.getMessage());
        }
    }

    private static IrReadException malformed(String source, String detail) {
        return new IrReadException("Contract baseline " + source + " is not valid Contract IR JSON: " + detail);
    }

    private static Entity readEntity(JsonNode node) {
        return new Entity(string(node, K_CLASS_NAME), string(node, K_DTO_NAME), string(node, K_DTO_PACKAGE),
                string(node, K_DISPLAY_NAME), string(node, K_DESCRIPTION), bool(node, K_INCLUDE_TYPE_INFO),
                list(node, K_FIELDS, IrJson::readField));
    }

    private static Field readField(JsonNode node) {
        JsonNode life = object(node, K_LIFECYCLE);
        return new Field(string(node, K_NAME), string(node, K_DISPLAY_NAME), string(node, K_JAVA_TYPE),
                string(node, K_COLLECTION_KIND), nullableString(node, K_ELEMENT_TYPE),
                nullableString(node, K_TYPE_HINT), readTypeRef(node), bool(node, K_ENUM_TYPE),
                strings(node, K_ALLOWED_VALUES), bool(node, K_OPEN_ENUM), bool(node, K_SENSITIVE),
                bool(node, K_CHECK_CIRCULAR_REFERENCE), string(node, K_DESCRIPTION),
                new FieldLifecycle(integer(life, K_SINCE_VERSION), integer(life, K_REMOVED_IN_VERSION),
                        integer(life, K_DEPRECATED_SINCE_VERSION), string(life, K_DEPRECATED_MESSAGE)));
    }

    private static TypeRef readTypeRef(JsonNode parent) {
        JsonNode ref = nullableObject(parent, K_REFERENCE);
        return ref == null ? null : new TypeRef(string(ref, K_ENTITY), string(ref, K_DTO));
    }

    private static Operation readOperation(JsonNode node) {
        JsonNode restNode = nullableObject(node, K_REST);
        Rest rest = restNode == null ? null
                : new Rest(string(restNode, K_HTTP_METHOD), string(restNode, K_PATH));
        JsonNode returns = object(node, K_RETURNS);
        JsonNode life = object(node, K_LIFECYCLE);
        return new Operation(string(node, K_SERVICE), string(node, K_METHOD), string(node, K_TOOL_NAME),
                strings(node, K_CHANNELS), string(node, K_DESCRIPTION), rest,
                list(node, K_PARAMETERS, p -> new Parameter(string(p, K_NAME), string(p, K_JAVA_TYPE),
                        string(p, K_DESCRIPTION), strings(p, K_ENUM_CONSTANTS))),
                new Return(string(returns, K_JAVA_TYPE), string(returns, K_RETURN_KIND),
                        nullableString(returns, K_RETURN_TYPE), readTypeRef(returns)),
                new OperationLifecycle(integer(life, K_API_SINCE), integer(life, K_API_UNTIL),
                        integer(life, K_API_DEPRECATED_SINCE), string(life, K_API_REPLACEMENT)));
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return value;
    }

    private static String string(JsonNode node, String key) {
        JsonNode value = required(node, key);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("'" + key + "' must be a string");
        }
        return value.textValue();
    }

    private static String nullableString(JsonNode node, String key) {
        return required(node, key).isNull() ? null : string(node, key);
    }

    private static int integer(JsonNode node, String key) {
        JsonNode value = required(node, key);
        if (!value.isInt()) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String key) {
        JsonNode value = required(node, key);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("'" + key + "' must be a boolean");
        }
        return value.booleanValue();
    }

    private static JsonNode object(JsonNode node, String key) {
        JsonNode value = required(node, key);
        if (!value.isObject()) {
            throw new IllegalArgumentException("'" + key + "' must be an object");
        }
        return value;
    }

    private static JsonNode nullableObject(JsonNode node, String key) {
        return required(node, key).isNull() ? null : object(node, key);
    }

    private static <T> List<T> list(JsonNode node, String key, Function<JsonNode, T> reader) {
        JsonNode value = required(node, key);
        if (!value.isArray()) {
            throw new IllegalArgumentException("'" + key + "' must be an array");
        }
        List<T> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new IllegalArgumentException("every item of '" + key + "' must be an object");
            }
            result.add(reader.apply(item));
        }
        return result;
    }

    private static List<String> strings(JsonNode node, String key) {
        JsonNode value = required(node, key);
        if (!value.isArray()) {
            throw new IllegalArgumentException("'" + key + "' must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("every item of '" + key + "' must be a string");
            }
            result.add(item.textValue());
        }
        return result;
    }
}
