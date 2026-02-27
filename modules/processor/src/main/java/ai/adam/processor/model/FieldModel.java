/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.model;

import com.palantir.javapoet.TypeName;

/**
 * Internal model representing a single {@code @AgentVisible} field
 * within an entity class. Used by generators to produce DTO components.
 */
public record FieldModel(
        String name,
        TypeName typeName,
        String description,
        boolean sensitive
) {
}
