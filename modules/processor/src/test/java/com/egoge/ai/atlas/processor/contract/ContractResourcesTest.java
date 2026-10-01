/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractResources.Manifest;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ContractResources}: the reserved paths and the {@link Manifest} format (D2.7). */
class ContractResourcesTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final EffectiveOptions CONFIG = new EffectiveOptions("/api", 2, "2.0.0", true, false, false);

    @Test
    void reservedMatcherAcceptsVersionedOpenApiDocuments() {
        assertThat(ContractResources.isReserved("META-INF/openapi/openapi-v1.json")).isTrue();
        assertThat(ContractResources.isReserved("META-INF/openapi/openapi-v12.json")).isTrue();
    }

    @Test
    void reservedMatcherRejectsMalformedOrUnrelatedPaths() {
        assertThat(ContractResources.isReserved("META-INF/openapi/openapi-v0.json")).isFalse();
        assertThat(ContractResources.isReserved("META-INF/openapi/openapi-vx.json")).isFalse();
        assertThat(ContractResources.isReserved("META-INF/other/x.json")).isFalse();
        assertThat(ContractResources.isReserved("META-INF/ai-atlas/something-else.json")).isFalse();
    }

    @Test
    void reservedMatcherAcceptsTheFixedPaths() {
        assertThat(ContractResources.isReserved(ContractIr.RESOURCE_PATH)).isTrue();
        assertThat(ContractResources.isReserved(ContractResources.MANIFEST_PATH)).isTrue();
        assertThat(ContractResources.isReserved("META-INF/openapi/openapi.json")).isTrue();
    }

    @Test
    void writeThenReadRoundTripsForADeclaredContract() {
        Manifest manifest = new Manifest("declared", CONFIG, new TreeMap<>(java.util.Map.of(
                ContractIr.RESOURCE_PATH, A, "META-INF/openapi/openapi-v2.json", B)));

        String written = manifest.write();
        Manifest read = Manifest.read(written);

        assertThat(read).isEqualTo(manifest);
        assertThat(read.write()).isEqualTo(written);
    }

    @Test
    void theCollectionsOptionIsRecordedAndReadBack() {
        EffectiveOptions collections = new EffectiveOptions("/api", 1, "1.0.0", false, false, true);
        Manifest manifest = new Manifest("declared", collections, new TreeMap<>());

        assertThat(manifest.write()).contains("\"ai.atlas.collections\": true");
        assertThat(Manifest.read(manifest.write()).configuration().collections()).isTrue();
    }

    @Test
    void writeThenReadRoundTripsForAnEmptyContract() {
        Manifest manifest = new Manifest("empty", CONFIG, new TreeMap<>(java.util.Map.of(ContractIr.RESOURCE_PATH, A)));

        String written = manifest.write();
        Manifest read = Manifest.read(written);

        assertThat(read).isEqualTo(manifest);
        assertThat(read.write()).isEqualTo(written);
    }

    @Test
    void writesTheCanonicalShape() {
        Manifest manifest = new Manifest("declared", CONFIG, new TreeMap<>(java.util.Map.of(
                ContractIr.RESOURCE_PATH, A, "META-INF/openapi/openapi-v2.json", B)));

        assertThat(manifest.write()).isEqualTo("""
                {
                  "manifestVersion": 1,
                  "contract": "declared",
                  "configuration": {
                    "ai.atlas.api.basePath": "/api",
                    "ai.atlas.api.major": 2,
                    "ai.atlas.collections": false,
                    "ai.atlas.constraints": true,
                    "ai.atlas.openapi.infoVersion": "2.0.0",
                    "ai.atlas.projections": false
                  },
                  "artifacts": {
                    "META-INF/ai-atlas/api.ir.json": "%s",
                    "META-INF/openapi/openapi-v2.json": "%s"
                  }
                }
                """.formatted(A, B));
    }

    @Test
    void strictReadRejectsUnknownContractValue() {
        String bogus = new Manifest("declared", CONFIG, new TreeMap<>(java.util.Map.of(ContractIr.RESOURCE_PATH, A)))
                .write().replace("\"contract\": \"declared\"", "\"contract\": \"bogus\"");

        assertThatThrownBy(() -> Manifest.read(bogus)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'contract'");
    }

    @Test
    void strictReadRejectsANewerManifestVersion() {
        String newer = new Manifest("declared", CONFIG, new TreeMap<>(java.util.Map.of(ContractIr.RESOURCE_PATH, A)))
                .write().replace("\"manifestVersion\": 1", "\"manifestVersion\": 2");

        assertThatThrownBy(() -> Manifest.read(newer)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("written by a newer ai-atlas");
    }

    @Test
    void strictReadRejectsMalformedDigests() {
        String upper = new Manifest("declared", CONFIG, new TreeMap<>(java.util.Map.of(ContractIr.RESOURCE_PATH, A)))
                .write().replace(A, A.toUpperCase());

        assertThatThrownBy(() -> Manifest.read(upper)).hasMessageContaining("lowercase SHA-256");
    }

    private static final EffectiveOptions NO_CONSTRAINTS = new EffectiveOptions("/api", 2, "2.0.0", false, false, false);

    @Test
    void requiredAlwaysIncludesTheIr() {
        Manifest manifest = new Manifest("declared", NO_CONSTRAINTS, new TreeMap<>());

        assertThat(ContractResources.required(manifest, false)).containsExactly(ContractIr.RESOURCE_PATH);
    }

    @Test
    void requiredIncludesTheVersionedOpenApiWhenTheIrIsNonEmpty() {
        Manifest manifest = new Manifest("declared", NO_CONSTRAINTS, new TreeMap<>());

        assertThat(ContractResources.required(manifest, true)).containsExactlyInAnyOrder(
                ContractIr.RESOURCE_PATH, "META-INF/openapi/openapi-v2.json");
    }

    @Test
    void requiredIncludesMcpToolsWhenConstraintsIsOn() {
        EffectiveOptions noConstraints = new EffectiveOptions("/api", 1, "1.0.0", false, false, false);
        Manifest withConstraints = new Manifest("declared", CONFIG, new TreeMap<>());
        Manifest withoutConstraints = new Manifest("declared", noConstraints, new TreeMap<>());

        assertThat(ContractResources.required(withConstraints, false))
                .contains("META-INF/ai-atlas/mcp-tools.json");
        assertThat(ContractResources.required(withoutConstraints, false))
                .doesNotContain("META-INF/ai-atlas/mcp-tools.json");
    }

    @Test
    void snapshottedKeepsOnlyTheIrVersionedOpenApiAndMcpTools() {
        Manifest manifest = new Manifest("declared", CONFIG, new TreeMap<>(java.util.Map.of(
                ContractIr.RESOURCE_PATH, A,
                "META-INF/openapi/openapi-v2.json", B,
                "META-INF/openapi/openapi.json", A,
                "META-INF/ai-atlas/mcp-tools.json", B,
                "META-INF/ai-atlas/api-version.properties", A,
                "META-INF/ai-atlas/contract-diff.json", A,
                "META-INF/ai-atlas/deprecation-manifest.json", A)));

        assertThat(ContractResources.snapshotted(manifest)).containsExactlyInAnyOrder(
                ContractIr.RESOURCE_PATH, "META-INF/openapi/openapi-v2.json", "META-INF/ai-atlas/mcp-tools.json");
    }
}
