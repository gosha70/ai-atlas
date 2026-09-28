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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI passes {@code ai.atlas.projections} through {@code -A} unchanged: with it off, a declared
 * {@code @AgenticField(channels)} fails the generation; with it on, the generation succeeds.
 */
class ProjectionsPassThroughTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROJECTIONS = "-Aai.atlas.projections=";

    @TempDir
    private Path workspace;

    private Path sources;
    private StringWriter stdout;

    @BeforeEach
    void declareAnApiOnlyField() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        CliTestFixtures.writeSampleSources(sources);
        Path customer = sources.resolve("test/Customer.java");
        Files.writeString(customer, Files.readString(customer).replace(
                "@AgenticField(description = \"Display name\")",
                "@AgenticField(description = \"Display name\","
                        + " channels = com.egoge.ai.atlas.annotations.AgenticExposed.Channel.API)"),
                StandardCharsets.UTF_8);
    }

    @Test
    void aDeclaredEligibilityFailsWithoutTheOption() throws IOException {
        int exitCode = generate(PROJECTIONS + "false");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        JsonNode report = MAPPER.readTree(stdout.toString());
        assertThat(report.get("status").asText()).isEqualTo("error");
        assertThat(report.get("diagnostics").toString())
                .contains("@AgenticField(channels) on field 'name' of Customer requires ai.atlas.projections=true");
    }

    @Test
    void theOptionReachesTheProcessor() throws IOException {
        int exitCode = generate(PROJECTIONS + "true");

        assertThat(exitCode).as(stdout.toString()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(MAPPER.readTree(stdout.toString()).get("status").asText()).isEqualTo("ok");
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
