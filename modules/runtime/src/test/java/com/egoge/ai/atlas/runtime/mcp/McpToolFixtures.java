/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The tool beans of {@code McpToolSpecificationTest}, matching the entries of the
 * {@code mcp-tools/orders} fixture.
 */
final class McpToolFixtures {

    static final String FIND_ORDERS = "find_orders";
    static final String PLACE_ORDER = "place_order";
    static final String PING = "ping";

    private McpToolFixtures() {
    }

    /** The real application: every auto-configuration on the test class path, no component scan. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({OrderTools.class, PingTools.class})
    static class ToolServerApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OrderTools.class, PingTools.class})
    static class ToolBeans {
    }

    /**
     * The real application, which also registers {@link PingTools} through its own provider, the usual
     * Spring AI pattern.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({OrderTools.class, PingTools.class})
    static class OwnProviderApplication {

        @Bean
        ToolCallbackProvider pingToolProvider(PingTools tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }
    }

    public record LineItem(String sku, int quantity) {
    }

    /** In the shape of a generated MCP tool class with {@code ai.atlas.constraints} on. */
    @Service
    @Validated
    public static class OrderTools {

        private final AtomicInteger invocations = new AtomicInteger();

        @Tool(name = FIND_ORDERS, description = "Finds a customer's orders.")
        public String findOrders(@ToolParam(description = "Maximum results") @NotNull @DecimalMax("100") Integer limit,
                                 @ToolParam(description = "Customer code") @NotNull @Size(max = 5) String customer) {
            invocations.incrementAndGet();
            return customer + ":" + limit;
        }

        @Tool(name = PLACE_ORDER, description = "Places an order.")
        public String placeOrder(@NotNull @Size(min = 1) List<LineItem> items,
                                 @ToolParam(required = false) Map<String, String> attributes,
                                 @ToolParam(required = false) LocalDate deliverOn,
                                 @NotNull @Size(min = 1) Vector<String> tags) {
            invocations.incrementAndGet();
            return "placed " + items.size();
        }

        public int invocations() {
            return invocations.get();
        }
    }

    /** A tool with no specification. */
    @Service
    public static class PingTools {

        @Tool(name = PING, description = "Echoes the message.")
        public String ping(String message) {
            return message;
        }
    }
}
