/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.runtime.json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SerializationContextTest {

    @AfterEach
    void cleanup() {
        SerializationContext.clear();
    }

    @Test
    void emptyContextDoesNotContainAnything() {
        assertThat(SerializationContext.containsInstance(new Object())).isFalse();
    }

    @Test
    void nullIsNeverContained() {
        assertThat(SerializationContext.containsInstance(null)).isFalse();
    }

    @Test
    void addAndContainsSameInstance() {
        Object obj = new Object();
        SerializationContext.addInstance(obj);

        assertThat(SerializationContext.containsInstance(obj)).isTrue();
    }

    @Test
    void doesNotContainDifferentObjectWithSameState() {
        String a = new String("test");
        String b = new String("test");
        // a.equals(b) is true, but they are different instances
        assertThat(a).isEqualTo(b);

        SerializationContext.addInstance(a);

        assertThat(SerializationContext.containsInstance(a)).isTrue();
        assertThat(SerializationContext.containsInstance(b)).isFalse();
    }

    @Test
    void removeInstanceAllowsReuse() {
        Object obj = new Object();
        SerializationContext.addInstance(obj);
        assertThat(SerializationContext.containsInstance(obj)).isTrue();

        SerializationContext.removeInstance(obj);
        assertThat(SerializationContext.containsInstance(obj)).isFalse();
    }

    @Test
    void clearRemovesAllInstances() {
        Object a = new Object();
        Object b = new Object();
        SerializationContext.addInstance(a);
        SerializationContext.addInstance(b);

        SerializationContext.clear();

        assertThat(SerializationContext.containsInstance(a)).isFalse();
        assertThat(SerializationContext.containsInstance(b)).isFalse();
    }

    @Test
    void addNullDoesNotThrow() {
        SerializationContext.addInstance(null);
        // Should not throw, and null is still not contained
        assertThat(SerializationContext.containsInstance(null)).isFalse();
    }

    @Test
    void removeNullDoesNotThrow() {
        SerializationContext.removeInstance(null);
        // Should not throw
    }
}
