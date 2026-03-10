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
 * Marks a service class or method for MCP tool and REST controller generation.
 * The annotation processor will generate:
 * <ul>
 *   <li>An MCP tool class with {@code @McpTool} annotation</li>
 *   <li>A REST controller with appropriate mappings</li>
 * </ul>
 *
 * <p>When applied to a type, all public methods are exposed.
 * When applied to a method, only that method is exposed.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AgenticExposed(
 *     toolName = "getOrders",
 *     description = "Retrieves orders by status"
 * )
 * public OrderDto findByStatus(String status) { ... }
 * }</pre>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticExposed {

    /**
     * Name for the generated MCP tool. Defaults to the method/class name.
     */
    String toolName() default "";

    /**
     * Description of what this tool does and when to use it.
     * Used in MCP tool descriptions and OpenAPI operation summaries.
     */
    String description() default "";

    /**
     * The entity type returned by this method, used to resolve the
     * generated DTO for response mapping. Required when the return
     * type cannot be inferred from the method signature.
     *
     * <p>At compile time this is read via {@code MirroredTypeException}
     * to obtain the {@code TypeMirror}.
     */
    Class<?> returnType() default void.class;

    /**
     * Controls which channels this method is exposed to.
     * Defaults to {@code {Channel.INHERIT}}, which inherits the class-level
     * channels or the framework default ({@code AI, API}) if none are set.
     *
     * <p>When set explicitly, the values must be a subset of the class-level
     * channels (narrowing only). Mixing {@code INHERIT} with explicit values
     * is a compile error.
     *
     * <p>Use {@code channels = {Channel.API}} to expose only as a REST
     * endpoint, or {@code channels = {Channel.AI}} for MCP-only exposure.
     */
    Channel[] channels() default { Channel.INHERIT };

    /**
     * Exposure channel for generated service artifacts.
     */
    enum Channel {
        /** Inherit class-level channels, or framework default {AI, API} if no class-level value. */
        INHERIT,
        /** MCP tool generation */
        AI,
        /** REST controller and OpenAPI generation */
        API
    }

    /** Sentinel for int version attributes: "inherit from class, or use framework default." */
    int INHERIT = -1;

    /**
     * Minimum major API version where this method is available.
     * Included in generated artifacts when {@code apiSince <= configuredMajor}.
     * Use {@link #INHERIT} (default) to inherit from class-level annotation.
     * Framework default: 1 (available from the first version).
     */
    int apiSince() default INHERIT;

    /**
     * Maximum major API version where this method is available.
     * Excluded from generated artifacts when {@code configuredMajor > apiUntil}.
     * Use {@link #INHERIT} (default) to inherit from class-level annotation.
     * Framework default: {@code Integer.MAX_VALUE} (never removed).
     */
    int apiUntil() default INHERIT;

    /**
     * Major version at which this method became deprecated.
     * When {@code apiDeprecatedSince > 0 && apiDeprecatedSince <= configuredMajor},
     * the method is still generated but marked as deprecated in all artifacts.
     * Use {@link #INHERIT} (default) to inherit from class-level annotation.
     * Framework default: 0 (not deprecated).
     */
    int apiDeprecatedSince() default INHERIT;

    /** Sentinel for string version attributes: "inherit from class, or use framework default." */
    String INHERIT_STR = "\0";

    /**
     * Migration guidance for deprecated methods.
     * Only meaningful when {@code apiDeprecatedSince > 0}.
     * Use {@link #INHERIT_STR} (default) to inherit from class-level annotation.
     * Framework default: {@code ""} (no replacement).
     */
    String apiReplacement() default INHERIT_STR;
}
