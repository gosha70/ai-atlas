/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.processor.model;

import com.palantir.javapoet.TypeName;

import java.util.List;

/**
 * Internal model representing a single {@code @AgentVisible} field
 * within an entity class. Used by generators to produce DTO components
 * and field metadata for enriched JSON serialization.
 */
public record FieldModel(
        /** Java field name (e.g., "status") */
        String name,
        /** Display name from @AgentVisible.name, or field name if empty */
        String displayName,
        TypeName typeName,
        String description,
        boolean sensitive,
        /** Whether to check for circular references during serialization */
        boolean checkCircularReference,
        /** True if the field type is a Java enum */
        boolean enumType,
        /** Enum constant names (empty list if not an enum type) */
        List<String> enumValues
) {
}
