/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static com.egoge.ai.atlas.runtime.mcp.McpOwnProviderResultTest.client;
import static com.egoge.ai.atlas.runtime.mcp.McpOwnProviderResultTest.start;
import static com.egoge.ai.atlas.runtime.mcp.McpOwnProviderResultTest.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #50, for the tools AI-ATLAS cannot protect by rebuilding their callback: the application's
 * beans keep their own types, a callback it cannot read fails startup, a decorated callback is
 * protected through its delegate where it can be reached and reported otherwise, and an
 * {@code @McpTool} method returning an entity is reported. Over real transports.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpUnprotectedToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FAIL = AgentSafeToolCallbacks.FAIL_ON_UNPROTECTED + "=true";

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_STREAMABLE", "STATELESS", "ASYNC_STREAMABLE"})
    void providerInjectedByItsConcreteTypeStaysThatBeanAndIsProtected(String server) throws Exception {
        try (ConfigurableApplicationContext context = start(
                McpUnprotectedToolFixtures.ConcreteProviderApplication.class, server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            MethodToolCallbackProvider provider = context.getBean(MethodToolCallbackProvider.class);
            assertThat(provider.getClass()).isEqualTo(MethodToolCallbackProvider.class);
            assertThat(context.getBean(McpUnprotectedToolFixtures.ProviderConsumer.class).provider).isSameAs(provider);
            SoftAssertions softly = new SoftAssertions();
            McpEntityResultTest.assertWhitelisted(softly, "app_person", JSON.readTree(text(client, "app_person", "{}")));
            McpEntityResultTest.assertWhitelisted(softly, "get_person",
                    JSON.readTree(text(client, "get_person", "{\"id\":1}")));
            softly.assertAll();
        }
    }

    @Test
    void unreadableMethodToolCallbackFailsWhateverItsName() {
        AgentSafeToolCallbacks protection = new AgentSafeToolCallbacks((target, field) -> {
            throw new NoSuchFieldException(field);
        });
        ToolCallback callback = ToolCallbacks.from(new McpOwnProviderFixtures.AppTools())[0];

        assertThatThrownBy(() -> protection.protect(callback, "ToolCallbackProvider bean 'appProvider'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read the field 'toolCallResultConverter'")
                .hasMessageContaining("MethodToolCallback of MCP tool '" + callback.getToolDefinition().name() + "'")
                .hasMessageContaining("ToolCallbackProvider bean 'appProvider'")
                .hasMessageContaining("@AgenticField whitelist");
    }

    @Test
    void methodToolCallbackMissingAFieldValueFails() {
        AgentSafeToolCallbacks protection = new AgentSafeToolCallbacks(
                (target, field) -> field.equals("toolMethod") ? null : readField(target, field));
        ToolCallback callback = ToolCallbacks.from(new McpOwnProviderFixtures.AppTools())[0];

        assertThatThrownBy(() -> protection.protect(callback, "a ToolCallback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read the field 'toolMethod'");
    }

    @Test
    void unreadableFunctionToolCallbackFails() {
        AgentSafeToolCallbacks protection = new AgentSafeToolCallbacks((target, field) -> {
            throw new IllegalAccessException(field);
        });
        ToolCallback callback = FunctionToolCallback.builder("any_name", (Map<String, Object> input) -> "x")
                .description("any").inputType(Map.class).build();

        assertThatThrownBy(() -> protection.protect(callback, "ToolCallback bean 'fn'"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FunctionToolCallback of MCP tool 'any_name'")
                .hasMessageContaining("ToolCallback bean 'fn'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_STREAMABLE", "STATELESS", "ASYNC_STREAMABLE"})
    void decoratedCallbacksAreProtectedThroughTheirDelegateOrReported(String server, CapturedOutput output)
            throws Exception {
        try (ConfigurableApplicationContext context = start(McpUnprotectedToolFixtures.DecoratedApplication.class,
                server);
             RawMcpClient client = client(server, context)) {
            client.initialize();

            SoftAssertions softly = new SoftAssertions();
            McpEntityResultTest.assertWhitelisted(softly, McpUnprotectedToolFixtures.RECORD_DECORATED,
                    JSON.readTree(text(client, McpUnprotectedToolFixtures.RECORD_DECORATED, "{}")));
            McpEntityResultTest.assertWhitelisted(softly, McpUnprotectedToolFixtures.AUGMENTED,
                    JSON.readTree(text(client, McpUnprotectedToolFixtures.AUGMENTED, "{\"reason\":\"audit\"}")));
            softly.assertAll();
            // The decorator class hides how it treats its delegate: served as it is, reported once
            assertThat(text(client, McpUnprotectedToolFixtures.DECORATED, "{}")).contains(McpEntityFixtures.SSN);
            assertThat(output).containsOnlyOnce("MCP tool '" + McpUnprotectedToolFixtures.DECORATED
                    + "' is served by ToolCallback bean 'decoratedPerson' through a "
                    + McpUnprotectedToolFixtures.LoggingCallback.class.getName())
                    .contains("its results are not whitelisted")
                    .doesNotContain("MCP tool '" + McpUnprotectedToolFixtures.RECORD_DECORATED + "' is served")
                    .doesNotContain("MCP tool '" + McpUnprotectedToolFixtures.AUGMENTED + "' is served");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYNC_STREAMABLE", "STATELESS", "ASYNC_STREAMABLE"})
    void decoratedCallbackFailsStartupWhenAsked(String server) {
        assertThatThrownBy(() -> start(McpUnprotectedToolFixtures.DecoratedApplication.class, server, FAIL).close())
                .satisfies(e -> assertThat(rootCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("MCP tool '" + McpUnprotectedToolFixtures.DECORATED + "'")
                        .hasMessageContaining("ToolCallback bean 'decoratedPerson'")
                        .hasMessageContaining(AgentSafeToolCallbacks.FAIL_ON_UNPROTECTED + " is true"));
    }

    @Test
    void mcpToolReturningAnEntityIsReported(CapturedOutput output) throws Exception {
        try (ConfigurableApplicationContext context = start(McpUnprotectedToolFixtures.McpToolApplication.class,
                "SYNC_STREAMABLE")) {
            assertThat(context.isActive()).isTrue();
            assertThat(output)
                    .containsOnlyOnce("MCP tool 'mcp_person' (@McpTool method "
                            + McpUnprotectedToolFixtures.AnnotatedTools.class.getName() + ".mcpPerson")
                    .contains("returns the @AgenticEntity " + McpEntityFixtures.Person.class.getName())
                    .containsOnlyOnce("MCP tool 'mcpPeople' (@McpTool method")
                    .contains("Return a generated DTO, or serialize the result with AgentSafeModule")
                    .doesNotContain("MCP tool 'mcp_text'");
        }
    }

    @Test
    void mcpToolReturningAnEntityFailsStartupWhenAsked() {
        assertThatThrownBy(() -> start(McpUnprotectedToolFixtures.McpToolApplication.class, "SYNC_STREAMABLE",
                FAIL).close())
                .satisfies(e -> assertThat(rootCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("MCP tool 'mcp_person'")
                        .hasMessageContaining("MCP tool 'mcpPeople'")
                        .hasMessageNotContaining("mcp_text"));
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = org.springframework.util.ReflectionUtils.findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
