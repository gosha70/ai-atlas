/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.mcp.McpEntityFixtures.Person;
import com.egoge.ai.atlas.runtime.mcp.McpEntityFixtures.PersonMcpTool;
import com.egoge.ai.atlas.runtime.mcp.McpOwnProviderFixtures.AppTools;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The applications of {@code McpUnprotectedToolTest} (issue #50): an application provider injected by
 * its concrete type, callbacks decorated by the application or by Spring AI, and {@code @McpTool}
 * methods, which Spring AI's MCP annotation support serves outside the tool-callback path.
 */
final class McpUnprotectedToolFixtures {

    static final String DECORATED = "decorated_person";
    static final String RECORD_DECORATED = "record_person";
    static final String AUGMENTED = "augmented_person";

    private McpUnprotectedToolFixtures() {
    }

    /** A {@code MethodToolCallbackProvider} bean, injected by that final class into another bean. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PersonMcpTool.class, ProviderConsumer.class})
    static class ConcreteProviderApplication {

        @Bean
        MethodToolCallbackProvider appProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(new AppTools()).build();
        }
    }

    /** Needs the provider by its concrete type. */
    @Component
    static class ProviderConsumer {

        final MethodToolCallbackProvider provider;

        ProviderConsumer(MethodToolCallbackProvider provider) {
            this.provider = provider;
        }
    }

    /**
     * An application decorator class and record around default callbacks, and Spring AI's
     * {@link AugmentedToolCallbackProvider}.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DecoratedApplication {

        @Bean
        ToolCallback decoratedPerson() {
            return new LoggingCallback(ToolCallbacks.from(new DecoratedTools())[0]);
        }

        @Bean
        List<ToolCallback> recordDecorated() {
            return List.of(new TracingCallback("trace", ToolCallbacks.from(new RecordDecoratedTools())[0]));
        }

        @Bean
        ToolCallbackProvider augmentedProvider() {
            return AugmentedToolCallbackProvider.<Reason>builder()
                    .toolObject(new AugmentedTools())
                    .argumentType(Reason.class)
                    .argumentConsumer(event -> { })
                    .removeExtraArgumentsAfterProcessing(true)
                    .build();
        }
    }

    /** {@code @McpTool} methods, one returning an entity inside a container. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(AnnotatedTools.class)
    static class McpToolApplication {
    }

    @Component
    public static class AnnotatedTools {

        @McpTool(name = "mcp_person", description = "mcp_person")
        public Person mcpPerson() {
            return McpEntityFixtures.person();
        }

        @McpTool(description = "mcp_people")
        public Optional<List<McpEntityFixtures.Vip>> mcpPeople() {
            return Optional.of(List.of(McpEntityFixtures.vip()));
        }

        @McpTool(name = "mcp_text", description = "mcp_text")
        public String mcpText() {
            return "text";
        }
    }

    public static class DecoratedTools {

        @Tool(name = DECORATED, description = DECORATED)
        public Person decoratedPerson() {
            return McpEntityFixtures.person();
        }
    }

    public static class RecordDecoratedTools {

        @Tool(name = RECORD_DECORATED, description = RECORD_DECORATED)
        public Person recordPerson() {
            return McpEntityFixtures.person();
        }
    }

    public static class AugmentedTools {

        @Tool(name = AUGMENTED, description = AUGMENTED)
        public Person augmentedPerson() {
            return McpEntityFixtures.person();
        }
    }

    /** The extra argument of the augmented tool. */
    public record Reason(String reason) {
    }

    /** An application decorator class: AI-ATLAS cannot tell how it treats its delegate. */
    static final class LoggingCallback implements ToolCallback {

        private final ToolCallback delegate;

        LoggingCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }
    }

    /** An application decorator record with one {@code ToolCallback} component. */
    record TracingCallback(String label, ToolCallback delegate) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
