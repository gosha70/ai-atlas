/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.annotations.AgenticBound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import javax.annotation.processing.Generated;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An MCP tool result over the bound its generated tool method declares ({@link AgenticBound}) is
 * logged as a WARN and returned unchanged, whether AI-ATLAS registers the tool or the application's
 * own provider serves it.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpResultBoundTest {

    private static final String OVER = "more than the maxResults = 2";

    /** A generated tool class in the shape the processor writes for {@code maxResults = 2}. */
    @Service
    @Generated("com.egoge.ai.atlas.processor")
    public static class BoundedMcpTool {

        @Tool(name = "top", description = "The top items. Returns at most 2 results.")
        @AgenticBound(maxResults = 2)
        public List<String> top() {
            return List.of("a", "b", "c");
        }

        @Tool(name = "few", description = "A few items. Returns at most 2 results.")
        @AgenticBound(maxResults = 2)
        public List<String> few() {
            return List.of("a");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BoundedMcpTool.class)
    static class ToolBeans {
    }

    @Test
    void aRegisteredToolsResultOverItsBoundIsAWarnAndIsReturnedUnchanged(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenticMcpConfiguration.class))
                .withUserConfiguration(ToolBeans.class)
                .withPropertyValues("spring.ai.mcp.server.protocol=STATELESS")
                .run(context -> {
                    ToolCallback[] callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();

                    assertThat(named(callbacks, "top").call("{}")).isEqualTo("[\"a\",\"b\",\"c\"]");
                    assertThat(output.getOut()).contains("[ai-atlas] MCP tool 'top' returned 3 results, " + OVER
                            + " its service method BoundedMcpTool#top declares");
                    assertThat(named(callbacks, "few").call("{}")).isEqualTo("[\"a\"]");
                    assertThat(output.getOut()).doesNotContain("MCP tool 'few'");
                });
    }

    @Test
    void aToolTheApplicationsProviderServesIsCheckedToo(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgenticMcpConfiguration.class))
                .withPropertyValues("spring.ai.mcp.server.protocol=STATELESS")
                .run(context -> {
                    AgentSafeToolCallbacks protection = new AgentSafeToolCallbacks();
                    protection.setApplicationContext(context);
                    ToolCallback raw = Stream.of(MethodToolCallbackProvider.builder()
                                    .toolObjects(new BoundedMcpTool()).build().getToolCallbacks())
                            .filter(c -> c.getToolDefinition().name().equals("top")).findFirst().orElseThrow();

                    ToolCallback served = protection.protect(raw, "test");

                    assertThat(served.call("{}")).isEqualTo("[\"a\",\"b\",\"c\"]");
                    assertThat(output.getOut()).contains("MCP tool 'top' returned 3 results, " + OVER);
                    // Protecting it again keeps the one check, around this context's converter
                    assertThat(protection.protect(served, "test")).isSameAs(served);
                    protection.destroy();
                });
    }

    private static ToolCallback named(ToolCallback[] callbacks, String name) {
        return Stream.of(callbacks).filter(c -> c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
    }
}
