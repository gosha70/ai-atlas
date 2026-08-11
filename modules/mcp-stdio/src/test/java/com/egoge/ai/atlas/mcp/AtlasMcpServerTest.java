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
import java.util.Arrays;
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
    private static McpSyncClient client;

    @BeforeAll
    static void startServerAndConnect() throws IOException {
        sources = Files.createDirectories(workspace.resolve("src"));
        Path packageDir = Files.createDirectories(sources.resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), ENTITY_SOURCE,
                StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE,
                StandardCharsets.UTF_8);

        String jar = System.getProperty(JAR_PROPERTY);
        assertThat(jar).as("the mcp-stdio build must export the built atlas-mcp.jar to this test")
                .isNotNull();

        // The spec's .mcp.json launch, verbatim: {"command":"java","args":["-jar","atlas-mcp.jar"]}
        ServerParameters launch = ServerParameters.builder(javaExecutable())
                .args("-jar", jar)
                .build();
        client = McpClient.sync(new StdioClientTransport(launch,
                        new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(CALL_TIMEOUT)
                .build();
        McpSchema.InitializeResult initialized = client.initialize();
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
        }
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
        assertThat(report.get("outputDir")).isNull();
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
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                AtlasMcpServer.TOOL_GENERATE,
                Map.of(AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()))));

        assertThat(result.isError()).isTrue();
        JsonNode report = MAPPER.readTree(text(result));
        assertThat(report.get("status").asText()).isEqualTo(AtlasMcpServer.STATUS_ERROR);
        assertThat(report.get("errors")).isNotEmpty();
        assertThat(report.get("summary").asText()).isNotBlank();
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
