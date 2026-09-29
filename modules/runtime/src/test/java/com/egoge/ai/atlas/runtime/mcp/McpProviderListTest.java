/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static com.egoge.ai.atlas.runtime.mcp.McpOwnProviderResultTest.client;
import static com.egoge.ai.atlas.runtime.mcp.McpOwnProviderResultTest.start;
import static com.egoge.ai.atlas.runtime.mcp.McpOwnProviderResultTest.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #50, for a {@code List<ToolCallbackProvider>} bean: Spring AI's MCP server reads such a list
 * before every other provider and keeps the first callback of each name, so a generated tool in it
 * would win over the one AI-ATLAS registers. Each tool it serves keeps the {@code @AgenticField}
 * whitelist, on every transport, and a generated tool in it is registered once.
 */
class McpProviderListTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_SSE", "SYNC_STREAMABLE", "STATELESS", "ASYNC_SSE", "ASYNC_STREAMABLE", "STATELESS_ASYNC"})
    void generatedAndApplicationToolsInAProviderListKeepTheWhitelist(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(McpProviderListFixtures.ProviderListApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            SoftAssertions softly = new SoftAssertions();
            for (String tool : List.of("get_person", "app_person", "app_direct")) {
                McpEntityResultTest.assertWhitelisted(softly, tool, JSON.readTree(text(client, tool, "{\"id\":1}")));
            }
            softly.assertAll();
            List<String> names = new ArrayList<>();
            client.request("tools/list", "{}").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
            assertThat(names).contains("get_person", "app_person").doesNotHaveDuplicates();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_STREAMABLE", "STATELESS", "ASYNC_STREAMABLE"})
    void applicationOnlyProviderListKeepsTheWhitelist(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(
                McpProviderListFixtures.AppOnlyProviderListApplication.class, server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            SoftAssertions softly = new SoftAssertions();
            McpEntityResultTest.assertWhitelisted(softly, "app_person",
                    JSON.readTree(text(client, "app_person", "{}")));
            softly.assertAll();
        }
    }
}
