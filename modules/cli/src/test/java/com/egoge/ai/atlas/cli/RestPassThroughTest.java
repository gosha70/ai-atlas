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
 * The CLI passes {@code ai.atlas.rest} through {@code -A} unchanged: with it off, a declared CRUD
 * style fails the generation, naming the route still served; with it on, the controller serves
 * the CRUD route.
 */
class RestPassThroughTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REST = "-Aai.atlas.rest=";

    @TempDir
    private Path workspace;

    private Path sources;
    private StringWriter stdout;

    @BeforeEach
    void declareTheCrudStyle() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        CliTestFixtures.writeSampleSources(sources);
        Path service = sources.resolve("test/CustomerService.java");
        Files.writeString(service, Files.readString(service).replace(
                "returnType = Customer.class)",
                "returnType = Customer.class, rest = @AgenticExposed.Rest(style = AgenticExposed.RestStyle.CRUD,"
                        + " resource = \"customers\"))"),
                StandardCharsets.UTF_8);
    }

    @Test
    void aRestDeclarationFailsWithoutTheOption() throws IOException {
        int exitCode = generate(REST + "false");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        JsonNode report = MAPPER.readTree(stdout.toString());
        assertThat(report.get("status").asText()).isEqualTo("error");
        assertThat(report.get("diagnostics").toString()).contains("REST metadata on 'findById' requires"
                + " ai.atlas.rest=true. Without it the operation is still served at POST /api/v1/customer-service/find-by-id");
    }

    @Test
    void theOptionReachesTheProcessor() throws IOException {
        int exitCode = generate(REST + "true");

        assertThat(exitCode).as(stdout.toString()).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(MAPPER.readTree(stdout.toString()).get("status").asText()).isEqualTo("ok");
        String controller = Files.readString(workspace.resolve(
                "out/sources/test/generated/CustomerServiceRestController.java"));
        assertThat(controller).contains("@RequestMapping(\"/api/v1/customers\")")
                .contains("@GetMapping(\"/{id}\")").contains("@GetMapping\n");
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
