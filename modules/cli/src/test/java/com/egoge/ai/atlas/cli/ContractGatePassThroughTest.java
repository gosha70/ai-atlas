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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-017: the CLI passes {@code ai.atlas.contract.baseline} and {@code ai.atlas.contract.locked}
 * through {@code -A} unchanged, and a gate failure is a failed generation: exit 1 and
 * {@code status: error} carrying the gate's diagnostic. Sources with no ai-atlas annotation are
 * checked against the baseline with the empty-contract check.
 */
class ContractGatePassThroughTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASELINE = "-Aai.atlas.contract.baseline=";
    private static final String LOCKED = "-Aai.atlas.contract.locked=";

    @TempDir
    private Path workspace;

    private Path sources;
    private Path baseline;
    private StringWriter stdout;

    @BeforeEach
    void acceptTheSampleContract() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        CliTestFixtures.writeSampleSources(sources);
        Path first = workspace.resolve("first");
        assertThat(run("generate", "--sources", sources.toString(), "--classpath",
                CliTestFixtures.testClasspath(), "--out", first.toString())).isEqualTo(CommandLine.ExitCode.OK);
        baseline = Files.copy(first.resolve("resources/META-INF/ai-atlas/api.ir.json"),
                workspace.resolve("api.ir.json"));
    }

    @Test
    void aBaselineTheSourcesBreakFailsTheGeneration() throws IOException {
        Path customer = sources.resolve("test/Customer.java");
        Files.writeString(customer, Files.readString(customer).replace(
                "@AgenticField(description = \"Display name\")", ""), StandardCharsets.UTF_8);

        JsonNode report = generateAgainstBaseline(sources);

        assertThat(errorMessages(report)).anySatisfy(m -> assertThat(m)
                .contains("Breaking contract change to field test.Customer#name: removed"));
    }

    @Test
    void anUnchangedContractPasses() throws IOException {
        int exitCode = run("generate", "--sources", sources.toString(), "--classpath",
                CliTestFixtures.testClasspath(), "--out", workspace.resolve("out").toString(),
                BASELINE + baseline, LOCKED + "true", "--json");

        assertThat(exitCode).as(stdout.toString()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(MAPPER.readTree(stdout.toString()).get("status").asText()).isEqualTo("ok");
    }

    @Test
    void sourcesWithNoAnnotationFailThroughTheEmptyContractCheck() throws IOException {
        Path plain = CliTestFixtures.writePlainSource(Files.createDirectories(workspace.resolve("plain")));

        JsonNode report = generateAgainstBaseline(plain);

        assertThat(errorMessages(report))
                .anySatisfy(m -> assertThat(m).contains("declares no @AgenticEntity or @AgenticExposed")
                        .contains(baseline.toString()))
                .anySatisfy(m -> assertThat(m).contains("entity test.Customer: removed"))
                .anySatisfy(m -> assertThat(m).contains("operation test.CustomerService#findAll(): removed"));
    }

    private JsonNode generateAgainstBaseline(Path sourceRoot) throws IOException {
        int exitCode = run("generate", "--sources", sourceRoot.toString(), "--classpath",
                CliTestFixtures.testClasspath(), "--out", workspace.resolve("out").toString(),
                BASELINE + baseline, "--json");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        JsonNode report = MAPPER.readTree(stdout.toString());
        assertThat(report.get("status").asText()).isEqualTo("error");
        return report;
    }

    private static List<String> errorMessages(JsonNode report) {
        List<String> messages = new ArrayList<>();
        report.get("diagnostics").forEach(d -> {
            if ("ERROR".equals(d.get("severity").asText())) {
                messages.add(d.get("message").asText());
            }
        });
        return messages;
    }

    private int run(String... args) {
        stdout = new StringWriter();
        PrintWriter out = new PrintWriter(stdout, true);
        PrintWriter err = new PrintWriter(new StringWriter(), true);
        int exitCode = AtlasCli.execute(out, err, args);
        out.flush();
        return exitCode;
    }
}
