/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

/**
 * Whether clients must pass a parameter, as declared by {@link AgenticParam#required()}.
 */
public enum Requiredness {

    /**
     * Not declared: the parameter is required when it is a primitive or carries {@code @NotNull},
     * {@code @NotBlank} or {@code @NotEmpty}, and otherwise required as REST query parameters
     * are.
     */
    DEFAULT,

    /** Clients must pass the parameter. */
    REQUIRED,

    /**
     * Clients may omit the parameter. Not allowed on a primitive, or together with
     * {@code @NotNull}, {@code @NotBlank} or {@code @NotEmpty}.
     */
    OPTIONAL
}
