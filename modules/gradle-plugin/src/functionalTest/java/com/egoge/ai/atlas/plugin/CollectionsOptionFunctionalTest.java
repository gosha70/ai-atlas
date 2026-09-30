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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code agentic { collections }}, when set, reaches the main {@code compileJava} and
 * {@code atlasAcceptCompile}, as the processor option {@code ai.atlas.collections}, and never the
 * test compilation. Unset, it is not passed, so the processor's default ({@code false}) applies:
 * a declared {@code maxResults} then fails the compilation.
 */
class CollectionsOptionFunctionalTest {

    private static final String OPTION = "ai.atlas.collections";
    private static final String SOURCES = "src/main/java/test/";
    private static final String PRINT_ARGS = """
            tasks.named<JavaCompile>("compileJava") { doFirst { println("MAIN-ARGS " + options.allCompilerArgs) } }
            tasks.named<JavaCompile>("compileTestJava") { doFirst { println("TEST-ARGS " + options.allCompilerArgs) } }
            tasks.named<JavaCompile>("atlasAcceptCompile") { doFirst { println("ACCEPT-ARGS " + options.allCompilerArgs) } }
            """;
    private static final String BOUND = ", maxResults = 10";

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
        write("src/test/java/test/PlainTest.java", "package test;\n\npublic class PlainTest {\n}\n");
    }

    @Test
    void theOptionIsOffByDefaultAndNotPassed() throws IOException {
        writeProject("", "");
        append("build.gradle.kts", PRINT_ARGS);

        BuildResult result = runner("compileTestJava", "atlasAccept").build();

        assertThat(line(result.getOutput(), "MAIN-ARGS ")).doesNotContain(OPTION);
        assertThat(line(result.getOutput(), "TEST-ARGS ")).doesNotContain(OPTION);
        assertThat(line(result.getOutput(), "ACCEPT-ARGS ")).doesNotContain(OPTION);
        // Off: the unbounded list is not reported
        assertThat(result.getOutput()).doesNotContain("with no paging contract");
    }

    @Test
    void turningTheOptionOnReachesTheMainAndAcceptCompilationsOnly() throws IOException {
        writeProject("collections.set(true)", "");
        append("build.gradle.kts", PRINT_ARGS);

        BuildResult result = runner("compileTestJava", "atlasAccept").build();

        assertThat(line(result.getOutput(), "MAIN-ARGS ")).containsOnlyOnce("-A" + OPTION + "=true");
        assertThat(line(result.getOutput(), "ACCEPT-ARGS ")).containsOnlyOnce("-A" + OPTION + "=true");
        assertThat(line(result.getOutput(), "TEST-ARGS ")).doesNotContain(OPTION);
        assertThat(result.getOutput()).contains("test.OrderService#all returns java.util.List<test.Order> on channels"
                + " [AI, API] with no paging contract and no declared bound");
    }

    @Test
    void aDeclaredBoundFailsTheCompilationWhileTheOptionIsOffAndPassesWithItOn() throws IOException {
        writeProject("", BOUND);

        assertThat(runner("compileJava").buildAndFail().getOutput())
                .contains("@AgenticExposed(maxResults) on test.OrderService#all requires " + OPTION + "=true");

        writeProject("collections.set(true)", BOUND);

        assertThat(runner("compileJava").build().getOutput()).doesNotContain("with no paging contract");
    }

    private static String line(String output, String prefix) {
        return output.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    /**
     * A consumer project with one exposed service returning a list, resolving AI-ATLAS from the
     * build-local repository.
     *
     * @param agenticSettings the body of the {@code agentic} block
     * @param bound           the {@code @AgenticExposed} attributes of {@code all}, besides its description
     */
    private void writeProject(String agenticSettings, String bound) throws IOException {
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
                import java.util.List;

                @AgenticExposed(description = "Orders", returnType = Order.class)
                public class OrderService {
                    @AgenticExposed(description = "Every order"%s)
                    public List<Order> all() { return List.of(); }
                }
                """.formatted(bound));
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
