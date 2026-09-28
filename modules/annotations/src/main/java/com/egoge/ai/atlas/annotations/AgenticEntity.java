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
 * Marks a class as a source for DTO generation. The annotation processor
 * will scan this class for {@link AgenticField} fields and generate a
 * corresponding Java record DTO containing only the whitelisted fields.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AgenticEntity(dtoName = "OrderSummary")
 * public class Order {
 *     @AgenticField(description = "Order ID")
 *     private Long id;
 *     // ...
 * }
 * }</pre>
 *
 * <p>Generated output: {@code OrderSummary.java} record in
 * {@code {originalPackage}.generated} package (unless overridden).
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticEntity {

    /**
     * Custom name for the generated DTO. Defaults to {@code {ClassName}Dto}.
     */
    String dtoName() default "";

    /**
     * Custom name for the separate record MCP tools return when the entity's AI and API projections
     * differ ({@link AgenticField#channels()}). Defaults to the DTO name with its {@code Dto} suffix
     * replaced by {@code AiDto}: {@code OrderDto} becomes {@code OrderAiDto}, and a custom
     * {@code OrderSummary} becomes {@code OrderSummaryAiDto}. Generated in the DTO's package.
     *
     * <p>The record exists only while the projections differ, directly or through a referenced
     * entity, so it can appear or disappear as channel declarations change; MCP clients receive
     * JSON and never see its name. Only meaningful with the processor option
     * {@code ai.atlas.projections=true}; a name that collides with another type in the package is a
     * compile error.
     */
    String aiDtoName() default "";

    /**
     * Override package for the generated DTO. Defaults to
     * {@code {originalPackage}.generated}.
     */
    String packageName() default "";

    /**
     * Display name for this entity type in LLM-facing contexts.
     * Used in the {@code typeInfo} block of enriched JSON output
     * and MCP tool descriptions. Defaults to the simple class name.
     *
     * <p>Example: {@code @AgenticEntity(name = "insight")} produces
     * {@code "typeInfo": {"name": "insight", ...}} in enriched JSON.
     */
    String name() default "";

    /**
     * Human-readable description of this entity class for LLM consumption.
     * Included in the {@code typeInfo} block of enriched JSON output,
     * OpenAPI schema descriptions, and MCP tool context.
     */
    String description() default "";

    /**
     * Whether to include a {@code typeInfo} block (containing name and
     * description) in enriched JSON output. Defaults to {@code true}.
     */
    boolean includeTypeInfo() default true;
}
