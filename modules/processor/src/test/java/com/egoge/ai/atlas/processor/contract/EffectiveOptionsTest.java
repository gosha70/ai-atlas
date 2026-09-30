/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EffectiveOptions#fromArguments}: a plain last-value-wins map, as the Gradle plugin builds
 * from {@code compileJava}'s arguments, read as the processor reads its options.
 */
class EffectiveOptionsTest {

    @Test
    void fromArgumentsAppliesTheDefaults() {
        assertThat(EffectiveOptions.fromArguments(Map.of()))
                .isEqualTo(new EffectiveOptions("/api", 1, "1.0.0", false, false, false));
    }

    @Test
    void fromArgumentsHonoursTheLastValueOfARepeatedArgument() {
        Map<String, String> lastWins = lastValueWins(List.of("-Aai.atlas.api.major=1", "-Aai.atlas.api.major=2"));

        EffectiveOptions options = EffectiveOptions.fromArguments(lastWins);

        assertThat(options.apiMajor()).isEqualTo(2);
        assertThat(options.openApiInfoVersion()).isEqualTo("2.0.0");
    }

    @Test
    void fromArgumentsReadsConstraintsAndProjections() {
        EffectiveOptions options = EffectiveOptions.fromArguments(
                Map.of("ai.atlas.constraints", "true", "ai.atlas.projections", "TRUE"));

        assertThat(options.constraints()).isTrue();
        assertThat(options.projections()).isTrue();
    }

    @Test
    void fromArgumentsReadsCollections() {
        // The collections option changes the IR's bounds, OpenAPI and MCP schemas, so it is recorded too
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.collections", "true")).collections()).isTrue();
        assertThat(EffectiveOptions.fromArguments(Map.of()).collections()).isFalse();
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.collections", "on"))).isNull();
    }

    @Test
    void fromArgumentsRejectsAFlagThatIsNeitherTrueNorFalse() {
        // Where the processor never runs, as for an empty contract, nothing else would reject it
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.constraints", "yes"))).isNull();
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.projections", ""))).isNull();
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.constraints", "False")).constraints()).isFalse();
    }

    @Test
    void fromArgumentsValidatesAndNormalizesTheVersionOptionsAsVersionConfigDoes() {
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.api.basePath", "api"))).isNull();
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.api.major", "0"))).isNull();
        assertThat(EffectiveOptions.fromArguments(Map.of("ai.atlas.api.basePath", "/api/")).apiBasePath())
                .isEqualTo("/api");
    }

    private static Map<String, String> lastValueWins(List<String> arguments) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            result.put(argument.substring(2, equals), argument.substring(equals + 1));
        }
        return result;
    }
}
