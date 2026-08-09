/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the {@code atlas} CLI: the two subcommands, the stable {@code --json} document, the
 * exit-code contract, and the FR-005 invariant that nothing Spring ships inside {@code atlas.jar}.
 *
 * <p>Generation runs for real — no mocking of the processing environment — with the test JVM's own
 * classpath handed to {@code --classpath}, which is how a caller supplies the Spring AI / Spring
 * Web types the generated wrappers reference.
 */
class AtlasCliTest {

    private static final String ENTITY_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticEntity;
            import com.egoge.ai.atlas.annotations.AgenticField;

            @AgenticEntity(description = "A customer of the shop")
            public class Customer {
                @AgenticField(description = "Unique identifier")
                private Long id;

                @AgenticField(description = "Display name")
                private String name;

                private String socialSecurityNumber;

                public Long getId() { return id; }
                public String getName() { return name; }
                public String getSocialSecurityNumber() { return socialSecurityNumber; }
            }
            """;

    private static final String SERVICE_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticExposed;
            import java.util.List;

            @AgenticExposed(description = "Look customers up", returnType = Customer.class)
            public class CustomerService {
                public Customer findById(Long id) { return null; }
                public List<Customer> findAll() { return List.of(); }
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path workspace;

    private Path sources;
    private Path out;
    private StringWriter stdout;
    private StringWriter stderr;

    @BeforeEach
    void setUp() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        out = workspace.resolve("out");
        Path packageDir = Files.createDirectories(sources.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE,
                StandardCharsets.UTF_8);
        stdout = new StringWriter();
        stderr = new StringWriter();
    }

    /** Runs the CLI in-process and captures both streams; never exits the test JVM. */
    private int run(String... args) {
        PrintWriter outWriter = new PrintWriter(stdout, true);
        PrintWriter errWriter = new PrintWriter(stderr, true);
        int exitCode = AtlasCli.execute(outWriter, errWriter, args);
        outWriter.flush();
        errWriter.flush();
        return exitCode;
    }

    /** The test JVM's classpath, which carries the Spring AI / Spring Web types (FR-005). */
    private static String testClasspath() {
        return System.getProperty("java.class.path");
    }

    private JsonNode parseStdout() throws IOException {
        return MAPPER.readTree(stdout.toString());
    }

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("writes the generated tree and reports it on stdout")
        void writesGeneratedTree() {
            int exitCode = run("generate", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--out", out.toString());

            assertThat(exitCode).isZero();
            assertThat(out.resolve("sources/test/generated/CustomerDto.java")).exists();
            assertThat(stdout.toString())
                    .contains("Generated")
                    .contains("DTO")
                    .contains("test/generated/CustomerDto.java");
        }

        @Test
        @DisplayName("--json emits the documented stable document")
        void jsonShape() throws IOException {
            int exitCode = run("generate", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--out", out.toString(), "--json");

            assertThat(exitCode).isZero();
            JsonNode report = parseStdout();

            // Every documented key is present, on every run.
            assertThat(report.fieldNames()).toIterable().containsExactly(
                    "schemaVersion", "command", "status", "outputDir", "files", "counts",
                    "openApi", "diagnostics", "errors");
            assertThat(report.get("schemaVersion").asInt()).isEqualTo(JsonOutput.SCHEMA_VERSION);
            assertThat(report.get("command").asText()).isEqualTo("generate");
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_OK);
            assertThat(report.get("outputDir").asText())
                    .isEqualTo(out.toAbsolutePath().normalize().toString());
            assertThat(report.get("errors")).isEmpty();

            JsonNode file = report.get("files").get(0);
            assertThat(file.fieldNames()).toIterable()
                    .containsExactly("kind", "relativePath", "path", "source");
            assertThat(file.get("kind").asText()).isEqualTo("DTO");
            assertThat(file.get("source").asBoolean()).isTrue();
            assertThat(Path.of(file.get("path").asText())).exists();

            assertThat(report.get("counts").get("DTO").asInt()).isEqualTo(1);
            assertThat(report.get("counts").get("MCP_TOOL").asInt()).isEqualTo(1);
            assertThat(report.get("counts").get("REST_CONTROLLER").asInt()).isEqualTo(1);
            assertThat(report.get("openApi").get("document").asText()).contains("openapi");
        }

        @Test
        @DisplayName("passes -A processor options through to the processor")
        void processorOptions() throws IOException {
            int exitCode = run("generate", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--out", out.toString(),
                    "-Aai.atlas.api.major=3", "-Aai.atlas.api.basePath=/svc", "--json");

            assertThat(exitCode).isZero();
            assertThat(out.resolve("resources/META-INF/openapi/openapi-v3.json")).exists();
            assertThat(parseStdout().get("openApi").get("document").asText()).contains("/svc/");
        }

        @Test
        @DisplayName("fails with exit 1 and an error document when the sources do not compile")
        void compileFailure() throws IOException {
            // Without Spring on --classpath the generated wrappers cannot compile — the same
            // failure a caller hits when they pass the wrong classpath.
            int exitCode = run("generate", "--sources", sources.toString(),
                    "--out", out.toString(), "--json");

            assertThat(exitCode).isEqualTo(1);
            JsonNode report = parseStdout();
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_ERROR);
            assertThat(report.get("files")).isEmpty();
            assertThat(report.get("diagnostics")).anyMatch(
                    node -> "ERROR".equals(node.get("severity").asText()));
            assertThat(stderr.toString()).contains("generation failed");
            // The driver creates the output directory but publishes nothing from a failed run.
            assertThat(out.resolve("sources")).doesNotExist();
            assertThat(out.resolve("resources")).doesNotExist();
        }

        @Test
        @DisplayName("fails with exit 1 and an error document when a source path is missing")
        void missingSources() throws IOException {
            int exitCode = run("generate", "--sources", workspace.resolve("nope").toString(),
                    "--classpath", testClasspath(), "--out", out.toString(), "--json");

            assertThat(exitCode).isEqualTo(1);
            JsonNode report = parseStdout();
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_ERROR);
            assertThat(report.get("errors").get(0).asText()).contains("does not exist");
            assertThat(stderr.toString()).contains("atlas:");
        }
    }

    @Nested
    @DisplayName("openapi")
    class OpenApi {

        @Test
        @DisplayName("prints the specification on stdout when --out is omitted")
        void printsSpec() throws IOException {
            int exitCode = run("openapi", "--sources", sources.toString(),
                    "--classpath", testClasspath());

            assertThat(exitCode).isZero();
            assertThat(MAPPER.readTree(stdout.toString()).get("openapi").asText())
                    .startsWith("3.");
        }

        @Test
        @DisplayName("writes the specification to --out and reports the path")
        void writesSpec() throws IOException {
            Path target = out.resolve("nested/openapi.json");

            int exitCode = run("openapi", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--out", target.toString());

            assertThat(exitCode).isZero();
            assertThat(target).exists();
            assertThat(stdout.toString().trim())
                    .isEqualTo(target.toAbsolutePath().normalize().toString());
            assertThat(MAPPER.readTree(Files.readString(target)).has("paths")).isTrue();
        }

        @Test
        @DisplayName("--json embeds the document and the path it was written to")
        void jsonShape() throws IOException {
            Path target = out.resolve("openapi.json");

            int exitCode = run("openapi", "--sources", sources.toString(),
                    "--classpath", testClasspath(), "--out", target.toString(), "--json");

            assertThat(exitCode).isZero();
            JsonNode report = parseStdout();
            assertThat(report.get("command").asText()).isEqualTo("openapi");
            assertThat(report.get("status").asText()).isEqualTo(JsonOutput.STATUS_OK);
            assertThat(report.get("outputDir").isNull()).isTrue();
            assertThat(report.get("openApi").get("path").asText())
                    .isEqualTo(target.toAbsolutePath().normalize().toString());
            assertThat(report.get("openApi").get("document").asText())
                    .isEqualTo(Files.readString(target));
        }

        @Test
        @DisplayName("leaves nothing behind but --out")
        void cleansUpItsWorkingDirectory() throws IOException {
            int exitCode = run("openapi", "--sources", sources.toString(),
                    "--classpath", testClasspath());

            assertThat(exitCode).isZero();
            try (var entries = Files.list(sources)) {
                assertThat(entries).containsExactly(sources.resolve("test"));
            }
            assertThat(out).doesNotExist();
        }
    }

    @Nested
    @DisplayName("command line contract")
    class CommandLineContract {

        @Test
        @DisplayName("--help lists both subcommands and exits 0")
        void help() {
            assertThat(run("--help")).isZero();
            assertThat(stdout.toString()).contains("generate").contains("openapi");
        }

        @Test
        @DisplayName("bare atlas prints usage on stderr and exits 2")
        void noSubcommand() {
            assertThat(run()).isEqualTo(2);
            assertThat(stderr.toString()).contains("Usage:").contains("atlas");
            assertThat(stdout.toString()).isEmpty();
        }

        @Test
        @DisplayName("an unknown option is a usage error, exit 2, with stdout left clean")
        void unknownOption() {
            assertThat(run("generate", "--sources", sources.toString(), "--out", out.toString(),
                    "--nonsense", "--json")).isEqualTo(2);
            assertThat(stderr.toString()).contains("Unknown option");
            assertThat(stdout.toString()).isEmpty();
        }

        @Test
        @DisplayName("a missing required option is a usage error, exit 2")
        void missingRequiredOption() {
            assertThat(run("generate", "--sources", sources.toString())).isEqualTo(2);
            assertThat(stderr.toString()).contains("--out");
        }
    }

    @Test
    @DisplayName("FR-005: no Spring on the classpath that ships in atlas.jar")
    void noSpringOnRuntimeClasspath() {
        String property = System.getProperty("ai.atlas.cli.runtimeClasspath");
        assertThat(property)
                .as("the cli build must export its resolved runtime classpath to this test")
                .isNotNull();

        List<String> springEntries = Arrays.stream(property.split(File.pathSeparator))
                .map(entry -> Path.of(entry).getFileName().toString())
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.startsWith("spring-") || lower.startsWith("jakarta.servlet")
                            || lower.startsWith("tomcat-");
                })
                .toList();

        assertThat(springEntries)
                .as("the cli module must declare no Spring dependency of its own; Spring types "
                        + "reach generation through the caller's --classpath")
                .isEmpty();
    }
}
