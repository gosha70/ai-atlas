/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.json.AgentSafeModule;
import com.egoge.ai.atlas.runtime.mcp.McpEntityFixtures.Person;
import com.egoge.ai.atlas.runtime.mcp.McpEntityFixtures.PersonMcpTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The applications of {@code McpOwnProviderResultTest} (issue #50): tools served through the
 * application's own {@code ToolCallbackProvider}, {@code ToolCallback} and {@code List<ToolCallback>}
 * beans rather than through AI-ATLAS.
 */
final class McpOwnProviderFixtures {

    static final String OPAQUE_ECHO = "opaque_echo";

    private McpOwnProviderFixtures() {
    }

    /**
     * The generated {@code get_person} tool, also registered through an ordinary
     * {@link MethodToolCallbackProvider}, with application-only tools beside it.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PersonMcpTool.class)
    static class OwnProviderApplication {

        @Bean
        ToolCallbackProvider personToolProvider(PersonMcpTool tools) {
            return MethodToolCallbackProvider.builder().toolObjects(tools).build();
        }

        @Bean
        ToolCallbackProvider appToolProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(new AppTools()).build();
        }

        @Bean
        ToolCallbackProvider opaqueToolProvider() {
            return ToolCallbackProvider.from(new OpaqueCallback(OPAQUE_ECHO));
        }

        @Bean
        ToolCallback functionPerson() {
            return FunctionToolCallback.builder("fn_person", (Map<String, Object> input) -> McpEntityFixtures.person())
                    .description("fn_person")
                    .inputType(Map.class)
                    .build();
        }

        @Bean
        List<ToolCallback> listedTools() {
            return List.of(ToolCallbacks.from(new ListedTools()));
        }
    }

    /** A provider serving the generated {@code get_person} tool through an opaque callback. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PersonMcpTool.class)
    static class OpaqueGeneratedToolApplication {

        @Bean
        ToolCallbackProvider opaquePersonProvider() {
            return ToolCallbackProvider.from(new OpaqueCallback("get_person"));
        }
    }

    /** A tool opted in through {@code @Tool(resultConverter)}, which Spring AI instantiates reflectively. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PersonMcpTool.class)
    static class OptedInApplication {

        @Bean
        ToolCallbackProvider optedInProvider() {
            return MethodToolCallbackProvider.builder().toolObjects(new McpEntityFixtures.OptedInTool()).build();
        }
    }

    /** The application declares its own {@code AgentSafeModule}: enriched, without descriptions. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(PersonMcpTool.class)
    static class OwnModuleApplication {

        @Bean
        AgentSafeModule appModule() {
            return new AgentSafeModule(true, false, false);
        }
    }

    /** A plain value object: its JSON must be Spring AI's. */
    public static class Address {

        private final String street;
        private final LocalDate since;
        private final String note;

        Address(String street, LocalDate since, String note) {
            this.street = street;
            this.since = since;
            this.note = note;
        }

        public String getStreet() { return street; }
        public LocalDate getSince() { return since; }
        public String getNote() { return note; }
    }

    static Address address() {
        return new Address("1 Main St", LocalDate.of(2020, 5, 17), null);
    }

    static Map<String, Object> map() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("when", LocalDate.of(2026, 1, 2));
        map.put("count", 3);
        map.put("nothing", null);
        map.put("tags", List.of("x", "y"));
        return map;
    }

    /** Tools that exist only in the application, never generated. */
    public static class AppTools {

        @Tool(name = "app_person", description = "app_person")
        public Person appPerson() {
            return McpEntityFixtures.person();
        }

        @Tool(name = "app_people", description = "app_people")
        public Map<String, List<Person>> appPeople() {
            return Map.of("all", List.of(McpEntityFixtures.person(), McpEntityFixtures.vip()));
        }

        @Tool(name = "app_address", description = "app_address")
        public Address appAddress() {
            return address();
        }

        @Tool(name = "app_map", description = "app_map")
        public Map<String, Object> appMap() {
            return map();
        }

        @Tool(name = "app_text", description = "app_text")
        public String appText(@ToolParam(description = "name") String name) {
            return "hi " + name;
        }

        @Tool(name = "app_void", description = "app_void")
        public void appVoid() {
        }

        @Tool(name = "app_direct", description = "app_direct", returnDirect = true)
        public Person appDirect() {
            return McpEntityFixtures.person();
        }

        @Tool(name = "app_own_converter", description = "app_own_converter",
                resultConverter = McpEntityFixtures.FixedConverter.class)
        public Person appOwnConverter() {
            return McpEntityFixtures.person();
        }
    }

    /** Tools registered as a {@code List<ToolCallback>} bean. */
    public static class ListedTools {

        @Tool(name = "listed_person", description = "listed_person")
        public Person listedPerson() {
            return McpEntityFixtures.person();
        }
    }

    /** A callback whose result conversion AI-ATLAS cannot see. */
    static final class OpaqueCallback implements ToolCallback {

        static final String RESULT = "opaque";

        private final ToolDefinition definition;

        OpaqueCallback(String name) {
            this.definition = DefaultToolDefinition.builder().name(name).description(name)
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "\"" + RESULT + "\"";
        }
    }
}
