/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * FR-016: with {@code spring.ai.mcp.server.protocol=STREAMABLE}, the Atlas tools are served over
 * MCP Streamable HTTP at Spring AI's streamable endpoint, and an MCP {@code tools/list} over that
 * endpoint returns the same tool names the SSE transport serves for the same tool beans.
 *
 * <p>Both contexts are started through the auto-configurations actually registered in the
 * {@code AutoConfiguration.imports} files (the Atlas auto-configuration plus Spring AI's MCP server
 * stack), the same way {@code SseUnchangedTest} starts the SSE path. The streamable exchange is
 * driven through MockMvc — in-process, no network I/O.
 */
class StreamableHttpTransportTest {

    private static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    private static final String STREAMABLE = "STREAMABLE";
    private static final String PROVIDER_BEAN_METHOD = "agenticToolCallbackProvider";
    private static final String ATLAS_AUTO_CONFIGURATION =
            "com.egoge.ai.atlas.runtime.autoconfigure.AgenticAutoConfiguration";
    private static final String MCP_SERVER_AUTO_CONFIGURATION_PACKAGE =
            "org.springframework.ai.mcp.server.";
    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String STREAMABLE_ENDPOINT = "/mcp";
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String ACCEPT_BOTH = "application/json, text/event-stream";
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String SSE_DATA_PREFIX = "data:";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(10);
    private static final String ECHO_TOOL = "echo_message";
    private static final String REVERSE_TOOL = "reverse_message";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(registeredMcpBootPath())
            .withUserConfiguration(AtlasToolConfiguration.class);

    @Test
    void streamablePropertySwitchesTransportAndKeepsAtlasProvider() {
        runner.withPropertyValues(PROTOCOL_PROPERTY + "=" + STREAMABLE).run(context -> {
            assertThat(context).hasBean(PROVIDER_BEAN_METHOD);
            assertThat(context).hasSingleBean(McpSyncServer.class);
            assertThat(context).hasSingleBean(WebMvcStreamableServerTransportProvider.class);
            assertThat(context).doesNotHaveBean(WebMvcSseServerTransportProvider.class);
        });
    }

    @Test
    void toolsListedOverStreamableEndpointEqualThoseServedOverSse() {
        List<String> sseToolNames = new ArrayList<>();
        runner.run(context -> {
            // No property: the SSE path, as SseUnchangedTest pins it.
            assertThat(context).hasSingleBean(WebMvcSseServerTransportProvider.class);
            sseToolNames.addAll(registeredToolNames(context));
        });
        assertThat(sseToolNames).containsExactlyInAnyOrder(ECHO_TOOL, REVERSE_TOOL);

        runner.withPropertyValues(PROTOCOL_PROPERTY + "=" + STREAMABLE).run(context -> {
            List<String> streamableToolNames = listToolsOverStreamableHttp(context);
            assertThat(streamableToolNames)
                    .containsExactlyInAnyOrderElementsOf(sseToolNames);
        });
    }

    /** Runs an MCP initialize → initialized → tools/list exchange against {@code POST /mcp}. */
    private static List<String> listToolsOverStreamableHttp(WebApplicationContext context)
            throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        MockHttpServletResponse initialize = mvc.perform(jsonRpc(null,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                                + "{\"protocolVersion\":\"" + PROTOCOL_VERSION + "\","
                                + "\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"atlas-test\",\"version\":\"1\"}}}"))
                .andReturn().getResponse();
        assertThat(initialize.getStatus()).isEqualTo(HttpStatus.OK.value());
        String sessionId = initialize.getHeader(SESSION_HEADER);
        assertThat(sessionId).isNotBlank();

