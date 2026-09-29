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
     * The parameter's role in a paging contract the service honours: {@link Paging#LIMIT} for the
     * most results it returns, {@link Paging#CURSOR} for the position it resumes after.
     * {@link Paging#NONE} (default) gives it none. Read only with the processor option
     * {@code ai.atlas.collections} on; a role declared with it off is a compile error.
     */
    Paging paging() default Paging.NONE;

    /**
     * On a Spring Data {@code Pageable} parameter, the entity properties a REST client may sort
     * by, each a field of the returned entity's DTO. When empty (default), the generated REST
     * controller drops any requested sort, as a sort on a property clients cannot see leaks its
     * values through the order. Sorting is never offered on MCP. Read only with the processor
     * option {@code ai.atlas.collections} on; declared with it off, or on any other parameter, it is
     * a compile error.
     */
    String[] sortable() default {};
}
