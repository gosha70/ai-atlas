/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;

import java.util.List;

/**
 * The Contract IR: every {@code @AgenticEntity} and {@code @AgenticExposed} declaration of one
 * compilation with its lifecycle, independent of the configured major (FR-001, FR-002).
 *
 * <p>Plain values only (FR-005): Java types are canonical source-form strings, enums are their
 * names, and absent values are {@code null}. {@link IrJson} writes and reads the canonical form.
 *
 * <p>A constraint, requiredness or hint slot is {@code null} only in a document migrated from
 * {@code irVersion} 1: its value is <em>unknown</em>, which is distinct from "none" (FR-006). A
 * field's channels are never unknown: before {@code irVersion} 3 nothing could narrow them, so a
 * migrated field is on every channel.
 *
 * @param irVersion   version of this document's format, {@link #IR_VERSION} when written
 * @param apiBasePath configured REST base path, e.g. {@code /api}
 * @param apiMajor    configured major the document was emitted for
 * @param entities    entities ordered by qualified class name
 * @param operations  operations ordered by service qualified name, then method identity
 */
public record ContractIr(int irVersion, String apiBasePath, int apiMajor,
                         List<Entity> entities, List<Operation> operations) {

    /** The {@code irVersion} this ai-atlas writes and the highest it reads. */
    public static final int IR_VERSION = 3;
    /** Class-output-relative path of the emitted IR document. */
    public static final String RESOURCE_PATH = "META-INF/ai-atlas/api.ir.json";

    public ContractIr {
        entities = List.copyOf(entities);
        operations = List.copyOf(operations);
    }

    /**
     * An {@code @AgenticEntity} class.
     *
     * @param className       qualified name of the source class, the entity's identity
     * @param dtoName         simple name of the generated DTO
     * @param dtoPackage      package of the generated DTO
     * @param displayName     {@code @AgenticEntity(name)}, or the simple class name
     * @param description     {@code @AgenticEntity(description)}
     * @param includeTypeInfo {@code @AgenticEntity(includeTypeInfo)}
     * @param fields          every {@code @AgenticField}, in the order the DTO declares them
     */
    public record Entity(String className, String dtoName, String dtoPackage, String displayName,
                         String description, boolean includeTypeInfo, List<Field> fields) {

        public Entity {
            fields = List.copyOf(fields);
        }
    }

    /**
     * An {@code @AgenticField}.
     *
     * @param name                   Java field name
     * @param displayName            {@code @AgenticField(name)}, or the field name
     * @param javaType               the field's Java type
     * @param collectionKind         {@code NONE}, {@code COLLECTION}, {@code ITERABLE} or {@code ARRAY}
     * @param elementType            element type of a collection or array, or {@code null}
     * @param typeHint               {@code @AgenticField(type)}, or {@code null}
     * @param reference              the entity the field refers to and its DTO, or {@code null}
     * @param enumType               whether {@code allowedValues} are the constants of the field's enum type
     * @param allowedValues          {@code @AgenticField(allowedValues)}, or the enum constants
     * @param openEnum               whether clients tolerate values added to {@code allowedValues}
     * @param sensitive              {@code @AgenticField(sensitive)}
     * @param checkCircularReference {@code @AgenticField(checkCircularReference)}
     * @param description            {@code @AgenticField(description)}
     * @param constraints            the field's effective constraints, or {@code null} when unknown
     * @param channels               the channels whose responses carry the field, sorted: its
     *                               effective eligibility, every channel unless
     *                               {@code ai.atlas.projections} is on and it declares fewer
     * @param lifecycle              the field's lifecycle
     */
    public record Field(String name, String displayName, String javaType, String collectionKind,
                        String elementType, String typeHint, TypeRef reference, boolean enumType,
                        List<String> allowedValues, boolean openEnum, boolean sensitive,
                        boolean checkCircularReference, String description, EffectiveConstraints constraints,
                        List<String> channels, FieldLifecycle lifecycle) {

        /** The channels of a field that declares none, and of every field migrated from before {@code irVersion} 3. */
        public static final List<String> EVERY_CHANNEL = List.of("AI", "API");

        /**
         * @throws IllegalArgumentException if {@code channels} is not a non-empty, sorted list of
         *                                  distinct channels among {@link #EVERY_CHANNEL}
         */
        public Field {
            allowedValues = List.copyOf(allowedValues);
            channels = List.copyOf(channels);
            if (channels.isEmpty() || !EVERY_CHANNEL.containsAll(channels)
                    || !channels.equals(channels.stream().distinct().sorted().toList())) {
                throw new IllegalArgumentException("'channels' of field '" + name + "' must be a non-empty,"
                        + " sorted list of distinct channels among " + EVERY_CHANNEL + ", got " + channels);
            }
        }
    }

    /**
     * A field's lifecycle, as declared on {@code @AgenticField}.
     *
     * @param sinceVersion           first major the field is active in
     * @param removedInVersion       first major the field is no longer active in
     * @param deprecatedSinceVersion major the field is deprecated from, {@code 0} if never
     * @param deprecatedMessage      migration guidance for a deprecated field
     */
    public record FieldLifecycle(int sinceVersion, int removedInVersion, int deprecatedSinceVersion,
                                 String deprecatedMessage) {
    }

    /**
     * A reference to an entity and the DTO generated for it, both qualified names.
     *
     * @param entity qualified name of the entity class
     * @param dto    qualified name of its DTO
     */
    public record TypeRef(String entity, String dto) {
    }

    /**
     * An exposed method. Its identity is the service, the method name and the parameter types.
     *
     * @param service     qualified name of the service class
     * @param method      method name
     * @param toolName    MCP tool name
     * @param channels    resolved channels, sorted
     * @param description resolved description
     * @param rest        the REST mapping, or {@code null} when the method is not on the API channel
     * @param parameters  parameters in declaration order
     * @param returns     the method's return
     * @param hints       the declared MCP behavioural hints, or {@code null} when unknown
     * @param lifecycle   the method's lifecycle, after method-then-class resolution
     */
    public record Operation(String service, String method, String toolName, List<String> channels,
                            String description, Rest rest, List<Parameter> parameters, Return returns,
                            Hints hints, OperationLifecycle lifecycle) {

        public Operation {
            channels = List.copyOf(channels);
            parameters = List.copyOf(parameters);
        }

        /** The identity as {@code service#method(parameter types)}. */
        public String id() {
            return service + "#" + signature();
        }

        /** The method identity within its service, {@code method(parameter types)}. */
        public String signature() {
            return renderSignature(method, parameters.stream().map(Parameter::javaType).toList());
        }

        /**
         * {@code method(parameter types)}, the one rendering of an operation's identity within its
         * service, shared by the IR and the gate's element lookup.
         *
         * @param method         method name
         * @param parameterTypes parameter types in declaration order, as {@link Parameter#javaType()}
         * @return the signature
         */
        public static String renderSignature(String method, List<String> parameterTypes) {
            return method + "(" + String.join(",", parameterTypes) + ")";
        }
    }

    /**
     * The REST mapping of an operation, independent of the base path and major.
     *
     * @param httpMethod {@code GET} or {@code POST}
     * @param path       {@code /<service-kebab>/<method-kebab>}; the generated mapping prefixes
     *                   {@code <apiBasePath>/v<major>}
     */
    public record Rest(String httpMethod, String path) {
    }

    /**
     * A method parameter.
     *
     * @param name          parameter name
     * @param javaType      parameter Java type
     * @param description   parameter description
     * @param enumConstants constants of the parameter's enum type; empty when it is not an enum
     * @param required      whether clients must pass the parameter, or {@code null} when unknown
     * @param constraints   the parameter's effective constraints, or {@code null} when unknown
     */
    public record Parameter(String name, String javaType, String description, List<String> enumConstants,
                            Boolean required, EffectiveConstraints constraints) {

        public Parameter {
            enumConstants = List.copyOf(enumConstants);
        }
    }

    /**
     * An operation's return.
     *
     * @param javaType   the method's return type
     * @param returnKind {@code NONE}, {@code COLLECTION}, {@code ITERABLE} or {@code ARRAY}
     * @param returnType the effective {@code @AgenticExposed(returnType)} after method-then-class
     *                   resolution, or {@code null}
     * @param reference  the entity {@code returnType} names and its DTO, or {@code null} when it
     *                   names no declared entity
     */
    public record Return(String javaType, String returnKind, String returnType, TypeRef reference) {
    }

    /**
     * An operation's declared MCP behavioural hints; each is {@code null} when not declared.
     *
     * @param readOnly    whether the tool only reads
     * @param destructive whether the tool may destroy data
     * @param idempotent  whether repeating a call has no further effect
     * @param openWorld   whether the tool reaches outside the service's own domain
     */
    public record Hints(Boolean readOnly, Boolean destructive, Boolean idempotent, Boolean openWorld) {

        /** No hint declared. */
        public static final Hints NONE = new Hints(null, null, null, null);
    }

    /**
     * An operation's lifecycle, after method-then-class resolution.
     *
     * @param apiSince           first major the operation is active in
     * @param apiUntil           last major the operation is active in
     * @param apiDeprecatedSince major the operation is deprecated from, {@code 0} if never
     * @param apiReplacement     the replacement named for a deprecated operation
     */
    public record OperationLifecycle(int apiSince, int apiUntil, int apiDeprecatedSince, String apiReplacement) {
    }
}
