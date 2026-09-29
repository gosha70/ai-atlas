/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * The entities with each direct field's {@code @AgenticField(type = ...)} hint in effect, which
     * {@link #resolve} otherwise honours only for collection, iterable and array elements. A field
     * whose declared type is not a registered entity but whose hint is one, such as an unannotated
     * {@code Vip extends Person} field hinted {@code type = Person.class}, gets the hint as its type, so
     * it maps through the entity's records. Only {@code ai.atlas.projections=true} applies this; with
     * the option off a direct hint keeps having no effect.
     *
     * @param registry every registered entity by qualified class name
     * @return the entities, in the same order; an entity with no such field is the same instance
     */
    public static Map<String, EntityModel> withDirectHints(Map<String, EntityModel> registry) {
        Map<String, EntityModel> result = new LinkedHashMap<>();
        registry.forEach((className, entity) -> {
            List<FieldModel> fields = entity.fields().stream().map(f -> directHinted(f, registry)).toList();
            result.put(className, fields.equals(entity.fields()) ? entity
                    : new EntityModel(entity.sourceClassName(), entity.dtoName(), entity.dtoPackageName(),
                            entity.displayName(), entity.classDescription(), entity.includeTypeInfo(), fields));
        });
        return result;
    }

    /**
     * A direct field with its hint as its type when the hint names a registered entity and its
     * declared type does not; any other field unchanged.
     *
     * @param field    the field
     * @param registry every registered entity by qualified class name
     * @return the field as {@link #withDirectHints} sees it
     */
    public static FieldModel directHinted(FieldModel field, Map<String, EntityModel> registry) {
        if (field.collectionKind() != FieldModel.CollectionKind.NONE
                || !(field.hintTypeName() instanceof ClassName hint) || !registry.containsKey(hint.canonicalName())
                || field.typeName() instanceof ClassName type && registry.containsKey(type.canonicalName())) {
            return field;
        }
        return new FieldModel(field.name(), field.displayName(), hint, field.description(), field.sensitive(),
                field.checkCircularReference(), field.enumType(), field.enumValues(), field.collectionKind(),
                field.elementTypeName(), field.hintTypeName(), field.sinceVersion(), field.removedInVersion(),
                field.deprecatedSinceVersion(), field.deprecatedMessage());
    }
}
