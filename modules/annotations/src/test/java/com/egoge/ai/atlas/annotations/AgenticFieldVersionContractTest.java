/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticFieldVersionContractTest {

    @Test
    void sinceVersion_defaultIsOne() throws Exception {
        var method = AgenticField.class.getDeclaredMethod("sinceVersion");
        assertThat(method.getDefaultValue()).isEqualTo(1);
    }

    @Test
    void removedInVersion_defaultIsMaxValue() throws Exception {
        var method = AgenticField.class.getDeclaredMethod("removedInVersion");
        assertThat(method.getDefaultValue()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void deprecatedSinceVersion_defaultIsZero() throws Exception {
        var method = AgenticField.class.getDeclaredMethod("deprecatedSinceVersion");
        assertThat(method.getDefaultValue()).isEqualTo(0);
    }

    @Test
    void deprecatedMessage_defaultIsEmpty() throws Exception {
        var method = AgenticField.class.getDeclaredMethod("deprecatedMessage");
        assertThat(method.getDefaultValue()).isEqualTo("");
    }
}
