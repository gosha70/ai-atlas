/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPIKE (epic #23, Phase 5): functional tests of the {@code agenticRelease} prototype. Each release
 * is accepted with {@code atlasAccept} first, as the task releases only the accepted contract.
 */
class AgenticReleaseFunctionalTest {

    private static final String ORDER = "src/main/java/test/Order.java";
    private static final String SERVICE = "src/main/java/test/OrderService.java";
    private static final String ORDER_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "An order")
            public class Order {
                @AgenticField(description = "Id") private Long id;
                %s

                public Long getId() { return id; }
                %s
            }
            """;
    private static final String LEGACY_FIELD = "@AgenticField(description = \"Legacy code\"%s) private String legacy;";
    private static final String LEGACY_GETTER = "public String getLegacy() { return null; }";
    private static final String NOTE_FIELD = "@AgenticField(description = \"A note\") private String note;";
    private static final String NOTE_GETTER = "public String getNote() { return null; }";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
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
                    releaseVersion.set(providers.gradleProperty("releaseVersion"))
                }
                """.formatted(repo, System.getProperty("ai.atlas.functionalTest.version")));
        order("", "");
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
    void firstReleaseSnapshotsTheAcceptedIrAndOpenApiAndChangelog() throws IOException {
        BuildResult result = release("1.0.0").build();

        Path dir = releaseDir("1.0.0");
        assertThat(files(dir)).containsExactly("CHANGELOG.md", "api.ir.json", "contract-diff.json",
                "openapi-v1.json", "release.json");
        assertThat(Files.readAllBytes(dir.resolve("api.ir.json")))
                .isEqualTo(Files.readAllBytes(projectDir.toPath().resolve(".atlas/api.ir.json")));
        assertThat(Files.readString(dir.resolve("openapi-v1.json"))).contains("\"/api/v1/order-service/find\"");
        String changelog = Files.readString(dir.resolve("CHANGELOG.md"));
        assertThat(changelog).startsWith("## 1.0.0 (API major 1)\n\nFirst release of this contract.\n")
                .contains("### Added", "- `operation test.OrderService#find(java.lang.Long)` (input)",
                        "- `field test.Order#id` (output, `java.lang.Long`)");
        assertThat(Files.readString(dir.resolve("release.json"))).contains("\"version\": \"1.0.0\"",
                "\"apiMajor\": 1", "\"previous\": null", "\"api.ir.json\": \"");
        assertThat(result.getOutput()).contains("Released contract 1.0.0 (API major 1)");
    }

    @Test
    void releasingTheSameVersionTwiceFails() throws IOException {
        release("1.0.0").build();
        byte[] before = Files.readAllBytes(releaseDir("1.0.0").resolve("release.json"));

        BuildResult again = release("1.0.0").buildAndFail();

        assertThat(again.getOutput()).contains("Version 1.0.0 is already released at",
                "Released contracts are immutable");
        assertThat(Files.readAllBytes(releaseDir("1.0.0").resolve("release.json"))).isEqualTo(before);
    }

    @Test
    void aVersionBelowTheLatestReleaseAndASnapshotAreRefused() throws IOException {
        release("1.1.0").build();

        assertThat(release("1.0.5").buildAndFail().getOutput())
                .contains("Version 1.0.5 is not above the latest release 1.1.0");
        assertThat(release("1.2.0-SNAPSHOT").buildAndFail().getOutput())
                .contains("releases MAJOR.MINOR.PATCH versions only, got '1.2.0-SNAPSHOT'", "A SNAPSHOT is not a release");
    }

    @Test
    void aContractThatWasNotAcceptedIsNotReleased() throws IOException {
        release("1.0.0").build();
        order(NOTE_FIELD, NOTE_GETTER);

        // The gate passes an added field, but the baseline does not carry it yet
        BuildResult result = runner("agenticRelease", "-PreleaseVersion=1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("The contract the build emitted differs from the baseline",
                "run atlasAccept, then release");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    @Test
    void aCompatibleChangeIsReleasedWithItsChangelog() throws IOException {
        release("1.0.0").build();
        order(NOTE_FIELD, NOTE_GETTER);

        release("1.1.0").build();

        String changelog = Files.readString(releaseDir("1.1.0").resolve("CHANGELOG.md"));
        assertThat(changelog).isEqualTo("""
                ## 1.1.0 (API major 1)

                Compared with 1.0.0 (API major 1) by the ai-atlas compatibility gate.

                ### Added

                - `field test.Order#note` (output, `java.lang.String`)
                """);
        assertThat(Files.readString(releaseDir("1.1.0").resolve("contract-diff.json")))
                .contains("\"path\": \"field test.Order#note\"", "\"classification\": \"compatible\"");
        assertThat(Files.readString(releaseDir("1.1.0").resolve("release.json")))
                .contains("\"previous\": \"1.0.0\"");
    }

    @Test
    void aRemovalThatWasNeverReleasedDeprecatedFailsTheRelease() throws IOException {
        order(LEGACY_FIELD.formatted(""), LEGACY_GETTER);
        release("1.0.0").build();
        order("", "");

        // atlasAccept accepts the breaking removal; the release policy still refuses it
        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains(
                "Release 1.1.0 removes 1 previously published element(s) without satisfying the deprecation policy",
                "field test.Order#legacy: never released as deprecated",
                "declare @AgenticField(deprecatedSinceVersion = N)");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    @Test
    void aRemovalInTheMajorItWasDeprecatedInFailsTheDefaultPolicy() throws IOException {
        order(LEGACY_FIELD.formatted(", deprecatedSinceVersion = 1, deprecatedMessage = \"Use id\""),
                LEGACY_GETTER);
        release("1.0.0").build();
        order("", "");

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains(
                "field test.Order#legacy: deprecated since major 1 in 1 release(s), removed in major 1");
    }

    @Test
    void aDeclaredRemovalInTheNextMajorAfterADeprecatedReleaseIsReleased() throws IOException {
        order(LEGACY_FIELD.formatted(", deprecatedSinceVersion = 1, removedInVersion = 2,"
                + " deprecatedMessage = \"Use id\""), LEGACY_GETTER);
        release("1.0.0").build();
        assertThat(Files.readString(releaseDir("1.0.0").resolve("CHANGELOG.md")))
                .contains("- `field test.Order#legacy` (output, `java.lang.String`)");

        release("2.0.0", "-PapiMajor=2").build();

        Path dir = releaseDir("2.0.0");
        assertThat(files(dir)).contains("openapi-v2.json").doesNotContain("openapi-v1.json");
        assertThat(Files.readString(dir.resolve("CHANGELOG.md"))).isEqualTo("""
                ## 2.0.0 (API major 2)

                Compared with 1.0.0 (API major 1) by the ai-atlas compatibility gate.

                ### Removed

                - `field test.Order#legacy` (output), deprecated since major 1 (first released deprecated in 1.0.0, 1 release(s))
                """);
    }

    @Test
    void aDeprecationIsListedInTheChangelog() throws IOException {
        order(LEGACY_FIELD.formatted(""), LEGACY_GETTER);
        release("1.0.0").build();
        order(LEGACY_FIELD.formatted(", deprecatedSinceVersion = 1, removedInVersion = 2,"
                + " deprecatedMessage = \"Use id\""), LEGACY_GETTER);

        release("1.1.0").build();

        assertThat(Files.readString(releaseDir("1.1.0").resolve("CHANGELOG.md"))).contains("""
                ### Deprecated

                - `field test.Order#legacy`: deprecated since major 1, removed in major 2. Use id
                """);
    }

    @Test
    void aReleasedFileEditedAfterReleaseIsDetected() throws IOException {
        release("1.0.0").build();
        Path ir = releaseDir("1.0.0").resolve("api.ir.json");
        Files.writeString(ir, Files.readString(ir).replace("\"Id\"", "\"Identifier\""));

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("api.ir.json was modified or deleted after release");
    }

    @Test
    void releasesAreByteForByteDeterministic() throws IOException {
        release("1.0.0").build();
        order(NOTE_FIELD, NOTE_GETTER);
        release("1.1.0").build();
        Map<String, byte[]> first = contents(releaseDir("1.1.0"));

        deleteRecursively(releaseDir("1.1.0"));
        runner("clean").build();
        release("1.1.0").build();

        Map<String, byte[]> second = contents(releaseDir("1.1.0"));
        assertThat(second.keySet()).isEqualTo(first.keySet());
        first.forEach((name, bytes) -> assertThat(second.get(name)).as(name).isEqualTo(bytes));
    }

    // ------------------------------------------------------------ helpers

    /** Accepts the current sources, then releases them. */
    private GradleRunner release(String version, String... extra) {
        List<String> accept = new ArrayList<>(List.of("atlasAccept"));
        accept.addAll(List.of(extra));
        runner(accept.toArray(String[]::new)).build();
        List<String> release = new ArrayList<>(List.of("agenticRelease", "-PreleaseVersion=" + version));
        release.addAll(List.of(extra));
        return runner(release.toArray(String[]::new));
    }

    private void order(String field, String getter) throws IOException {
        write(ORDER, ORDER_SOURCE.formatted(field, getter));
    }

    private Path releaseDir(String version) {
        return projectDir.toPath().resolve(".atlas/releases/" + version);
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
