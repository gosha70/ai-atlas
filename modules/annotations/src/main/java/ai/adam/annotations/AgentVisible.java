/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.annotations;

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
     * Used in MCP tool parameter descriptions and OpenAPI schema docs.
     */
    String description() default "";

    /**
     * Whether this field contains sensitive data that should be logged
     * with care (e.g., masked in audit logs). The field is still included
     * in the DTO, but runtime interceptors may mask its value.
     */
    boolean sensitive() default false;
}
