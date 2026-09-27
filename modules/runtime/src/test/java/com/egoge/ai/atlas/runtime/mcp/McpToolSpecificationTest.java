/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-018 and FR-019: on a SYNC MCP server, a tool listed in {@code META-INF/ai-atlas/mcp-tools.json}
 * is served with the listed constraint keywords and requiredness merged into Spring AI's derived
 * input schema, and with the listed hints as its annotations; a call that violates a constraint of
 * a {@code @Validated} tool is a tool error that never reaches the service.
 *
 * <p>The tool beans are hand-written in the shape the processor generates, and the tool
 * specifications are test fixtures under {@code mcp-tools/<name>/}, put on the class path through
 * a dedicated class loader, so no other test sees them. The transport tests start the real
 * application on a random port and speak raw JSON-RPC over MCP SSE and Streamable HTTP.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpToolSpecificationTest {

    private static final String ORDERS_FIXTURE = "mcp-tools/orders/";
    private static final String DUPLICATE_FIXTURE = "mcp-tools/duplicate/";
    private static final String ORPHAN_FIXTURE = "mcp-tools/orphan/";
    private static final String FIND_ORDERS = McpToolFixtures.FIND_ORDERS;
    private static final String PLACE_ORDER = McpToolFixtures.PLACE_ORDER;
    private static final String PING = McpToolFixtures.PING;
    private static final String SPECIFICATIONS_BEAN = "agenticToolSpecifications";
    private static final String PROVIDER_BEAN = "agenticToolCallbackProvider";
    private static final String PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    private static final String TYPE_PROPERTY = "spring.ai.mcp.server.type";
    private static final String SSE = "SSE";
    private static final String STREAMABLE = "STREAMABLE";
    private static final String ADVISORY_WARNING = "the MCP tool constraints are advisory";
    private static final String ATLAS_AUTO_CONFIGURATION =
            "com.egoge.ai.atlas.runtime.autoconfigure.AgenticAutoConfiguration";
    private static final String MCP_SERVER_AUTO_CONFIGURATION_PACKAGE = "org.springframework.ai.mcp.server.";
    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String VALID_PLACE_ORDER = "{\"items\":[{\"sku\":\"a\",\"quantity\":1}],"
            + "\"attributes\":{},\"deliverOn\":\"2026-01-01\",\"tags\":[\"x\"]}";

    private static final ObjectMapper JSON = new ObjectMapper();
    static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenticMcpConfiguration.class))
            .withUserConfiguration(McpToolFixtures.ToolBeans.class);

    // ---- served over the transports -------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {SSE, STREAMABLE})
    void toolsListServesMergedSchemasAndHintsAndCallsAreValidated(String transport) throws Exception {
        boolean streamable = STREAMABLE.equals(transport);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(McpToolFixtures.ToolServerApplication.class)
                .resourceLoader(new DefaultResourceLoader(fixtures(ORDERS_FIXTURE)))
                .properties("server.port=0", "spring.main.banner-mode=off", PROTOCOL_PROPERTY + "=" + transport)
                .run();
             RawMcpClient client = streamable ? new RawMcpClient.Streamable(baseUrl(context))
                     : new RawMcpClient.Sse(baseUrl(context))) {
            client.initialize();
            Map<String, JsonNode> tools = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            client.request("tools/list", "{}").path("tools").forEach(tool -> {
                names.add(tool.path("name").asText());
                tools.put(tool.path("name").asText(), tool);
            });
            // Every name is registered once, through the one path
            assertThat(names).containsExactlyInAnyOrder(FIND_ORDERS, PLACE_ORDER, PING);

            JsonNode findOrders = tools.get(FIND_ORDERS);
            assertThat(findOrders.at("/inputSchema/$schema").asText())
                    .isEqualTo(AgenticMcpConfiguration.JSON_SCHEMA_2020_12);
            JsonNode limit = findOrders.at("/inputSchema/properties/limit");
            assertThat(limit.path("type").asText()).isEqualTo("integer");
            assertThat(limit.path("maximum").asInt()).isEqualTo(100);
            assertThat(limit.path("description").asText()).isEqualTo("Maximum results");
            assertThat(findOrders.at("/inputSchema/properties/customer/maxLength").asInt()).isEqualTo(5);
            JsonNode hints = findOrders.path("annotations");
            assertThat(hints.path("readOnlyHint").asBoolean()).isTrue();
            assertThat(hints.path("openWorldHint").isBoolean()).isTrue();
            assertThat(hints.path("openWorldHint").asBoolean()).isFalse();
            assertThat(hints.has("destructiveHint")).isFalse();
            assertThat(hints.has("idempotentHint")).isFalse();

            JsonNode placeOrder = tools.get(PLACE_ORDER);
            assertThat(placeOrder.at("/inputSchema/properties/tags/minItems").asInt()).isEqualTo(1);
            assertThat(placeOrder.at("/inputSchema/properties/items/items/type").asText()).isEqualTo("object");
            assertThat(placeOrder.path("annotations").path("destructiveHint").asBoolean(true)).isFalse();

            // A tool with no specification keeps its derived schema, without annotations
            JsonNode ping = tools.get(PING);
            assertThat(ping.has("annotations")).isFalse();
            assertThat(ping.path("inputSchema").has("$schema")).as("only merged schemas are re-declared").isFalse();
            assertThat(ping.at("/inputSchema/properties/message")).isEqualTo(
                    derivedSchema(new McpToolFixtures.PingTools(), PING).at("/properties/message"));

            for (JsonNode tool : tools.values()) {
                assertThat(metaschema().validate(tool.path("inputSchema"))).as(tool.path("name").asText()).isEmpty();
            }

            McpToolFixtures.OrderTools service = context.getBean(McpToolFixtures.OrderTools.class);
            JsonNode rejected = client.call(FIND_ORDERS, "{\"limit\":101,\"customer\":\"acme\"}");
            assertThat(rejected.path("isError").asBoolean()).as(rejected.toString()).isTrue();
            assertThat(service.invocations()).as("the violating call never reached the service").isZero();

            JsonNode accepted = client.call(FIND_ORDERS, "{\"limit\":100,\"customer\":\"acme\"}");
            assertThat(accepted.path("isError").asBoolean()).as(accepted.toString()).isFalse();
            assertThat(accepted.path("content").get(0).path("text").asText()).contains("acme:100");
            assertThat(service.invocations()).isEqualTo(1);
        }
    }

    // ---- the merge --------------------------------------------------------------------------------

    @Test
    void mergeKeepsEveryDerivedKeywordAndAddsConstraintsAndRequiredness() {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE)).run(context -> {
            JsonNode derived = derivedSchema(new McpToolFixtures.OrderTools(), PLACE_ORDER);
            JsonNode merged = servedSchema(context, PLACE_ORDER);

            Iterator<Map.Entry<String, JsonNode>> properties = derived.path("properties").fields();
            while (properties.hasNext()) {
                Map.Entry<String, JsonNode> property = properties.next();
                property.getValue().fields().forEachRemaining(keyword -> assertThat(
                        merged.at("/properties/" + property.getKey() + "/" + keyword.getKey()))
                        .as(property.getKey() + "." + keyword.getKey()).isEqualTo(keyword.getValue()));
            }
            // The DTO list keeps its derived item object schema, down to the nested format
            JsonNode items = merged.at("/properties/items");
            assertThat(items.at("/items/type").asText()).isEqualTo("object");
            assertThat(items.at("/items/properties/quantity/format").asText()).isEqualTo("int32");
            assertThat(items.path("minItems").asInt()).isEqualTo(1);
            // The Map and the LocalDate keep their derived shape and gain requiredness
            assertThat(merged.at("/properties/attributes")).isEqualTo(derived.at("/properties/attributes"));
            assertThat(merged.at("/properties/deliverOn")).isEqualTo(derived.at("/properties/deliverOn"));
            assertThat(texts(derived.path("required"))).doesNotContain("attributes", "deliverOn");
            assertThat(texts(merged.path("required")))
                    .containsExactlyInAnyOrder("items", "attributes", "deliverOn", "tags");
        });
    }

    @Test
    void mergeKeepsFormatAdditionalPropertiesAndEnumOfTheDerivedProperty() throws IOException {
        String derived = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{
                   "since":{"type":"string","format":"date","description":"From"},
                   "attributes":{"type":"object","additionalProperties":{"type":"string"}},
                   "status":{"type":"string","enum":["OPEN","CLOSED"]}},
                 "required":["since"],"additionalProperties":false}""";
        JsonNode generated = JSON.readTree("""
                {"type":"object","properties":{
                   "since":{"type":"string","description":"since","minLength":10},
                   "attributes":{"type":"object","description":"attributes"},
                   "status":{"type":"string","enum":["OPEN"],"pattern":"^(?:[A-Z]+)$"}},
                 "required":["since","attributes","status"]}""");

        ObjectNode merged = InputSchemaMerge.mergeInputSchema("t", derived, generated);

        assertThat(merged.path("$schema").asText()).isEqualTo(AgenticMcpConfiguration.JSON_SCHEMA_2020_12);
        assertThat(merged.path("properties")).isEqualTo(JSON.readTree("""
                {"since":{"type":"string","format":"date","description":"From","minLength":10},
                 "attributes":{"type":"object","additionalProperties":{"type":"string"}},
                 "status":{"type":"string","enum":["OPEN","CLOSED"],"pattern":"^(?:[A-Z]+)$"}}"""));
        assertThat(texts(merged.path("required"))).containsExactly("since", "attributes", "status");
        assertThat(merged.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(metaschema().validate(merged)).isEmpty();
    }

    @Test
    void vectorParameterKeepsItsDerivedArrayTypeAndGainsMinItems() {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE)).run(context -> {
            JsonNode tags = servedSchema(context, PLACE_ORDER).at("/properties/tags");
            assertThat(tags.path("type").asText()).isEqualTo("array");
            assertThat(tags.at("/items/type").asText()).isEqualTo("string");
            assertThat(tags.path("minItems").asInt()).isEqualTo(1);

            // The semantics, not only the shape: a 2020-12 validator accepts an array and rejects an
            // empty one or a non-array
            Schema schema = SCHEMAS.getSchema(servedSchema(context, PLACE_ORDER));
            assertThat(schema.validate(JSON.readTree(VALID_PLACE_ORDER))).isEmpty();
            ObjectNode empty = (ObjectNode) JSON.readTree(VALID_PLACE_ORDER);
            empty.putArray("tags");
            assertThat(schema.validate(empty)).isNotEmpty();
            ObjectNode scalar = (ObjectNode) JSON.readTree(VALID_PLACE_ORDER);
            scalar.put("tags", "x");
            assertThat(schema.validate(scalar)).isNotEmpty();
        });
    }

    @Test
    void keywordNotFittingTheDerivedTypeIsLeftOutWithWarning(CapturedOutput output) {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE)).run(context -> {
            // The fixture lists customer as an object (a processor fallback) with a length and a
            // bound; the derived type is string
            JsonNode customer = servedSchema(context, FIND_ORDERS).at("/properties/customer");
            assertThat(customer.path("type").asText()).isEqualTo("string");
            assertThat(customer.path("maxLength").asInt()).isEqualTo(5);
            assertThat(customer.has("minimum")).isFalse();
            assertThat(output).containsOnlyOnce("MCP tool 'find_orders': constraint keyword 'minimum' on "
                    + "property 'customer' does not apply to its derived type 'string'");
        });
    }

    @Test
    void propertyAbsentFromTheDerivedSchemaIsNotAddedWithWarning(CapturedOutput output) {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE)).run(context -> {
            JsonNode schema = servedSchema(context, FIND_ORDERS);
            assertThat(schema.path("properties").has("ghost")).isFalse();
            assertThat(texts(schema.path("required"))).containsExactlyInAnyOrder("limit", "customer");
            assertThat(output).containsOnlyOnce("MCP tool 'find_orders': property 'ghost' of "
                    + AgenticMcpConfiguration.TOOL_SPECIFICATIONS + " is not in the derived input schema");
        });
    }

    @Test
    void listedToolWithNoCallbackIsReportedWithWarning(CapturedOutput output) {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE, ORPHAN_FIXTURE)).run(context -> {
            servedSchema(context, FIND_ORDERS);
            assertThat(output).containsOnlyOnce("MCP tool 'orphan_tool' is listed in ")
                    .contains(ORPHAN_FIXTURE + AgenticMcpConfiguration.TOOL_SPECIFICATIONS + "] but no @Tool method "
                            + "registers it")
                    .doesNotContain("MCP tool '" + FIND_ORDERS + "' is listed in");
        });
    }

    @Test
    void everyMergedInputSchemaValidatesAgainstTheDraft202012Metaschema() {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE)).run(context -> {
            Map<String, AgenticMcpConfiguration.ToolSpecificationEntry> listed =
                    AgenticMcpConfiguration.readToolSpecifications(context);
            assertThat(listed).containsOnlyKeys(FIND_ORDERS, PLACE_ORDER);
            for (AgenticMcpConfiguration.ToolSpecificationEntry entry : listed.values()) {
                ObjectNode merged = InputSchemaMerge.mergeInputSchema(entry.name(),
                        derivedSchema(new McpToolFixtures.OrderTools(), entry.name()).toString(), entry.inputSchema());
                assertThat(merged.path("$schema").asText()).isEqualTo(AgenticMcpConfiguration.JSON_SCHEMA_2020_12);
                assertThat(metaschema().validate(merged)).as(entry.name()).isEmpty();
                assertThat(metaschema().validate(servedSchema(context, entry.name()))).as(entry.name()).isEmpty();
            }
            // The metaschema is not vacuous
            assertThat(metaschema().validate(JSON.readTree("{\"minItems\": -1}"))).isNotEmpty();
        });
    }

    // ---- registration -------------------------------------------------------------------------------

    @Test
    void duplicateToolNameAcrossTwoResourcesFailsStartupNamingBoth() {
        new WebApplicationContextRunner()
                .withConfiguration(registeredMcpBootPath())
                .withUserConfiguration(McpToolFixtures.ToolBeans.class)
                .withClassLoader(fixtures(ORDERS_FIXTURE, DUPLICATE_FIXTURE))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable cause = context.getStartupFailure();
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    assertThat(cause).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("MCP tool '" + FIND_ORDERS + "'")
                            .hasMessageContaining(ORDERS_FIXTURE + AgenticMcpConfiguration.TOOL_SPECIFICATIONS)
                            .hasMessageContaining(DUPLICATE_FIXTURE + AgenticMcpConfiguration.TOOL_SPECIFICATIONS);
                });
    }

    @Test
    void syncServerRegistersEveryToolThroughTheSpecificationsOnly() {
        new WebApplicationContextRunner()
                .withConfiguration(registeredMcpBootPath())
                .withUserConfiguration(McpToolFixtures.ToolBeans.class)
                .withClassLoader(fixtures(ORDERS_FIXTURE))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(McpSyncServer.class);
                    assertThat(context).hasBean(SPECIFICATIONS_BEAN);
                    assertThat(context).doesNotHaveBean(ToolCallbackProvider.class);
                });
    }

    @Test
    void asyncServerKeepsTheDerivedRegistration(CapturedOutput output) {
        new WebApplicationContextRunner()
                .withConfiguration(registeredMcpBootPath())
                .withUserConfiguration(McpToolFixtures.ToolBeans.class)
                .withClassLoader(fixtures(ORDERS_FIXTURE))
                .withPropertyValues(TYPE_PROPERTY + "=ASYNC")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(McpAsyncServer.class);
                    assertThat(context).doesNotHaveBean(SPECIFICATIONS_BEAN);
                    ToolCallback[] callbacks = context.getBean(PROVIDER_BEAN, ToolCallbackProvider.class)
                            .getToolCallbacks();
                    assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                            .containsExactlyInAnyOrder(FIND_ORDERS, PLACE_ORDER, PING);
                    // The derived schema, without the listed constraint
                    JsonNode limit = JSON.readTree(callbackNamed(callbacks, FIND_ORDERS).getToolDefinition()
                            .inputSchema()).at("/properties/limit");
                    assertThat(limit.has("maximum")).isFalse();
                    assertThat(output).containsOnlyOnce("The MCP server is ASYNC or STATELESS, so the generated "
                            + "MCP tool input schemas and hints are not applied");
                });
    }

    @Test
    void statelessServerKeepsTheDerivedRegistration(CapturedOutput output) {
        runner.withPropertyValues(PROTOCOL_PROPERTY + "=STATELESS").run(context -> {
            assertThat(context).hasBean(PROVIDER_BEAN);
            assertThat(context).doesNotHaveBean(SPECIFICATIONS_BEAN);
            assertThat(output).containsOnlyOnce("The MCP server is ASYNC or STATELESS, so the generated "
                    + "MCP tool input schemas and hints are not applied");
        });
    }

    // ---- the advisory warning (FR-019) ------------------------------------------------------------

    @Test
    void warnsOnceWhenSpecificationsArePresentWithoutMethodValidation(CapturedOutput output) {
        runner.withClassLoader(fixtures(ORDERS_FIXTURE)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output).containsOnlyOnce(ADVISORY_WARNING).contains("spring-boot-starter-validation");
        });
    }

    @Test
    void noAdvisoryWarningWithMethodValidation(CapturedOutput output) {
        runner.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withClassLoader(fixtures(ORDERS_FIXTURE))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).doesNotContain(ADVISORY_WARNING);
                });
    }

    @Test
    void noAdvisoryWarningWithoutSpecifications(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output).doesNotContain(ADVISORY_WARNING);
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** A class loader that adds the given fixture directories to the test class path. */
    static ClassLoader fixtures(String... directories) {
        ClassLoader parent = McpToolSpecificationTest.class.getClassLoader();
        URL[] urls = new URL[directories.length];
        for (int i = 0; i < directories.length; i++) {
            urls[i] = parent.getResource(directories[i]);
            assertThat(urls[i]).as(directories[i]).isNotNull();
        }
        return new URLClassLoader(urls, parent);
    }

    static Schema metaschema() {
        return SCHEMAS.getSchema(SchemaLocation.of(AgenticMcpConfiguration.JSON_SCHEMA_2020_12));
    }

    /** The input schema Spring AI derives for the named tool of the bean. */
    private static JsonNode derivedSchema(Object toolBean, String name) throws IOException {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(toolBean).build()
                .getToolCallbacks();
        return JSON.readTree(callbackNamed(callbacks, name).getToolDefinition().inputSchema());
    }

    private static ToolCallback callbackNamed(ToolCallback[] callbacks, String name) {
        for (ToolCallback callback : callbacks) {
            if (callback.getToolDefinition().name().equals(name)) {
                return callback;
            }
        }
        throw new AssertionError("No tool " + name);
    }

    /** The input schema the Atlas specification list hands the MCP server for the named tool. */
    private static JsonNode servedSchema(ApplicationContext context, String name) {
        @SuppressWarnings("unchecked")
        List<SyncToolSpecification> specifications =
                (List<SyncToolSpecification>) context.getBean(SPECIFICATIONS_BEAN);
        for (SyncToolSpecification specification : specifications) {
            McpSchema.Tool tool = specification.tool();
            if (tool.name().equals(name)) {
                return JSON.valueToTree(tool.inputSchema());
            }
        }
        throw new AssertionError("No tool specification " + name);
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    static String baseUrl(ApplicationContext context) {
        return "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
    }

    /** The registered Atlas and MCP server auto-configurations, plus Jackson. */
    private static AutoConfigurations registeredMcpBootPath() {
        List<Class<?>> path = new ArrayList<>();
        path.add(JacksonAutoConfiguration.class);
        try {
            Enumeration<URL> resources = McpToolSpecificationTest.class.getClassLoader()
                    .getResources(AUTO_CONFIGURATION_IMPORTS);
            while (resources.hasMoreElements()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resources.nextElement().openStream(), StandardCharsets.UTF_8))) {
                    for (String name : reader.lines().map(String::trim).toList()) {
                        if (name.equals(ATLAS_AUTO_CONFIGURATION) || name.startsWith(MCP_SERVER_AUTO_CONFIGURATION_PACKAGE)) {
                            path.add(Class.forName(name));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return AutoConfigurations.of(path.toArray(Class<?>[]::new));
    }
}
