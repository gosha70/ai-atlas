/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ReleaseVersion}: strict {@code MAJOR.MINOR.PATCH}, ordered numerically. */
class ReleaseVersionTest {

    @Test
    void parsesAndPrintsAReleaseVersion() {
        ReleaseVersion version = ReleaseVersion.parse("2.10.3");

        assertThat(version).isEqualTo(new ReleaseVersion(2, 10, 3));
        assertThat(version).hasToString("2.10.3");
    }

    @Test
    void ordersNumerically() {
        assertThat(ReleaseVersion.parse("1.10.0")).isGreaterThan(ReleaseVersion.parse("1.9.9"));
        assertThat(ReleaseVersion.parse("2.0.0")).isGreaterThan(ReleaseVersion.parse("1.99.99"));
        assertThat(ReleaseVersion.parse("1.0.1")).isGreaterThan(ReleaseVersion.parse("1.0.0"));
    }

    @Test
    void refusesASnapshotWithItsOwnMessage() {
        assertThatThrownBy(() -> ReleaseVersion.parse("1.2.0-SNAPSHOT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Version '1.2.0-SNAPSHOT' is a SNAPSHOT, and a SNAPSHOT is never released: its contract"
                        + " can still change under the same name. Release 1.2.0 instead");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2", "1", "v1.2.0", "1.2.0-rc.1", "1.2.0+build", "01.2.0", "1.2.0.0", "", " 1.2.0"})
    void refusesAnythingButMajorMinorPatch(String version) {
        assertThatThrownBy(() -> ReleaseVersion.parse(version))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not a release version: a release is MAJOR.MINOR.PATCH");
        assertThat(ReleaseVersion.matches(version)).isFalse();
    }

    @Test
    void refusesNull() {
        assertThatThrownBy(() -> ReleaseVersion.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
