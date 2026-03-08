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
     * Defaults to both AI (MCP tools) and API (REST controllers + OpenAPI).
     *
     * <p>Use {@code channels = {Channel.API}} to expose only as a REST
     * endpoint, or {@code channels = {Channel.AI}} for MCP-only exposure.
     */
    Channel[] channels() default { Channel.AI, Channel.API };

    /**
     * Exposure channel for generated service artifacts.
     */
    enum Channel {
        /** MCP tool generation */
        AI,
        /** REST controller and OpenAPI generation */
        API
    }
}
