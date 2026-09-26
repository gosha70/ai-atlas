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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests of how the plugin wires the contract gate into the build: the build cache key of
 * {@code compileJava}, the configuration {@code atlasAccept} compiles with, and a processor version
 * the plugin cannot run.
 */
class ContractGateWiringFunctionalTest {

    private static final String ORDER = "src/main/java/test/Order.java";
    private static final String SERVICE = "src/main/java/test/OrderService.java";

    @TempDir
    File projectDir;

    /** A second checkout, a shared build cache and a stub processor, outside {@link #projectDir}. */
    @TempDir
    File otherDir;

    @BeforeEach
    void setup() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"test-project\"");
        String repo = System.getProperty("ai.atlas.functionalTest.repo").replace('\\', '/');
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
                    public Order find(Long id) { return null; }
                }
                """);
    }

    @Test
    void compileJavaIsLoadedFromTheBuildCacheInAnotherCheckout() throws IOException {
        run("atlasAccept").build();
        append("settings.gradle.kts", """

                buildCache {
                    local { directory = file("%s") }
                }
                """.formatted(new File(otherDir, "cache").getAbsolutePath().replace('\\', '/')));
        File checkout = new File(otherDir, "checkout");
        copyProject(checkout);

        BuildResult first = run("classes", "--build-cache").build();
        assertThat(first.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

        BuildResult second = run("classes", "--build-cache").withProjectDir(checkout).build();

        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
    }

    @Test
    void atlasAcceptCompilesWithTheCompilerArgumentsCompileJavaEndsWith() throws IOException {
        append("build.gradle.kts", """
                afterEvaluate {
                    tasks.named<JavaCompile>("compileJava") { options.compilerArgs.add("-Aai.atlas.api.basePath=/late") }
                }
                """);
        run("compileJava").build();
        // javac keeps the last value of a repeated -A option
        assertThat(Files.readString(emittedIr().toPath())).contains("\"apiBasePath\": \"/late\"");

        run("atlasAccept").build();

        assertThat(Files.readAllBytes(baseline().toPath())).isEqualTo(Files.readAllBytes(emittedIr().toPath()));
    }

    @Test
    void atlasAcceptCompilesWithTheEncodingAndForkOptionsCompileJavaEndsWith() throws IOException {
        // Latin-1 bytes, which only the late encoding decodes to the same description
        Path order = new File(projectDir, ORDER).toPath();
        Files.writeString(order, Files.readString(order).replace("An order", "Café order"),
                StandardCharsets.ISO_8859_1);
        append("build.gradle.kts", """
                afterEvaluate {
                    tasks.named<JavaCompile>("compileJava") {
                        options.encoding = "ISO-8859-1"
                        options.isFork = true
                        options.forkOptions.jvmArgs = listOf("-Dai.atlas.test.fork=late")
                    }
                }
                tasks.named<JavaCompile>("atlasAcceptCompile") {
                    doLast { println("ACCEPT-FORK " + options.isFork + " " + options.forkOptions.jvmArgs) }
                }
                """);
        run("compileJava").build();
        assertThat(Files.readString(emittedIr().toPath())).contains("Café order");

        BuildResult result = run("atlasAccept").build();

        assertThat(Files.readAllBytes(baseline().toPath())).isEqualTo(Files.readAllBytes(emittedIr().toPath()));
        assertThat(result.getOutput()).contains("ACCEPT-FORK true [-Dai.atlas.test.fork=late]");
    }

    @Test
    void atlasAcceptCompileRerunsWhenAJvmArgumentProviderOfCompileJavaChanges() throws IOException {
        append("build.gradle.kts", """
                abstract class JvmFlag : CommandLineArgumentProvider {
                    @get:Input abstract val flag: Property<String>
                    override fun asArguments() = listOf("-Dsome.flag=" + flag.get())
                }
                tasks.named<JavaCompile>("compileJava") {
                    options.isFork = true
                    options.forkOptions.jvmArgumentProviders.add(objects.newInstance<JvmFlag>().apply {
                        flag.set(providers.gradleProperty("jvmFlag"))
                    })
                }
                tasks.named<JavaCompile>("atlasAcceptCompile") {
                    doLast { println("ACCEPT-JVM " + options.forkOptions.allJvmArgs) }
                }
                """);
        run("atlasAccept", "-PjvmFlag=a").build();

        BuildResult changed = run("atlasAccept", "-PjvmFlag=b").build();
        BuildResult unchanged = run("atlasAccept", "-PjvmFlag=b").build();

        assertThat(changed.task(":atlasAcceptCompile").getOutcome())
                .isIn(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE);
        assertThat(changed.getOutput()).contains("-Dsome.flag=b");
        assertThat(unchanged.task(":atlasAcceptCompile").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void aProcessorVersionThePluginCannotRunFailsWithTheRemedy() throws IOException {
        run("atlasAccept").build();
        // An empty module: atlasContractCheck and atlasAccept call EmptyContract
        write(ORDER, "package test;\n\npublic class Order {\n}\n");
        write(SERVICE, "package test;\n\npublic class OrderService {\n}\n");
        append("build.gradle.kts", """
                configurations.named("annotationProcessor") {
                    exclude(group = "com.egoge", module = "ai-atlas-processor")
                }
                dependencies {
                    annotationProcessor(files("%s"))
                }
                """.formatted(writeStubProcessor().getAbsolutePath().replace('\\', '/')));

        BuildResult check = run("classes", "--stacktrace").buildAndFail();
        assertThat(check.task(":atlasContractCheck").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertVersionMismatchReported(check);

        BuildResult accept = run("atlasAccept", "--stacktrace").buildAndFail();
        assertThat(accept.task(":atlasAccept").getOutcome()).isEqualTo(TaskOutcome.FAILED);
        assertVersionMismatchReported(accept);
    }

    private static void assertVersionMismatchReported(BuildResult result) {
        assertThat(result.getOutput()).contains("The ai-atlas Gradle plugin")
                .contains("cannot run ai-atlas-processor 0.0.1 on the annotationProcessor classpath")
                .contains("The plugin and processor versions must match")
                .contains("align agentic { version } with the plugin's version, or remove the agentic { version } pin")
                .doesNotContain("Caused by: java.lang.NoSuchMethodError");
    }

    /**
     * Builds {@code ai-atlas-processor-0.0.1.jar}: this build's processor whose {@code EmptyContract}
     * has none of the methods the plugin calls, as an older processor would.
     */
    private File writeStubProcessor() throws IOException {
        File published = new File(System.getProperty("ai.atlas.functionalTest.repo"),
                "com/egoge/ai-atlas-processor/" + System.getProperty("ai.atlas.functionalTest.version"));
        File processor;
        // A snapshot is published with a timestamp in its name: the latest is the one the consumer resolves
        try (Stream<Path> jars = Files.list(published.toPath())) {
            processor = jars.map(Path::toFile).filter(f -> f.getName().endsWith(".jar"))
                    .filter(f -> !f.getName().endsWith("-sources.jar") && !f.getName().endsWith("-javadoc.jar"))
                    .max(Comparator.comparing(File::getName)).orElseThrow();
        }
        File source = new File(otherDir, "stub/src/com/egoge/ai/atlas/processor/contract/EmptyContract.java");
        File classes = new File(otherDir, "stub/classes");
        Files.createDirectories(source.getParentFile().toPath());
        Files.createDirectories(classes.toPath());
        Files.writeString(source.toPath(), """
                package com.egoge.ai.atlas.processor.contract;

                public final class EmptyContract {
                    private EmptyContract() {
                    }
                }
                """);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, "--release", "17", "-d", classes.getPath(), source.getPath()))
                .isZero();
        String stubbed = "com/egoge/ai/atlas/processor/contract/EmptyContract.class";
        File jar = new File(otherDir, "stub/ai-atlas-processor-0.0.1.jar");
        try (JarFile in = new JarFile(processor);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (JarEntry entry : Collections.list(in.entries())) {
                out.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals(stubbed)) {
                    out.write(Files.readAllBytes(new File(classes, stubbed).toPath()));
                } else {
                    try (InputStream bytes = in.getInputStream(entry)) {
                        bytes.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Copies the project's sources, scripts and baseline, without build output, to another directory. */
    private void copyProject(File target) throws IOException {
        Path source = projectDir.toPath();
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (relative.startsWith("build") || relative.startsWith(".gradle")) {
                    continue;
                }
                Path copy = target.toPath().resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private File baseline() {
        return new File(projectDir, ".atlas/api.ir.json");
    }

    private File emittedIr() {
        return new File(projectDir, "build/classes/java/main/META-INF/ai-atlas/api.ir.json");
    }

    private void write(String path, String content) throws IOException {
        File file = new File(projectDir, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    private void append(String path, String content) throws IOException {
        Files.writeString(new File(projectDir, path).toPath(), content, StandardOpenOption.APPEND);
    }

    private GradleRunner run(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(tasks);
    }
}
