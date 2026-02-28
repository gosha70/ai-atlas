/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.processor.model;

import com.palantir.javapoet.ClassName;

import java.util.List;

/**
 * Internal model representing an entity class annotated with
 * {@code @AgentVisibleClass}. Contains the entity's type information,
 * class-level metadata for LLM context, and the list of whitelisted
 * fields for DTO generation.
 */
public record EntityModel(
        ClassName sourceClassName,
        String dtoName,
        String dtoPackageName,
        /** Display name from @AgentVisibleClass.name (or simple class name) */
        String displayName,
        /** Class description from @AgentVisibleClass.description */
        String classDescription,
        /** Whether to include typeInfo block in enriched JSON */
        boolean includeTypeInfo,
        List<FieldModel> fields
) {

    /**
     * Returns the fully qualified ClassName for the generated DTO.
     */
    public ClassName dtoClassName() {
        return ClassName.get(dtoPackageName, dtoName);
    }
}
