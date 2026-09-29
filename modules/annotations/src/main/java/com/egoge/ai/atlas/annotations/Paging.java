/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

/**
 * A parameter's role in a paging contract the service itself honours, as declared by
 * {@link AgenticParam#paging()}. Read only with the processor option {@code ai.atlas.collections}
 * on; declaring a role with it off is a compile error.
 */
public enum Paging {

    /** No paging role. */
    NONE,

    /**
     * The most results the service returns for the call. The parameter must be an {@code int},
     * {@code long} or {@code short}, boxed or not, and the service must honour it: ai-atlas never
     * truncates a result. With {@code ai.atlas.constraints} on, its ceiling is its {@code @Max}.
     */
    LIMIT,

    /**
     * An opaque position the service resumes after. Only allowed with a {@link #LIMIT} parameter,
     * as a cursor bounds nothing on its own, and optional unless declared otherwise, as the first
     * call has none.
     */
    CURSOR
}
