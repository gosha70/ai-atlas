/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.EmptyContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.LEGACY;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.NOTE;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.irJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ContractRelease}: immutable snapshots of the accepted contract, and their verification. */
class ContractReleaseTest {

    /** An {@code irVersion} 1 release of {@code Order} with {@code id}, and {@code find}, at major 1. */
    private static final String IR_VERSION_1 = """
            {
              "irVersion": 1,
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
                  "id": "test.OrderService#find(java.lang.Long)",
                  "service": "test.OrderService",
                  "method": "find",
                  "toolName": "find",
                  "channels": [
                    "AI",
                    "API"
                  ],
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
                      "enumConstants": []
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
    void aFirstReleaseSnapshotsTheAcceptedContract() throws Exception {
        String ir = accept(irJson(1, ""));

        ContractRelease.Outcome outcome = ContractRelease.release(releases, changelog,
                request("1.0.0", ir, "{\"openapi\": \"3.0.3\"}\n", "{\"tools\": []}\n"));

        Path release = releases.resolve("1.0.0");
        assertThat(outcome.directory()).isEqualTo(release);
        assertThat(files(release)).containsExactly("CHANGELOG.md", "api.ir.json", "contract-diff.json",
                "mcp-tools.json", "openapi-v1.json", "release.json");
        assertThat(Files.readAllBytes(release.resolve("api.ir.json"))).isEqualTo(Files.readAllBytes(baseline));
        assertThat(Files.readString(release.resolve("openapi-v1.json"))).isEqualTo("{\"openapi\": \"3.0.3\"}\n");
        ReleaseManifest manifest = ReleaseManifest.read(Files.readString(release.resolve("release.json")));
        assertThat(manifest.version()).isEqualTo("1.0.0");
        assertThat(manifest.apiMajor()).isEqualTo(1);
        assertThat(manifest.previous()).isNull();
        assertThat(manifest.policy()).isEqualTo(ReleasePolicy.Policy.DEFAULT);
        assertThat(manifest.digests()).containsOnlyKeys("CHANGELOG.md", "api.ir.json", "contract-diff.json",
                "mcp-tools.json", "openapi-v1.json");
        assertThat(manifest.digests().get("api.ir.json"))
                .isEqualTo(ContractRelease.sha256(ir.getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readString(release.resolve("contract-diff.json"))).contains("\"publishedMajor\": 0",
                "\"path\": \"field test.Order#id\"", "\"change\": \"added\"");
        assertThat(outcome.changelog()).startsWith("## 1.0.0 (API major 1)\n\nFirst release of this contract.\n");
        assertThat(Files.readString(changelog)).startsWith(ReleaseChangelog.AGGREGATE_TITLE)
                .endsWith(outcome.changelog());
        assertThat(files(releases)).containsExactly("1.0.0");
    }

    @Test
    void optionalFilesAreLeftOutWhenNotGenerated() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));

        assertThat(files(releases.resolve("1.0.0"))).containsExactly("CHANGELOG.md", "api.ir.json",
                "contract-diff.json", "release.json");
    }

    @Test
    void aReleasedVersionIsNeverOverwritten() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        Map<String, byte[]> before = contents(releases.resolve("1.0.0"));
        String note = accept(irJson(1, NOTE));

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, request("1.0.0", note, null, null)))
                .isInstanceOf(ContractRelease.ReleaseException.class)
                .hasMessageContaining("Version 1.0.0 is already released at")
                .hasMessageContaining("release a new version instead");
        assertThat(contents(releases.resolve("1.0.0"))).containsOnlyKeys(before.keySet())
                .allSatisfy((name, bytes) -> assertThat(bytes).isEqualTo(before.get(name)));
    }

    @Test
    void aSnapshotAndOtherVersionsAreRefused() {
        String ir = accept(irJson(1, ""));

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, request("1.0.0-SNAPSHOT", ir, null, null)))
                .hasMessage("[ai-atlas] Version '1.0.0-SNAPSHOT' is a SNAPSHOT, and a SNAPSHOT is never released:"
                        + " its contract can still change under the same name. Release 1.0.0 instead.");
        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, request("1.0", ir, null, null)))
                .hasMessageContaining("is not a release version");
        assertThat(releases).doesNotExist();
    }

    @Test
    void aContractThatIsNotTheBaselineIsNotReleased() throws Exception {
        accept(irJson(1, ""));
        byte[] emitted = irJson(1, NOTE).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, new ContractRelease.Request("1.0.0",
                false, ReleasePolicy.Policy.DEFAULT, baseline, emitted, null, null)))
                .hasMessageContaining("The contract the build emitted differs from the baseline " + baseline)
                .hasMessageContaining("run atlasAccept, then release");
        Files.delete(baseline);
        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, new ContractRelease.Request("1.0.0",
                false, ReleasePolicy.Policy.DEFAULT, baseline, emitted, null, null)))
                .hasMessageContaining("No contract baseline at " + baseline);
        assertThat(releases).doesNotExist();
    }

    @Test
    void versionsAndMajorsNeverGoBackwards() throws Exception {
        ContractRelease.release(releases, changelog, request("1.1.0", accept(irJson(2, "")), null, null));

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog,
                request("1.0.5", accept(irJson(2, NOTE)), null, null)))
                .hasMessageContaining("Version 1.0.5 is not above the latest release 1.1.0");
        assertThatThrownBy(() -> ContractRelease.release(releases, changelog,
                request("1.2.0", accept(irJson(1, "")), null, null)))
                .hasMessageContaining("The contract's apiMajor 1 is below the apiMajor 2 of the latest release 1.1.0");
    }

    @Test
    void theVersionCanBeRequiredToTrackTheApiMajor() throws Exception {
        String ir = accept(irJson(2, ""));
        ContractRelease.Request request = new ContractRelease.Request("1.0.0", true, ReleasePolicy.Policy.DEFAULT,
                baseline, ir.getBytes(StandardCharsets.UTF_8), null, null);

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, request))
                .hasMessageContaining("Version 1.0.0 has major 1, but the contract's apiMajor is 2")
                .hasMessageContaining("releaseVersionTracksApiMajor");
        ContractRelease.release(releases, changelog, new ContractRelease.Request("2.0.0", true,
                ReleasePolicy.Policy.DEFAULT, baseline, ir.getBytes(StandardCharsets.UTF_8), null, null));
        assertThat(releases.resolve("2.0.0")).isDirectory();
    }

    @Test
    void aPolicyViolationFailsAndWritesNothing() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, LEGACY.formatted(""))),
                null, null));
        String aggregate = Files.readString(changelog);

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog,
                request("1.1.0", accept(irJson(1, "")), null, null)))
                .hasMessageContaining("Release 1.1.0 violates the release policy (released deprecated in at least 1"
                        + " release(s), removed at least 1 major(s) after deprecation, failOnBreaking = true) at 1"
                        + " element(s):")
                .hasMessageContaining("field test.Order#legacy (removed): removed in API major 1, but never released"
                        + " as deprecated");
        assertThat(files(releases)).containsExactly("1.0.0");
        assertThat(Files.readString(changelog)).isEqualTo(aggregate);
    }

    @Test
    void theAggregateChangelogListsEveryReleaseNewestFirst() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        ContractRelease.release(releases, changelog, request("1.1.0", accept(irJson(1, NOTE)), null, null));
        ContractRelease.release(releases, changelog, request("1.10.0", accept(irJson(1, NOTE)), null, null));

        String aggregate = Files.readString(changelog);
        assertThat(aggregate).isEqualTo(ReleaseChangelog.aggregate(List.of(
                Files.readString(releases.resolve("1.10.0/CHANGELOG.md")),
                Files.readString(releases.resolve("1.1.0/CHANGELOG.md")),
                Files.readString(releases.resolve("1.0.0/CHANGELOG.md")))));
        assertThat(aggregate.indexOf("## 1.10.0")).isLessThan(aggregate.indexOf("## 1.1.0"));
        assertThat(ReleaseManifest.read(Files.readString(releases.resolve("1.1.0/release.json"))).previous())
                .isEqualTo("1.0.0");
    }

    @Test
    void theSameInputsGiveTheSameBytes() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        ContractRelease.release(releases, changelog, request("1.1.0", accept(irJson(1, NOTE)), "{}\n", null));
        Map<String, byte[]> first = contents(releases.resolve("1.1.0"));
        byte[] aggregate = Files.readAllBytes(changelog);

        deleteRecursively(releases.resolve("1.1.0"));
        ContractRelease.release(releases, changelog, request("1.1.0", accept(irJson(1, NOTE)), "{}\n", null));

        Map<String, byte[]> second = contents(releases.resolve("1.1.0"));
        assertThat(second.keySet()).isEqualTo(first.keySet());
        first.forEach((name, bytes) -> assertThat(second.get(name)).as(name).isEqualTo(bytes));
        assertThat(Files.readAllBytes(changelog)).isEqualTo(aggregate);
    }

    @Test
    void aReleaseWithAnOlderIrVersionKeepsItsBytesAndIsComparedAfterMigration() throws Exception {
        Files.createDirectories(releases.resolve("1.0.0"));
        writeRelease(releases.resolve("1.0.0"), "1.0.0", IR_VERSION_1);

        ContractRelease.Outcome outcome = ContractRelease.release(releases, changelog,
                request("1.1.0", accept(irJson(1, NOTE)), null, null));

        assertThat(Files.readString(releases.resolve("1.0.0/api.ir.json"))).isEqualTo(IR_VERSION_1);
        assertThat(ReleaseHistory.history(releases)).hasSize(2);
        assertThat(outcome.changelog()).isEqualTo("""
                ## 1.1.0 (API major 1)

