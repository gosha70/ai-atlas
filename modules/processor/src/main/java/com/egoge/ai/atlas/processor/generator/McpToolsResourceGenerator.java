/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.generator;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractIr.Hints;
import com.egoge.ai.atlas.processor.contract.ContractProjection;
import com.egoge.ai.atlas.processor.contract.IrJson;
import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.ParameterModel;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes {@code META-INF/ai-atlas/mcp-tools.json} with {@code ai.atlas.constraints} on (FR-017):
 * one entry per AI-channel tool active at the configured major, ordered by tool name, each with its
 * {@code inputSchema} in JSON Schema 2020-12 and the declared hints as MCP tool
 * {@code annotations}. The runtime registers each tool with this schema and these annotations.
 *
 * <p>The document is written in the canonical form of {@link IrJson#writeCanonical}, so equal
 * inputs give equal bytes.
 */
public final class McpToolsResourceGenerator {

    /** Class-output-relative path of the tool specifications. */
    public static final String RESOURCE_PATH = "META-INF/ai-atlas/mcp-tools.json";
    /** The dialect MCP uses for tool input schemas. */
    public static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    static final String K_TOOLS = "tools";
    static final String K_NAME = "name";
    static final String K_INPUT_SCHEMA = "inputSchema";
    static final String K_ANNOTATIONS = "annotations";
    private static final String K_SCHEMA = "$schema";
    private static final String K_TYPE = "type";
    private static final String K_ITEMS = "items";
    private static final String K_ENUM = "enum";
    private static final String K_DESCRIPTION = "description";
    private static final String K_PROPERTIES = "properties";
    private static final String K_REQUIRED = "required";
    private static final String K_ADDITIONAL_PROPERTIES = "additionalProperties";
    /** MCP {@code ToolAnnotations} keys. */
    private static final String K_READ_ONLY_HINT = "readOnlyHint";
    private static final String K_DESTRUCTIVE_HINT = "destructiveHint";
    private static final String K_IDEMPOTENT_HINT = "idempotentHint";
    private static final String K_OPEN_WORLD_HINT = "openWorldHint";

    private static final String T_OBJECT = "object";
    private static final String T_ARRAY = "array";
    private static final String T_STRING = "string";
    private static final String T_INTEGER = "integer";
    private static final String T_NUMBER = "number";
    private static final String T_BOOLEAN = "boolean";
    private static final String AI_CHANNEL = "AI";

    private static final Set<String> STRING_TYPES = Set.of("java.lang.String", "java.lang.CharSequence",
            "java.lang.Character", "java.util.UUID", "java.util.Date");
    private static final String TIME_PACKAGE = "java.time";
    private static final Set<String> INTEGER_TYPES = Set.of("java.math.BigInteger");
    private static final Set<String> NUMBER_TYPES = Set.of("java.math.BigDecimal", "java.lang.Number");
    private static final Set<String> COLLECTION_TYPES = Set.of("java.lang.Iterable", "java.util.Collection",
            "java.util.List", "java.util.Set", "java.util.SortedSet", "java.util.NavigableSet", "java.util.Queue",
            "java.util.Deque", "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.TreeSet");

    private McpToolsResourceGenerator() {
    }

    /**
     * Writes the tool specifications of every generated service.
     *
     * @param services    the generated services, holding their operations active at the major
     * @param apiMajor    the configured major
     * @param constraints the constraint surfaces; the flag is on
     */
    public static void generate(List<ServiceModel> services, int apiMajor, ConstraintSurfaces constraints,
                                Filer filer, Messager messager) {
        Map<String, Object> document = document(services, apiMajor, constraints);
        if (!constraints.beanValidation() && !((List<?>) document.get(K_TOOLS)).isEmpty()) {
            // One NOTE per compilation: the tool classes were generated without Bean Validation (FR-016)
            messager.printMessage(Diagnostic.Kind.NOTE, "[ai-atlas] " + McpToolGenerator.VALIDATION_API_PROBE
                    + " does not resolve in this compilation, so the generated MCP tool classes carry no Bean"
                    + " Validation annotations and the MCP constraints are advisory. Add jakarta.validation-api"
                    + " to enforce them");
        }
        try {
            var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", RESOURCE_PATH);
            try (OutputStream out = resource.openOutputStream()) {
                out.write(IrJson.writeCanonical(document).getBytes(StandardCharsets.UTF_8));
            }
            messager.printMessage(Diagnostic.Kind.NOTE, "[ai-atlas] Generated MCP tool specifications: "
                    + RESOURCE_PATH);
        } catch (IOException e) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] Failed to write " + RESOURCE_PATH + ": " + e.getMessage());
        }
    }

    /** One AI-channel tool and the IR operation it was projected from. */
    private record Tool(String name, String operationKey, MethodModel method, ContractIr.Operation operation) {
    }

    static Map<String, Object> document(List<ServiceModel> services, int apiMajor, ConstraintSurfaces constraints) {
        List<Tool> tools = new ArrayList<>();
        for (ServiceModel service : services) {
            for (MethodModel method : service.methods()) {
                if (method.channels().contains(AI_CHANNEL) && VersionSelector.isActive(method, apiMajor)) {
                    String key = ContractProjection.operationKey(service.serviceClassName(), method);
                    tools.add(new Tool(method.toolName(), key, method, constraints.operation(key)));
                }
            }
        }
        tools.sort(Comparator.comparing(Tool::name).thenComparing(Tool::operationKey));
        List<Object> entries = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(K_NAME, tool.name());
            entry.put(K_INPUT_SCHEMA, inputSchema(tool.method(), tool.operation()));
            entry.put(K_ANNOTATIONS, annotations(tool.operation().hints()));
            entries.add(entry);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(K_TOOLS, entries);
        return doc;
    }

    private static Map<String, Object> inputSchema(MethodModel method, ContractIr.Operation operation) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (int i = 0; i < method.parameters().size(); i++) {
            ParameterModel param = method.parameters().get(i);
            ContractIr.Parameter irParam = operation.parameters().get(i);
            Map<String, Object> property = type(param.typeName(), irParam.enumConstants());
            property.put(K_DESCRIPTION, param.description().isEmpty() ? param.name() : param.description());
            ConstraintSurfaces.applyJsonSchema(property, irParam.constraints());
            properties.put(param.name(), property);
            if (irParam.required()) {
                required.add(param.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(K_SCHEMA, JSON_SCHEMA_2020_12);
        schema.put(K_TYPE, T_OBJECT);
        schema.put(K_PROPERTIES, properties);
        schema.put(K_REQUIRED, required);
        schema.put(K_ADDITIONAL_PROPERTIES, false);
        return schema;
    }

    /** The declared hints only, under MCP's {@code ToolAnnotations} keys. */
    private static Map<String, Object> annotations(Hints hints) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        putIfSet(annotations, K_READ_ONLY_HINT, hints.readOnly());
        putIfSet(annotations, K_DESTRUCTIVE_HINT, hints.destructive());
        putIfSet(annotations, K_IDEMPOTENT_HINT, hints.idempotent());
        putIfSet(annotations, K_OPEN_WORLD_HINT, hints.openWorld());
        return annotations;
    }

    /** The JSON type of a Java type; a type with no JSON counterpart is an {@code object}. */
    private static Map<String, Object> type(TypeName type, List<String> enumConstants) {
        Map<String, Object> schema = new LinkedHashMap<>();
        if (!enumConstants.isEmpty()) {
            schema.put(K_TYPE, T_STRING);
            schema.put(K_ENUM, new ArrayList<Object>(enumConstants));
            return schema;
        }
        String scalar = scalarType(type);
        if (scalar != null) {
            schema.put(K_TYPE, scalar);
            return schema;
        }
        TypeName element = elementType(type);
        if (element != null) {
            schema.put(K_TYPE, T_ARRAY);
            String elementScalar = scalarType(element);
            if (elementScalar != null) {
                Map<String, Object> items = new LinkedHashMap<>();
                items.put(K_TYPE, elementScalar);
                schema.put(K_ITEMS, items);
            }
            return schema;
        }
        schema.put(K_TYPE, T_OBJECT);
        return schema;
    }

    private static String scalarType(TypeName type) {
        TypeName unboxed = type.isBoxedPrimitive() ? type.unbox() : type;
        if (unboxed.isPrimitive()) {
            if (unboxed.equals(TypeName.BOOLEAN)) {
                return T_BOOLEAN;
            }
            if (unboxed.equals(TypeName.CHAR)) {
                return T_STRING;
            }
            return unboxed.equals(TypeName.FLOAT) || unboxed.equals(TypeName.DOUBLE) ? T_NUMBER : T_INTEGER;
        }
        if (!(type instanceof ClassName className)) {
            return null;
        }
        String name = className.canonicalName();
        if (STRING_TYPES.contains(name) || TIME_PACKAGE.equals(className.packageName())) {
            return T_STRING;
        }
        if (INTEGER_TYPES.contains(name)) {
            return T_INTEGER;
        }
        return NUMBER_TYPES.contains(name) ? T_NUMBER : null;
    }

    /** The element type of an array or a {@code java.util} collection, or {@code null}. */
    private static TypeName elementType(TypeName type) {
        if (type instanceof ArrayTypeName array) {
            return array.componentType();
        }
        if (type instanceof ParameterizedTypeName parameterized
                && COLLECTION_TYPES.contains(parameterized.rawType().canonicalName())
                && parameterized.typeArguments().size() == 1) {
            TypeName argument = parameterized.typeArguments().get(0);
            return argument instanceof WildcardTypeName wildcard ? wildcard.upperBounds().get(0) : argument;
        }
        return null;
    }

    private static void putIfSet(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
