/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.annotations.AgenticEntity;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What a method's declared return type holds: its element type, and the entity it is or extends. */
public final class ReturnedTypes {

    private ReturnedTypes() {
    }

    /**
     * The {@code @AgenticEntity} a type is or, nearest first, extends or implements, or {@code null}
     * when none. The annotation is not {@code @Inherited}, so an unannotated subclass of an entity is
     * found only by walking its supertypes.
     */
    public static TypeElement entityOf(TypeMirror type, Types types) {
        Deque<TypeMirror> pending = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            TypeMirror current = pending.removeFirst();
            if (!(current instanceof DeclaredType declared)) {
                continue;
            }
            TypeElement element = (TypeElement) declared.asElement();
            if (!seen.add(element.getQualifiedName().toString())) {
                continue;
            }
            if (element.getAnnotation(AgenticEntity.class) != null) {
                return element;
            }
            pending.addAll(types.directSupertypes(current));
        }
        return null;
    }

    /**
     * The element type of an array, or of a collection or iterable through its {@code Iterable}
     * supertype, or {@code null} when it is raw or unbounded.
     */
    public static TypeMirror elementType(TypeMirror type, Types types) {
        if (type instanceof ArrayType array) {
            return array.getComponentType();
        }
        Deque<TypeMirror> pending = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            TypeMirror current = pending.pop();
            if (!(current instanceof DeclaredType declared) || !seen.add(current.toString())) {
                continue;
            }
            if (((TypeElement) declared.asElement()).getQualifiedName().contentEquals("java.lang.Iterable")) {
                List<? extends TypeMirror> arguments = declared.getTypeArguments();
                TypeMirror element = arguments.isEmpty() ? null : arguments.get(0);
                return element instanceof WildcardType wildcard ? wildcard.getExtendsBound() : element;
            }
            pending.addAll(types.directSupertypes(current));
        }
        return null;
    }
}
