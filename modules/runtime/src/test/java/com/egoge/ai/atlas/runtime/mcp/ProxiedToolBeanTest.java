/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.framework.autoproxy.BeanNameAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.context.WebApplicationContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Tool beans wrapped in a class-based AOP proxy are served over MCP like any other tool bean.
 *
 * <p>{@code getBeansWithAnnotation(Service.class)} finds a proxied bean, but the proxy's own
 * class does not carry the {@code @Tool} annotations, so tool discovery must look at the target
 * class. A CGLIB subclass (a {@code @Validated} service, proxied by Boot's method validation) is
 * served, and its calls still go through the proxy: the {@code @Validated} constraint is asserted
 * to reject an out-of-range argument. A JDK interface proxy cannot be invoked by Spring AI's tool
 * callbacks, so it is skipped loudly rather than silently.
 *
 * <p>Started through the registered Atlas + MCP server auto-configurations and driven over MCP
 * Streamable HTTP with MockMvc, the same way {@code StreamableHttpTransportTest} does.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProxiedToolBeanTest {

    private static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    private static final String STREAMABLE = "STREAMABLE";
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
    private static final String VALIDATED_TOOL = "validated_max";
    private static final String INTERFACE_TOOL = "interface_greet";
    private static final String INTERFACE_TOOL_BEAN = "greetingToolService";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(registeredMcpBootPath())
            .withPropertyValues(PROTOCOL_PROPERTY + "=" + STREAMABLE);

    @Test
    void validatedServiceToolIsListedAndCalledThroughTheProxy() {
        runner.withUserConfiguration(ValidatedToolConfiguration.class).run(context -> {
            assertThat(AopUtils.isCglibProxy(context.getBean(ValidatedToolService.class)))
                    .as("@Validated service is a CGLIB proxy").isTrue();

            McpSession session = McpSession.open(context);
            assertThat(session.listToolNames()).contains(VALIDATED_TOOL);

            JsonNode accepted = session.callTool(VALIDATED_TOOL, "{\"value\":100}");
            assertThat(accepted.path("isError").asBoolean()).as(accepted.toString()).isFalse();
            assertThat(accepted.path("content").get(0).path("text").asText()).isEqualTo("100");

            // @Max(100) is enforced by the method-validation proxy only: a tool error here proves
            // the MCP call went through the proxy rather than the raw target.
            JsonNode rejected = session.callTool(VALIDATED_TOOL, "{\"value\":101}");
            assertThat(rejected.path("isError").asBoolean()).isTrue();
        });
    }

    /**
     * Spring AI's {@code MethodToolCallback} invokes the target class's method on the registered
     * bean, which a JDK interface proxy is not an instance of, so every call would fail with
     * "object is not an instance of declaring class". Such a bean is skipped with a warning
     * instead of being served as a tool that can never succeed.
     */
    @Test
    void interfaceProxiedServiceIsSkippedWithWarning(CapturedOutput output) {
        runner.withUserConfiguration(ValidatedToolConfiguration.class,
                        InterfaceProxyToolConfiguration.class)
                // Registered by class, as component scanning registers a @Service.
                .withBean(INTERFACE_TOOL_BEAN, GreetingToolService.class)
                .run(context -> {
                    assertThat(AopUtils.isJdkDynamicProxy(context.getBean(INTERFACE_TOOL_BEAN)))
                            .as("interface-proxied service is a JDK proxy").isTrue();

                    McpSession session = McpSession.open(context);
                    assertThat(session.listToolNames())
                            .contains(VALIDATED_TOOL)
                            .doesNotContain(INTERFACE_TOOL);
                    assertThat(output).contains("Skipped MCP tool bean '" + INTERFACE_TOOL_BEAN
                            + "': it is a JDK interface proxy");
                });
    }

    /** An initialized MCP Streamable HTTP session against {@code POST /mcp}. */
    private static final class McpSession {

        private final MockMvc mvc;
        private final String sessionId;
        private int nextId = 2;

        private McpSession(MockMvc mvc, String sessionId) {
            this.mvc = mvc;
            this.sessionId = sessionId;
        }

        static McpSession open(WebApplicationContext context) throws Exception {
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
            return new McpSession(mvc, sessionId);
        }

        List<String> listToolNames() throws Exception {
            JsonNode result = request("tools/list", "{}");
            List<String> names = new ArrayList<>();
            result.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
            return names;
        }

        JsonNode callTool(String name, String arguments) throws Exception {
            return request("tools/call",
                    "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}");
        }

        private JsonNode request(String method, String params) throws Exception {
            int id = nextId++;
            MockHttpServletResponse response = mvc.perform(jsonRpc(sessionId,
                            "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                                    + "\",\"params\":" + params + "}"))
                    .andReturn().getResponse();
            return awaitJsonRpcResult(response, method);
        }
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
    private static JsonNode awaitJsonRpcResult(MockHttpServletResponse response, String method)
            throws Exception {
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
                    if (message.has("error")) {
                        throw new AssertionError(method + " failed: " + message.get("error"));
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No " + method + " response over Streamable HTTP within "
                + RESPONSE_TIMEOUT + "; got: "
                + response.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * The registered Atlas + MCP server auto-configurations, plus the Boot web MVC infrastructure
     * MockMvc dispatches through and Boot's method validation (which proxies
     * {@code @Validated} beans).
     */
    private static AutoConfigurations registeredMcpBootPath() {
        List<String> imports = autoConfigurationImports();
        assertThat(imports).contains(ATLAS_AUTO_CONFIGURATION);
        List<Class<?>> path = new ArrayList<>(imports.stream()
                .filter(name -> name.equals(ATLAS_AUTO_CONFIGURATION)
                        || name.startsWith(MCP_SERVER_AUTO_CONFIGURATION_PACKAGE))
                .map(ProxiedToolBeanTest::loadAutoConfiguration)
                .toList());
        path.addAll(List.of(JacksonAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class,
                DispatcherServletAutoConfiguration.class,
                WebMvcAutoConfiguration.class,
                ValidationAutoConfiguration.class));
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

    /** Every auto-configuration registered on the test classpath, across all jars. */
    private static List<String> autoConfigurationImports() {
        List<String> imports = new ArrayList<>();
        try {
            Enumeration<URL> resources = ProxiedToolBeanTest.class.getClassLoader()
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
    static class ValidatedToolConfiguration {

        @Bean
        ValidatedToolService validatedToolService() {
            return new ValidatedToolService();
        }
    }

    /** A tool bean Boot's method validation wraps in a CGLIB subclass proxy. */
    @Service
    @Validated
    public static class ValidatedToolService {

        @Tool(name = VALIDATED_TOOL, description = "Returns the given value; at most 100.")
        public int validatedMax(@Max(100) int value) {
            return value;
        }
    }

    @Configuration
    static class InterfaceProxyToolConfiguration {

        @Bean
        MethodInterceptor passThroughInterceptor() {
            return MethodInvocation::proceed;
        }

        /** Proxies the tool bean through its interface only — a JDK dynamic proxy. */
        @Bean
        static BeanNameAutoProxyCreator interfaceProxyCreator() {
            BeanNameAutoProxyCreator creator = new BeanNameAutoProxyCreator();
            creator.setBeanNames(INTERFACE_TOOL_BEAN);
            creator.setInterceptorNames("passThroughInterceptor");
            creator.setProxyTargetClass(false);
            return creator;
        }
    }

    /** The tool contract, declared on the interface the JDK proxy implements. */
    public interface GreetingTools {

        @Tool(name = INTERFACE_TOOL, description = "Greets the given name.")
        String greet(String name);
    }

    @Service
    public static class GreetingToolService implements GreetingTools {

        @Override
        public String greet(String name) {
            return "Hello, " + name;
        }
    }
}
