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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's mandatory C4 regression, distinguishing the two paths byte for byte: the class
 * output is tampered from {@code compileJava}'s own {@code doLast}, so the tamper becomes part of
 * what {@code compileJava} itself considers correct — a second build leaves it {@code UP-TO-DATE},
 * exactly as a stale aggregating-processor leftover would, and nothing will ever regenerate it.
 * Against that stable, tampered output, {@code agenticRelease} must fail naming the tampered path,
 * and must never write, delete or modify anything: not the class output, not {@code
 * contract-resources.json}, no release snapshot directory, and no aggregate changelog (D2.6, D2.8,
 * AC2, AC4).
 */
class ReleaseResourcesFunctionalTest {

    private static final String ORDER = "src/main/java/test/Order.java";
    private static final String SERVICE = "src/main/java/test/OrderService.java";
    private static final String ORDER_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "An order")
            public class Order {
                @AgenticField(description = "Id") private Long id;
                public Long getId() { return id; }
            }
            """;
    private static final String SERVICE_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticExposed;

            @AgenticExposed(description = "Orders", returnType = Order.class)
            public class OrderService {
                @AgenticExposed(description = "Finds an order by id")
                public Order find(Long id) { return null; }
            }
            """;
    private static final String UNRELEASED_VERSION = "9.9.9";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"release-resources-test\"");
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        write("build.gradle.kts", """
                import org.gradle.api.tasks.compile.JavaCompile

                plugins {
                    id("com.egoge.ai-atlas")
                }

                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }

                agentic {
                    version.set("%s")
                }
                """.formatted(repo, System.getProperty("ai.atlas.functionalTest.version")));
        write(ORDER, ORDER_SOURCE);
        write(SERVICE, SERVICE_SOURCE);
    }

    @Test
    void aByteTamperedIntoTheIrAfterCompileFailsTheReleaseAndChangesNothing() throws IOException {
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    val destDir = destinationDirectory
                    doLast {
                        val ir = File(destDir.get().asFile, "META-INF/ai-atlas/api.ir.json")
                        ir.writeText(ir.readText() + " ")
                    }
                }
                """);

        assertReleaseNeverWrites("META-INF/ai-atlas/api.ir.json", "does not match the digest");
    }

    @Test
    void anUnlistedReservedFilePlantedAfterCompileFailsTheReleaseAndChangesNothing() throws IOException {
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    val destDir = destinationDirectory
                    doLast {
                        val stale = File(destDir.get().asFile, "META-INF/openapi/openapi-v99.json")
                        stale.parentFile.mkdirs()
                        stale.writeText("{}\\n")
                    }
                }
                """);

        assertReleaseNeverWrites("META-INF/openapi/openapi-v99.json", "clean");
    }

    @Test
    void anotherProcessorsResourceIsIgnored() throws IOException {
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    val destDir = destinationDirectory
                    doLast {
                        val foreign = File(destDir.get().asFile, "META-INF/foo/bar.json")
                        foreign.parentFile.mkdirs()
                        foreign.writeText("{}\\n")
                    }
                }
                """);
        runner("atlasAccept").build();

        BuildResult result = runner("agenticRelease", "-Pversion=1.0.0").build();

        assertThat(result.task(":agenticRelease").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(new File(projectDir, "build/classes/java/main/META-INF/foo/bar.json")).isFile();
    }

    @Test
    void anEmptyContractReleases() throws IOException {
        write(ORDER, "package test;\n\npublic class Order {\n}\n");
        write(SERVICE, "package test;\n\npublic class OrderService {\n}\n");
        runner("atlasAccept").build();

        BuildResult result = runner("agenticRelease", "-Pversion=1.0.0").build();

        assertThat(result.task(":agenticRelease").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Path dir = releaseDir("1.0.0");
        Path emittedIr = projectDir.toPath().resolve("build/classes/java/main/META-INF/ai-atlas/api.ir.json");
        assertThat(Files.readAllBytes(dir.resolve("api.ir.json"))).isEqualTo(Files.readAllBytes(emittedIr));
        assertThat(Files.readString(dir.resolve("release.json"))).contains("\"contract\": \"empty\"");
    }

    /**
     * Distinct from {@link #anUnlistedReservedFilePlantedAfterCompileFailsTheReleaseAndChangesNothing}:
     * there, the class output carries a {@code "declared"} manifest; here it carries the {@code
     * "empty"} one {@link com.egoge.ai.atlas.plugin.EmptyContractResourcesAction} writes, so this
     * pins that {@code ClassOutputResources} rejects an unlisted reserved file against that manifest
     * shape too (OQ-2 default).
     */
    @Test
    void anEmptyContractWithALeftoverReservedFileFailsTheRelease() throws IOException {
        write(ORDER, "package test;\n\npublic class Order {\n}\n");
        write(SERVICE, "package test;\n\npublic class OrderService {\n}\n");
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    val destDir = destinationDirectory
                    doLast {
                        val stale = File(destDir.get().asFile, "META-INF/openapi/openapi-v1.json")
                        stale.parentFile.mkdirs()
                        stale.writeText("{}\\n")
                    }
                }
                """);
        runner("atlasAccept").build();

        BuildResult result = runner("agenticRelease", "-Pversion=1.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("openapi-v1.json", "clean");
        assertThat(releaseDir("1.0.0")).doesNotExist();
    }

    /**
     * Compiles (with the build script's tamper already wired into {@code compileJava.doLast}), so
     * the tampered class output is stable and {@code UP-TO-DATE} on a second build; then runs {@code
     * agenticRelease} against that already-stable output, asserts it fails naming both {@code
     * messageFragments}, and that nothing changed: the class output's bytes, {@code
     * contract-resources.json}'s bytes, the absence of a release snapshot directory, and the absence
     * of the aggregate changelog, all exactly as they were right before the release attempt
     * (the owner's assertions 1-4).
     */
    private void assertReleaseNeverWrites(String... messageFragments) throws IOException {
        Path classesDir = projectDir.toPath().resolve("build/classes/java/main");
        Path manifestFile = classesDir.resolve("META-INF/ai-atlas/contract-resources.json");
        Path releaseDir = projectDir.toPath().resolve(".atlas/releases/" + UNRELEASED_VERSION);
        Path changelog = projectDir.toPath().resolve(".atlas/CHANGELOG.md");

        runner("classes").build();
        // A second, source-unchanged build stays up to date: nothing will regenerate the tamper.
        assertThat(runner("classes").build().task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(changelog).doesNotExist();
        byte[] classOutputBefore = concatenatedBytes(classesDir);
        byte[] manifestBefore = Files.readAllBytes(manifestFile);

        BuildResult result = runner("agenticRelease", "-Pversion=" + UNRELEASED_VERSION).buildAndFail();

        for (String fragment : messageFragments) {
            assertThat(result.getOutput()).contains(fragment);
        }
        // (1) the class output's bytes are unchanged; (2) contract-resources.json's bytes are unchanged.
        assertThat(concatenatedBytes(classesDir)).isEqualTo(classOutputBefore);
        assertThat(Files.readAllBytes(manifestFile)).isEqualTo(manifestBefore);
        // (3) no release snapshot directory was created.
        assertThat(releaseDir).doesNotExist();
        // (4) the aggregate changelog is still absent.
        assertThat(changelog).doesNotExist();
    }

    private Path releaseDir(String version) {
        return projectDir.toPath().resolve(".atlas/releases/" + version);
    }

    /** Every regular file under {@code dir}, concatenated in a stable (sorted-path) order. */
    private static byte[] concatenatedBytes(Path dir) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.filter(Files::isRegularFile).sorted().toList()) {
                out.write(Files.readAllBytes(path));
            }
            return out.toByteArray();
        }
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private void append(String buildScript) throws IOException {
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), buildScript, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }

    private GradleRunner runner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }
}
