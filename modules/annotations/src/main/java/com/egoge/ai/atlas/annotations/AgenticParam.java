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

    /**
     * Where a REST request carries the parameter. Requires the processor option
     * {@code ai.atlas.rest=true}; without it, any value other than {@link In#DEFAULT} is a compile
     * error. MCP tools are unaffected: they always take the parameter by name.
     */
    In in() default In.DEFAULT;

    /** The location of a REST parameter. */
    enum In {
        /**
         * Derived, in this order: the path when a {@code {name}} segment of the operation's path
         * names the parameter; the body when it is an {@code @AgenticEntity} of an operation with
         * an explicit or CRUD mapping; otherwise the query. An operation on the RPC mapping never
         * gets a body by default.
         */
        DEFAULT,
        /** A {@code {name}} segment of the operation's path, which must name the parameter. */
        PATH,
        /** A query parameter. */
        QUERY,
        /** The JSON request body; at most one parameter per operation. */
        BODY
    }
}
