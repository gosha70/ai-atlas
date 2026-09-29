/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntityTypes} searches supertypes breadth first, as the processor's
 * {@code ReturnedTypes.entityOf} does: the class, then its superclass and interfaces, then theirs.
 */
class EntityTypesTest {

    @AgenticEntity
    static class Grandparent {
    }

    static class Parent extends Grandparent {
    }

    @AgenticEntity
    interface Direct {
    }

    static class Child extends Parent implements Direct {
    }

    @AgenticEntity
    static class Annotated extends Parent implements Direct {
    }

    static class Plain {
    }

    @Test
    void directInterfaceIsFoundBeforeGrandparentClass() {
        assertThat(EntityTypes.entityOf(Child.class)).isEqualTo(Direct.class);
    }

    @Test
    void superclassIsFoundBeforeItsOwnSupertypes() {
        assertThat(EntityTypes.entityOf(Parent.class)).isEqualTo(Grandparent.class);
    }

    @Test
    void annotatedClassIsItsOwnEntity() {
        assertThat(EntityTypes.entityOf(Annotated.class)).isEqualTo(Annotated.class);
        assertThat(EntityTypes.entityOf(Plain.class)).isNull();
    }
}
