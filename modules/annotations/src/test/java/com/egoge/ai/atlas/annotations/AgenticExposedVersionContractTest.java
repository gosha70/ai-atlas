/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.annotations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticExposedVersionContractTest {

    @Test
    void inheritSentinel_isNegativeOne() {
        assertThat(AgenticExposed.INHERIT).isEqualTo(-1);
    }

    @Test
    void inheritStrSentinel_isNullChar() {
        assertThat(AgenticExposed.INHERIT_STR).isEqualTo("\0");
    }

    @Test
    void apiSince_defaultIsInherit() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("apiSince");
        assertThat(method.getDefaultValue()).isEqualTo(AgenticExposed.INHERIT);
    }

    @Test
    void apiUntil_defaultIsInherit() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("apiUntil");
        assertThat(method.getDefaultValue()).isEqualTo(AgenticExposed.INHERIT);
    }

    @Test
    void apiDeprecatedSince_defaultIsInherit() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("apiDeprecatedSince");
        assertThat(method.getDefaultValue()).isEqualTo(AgenticExposed.INHERIT);
    }

    @Test
    void apiReplacement_defaultIsInheritStr() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("apiReplacement");
        assertThat(method.getDefaultValue()).isEqualTo(AgenticExposed.INHERIT_STR);
    }

    @Test
    void channelEnum_containsInherit() {
        AgenticExposed.Channel[] values = AgenticExposed.Channel.values();
        assertThat(values).contains(AgenticExposed.Channel.INHERIT);
    }

    @Test
    void channels_defaultIsInherit() throws Exception {
        var method = AgenticExposed.class.getDeclaredMethod("channels");
        Object defaultValue = method.getDefaultValue();
        assertThat((AgenticExposed.Channel[]) defaultValue)
                .containsExactly(AgenticExposed.Channel.INHERIT);
    }
}
