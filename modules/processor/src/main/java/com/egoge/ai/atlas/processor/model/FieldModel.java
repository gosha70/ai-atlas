/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.model;

import com.palantir.javapoet.TypeName;

import java.util.List;

/**
 * Internal model representing a single {@code @AgenticField} field
 * within an entity class. Used by generators to produce DTO components
 * and field metadata for enriched JSON serialization.
 */
public record FieldModel(
    /* Java field name (e.g., "status") */
    String name,
    /* Display name from @AgenticField.name, or field name if empty */
    String displayName,
    TypeName typeName,
    String description,
    boolean sensitive,
    /* Whether to check for circular references during serialization */
    boolean checkCircularReference,
    /* True if the field type is a Java enum */
    boolean enumType,
    /* Enum constant names (empty list if not an enum type) */
    List<String> enumValues,
    /* How this field relates to collections/arrays */
    CollectionKind collectionKind,
    /* Element type for collections/arrays (null if NONE) */
    TypeName elementTypeName,
    /* User-declared element type from @AgenticField(type = ...), null if void.class */
    TypeName hintTypeName
) {

    /**
     * Classifies how a field type relates to iteration.
     * Used by the DtoGenerator to emit correct mapping code.
     */
    public enum CollectionKind {
        /** Not a collection or array */
        NONE,
        /** Assignable to java.util.Collection — has .stream() */
        COLLECTION,
        /** Assignable to java.lang.Iterable but NOT java.util.Collection — needs StreamSupport */
        ITERABLE,
        /** Java array type (Entity[]) — needs Arrays.stream() */
        ARRAY
    }
}
