/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-018: on a SYNC MCP server, a tool the application's own {@code ToolCallbackProvider} bean also
 * provides is registered once. While Spring AI's tool-callback conversion is on, the provider takes
 * precedence; with it off, AI-ATLAS registers the tool with its generated schema and hints. Each
 * test starts the real application on a random port and speaks raw JSON-RPC over MCP SSE and
 * Streamable HTTP.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpToolProviderPrecedenceTest {

    private static final String ORDERS_FIXTURE = "mcp-tools/orders/";
    private static final String PING_FIXTURE = "mcp-tools/ping/";
    private static final String FIND_ORDERS = McpToolFixtures.FIND_ORDERS;
    private static final String PLACE_ORDER = McpToolFixtures.PLACE_ORDER;
    private static final String PING = McpToolFixtures.PING;
    private static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    private static final String SSE = "SSE";
    private static final String STREAMABLE = "STREAMABLE";

    @ParameterizedTest
    @ValueSource(strings = {SSE, STREAMABLE})
    void applicationOwnProviderTakesPrecedenceAndEachNameIsRegisteredOnce(String transport, CapturedOutput output)
            throws Exception {
        boolean streamable = STREAMABLE.equals(transport);
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(McpToolFixtures.OwnProviderApplication.class)
                .resourceLoader(new DefaultResourceLoader(McpToolSpecificationTest.fixtures(ORDERS_FIXTURE)))
                .properties("server.port=0", "spring.main.banner-mode=off", PROTOCOL_PROPERTY + "=" + transport)
                .run();
             RawMcpClient client = streamable ? new RawMcpClient.Streamable(McpToolSpecificationTest.baseUrl(context))
                     : new RawMcpClient.Sse(McpToolSpecificationTest.baseUrl(context))) {
            client.initialize();
            List<String> names = new ArrayList<>();
            client.request("tools/list", "{}").path("tools").forEach(tool -> names.add(tool.path("name").asText()));

            assertThat(names).containsExactlyInAnyOrder(FIND_ORDERS, PLACE_ORDER, PING);
            assertThat(output).containsOnlyOnce("MCP tool '" + PING + "' is registered by the application's own "
                    + "ToolCallbackProvider bean 'pingToolProvider'");
            JsonNode pong = client.call(PING, "{\"message\":\"hi\"}");
            assertThat(pong.path("isError").asBoolean()).as(pong.toString()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SSE, STREAMABLE})
    void withoutToolCallbackConversionAiAtlasRegistersTheApplicationProvidedTool(String transport,
                                                                                 CapturedOutput output)
            throws Exception {
        boolean streamable = STREAMABLE.equals(transport);
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(McpToolFixtures.OwnProviderApplication.class)
                .resourceLoader(new DefaultResourceLoader(McpToolSpecificationTest.fixtures(ORDERS_FIXTURE, PING_FIXTURE)))
                .properties("server.port=0", "spring.main.banner-mode=off", PROTOCOL_PROPERTY + "=" + transport,
                        "spring.ai.mcp.server.tool-callback-converter=false")
                .run();
             RawMcpClient client = streamable ? new RawMcpClient.Streamable(McpToolSpecificationTest.baseUrl(context))
                     : new RawMcpClient.Sse(McpToolSpecificationTest.baseUrl(context))) {
            client.initialize();
            List<String> names = new ArrayList<>();
            Map<String, JsonNode> tools = new LinkedHashMap<>();
            client.request("tools/list", "{}").path("tools").forEach(tool -> {
                names.add(tool.path("name").asText());
                tools.put(tool.path("name").asText(), tool);
            });

            // Spring AI does not register the application's provider, so AI-ATLAS registers every tool
            assertThat(names).containsExactlyInAnyOrder(FIND_ORDERS, PLACE_ORDER, PING);
            JsonNode ping = tools.get(PING);
            assertThat(ping.at("/inputSchema/properties/message/minLength").asInt()).isEqualTo(1);
            assertThat(ping.path("annotations").path("readOnlyHint").asBoolean(false)).isTrue();
            assertThat(ping.path("annotations").path("idempotentHint").asBoolean(false)).isTrue();
            assertThat(output).doesNotContain("is registered by the application's own ToolCallbackProvider")
                    .doesNotContain("no @Tool method registers it");
            JsonNode pong = client.call(PING, "{\"message\":\"hi\"}");
            assertThat(pong.path("isError").asBoolean()).as(pong.toString()).isFalse();
        }
    }
}
