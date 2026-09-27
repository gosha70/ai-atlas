/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeType;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovers Spring beans with {@code @Tool}-annotated methods and registers them as MCP
 * tools.
 *
 * <p>On a SYNC MCP server the tools are registered through one lazy
 * {@code List<SyncToolSpecification>} bean (FR-018). A tool listed in any
 * {@code META-INF/ai-atlas/mcp-tools.json} on the classpath is served with the listed constraint
 * keywords and requiredness merged into Spring AI's derived input schema, and with the listed
 * behavioural hints as its MCP {@code annotations}. Every other tool keeps its derived schema.
 * No {@link ToolCallbackProvider} is registered then, so every tool name comes from one path.
 *
 * <p>On an ASYNC or STATELESS server the tools are registered through a lazy
 * {@link ToolCallbackProvider}, with Spring AI's derived schemas only.
 *
 * <p>Both are lazy to avoid circular dependencies with the MCP server auto-configuration: tool
 * beans are scanned when the MCP server first reads the tools, not at bean creation time.
 */
@Configuration
@ConditionalOnClass(ToolCallbackProvider.class)
@ConditionalOnProperty(prefix = "ai.atlas.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgenticMcpConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenticMcpConfiguration.class);

    /** Class-path location of the tool specifications the processor generates (FR-017). */
    static final String TOOL_SPECIFICATIONS = "META-INF/ai-atlas/mcp-tools.json";
    /** The dialect MCP uses for tool input schemas. */
    static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private static final String METHOD_VALIDATION_POST_PROCESSOR =
            "org.springframework.validation.beanvalidation.MethodValidationPostProcessor";
    private static final String SERVER_TYPE_PROPERTY = "spring.ai.mcp.server.type";
    private static final String SERVER_PROTOCOL_PROPERTY = "spring.ai.mcp.server.protocol";
    private static final String SYNC = "SYNC";
    private static final String STATELESS = "STATELESS";

    private static final String K_TOOLS = "tools";
    private static final String K_NAME = "name";
    private static final String K_INPUT_SCHEMA = "inputSchema";
    private static final String K_ANNOTATIONS = "annotations";
    private static final String K_READ_ONLY_HINT = "readOnlyHint";
    private static final String K_DESTRUCTIVE_HINT = "destructiveHint";
    private static final String K_IDEMPOTENT_HINT = "idempotentHint";
    private static final String K_OPEN_WORLD_HINT = "openWorldHint";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    @Conditional(SyncServerCondition.class)
    public List<SyncToolSpecification> agenticToolSpecifications(
            ApplicationContext context, ObjectProvider<McpServerProperties> serverProperties,
            DeclaredInputSchemas declaredInputSchemas) {
        warnIfConstraintsAdvisory(context);
        // A lazy list — no bean scanning at creation time, which breaks the circular dep with
        // McpServerAutoConfiguration
        return new LazyToolSpecifications(context, serverProperties, declaredInputSchemas);
    }

    /** Keeps each merged input schema's {@code $schema} on the wire (FR-018); static, as a post-processor. */
    @Bean
    @Conditional(SyncServerCondition.class)
    static DeclaredInputSchemas agenticDeclaredInputSchemas() {
        return new DeclaredInputSchemas();
    }

    @Bean
    @Conditional(NonSyncServerCondition.class)
    public ToolCallbackProvider agenticToolCallbackProvider(ApplicationContext context) {
        log.info("AI-ATLAS: The MCP server is ASYNC or STATELESS, so the generated MCP tool input schemas "
                + "and hints are not applied; tools keep Spring AI's derived schemas");
        warnIfConstraintsAdvisory(context);
        return new LazyToolCallbackProvider(context);
    }

    /** SYNC and not STATELESS, as Spring AI's MCP server auto-configuration reads the same properties. */
    static class SyncServerCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String type = context.getEnvironment().getProperty(SERVER_TYPE_PROPERTY, SYNC).trim();
            String protocol = context.getEnvironment().getProperty(SERVER_PROTOCOL_PROPERTY, "").trim();
            return SYNC.equalsIgnoreCase(type) && !STATELESS.equalsIgnoreCase(protocol);
        }
    }

    static class NonSyncServerCondition extends SyncServerCondition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !super.matches(context, metadata);
        }
    }

    /**
     * Warns once when tool specifications are present but nothing enforces their constraints: the
     * generated tool classes are {@code @Validated}, which only a {@code MethodValidationPostProcessor}
     * acts on (FR-019).
     */
    private static void warnIfConstraintsAdvisory(ApplicationContext context) {
        if (toolSpecificationResources(context).length == 0 || hasMethodValidation(context)) {
            return;
        }
        log.warn("AI-ATLAS: {} is present but the application has no MethodValidationPostProcessor bean, so "
                + "the MCP tool constraints are advisory: a call that violates them reaches the service. "
                + "Add spring-boot-starter-validation to enforce them", TOOL_SPECIFICATIONS);
    }

    private static boolean hasMethodValidation(ApplicationContext context) {
        ClassLoader classLoader = context.getClassLoader();
        if (!ClassUtils.isPresent(METHOD_VALIDATION_POST_PROCESSOR, classLoader)) {
            return false;
        }
        Class<?> type = ClassUtils.resolveClassName(METHOD_VALIDATION_POST_PROCESSOR, classLoader);
        return context.getBeanNamesForType(type, true, false).length > 0;
    }

    private static Resource[] toolSpecificationResources(ApplicationContext context) {
        try {
            return context.getResources("classpath*:" + TOOL_SPECIFICATIONS);
        } catch (IOException e) {
            throw new UncheckedIOException("AI-ATLAS: Failed to look up " + TOOL_SPECIFICATIONS, e);
        }
    }

    /** One tool entry of a tool specifications resource. */
    record ToolSpecificationEntry(String name, JsonNode inputSchema, JsonNode annotations, String resource) {
    }

    /**
     * Every tool entry of every tool specifications resource on the class path, by tool name.
     *
     * @throws IllegalStateException when two resources list the same tool name
     */
    static Map<String, ToolSpecificationEntry> readToolSpecifications(ApplicationContext context) {
        Map<String, ToolSpecificationEntry> entries = new LinkedHashMap<>();
        for (Resource resource : toolSpecificationResources(context)) {
            String location = resource.getDescription();
            JsonNode document;
            try (InputStream in = resource.getInputStream()) {
                document = JSON.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException("AI-ATLAS: Failed to read " + location, e);
            }
            for (JsonNode tool : document.path(K_TOOLS)) {
                String name = tool.path(K_NAME).asText();
                ToolSpecificationEntry entry = new ToolSpecificationEntry(name, tool.path(K_INPUT_SCHEMA),
                        tool.path(K_ANNOTATIONS), location);
                ToolSpecificationEntry previous = entries.putIfAbsent(name, entry);
                if (previous != null) {
                    throw new IllegalStateException("AI-ATLAS: MCP tool '" + name + "' is listed by two tool "
                            + "specification resources: " + previous.resource() + " and " + location);
                }
            }
        }
        return entries;
    }

    /** The declared hints as MCP tool annotations, or {@code null} when none is declared. */
    static McpSchema.ToolAnnotations toolAnnotations(JsonNode annotations) {
        Boolean readOnly = hint(annotations, K_READ_ONLY_HINT);
        Boolean destructive = hint(annotations, K_DESTRUCTIVE_HINT);
        Boolean idempotent = hint(annotations, K_IDEMPOTENT_HINT);
        Boolean openWorld = hint(annotations, K_OPEN_WORLD_HINT);
        if (readOnly == null && destructive == null && idempotent == null && openWorld == null) {
            return null;
        }
        return new McpSchema.ToolAnnotations(null, readOnly, destructive, idempotent, openWorld, null);
    }

    private static Boolean hint(JsonNode annotations, String key) {
        JsonNode value = annotations.get(key);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    /**
     * Lazily builds the tool specifications on first access. This avoids triggering bean creation
     * during the factory method, which would cause a circular dependency with mcpSyncServer.
     */
    static class LazyToolSpecifications extends AbstractList<SyncToolSpecification> {

        private final ApplicationContext context;
        private final ObjectProvider<McpServerProperties> serverProperties;
        private final DeclaredInputSchemas declaredInputSchemas;
        private volatile List<SyncToolSpecification> specifications;

        LazyToolSpecifications(ApplicationContext context, ObjectProvider<McpServerProperties> serverProperties,
                               DeclaredInputSchemas declaredInputSchemas) {
            this.context = context;
            this.serverProperties = serverProperties;
            this.declaredInputSchemas = declaredInputSchemas;
        }

        @Override
        public SyncToolSpecification get(int index) {
            return specifications().get(index);
        }

        @Override
        public int size() {
            return specifications().size();
        }

        private List<SyncToolSpecification> specifications() {
            if (specifications == null) {
                synchronized (this) {
                    if (specifications == null) {
                        specifications = resolveSpecifications();
                    }
                }
            }
            return specifications;
        }

        private List<SyncToolSpecification> resolveSpecifications() {
            Map<String, ToolSpecificationEntry> listed = readToolSpecifications(context);
            McpServerProperties properties = serverProperties.getIfAvailable();
            List<SyncToolSpecification> result = new ArrayList<>();
            int applied = 0;
            for (ToolCallback callback : resolveCallbacks(context)) {
                String name = callback.getToolDefinition().name();
                String mimeType = properties != null ? properties.getToolResponseMimeType().get(name) : null;
                SyncToolSpecification derived = McpToolUtils.toSyncToolSpecification(callback,
                        mimeType != null ? MimeType.valueOf(mimeType) : null);
                ToolSpecificationEntry entry = listed.get(name);
                if (entry == null) {
                    result.add(derived);
                } else {
                    result.add(apply(entry, callback, derived));
                    applied++;
                }
            }
            if (applied > 0) {
                log.info("AI-ATLAS: Applied generated input schemas and hints to {} MCP tool(s)", applied);
            }
            return List.copyOf(result);
        }

        private SyncToolSpecification apply(ToolSpecificationEntry entry, ToolCallback callback,
                                            SyncToolSpecification derived) {
            ObjectNode inputSchema = InputSchemaMerge.mergeInputSchema(entry.name(), callback.getToolDefinition().inputSchema(),
                    entry.inputSchema());
            McpSchema.Tool base = derived.tool();
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name(base.name())
                    .title(base.title())
                    .description(base.description())
                    .inputSchema(McpJsonDefaults.getMapper(), inputSchema.toString())
                    .outputSchema(base.outputSchema())
                    .annotations(toolAnnotations(entry.annotations()))
                    .meta(base.meta())
                    .build();
            declaredInputSchemas.register(tool.inputSchema(), inputSchema);
            return SyncToolSpecification.builder().tool(tool).callHandler(derived.callHandler()).build();
        }
    }

    /**
     * Lazily discovers @Tool-annotated beans on first access, for ASYNC and STATELESS servers.
     * This avoids triggering bean creation during the factory method, which would cause a circular
     * dependency with the MCP server.
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
                        callbacks = resolveCallbacks(context);
                    }
                }
            }
            return callbacks;
        }
    }

    private static ToolCallback[] resolveCallbacks(ApplicationContext context) {
        List<Object> toolBeans = new ArrayList<>();

        // Only scan @Service beans — avoids triggering infrastructure beans
        // like mcpSyncServer which would cause circular dependency
        Map<String, Object> serviceBeans = context.getBeansWithAnnotation(Service.class);
        for (Map.Entry<String, Object> entry : serviceBeans.entrySet()) {
            if (!hasToolMethods(entry.getValue())) {
                continue;
            }
            if (AopUtils.isJdkDynamicProxy(entry.getValue())) {
                // MethodToolCallback invokes the target class's method on the bean, which
                // a JDK interface proxy is not an instance of: every call would fail.
                log.warn("AI-ATLAS: Skipped MCP tool bean '{}': it is a JDK interface proxy, "
                        + "whose @Tool methods cannot be invoked; proxy it by class "
                        + "(proxyTargetClass=true) to register its tools", entry.getKey());
            } else {
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

    /**
     * Decides on the bean's target class, the same way {@link MethodToolCallbackProvider}
     * does: an AOP proxy's own class (a CGLIB subclass or a JDK interface proxy) does not
     * carry the {@code @Tool} annotations, so checking it would silently drop the bean.
     * The proxy itself is still what gets registered, so calls go through its advice.
     */
    private static boolean hasToolMethods(Object bean) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        for (Method method : ReflectionUtils.getDeclaredMethods(targetClass)) {
            if (AnnotationUtils.findAnnotation(method, Tool.class) != null) {
                return true;
            }
        }
        return false;
    }
}
