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
 * {@code agentic { rest }}, when set, reaches the main {@code compileJava} and
 * {@code atlasAcceptCompile}, as the processor option {@code ai.atlas.rest}, and never the test
 * compilation. Unset, it is not passed, so the processor's default ({@code false}) or a value in
 * {@code options.compilerArgs} applies. With it off, a declared {@code @Rest} fails the compilation,
 * naming the route still served; with it on, the controller serves the declared route.
 */
class RestOptionFunctionalTest {

    private static final String OPTION = "ai.atlas.rest";
    private static final String SOURCES = "src/main/java/test/";
    private static final String BASELINE = ".atlas/api.ir.json";
    private static final String DECLARED = "rest = @AgenticExposed.Rest(method = AgenticExposed.HttpMethod.GET,"
            + " path = \"/{id}\")";
    private static final String PRINT_ARGS = """
            tasks.named<JavaCompile>("compileJava") { doFirst { println("MAIN-ARGS " + options.allCompilerArgs) } }
            tasks.named<JavaCompile>("compileTestJava") { doFirst { println("TEST-ARGS " + options.allCompilerArgs) } }
            tasks.named<JavaCompile>("atlasAcceptCompile") { doFirst { println("ACCEPT-ARGS " + options.allCompilerArgs) } }
            """;

    @TempDir
    File projectDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
    }

    @Test
    void theOptionIsOffByDefaultAndNotPassed() throws IOException {
        writeProject("", "");
        append("build.gradle.kts", PRINT_ARGS);
        write("src/test/java/test/PlainTest.java", "package test;\n\npublic class PlainTest {\n}\n");

        String output = runner("compileTestJava", "atlasAccept").build().getOutput();

        assertThat(line(output, "MAIN-ARGS ")).doesNotContain(OPTION);
        assertThat(line(output, "TEST-ARGS ")).doesNotContain(OPTION);
        assertThat(line(output, "ACCEPT-ARGS ")).doesNotContain(OPTION);
    }

    @Test
    void turningTheOptionOnReachesTheMainAndAcceptCompilationsOnly() throws IOException {
        writeProject("rest.set(true)", DECLARED);
        append("build.gradle.kts", PRINT_ARGS);
        write("src/test/java/test/PlainTest.java", "package test;\n\npublic class PlainTest {\n}\n");

        String output = runner("compileTestJava", "atlasAccept").build().getOutput();

        assertThat(line(output, "MAIN-ARGS ")).containsOnlyOnce("-A" + OPTION + "=true");
        assertThat(line(output, "ACCEPT-ARGS ")).containsOnlyOnce("-A" + OPTION + "=true");
        assertThat(line(output, "TEST-ARGS ")).doesNotContain(OPTION);
        assertThat(new File(projectDir, BASELINE)).isFile();
        assertThat(Files.readString(new File(projectDir,
                "build/generated/sources/annotationProcessor/java/main/test/generated/OrderServiceRestController.java")
                .toPath())).contains("@GetMapping(\"/{id}\")").contains("@PathVariable(\"id\") Long id");
    }

    @Test
    void theExtensionOverridesAConflictingCompilerArgument() throws IOException {
        writeProject("rest.set(true)", DECLARED);
        append("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgs.add("-A%s=false")
                    doFirst { println("MAIN-ARGS " + options.allCompilerArgs) }
                }
                """.formatted(OPTION));

        BuildResult on = runner("compileJava").build();

        String args = line(on.getOutput(), "MAIN-ARGS ");
        assertThat(args.lastIndexOf("-A" + OPTION + "=true")).isGreaterThan(args.indexOf("-A" + OPTION + "=false"));
    }

    @Test
    void aRestDeclarationFailsTheCompilationWhileTheOptionIsOff() throws IOException {
        writeProject("", DECLARED);

        BuildResult result = runner("compileJava").buildAndFail();

        assertThat(result.getOutput()).contains("REST metadata on 'find' requires " + OPTION
                + "=true. Without it the operation is still served at POST /api/v1/order-service/find");
    }

    private static String line(String output, String prefix) {
        return output.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
    }

    /**
     * A consumer project with one exposed service, resolving AI-ATLAS from the build-local repository.
     *
     * @param agenticSettings the body of the {@code agentic} block
     * @param rest            the {@code rest} attribute of {@code OrderService.find}'s {@code @AgenticExposed}, or empty
     */
    private void writeProject(String agenticSettings, String rest) throws IOException {
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

                public class OrderService {
                    @AgenticExposed(description = "Find an order", returnType = Order.class%s)
                    public Order find(Long id) { return null; }
                }
                """.formatted(rest.isEmpty() ? "" : ", " + rest));
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
