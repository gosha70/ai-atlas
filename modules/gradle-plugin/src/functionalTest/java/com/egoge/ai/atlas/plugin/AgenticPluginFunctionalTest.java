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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for the AI-ATLAS Gradle plugin using Gradle TestKit.
 */
class AgenticPluginFunctionalTest {

    private static final String MISSING_DESCRIPTION_PREFIX = "[ai-atlas] test.OrderService#findAll";
    private static final String MISSING_DESCRIPTION = "has no description of its own";

    private static final String SOURCES = "src/main/java/test/";
    private static final String ORDER = "Order.java";
    private static final String ORDER_SERVICE = "OrderService.java";
    /** The canonical IR of a compilation that declares nothing, at the default options. */
    private static final String EMPTY_CONTRACT = """
            {
              "irVersion": 1,
              "apiBasePath": "/api",
              "apiMajor": 1,
              "entities": [],
              "operations": []
            }
            """;

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
                    id("com.egoge.ai-atlas")
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
                    id("com.egoge.ai-atlas")
                }

                agentic {
                    version.set("0.1.0")
                    mcpEnabled.set(true)
                    restEnabled.set(false)
                    openApiEnabled.set(true)
                }

                tasks.register("printExtension") {
                    doLast {
                        val ext = project.extensions.getByType(com.egoge.ai.atlas.plugin.AgenticExtension::class.java)
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
                    id("com.egoge.ai-atlas")
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
        assertThat(result.getOutput()).contains("impl: com.egoge:ai-atlas-annotations:0.1.0");
        assertThat(result.getOutput()).contains("apt: com.egoge:ai-atlas-processor:0.1.0");
        assertThat(result.getOutput()).contains("impl: com.egoge:ai-atlas-runtime:0.1.0");
    }

    @Test
    void strictModeFailsBuildOnMissingToolDescription() throws IOException {
        writeServiceProject("strict.set(true)");

        BuildResult result = createRunner("compileJava").buildAndFail();
        assertThat(result.getOutput()).contains("error: " + MISSING_DESCRIPTION_PREFIX);
        assertThat(result.getOutput()).contains(MISSING_DESCRIPTION);
        assertThat(result.getOutput()).contains("BUILD FAILED");
    }

    @Test
    void defaultModeWarnsOnMissingToolDescription() throws IOException {
        writeServiceProject("");

        BuildResult result = createRunner("compileJava").build();
        assertThat(result.getOutput()).contains("warning: " + MISSING_DESCRIPTION_PREFIX);
        assertThat(result.getOutput()).contains(MISSING_DESCRIPTION);
        assertThat(result.getOutput()).contains("BUILD SUCCESSFUL");
    }

    // ---------------------------------------------------------------- contract gate (FR-015, FR-016)

    @Test
    void withoutABaselineTheBuildPassesWithTheNote() throws IOException {
        writeContractProject("");

        BuildResult result = createRunner("classes").build();

        assertThat(result.getOutput()).contains("No contract baseline at " + baseline().getCanonicalPath())
                .contains("atlasAccept");
        assertThat(result.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void atlasAcceptWritesTheEmittedIrByteForByte() throws IOException {
        writeContractProject("");
        createRunner("compileJava").build();

        BuildResult result = createRunner("atlasAccept").build();

        assertThat(result.getOutput()).contains("No previous baseline at");
        assertThat(Files.readAllBytes(baseline().toPath())).isEqualTo(Files.readAllBytes(emittedIr().toPath()));
    }

    @Test
    void deletingAFieldFailsTheBuildUntilAccepted() throws IOException {
        writeContractProject("");
        createRunner("atlasAccept").build();
        replaceIn(ORDER, "@AgenticField(description = \"Note\") ", "");

        BuildResult failed = createRunner("classes").buildAndFail();
        assertThat(failed.getOutput()).contains("Breaking contract change to field test.Order#note: removed");

        BuildResult accepted = createRunner("atlasAccept").build();
        assertThat(accepted.getOutput()).contains("breaking (1):").contains("field test.Order#note: removed");
        createRunner("classes").build();
    }

    @Test
    void lockModeFailsADescriptionChangeUntilAccepted() throws IOException {
        writeContractProject("contractLocked.set(true)");
        createRunner("atlasAccept").build();
        replaceIn(ORDER, "description = \"Note\"", "description = \"A note\"");

        BuildResult failed = createRunner("classes").buildAndFail();
        assertThat(failed.getOutput()).contains("Lock mode (ai.atlas.contract.locked=true)")
                .contains("field test.Order#note");

        BuildResult accepted = createRunner("atlasAccept").build();
        assertThat(accepted.getOutput()).contains("compatible (1):");
        createRunner("classes").build();
    }

    @Test
    void removingEveryAnnotationWithoutCleanFailsUntilAccepted() throws IOException {
        writeContractProject("");
        createRunner("atlasAccept").build();
        createRunner("build").build();
        assertThat(emittedIr()).isFile();
        removeEveryAnnotation();

        BuildResult failed = createRunner("build", "--info").buildAndFail();
        // compileJava ran again on the previous build's output: no clean in between
        assertThat(failed.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(failed.getOutput()).contains("Incremental compilation of")
                .doesNotContain("Full recompilation is required");
        assertThat(failed.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(failed.getOutput()).contains("declares no @AgenticEntity or @AgenticExposed")
                .contains("entity test.Order: removed").contains("operation test.OrderService#find(java.lang.Long): removed");

        BuildResult again = createRunner("build").buildAndFail();
        assertThat(again.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(again.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);

        createRunner("atlasAccept").build();
        assertThat(Files.readString(baseline().toPath())).isEqualTo(EMPTY_CONTRACT);
        createRunner("build").build();
    }

    @Test
    void compileJavaAloneDoesNotRunTheEmptyContractCheckButClassesDoes() throws IOException {
        writeContractProject("");
        createRunner("atlasAccept").build();
        removeEveryAnnotation();

        BuildResult compiled = createRunner("compileJava").build();
        assertThat(compiled.task(":atlasContractCheck")).isNull();

        // A stale IR from an earlier build, as Gradle may preserve it, must not hide the empty contract
        Files.createDirectories(emittedIr().getParentFile().toPath());
        Files.copy(baseline().toPath(), emittedIr().toPath(), StandardCopyOption.REPLACE_EXISTING);
        BuildResult classes = createRunner("classes").buildAndFail();
        assertThat(classes.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertThat(classes.getOutput()).contains("declares no @AgenticEntity or @AgenticExposed");
        assertThat(emittedIr()).isFile();
    }

    @Test
    void editingASourceWithSourceRetentionAnnotationsCompilesIncrementally() throws IOException {
        writeContractProject("");
        createRunner("classes").build();
        replaceIn("Plain.java", "return \"plain\";", "return \"still plain\";");

        BuildResult result = createRunner("compileJava", "--info").build();

        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.getOutput()).doesNotContain("Full recompilation is required");
    }

    @Test
    void editingTheBaselineRerunsCompilation() throws IOException {
        writeContractProject("");
        createRunner("atlasAccept").build();
        createRunner("classes").build();
        assertThat(createRunner("compileJava").build().task(":compileJava").getOutcome())
                .isEqualTo(TaskOutcome.UP_TO_DATE);
        Files.writeString(baseline().toPath(), Files.readString(baseline().toPath())
                .replace("\"description\": \"Note\"", "\"description\": \"Old note\""));

        BuildResult result = createRunner("compileJava").build();

        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void onlyTheMainCompilationReceivesTheContractOptions() throws IOException {
        writeContractProject("");
        appendFile("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") { doFirst { println("MAIN-ARGS " + options.allCompilerArgs) } }
                tasks.named<JavaCompile>("compileTestJava") { doFirst { println("TEST-ARGS " + options.allCompilerArgs) } }
                """);
        writeSource("src/test/java/test/PlainTest.java", "package test;\n\npublic class PlainTest {\n}\n");

        String output = createRunner("compileTestJava").build().getOutput();

        String main = output.lines().filter(l -> l.startsWith("MAIN-ARGS ")).findFirst().orElseThrow();
        String test = output.lines().filter(l -> l.startsWith("TEST-ARGS ")).findFirst().orElseThrow();
        assertThat(main).contains("-Aai.atlas.contract.baseline=" + baseline().getCanonicalPath())
                .contains("-Aai.atlas.contract.locked=false");
        assertThat(test).doesNotContain("ai.atlas.contract.baseline").doesNotContain("ai.atlas.contract.locked");
    }

    @Test
    void theAcceptCompilationKeepsOtherArgumentProvidersButNotTheContractOptions() throws IOException {
        writeContractProject("");
        appendFile("build.gradle.kts", """
                tasks.named<JavaCompile>("compileJava") {
                    options.compilerArgumentProviders.add(CommandLineArgumentProvider { listOf("-Aother.option=1") })
                }
                tasks.named<JavaCompile>("atlasAcceptCompile") { doFirst { println("ACCEPT-ARGS " + options.allCompilerArgs) } }
                """);

        String output = createRunner("atlasAccept").build().getOutput();

        String accept = output.lines().filter(l -> l.startsWith("ACCEPT-ARGS ")).findFirst().orElseThrow();
        assertThat(accept).contains("-Aother.option=1")
                .doesNotContain("ai.atlas.contract.baseline").doesNotContain("ai.atlas.contract.locked");
    }

    @Test
    void creatingTheBaselineRerunsCompilation() throws IOException {
        writeContractProject("");
        createRunner("classes").build();
        assertThat(baseline()).doesNotExist();
        assertThat(createRunner("compileJava").build().task(":compileJava").getOutcome())
                .isEqualTo(TaskOutcome.UP_TO_DATE);
        createRunner("atlasAccept").build();

        BuildResult result = createRunner("compileJava").build();

        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    /**
     * Writes a consumer project with an entity, a service, and ordinary sources carrying
     * {@code SOURCE}-retention annotations, resolving AI-ATLAS from the build-local repository.
     */
    private void writeContractProject(String agenticSettings) throws IOException {
        writeBuildScript(agenticSettings);
        writeSource(SOURCES + ORDER, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticEntity;
                import com.egoge.ai.atlas.annotations.AgenticField;

                @AgenticEntity(description = "An order")
                public class Order {
                    @AgenticField(description = "Id") private Long id;
                    @AgenticField(description = "Note") private String note;

                    public Long getId() { return id; }
                    public String getNote() { return note; }
                }
                """);
        writeSource(SOURCES + ORDER_SERVICE, """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                @AgenticExposed(description = "Orders", returnType = Order.class)
                public class OrderService {
                    public Order find(Long id) { return null; }
                }
                """);
        writeSource(SOURCES + "Marker.java", """
                package test;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.SOURCE)
                public @interface Marker {
                }
                """);
        writeSource(SOURCES + "Plain.java", """
                package test;

                @Marker
                public class Plain {
                    @SuppressWarnings("unused")
                    private int count;

                    @Override
                    public String toString() { return "plain"; }
                }
                """);
    }

    /** Keeps the entity and service as ordinary classes, without any ai-atlas annotation. */
    private void removeEveryAnnotation() throws IOException {
        writeSource(SOURCES + ORDER, """
                package test;

                public class Order {
                    private Long id;
                    private String note;

                    public Long getId() { return id; }
                    public String getNote() { return note; }
                }
                """);
        writeSource(SOURCES + ORDER_SERVICE, """
                package test;

                public class OrderService {
                    public Order find(Long id) { return null; }
                }
                """);
    }

    private File baseline() {
        return new File(projectDir, ".atlas/api.ir.json");
    }

    private File emittedIr() {
        return new File(projectDir, "build/classes/java/main/META-INF/ai-atlas/api.ir.json");
    }

    private void replaceIn(String source, String from, String to) throws IOException {
        File file = new File(projectDir, SOURCES + source);
        String text = Files.readString(file.toPath());
        assertThat(text).contains(from);
        Files.writeString(file.toPath(), text.replace(from, to));
    }

    private void writeSource(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private void appendFile(String name, String content) throws IOException {
        Files.writeString(new File(projectDir, name).toPath(), content, StandardOpenOption.APPEND);
    }

    /**
     * Writes a consumer project whose AI-exposed method has no description of its own, resolving
     * the AI-ATLAS modules from the build-local repository this build published them to.
     */
    private void writeServiceProject(String agenticSettings) throws IOException {
        writeBuildScript(agenticSettings);

        File serviceDir = new File(projectDir, "src/main/java/test");
        Files.createDirectories(serviceDir.toPath());
        Files.writeString(new File(serviceDir, "OrderService.java").toPath(), """
                package test;

                import com.egoge.ai.atlas.annotations.AgenticExposed;

                public class OrderService {
                    @AgenticExposed
                    public String findAll() {
                        return null;
                    }
                }
                """);
    }

    /** Writes a build script resolving the AI-ATLAS modules from the build-local repository. */
    private void writeBuildScript(String agenticSettings) throws IOException {
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
        String version = System.getProperty("ai.atlas.functionalTest.version");
        writeFile("build.gradle.kts", """
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
