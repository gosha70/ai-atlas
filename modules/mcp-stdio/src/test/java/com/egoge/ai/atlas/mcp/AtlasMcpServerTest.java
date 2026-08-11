/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US4 round-trip: the packaged {@code atlas-mcp.jar} driven the way a copilot's {@code .mcp.json}
 * drives it — spawned as {@code java -jar atlas-mcp.jar} and spoken to over stdio — with no
 * Spring Boot application running. Lists the tools and calls each of the three, asserting every
 * result carries the file manifest and summary an agent reviews before writing (FR-007).
 *
 * <p>One server process serves all the calls, exactly as a harness session would.
 */
class AtlasMcpServerTest {

    /** System property carrying the built {@code atlas-mcp.jar}; set by this module's build. */
    private static final String JAR_PROPERTY = "ai.atlas.mcp.jar";
    /** System property carrying the module's resolved runtime classpath; set by the build. */
    private static final String RUNTIME_CLASSPATH_PROPERTY = "ai.atlas.mcp.runtimeClasspath";
    /**
     * System property carrying a JVM argument the build wants on the spawned server's command
     * line — the JaCoCo agent, so the child's coverage lands in the module's execution data.
     */
    private static final String CHILD_JVM_ARG_PROPERTY = "ai.atlas.mcp.childJvmArg";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(300);

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @TempDir
    private static Path workspace;

    private static Path sources;
    private static Path plainSources;
    private static McpSyncClient client;
    private static McpSchema.InitializeResult initialized;

    @BeforeAll
    static void startServerAndConnect() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        Path packageDir = Files.createDirectories(sources.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE,
                StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE,
                StandardCharsets.UTF_8);
        // Compiles cleanly but carries no ai-atlas annotation — the "nothing to emit" input.
        plainSources = Files.createDirectories(workspace.resolve("plain-src"));
        Files.writeString(plainSources.resolve("Plain.java"), "public class Plain {}",
                StandardCharsets.UTF_8);

        String jar = System.getProperty(JAR_PROPERTY);
        assertThat(jar).as("the mcp-stdio build must export the built atlas-mcp.jar to this test")
                .isNotNull();

        // The spec's .mcp.json launch, verbatim: {"command":"java","args":["-jar","atlas-mcp.jar"]}
        // — plus the build's JaCoCo agent argument when present, so the child's coverage counts.
        List<String> args = new ArrayList<>();
        String childJvmArg = System.getProperty(CHILD_JVM_ARG_PROPERTY);
        if (childJvmArg != null && !childJvmArg.isBlank()) {
            args.add(childJvmArg);
        }
        args.add("-jar");
        args.add(jar);
        ServerParameters launch = ServerParameters.builder(javaExecutable())
                .args(args.toArray(String[]::new))
                .build();
        client = McpClient.sync(new StdioClientTransport(launch,
                        new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(CALL_TIMEOUT)
                .build();
        initialized = client.initialize();
        assertThat(initialized.serverInfo().name()).isEqualTo(AtlasMcpServer.SERVER_NAME);
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("FR-006: the server lists exactly the three atlas tools, each with a schema")
    void listsTheThreeTools() {
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder(AtlasMcpServer.TOOL_INSPECT,
                        AtlasMcpServer.TOOL_GENERATE, AtlasMcpServer.TOOL_OPENAPI);
        for (McpSchema.Tool tool : tools) {
            assertThat(tool.description()).as(tool.name()).isNotBlank();
            assertThat(tool.inputSchema().properties()).as(tool.name())
                    .containsKey(AtlasMcpServer.ARG_SOURCES);
            // FR-005: a call cannot compile without the Spring types, so classpath is required.
            assertThat(tool.inputSchema().required()).as(tool.name())
                    .contains(AtlasMcpServer.ARG_SOURCES, AtlasMcpServer.ARG_CLASSPATH);
            @SuppressWarnings("unchecked")
            Map<String, Object> optionsSchema = (Map<String, Object>)
                    tool.inputSchema().properties().get(AtlasMcpServer.ARG_OPTIONS);
            assertThat(optionsSchema).as(tool.name())
                    .containsEntry("additionalProperties", Map.of("type", "string"));
        }
    }

    @Test
    @DisplayName("the static tool list does not advertise listChanged notifications")
    void advertisesNoToolListChanges() {
        assertThat(initialized.capabilities().tools().listChanged())
                .as("the tool list is static and no notifications are ever emitted")
                .isFalse();
    }

    @Test
    @DisplayName("atlas_inspect_services reports services and would-be artifacts, writing nothing")
    void inspectReportsServicesAndManifest() throws IOException {
        JsonNode report = callOk(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath()));

        assertThat(report.get("services")).extracting(JsonNode::asText)
                .containsExactly("test.CustomerService");
        assertThat(report.get("files")).isNotEmpty();
        // Nothing was written: an inspection manifest carries no on-disk paths.
        report.get("files").forEach(file -> assertThat(file.get("path").isNull()).isTrue());
        assertThat(report.get("counts").get("DTO")).isNotNull();
        assertThat(report.get("summary").asText()).contains("1 @AgenticExposed service");
        // Stable shape: the field is always present, holding explicit null when inapplicable.
        assertThat(report.has("outputDir")).isTrue();
        assertThat(report.get("outputDir").isNull()).isTrue();
    }

    @Test
    @DisplayName("atlas_generate writes the tree and returns a manifest of real on-disk paths")
    void generateWritesFilesAndReturnsManifest() throws IOException {
        Path out = workspace.resolve("generated");

        JsonNode report = callOk(AtlasMcpServer.TOOL_GENERATE, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OUT, out.toString()));

        assertThat(report.get("outputDir").asText()).isEqualTo(out.toString());
        assertThat(report.get("summary").asText()).contains("Generated");
        assertThat(report.get("files")).isNotEmpty();
        for (JsonNode file : report.get("files")) {
            assertThat(Path.of(file.get("path").asText()))
                    .as(file.get("relativePath").asText()).exists();
        }
        assertThat(out.resolve("sources/test/generated/CustomerDto.java")).exists();
    }

