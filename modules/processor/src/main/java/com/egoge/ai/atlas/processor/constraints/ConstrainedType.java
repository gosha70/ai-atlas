/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * What kinds of constraint a constrained element's type can carry (FR-002, FR-004).
 *
 * @param type     the element's type
 * @param numeric  whether bounds apply: a numeric primitive or a {@link Number}
 * @param integral whether bounds are compared over integers (FR-008)
 * @param string   whether lengths apply: a {@link CharSequence}
 * @param items    whether item counts apply: a collection or an array
 */
record ConstrainedType(TypeMirror type, boolean numeric, boolean integral, boolean string, boolean items) {

    static ConstrainedType of(TypeMirror type, ProcessingEnvironment env) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()) {
            boolean integral = kind == TypeKind.BYTE || kind == TypeKind.SHORT || kind == TypeKind.INT
                    || kind == TypeKind.LONG;
            boolean numeric = integral || kind == TypeKind.FLOAT || kind == TypeKind.DOUBLE;
            return new ConstrainedType(type, numeric, integral, false, false);
        }
        if (kind == TypeKind.ARRAY) {
            return new ConstrainedType(type, false, false, false, true);
        }
        if (kind != TypeKind.DECLARED) {
            return new ConstrainedType(type, false, false, false, false);
        }
        TypeMirror erased = env.getTypeUtils().erasure(type);
        String name = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
        return new ConstrainedType(type,
                assignable(erased, "java.lang.Number", env),
                Endpoint.INTEGRAL_BOXES.contains(name),
                assignable(erased, "java.lang.CharSequence", env),
                assignable(erased, "java.util.Collection", env));
    }

    private static boolean assignable(TypeMirror erased, String target, ProcessingEnvironment env) {
        TypeElement element = env.getElementUtils().getTypeElement(target);
        return element != null && env.getTypeUtils().isAssignable(erased, env.getTypeUtils().erasure(element.asType()));
    }
}
