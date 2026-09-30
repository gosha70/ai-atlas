/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ReleaseManifest}: canonical {@code release.json}, versioned by {@code manifestVersion}. */
class ReleaseManifestTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    private static final Map<String, Object> CONTRACT_RESOURCES = Map.of("contract", "empty");

    @Test
    void writesTheCanonicalFormWithSortedDigests() {
        ReleaseManifest manifest = new ReleaseManifest("1.1.0", 1, 3, "1.0.0", ReleasePolicy.Policy.DEFAULT,
                Map.of("api.ir.json", A, "CHANGELOG.md", B), CONTRACT_RESOURCES);

        assertThat(manifest.write()).isEqualTo("""
                {
                  "manifestVersion": 1,
                  "version": "1.1.0",
                  "apiMajor": 1,
                  "irVersion": 3,
                  "previous": "1.0.0",
                  "policy": {
                    "minDeprecatedReleases": 1,
                    "minApiMajorAdvance": 1,
                    "failOnBreaking": true
                  },
                  "sha256": {
                    "CHANGELOG.md": "%s",
                    "api.ir.json": "%s"
                  },
                  "contractResources": {
                    "contract": "empty"
                  }
                }
                """.formatted(B, A));
    }

    @Test
    void readsWhatItWrites() {
        ReleaseManifest manifest = new ReleaseManifest("2.0.0", 2, 1, null, new ReleasePolicy.Policy(2, 0, false),
                Map.of("api.ir.json", A), CONTRACT_RESOURCES);

        assertThat(ReleaseManifest.read(manifest.write())).isEqualTo(manifest);
    }

    @Test
    void refusesANewerManifestVersion() {
        String newer = new ReleaseManifest("1.0.0", 1, 3, null, ReleasePolicy.Policy.DEFAULT, Map.of("api.ir.json", A),
                CONTRACT_RESOURCES).write().replace("\"manifestVersion\": 1", "\"manifestVersion\": 2");

        assertThatThrownBy(() -> ReleaseManifest.read(newer)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("written by a newer ai-atlas");
    }

    @Test
    void refusesMalformedDigestsAndDocuments() {
        String upper = new ReleaseManifest("1.0.0", 1, 3, null, ReleasePolicy.Policy.DEFAULT, Map.of("api.ir.json", A),
                CONTRACT_RESOURCES).write().replace(A, A.toUpperCase());

        assertThatThrownBy(() -> ReleaseManifest.read(upper)).hasMessageContaining("lowercase SHA-256");
        assertThatThrownBy(() -> ReleaseManifest.read("[]")).hasMessageContaining("not a JSON object");
        assertThatThrownBy(() -> ReleaseManifest.read("{")).hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> ReleaseManifest.read("{\"manifestVersion\": 1}")).hasMessageContaining("'previous'");
    }

    @Test
    void readsTheIrVersionAsWritten() {
        assertThat(ReleaseManifest.irVersionOf("{\"irVersion\": 2, \"apiMajor\": 1}")).isEqualTo(2);
    }
}
