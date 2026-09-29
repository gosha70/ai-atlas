/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.json.AgentSafeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.augment.AugmentedToolCallback;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Keeps the results of every tool callback Spring AI's MCP server serves from the application's own
 * beans to the {@code @AgenticField} whitelist (issue #50).
 *
 * <p>{@link AgentSafeMcpToolSpecifications} hands each callback Spring AI's tool-callback conversion
 * serves, from the {@link ToolCallbackProvider}, {@code List<ToolCallbackProvider>},
 * {@link ToolCallback} and {@code List<ToolCallback>} beans alike, to {@link #protect}, which
 * rebuilds each {@link MethodToolCallback} and {@link FunctionToolCallback} that uses Spring AI's
 * {@link DefaultToolCallResultConverter}, with the same definition, metadata, method or function and
 * target, around the context's {@link AgentSafeToolCallResultConverter}. The application's beans are
 * never replaced: only the callbacks the MCP server serves are. A callback that names a converter of
 * its own keeps it. Spring AI's {@link AugmentedToolCallback}, and a record with one
 * {@code ToolCallback} component, are rebuilt around their protected delegate.
 *
 * <p>Startup fails when a {@code MethodToolCallback} or {@code FunctionToolCallback} cannot be read,
 * as with a Spring AI version whose fields differ. Any other callback's result conversion cannot be
 * seen: serving one under a tool name that AI-ATLAS registers fails startup, and any other is served
 * with one WARNING per tool, or fails startup when {@value #FAIL_ON_UNPROTECTED} is {@code true}.
 *
 * <p>{@link McpToolEntityCheck} reports the {@code @McpTool} methods, which Spring AI's MCP annotation
 * support serves outside this path.
 */
@ConditionalOnClass(ToolCallbackProvider.class)
public class AgentSafeToolCallbacks implements ApplicationContextAware, DisposableBean {

    /** Fails startup, rather than warning, for a served tool whose results AI-ATLAS cannot whitelist. */
    public static final String FAIL_ON_UNPROTECTED = "ai.atlas.mcp.fail-on-unprotected-tools";

    private static final Logger log = LoggerFactory.getLogger(AgentSafeToolCallbacks.class);

    /** The instances of the running contexts, for a converter Spring AI builds reflectively. */
    private static final Set<AgentSafeToolCallbacks> LIVE = ConcurrentHashMap.newKeySet();

    private final FieldReader fields;
    private ConfigurableApplicationContext context;
    private volatile AgentSafeToolCallResultConverter converter;
    private volatile Map<String, List<String>> atlasTools;
    private final Set<String> reportedOpaque = ConcurrentHashMap.newKeySet();

    public AgentSafeToolCallbacks() {
        this(AgentSafeToolCallbacks::readField);
    }

    /** With the given way to read a Spring AI callback's private fields; a test seam. */
    AgentSafeToolCallbacks(FieldReader fields) {
        this.fields = fields;
    }

    /** Reads a private field of a Spring AI callback. */
    @FunctionalInterface
    interface FieldReader {

        /**
         * The field's value.
         *
         * @throws ReflectiveOperationException when the field is missing or cannot be read
         */
        Object read(Object target, String name) throws ReflectiveOperationException;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = (ConfigurableApplicationContext) applicationContext;
        LIVE.add(this);
    }

    @Override
    public void destroy() {
        LIVE.remove(this);
    }

    /** The application's {@code AgentSafeModule} bean, or one built from {@code ai.atlas.json.*}. */
    static AgentSafeModule module(ApplicationContext context) {
        return context.getBeanProvider(AgentSafeModule.class).getIfUnique(() -> module(context.getEnvironment()));
    }

    /** A module with the {@code ai.atlas.json.*} settings of {@code environment}. */
    static AgentSafeModule module(Environment environment) {
        Binder binder = Binder.get(environment);
        return new AgentSafeModule(
                binder.bind("ai.atlas.json.enriched", Boolean.class).orElse(false),
                binder.bind("ai.atlas.json.include-descriptions", Boolean.class).orElse(true),
                binder.bind("ai.atlas.json.include-valid-values", Boolean.class).orElse(true));
    }

    /**
     * The module for a converter Spring AI instantiates reflectively, from
     * {@code @Tool(resultConverter = AgentSafeToolCallResultConverter.class)}: that of the one running
     * context whose class loader is the thread's context class loader, or of the only running context.
     * With none, or several it cannot tell apart, the default (flat) settings, which keep the whitelist
     * too. A tool served over MCP is rebuilt around its own context's converter whatever this returns.
     */
    static AgentSafeModule currentModule() {
        List<AgentSafeToolCallbacks> live = new ArrayList<>(LIVE);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<AgentSafeToolCallbacks> sameLoader = live.stream()
                .filter(callbacks -> callbacks.context.getClassLoader() == loader).toList();
        List<AgentSafeToolCallbacks> candidates = sameLoader.isEmpty() ? live : sameLoader;
        if (candidates.size() != 1) {
            return new AgentSafeModule(false, true, true);
        }
        ConfigurableApplicationContext only = candidates.get(0).context;
        try {
            return module(only);
        } catch (IllegalStateException e) {
            // The context is closing: its settings still apply
            return module(only.getEnvironment());
        }
    }

    /** The converter of this context, built once. */
    AgentSafeToolCallResultConverter converter() {
        if (converter == null) {
            converter = new AgentSafeToolCallResultConverter(module(context));
        }
        return converter;
    }

    /**
     * The provider's callbacks, each kept to the {@code @AgenticField} whitelist as the MCP server
     * serves it, for tools called in process, as with
     * {@code ChatClient.prompt().toolCallbacks(agentSafeToolCallbacks.agentSafe(provider))}.
     *
     * @throws IllegalStateException as {@link #protect} does
     */
    public ToolCallback[] agentSafe(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> protect(callback, "the ToolCallbackProvider passed to agentSafe"))
                .toArray(ToolCallback[]::new);
    }

    /**
     * The callback as the MCP server may serve it: rebuilt around this context's agent-safe converter
     * when it would use Spring AI's default one; otherwise itself, with a WARNING or a startup failure
     * when its result conversion cannot be seen.
     *
     * @param source the bean the callback comes from, for the messages
     * @throws IllegalStateException when the callback cannot be protected and must not be served
     */
    ToolCallback protect(ToolCallback callback, String source) {
        ToolCallback safe = safe(callback, source);
        return safe != null ? safe : opaque(callback, source);
    }

    /** The protected callback, the callback itself when it converts results its own way, or {@code null}. */
    private ToolCallback safe(ToolCallback callback, String source) {
        Class<?> type = callback.getClass();
        if (type == MethodToolCallback.class) {
            ToolCallResultConverter own = required(callback, "toolCallResultConverter", source);
            Method method = required(callback, "toolMethod", source);
            Object target = read(callback, "toolObject", source);
            if (target == null && !Modifier.isStatic(method.getModifiers())) {
                throw unreadable(callback, "toolObject", source, null);
            }
            if (!replaceable(own)) {
                return callback;
            }
            return MethodToolCallback.builder()
                    .toolDefinition(callback.getToolDefinition())
                    .toolMetadata(callback.getToolMetadata())
                    .toolMethod(method)
                    .toolObject(target)
                    .toolCallResultConverter(converter())
                    .build();
        }
        if (type == FunctionToolCallback.class) {
            ToolCallResultConverter own = required(callback, "toolCallResultConverter", source);
            Type inputType = required(callback, "toolInputType", source);
            BiFunction<Object, ToolContext, Object> function = required(callback, "toolFunction", source);
            if (!replaceable(own)) {
                return callback;
            }
            return new FunctionToolCallback<>(callback.getToolDefinition(), callback.getToolMetadata(),
                    inputType, function, converter());
        }
        if (type == AugmentedToolCallback.class) {
            return safeAugmented(callback, source);
        }
        if (type.isRecord()) {
            return safeRecord(callback, source);
        }
        return null;
    }

    /** Spring AI's own converter, or an agent-safe one of another context or built reflectively. */
    private boolean replaceable(ToolCallResultConverter own) {
        return own.getClass() == DefaultToolCallResultConverter.class
                || own instanceof AgentSafeToolCallResultConverter && own != converter();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ToolCallback safeAugmented(ToolCallback callback, String source) {
        ToolCallback delegate = required(callback, "delegate", source);
        Class argumentsClass = required(callback, "augmentedArgumentsClass", source);
        Consumer consumer = read(callback, "augmentedArgumentsConsumer", source);
        Boolean remove = required(callback, "removeAugmentedArgumentsAfterProcessing", source);
        ToolCallback safe = safe(delegate, source);
        if (safe == null) {
            return null;
        }
        return safe == delegate ? callback : new AugmentedToolCallback(safe, argumentsClass, consumer, remove);
    }

    /** A record decorating exactly one {@code ToolCallback}, rebuilt around its protected delegate. */
    private ToolCallback safeRecord(ToolCallback callback, String source) {
        RecordComponent[] components = callback.getClass().getRecordComponents();
        int delegateIndex = -1;
        for (int i = 0; i < components.length; i++) {
            if (ToolCallback.class.isAssignableFrom(components[i].getType())) {
                if (delegateIndex >= 0) {
                    return null;
                }
                delegateIndex = i;
            }
        }
        if (delegateIndex < 0) {
            return null;
        }
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Constructor<?> constructor;
        try {
            for (int i = 0; i < components.length; i++) {
                Method accessor = components[i].getAccessor();
                ReflectionUtils.makeAccessible(accessor);
                values[i] = accessor.invoke(callback);
                types[i] = components[i].getType();
            }
            constructor = callback.getClass().getDeclaredConstructor(types);
            ReflectionUtils.makeAccessible(constructor);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        if (!(values[delegateIndex] instanceof ToolCallback delegate)) {
            return null;
        }
        ToolCallback safe = safe(delegate, source);
        if (safe == null || safe == delegate) {
            return safe == null ? null : callback;
        }
        values[delegateIndex] = safe;
        try {
            return (ToolCallback) constructor.newInstance(values);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private ToolCallback opaque(ToolCallback callback, String source) {
        String name = callback.getToolDefinition().name();
        String served = "MCP tool '" + name + "' is served by " + source + " through a "
                + callback.getClass().getName() + ", whose result conversion AI-ATLAS cannot see";
        if (isAtlasTool(name)) {
            throw new IllegalStateException("AI-ATLAS: " + served + ", so an @AgenticEntity result could reach "
                    + "the MCP client with fields outside its @AgenticField whitelist. " + atlasToolRemedy(name));
        }
        if (failOnUnprotected()) {
            throw new IllegalStateException("AI-ATLAS: " + served + ", so its results are not kept to the "
                    + "@AgenticField whitelist, and " + FAIL_ON_UNPROTECTED + " is true. Serve it through a "
                    + "MethodToolCallbackProvider (or a MethodToolCallback or FunctionToolCallback, or a record "
                    + "with one such ToolCallback component), or set " + FAIL_ON_UNPROTECTED + "=false to serve "
                    + "it as it is");
        }
        if (reportedOpaque.add(source + '\u0000' + name)) {
            log.warn("AI-ATLAS: {}: its results are not whitelisted, so an @AgenticEntity in them reaches the "
                    + "MCP client with every getter. Set {}=true to fail startup instead", served,
                    FAIL_ON_UNPROTECTED);
        }
        return callback;
    }

    private String atlasToolRemedy(String name) {
        String serveIt = "Serve the tool's @Tool method through a MethodToolCallbackProvider (or a "
                + "MethodToolCallback or FunctionToolCallback)";
        if (context.getEnvironment().getProperty("ai.atlas.mcp.enabled", Boolean.class, true)) {
            return serveIt + ", or remove '" + name + "' from that bean so AI-ATLAS registers it";
        }
        return serveIt + ", or remove '" + name + "' from that bean and set ai.atlas.mcp.enabled=true so "
                + "AI-ATLAS registers it";
    }

    private boolean failOnUnprotected() {
        return failOnUnprotected(context.getEnvironment());
    }

    static boolean failOnUnprotected(Environment environment) {
        return environment.getProperty(FAIL_ON_UNPROTECTED, Boolean.class, false);
    }

    /**
     * Whether AI-ATLAS registers a tool of that name: a {@code @Tool} method of a {@code @Service} bean
     * that is not a JDK interface proxy, which AI-ATLAS skips, or a tool listed in any tool
     * specifications resource.
     */
    private boolean isAtlasTool(String name) {
        List<String> beans = atlasTools().get(name);
        if (beans == null) {
            return false;
        }
        return beans.isEmpty() || beans.stream().anyMatch(bean -> !AopUtils.isJdkDynamicProxy(context.getBean(bean)));
    }

    /**
     * The tool names AI-ATLAS registers, each with its {@code @Service} beans, read from the bean types
     * without creating a bean; a name listed in a tool specifications resource has no beans.
     */
    private Map<String, List<String>> atlasTools() {
        if (atlasTools == null) {
            Map<String, List<String>> names = new LinkedHashMap<>();
            AgenticMcpConfiguration.readToolSpecifications(context).keySet()
                    .forEach(name -> names.put(name, List.of()));
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            for (String bean : beanFactory.getBeanDefinitionNames()) {
                Class<?> userType = userType(beanFactory, bean);
                if (userType == null || !AnnotatedElementUtils.hasAnnotation(userType, Service.class)) {
                    continue;
                }
                for (Method method : declaredMethods(userType, false)) {
                    if (AnnotationUtils.findAnnotation(method, Tool.class) != null) {
                        List<String> beans = names.computeIfAbsent(ToolUtils.getToolName(method),
                                key -> new ArrayList<>());
                        if (beans instanceof ArrayList<String> mutable) {
                            mutable.add(bean);
                        }
                    }
                }
            }
            atlasTools = names;
        }
        return atlasTools;
    }

    /**
     * The methods of a bean class, or none when they cannot be introspected, as when a signature names
     * a class missing from the class path; with {@code inherited}, those of its superclasses too.
     */
    static Method[] declaredMethods(Class<?> type, boolean inherited) {
        try {
            return inherited ? ReflectionUtils.getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)
                    : ReflectionUtils.getDeclaredMethods(type);
        } catch (IllegalStateException | LinkageError e) {
            return new Method[0];
        }
    }

    static Class<?> userType(ConfigurableListableBeanFactory beanFactory, String bean) {
        try {
            Class<?> type = beanFactory.getType(bean, false);
            return type == null ? null : ClassUtils.getUserClass(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A field of a Spring AI callback, which may be {@code null}. */
    @SuppressWarnings("unchecked")
    private <T> T read(ToolCallback callback, String name, String source) {
        try {
            return (T) fields.read(callback, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw unreadable(callback, name, source, e);
        }
    }

    /** A field of a Spring AI callback that is never {@code null}. */
    private <T> T required(ToolCallback callback, String name, String source) {
        T value = read(callback, name, source);
        if (value == null) {
            throw unreadable(callback, name, source, null);
        }
        return value;
    }

    private static IllegalStateException unreadable(ToolCallback callback, String field, String source,
                                                    Exception cause) {
        return new IllegalStateException("AI-ATLAS: Cannot read the field '" + field + "' of the "
                + callback.getClass().getName() + " of MCP tool '" + callback.getToolDefinition().name()
                + "' served by " + source + ", so AI-ATLAS cannot keep its results to the @AgenticField "
                + "whitelist. Use the Spring AI version this AI-ATLAS release is built against, and, on the "
                + "module path, open the package of " + callback.getClass().getSimpleName() + " to AI-ATLAS",
                cause);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = ReflectionUtils.findField(target.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldException(target.getClass().getName() + "." + name);
        }
        field.setAccessible(true);
        return field.get(target);
    }
}
