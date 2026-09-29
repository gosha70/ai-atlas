/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.json.AgentSafeModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code AgentSafeModule} MCP tool results are written with: the application's own module bean
 * when it declares one, and the {@code ai.atlas.json.*} settings otherwise, also for a converter
 * Spring AI instantiates reflectively from {@code @Tool(resultConverter)}.
 */
class McpAgentSafeModuleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SSN = McpEntityFixtures.SSN;

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_SSE", "SYNC_STREAMABLE", "STATELESS"})
    void applicationsOwnModuleReplacesTheDefaultOne(String server) throws Exception {
        try (ConfigurableApplicationContext context = McpOwnProviderResultTest.start(
                McpOwnProviderFixtures.OwnModuleApplication.class, server);
             RawMcpClient client = McpOwnProviderResultTest.client(server, context)) {
            client.initialize();

            assertThat(context.getBeansOfType(AgentSafeModule.class)).containsOnlyKeys("appModule");
            JsonNode person = JSON.readTree(McpOwnProviderResultTest.text(client, "get_person", "{\"id\":1}"));
            assertEnriched(person, "Ada");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_SSE", "SYNC_STREAMABLE", "STATELESS"})
    void reflectivelyInstantiatedConverterHonoursTheJsonSettings(String server) throws Exception {
        try (ConfigurableApplicationContext context = McpOwnProviderResultTest.start(
                McpOwnProviderFixtures.OptedInApplication.class, server, "ai.atlas.json.enriched=true");
             RawMcpClient client = McpOwnProviderResultTest.client(server, context)) {
            client.initialize();

            // A Vip serializes as its entity, Person
            assertEnriched(JSON.readTree(McpOwnProviderResultTest.text(client, "opted_in", "{}")), "Grace");
            assertEnriched(JSON.readTree(McpOwnProviderResultTest.text(client, "get_person", "{\"id\":1}")), "Ada");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_STREAMABLE"})
    void reflectivelyInstantiatedConverterIsFlatByDefault(String server) throws Exception {
        try (ConfigurableApplicationContext context = McpOwnProviderResultTest.start(
                McpOwnProviderFixtures.OptedInApplication.class, server);
             RawMcpClient client = McpOwnProviderResultTest.client(server, context)) {
            client.initialize();

            assertThat(McpOwnProviderResultTest.text(client, "opted_in", "{}"))
                    .isEqualTo("{\"id\":2,\"name\":\"Grace\"}");
        }
    }

    /** Enriched JSON of the {@code Person} entity: its type, and each whitelisted field as a value. */
    private static void assertEnriched(JsonNode entity, String name) {
        List<String> fields = new ArrayList<>();
        entity.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as(entity.toString()).containsExactlyInAnyOrder("typeInfo", "id", "name");
        assertThat(entity.at("/typeInfo/name").asText()).isEqualTo("Person");
        assertThat(entity.at("/name/value").asText()).isEqualTo(name);
        assertThat(entity.toString()).doesNotContain(SSN);
    }
}