                Compared with 1.0.0 (API major 1).

                ### Added

                - `field test.Order#note` (output, `java.lang.String`)
                """);
        assertThat(ReleaseManifest.read(Files.readString(releases.resolve("1.0.0/release.json"))).irVersion())
                .isEqualTo(1);
    }

    @Test
    void checkReleasedMatchesTheBuildsContractWithTheReleasedOne() throws Exception {
        String ir = accept(irJson(1, ""));
        ContractRelease.release(releases, changelog, request("1.0.0", ir, null, null));

        ContractRelease.checkReleased(releases, "1.0.0", ir.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ContractRelease.checkReleased(releases, "1.0.0",
                irJson(1, NOTE).getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("The contract the build emitted differs from the released contract "
                        + releases.resolve("1.0.0/api.ir.json"));
        assertThatThrownBy(() -> ContractRelease.checkReleased(releases, "1.1.0", ir.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("Version 1.1.0 is not released");
        assertThatThrownBy(() -> ContractRelease.checkReleased(releases, "1.1.0-SNAPSHOT",
                ir.getBytes(StandardCharsets.UTF_8))).hasMessageContaining("is a SNAPSHOT");
    }

    @Test
    void anEmptyContractCanBeReleasedAndItsRemovalsArePoliced() throws Exception {
        ContractRelease.release(releases, changelog, request("1.0.0", accept(irJson(1, "")), null, null));
        String empty = accept(EmptyContract.json("/api", 1));

        assertThatThrownBy(() -> ContractRelease.release(releases, changelog, request("1.1.0", empty, null, null)))
                .hasMessageContaining("at 2 element(s)")
                .hasMessageContaining("field test.Order#id (removed)")
                .hasMessageContaining("operation test.OrderService#find(java.lang.Long) (removed)");
        ContractRelease.release(releases, changelog, new ContractRelease.Request("1.1.0", false,
                new ReleasePolicy.Policy(0, 0, true), baseline, empty.getBytes(StandardCharsets.UTF_8), null, null));
        assertThat(Files.readString(releases.resolve("1.1.0/CHANGELOG.md"))).contains("### Removed",
                "- `entity test.Order` (output)\n");
    }

    // ------------------------------------------------------------ helpers

    /** Writes {@code ir} as the baseline and returns it. */
    private String accept(String ir) {
        try {
            Files.createDirectories(baseline.getParent());
            Files.writeString(baseline, ir, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ir;
    }

    private ContractRelease.Request request(String version, String ir, String openApi, String mcpTools) {
        return new ContractRelease.Request(version, false, ReleasePolicy.Policy.DEFAULT, baseline,
                ir.getBytes(StandardCharsets.UTF_8), bytes(openApi), bytes(mcpTools));
    }

    private static byte[] bytes(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }

    /** A release as an earlier ai-atlas wrote it: its IR, a changelog and a manifest recording both. */
    private static void writeRelease(Path dir, String version, String ir) throws IOException {
        String section = "## " + version + " (API major 1)\n\nFirst release of this contract.\n";
        Files.writeString(dir.resolve("api.ir.json"), ir);
        Files.writeString(dir.resolve("CHANGELOG.md"), section);
        Files.writeString(dir.resolve("release.json"), new ReleaseManifest(version, 1,
                ReleaseManifest.irVersionOf(ir), null, ReleasePolicy.Policy.DEFAULT, Map.of(
                "api.ir.json", ContractRelease.sha256(ir.getBytes(StandardCharsets.UTF_8)),
                "CHANGELOG.md", ContractRelease.sha256(section.getBytes(StandardCharsets.UTF_8)))).write());
    }

    private static List<String> files(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static Map<String, byte[]> contents(Path dir) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : files(dir)) {
            result.put(name, Files.readAllBytes(dir.resolve(name)));
        }
        return result;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
