/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Thread-local circular reference tracker for safe JSON serialization of
 * JPA entity graphs. Uses object identity ({@code ==}) to detect cycles
 * in bidirectional relationships.
 *
 * <p>Ported from Appian's {@code CopilotPromptSerializationContext} pattern.
 * Must be cleared after serialization to prevent memory leaks.
 *
 * <p>Usage:
 * <pre>{@code
 * try {
 *     SerializationContext.addInstance(entity);
 *     // serialize...
 * } finally {
 *     SerializationContext.removeInstance(entity);
 * }
 * }</pre>
 */
public final class SerializationContext {

    private static final ThreadLocal<Set<Object>> CONTEXT =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    private SerializationContext() {
    }

    /**
     * Adds an object instance to the current serialization context.
     *
     * @param instance the object being serialized
     */
    public static void addInstance(Object instance) {
        if (instance != null) {
            CONTEXT.get().add(instance);
        }
    }

    /**
     * Removes an object instance from the current serialization context.
     *
     * @param instance the object that has been serialized
     */
    public static void removeInstance(Object instance) {
        if (instance != null) {
            CONTEXT.get().remove(instance);
        }
    }

    /**
     * Checks if the given instance is already being serialized (circular reference).
     *
     * @param instance the object to check
     * @return true if the object is already in the serialization context
     */
    public static boolean containsInstance(Object instance) {
        if (instance == null) {
            return false;
        }
        return CONTEXT.get().contains(instance);
    }

    /**
     * Clears the entire serialization context for the current thread
     * and removes the ThreadLocal value to prevent memory leaks in
     * thread-pool environments.
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
