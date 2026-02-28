/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.json;

import java.util.HashSet;
import java.util.Set;

/**
 * Thread-local circular reference tracker for safe JSON serialization of
 * JPA entity graphs. Uses object identity (System.identityHashCode) to detect
 * cycles in bidirectional relationships.
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

    private static final ThreadLocal<Set<Integer>> CONTEXT =
            ThreadLocal.withInitial(HashSet::new);

    private SerializationContext() {
    }

    /**
     * Adds an object instance to the current serialization context.
     *
     * @param instance the object being serialized
     */
    public static void addInstance(Object instance) {
        if (instance != null) {
            CONTEXT.get().add(System.identityHashCode(instance));
        }
    }

    /**
     * Removes an object instance from the current serialization context.
     *
     * @param instance the object that has been serialized
     */
    public static void removeInstance(Object instance) {
        if (instance != null) {
            CONTEXT.get().remove(System.identityHashCode(instance));
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
        return CONTEXT.get().contains(System.identityHashCode(instance));
    }

    /**
     * Clears the entire serialization context for the current thread.
     * Must be called in a finally block after serialization completes.
     */
    public static void clear() {
        CONTEXT.get().clear();
    }
}
