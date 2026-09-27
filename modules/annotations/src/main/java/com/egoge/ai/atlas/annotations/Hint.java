/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

/**
 * A declared MCP behavioural hint on {@link AgenticExposed}. Hints are client guidance, not
 * authorization: they describe the tool to an MCP client and enforce nothing.
 */
public enum Hint {

    /** Not declared: a method-level value inherits the class-level one, and nothing is inferred. */
    UNSET,

    /** Declared true. */
    TRUE,

    /** Declared false. */
    FALSE
}
