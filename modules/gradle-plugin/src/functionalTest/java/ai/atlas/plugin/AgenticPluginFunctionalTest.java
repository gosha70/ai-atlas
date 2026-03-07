/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.plugin;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for the AI-ATLAS Gradle plugin using Gradle TestKit.
 */
class AgenticPluginFunctionalTest {

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        writeFile("settings.gradle.kts", "rootProject.name = \"test-project\"");
    }

    @Test
    void pluginAppliesSuccessfully() throws IOException {
        writeFile("build.gradle.kts", """
                plugins {
                    id("ai.atlas.gradle-plugin")
                }

                agentic {
                    version.set("0.1.0")
                }
                """);

        BuildResult result = createRunner("tasks").build();
        assertThat(result.getOutput()).contains("BUILD SUCCESSFUL");
    }

    @Test
    void pluginCreatesAgenticExtension() throws IOException {
        writeFile("build.gradle.kts", """
                plugins {
                    id("ai.atlas.gradle-plugin")
                }

                agentic {
                    version.set("0.1.0")
                    mcpEnabled.set(true)
                    restEnabled.set(false)
                    openApiEnabled.set(true)
                }

                tasks.register("printExtension") {
                    doLast {
                        val ext = project.extensions.getByType(ai.atlas.plugin.AgenticExtension::class.java)
                        println("version=" + ext.version.get())
                        println("mcpEnabled=" + ext.mcpEnabled.get())
                        println("restEnabled=" + ext.restEnabled.get())
                        println("openApiEnabled=" + ext.openApiEnabled.get())
                    }
                }
                """);

        BuildResult result = createRunner("printExtension").build();
        assertThat(result.getOutput()).contains("version=0.1.0");
        assertThat(result.getOutput()).contains("mcpEnabled=true");
        assertThat(result.getOutput()).contains("restEnabled=false");
        assertThat(result.getOutput()).contains("openApiEnabled=true");
    }

    @Test
    void pluginAddsDependencies() throws IOException {
        writeFile("build.gradle.kts", """
                plugins {
                    id("ai.atlas.gradle-plugin")
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                agentic {
                    version.set("0.1.0")
                }

                tasks.register("printDeps") {
                    doLast {
                        val impl = configurations.getByName("implementation").allDependencies
                        val apt = configurations.getByName("annotationProcessor").allDependencies
                        impl.forEach { println("impl: ${it.group}:${it.name}:${it.version}") }
                        apt.forEach { println("apt: ${it.group}:${it.name}:${it.version}") }
                    }
                }
                """);

        BuildResult result = createRunner("printDeps").build();
        assertThat(result.getOutput()).contains("impl: ai.atlas:annotations:0.1.0");
        assertThat(result.getOutput()).contains("apt: ai.atlas:processor:0.1.0");
        assertThat(result.getOutput()).contains("impl: ai.atlas:runtime:0.1.0");
    }

    private GradleRunner createRunner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }

    private void writeFile(String name, String content) throws IOException {
        Files.writeString(new File(projectDir, name).toPath(), content);
    }
}
