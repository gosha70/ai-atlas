/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of {@code atlas inspect}: the stable {@code --json} document, the no-write guarantee,
 * and exit-code contract.
 */
class InspectCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path workspace;

    private Path sources;
    private StringWriter stdout;
    private StringWriter stderr;

    @BeforeEach
    void setUp() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        CliTestFixtures.writeSampleSources(sources);
        resetStreams();
    }

    private void resetStreams() {
        stdout = new StringWriter();
        stderr = new StringWriter();
    }

    private int run(String... args) {
        PrintWriter outWriter = new PrintWriter(stdout, true);
        PrintWriter errWriter = new PrintWriter(stderr, true);
        int exitCode = AtlasCli.execute(outWriter, errWriter, args);
        outWriter.flush();
        errWriter.flush();
        return exitCode;
    }

    private static String testClasspath() {
        return CliTestFixtures.testClasspath();
    }

    private JsonNode parseStdout() throws IOException {
        return MAPPER.readTree(stdout.toString());
    }

    @Nested
    @DisplayName("inspect --json")
    class JsonMode {

        @Test
        @DisplayName("emits the documented stable document")
        void jsonShape() throws IOException {
            int exitCode = run("inspect", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--json");

            assertThat(exitCode).isZero();
            JsonNode report = parseStdout();

            // All documented keys are present.
            assertThat(report.fieldNames()).toIterable().contains(
                    "schemaVersion", "command", "status", "outputDir", "files", "counts",
                    "openApi", "diagnostics", "errors", "services");
            assertThat(report.get("schemaVersion").asInt()).isEqualTo(JsonOutput.SCHEMA_VERSION);
            assertThat(report.get("command").asText()).isEqualTo("inspect");
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_OK);
            // outputDir is always null for inspect — nothing is written.
            assertThat(report.get("outputDir").isNull()).isTrue();
            assertThat(report.get("errors")).isEmpty();

            // files use inspectFiles: path is null, relativePath and kind are populated.
            assertThat(report.get("files")).isNotEmpty();
            JsonNode file = report.get("files").get(0);
            assertThat(file.fieldNames()).toIterable()
                    .containsExactly("kind", "relativePath", "path", "source");
            assertThat(file.get("path").isNull())
                    .as("absolute path must be null — inspect writes no files").isTrue();
            assertThat(file.get("relativePath").asText()).isNotEmpty();
            assertThat(file.get("kind").asText()).isNotEmpty();
            assertThat(file.get("source").asBoolean()).isTrue();

            // Counts are populated.
            assertThat(report.get("counts").get("DTO").asInt()).isEqualTo(1);
            assertThat(report.get("counts").get("MCP_TOOL").asInt()).isEqualTo(1);
            assertThat(report.get("counts").get("REST_CONTROLLER").asInt()).isEqualTo(1);

            // OpenAPI document is embedded but was never written to disk.
            assertThat(report.get("openApi").get("path").isNull()).isTrue();
            assertThat(report.get("openApi").get("document").asText()).contains("openapi");

            // Exposed services are listed with qualified names.
            assertThat(report.get("services")).isNotEmpty();
            assertThat(report.get("services").get(0).asText()).isEqualTo("test.CustomerService");
        }

        @Test
        @DisplayName("no files are written to the source tree or any caller-visible directory")
        void noWriteGuarantee() throws IOException {
            // Snapshot the file listing before the command runs.
            var before = CliTestFixtures.readTree(sources);

            int exitCode = run("inspect", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--json");

            assertThat(exitCode).isZero();
            // The source tree must be byte-for-byte identical.
            assertThat(CliTestFixtures.readTree(sources))
                    .as("the source tree must be unchanged — inspect writes nothing")
                    .isEqualTo(before);
            // The workspace must contain nothing new besides the sources the test itself created
            // and the temp directories JUnit manages.
            try (var entries = Files.list(workspace)) {
                var names = entries.map(p -> p.getFileName().toString()).toList();
                assertThat(names)
                        .as("no output directories may appear in the workspace")
                        .doesNotContain("sources", "resources", "out");
            }
        }

        @Test
        @DisplayName("lists the service exposed by @AgenticExposed with its qualified name")
        void listsExposedServices() throws IOException {
            int exitCode = run("inspect", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--json");

            assertThat(exitCode).isZero();
            JsonNode report = parseStdout();
            assertThat(report.get("services").get(0).asText())
                    .isEqualTo("test.CustomerService");
        }

        @Test
        @DisplayName("lists the DTO that would be generated for the entity")
        void listsDto() throws IOException {
            int exitCode = run("inspect", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--json");

            assertThat(exitCode).isZero();
            JsonNode report = parseStdout();
            assertThat(report.get("files")).anyMatch(
                    node -> "DTO".equals(node.get("kind").asText())
                            && node.get("relativePath").asText().contains("CustomerDto"));
        }

        @Test
        @DisplayName("fails with exit 1 and an error document when the sources do not compile")
        void compileFailure() throws IOException {
            int exitCode = run("inspect", "--sources", sources.toString(), "--json");

            assertThat(exitCode).isEqualTo(1);
            JsonNode report = parseStdout();
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_ERROR);
            assertThat(report.get("files")).isEmpty();
            // services is always present — its empty-array value on failure is the stable schema
            // contract that consumers can rely on.
            assertThat(report.has("services")).isTrue();
            assertThat(report.get("services")).isEmpty();
            assertThat(report.get("diagnostics")).anyMatch(
                    node -> "ERROR".equals(node.get("severity").asText()));
            assertThat(stderr.toString()).contains("generation failed");
        }

        @Test
        @DisplayName("fails when there is nothing to generate")
        void nothingToEmit() throws IOException {
            Path plain = CliTestFixtures.writePlainSource(workspace.resolve("plain-src"));

            int exitCode = run("inspect", "--sources", plain.toString(),
                    "--classpath", testClasspath(), "--json");

            assertThat(exitCode).isEqualTo(1);
            JsonNode report = parseStdout();
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_ERROR);
            assertThat(report.get("files")).isEmpty();
            assertThat(report.get("services")).isEmpty();
            assertThat(report.get("errors").get(0).asText()).contains("nothing to inspect");
        }
    }

    @Nested
    @DisplayName("inspect (human-readable)")
    class HumanReadable {

        @Test
        @DisplayName("prints a summary of exposed services and would-be files")
        void printsSummary() {
            int exitCode = run("inspect", "--sources", sources.toString(),
                    "--classpath", testClasspath());

            assertThat(exitCode).isZero();
            String output = stdout.toString();
            assertThat(output).contains("Exposed services:");
            assertThat(output).contains("CustomerService");
            assertThat(output).contains("Would generate");
            assertThat(output).contains("DTO");
            assertThat(output).contains("MCP_TOOL");
            assertThat(output).contains("REST_CONTROLLER");
            assertThat(output).contains("CustomerDto.java");
        }

        @Test
        @DisplayName("does not print success summary on compile failure")
        void compileFailureNoSummary() {
            int exitCode = run("inspect", "--sources", sources.toString());

            assertThat(exitCode).isEqualTo(1);
            assertThat(stdout.toString()).doesNotContain("Exposed services");
            assertThat(stdout.toString()).doesNotContain("Would generate");
            assertThat(stderr.toString()).contains("generation failed");
        }
    }

    @Nested
    @DisplayName("command line contract")
    class CommandLineContract {

        @Test
        @DisplayName("--help lists the inspect subcommand and exits 0")
        void help() {
            assertThat(run("--help")).isZero();
            assertThat(stdout.toString()).contains("inspect");
        }

        @Test
        @DisplayName("a missing --sources option is a usage error, exit 2")
        void missingSources() {
            assertThat(run("inspect", "--json")).isEqualTo(2);
            assertThat(stderr.toString()).contains("--sources");
        }

        @Test
        @DisplayName("--version prints the version and exits 0")
        void version() {
            assertThat(run("inspect", "--version")).isZero();
            assertThat(stdout.toString().trim()).startsWith(AtlasCli.NAME + " ");
        }
    }

    @Nested
    @DisplayName("qualifiedServiceName")
    class QualifiedServiceName {

        @Test
        @DisplayName("extracts the qualified service name from an MCP tool relative path")
        void fromMcpTool() {
            GeneratedFile toolFile = new GeneratedFile(GeneratedFile.Kind.MCP_TOOL,
                    "test/generated/CustomerServiceMcpTool.java",
                    Path.of("/tmp/atlas-inspect-123/sources/test/generated/CustomerServiceMcpTool.java"),
                    "// generated");

            assertThat(InspectCommand.qualifiedServiceName(toolFile))
                    .isEqualTo("test.CustomerService");
        }

        @Test
        @DisplayName("extracts the qualified service name from a REST controller relative path")
        void fromRestController() {
            GeneratedFile ctrlFile = new GeneratedFile(GeneratedFile.Kind.REST_CONTROLLER,
                    "test/generated/CustomerServiceRestController.java",
                    Path.of("/tmp/atlas-inspect-123/sources/test/generated/CustomerServiceRestController.java"),
                    "// generated");

            assertThat(InspectCommand.qualifiedServiceName(ctrlFile))
                    .isEqualTo("test.CustomerService");
        }

        @Test
        @DisplayName("handles services in the default package")
        void defaultPackage() {
            GeneratedFile toolFile = new GeneratedFile(GeneratedFile.Kind.MCP_TOOL,
                    "generated/SimpleServiceMcpTool.java",
                    Path.of("/tmp/atlas-inspect-123/sources/generated/SimpleServiceMcpTool.java"),
                    "// generated");

            assertThat(InspectCommand.qualifiedServiceName(toolFile))
                    .isEqualTo("SimpleService");
        }
    }
}
