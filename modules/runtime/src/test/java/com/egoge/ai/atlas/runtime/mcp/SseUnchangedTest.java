/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.autoconfigure.McpServerSseWebMvcAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-008 regression guard: the standalone CLI / STDIO MCP surfaces must leave the runtime SSE MCP
 * path untouched — it remains the default for live tool serving from a deployed Spring Boot app.
 *
 * <p>Pins the three things that make up that path:
 * <ul>
 *   <li>{@link AgenticMcpConfiguration}'s behaviour — the lazy {@link ToolCallbackProvider} is
 *       registered by default, honours {@code ai.atlas.mcp.enabled}, and discovers
 *       {@code @Service} beans with {@code @Tool} methods (the shape of generated MCP tools).</li>
 *   <li>Its wiring contract — the conditional annotations and single bean method, so a change to
 *       the configuration class itself fails here rather than silently altering the SSE path.</li>
 *   <li>The transport — Spring AI's {@code McpServerSseWebMvcAutoConfiguration} is still
 *       registered, a servlet context wires the SDK's SSE transport provider and its router
 *       serving {@code GET /sse} / {@code POST /mcp/message}, and the default endpoint
 *       properties are unchanged. This module's own wiring registers no STDIO transport
 *       (STDIO lives only in the standalone {@code mcp-stdio} module).</li>
 * </ul>
 */
class SseUnchangedTest {

    private static final String PROVIDER_BEAN_METHOD = "agenticToolCallbackProvider";
    private static final String ENABLED_PROPERTY_PREFIX = "ai.atlas.mcp";
    private static final String ENABLED_PROPERTY_NAME = "enabled";
    private static final String ATLAS_AUTO_CONFIGURATION =
            "com.egoge.ai.atlas.runtime.autoconfigure.AgenticAutoConfiguration";
    private static final String SSE_ROUTER_BEAN = "webMvcSseServerRouterFunction";
    private static final String SSE_ENDPOINT = "/sse";
    private static final String SSE_MESSAGE_ENDPOINT = "/mcp/message";
    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String TOOL_NAME = "echo_message";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenticMcpConfiguration.class));

    @Test
    void toolCallbackProviderRegisteredByDefault() {
        runner.run(context -> {
            assertThat(context).hasBean(PROVIDER_BEAN_METHOD);
            assertThat(context).hasSingleBean(ToolCallbackProvider.class);
        });
    }

    @Test
    void toolCallbackProviderAbsentWhenDisabled() {
        runner.withPropertyValues(ENABLED_PROPERTY_PREFIX + "." + ENABLED_PROPERTY_NAME + "=false")
                .run(context -> assertThat(context).doesNotHaveBean(ToolCallbackProvider.class));
    }

    @Test
    void providerDiscoversServiceBeansWithToolMethods() {
        runner.withUserConfiguration(EchoToolConfiguration.class).run(context -> {
            ToolCallback[] callbacks =
                    context.getBean(ToolCallbackProvider.class).getToolCallbacks();
            assertThat(callbacks).hasSize(1);
            assertThat(callbacks[0].getToolDefinition().name()).isEqualTo(TOOL_NAME);
        });
    }

    @Test
    void providerIsEmptyWithoutToolBeans() {
        runner.withUserConfiguration(PlainServiceConfiguration.class).run(context ->
                assertThat(context.getBean(ToolCallbackProvider.class).getToolCallbacks())
                        .isEmpty());
    }

    @Test
    void mcpWiringContractUnchanged() {
        ConditionalOnClass onClass =
                AgenticMcpConfiguration.class.getAnnotation(ConditionalOnClass.class);
        assertThat(onClass).isNotNull();
        assertThat(onClass.value()).containsExactly(ToolCallbackProvider.class);

        ConditionalOnProperty onProperty =
                AgenticMcpConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(onProperty).isNotNull();
        assertThat(onProperty.prefix()).isEqualTo(ENABLED_PROPERTY_PREFIX);
        assertThat(onProperty.name()).containsExactly(ENABLED_PROPERTY_NAME);
        assertThat(onProperty.matchIfMissing()).isTrue();

        // The configuration contributes exactly one bean — the tool callback provider. A second
        // bean method (e.g. a transport provider) would mean the runtime wiring grew beyond the
        // frozen SSE path.
        List<Method> beanMethods = Arrays.stream(AgenticMcpConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .toList();
        assertThat(beanMethods).hasSize(1);
        assertThat(beanMethods.get(0).getName()).isEqualTo(PROVIDER_BEAN_METHOD);
        assertThat(beanMethods.get(0).getReturnType()).isEqualTo(ToolCallbackProvider.class);
    }

    @Test
    void runtimeAutoConfigurationStillRegistered() {
        assertThat(autoConfigurationImports()).contains(ATLAS_AUTO_CONFIGURATION);
    }

    @Test
    void sseAutoConfigurationStillRegistered() {
        // The exact SSE auto-configuration — not just any class under the MCP server package —
        // so removing the SSE auto-configuration (while e.g. streamable-HTTP remains) fails here.
        assertThat(autoConfigurationImports())
                .contains(McpServerSseWebMvcAutoConfiguration.class.getName());
    }

    @Test
    void sseServerBeansAndRouteStillServed() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        McpServerObjectMapperAutoConfiguration.class,
                        McpServerSseWebMvcAutoConfiguration.class))
                .run(context -> {
                    // The SDK's WebMvc SSE transport and its router are wired as before.
                    assertThat(context).hasSingleBean(WebMvcSseServerTransportProvider.class);
                    assertThat(context).hasBean(SSE_ROUTER_BEAN);

                    // The router still serves GET /sse (and only MCP routes).
                    RouterFunction<?> router = context.getBean(SSE_ROUTER_BEAN, RouterFunction.class);
                    assertThat(router.route(mockRequest("GET", SSE_ENDPOINT))).isPresent();
                    assertThat(router.route(mockRequest("POST", SSE_MESSAGE_ENDPOINT))).isPresent();
                    assertThat(router.route(mockRequest("GET", "/not-an-mcp-route"))).isEmpty();
                });
    }

    @Test
    void defaultSseEndpointsUnchanged() {
        McpServerSseProperties properties = new McpServerSseProperties();
        assertThat(properties.getSseEndpoint()).isEqualTo(SSE_ENDPOINT);
        assertThat(properties.getSseMessageEndpoint()).isEqualTo(SSE_MESSAGE_ENDPOINT);
    }

    private static ServerRequest mockRequest(String method, String path) {
        return ServerRequest.create(new MockHttpServletRequest(method, path), List.of());
    }

    /** Every auto-configuration registered on the test classpath, across all jars. */
    private static List<String> autoConfigurationImports() {
        List<String> imports = new ArrayList<>();
        try {
            Enumeration<URL> resources = SseUnchangedTest.class.getClassLoader()
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
    static class EchoToolConfiguration {

        @Bean
        EchoToolService echoToolService() {
            return new EchoToolService();
        }
    }

    @Service
    static class EchoToolService {

        @Tool(name = TOOL_NAME, description = "Echoes the given message back to the caller.")
        public String echo(String message) {
            return message;
        }
    }

    @Configuration
    static class PlainServiceConfiguration {

        @Bean
        PlainService plainService() {
            return new PlainService();
        }
    }

    @Service
    static class PlainService {

        public String noTools() {
            return "not a tool";
        }
    }
}
