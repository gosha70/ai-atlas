/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the {@code @AgenticEntity} a class serializes as: the class itself when annotated, else
 * the nearest annotated supertype, breadth first, as the processor's {@code ReturnedTypes.entityOf}
 * does: the class, then its superclass and its interfaces, then theirs, so a directly implemented
 * interface is found before a grandparent class. {@code @AgenticEntity} is not {@code @Inherited},
 * so an unannotated subtype (or a proxy subclass) would otherwise escape the whitelist (issue #50).
 */
final class EntityTypes {

    private static final ClassValue<Optional<Class<?>>> ENTITIES = new ClassValue<>() {
        @Override
        protected Optional<Class<?>> computeValue(Class<?> type) {
            return Optional.ofNullable(resolve(type));
        }
    };

    private EntityTypes() {
    }

    /** The nearest {@code @AgenticEntity} type of {@code type}, or {@code null} when there is none. */
    static Class<?> entityOf(Class<?> type) {
        return ENTITIES.get(type).orElse(null);
    }

    private static Class<?> resolve(Class<?> type) {
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(type);
        Set<Class<?>> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Class<?> current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current.isAnnotationPresent(AgenticEntity.class)) {
                return current;
            }
            if (current.getSuperclass() != null) {
                pending.add(current.getSuperclass());
            }
            pending.addAll(List.of(current.getInterfaces()));
        }
        return null;
    }
}
