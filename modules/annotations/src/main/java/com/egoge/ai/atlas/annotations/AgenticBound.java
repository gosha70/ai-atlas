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
 * Written by the ai-atlas processor on a generated REST or MCP wrapper method whose service method
 * declares {@link AgenticExposed#maxResults()}, so the runtime can log a WARN when a result holds
 * more elements than declared. The result is never truncated. Declare the bound with
 * {@code @AgenticExposed(maxResults = N)} on the service method, not with this annotation.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgenticBound {

    /** The declared bound: the most elements the service method's result holds. */
    int maxResults();
}