    @Test
    @DisplayName("atlas_openapi returns the OpenAPI document in-memory, for review before writing")
    void openApiReturnsTheDocument() throws IOException {
        JsonNode report = callOk(AtlasMcpServer.TOOL_OPENAPI, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath()));

        JsonNode openApi = report.get("openApi");
        assertThat(openApi.isNull()).isFalse();
        assertThat(openApi.get("path").isNull()).as("nothing was written to disk").isTrue();
        JsonNode document = MAPPER.readTree(openApi.get("document").asText());
        assertThat(document.get("openapi").asText()).startsWith("3.");
        assertThat(document.get("paths").properties()).isNotEmpty();
    }

    @Test
    @DisplayName("a bad call comes back as an isError result carrying the same document shape")
    void reportsArgumentErrorsInBand() throws IOException {
        // atlas_generate without its required 'out' argument.
        JsonNode report = assertRejected(AtlasMcpServer.TOOL_GENERATE, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath()));
        assertThat(report.get("summary").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the advertised schema is enforced: no scalars, coercions or missing classpath")
    void rejectsArgumentsOutsideTheSchema() throws IOException {
        // A scalar where the schema says array of strings.
        assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, sources.toString(),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath()));
        // A non-string source element must not be coerced into a path.
        assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(42),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath()));
        // FR-005: classpath is required and must not be blank.
        assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString())));
        assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, "  "));
        // A non-string option value must not be stringified into a -A option.
        assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OPTIONS, Map.of("ai.atlas.api.major", 2)));
    }

    @Test
    @DisplayName("additionalProperties: false is enforced — an unknown top-level argument is "
            + "rejected, not silently ignored")
    void rejectsUnknownTopLevelArguments() throws IOException {
        JsonNode report = assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                "unexpected", true));
        assertThat(report.get("summary").asText()).contains("unexpected");
        // 'out' belongs to atlas_generate's schema only; the other tools must reject it too.
        assertRejected(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OUT, workspace.resolve("never-written").toString()));
    }

    @Test
    @DisplayName("an explicit options: null violates the schema and is rejected, not treated "
            + "as an omitted key")
    void rejectsExplicitNullOptions() throws IOException {
        // Map.of refuses nulls, so build the argument map by hand.
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()));
        arguments.put(AtlasMcpServer.ARG_CLASSPATH, testClasspath());
        arguments.put(AtlasMcpServer.ARG_OPTIONS, null);
        JsonNode report = assertRejected(AtlasMcpServer.TOOL_INSPECT, arguments);
        assertThat(report.get("summary").asText()).contains(AtlasMcpServer.ARG_OPTIONS);
    }

    @Test
    @DisplayName("atlas_openapi with no annotated types reports the missing document, not a "
            + "bogus zero-diagnostic compile failure")
    void openApiWithoutAnnotationsExplainsTheMissingDocument() throws IOException {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                AtlasMcpServer.TOOL_OPENAPI, Map.of(
                        AtlasMcpServer.ARG_SOURCES, List.of(plainSources.toString()),
                        AtlasMcpServer.ARG_CLASSPATH, testClasspath())));

        assertThat(result.isError()).isTrue();
        JsonNode report = MAPPER.readTree(text(result));
        assertThat(report.get("status").asText()).isEqualTo(AtlasMcpServer.STATUS_ERROR);
        assertThat(report.get("summary").asText())
                .contains("no OpenAPI document")
                .doesNotContain("error diagnostic");
        assertThat(report.get("errors")).isNotEmpty();
        assertThat(report.has("openApi")).isTrue();
        assertThat(report.get("openApi").isNull()).isTrue();
    }

    @Test
    @DisplayName("atlas_generate that emits nothing fails and leaves prior output untouched — "
            + "and says so")
    void generateWithoutAnnotationsFailsAndPreservesPriorOutput() throws IOException {
        Path out = workspace.resolve("generated-stale");
        callOk(AtlasMcpServer.TOOL_GENERATE, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OUT, out.toString()));
        Path priorDto = out.resolve("sources/test/generated/CustomerDto.java");
        assertThat(priorDto).exists();

        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                AtlasMcpServer.TOOL_GENERATE, Map.of(
                        AtlasMcpServer.ARG_SOURCES, List.of(plainSources.toString()),
                        AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                        AtlasMcpServer.ARG_OUT, out.toString())));

        assertThat(result.isError()).isTrue();
        JsonNode report = MAPPER.readTree(text(result));
        assertThat(report.get("status").asText()).isEqualTo(AtlasMcpServer.STATUS_ERROR);
        assertThat(report.get("summary").asText()).contains("produced nothing to generate");
        assertThat(report.get("errors")).isNotEmpty();
        assertThat(report.get("files")).isEmpty();
        // The prior tree really is untouched — exactly what the failure message warns about.
        assertThat(priorDto).exists();
    }

    @Test
    @DisplayName("a compilation failure reports its error diagnostics, distinct from the "
            + "semantic no-output failures")
    void compilationFailureCarriesDiagnostics() throws IOException {
        Path broken = Files.createDirectories(workspace.resolve("broken-src"));
        Files.writeString(broken.resolve("Broken.java"), "public class Broken { int x = }",
                StandardCharsets.UTF_8);

        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                AtlasMcpServer.TOOL_INSPECT, Map.of(
                        AtlasMcpServer.ARG_SOURCES, List.of(broken.toString()),
                        AtlasMcpServer.ARG_CLASSPATH, testClasspath())));

        assertThat(result.isError()).isTrue();
        JsonNode report = MAPPER.readTree(text(result));
        assertThat(report.get("summary").asText())
                .matches("Generation failed with [1-9]\\d* error diagnostic\\(s\\).*");
        assertThat(report.get("diagnostics")).isNotEmpty();
    }

    @Test
    @DisplayName("FR-005: no Spring on the classpath that ships in atlas-mcp.jar")
    void noSpringOnRuntimeClasspath() {
        String property = System.getProperty(RUNTIME_CLASSPATH_PROPERTY);
        assertThat(property)
                .as("the mcp-stdio build must export its resolved runtime classpath to this test")
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
                .as("the mcp-stdio module must declare no Spring dependency of its own; Spring "
                        + "types reach generation through the caller-supplied classpath argument")
                .isEmpty();
    }

    /**
     * Calls the tool, asserts the call was rejected in-band with the stable document shape —
     * every key present, {@code outputDir} and {@code openApi} explicit null — and returns the
     * parsed document.
     */
    private static JsonNode assertRejected(String tool, Map<String, Object> arguments)
            throws IOException {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as(arguments.toString()).isTrue();
        JsonNode report = MAPPER.readTree(text(result));
        assertThat(report.get("status").asText()).isEqualTo(AtlasMcpServer.STATUS_ERROR);
        assertThat(report.get("errors")).isNotEmpty();
        assertThat(report.has("outputDir")).as("outputDir is present").isTrue();
        assertThat(report.get("outputDir").isNull()).isTrue();
        assertThat(report.has("openApi")).as("openApi is present").isTrue();
        assertThat(report.get("openApi").isNull()).isTrue();
        return report;
    }

    /** Calls the tool, asserts the MCP result is not an error, and parses its JSON document. */
    private static JsonNode callOk(String tool, Map<String, Object> arguments) throws IOException {
        McpSchema.CallToolResult result =
                client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        String text = text(result);
        assertThat(result.isError()).as(text).isFalse();
        JsonNode report = MAPPER.readTree(text);
        assertThat(report.get("tool").asText()).isEqualTo(tool);
        assertThat(report.get("status").asText()).isEqualTo(AtlasMcpServer.STATUS_OK);
        return report;
    }

    /** The single text content every atlas tool result carries. */
    private static String text(McpSchema.CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    /**
     * The test JVM's classpath — it carries the annotations module plus the Spring AI / Spring Web
     * types the generated wrappers reference, handed to generation exactly as a real caller's
     * classpath argument would be (FR-005).
     */
    private static String testClasspath() {
        return System.getProperty("java.class.path");
    }

    /** The {@code java} of the JVM running the tests — a JDK, which generation requires. */
    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
