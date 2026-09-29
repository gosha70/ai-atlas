/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #50, for tools the application serves itself: every {@code ToolCallback} Spring AI's MCP
 * server serves from the application's own {@code ToolCallbackProvider}, {@code ToolCallback} and
 * {@code List<ToolCallback>} beans keeps the {@code @AgenticField} whitelist, while every other result
 * keeps Spring AI's JSON, byte for byte. A generated tool served through a callback whose result
 * conversion AI-ATLAS cannot see fails startup. Each test starts the real application on a random
 * port and speaks raw JSON-RPC over MCP.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpOwnProviderResultTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultToolCallResultConverter SPRING_AI = new DefaultToolCallResultConverter();
    private static final String SSN = McpEntityFixtures.SSN;

    private static final String SYNC_SSE = "SYNC_SSE";
    private static final String SYNC_STREAMABLE = "SYNC_STREAMABLE";
    private static final String STATELESS = "STATELESS";
    private static final String ASYNC_SSE = "ASYNC_SSE";
    private static final String ASYNC_STREAMABLE = "ASYNC_STREAMABLE";
    private static final String STATELESS_ASYNC = "STATELESS_ASYNC";
    private static final String NO_CONVERSION = "spring.ai.mcp.server.tool-callback-converter=false";

    /** The generated tool through the application's provider, and application-only entity tools. */
    private static final List<String> ENTITY_TOOLS = List.of("get_person", "app_person", "app_direct",
            "fn_person", "listed_person");

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE, ASYNC_STREAMABLE, STATELESS_ASYNC})
    void generatedToolServedByTheApplicationsOwnProviderKeepsTheWhitelist(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(McpOwnProviderFixtures.OwnProviderApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            String person = text(client, "get_person", "{\"id\":1}");
            assertThat(person).doesNotContain(SSN).isEqualTo("{\"id\":1,\"name\":\"Ada\"}");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE, ASYNC_STREAMABLE, STATELESS_ASYNC})
    void applicationOnlyEntityToolsKeepTheWhitelist(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(McpOwnProviderFixtures.OwnProviderApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            SoftAssertions softly = new SoftAssertions();
            for (String tool : ENTITY_TOOLS) {
                McpEntityResultTest.assertWhitelisted(softly, tool, JSON.readTree(text(client, tool, "{\"id\":1}")));
            }
            JsonNode people = JSON.readTree(text(client, "app_people", "{}")).path("all");
            assertThat(people.size()).isEqualTo(2);
            people.forEach(entity -> McpEntityResultTest.assertWhitelisted(softly, "app_people", entity));
            softly.assertAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE, ASYNC_STREAMABLE, STATELESS_ASYNC})
    void otherResultsOfApplicationToolsAreSpringAiJson(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(McpOwnProviderFixtures.OwnProviderApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            assertThat(text(client, "app_address", "{}"))
                    .isEqualTo(SPRING_AI.convert(McpOwnProviderFixtures.address(), McpOwnProviderFixtures.Address.class));
            assertThat(text(client, "app_map", "{}"))
                    .isEqualTo(SPRING_AI.convert(McpOwnProviderFixtures.map(), Map.class));
            assertThat(text(client, "app_text", "{\"name\":\"Ada\"}"))
                    .isEqualTo(SPRING_AI.convert("hi Ada", String.class));
            assertThat(text(client, "app_void", "{}")).isEqualTo(SPRING_AI.convert(null, void.class));
            assertThat(text(client, "get_dto", "{}"))
                    .isEqualTo(SPRING_AI.convert(McpEntityFixtures.dto(), McpEntityFixtures.PersonDto.class));
            // A converter the tool names itself, and an opaque callback, are left as they are
            assertThat(text(client, "app_own_converter", "{}")).isEqualTo(McpEntityFixtures.FixedConverter.RESULT);
            assertThat(text(client, "own_converter", "{}")).isEqualTo(McpEntityFixtures.FixedConverter.RESULT);
            assertThat(text(client, McpOwnProviderFixtures.OPAQUE_ECHO, "{}"))
                    .isEqualTo("\"" + McpOwnProviderFixtures.OpaqueCallback.RESULT + "\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE, ASYNC_STREAMABLE, STATELESS_ASYNC})
    void protectedCallbacksKeepTheirDefinitionAndReturnDirect(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(McpOwnProviderFixtures.OwnProviderApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            // The application's own beans are left as they are: only the callbacks the MCP server serves change
            ToolCallback raw = named(context, "app_direct");
            assertThat(raw.call("{}")).contains(SSN);
            AgentSafeToolCallbacks protection = context.getBean(AgentSafeToolCallbacks.class);
            ToolCallback direct = protection.protect(raw, "test");
            assertThat(direct.getToolDefinition()).isEqualTo(raw.getToolDefinition());
            assertThat(direct.getToolMetadata().returnDirect()).isTrue();
            assertThat(protection.protect(named(context, "app_person"), "test").getToolMetadata().returnDirect())
                    .isFalse();
            assertThat(direct.call("{}")).isEqualTo("{\"id\":1,\"name\":\"Ada\"}");
            // In process, as for ChatClient.toolCallbacks
            ToolCallbackProvider appProvider = context.getBean("appToolProvider", ToolCallbackProvider.class);
            for (ToolCallback callback : protection.agentSafe(appProvider)) {
                if (callback.getToolDefinition().name().equals("app_person")) {
                    assertThat(callback.call("{}")).isEqualTo("{\"id\":1,\"name\":\"Ada\"}");
                }
            }
            List<String> names = new ArrayList<>();
            client.request("tools/list", "{}").path("tools").forEach(tool -> {
                names.add(tool.path("name").asText());
                if (tool.path("name").asText().equals("app_direct")) {
                    assertThat(tool.path("description").asText()).isEqualTo("app_direct");
                }
            });
            assertThat(names).contains("app_direct", "get_person", "fn_person", "listed_person",
                    McpOwnProviderFixtures.OPAQUE_ECHO).doesNotHaveDuplicates();
            assertThat(text(client, "app_direct", "{}")).isEqualTo("{\"id\":1,\"name\":\"Ada\"}");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE, ASYNC_STREAMABLE, STATELESS_ASYNC})
    void opaqueCallbackServingAGeneratedToolFailsStartup(String server) {
        assertThatThrownBy(() -> start(McpOwnProviderFixtures.OpaqueGeneratedToolApplication.class, server).close())
                .satisfies(e -> assertThat(rootCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("MCP tool 'get_person'")
                        .hasMessageContaining("ToolCallbackProvider bean 'opaquePersonProvider'")
                        .hasMessageContaining(McpOwnProviderFixtures.OpaqueCallback.class.getName())
                        .hasMessageContaining("MethodToolCallbackProvider"));
    }

    @Test
    void opaqueGeneratedToolRemedyNamesTheSwitchWhenAiAtlasMcpIsOff() {
        assertThatThrownBy(() -> start(McpOwnProviderFixtures.OpaqueGeneratedToolApplication.class, SYNC_STREAMABLE,
                "ai.atlas.mcp.enabled=false").close())
                .satisfies(e -> assertThat(rootCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("MCP tool 'get_person'")
                        .hasMessageContaining("set ai.atlas.mcp.enabled=true so AI-ATLAS registers it"));
        assertThatThrownBy(() -> start(McpOwnProviderFixtures.OpaqueGeneratedToolApplication.class, SYNC_STREAMABLE)
                .close())
                .satisfies(e -> assertThat(rootCause(e)).hasMessageNotContaining("ai.atlas.mcp.enabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_STREAMABLE, STATELESS})
    void opaqueCallbackNamedLikeASkippedInterfaceProxiedToolIsOnlyReported(String server, CapturedOutput output)
            throws Exception {
        try (ConfigurableApplicationContext context = start(
                McpOwnProviderFixtures.InterfaceProxiedServiceApplication.class, server);
             RawMcpClient client = client(server, context)) {
            client.initialize();
            assertThat(text(client, McpOwnProviderFixtures.INTERFACE_TOOL, "{}"))
                    .isEqualTo("\"" + McpOwnProviderFixtures.OpaqueCallback.RESULT + "\"");
            assertThat(output).containsOnlyOnce("MCP tool '" + McpOwnProviderFixtures.INTERFACE_TOOL
                    + "' is served by ToolCallbackProvider bean 'opaqueGreetingProvider'");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_STREAMABLE, STATELESS, ASYNC_STREAMABLE})
    void unrelatedOpaqueCallbackIsServedWithOneWarning(String server, CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = start(McpOwnProviderFixtures.OwnProviderApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();
            client.request("tools/list", "{}");
            assertThat(output).containsOnlyOnce("MCP tool '"
                    + McpOwnProviderFixtures.OPAQUE_ECHO + "' is served by ToolCallbackProvider bean "
                    + "'opaqueToolProvider' through a " + McpOwnProviderFixtures.OpaqueCallback.class.getName())
                    .contains("its results are not whitelisted");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE})
    void withoutToolCallbackConversionTheGeneratedToolKeepsTheWhitelist(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(McpOwnProviderFixtures.OwnProviderApplication.class,
                server, NO_CONVERSION);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            assertThat(text(client, "get_person", "{\"id\":1}")).isEqualTo("{\"id\":1,\"name\":\"Ada\"}");
            // Spring AI serves none of the application's provider tools, so no opaque callback is served either
            try (ConfigurableApplicationContext opaque = start(
                    McpOwnProviderFixtures.OpaqueGeneratedToolApplication.class, server, NO_CONVERSION)) {
                assertThat(opaque.isActive()).isTrue();
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    static ConfigurableApplicationContext start(Class<?> application, String server, String... extra) {
        List<String> properties = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off"));
        switch (server) {
            case SYNC_SSE -> properties.add("spring.ai.mcp.server.protocol=SSE");
            case SYNC_STREAMABLE -> properties.add("spring.ai.mcp.server.protocol=STREAMABLE");
            case STATELESS -> properties.add("spring.ai.mcp.server.protocol=STATELESS");
            case ASYNC_SSE -> {
                properties.add("spring.ai.mcp.server.protocol=SSE");
                properties.add("spring.ai.mcp.server.type=ASYNC");
            }
            case STATELESS_ASYNC -> {
                properties.add("spring.ai.mcp.server.protocol=STATELESS");
                properties.add("spring.ai.mcp.server.type=ASYNC");
            }
            case ASYNC_STREAMABLE -> {
                properties.add("spring.ai.mcp.server.protocol=STREAMABLE");
                properties.add("spring.ai.mcp.server.type=ASYNC");
            }
            default -> throw new IllegalArgumentException(server);
        }
        properties.addAll(List.of(extra));
        return new SpringApplicationBuilder(application).properties(properties.toArray(String[]::new)).run();
    }

    static RawMcpClient client(String server, ConfigurableApplicationContext context) throws Exception {
        String base = McpToolSpecificationTest.baseUrl(context);
        return server.endsWith("SSE") ? new RawMcpClient.Sse(base) : new RawMcpClient.Streamable(base);
    }

    static String text(RawMcpClient client, String tool, String arguments) throws Exception {
        JsonNode result = client.call(tool, arguments);
        assertThat(result.path("isError").asBoolean()).as(tool + ": " + result).isFalse();
        return result.path("content").get(0).path("text").asText();
    }

    /** The callback an application's provider bean serves under {@code name}. */
    private static ToolCallback named(ConfigurableApplicationContext context, String name) {
        for (ToolCallbackProvider provider : context.getBeansOfType(ToolCallbackProvider.class).values()) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                if (callback.getToolDefinition().name().equals(name)) {
                    return callback;
                }
            }
        }
        throw new AssertionError("No tool " + name);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
