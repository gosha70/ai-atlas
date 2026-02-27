/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.model;

import com.palantir.javapoet.ClassName;

import java.util.List;

/**
 * Internal model representing an entity class annotated with
 * {@code @AgentVisibleClass}. Contains the entity's type information
 * and the list of whitelisted fields for DTO generation.
 */
public record EntityModel(
        ClassName sourceClassName,
        String dtoName,
        String dtoPackageName,
        List<FieldModel> fields
) {

    /**
     * Returns the fully qualified ClassName for the generated DTO.
     */
    public ClassName dtoClassName() {
        return ClassName.get(dtoPackageName, dtoName);
    }
}
