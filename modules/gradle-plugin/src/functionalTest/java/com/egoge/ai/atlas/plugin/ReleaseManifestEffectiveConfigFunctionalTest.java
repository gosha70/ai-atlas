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
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code contract-resources.json} and the release snapshot must reflect a compilation's *effective*
 * {@code ai.atlas.*} configuration (D2.5, D2.6): what {@code compileJava} actually ran with, through
 * whatever combination of {@code agentic { }}, plain {@code options.compilerArgs} or a custom {@code
 * CommandLineArgumentProvider} set it — never only what the {@code agentic { }} extension itself
 * holds. It must also stay stale-clean across a configuration change alone (no source edit): a
 * reserved artifact a compilation with the new configuration would never produce, but an earlier
 * compilation left behind, fails the release naming it and {@code clean} (AC3, AC4).
 */
class ReleaseManifestEffectiveConfigFunctionalTest {

    private static final String ORDER = "src/main/java/test/Order.java";
    private static final String SERVICE = "src/main/java/test/OrderService.java";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"release-manifest-config-test\"");
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        write("build.gradle.kts", """
                import org.gradle.api.tasks.compile.JavaCompile
                import org.gradle.process.CommandLineArgumentProvider

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
        write(ORDER, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;

                @AgenticEntity(description = "An order")
                public class Order {
                    @AgenticField(description = "Id") private Long id;
                    public Long getId() { return id; }
                }
                """);
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
    void aStaleOpenApiDocumentFailsAfterABumpToMajor2() throws IOException {
        release("1.0.0").build();

        append("""

                agentic {
                    apiMajorVersion.set(2)
                }
                tasks.named<JavaCompile>("compileJava") {
                    val destDir = destinationDirectory
                    doLast {
                        val stale = File(destDir.get().asFile, "META-INF/openapi/openapi-v1.json")
                        stale.parentFile.mkdirs()
                        stale.writeText("{}\\n")
                    }
                }
                """);

        BuildResult result = release("2.0.0").buildAndFail();

        assertThat(result.getOutput()).contains("openapi-v1.json", "clean");
        assertThat(releaseDir("2.0.0")).doesNotExist();
    }

    @Test
    void aStaleMcpToolsFileFailsAfterConstraintsAreTurnedOff() throws IOException {
        append("agentic { constraints.set(true) }\n");
        release("1.0.0").build();

        append("""

                agentic {
                    constraints.set(false)
                }
                tasks.named<JavaCompile>("compileJava") {
                    val destDir = destinationDirectory
                    doLast {
                        val stale = File(destDir.get().asFile, "META-INF/ai-atlas/mcp-tools.json")
                        stale.parentFile.mkdirs()
                        stale.writeText("{}\\n")
                    }
                }
                """);

        BuildResult result = release("1.1.0").buildAndFail();

        assertThat(result.getOutput()).contains("mcp-tools.json", "clean");
        assertThat(releaseDir("1.1.0")).doesNotExist();
    }

    /**
     * The {@code agentic { constraints }} extension is left unset, so {@link ContractArguments}
     * contributes no {@code -Aai.atlas.constraints} argument at all: only the plain {@code
     * options.compilerArgs} value reaches {@code compileJava}, and the processor's {@code
     * contract-resources.json} (C2) and the release snapshot (C4) must still list {@code
     * mcp-tools.json} for it, proving the manifest reflects the true effective configuration, not
     * just what the extension set.
     */
    @Test
    void constraintsSetOnlyThroughCompilerArgsStillSnapshotsMcpTools() throws IOException {
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("-Aai.atlas.constraints=true")
                }
                """);

        release("1.0.0").build();

        assertThat(Files.readString(manifestFile())).contains("\"META-INF/ai-atlas/mcp-tools.json\"");
        assertThat(files(releaseDir("1.0.0"))).contains("mcp-tools.json");
        assertThat(Files.readString(releaseDir("1.0.0").resolve("release.json"))).contains("mcp-tools.json");
    }

    /**
     * The major is set to 2 only by a user {@link CommandLineArgumentProvider}, with {@code agentic {
     * apiMajorVersion }} left at its default, 1. The release must snapshot {@code openapi-v2.json},
     * never {@code openapi-v1.json}: the pre-C4 code (before this phase, at {@code bb98010}) looked up
     * the extension's major for the release rather than the compilation's effective one, and failed
     * this exact case.
     */
    @Test
    void theMajorSetByAnArgumentProviderOverridesTheExtensionsDefaultInTheSnapshot() throws IOException {
        append("""

                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgumentProviders.add(CommandLineArgumentProvider { listOf("-Aai.atlas.api.major=2") })
                }
                """);

        release("1.0.0").build();

        assertThat(files(releaseDir("1.0.0"))).contains("openapi-v2.json").doesNotContain("openapi-v1.json");
        assertThat(Files.readString(releaseDir("1.0.0").resolve("release.json")))
                .contains("\"apiMajor\": 2");
    }

    // ------------------------------------------------------------ helpers

    /** Accepts the current sources, then returns a runner releasing them as {@code version}. */
    private GradleRunner release(String version) {
        runner("atlasAccept").build();
        return runner("agenticRelease", "-Pversion=" + version);
    }

    private Path releaseDir(String version) {
        return projectDir.toPath().resolve(".atlas/releases/" + version);
    }

    private Path manifestFile() {
        return projectDir.toPath().resolve("build/classes/java/main/META-INF/ai-atlas/contract-resources.json");
    }

    private static List<String> files(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
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
