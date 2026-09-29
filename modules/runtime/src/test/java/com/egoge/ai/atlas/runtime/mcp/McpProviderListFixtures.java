/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.mcp.McpEntityFixtures.PersonMcpTool;
import com.egoge.ai.atlas.runtime.mcp.McpOwnProviderFixtures.AppTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * The applications of {@code McpProviderListTest} (issue #50): tools served through a
 * {@code List<ToolCallbackProvider>} bean, which Spring AI's MCP server reads before every other
 * provider.
 */
final class McpProviderListFixtures {

    private McpProviderListFixtures() {
    }

    /** The generated {@code get_person} tool and the application-only tools, through one provider list. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PersonMcpTool.class)
    static class ProviderListApplication {

        @Bean
        List<ToolCallbackProvider> providerList(PersonMcpTool tools) {
            return List.of(MethodToolCallbackProvider.builder().toolObjects(tools).build(),
                    MethodToolCallbackProvider.builder().toolObjects(new AppTools()).build());
        }
    }

    /** Application-only tools through a provider list, with no generated tool bean at all. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AppOnlyProviderListApplication {

        @Bean
        List<ToolCallbackProvider> providerList() {
            return List.of(MethodToolCallbackProvider.builder().toolObjects(new AppTools()).build());
        }
    }
}