        MockHttpServletResponse initialized = mvc.perform(jsonRpc(sessionId,
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andReturn().getResponse();
        assertThat(initialized.getStatus()).isEqualTo(HttpStatus.ACCEPTED.value());

        MvcResult toolsList = mvc.perform(jsonRpc(sessionId,
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andReturn();
        JsonNode result = awaitJsonRpcResult(toolsList.getResponse());

        List<String> names = new ArrayList<>();
        result.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private static RequestBuilder jsonRpc(String sessionId, String body) {
        var request = post(STREAMABLE_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Accept", ACCEPT_BOTH)
                .content(body);
        if (sessionId != null) {
            request.header(SESSION_HEADER, sessionId);
        }
        return request;
    }

    /**
     * The streamable transport answers a request either as a JSON body or as an SSE stream whose
     * {@code data:} line carries the JSON-RPC response; waits for it and returns its
     * {@code result}.
     */
    private static JsonNode awaitJsonRpcResult(MockHttpServletResponse response) throws Exception {
        long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            String content = response.getContentAsString(StandardCharsets.UTF_8);
            for (String line : content.split("\n")) {
                String payload = line.startsWith(SSE_DATA_PREFIX)
                        ? line.substring(SSE_DATA_PREFIX.length()).trim()
                        : line.trim();
                if (payload.startsWith("{")) {
                    JsonNode message = JSON.readTree(payload);
                    if (message.has("result")) {
                        return message.get("result");
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No tools/list response over Streamable HTTP within "
                + RESPONSE_TIMEOUT + "; got: "
                + response.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * The registered Atlas + MCP server auto-configurations, plus the Boot web MVC infrastructure
     * MockMvc dispatches through (a deployed app gets it from its own auto-configuration).
     */
    private static AutoConfigurations registeredMcpBootPath() {
        List<String> imports = autoConfigurationImports();
        assertThat(imports).contains(
                ATLAS_AUTO_CONFIGURATION,
                McpServerStreamableHttpWebMvcAutoConfiguration.class.getName());
        List<Class<?>> path = new ArrayList<>(imports.stream()
                .filter(name -> name.equals(ATLAS_AUTO_CONFIGURATION)
                        || name.startsWith(MCP_SERVER_AUTO_CONFIGURATION_PACKAGE))
                .map(StreamableHttpTransportTest::loadAutoConfiguration)
                .toList());
        path.addAll(List.of(JacksonAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class,
                DispatcherServletAutoConfiguration.class,
                WebMvcAutoConfiguration.class));
        return AutoConfigurations.of(path.toArray(Class<?>[]::new));
    }

    private static Class<?> loadAutoConfiguration(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Registered auto-configuration not loadable: " + className, e);
        }
    }

    /** Names of every tool specification handed to the MCP server in the given context. */
    private static List<String> registeredToolNames(ApplicationContext context) {
        ResolvableType specificationList = ResolvableType.forClassWithGenerics(
                List.class, McpServerFeatures.SyncToolSpecification.class);
        return Arrays.stream(context.getBeanNamesForType(specificationList))
                .map(name -> {
                    @SuppressWarnings("unchecked")
                    List<McpServerFeatures.SyncToolSpecification> specifications =
                            (List<McpServerFeatures.SyncToolSpecification>) context.getBean(name);
                    return specifications;
                })
                .flatMap(List::stream)
                .map(specification -> specification.tool().name())
                .toList();
    }

    /** Every auto-configuration registered on the test classpath, across all jars. */
    private static List<String> autoConfigurationImports() {
        List<String> imports = new ArrayList<>();
        try {
            Enumeration<URL> resources = StreamableHttpTransportTest.class.getClassLoader()
                    .getResources(AUTO_CONFIGURATION_IMPORTS);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                    reader.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(imports::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return imports;
    }

    @Configuration
    static class AtlasToolConfiguration {

        @Bean
        MessageToolService messageToolService() {
            return new MessageToolService();
        }
    }

    /** The shape of a generated MCP tool bean: a {@code @Service} with {@code @Tool} methods. */
    @Service
    static class MessageToolService {

        @Tool(name = ECHO_TOOL, description = "Echoes the given message back to the caller.")
        public String echo(String message) {
            return message;
        }

        @Tool(name = REVERSE_TOOL, description = "Returns the given message reversed.")
        public String reverse(String message) {
            return new StringBuilder(message).reverse().toString();
        }
    }
}
