package spike;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.List;
import java.util.function.Supplier;

@Configuration
public class SpikeToolConfig {

    /** What a generated per-tool spec would carry: a hand-written JSON Schema with constraints. */
    static final String SCHEMA = """
            {"type":"object",
             "properties":{
               "count":{"type":"integer","description":"Page size","minimum":1,"maximum":100},
               "code":{"type":"string","minLength":2,"maxLength":10,"pattern":"^[a-z]+$"},
               "tags":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":3}},
             "required":["count","code"],
             "additionalProperties":false}""";

    /** Records whether the spec list was resolved while mcpSyncServer was being created. */
    static volatile Boolean resolvedDuringMcpSyncServerCreation;

    static ToolCallback callback(String name, Object target) {
        Method method = ReflectionUtils.findMethod(ExplicitTools.class, "pageSize", int.class, String.class,
                List.class);
        return MethodToolCallback.builder()
                .toolDefinition(ToolDefinition.builder()
                        .name(name)
                        .description("Explicit ToolDefinition.inputSchema(...)")
                        .inputSchema(SCHEMA)
                        .build())
                .toolMethod(method)
                .toolObject(target)
                .build();
    }

    /** Q2: a plain ToolCallback with an explicit ToolDefinition, via the normal converter path. */
    @Bean
    ToolCallbackProvider explicitSchemaProvider(ObjectProvider<ExplicitTools> tools) {
        // Lazy like LazyToolCallbackProvider: the target bean is fetched on first getToolCallbacks().
        return () -> new ToolCallback[] {callback("explicit_schema", tools.getObject())};
    }

    /**
     * Q4: LazyToolCallbackProvider skips @Validated (CGLIB-proxied) beans because hasToolMethods()
     * reads bean.getClass().getMethods(); MethodToolCallbackProvider itself unwraps proxies
     * (AopUtils.getTargetClass), so register the validated bean directly to observe enforcement.
     */
    @Bean
    ToolCallbackProvider validatedProvider(ObjectProvider<ValidatedTools> tools) {
        return () -> MethodToolCallbackProvider.builder().toolObjects(tools.getObject()).build()
                .getToolCallbacks();
    }

    /**
     * Q3/Q5: a bean of type List&lt;SyncToolSpecification&gt; — McpServerAutoConfiguration.mcpSyncServer
     * merges every such bean (ObjectProvider&lt;List&lt;SyncToolSpecification&gt;&gt;). The list is lazy:
     * the factory method touches no other bean; contents are built on first iteration.
     */
    @Bean
    List<SyncToolSpecification> atlasToolSpecifications(ObjectProvider<ExplicitTools> tools,
                                                        ConfigurableListableBeanFactory beanFactory) {
        return new LazyList<>(() -> {
            resolvedDuringMcpSyncServerCreation = beanFactory.isCurrentlyInCreation("mcpSyncServer");
            // Reuse Spring AI's ToolCallback -> spec conversion for the call handler ...
            SyncToolSpecification base = McpToolUtils.toSyncToolSpecification(
                    callback("hinted_explicit", tools.getObject()));
            // ... and rebuild the McpSchema.Tool with the explicit schema + ToolAnnotations.
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(base.tool().name())
                    .title("Hinted explicit tool")
                    .description(base.tool().description())
                    .inputSchema(McpJsonDefaults.getMapper(), SCHEMA)
                    .annotations(new McpSchema.ToolAnnotations("Hinted explicit tool",
                            true, false, true, false, null))
                    .build();
            return List.of(new SyncToolSpecification(tool, null, base.callHandler()));
        });
    }

    static final class LazyList<T> extends AbstractList<T> {
        private final Supplier<List<T>> supplier;
        private volatile List<T> delegate;

        LazyList(Supplier<List<T>> supplier) {
            this.supplier = supplier;
        }

        private List<T> delegate() {
            if (delegate == null) {
                synchronized (this) {
                    if (delegate == null) {
                        delegate = supplier.get();
                    }
                }
            }
            return delegate;
        }

        @Override
        public T get(int index) {
            return delegate().get(index);
        }

        @Override
        public int size() {
            return delegate().size();
        }
    }
}
