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

    /** This build's plugin version; every other version below is derived from it, so none can equal it. */
    private static final String PLUGIN_VERSION = System.getProperty("ai.atlas.functionalTest.version");
    private static final String PROJECT_VERSION = PLUGIN_VERSION + ".1";
    private static final String OVERRIDDEN_PROJECT_VERSION = PLUGIN_VERSION + ".2";
    private static final String EXPLICIT_VERSION = PLUGIN_VERSION + ".3";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        writeFile("settings.gradle.kts", "rootProject.name = \"test-project\"");
    }

    @Test
    void theDependencyVersionDefaultsToThePluginsOwnVersionNotTheProjectVersion() throws IOException {
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        writeFile("build.gradle.kts", """
                plugins {
                    id("com.egoge.ai-atlas")
                }

                version = "%s"

                repositories {
                    maven { url = uri("%s") }
                    mavenCentral()
                }
                %s""".formatted(PROJECT_VERSION, repo, PRINT_DEPS));
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
        BuildResult second = createRunner("printDeps", "compileJava", "-Pversion=" + OVERRIDDEN_PROJECT_VERSION)
                .build();

        for (BuildResult result : List.of(first, second)) {
            assertThat(result.getOutput()).contains(
                    "impl: com.egoge:ai-atlas-annotations:" + PLUGIN_VERSION,
                    "apt: com.egoge:ai-atlas-processor:" + PLUGIN_VERSION,
                    "impl: com.egoge:ai-atlas-runtime:" + PLUGIN_VERSION);
            assertThat(result.getOutput()).doesNotContain(":" + PROJECT_VERSION, ":" + OVERRIDDEN_PROJECT_VERSION);
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

                version = "%s"

                agentic {
                    version.set("%s")
                }
                %s""".formatted(PROJECT_VERSION, EXPLICIT_VERSION, PRINT_DEPS));

        BuildResult result = createRunner("printDeps", "-Pversion=" + OVERRIDDEN_PROJECT_VERSION).build();

        assertThat(result.getOutput()).contains(
                "impl: com.egoge:ai-atlas-annotations:" + EXPLICIT_VERSION,
                "apt: com.egoge:ai-atlas-processor:" + EXPLICIT_VERSION,
                "impl: com.egoge:ai-atlas-runtime:" + EXPLICIT_VERSION);
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
