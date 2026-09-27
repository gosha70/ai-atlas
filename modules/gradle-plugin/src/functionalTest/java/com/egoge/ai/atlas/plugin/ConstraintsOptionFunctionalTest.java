/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-020: {@code agentic { constraints }}, when set, reaches the main {@code compileJava} only, as
 * the processor option {@code ai.atlas.constraints}; turning it on makes the compilation write
 * {@code META-INF/ai-atlas/mcp-tools.json}. Unset, it is not passed, so the processor's default
 * ({@code false}) or a value in {@code options.compilerArgs} applies.
 */
class ConstraintsOptionFunctionalTest {

    private static final String OPTION = "ai.atlas.constraints";
    private static final String SOURCES = "src/main/java/test/";
    private static final String MCP_TOOLS = "build/classes/java/main/META-INF/ai-atlas/mcp-tools.json";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
    }

    @Test
    void theOptionIsOffByDefaultAndReachesTheMainCompilationOnly() throws IOException {
        writeProject("");
        append("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") { doFirst { println("MAIN-ARGS " + options.allCompilerArgs) } }
                tasks.named<JavaCompile>("compileTestJava") { doFirst { println("TEST-ARGS " + options.allCompilerArgs) } }
                tasks.named<JavaCompile>("atlasAcceptCompile") { doFirst { println("ACCEPT-ARGS " + options.allCompilerArgs) } }
                """);
        write("src/test/java/test/PlainTest.java", "package test;\n\npublic class PlainTest {\n}\n");

        String output = runner("compileTestJava", "atlasAccept").build().getOutput();

        assertThat(line(output, "MAIN-ARGS ")).doesNotContain(OPTION);
        assertThat(line(output, "TEST-ARGS ")).doesNotContain(OPTION);
        assertThat(line(output, "ACCEPT-ARGS ")).doesNotContain(OPTION);
        assertThat(new File(projectDir, MCP_TOOLS)).doesNotExist();
    }

    @Test
    void turningTheOptionOnWritesTheToolSpecifications() throws IOException {
        writeProject("constraints.set(true)");
        append("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") { doFirst { println("MAIN-ARGS " + options.allCompilerArgs) } }
                """);

        String output = runner("compileJava").build().getOutput();

        assertThat(line(output, "MAIN-ARGS ")).contains("-A" + OPTION + "=true");
        File tools = new File(projectDir, MCP_TOOLS);
        assertThat(tools).isFile();
        assertThat(Files.readString(tools.toPath())).contains("\"tools\"").contains("\"inputSchema\"");
    }

    @Test
    void aCompilerArgumentTurnsTheOptionOnWhenTheExtensionLeavesItUnset() throws IOException {
        writeProject("");
        append("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("-A%s=true")
                    doFirst { println("MAIN-ARGS " + options.allCompilerArgs) }
                }
                """.formatted(OPTION));

        String output = runner("compileJava").build().getOutput();

        assertThat(line(output, "MAIN-ARGS ")).containsOnlyOnce(OPTION).doesNotContain("-A" + OPTION + "=false");
        assertThat(new File(projectDir, MCP_TOOLS)).isFile();
    }

    @Test
    void settingTheOptionOffPassesFalse() throws IOException {
        writeProject("constraints.set(false)");
        append("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") { doFirst { println("MAIN-ARGS " + options.allCompilerArgs) } }
                """);

        String output = runner("compileJava").build().getOutput();

        assertThat(line(output, "MAIN-ARGS ")).contains("-A" + OPTION + "=false");
        assertThat(new File(projectDir, MCP_TOOLS)).doesNotExist();
    }

    private static String line(String output, String prefix) {
        return output.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    /** A consumer project with one exposed service, resolving AI-ATLAS from the build-local repository. */
    private void writeProject(String agenticSettings) throws IOException {
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
                    %s
                }
                """.formatted(repo, version, agenticSettings));
        write(SOURCES + "Order.java", """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;

                @AgenticEntity(description = "An order")
                public class Order {
                    @AgenticField(description = "Id") private Long id;

                    public Long getId() { return id; }
                }
                """);
        write(SOURCES + "OrderService.java", """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Orders", returnType = Order.class)
                public class OrderService {
                    public Order find(Long id) { return null; }
                }
                """);
    }

    private GradleRunner runner(String... tasks) {
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withArguments(tasks);
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private void append(String path, String content) throws IOException {
        Files.writeString(new File(projectDir, path).toPath(), content, StandardOpenOption.APPEND);
    }
}
