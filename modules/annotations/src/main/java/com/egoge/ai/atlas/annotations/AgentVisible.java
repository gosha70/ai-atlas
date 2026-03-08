/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as visible to AI agents. Only fields annotated with this
 * annotation will be included in generated DTOs. Unannotated fields are
 * structurally excluded — whitelist model ensures PII safety by default.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AgentVisibleClass
 * public class Order {
 *     @AgentVisible(description = "Unique order identifier")
 *     private Long id;
 *
 *     @AgentVisible(description = "Order status", sensitive = true)
 *     private String status;
 *
 *     // Not annotated — excluded from generated DTO
 *     private String customerSsn;
 * }
 * }</pre>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentVisible {

    /**
     * Human-readable description of the field for LLM consumption.
     * Used in MCP tool parameter descriptions, OpenAPI schema docs,
     * and enriched JSON output for AI agents.
     */
    String description() default "";

    /**
     * Custom display name for this field in enriched JSON metadata and
     * runtime entity serialization. When empty (default), the Java field
     * name is used. Mirrors {@code @CopilotField(name = ...)} semantics.
     *
     * <p>This name is used as the key in the generated {@code FIELD_METADATA}
     * map and as the JSON property name when the runtime {@code AgentSafeSerializer}
     * serializes entities in enriched mode. It does <strong>not</strong> rename
     * the DTO record component or OpenAPI schema property — those always use
     * the Java field name to maintain getter compatibility.
     *
     * <p>Must be unique across all {@code @AgentVisible} fields within the
     * same class. A compile error is emitted if two fields share the same
     * display name.
     *
     * <p>Example: {@code @AgentVisible(name = "totalCents")} on a field
     * named {@code total} will produce a FIELD_METADATA entry keyed by
     * {@code "totalCents"} and serialize as {@code "totalCents"} in
     * enriched JSON output.
     */
    String name() default "";

    /**
     * Whether this field contains sensitive data that should be logged
     * with care (e.g., masked in audit logs). The field is still included
     * in the DTO, but runtime interceptors may mask its value.
     */
    boolean sensitive() default false;

    /**
     * Whether to check for circular references when serializing this
     * field at runtime. When {@code true} (default), the JSON serializer
     * will track object identity and skip already-visited instances to
     * prevent infinite recursion — critical for bidirectional JPA
     * relationships.
     *
     * <p>Set to {@code false} only for leaf fields that are guaranteed
     * never to form cycles (e.g., primitive wrappers, strings, enums).
     */
    boolean checkCircularReference() default true;

    /**
     * Returns the array of values which either presents the enum values, or hard-coded values
     * specified by the developer.
     *
     * @return the range of values this field might be set to
     */
    String[] allowedValues() default {};

    /**
     * Declares the runtime element type for legacy collection fields whose
     * signatures use raw or wildcard types (e.g. {@code Collection} without
     * a type parameter). The annotation processor uses this hint as a
     * fallback when static type inference cannot resolve the element type.
     *
     * <p>At compile time this is read via {@code MirroredTypeException}
     * to obtain the {@code TypeMirror}, following the same pattern as
     * {@link AgenticExposed#returnType()}.
     *
     * <p>Only meaningful on collection, iterable, or array fields.
     * A compile warning is emitted if set on a non-collection field.
     */
    Class<?> type() default void.class;
}
