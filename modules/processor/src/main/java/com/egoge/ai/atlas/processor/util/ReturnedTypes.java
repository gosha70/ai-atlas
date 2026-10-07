/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import com.egoge.ai.atlas.annotations.AgenticEntity;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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

    /**
     * The declarations {@code type} mentions at any depth, by qualified name, a type variable's
     * through its bound: two variables of one name are told apart by what they bound, never by the
     * name. A type walk can follow a declaration's members once per erasure and set of these:
     * whatever an instance reaches through its members comes from the members' declared types or from
     * the declarations of its type arguments. Following them for every instance would not end on a
     * type that grows through its own supertype, such as {@code Grow<T> extends ArrayList<Grow<List<T>>>}.
     */
    public static Set<String> declarations(TypeMirror type) {
        Set<String> declarations = new TreeSet<>();
        Deque<TypeMirror> pending = new ArrayDeque<>(List.of(type));
        Set<TypeMirror> variables = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            TypeMirror current = pending.pop();
            if (current instanceof TypeVariable variable) {
                // Each variable once: its bound may mention itself
                if (variables.add(variable)) {
                    pending.push(variable.getUpperBound());
                }
            } else if (current instanceof IntersectionType intersection) {
                intersection.getBounds().forEach(pending::push);
            } else if (current instanceof ArrayType array) {
                pending.push(array.getComponentType());
            } else if (current instanceof WildcardType wildcard) {
                if (wildcard.getExtendsBound() != null) {
                    pending.push(wildcard.getExtendsBound());
                }
                if (wildcard.getSuperBound() != null) {
                    pending.push(wildcard.getSuperBound());
                }
            } else if (current instanceof DeclaredType declared) {
                declarations.add(((TypeElement) declared.asElement()).getQualifiedName().toString());
                declared.getTypeArguments().forEach(pending::push);
            } else {
                declarations.add(current.toString());
            }
        }
        return declarations;
    }
}
