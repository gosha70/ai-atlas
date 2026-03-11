/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.model.FieldModel;
import com.egoge.ai.atlas.processor.model.FieldModel.CollectionKind;
import com.egoge.ai.atlas.processor.util.VersionSelector;
import com.palantir.javapoet.TypeName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldVersionSelectorTest {

    private static FieldModel field(int sinceVersion, int removedInVersion, int deprecatedSinceVersion) {
        return new FieldModel(
                "testField", "testField", TypeName.get(String.class),
                "", false, true, false, List.of(),
                CollectionKind.NONE, null, null,
                sinceVersion, removedInVersion, deprecatedSinceVersion, "");
    }

    // ---- isFieldActive tests ----

    @Test
    void defaultField_alwaysActive() {
        FieldModel f = field(1, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isFieldActive(f, 1)).isTrue();
        assertThat(VersionSelector.isFieldActive(f, 99)).isTrue();
    }

    @Test
    void fieldNotYetIntroduced_notActive() {
        FieldModel f = field(2, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isFieldActive(f, 1)).isFalse();
    }

    @Test
    void fieldJustIntroduced_active() {
        FieldModel f = field(2, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isFieldActive(f, 2)).isTrue();
    }

    @Test
    void fieldRemoved_notActive() {
        // removedInVersion=3 means EXCLUDED at major=3 (exclusive/half-open semantics)
        FieldModel f = field(1, 3, 0);
        assertThat(VersionSelector.isFieldActive(f, 3)).isFalse();
    }

    @Test
    void fieldAtRemovalBoundaryMinusOne_active() {
        FieldModel f = field(1, 3, 0);
        assertThat(VersionSelector.isFieldActive(f, 2)).isTrue();
    }

    @Test
    void singleVersionField_active() {
        // since=2, removed=3 → active only at major=2
        FieldModel f = field(2, 3, 0);
        assertThat(VersionSelector.isFieldActive(f, 2)).isTrue();
    }

    @Test
    void singleVersionField_notActiveBefore() {
        FieldModel f = field(2, 3, 0);
        assertThat(VersionSelector.isFieldActive(f, 1)).isFalse();
    }

    @Test
    void singleVersionField_notActiveAtRemoval() {
        FieldModel f = field(2, 3, 0);
        assertThat(VersionSelector.isFieldActive(f, 3)).isFalse();
    }

    // ---- isFieldDeprecated tests ----

    @Test
    void defaultField_notDeprecated() {
        FieldModel f = field(1, Integer.MAX_VALUE, 0);
        assertThat(VersionSelector.isFieldDeprecated(f, 1)).isFalse();
        assertThat(VersionSelector.isFieldDeprecated(f, 99)).isFalse();
    }

    @Test
    void fieldDeprecatedInFuture_notDeprecatedNow() {
        FieldModel f = field(1, Integer.MAX_VALUE, 3);
        assertThat(VersionSelector.isFieldDeprecated(f, 2)).isFalse();
    }

    @Test
    void fieldDeprecatedAtBoundary_deprecated() {
        FieldModel f = field(1, Integer.MAX_VALUE, 2);
        assertThat(VersionSelector.isFieldDeprecated(f, 2)).isTrue();
    }

    @Test
    void fieldDeprecatedInPast_deprecated() {
        FieldModel f = field(1, Integer.MAX_VALUE, 1);
        assertThat(VersionSelector.isFieldDeprecated(f, 2)).isTrue();
    }
}
