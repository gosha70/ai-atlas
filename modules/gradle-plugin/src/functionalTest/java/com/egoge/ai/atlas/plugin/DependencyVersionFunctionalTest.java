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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests of the ai-atlas dependency version the plugin adds: the plugin's own version by
 * default, whatever the project's version, and an explicit {@code agentic { version }} when set.
 */
class DependencyVersionFunctionalTest {

    /** A task listing the ai-atlas dependencies the plugin added, without resolving them. */
    private static final String PRINT_DEPS = """

            tasks.register("printDeps") {
                doLast {
                    configurations.getByName("implementation").allDependencies
                        .forEach { println("impl: ${it.group}:${it.name}:${it.version}") }
                    configurations.getByName("annotationProcessor").allDependencies
                        .forEach { println("apt: ${it.group}:${it.name}:${it.version}") }
                }
            }
            """;

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        writeFile("settings.gradle.kts", "rootProject.name = \"test-project\"");
    }

    @Test
    void theDependencyVersionDefaultsToThePluginsOwnVersionNotTheProjectVersion() throws IOException {
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        String pluginVersion = System.getProperty("ai.atlas.functionalTest.version");
        writeFile("build.gradle.kts", """
                plugins {
                    id("com.egoge.ai-atlas")
                }

                version = "2.0.0"

                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }
                %s""".formatted(repo, PRINT_DEPS));
        writeFile("src/main/java/test/Order.java", """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;

                @AgenticEntity(description = "An order")
                public class Order {
                    @AgenticField(description = "Id") private Long id;

                    public Long getId() { return id; }
                }
                """);

        BuildResult first = createRunner("printDeps", "compileJava").build();
        // -Pversion changes the project version only: the ai-atlas dependencies, and so compileJava's
        // classpath and processor path, stay the same
        BuildResult second = createRunner("printDeps", "compileJava", "-Pversion=9.9.9").build();

        for (BuildResult result : List.of(first, second)) {
            assertThat(result.getOutput()).contains(
                    "impl: com.egoge:ai-atlas-annotations:" + pluginVersion,
                    "apt: com.egoge:ai-atlas-processor:" + pluginVersion,
                    "impl: com.egoge:ai-atlas-runtime:" + pluginVersion);
            assertThat(result.getOutput()).doesNotContain(":2.0.0", ":9.9.9");
        }
        assertThat(first.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void anExplicitDependencyVersionWinsOverThePluginsOwnVersion() throws IOException {
        writeFile("build.gradle.kts", """
                plugins {
                    id("com.egoge.ai-atlas")
                }

                version = "2.0.0"

                agentic {
                    version.set("7.7.7")
                }
                %s""".formatted(PRINT_DEPS));

        BuildResult result = createRunner("printDeps", "-Pversion=9.9.9").build();

        assertThat(result.getOutput()).contains(
                "impl: com.egoge:ai-atlas-annotations:7.7.7",
                "apt: com.egoge:ai-atlas-processor:7.7.7",
                "impl: com.egoge:ai-atlas-runtime:7.7.7");
    }

    private GradleRunner createRunner(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }

    private void writeFile(String name, String content) throws IOException {
        File file = new File(projectDir, name);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }
}
