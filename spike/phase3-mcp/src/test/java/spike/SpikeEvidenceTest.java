package spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Starts the real app on a random port per transport and captures raw tools/list + tools/call JSON. */
class SpikeEvidenceTest {

    private static final Path EVIDENCE = Path.of(System.getProperty("spike.evidence", "evidence"));

    static ConfigurableApplicationContext start(String... props) {
        return new SpringApplicationBuilder(SpikeApp.class)
                .properties("server.port=0", "spring.main.banner-mode=off")
                .properties(props)
                .run();
    }

    static String base(ConfigurableApplicationContext ctx) {
        return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSE", "STREAMABLE"})
    void captureToolsListAndCalls(String transport) throws Exception {
        boolean streamable = transport.equals("STREAMABLE");
        try (ConfigurableApplicationContext ctx = streamable
                ? start("spring.ai.mcp.server.protocol=STREAMABLE") : start();
             RawMcpClient client = streamable
                     ? new RawMcpClient.Streamable(base(ctx)) : new RawMcpClient.Sse(base(ctx))) {

            client.initialize(streamable ? "2025-03-26" : "2024-11-05");
            JsonNode list = client.request("tools/list", null);
            write(transport.toLowerCase() + "-tools-list.json", list);

            Map<String, JsonNode> tools = new LinkedHashMap<>();
            list.path("result").path("tools").forEach(t -> tools.put(t.path("name").asText(), t));
            assertThat(tools).containsKeys("derived_bean_validation", "derived_tool_param",
                    "derived_record_arg", "explicit_schema", "hinted_explicit");

            // Q1: derived schema has no constraint keywords
            assertThat(tools.get("derived_bean_validation").path("inputSchema").toString())
                    .doesNotContain("minimum", "maximum", "minLength", "maxLength", "pattern", "minItems");
            // Q2: explicit schema is served verbatim
            JsonNode count = tools.get("explicit_schema").at("/inputSchema/properties/count");
            assertThat(count.path("maximum").asInt()).isEqualTo(100);
            assertThat(tools.get("explicit_schema").at("/inputSchema/properties/tags/maxItems").asInt())
                    .isEqualTo(3);
            // Q3: hints on the SyncToolSpecification-registered tool
            assertThat(tools.get("hinted_explicit").path("annotations").path("readOnlyHint").asBoolean())
                    .isTrue();
            // Q5: the lazy spec list resolved inside mcpSyncServer creation, like the Lazy provider
            assertThat(SpikeToolConfig.resolvedDuringMcpSyncServerCreation).isTrue();

            // Q4: does anything enforce the schema at call time?
            ObjectNode calls = RawMcpClient.JSON.createObjectNode();
            calls.set("derived_bean_validation count=500",
                    client.call("derived_bean_validation", "{\"count\":500,\"code\":\"ABC!\",\"tags\":[]}"));
            calls.set("explicit_schema count=500",
                    client.call("explicit_schema", "{\"count\":500,\"code\":\"ABC!\",\"tags\":[\"a\",\"b\",\"c\",\"d\"]}"));
            calls.set("hinted_explicit count=500 + undeclared arg",
                    client.call("hinted_explicit", "{\"count\":500,\"code\":\"x\",\"extra\":1}"));
            calls.set("validated_max count=500", client.call("validated_max", "{\"count\":500}"));
            write(transport.toLowerCase() + "-tools-call.json", calls);

            // Q4/Q5: the unchanged Atlas LazyToolCallbackProvider silently drops the @Validated bean
            var atlasNames = java.util.Arrays.stream(ctx.getBean("agenticToolCallbackProvider",
                    org.springframework.ai.tool.ToolCallbackProvider.class).getToolCallbacks())
                    .map(cb -> cb.getToolDefinition().name()).sorted().toList();
            write("atlas-lazy-provider-tool-names.txt", atlasNames);
            assertThat(atlasNames).doesNotContain("validated_max");

            assertThat(calls.toString()).contains("invoked count=500 code=ABC! tags=[a, b, c, d]");
        }
    }

    /** Q5: a name registered by both the Atlas @Tool provider and a SyncToolSpecification. */
    @Test
    void duplicateToolNameAcrossPathsFailsStartup() {
        assertThatThrownBy(() -> start("spike.duplicate=true").close())
                .rootCause()
                .satisfies(e -> write("duplicate-name-startup-error.txt", e.toString()));
    }

    static void write(String name, Object content) {
        try {
            Files.createDirectories(EVIDENCE);
            String text = content instanceof JsonNode n
                    ? RawMcpClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(n)
                    : String.valueOf(content);
            Files.writeString(EVIDENCE.resolve(name), text + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
