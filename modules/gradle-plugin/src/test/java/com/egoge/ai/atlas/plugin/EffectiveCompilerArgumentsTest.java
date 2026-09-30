/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveCompilerArgumentsTest {

    @Test
    void lastValueWinsWhenTheSameKeyAppearsTwice() {
        Map<String, String> options = EffectiveCompilerArguments.lastWins(
                List.of("-Aai.atlas.api.major=1", "-Aai.atlas.api.major=2"));

        assertThat(options).containsExactly(Map.entry("ai.atlas.api.major", "2"));
    }

    @Test
    void nonAArgumentsAreIgnored() {
        Map<String, String> options = EffectiveCompilerArguments.lastWins(
                List.of("-Xlint:all", "-parameters", "-Aai.atlas.api.major=1"));

        assertThat(options).containsExactly(Map.entry("ai.atlas.api.major", "1"));
    }

    @Test
    void aBareAOptionWithNoEqualsSignIsIgnored() {
        Map<String, String> options = EffectiveCompilerArguments.lastWins(List.of("-A", "-Aai.atlas.api.major=1"));

        assertThat(options).containsExactly(Map.entry("ai.atlas.api.major", "1"));
    }

    @Test
    void aValueContainingAnEqualsSignRoundTrips() {
        Map<String, String> options = EffectiveCompilerArguments.lastWins(
                List.of("-Aai.atlas.openapi.infoVersion=2.0.0=beta"));

        assertThat(options).containsExactly(Map.entry("ai.atlas.openapi.infoVersion", "2.0.0=beta"));
    }

    @Test
    void nullAndEmptyInputsGiveAnEmptyMap() {
        assertThat(EffectiveCompilerArguments.lastWins(null)).isEmpty();
        assertThat(EffectiveCompilerArguments.lastWins(List.of())).isEmpty();
    }
}
