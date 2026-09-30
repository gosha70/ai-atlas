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
 * contract. The single-release and basic cases; the multi-release, cross-version deprecation
 * policy cases are {@link ReleasePolicyFunctionalTest} (D5).
 *
 * <p>Every successful release is committed and tagged in a throwaway repository ({@link
 * GitFixture}), as F1 (D3, D4) requires a proved tag for any release already on disk before the
 * next one is made.
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
    private static final String NOTE = """
            @AgenticField(description = "A note"%s) private String note;
            public String getNote() { return note; }
            """;
    private static final String ROOT_CHANGELOG = "# Changelog\n\n## [Unreleased]\n- Hand-written.\n";

    @TempDir
    File projectDir;

    GitFixture git;

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
        git = new GitFixture(projectDir);
    }

    @Test
    void aFirstReleaseSnapshotsTheAcceptedContract() throws IOException {
        BuildResult result = releaseAndTag("1.0.0");

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
                .contains("\"tagName\": \"v1.0.0\"", "\"minDeprecatedReleases\": 1", "\"minApiMajorAdvance\": 1",
                        "\"failOnBreaking\": true", "\"api.ir.json\": \"", "\"openapi-v1.json\": \"");
        assertThat(Files.readString(atlas("CHANGELOG.md"))).startsWith("# Contract changelog\n")
                .contains("## 1.0.0 (API major 1)");
        assertThat(Files.readString(projectDir.toPath().resolve("CHANGELOG.md"))).isEqualTo(ROOT_CHANGELOG);
        assertThat(result.getOutput()).contains("Released contract 1.0.0 (API major 1)");
        assertThat(files(atlas("releases"))).containsExactly("1.0.0");
    }

    @Test
    void releasingAVersionAgainFailsAndChangesNothing() throws IOException {
        releaseAndTag("1.0.0");
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

        BuildResult result = runner("classes", "agenticRelease", "-Pversion=1.2.0-SNAPSHOT").buildAndFail();

        assertThat(result.getOutput()).contains("Version '1.2.0-SNAPSHOT' is a SNAPSHOT, and a SNAPSHOT is never"
                + " released: its contract can still change under the same name. Release 1.2.0 instead.",
                "Set agentic { releaseVersion }, which defaults to the project version.");
        assertThat(atlas("releases")).doesNotExist();
    }

    @Test
    void aVersionBelowTheLatestReleaseIsRefused() {
        releaseAndTag("1.1.0");

        assertThat(release("1.0.5").buildAndFail().getOutput())
                .contains("Version 1.0.5 is not above the latest release 1.1.0");
    }

    @Test
    void aContractThatDiffersFromTheBaselineIsNotReleased() throws IOException {
        releaseAndTag("1.0.0");
        order(NOTE.formatted(""));

        // The gate passes an added field, but the baseline does not carry it yet
        BuildResult result = runner("classes", "agenticRelease", "-Pversion=1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("The contract the build emitted differs from the baseline",
                "run atlasAccept, then release");
        assertThat(releaseDir("1.1.0")).doesNotExist();
        assertThat(Files.readString(atlas("api.ir.json"))).doesNotContain("\"note\"");
    }

    @Test
    void theVersionCanBeRequiredToTrackTheApiMajor() {
        append("agentic { releaseVersionTracksApiMajor.set(true) }\n");

        BuildResult result = release("2.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("Version 2.0.0 has major 2, but the contract's apiMajor is 1");
        releaseAndTag("1.0.0");
    }

    @Test
    void releasesAreByteForByteDeterministic() throws IOException {
        // Only 1.0.0 needs a proved tag: releasing 1.1.0 twice (once recreated after deletion)
        // never needs its own tag, since nothing is released after it in this test.
        releaseAndTag("1.0.0");
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
        releaseAndTag("1.0.0");
        assertThat(runner("check").build().task(":agenticReleaseCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Path ir = releaseDir("1.0.0").resolve("api.ir.json");
        Files.writeString(ir, Files.readString(ir).replace("\"Id\"", "\"Identifier\""));

        BuildResult result = runner("check").buildAndFail();

        // Gradle reports the resolved path: on macOS the temporary directory's /var is /private/var
        assertThat(result.getOutput()).contains("Released file " + ir.toRealPath() + " was modified after release",
                "restore it from version control");
        assertThat(release("1.1.0").buildAndFail().getOutput()).isNotEmpty();
    }

    @Test
    void checkMatchesTheBuildWithAGivenReleaseVersion() throws IOException {
        releaseAndTag("1.0.0");

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
    GradleRunner release(String version, String... extra) {
        List<String> accept = new ArrayList<>(List.of("atlasAccept"));
        accept.addAll(List.of(extra));
        runner(accept.toArray(String[]::new)).build();
        List<String> release = new ArrayList<>(List.of("classes", "agenticRelease", "-Pversion=" + version));
        release.addAll(List.of(extra));
        return runner(release.toArray(String[]::new));
    }

    /** Releases {@code version}, then commits and tags it as {@code v<version>} (F1). */
    BuildResult releaseAndTag(String version, String... extra) {
        BuildResult result = release(version, extra).build();
        git.commitAndTag("v" + version);
        return result;
    }

    void order(String members) throws IOException {
        write(ORDER, ORDER_SOURCE.formatted(members));
    }

    Path atlas(String path) {
        return projectDir.toPath().resolve(".atlas/" + path);
    }

    Path releaseDir(String version) {
        return atlas("releases/" + version);
    }

    static List<String> files(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    static Map<String, byte[]> contents(Path dir) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : files(dir)) {
            result.put(name, Files.readAllBytes(dir.resolve(name)));
        }
        return result;
    }

    static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    void append(String buildScript) {
        try {
            Files.writeString(projectDir.toPath().resolve("build.gradle.kts"), buildScript, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    GradleRunner runner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }
}
