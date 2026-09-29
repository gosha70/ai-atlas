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
     * <strong>Spike (Phase 5, epic #23 &sect;8).</strong> The parameter's role in a paging contract the
     * service itself honours. Read only with the processor option {@code ai.atlas.collections} on.
     * {@link Paging#NONE} (default) gives it no role.
     */
    Paging paging() default Paging.NONE;

    /** A parameter's role in a service-honoured paging contract. */
    enum Paging {
        /** No paging role. */
        NONE,
        /** The most results the service returns for the call: an integral parameter the service honours. */
        LIMIT,
        /** An opaque position the service resumes after. Bounds nothing on its own. */
        CURSOR
    }
}
