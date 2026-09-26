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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests of how the contract gate reads the compile output and how {@code atlasAccept}
 * compiles, with consumer projects beyond the plain ones of {@link AgenticPluginFunctionalTest}.
 */
class ContractGateEdgeCaseFunctionalTest {

    private static final String SERVICE = "src/main/java/test/OrderService.java";
    private static final String EMPTY_CONTRACT_FAILURE = "declares no @AgenticEntity or @AgenticExposed";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
        writeBuildScript();
    }

    @Test
    void aReferenceToTheAnnotationTypeIsNoDeclaration() throws IOException {
        write(SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Orders")
                public class OrderService {
                    public String find(Long id) { return null; }
                }
                """);
        run("atlasAccept").build();
        write(SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                public class OrderService {
                    private AgenticExposed kept;

                    public String find(Long id) { return null; }
                }
                """);

        BuildResult result = run("classes").buildAndFail();

        assertThat(result.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(result.getOutput()).contains(EMPTY_CONTRACT_FAILURE)
                .contains("operation test.OrderService#find(java.lang.Long): removed");
    }

    @Test
    void aMethodAnnotatedAfterAnotherAnnotationWithValuesIsADeclaration() throws IOException {
        write(SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                public class OrderService {
                    private String note;

                    @Deprecated(since = "1", forRemoval = true)
                    @AgenticExposed(description = "Find", channels = { AgenticExposed.Channel.API })
                    public String find(Long id) { return note; }
                }
                """);
        run("atlasAccept").build();

        BuildResult result = run("classes").build();

        assertThat(result.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).doesNotContain(EMPTY_CONTRACT_FAILURE);
    }

    @Test
    void atlasAcceptRunsThePrerequisitesOfCompileJava() throws IOException {
        Files.writeString(new File(projectDir, "build.gradle.kts").toPath(), """

                val generatedDir = layout.buildDirectory.dir("custom")
                val generateCustom by tasks.registering {
                    outputs.dir(generatedDir)
                    doLast {
                        val file = generatedDir.get().file("test/Generated.java").asFile
                        file.parentFile.mkdirs()
                        file.writeText("package test;\\n\\npublic class Generated {\\n}\\n")
                    }
                }
                sourceSets.main { java.srcDir(generatedDir) }
                tasks.named("compileJava") { dependsOn(generateCustom) }
                """, StandardOpenOption.APPEND);
        write(SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Orders")
                public class OrderService {
                    public Generated find(Long id) { return null; }
                }
                """);

        BuildResult result = run("clean", "atlasAccept").build();

        assertThat(result.task(":generateCustom").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":atlasAcceptCompile").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":compileJava")).isNull();
        assertThat(new File(projectDir, ".atlas/api.ir.json")).isFile();
    }

    /** Writes a build script resolving the AI-ATLAS modules from the build-local repository. */
    private void writeBuildScript() throws IOException {
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        String version = System.getProperty("ai.atlas.functionalTest.version");
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
                }
                """.formatted(repo, version));
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private GradleRunner run(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }
}
