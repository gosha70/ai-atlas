/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.Map;

/**
 * Resolves entity references from field types against the entity registry.
 * Shared between DtoGenerator (for DTO generation) and AgenticProcessor
 * (for empty-entity validation).
 */
public final class EntityRefResolver {

    /** Result of resolving an entity reference. */
    public record EntityRef(ClassName entityClass, ClassName dtoClass,
                            FieldModel.CollectionKind collectionKind) {}

    private EntityRefResolver() {}

    /**
     * Checks if a field references a registered {@code @AgenticEntity} entity.
     * Checks direct type, collection/iterable/array element type, and
     * {@code @AgenticField(type = ...)} hint in that order.
     *
     * @return entity ref info, or null if the field does not reference a registered entity
     */
    public static EntityRef resolve(FieldModel field, Map<String, EntityModel> entityRegistry) {
        FieldModel.CollectionKind kind = field.collectionKind();

        if (kind == FieldModel.CollectionKind.NONE) {
            TypeName typeName = field.typeName();
            if (typeName instanceof ClassName className) {
                EntityModel ref = entityRegistry.get(className.canonicalName());
                if (ref != null) {
                    return new EntityRef(className, ref.dtoClassName(), kind);
                }
            }
            return null;
        }

        // Collection/Iterable/Array — check element type
        TypeName elementType = field.elementTypeName();
        if (elementType instanceof ClassName elementClassName) {
            EntityModel ref = entityRegistry.get(elementClassName.canonicalName());
            if (ref != null) {
                return new EntityRef(elementClassName, ref.dtoClassName(), kind);
            }
        }

        // Fallback: @AgenticField(type = ...) hint
        TypeName hintType = field.hintTypeName();
        if (hintType instanceof ClassName hintClassName) {
            EntityModel ref = entityRegistry.get(hintClassName.canonicalName());
            if (ref != null) {
                return new EntityRef(hintClassName, ref.dtoClassName(), kind);
            }
        }

        return null;
    }
}
