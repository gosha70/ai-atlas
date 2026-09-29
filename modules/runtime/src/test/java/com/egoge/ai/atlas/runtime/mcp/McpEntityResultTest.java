/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #50: an MCP tool result that is, or holds, an {@code @AgenticEntity} or an unannotated
 * subtype of one reaches the client through the entity's {@code @AgenticField} getters only, on
 * every MCP server type and transport; every other result keeps the JSON Spring AI writes for it.
 */
class McpEntityResultTest {

    private static final String SSN = McpEntityFixtures.SSN;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultToolCallResultConverter SPRING_AI = new DefaultToolCallResultConverter();
    /** Raw entities (no {@code returnType}), unchecked shapes, an unannotated subtype and a DTO holding one. */
    private static final List<String> ENTITY_TOOLS = List.of("get_person", "list_people", "people_by_key",
            "stream_people", "nested_people", "people_array", "super_people", "get_vip", "get_envelope");

    /** Server type and protocol, and the client transport. */
    private static final String SYNC_SSE = "SYNC_SSE";
    private static final String SYNC_STREAMABLE = "SYNC_STREAMABLE";
    private static final String STATELESS = "STATELESS";
    private static final String ASYNC_SSE = "ASYNC_SSE";

    // ---- over a real MCP transport -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE})
    void entityResultsKeepTheWhitelistOverMcp(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            SoftAssertions softly = new SoftAssertions();
            for (String tool : ENTITY_TOOLS) {
                JsonNode result = result(client, tool, "{\"id\":1}");
                entities(tool, result).forEach(entity -> assertWhitelisted(softly, tool, entity));
            }
            softly.assertAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SYNC_SSE, SYNC_STREAMABLE, STATELESS, ASYNC_SSE})
    void otherResultsKeepSpringAiJsonOverMcp(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            assertThat(text(client, "get_dto", "{}"))
                    .isEqualTo(SPRING_AI.convert(McpEntityFixtures.dto(), McpEntityFixtures.PersonDto.class));
            assertThat(text(client, "greet", "{\"name\":\"Ada\"}"))
                    .isEqualTo(SPRING_AI.convert("hello Ada", String.class));
            assertThat(text(client, "forget", "{\"id\":1}")).isEqualTo(SPRING_AI.convert(null, void.class));
        }
    }

    @Test
    void envelopeWithASubtypeKeepsTheWhitelistOverRest() throws Exception {
        try (ConfigurableApplicationContext context = start(SYNC_STREAMABLE)) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create(McpToolSpecificationTest.baseUrl(context) + "/envelope")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode envelope = JSON.readTree(response.body());
            assertThat(envelope.path("label").asText()).isEqualTo("vip");
            assertThat(envelope.at("/person/name").asText()).isEqualTo("Grace");
            SoftAssertions softly = new SoftAssertions();
            assertWhitelisted(softly, "GET /envelope", envelope.path("person"));
            softly.assertAll();
        }
    }

    // ---- the callbacks AI-ATLAS derives through Spring AI's MethodToolCallbackProvider ---------------

    @Test
    void derivedCallbacksKeepTheWhitelist() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenticMcpConfiguration.class))
                .withUserConfiguration(McpEntityFixtures.ToolBeans.class)
                .withPropertyValues("spring.ai.mcp.server.protocol=STATELESS")
                .run(context -> {
                    ToolCallback[] callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();

                    SoftAssertions softly = new SoftAssertions();
                    for (String tool : ENTITY_TOOLS) {
                        JsonNode result = JSON.readTree(named(callbacks, tool).call("{\"id\":1}"));
                        entities(tool, result).forEach(entity -> assertWhitelisted(softly, tool, entity));
                    }
                    softly.assertAll();
                    assertThat(named(callbacks, "get_dto").call("{}"))
                            .isEqualTo(SPRING_AI.convert(McpEntityFixtures.dto(), McpEntityFixtures.PersonDto.class));
                    assertThat(named(callbacks, "forget").call("{\"id\":1}"))
                            .isEqualTo(SPRING_AI.convert(null, void.class));
                    assertThat(named(callbacks, "greet").call("{\"name\":\"Ada\"}"))
                            .isEqualTo(SPRING_AI.convert("hello Ada", String.class));
                });
    }

    @Test
    void toolNamingItsOwnResultConverterKeepsIt() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenticMcpConfiguration.class))
                .withUserConfiguration(McpEntityFixtures.ToolBeans.class)
                .withPropertyValues("spring.ai.mcp.server.protocol=STATELESS")
                .run(context -> assertThat(named(context.getBean(ToolCallbackProvider.class).getToolCallbacks(),
                        "own_converter").call("{}")).isEqualTo(McpEntityFixtures.FixedConverter.RESULT));
    }

    @Test
    void applicationOwnProviderCanOptInThroughTheToolAnnotation() throws Exception {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new McpEntityFixtures.OptedInTool()).build().getToolCallbacks();

        SoftAssertions softly = new SoftAssertions();
        assertWhitelisted(softly, "opted_in", JSON.readTree(named(callbacks, "opted_in").call("{}")));
        softly.assertAll();
    }

    @Test
    void converterWritesEntitiesThroughTheWhitelistAndEverythingElseAsSpringAi() throws Exception {
        AgentSafeToolCallResultConverter converter = new AgentSafeToolCallResultConverter();

        SoftAssertions softly = new SoftAssertions();
        assertWhitelisted(softly, "vip", JSON.readTree(converter.convert(McpEntityFixtures.vip(),
                McpEntityFixtures.Vip.class)));
        softly.assertAll();
        Object[][] unchanged = {
                {McpEntityFixtures.dto(), McpEntityFixtures.PersonDto.class},
                {List.of(McpEntityFixtures.dto()), List.class},
                {"plain text", String.class},
                {"{\"already\":\"json\"}", String.class},
                {null, void.class},
                {null, Object.class},
                {42, int.class},
                {Map.of("k", LocalDate.of(2026, 1, 2)), Map.class},
        };
        for (Object[] value : unchanged) {
            assertThat(converter.convert(value[0], (Class<?>) value[1])).as(String.valueOf(value[0]))
                    .isEqualTo(SPRING_AI.convert(value[0], (Class<?>) value[1]));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** The entities, or entity subtypes, a tool's result holds. */
    private static List<JsonNode> entities(String tool, JsonNode result) {
        return switch (tool) {
            case "get_person", "get_vip" -> List.of(result);
            case "list_people", "stream_people", "people_array", "super_people" -> {
                List<JsonNode> items = new ArrayList<>();
                result.forEach(items::add);
                assertThat(items).as(tool).isNotEmpty();
                yield items;
            }
            case "people_by_key" -> List.of(result.path("ada"));
            case "nested_people" -> List.of(result.path(0).path(0));
            case "get_envelope" -> List.of(result.path("person"));
            default -> throw new IllegalArgumentException(tool);
        };
    }

    /** Exactly the entity's {@code @AgenticField} getters: {@code id} and {@code name}. */
    static void assertWhitelisted(SoftAssertions softly, String where, JsonNode entity) {
        List<String> fields = new ArrayList<>();
        entity.fieldNames().forEachRemaining(fields::add);
        softly.assertThat(fields).as(where + ": " + entity).containsExactlyInAnyOrder("id", "name");
        softly.assertThat(entity.toString()).as(where).doesNotContain(SSN);
    }

    private static ConfigurableApplicationContext start(String server) {
        List<String> properties = new ArrayList<>(List.of("server.port=0", "spring.main.banner-mode=off"));
        switch (server) {
            case SYNC_SSE -> properties.add("spring.ai.mcp.server.protocol=SSE");
            case SYNC_STREAMABLE -> properties.add("spring.ai.mcp.server.protocol=STREAMABLE");
            case STATELESS -> properties.add("spring.ai.mcp.server.protocol=STATELESS");
            case ASYNC_SSE -> {
                properties.add("spring.ai.mcp.server.protocol=SSE");
                properties.add("spring.ai.mcp.server.type=ASYNC");
            }
            default -> throw new IllegalArgumentException(server);
        }
        return new SpringApplicationBuilder(McpEntityFixtures.EntityToolApplication.class)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    private static RawMcpClient client(String server, ConfigurableApplicationContext context) throws Exception {
        String base = McpToolSpecificationTest.baseUrl(context);
        return server.endsWith("SSE") ? new RawMcpClient.Sse(base) : new RawMcpClient.Streamable(base);
    }

    private static String text(RawMcpClient client, String tool, String arguments) throws Exception {
        JsonNode result = client.call(tool, arguments);
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isFalse();
        return result.path("content").get(0).path("text").asText();
    }

    private static JsonNode result(RawMcpClient client, String tool, String arguments) throws Exception {
        return JSON.readTree(text(client, tool, arguments));
    }

    private static ToolCallback named(ToolCallback[] callbacks, String name) {
        for (ToolCallback callback : callbacks) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new AssertionError("No tool " + name);
    }
}
