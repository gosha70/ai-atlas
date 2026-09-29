/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.processor.model.EntityModel;
import com.egoge.ai.atlas.processor.model.FieldModel;
import com.palantir.javapoet.ClassName;

import java.util.List;

/**
 * The whitelisted input record a REST request body binds instead of an {@code @AgenticEntity}: the
 * entity's {@code @AgenticField}s active at the configured major, eligible for the API channel and
 * not declared {@code input = false}, with a {@code toEntity()} that creates the entity from them.
 * A property of the JSON body that is not a component, such as a field without
 * {@code @AgenticField}, is never set on the entity.
 *
 * @param name           the record's class, {@code <Entity>Input} in the entity's DTO package
 * @param entity         the entity, projected at the configured major
 * @param fields         the record's components, in the DTO's field order
 * @param viaConstructor whether {@code toEntity()} calls the entity's constructor taking every
 *                       component in order; otherwise its no-argument constructor and a setter per
 *                       component
 */
public record InputRecord(ClassName name, EntityModel entity, List<InputField> fields, boolean viaConstructor) {

    public InputRecord {
        fields = List.copyOf(fields);
    }

    /**
     * A component of an input record.
     *
     * @param field    the entity field it sets
     * @param required whether a request must set it: a primitive, or a Bean Validation
     *                 {@code @NotNull}, {@code @NotBlank} or {@code @NotEmpty}
     * @param setter   the entity's setter of the field, or {@code null} when the record creates the
     *                 entity through its constructor
     */
    public record InputField(FieldModel field, boolean required, String setter) {
    }
}
