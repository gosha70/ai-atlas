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
 * Describes a parameter of an {@link AgenticExposed} method to clients.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AgenticExposed(description = "Find orders")
 * public List<Order> find(@AgenticParam(description = "Page size", required = Requiredness.OPTIONAL)
 *                         Integer limit) { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticParam {

    /** The parameter's description for clients. When empty (default), none is declared. */
    String description() default "";

    /**
     * Whether clients must pass the parameter. {@link Requiredness#DEFAULT} (default) derives it
     * from the parameter's type and Bean Validation constraints.
     */
    Requiredness required() default Requiredness.DEFAULT;
}
