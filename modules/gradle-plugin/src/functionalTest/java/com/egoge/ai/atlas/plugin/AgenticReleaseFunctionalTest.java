/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code agenticRelease} and {@code agenticReleaseCheck} (epic #23 §10): immutable released
 * contracts, their changelog and deprecation policy, and their verification in {@code check}.
 * Each release is accepted with {@code atlasAccept} first, as the task releases only the accepted
 * contract.
 */
class AgenticReleaseFunctionalTest {

    private static final String ORDER = "src/main/java/test/Order.java";
    private static final String SERVICE = "src/main/java/test/OrderService.java";
    private static final String ORDER_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "An order")
            public class Order {
                @AgenticField(description = "Id") private Long id;
                public Long getId() { return id; }
                %s
            }
            """;
    private static final String LEGACY = """
            @AgenticField(description = "Legacy code"%s) private String legacy;
            public String getLegacy() { return legacy; }
            """;
    private static final String NOTE = """
            @AgenticField(description = "A note"%s) private String note;
            public String getNote() { return note; }
            """;
    private static final String ROOT_CHANGELOG = "# Changelog\n\n## [Unreleased]\n- Hand-written.\n";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        // agentic { version } pins the ai-atlas dependencies; releaseVersion defaults to the project version
        write("build.gradle.kts", """
                plugins {
                    id("com.egoge.ai-atlas")
                }

                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }

                agentic {
                    version.set("%s")
                    apiMajorVersion.set(providers.gradleProperty("apiMajor").map { it.toInt() }.orElse(1))
                }
                """.formatted(repo, System.getProperty("ai.atlas.functionalTest.version")));
        write("CHANGELOG.md", ROOT_CHANGELOG);
        order("");
        write(SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Orders", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Finds an order by id")
                    public Order find(Long id) { return null; }
                }
                """);
    }

    @Test
    void aFirstReleaseSnapshotsTheAcceptedContract() throws IOException {
        BuildResult result = release("1.0.0").build();

        Path dir = releaseDir("1.0.0");
        assertThat(files(dir)).containsExactly("CHANGELOG.md", "api.ir.json", "contract-diff.json",
                "openapi-v1.json", "release.json");
        assertThat(Files.readAllBytes(dir.resolve("api.ir.json"))).isEqualTo(Files.readAllBytes(atlas("api.ir.json")));
        assertThat(Files.readString(dir.resolve("openapi-v1.json"))).contains("\"/api/v1/order-service/find\"");
        assertThat(Files.readString(dir.resolve("CHANGELOG.md"))).isEqualTo("""
                ## 1.0.0 (API major 1)

                First release of this contract.

                ### Added

                - `field test.Order#id` (output, `java.lang.Long`)
                - `operation test.OrderService#find(java.lang.Long)` (input)
                """);
        String manifest = Files.readString(dir.resolve("release.json"));
        assertThat(manifest).startsWith("{\n  \"manifestVersion\": 1,\n  \"version\": \"1.0.0\",\n  \"apiMajor\": 1,\n")
                .contains("\"previous\": null", "\"minDeprecatedReleases\": 1", "\"minApiMajorAdvance\": 1",
                        "\"failOnBreaking\": true",
                        "\"api.ir.json\": \"", "\"openapi-v1.json\": \"");
        assertThat(Files.readString(atlas("CHANGELOG.md"))).startsWith("# Contract changelog\n")
                .contains("## 1.0.0 (API major 1)");
        assertThat(Files.readString(projectDir.toPath().resolve("CHANGELOG.md"))).isEqualTo(ROOT_CHANGELOG);
        assertThat(result.getOutput()).contains("Released contract 1.0.0 (API major 1)");
        assertThat(files(atlas("releases"))).containsExactly("1.0.0");
    }

    @Test
    void aSecondCompatibleReleaseIsComparedWithTheFirst() throws IOException {
        release("1.0.0").build();
        order(NOTE.formatted(""));

        release("1.1.0").build();

        assertThat(Files.readString(releaseDir("1.1.0").resolve("CHANGELOG.md"))).isEqualTo("""
                ## 1.1.0 (API major 1)

                Compared with 1.0.0 (API major 1).

                ### Added

                - `field test.Order#note` (output, `java.lang.String`)
                """);
        assertThat(Files.readString(releaseDir("1.1.0").resolve("contract-diff.json")))
                .contains("\"publishedMajor\": 1", "\"path\": \"field test.Order#note\"",
                        "\"classification\": \"compatible\"");
        assertThat(Files.readString(releaseDir("1.1.0").resolve("release.json"))).contains("\"previous\": \"1.0.0\"");
        String aggregate = Files.readString(atlas("CHANGELOG.md"));
        assertThat(aggregate.indexOf("## 1.1.0")).isPositive().isLessThan(aggregate.indexOf("## 1.0.0"));
        assertThat(Files.readString(projectDir.toPath().resolve("CHANGELOG.md"))).isEqualTo(ROOT_CHANGELOG);
    }

    @Test
    void releasingAVersionAgainFailsAndChangesNothing() throws IOException {
        release("1.0.0").build();
        Map<String, byte[]> before = contents(releaseDir("1.0.0"));
        order(NOTE.formatted(""));

        BuildResult again = release("1.0.0").buildAndFail();

        assertThat(again.getOutput()).contains("Version 1.0.0 is already released at",
                "Released contracts are immutable: release a new version instead.");
        Map<String, byte[]> after = contents(releaseDir("1.0.0"));
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((name, bytes) -> assertThat(after.get(name)).as(name).isEqualTo(bytes));
    }

    @Test
    void aSnapshotIsRefused() {
        runner("atlasAccept").build();

        BuildResult result = runner("agenticRelease", "-Pversion=1.2.0-SNAPSHOT").buildAndFail();

        assertThat(result.getOutput()).contains("Version '1.2.0-SNAPSHOT' is a SNAPSHOT, and a SNAPSHOT is never"
                + " released: its contract can still change under the same name. Release 1.2.0 instead.",
                "Set agentic { releaseVersion }, which defaults to the project version.");
        assertThat(atlas("releases")).doesNotExist();
    }

    @Test
    void aVersionBelowTheLatestReleaseIsRefused() {
        release("1.1.0").build();

        assertThat(release("1.0.5").buildAndFail().getOutput())
                .contains("Version 1.0.5 is not above the latest release 1.1.0");
    }

    @Test
    void aContractThatDiffersFromTheBaselineIsNotReleased() throws IOException {
        release("1.0.0").build();
        order(NOTE.formatted(""));

        // The gate passes an added field, but the baseline does not carry it yet
        BuildResult result = runner("agenticRelease", "-Pversion=1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("The contract the build emitted differs from the baseline",
                "run atlasAccept, then release");
        assertThat(releaseDir("1.1.0")).doesNotExist();
        assertThat(Files.readString(atlas("api.ir.json"))).doesNotContain("\"note\"");
    }

    @Test
    void aBreakingChangeInTheSameMajorFails() throws IOException {
        release("1.0.0").build();
        order(NOTE.formatted(""));
        release("1.1.0").build();
        order(NOTE.formatted("").replace("String note", "Integer note").replace("String getNote", "Integer getNote"));

        // atlasAccept accepts the breaking change; the release policy refuses it within major 1
        BuildResult result = release("1.2.0").buildAndFail();

        assertThat(result.getOutput()).contains("Release 1.2.0 violates the release policy",
                "field test.Order#note (javaType): breaking within API major 1, javaType java.lang.String →"
                        + " java.lang.Integer (output): The field's schema in responses changes.",
                "or release it under API major 2; or set failOnBreaking = false");
        assertThat(releaseDir("1.2.0")).doesNotExist();

        BuildResult nextMajor = release("2.0.0", "-PapiMajor=2").build();
        assertThat(nextMajor.getOutput()).contains("Released contract 2.0.0 (API major 2)");
        assertThat(Files.readString(releaseDir("2.0.0").resolve("CHANGELOG.md"))).contains("### Breaking",
                "- `field test.Order#note`: javaType `java.lang.String` → `java.lang.Integer` (output).");
    }

    @Test
    void aRemovalNeverReleasedDeprecatedFails() throws IOException {
        order(LEGACY.formatted(""));
        release("1.0.0").build();
        order("");

        // atlasAccept accepts the breaking removal; the release policy still refuses it
        BuildResult result = release("2.0.0", "-PapiMajor=2").buildAndFail();

        assertThat(result.getOutput()).contains("Release 2.0.0 violates the release policy",
                "field test.Order#legacy (removed): removed in API major 2, but never released as deprecated.",
                "declare @AgenticField(deprecatedSinceVersion = N)",
                "agentic { release { policy { minDeprecatedReleases; minApiMajorAdvance; failOnBreaking } } }");
        assertThat(releaseDir("2.0.0")).doesNotExist();
    }

    @Test
    void aRemovalAfterAReleasedDeprecationSatisfiesThePolicy() throws IOException {
        String deprecated = LEGACY.formatted(", deprecatedSinceVersion = 1, removedInVersion = 2,"
                + " deprecatedMessage = \"Use id\"");
        order(LEGACY.formatted(""));
        release("1.0.0").build();
        order(deprecated);
        release("1.1.0").build();
        assertThat(Files.readString(releaseDir("1.1.0").resolve("CHANGELOG.md"))).contains("""
                ### Deprecated

                - `field test.Order#legacy`: deprecated since major 1, removed in major 2. Use id
                """);

        release("2.0.0", "-PapiMajor=2").build();

        Path dir = releaseDir("2.0.0");
        assertThat(files(dir)).contains("openapi-v2.json").doesNotContain("openapi-v1.json");
        assertThat(Files.readString(dir.resolve("CHANGELOG.md"))).isEqualTo("""
                ## 2.0.0 (API major 2)

                Compared with 1.1.0 (API major 1).

                ### Removed

                - `field test.Order#legacy` (output), deprecated since major 1 (first released deprecated in 1.1.0, 1 release(s))
                """);
    }

    @Test
    void theDeprecationPolicyIsConfigurable() throws IOException {
        append("""
                agentic {
                    release {
                        policy {
                            minDeprecatedReleases.set(0)
                            minApiMajorAdvance.set(0)
                        }
                    }
                }
                """);
        order(LEGACY.formatted(""));
        release("1.0.0").build();
        order("");

        release("1.1.0").build();

        assertThat(Files.readString(releaseDir("1.1.0").resolve("release.json")))
                .contains("\"minDeprecatedReleases\": 0", "\"minApiMajorAdvance\": 0");
        assertThat(Files.readString(releaseDir("1.1.0").resolve("CHANGELOG.md")))
                .contains("- `field test.Order#legacy` (output), never released as deprecated");
    }

    @Test
    void aChannelLossIsARemoval() throws IOException {
        append("agentic { projections.set(true) }\n");
        order(NOTE.formatted(""));
        release("1.0.0").build();
        order(NOTE.formatted(", channels = AgenticExposed.Channel.API"));

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("field test.Order#note (channels.AI): loses a channel,"
                + " [AI, API] → [API], in API major 1, but never released as deprecated.",
                "A channel has no lifecycle of its own, so deprecate the whole field.");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    @Test
    void theVersionCanBeRequiredToTrackTheApiMajor() {
        append("agentic { releaseVersionTracksApiMajor.set(true) }\n");

        BuildResult result = release("2.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("Version 2.0.0 has major 2, but the contract's apiMajor is 1");
        release("1.0.0").build();
    }

    @Test
    void releasesAreByteForByteDeterministic() throws IOException {
        release("1.0.0").build();
        order(NOTE.formatted(""));
        release("1.1.0").build();
        Map<String, byte[]> first = contents(releaseDir("1.1.0"));
        byte[] aggregate = Files.readAllBytes(atlas("CHANGELOG.md"));

        deleteRecursively(releaseDir("1.1.0"));
        runner("clean").build();
        release("1.1.0").build();

        Map<String, byte[]> second = contents(releaseDir("1.1.0"));
        assertThat(second.keySet()).isEqualTo(first.keySet());
        first.forEach((name, bytes) -> assertThat(second.get(name)).as(name).isEqualTo(bytes));
        assertThat(Files.readAllBytes(atlas("CHANGELOG.md"))).isEqualTo(aggregate);
    }

    @Test
    void checkVerifiesTheDigestsOfEveryRelease() throws IOException {
        assertThat(runner("check").build().task(":agenticReleaseCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        release("1.0.0").build();
        assertThat(runner("check").build().task(":agenticReleaseCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Path ir = releaseDir("1.0.0").resolve("api.ir.json");
        Files.writeString(ir, Files.readString(ir).replace("\"Id\"", "\"Identifier\""));

        BuildResult result = runner("check").buildAndFail();

        // Gradle reports the resolved path: on macOS the temporary directory's /var is /private/var
        assertThat(result.getOutput()).contains("Released file " + ir.toRealPath() + " was modified after release",
                "restore it from version control");
        assertThat(release("1.1.0").buildAndFail().getOutput()).contains("was modified after release");
    }

    @Test
    void checkMatchesTheBuildWithAGivenReleaseVersion() throws IOException {
        release("1.0.0").build();

        BuildResult matches = runner("check", "agenticReleaseCheck", "--release-version=1.0.0").build();
        assertThat(matches.getOutput()).contains("The build's contract is the released contract 1.0.0.");

        order(NOTE.formatted(""));
        runner("atlasAccept").build();
        BuildResult differs = runner("agenticReleaseCheck", "--release-version=1.0.0").buildAndFail();
        assertThat(differs.getOutput()).contains("The contract the build emitted differs from the released contract",
                "1.0.0" + File.separator + "api.ir.json");
        assertThat(runner("agenticReleaseCheck", "--release-version=1.1.0").buildAndFail().getOutput())
                .contains("Version 1.1.0 is not released");

        append("agentic { release { checkVersion.set(\"1.0.0\") } }\n");
        assertThat(runner("check").buildAndFail().getOutput())
                .contains("The contract the build emitted differs from the released contract");
    }

    // ------------------------------------------------------------ helpers

    /** Accepts the current sources, then returns a runner releasing them as {@code version}. */
    private GradleRunner release(String version, String... extra) {
        List<String> accept = new ArrayList<>(List.of("atlasAccept"));
        accept.addAll(List.of(extra));
        runner(accept.toArray(String[]::new)).build();
        List<String> release = new ArrayList<>(List.of("agenticRelease", "-Pversion=" + version));
        release.addAll(List.of(extra));
        return runner(release.toArray(String[]::new));
    }

    private void order(String members) throws IOException {
        write(ORDER, ORDER_SOURCE.formatted(members));
    }

    private Path atlas(String path) {
        return projectDir.toPath().resolve(".atlas/" + path);
    }

    private Path releaseDir(String version) {
        return atlas("releases/" + version);
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

    private void append(String buildScript) {
        try {
            Files.writeString(projectDir.toPath().resolve("build.gradle.kts"), buildScript, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private GradleRunner runner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }
}
