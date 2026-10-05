/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.egoge.ai.atlas.processor.util.ReturnedTypes;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * The JSON shape of a request body that is not an {@code @AgenticEntity}, classified from its
 * compile-time type so the OpenAPI document describes what Jackson binds: an array or a
 * {@code java.util.Collection} is a JSON array, and an enum is a string with its constants.
 *
 * @param array      whether the body is an array or a collection
 * @param javaType   the body's type, or for an array its element type, as a qualified or primitive
 *                   name; {@code null} for a raw collection, whose elements are unconstrained
 * @param enumValues the constants of that type when it is an enum, else {@code null}
 */
public record BodyType(boolean array, String javaType, List<String> enumValues) {

    private static final String COLLECTION = "java.util.Collection";

    /** Classifies {@code type}, a body parameter's type. */
    static BodyType of(TypeMirror type, Types types, Elements elements) {
        TypeElement collection = elements.getTypeElement(COLLECTION);
        boolean array = type instanceof ArrayType
                || collection != null && types.isAssignable(types.erasure(type), types.erasure(collection.asType()));
        TypeMirror single = array ? ReturnedTypes.elementType(type, types) : type;
        if (single == null) {
            return new BodyType(true, null, null);
        }
        if (single instanceof DeclaredType declared) {
            TypeElement element = (TypeElement) declared.asElement();
            List<String> constants = element.getKind() != ElementKind.ENUM ? null
                    : element.getEnclosedElements().stream().filter(e -> e.getKind() == ElementKind.ENUM_CONSTANT)
                            .map(e -> e.getSimpleName().toString()).toList();
            return new BodyType(array, element.getQualifiedName().toString(), constants);
        }
        return new BodyType(array, single.toString(), null);
    }
}
