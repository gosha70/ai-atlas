/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.model.ServiceModel;
import com.egoge.ai.atlas.processor.model.ServiceModel.MethodModel;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.TypeName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VersionSelectorTest {

    private static MethodModel method(int apiSince, int apiUntil, int apiDeprecatedSince) {
        return new MethodModel("test", "test", "desc",
                TypeName.get(String.class), null, null,
                ServiceModel.ReturnKind.NONE, List.of(), Set.of("AI", "API"),
                apiSince, apiUntil, apiDeprecatedSince, "");
    }

    // ---- isActive tests ----

    @Test
    void defaultMethod_alwaysActive() {
        MethodModel m = method(1, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isActive(m, 1)).isTrue();
        assertThat(VersionSelector.isActive(m, 99)).isTrue();
    }

    @Test
    void methodNotYetIntroduced_notActive() {
        MethodModel m = method(2, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isActive(m, 1)).isFalse();
    }

    @Test
    void methodJustIntroduced_active() {
        MethodModel m = method(2, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isActive(m, 2)).isTrue();
    }

    @Test
    void methodRetired_notActive() {
        MethodModel m = method(1, 2, 0);
        assertThat(VersionSelector.isActive(m, 3)).isFalse();
    }

    @Test
    void methodAtRetirementBoundary_active() {
        MethodModel m = method(1, 2, 0);
        assertThat(VersionSelector.isActive(m, 2)).isTrue();
    }

    @Test
    void singleVersionMethod_active() {
        MethodModel m = method(2, 2, 0);
        assertThat(VersionSelector.isActive(m, 2)).isTrue();
    }

    @Test
    void singleVersionMethod_notActiveBefore() {
        MethodModel m = method(2, 2, 0);
        assertThat(VersionSelector.isActive(m, 1)).isFalse();
    }

    @Test
    void singleVersionMethod_notActiveAfter() {
        MethodModel m = method(2, 2, 0);
        assertThat(VersionSelector.isActive(m, 3)).isFalse();
    }

    // ---- isDeprecated tests ----

    @Test
    void defaultMethod_notDeprecated() {
        MethodModel m = method(1, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isDeprecated(m, 1)).isFalse();
        assertThat(VersionSelector.isDeprecated(m, 99)).isFalse();
    }

    @Test
    void methodDeprecatedInFuture_notDeprecatedNow() {
        MethodModel m = method(1, Integer.MAX_VALUE, 3);
        assertThat(VersionSelector.isDeprecated(m, 2)).isFalse();
    }

    @Test
    void methodDeprecatedAtBoundary_deprecated() {
        MethodModel m = method(1, Integer.MAX_VALUE, 2);
        assertThat(VersionSelector.isDeprecated(m, 2)).isTrue();
    }

    @Test
    void methodDeprecatedInPast_deprecated() {
        MethodModel m = method(1, Integer.MAX_VALUE, 1);
        assertThat(VersionSelector.isDeprecated(m, 2)).isTrue();
    }
}
