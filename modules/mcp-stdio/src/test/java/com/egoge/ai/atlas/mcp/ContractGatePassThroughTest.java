/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-017: the STDIO MCP server passes {@code ai.atlas.contract.baseline} and
 * {@code ai.atlas.contract.locked} through {@code options} unchanged, and a gate failure is an
 * {@code isError} result carrying the gate's diagnostic. Sources with no ai-atlas annotation are
 * checked against the baseline with the empty-contract check.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ContractGatePassThroughTest {

    private static final String JAR_PROPERTY = "ai.atlas.mcp.jar";
    private static final String CHILD_JVM_ARG_PROPERTY = "ai.atlas.mcp.childJvmArg";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASELINE = "ai.atlas.contract.baseline";
    private static final String LOCKED = "ai.atlas.contract.locked";

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

                public Long getId() { return id; }
                public String getName() { return name; }
            }
            """;

    private static final String SERVICE_SOURCE = """
            package test;

            import com.egoge.ai.atlas.annotations.AgenticExposed;

            @AgenticExposed(description = "Look customers up", returnType = Customer.class)
            public class CustomerService {
                public Customer findById(Long id) { return null; }
            }
            """;

    @TempDir
    private static Path workspace;

    private static McpServerSession server;
    private static Path baseline;

    @BeforeAll
    static void acceptTheSampleContract() throws IOException {
        server = McpServerSession.start(System.getProperty(JAR_PROPERTY),
                System.getProperty(CHILD_JVM_ARG_PROPERTY), CALL_TIMEOUT);
        server.initialize();
        Path sources = writeSources("accepted", ENTITY_SOURCE);
        Path out = workspace.resolve("accepted-out");
        McpSchema.CallToolResult accepted = server.callTool(AtlasMcpServer.TOOL_GENERATE, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OUT, out.toString()));
        assertThat(accepted.isError()).as(text(accepted)).isFalse();
        baseline = Files.copy(out.resolve("resources/META-INF/ai-atlas/api.ir.json"),
                workspace.resolve("api.ir.json"));
    }

    @AfterAll
    static void disconnect() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aBaselineTheSourcesBreakIsAnErrorResult() throws IOException {
        Path sources = writeSources("broken",
                ENTITY_SOURCE.replace("@AgenticField(description = \"Display name\")", ""));

        List<String> errors = errorsOf(AtlasMcpServer.TOOL_INSPECT, sources, Map.of(BASELINE, baseline.toString()));

        assertThat(errors).anySatisfy(m -> assertThat(m)
                .contains("Breaking contract change to field test.Customer#name: removed"));
    }

    @Test
    void anUnchangedContractInLockModePasses() throws IOException {
        McpSchema.CallToolResult result = server.callTool(AtlasMcpServer.TOOL_INSPECT, Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(writeSources("unchanged", ENTITY_SOURCE).toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OPTIONS, Map.of(BASELINE, baseline.toString(), LOCKED, "true")));

        assertThat(result.isError()).as(text(result)).isFalse();
    }

    @Test
    void sourcesWithNoAnnotationFailThroughTheEmptyContractCheck() throws IOException {
        Path plain = Files.createDirectories(workspace.resolve("plain/plain"));
        Files.writeString(plain.resolve("Plain.java"), "package plain;\npublic class Plain {}\n",
                StandardCharsets.UTF_8);

        List<String> errors = errorsOf(AtlasMcpServer.TOOL_GENERATE, plain.getParent(),
                Map.of(BASELINE, baseline.toString()));

        assertThat(errors)
                .anySatisfy(m -> assertThat(m).contains("declares no @AgenticEntity or @AgenticExposed")
                        .contains(baseline.toString()))
                .anySatisfy(m -> assertThat(m).contains("operation test.CustomerService#findById(java.lang.Long): removed"));
    }

    /** Calls {@code tool} with {@code options}, asserts an {@code isError} result, and returns its ERROR messages. */
    private static List<String> errorsOf(String tool, Path sources, Map<String, String> options) throws IOException {
        Map<String, Object> arguments = new HashMap<>(Map.of(
                AtlasMcpServer.ARG_SOURCES, List.of(sources.toString()),
                AtlasMcpServer.ARG_CLASSPATH, testClasspath(),
                AtlasMcpServer.ARG_OPTIONS, options));
        if (tool.equals(AtlasMcpServer.TOOL_GENERATE)) {
            arguments.put(AtlasMcpServer.ARG_OUT, workspace.resolve("out-" + sources.getFileName()).toString());
        }
        McpSchema.CallToolResult result = server.callTool(tool, arguments);
        assertThat(result.isError()).as(text(result)).isTrue();
        JsonNode report = MAPPER.readTree(text(result));
        assertThat(report.get("status").asText()).isEqualTo(AtlasMcpServer.STATUS_ERROR);
        List<String> errors = new ArrayList<>();
        report.get("diagnostics").forEach(d -> {
            if ("ERROR".equals(d.get("severity").asText())) {
                errors.add(d.get("message").asText());
            }
        });
        return errors;
    }

    private static Path writeSources(String name, String entitySource) throws IOException {
        Path packageDir = Files.createDirectories(workspace.resolve(name).resolve("test"));
        Files.writeString(packageDir.resolve("Customer.java"), entitySource, StandardCharsets.UTF_8);
        Files.writeString(packageDir.resolve("CustomerService.java"), SERVICE_SOURCE, StandardCharsets.UTF_8);
        return packageDir.getParent();
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private static String testClasspath() {
        return System.getProperty("java.class.path");
    }
}
