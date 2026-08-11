/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-011: the packaged executable driven the way the spec advertises it — {@code java -jar
 * atlas.jar}, in its own process.
 *
 * <p>{@link AtlasCliTest} exercises the same commands in-process, which is faster but blind to
 * everything packaging can break: a missing {@code Main-Class}, a dependency the shadow jar failed
 * to bundle, {@code META-INF/services} entries lost in the merge, or an exit status that never
 * reaches the shell. Those only show up by launching the jar.
 */
class AtlasJarFunctionalTest {

    /** System property carrying the built {@code atlas.jar}; set by the cli build's test task. */
    private static final String JAR_PROPERTY = "ai.atlas.cli.jar";

    private static final int TIMEOUT_SECONDS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicInteger runCounter = new AtomicInteger();

    @TempDir
    private Path workspace;

    private Path sources;

    @BeforeEach
    void setUp() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        CliTestFixtures.writeSampleSources(sources);
    }

    @Test
    @DisplayName("generate from the jar exits 0, emits the JSON report, and writes the same tree "
            + "the in-process processor run does")
    void generatesFromTheExecutableJar() throws Exception {
        Path jarOut = workspace.resolve("jar-out");

        ProcessResult run = runJar("generate", "--sources", sources.toString(),
                "--classpath", CliTestFixtures.testClasspath(), "--out", jarOut.toString(), "--json");

        assertThat(run.exitCode()).as(run.stderr()).isZero();
        JsonNode report = MAPPER.readTree(run.stdout());
        assertThat(report.get("command").asText()).isEqualTo(GenerateCommand.NAME);
        assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_OK);
        assertThat(report.get("files")).isNotEmpty();
        assertThat(jarOut.resolve("sources/test/generated/CustomerDto.java")).exists();

        // The processor is the reference: whatever an in-process run writes, the jar must write
        // byte for byte — a shading or service-file defect would show up as a difference here.
        assertThat(CliTestFixtures.readTree(jarOut))
                .as("the jar's output must match the in-process run")
                .isEqualTo(CliTestFixtures.readTree(generateInProcess()));
    }

    @Test
    @DisplayName("a failed generate from the jar exits 1 with an error document on stdout")
    void reportsFailureFromTheExecutableJar() throws Exception {
        Path jarOut = workspace.resolve("failed-out");

        // No --classpath, so the generated wrappers cannot resolve their Spring types.
        ProcessResult run = runJar("generate", "--sources", sources.toString(),
                "--out", jarOut.toString(), "--json");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(MAPPER.readTree(run.stdout()).get("status").asText())
                .isEqualTo(JsonOutput.STATUS_ERROR);
        assertThat(run.stderr()).contains("generation failed");
    }

    @Test
    @DisplayName("--version reports the version stamped into the jar manifest")
    void reportsItsVersion() throws Exception {
        ProcessResult run = runJar("--version");

        assertThat(run.exitCode()).as(run.stderr()).isZero();
        assertThat(run.stdout()).startsWith(AtlasCli.NAME + " ").doesNotContain("development build");
    }

    /** Runs the same generation in this JVM, into its own directory, and returns that directory. */
    private Path generateInProcess() {
        Path referenceOut = workspace.resolve("reference-out");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        try (PrintWriter outWriter = new PrintWriter(out, true);
             PrintWriter errWriter = new PrintWriter(err, true)) {
            int exitCode = AtlasCli.execute(outWriter, errWriter, "generate",
                    "--sources", sources.toString(), "--classpath", CliTestFixtures.testClasspath(),
                    "--out", referenceOut.toString());
            assertThat(exitCode).as(err.toString()).isZero();
        }
        return referenceOut;
    }

    /** Launches {@code java -jar atlas.jar} with the given arguments and collects its output. */
    private ProcessResult runJar(String... args) throws IOException, InterruptedException {
        String jar = System.getProperty(JAR_PROPERTY);
        assertThat(jar).as("the cli build must export the built atlas.jar to this test").isNotNull();

        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-jar", jar));
        command.addAll(List.of(args));

        // Both streams go to files: reading two pipes from this thread would deadlock as soon as
        // one of them filled up while we were blocked on the other.
        int run = runCounter.incrementAndGet();
        Path stdout = workspace.resolve("run-" + run + "-stdout.txt");
        Path stderr = workspace.resolve("run-" + run + "-stderr.txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("atlas.jar did not finish within " + TIMEOUT_SECONDS + "s").isTrue();

        return new ProcessResult(process.exitValue(),
                Files.readString(stdout, StandardCharsets.UTF_8),
                Files.readString(stderr, StandardCharsets.UTF_8));
    }

    /**
     * The {@code java} of the JVM running the tests — a JDK, which generation requires, and the
     * same one the rest of the build uses.
     */
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** What one {@code java -jar atlas.jar} invocation produced. */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
