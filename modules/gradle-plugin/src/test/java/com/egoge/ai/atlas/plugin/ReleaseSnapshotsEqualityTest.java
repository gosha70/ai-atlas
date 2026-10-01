/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import com.egoge.ai.atlas.processor.release.ReleasePolicy;

import com.egoge.ai.atlas.processor.contract.ContractIr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.egoge.ai.atlas.plugin.ReleaseFixtures.CONTRACT_RESOURCES_JSON;
import static com.egoge.ai.atlas.plugin.ReleaseFixtures.TAG_NAME;
import static com.egoge.ai.atlas.plugin.ReleaseFixtures.NOTE;
import static com.egoge.ai.atlas.plugin.ReleaseFixtures.irJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReleaseSnapshots}: C5's canonical, not byte, equality between the build's emitted IR and
 * the accepted baseline.
 */
class ReleaseSnapshotsEqualityTest {

    /**
     * An {@code irVersion} 3 baseline of {@code Order} with {@code id}, and {@code find}, at major 1:
     * the same contract {@link ReleaseFixtures#irJson} compiles, but written before {@code irVersion}
     * 4 added a mapping's {@code status}/{@code parameterIn} and a return's {@code bound}. Migrating
     * this document gives exactly the values a fresh compile gives for this fixture (the RPC default
     * {@code 200}/query mapping, and no bound), so it is canonically, but not byte, equal to it.
     */
    private static final String IR_VERSION_3 = """
            {
              "irVersion": 3,
              "apiBasePath": "/api",
              "apiMajor": 1,
              "entities": [
                {
                  "className": "test.Order",
                  "dtoName": "OrderDto",
                  "dtoPackage": "test.generated",
                  "displayName": "Order",
                  "description": "An order",
                  "includeTypeInfo": true,
                  "fields": [
                    {
                      "name": "id",
                      "displayName": "id",
                      "javaType": "java.lang.Long",
                      "collectionKind": "NONE",
                      "elementType": null,
                      "typeHint": null,
                      "reference": null,
                      "enumType": false,
                      "allowedValues": [],
                      "openEnum": false,
                      "sensitive": false,
                      "checkCircularReference": true,
                      "description": "Id",
                      "constraints": {},
                      "channels": ["AI", "API"],
                      "lifecycle": {
                        "sinceVersion": 1,
                        "removedInVersion": 2147483647,
                        "deprecatedSinceVersion": 0,
                        "deprecatedMessage": ""
                      }
                    }
                  ]
                }
              ],
              "operations": [
                {
                  "service": "test.OrderService",
                  "method": "find",
                  "toolName": "find",
                  "channels": ["AI", "API"],
                  "description": "Finds an order by id",
                  "rest": {
                    "httpMethod": "POST",
                    "path": "/order-service/find"
                  },
                  "parameters": [
                    {
                      "name": "id",
                      "javaType": "java.lang.Long",
                      "description": "",
                      "enumConstants": [],
                      "required": true,
                      "constraints": {}
                    }
                  ],
                  "returns": {
                    "javaType": "test.Order",
                    "returnKind": "NONE",
                    "returnType": "test.Order",
                    "reference": {
                      "entity": "test.Order",
                      "dto": "test.generated.OrderDto"
                    }
                  },
                  "hints": {},
                  "lifecycle": {
                    "apiSince": 1,
                    "apiUntil": 2147483647,
                    "apiDeprecatedSince": 0,
                    "apiReplacement": ""
                  }
                }
              ]
            }
            """;

    @TempDir
    Path dir;
    Path baseline;
    Path releases;
    Path changelog;

    @BeforeEach
    void paths() {
        baseline = dir.resolve(".atlas/api.ir.json");
        releases = dir.resolve(".atlas/releases");
        changelog = dir.resolve(".atlas/CHANGELOG.md");
    }

    @Test
    void aBaselineAtAnOlderIrVersionCanonicallyEqualToTheBuildReleases() throws Exception {
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, IR_VERSION_3, StandardCharsets.UTF_8);
        byte[] emitted = irJson(1, "").getBytes(StandardCharsets.UTF_8);

        ReleaseSnapshots.Outcome outcome = ReleaseSnapshots.release(releases, changelog,
                new ReleaseSnapshots.Request("1.0.0", false, ReleasePolicy.Policy.DEFAULT, baseline, emitted,
                        Map.of(), CONTRACT_RESOURCES_JSON, TAG_NAME, Set.of()));

        assertThat(outcome.directory()).isEqualTo(releases.resolve("1.0.0"));
        assertThat(Files.readString(releases.resolve("1.0.0/api.ir.json")))
                .as("the snapshot keeps the baseline's own bytes, not the emitted ones")
                .isEqualTo(IR_VERSION_3);
        assertThat(Files.readAllBytes(releases.resolve("1.0.0/api.ir.json")))
                .isEqualTo(IR_VERSION_3.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aSemanticDifferenceFromTheBaselineFailsNamingAtlasAccept() throws Exception {
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, IR_VERSION_3, StandardCharsets.UTF_8);
        byte[] emitted = irJson(1, NOTE).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ReleaseSnapshots.release(releases, changelog,
                new ReleaseSnapshots.Request("1.0.0", false, ReleasePolicy.Policy.DEFAULT, baseline, emitted,
                        Map.of(), CONTRACT_RESOURCES_JSON, TAG_NAME, Set.of())))
                .isInstanceOf(ReleaseSnapshots.ReleaseException.class)
                .hasMessageContaining("The contract the build emitted differs from the baseline " + baseline)
                .hasMessageContaining("run atlasAccept, then release");
        assertThat(releases).doesNotExist();
    }

    @Test
    void contractIrHasNoArrayComponentsAnywhereInItsTree() {
        assertNoArrayComponents(ContractIr.class, new HashSet<>());
    }

    /**
     * A {@code record}'s equality is only structural (and its bytes-independent equals only trusted)
     * when no component anywhere in its tree is an array: an array component compares by reference,
     * not content, which would silently break canonical equality. Walks every record component,
     * recursing into nested record types and into the element type of a {@code List<T>} component.
     */
    private static void assertNoArrayComponents(Class<?> type, Set<Class<?>> visited) {
        if (!type.isRecord() || !visited.add(type)) {
            return;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            assertThat(component.getType().isArray())
                    .as("record component %s#%s must not be an array component", type.getSimpleName(),
                            component.getName())
                    .isFalse();
            if (component.getType().isRecord()) {
                assertNoArrayComponents(component.getType(), visited);
            }
            if (List.class.isAssignableFrom(component.getType())
                    && component.getGenericType() instanceof ParameterizedType parameterized) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?> elementType && elementType.isRecord()) {
                    assertNoArrayComponents(elementType, visited);
                }
            }
        }
    }
}
