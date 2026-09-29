/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI passes {@code ai.atlas.collections} through {@code -A} unchanged: with it on, the
 * sample's unbounded {@code findAll} is reported as a WARNING; with it off, it is not.
 */
class CollectionsPassThroughTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTIONS = "-Aai.atlas.collections=";
    private static final String UNBOUNDED = "test.CustomerService#findAll returns java.util.List<test.Customer>"
            + " on channels [AI, API] with no paging contract and no declared bound";

    @TempDir
    private Path workspace;

    private Path sources;
    private StringWriter stdout;

    @BeforeEach
    void writeTheSample() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        CliTestFixtures.writeSampleSources(sources);
    }

    @Test
    void theOptionReachesTheProcessor() throws IOException {
        int exitCode = generate(COLLECTIONS + "true");

        assertThat(exitCode).as(stdout.toString()).isEqualTo(CommandLine.ExitCode.OK);
        JsonNode report = MAPPER.readTree(stdout.toString());
        assertThat(report.get("status").asText()).isEqualTo("ok");
        assertThat(report.get("diagnostics").toString()).contains(UNBOUNDED);
    }

    @Test
    void withTheOptionOffNothingIsReported() throws IOException {
        int exitCode = generate(COLLECTIONS + "false");

        assertThat(exitCode).as(stdout.toString()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(stdout.toString()).doesNotContain("no paging contract");
    }

    private int generate(String option) {
        stdout = new StringWriter();
        PrintWriter out = new PrintWriter(stdout, true);
        PrintWriter err = new PrintWriter(new StringWriter(), true);
        int exitCode = AtlasCli.execute(out, err, "generate", "--sources", sources.toString(), "--classpath",
                CliTestFixtures.testClasspath(), "--out", workspace.resolve("out").toString(), option, "--json");
        out.flush();
        return exitCode;
    }
}
