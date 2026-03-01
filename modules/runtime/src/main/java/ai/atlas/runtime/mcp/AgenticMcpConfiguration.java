/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.runtime.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovers Spring beans with {@code @Tool}-annotated methods
 * and registers them as MCP tools via {@link ToolCallbackProvider}.
 *
 * <p>Uses lazy resolution to avoid circular dependencies with
 * the MCP server auto-configuration. Tool beans are scanned
 * on first call to {@code getToolCallbacks()}, not at bean creation time.
 */
@Configuration
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnProperty(prefix = "ai.atlas.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgenticMcpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenticMcpConfiguration.class);

    @Bean
    public ToolCallbackProvider agenticToolCallbackProvider(ApplicationContext context) {
        // Return a lazy provider — no bean scanning at creation time,
        // which breaks the circular dep with McpServerAutoConfiguration
        return new LazyToolCallbackProvider(context);
    }

    /**
     * Lazily discovers @Tool-annotated beans on first access.
     * This avoids triggering bean creation during the factory method,
     * which would cause a circular dependency with mcpSyncServer.
     */
    static class LazyToolCallbackProvider implements ToolCallbackProvider {

        private final ApplicationContext context;
        private volatile ToolCallback[] callbacks;

        LazyToolCallbackProvider(ApplicationContext context) {
            this.context = context;
        }

        @Override
        public ToolCallback[] getToolCallbacks() {
            if (callbacks == null) {
                synchronized (this) {
                    if (callbacks == null) {
                        callbacks = resolveCallbacks();
                    }
                }
            }
            return callbacks;
        }

        private ToolCallback[] resolveCallbacks() {
            List<Object> toolBeans = new ArrayList<>();

            // Only scan @Service beans — avoids triggering infrastructure beans
            // like mcpSyncServer which would cause circular dependency
            Map<String, Object> serviceBeans = context.getBeansWithAnnotation(Service.class);
            for (Map.Entry<String, Object> entry : serviceBeans.entrySet()) {
                if (hasToolMethods(entry.getValue())) {
                    toolBeans.add(entry.getValue());
                    log.info("AI-ATLAS: Registered MCP tool bean: {}", entry.getKey());
                }
            }

            if (toolBeans.isEmpty()) {
                log.info("AI-ATLAS: No @Tool-annotated beans found for MCP registration");
                return new ToolCallback[0];
            }

            log.info("AI-ATLAS: Registered {} MCP tool bean(s)", toolBeans.size());
            return MethodToolCallbackProvider.builder()
                    .toolObjects(toolBeans.toArray())
                    .build()
                    .getToolCallbacks();
        }

        private static boolean hasToolMethods(Object bean) {
            for (Method method : bean.getClass().getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    return true;
                }
            }
            return false;
        }
    }
}
