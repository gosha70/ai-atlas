/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.json.AgentSafeModule;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Serializes the results of the application's own tools through {@link AgentSafeToolCallResultConverter}
 * (issue #50), so an {@code @AgenticEntity} keeps its {@code @AgenticField} whitelist whichever bean
 * serves the tool to Spring AI's MCP server.
 *
 * <p>Spring AI's MCP server serves every {@link ToolCallbackProvider}, {@link ToolCallback} and
 * {@code List<ToolCallback>} bean, each callback with its own result converter, which is Spring AI's
 * {@link DefaultToolCallResultConverter} unless the tool names another. This post-processor
 * rebuilds each {@link MethodToolCallback} and {@link FunctionToolCallback} that uses the default
 * converter, with the same definition, metadata, method or function and target, around the
 * agent-safe converter. A provider bean is wrapped in a proxy of its interfaces that does so on
 * every {@code getToolCallbacks()} call. A callback with a converter of its own keeps it.
 *
 * <p>Any other callback's result conversion cannot be seen. Serving one under a tool name that
 * AI-ATLAS registers, a {@code @Tool} method of a {@code @Service} bean or a tool listed in
 * {@value AgenticMcpConfiguration#TOOL_SPECIFICATIONS}, fails startup while Spring AI's tool-callback
 * conversion serves the application's beans. Any other opaque callback is left as it is, with one
 * DEBUG line.
 *
 * <p>A provider bean is replaced by a proxy: of its class, or of its interfaces when its class is
 * final, as {@code MethodToolCallbackProvider} is, so inject that one as {@code ToolCallbackProvider}.
 */
@ConditionalOnClass(ToolCallbackProvider.class)
public class AgentSafeToolCallbacks implements BeanPostProcessor, ApplicationContextAware, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentSafeToolCallbacks.class);

    /** The instance of the most recently started context, for the reflectively built converter. */
    private static volatile AgentSafeToolCallbacks current;

    private ConfigurableApplicationContext context;
    private volatile AgentSafeToolCallResultConverter converter;
    private volatile Set<String> atlasToolNames;
    private final Set<String> reportedOpaque = ConcurrentHashMap.newKeySet();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = (ConfigurableApplicationContext) applicationContext;
        current = this;
    }

    @Override
    public void destroy() {
        if (current == this) {
            current = null;
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ToolCallbackProvider provider
                && !(bean instanceof AgenticMcpConfiguration.LazyToolCallbackProvider)) {
            return agentSafeProvider(provider, "ToolCallbackProvider bean '" + beanName + "'");
        }
        if (bean instanceof ToolCallback callback) {
            return protect(callback, "ToolCallback bean '" + beanName + "'");
        }
        if (bean instanceof List<?> list && isToolCallbackList(beanName)) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(element instanceof ToolCallback callback
                    ? protect(callback, "List<ToolCallback> bean '" + beanName + "'") : element));
            return result;
        }
        return bean;
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
     * The module of the running application for a converter Spring AI instantiates reflectively,
     * from {@code @Tool(resultConverter = AgentSafeToolCallResultConverter.class)}; the default
     * (flat) settings outside an application context.
     */
    static AgentSafeModule currentModule() {
        AgentSafeToolCallbacks callbacks = current;
        if (callbacks == null) {
            return new AgentSafeModule(false, true, true);
        }
        try {
            return module(callbacks.context);
        } catch (IllegalStateException e) {
            // The context is closing: its settings still apply
            return module(callbacks.context.getEnvironment());
        }
    }

    private ToolCallResultConverter converter() {
        if (converter == null) {
            converter = new AgentSafeToolCallResultConverter(module(context));
        }
        return converter;
    }

    private Object agentSafeProvider(ToolCallbackProvider provider, String source) {
        ProxyFactory factory = new ProxyFactory(provider);
        // A class proxy keeps the provider's own type for injection; a final class, such as
        // MethodToolCallbackProvider, can only be proxied by its interfaces
        factory.setProxyTargetClass(!Modifier.isFinal(provider.getClass().getModifiers())
                && !provider.getClass().isHidden());
        factory.setInterfaces(ClassUtils.getAllInterfaces(provider));
        factory.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (result instanceof ToolCallback[] callbacks && invocation.getMethod().getName().equals("getToolCallbacks")
                    && invocation.getMethod().getParameterCount() == 0) {
                ToolCallback[] safe = new ToolCallback[callbacks.length];
                for (int i = 0; i < callbacks.length; i++) {
                    safe[i] = protect(callbacks[i], source);
                }
                return safe;
            }
            return result;
        });
        return factory.getProxy(provider.getClass().getClassLoader());
    }

    /** Whether the bean is declared as a {@code List} of tool callbacks, as Spring AI injects it. */
    private boolean isToolCallbackList(String beanName) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }
        ResolvableType type = beanFactory.getMergedBeanDefinition(beanName).getResolvableType();
        Class<?> element = type.asCollection().resolveGeneric(0);
        return List.class.isAssignableFrom(type.toClass()) && element != null
                && ToolCallback.class.isAssignableFrom(element);
    }

    /** The callback, rebuilt around the agent-safe converter when it would use Spring AI's default one. */
    ToolCallback protect(ToolCallback callback, String source) {
        if (callback.getClass() == MethodToolCallback.class) {
            ToolCallResultConverter own = field(callback, "toolCallResultConverter");
            Method method = field(callback, "toolMethod");
            Object target = field(callback, "toolObject");
            if (own != null && own.getClass() != DefaultToolCallResultConverter.class) {
                return callback;
            }
            if (own != null && method != null) {
                return MethodToolCallback.builder()
                        .toolDefinition(callback.getToolDefinition())
                        .toolMetadata(callback.getToolMetadata())
                        .toolMethod(method)
                        .toolObject(target)
                        .toolCallResultConverter(converter())
                        .build();
            }
        } else if (callback.getClass() == FunctionToolCallback.class) {
            ToolCallResultConverter own = field(callback, "toolCallResultConverter");
            Type inputType = field(callback, "toolInputType");
            BiFunction<Object, org.springframework.ai.chat.model.ToolContext, Object> function =
                    field(callback, "toolFunction");
            if (own != null && own.getClass() != DefaultToolCallResultConverter.class) {
                return callback;
            }
            if (own != null && inputType != null && function != null) {
                return new FunctionToolCallback<>(callback.getToolDefinition(), callback.getToolMetadata(),
                        inputType, function, converter());
            }
        }
        return opaque(callback, source);
    }

    private ToolCallback opaque(ToolCallback callback, String source) {
        String name = callback.getToolDefinition().name();
        if (servedByMcp() && atlasToolNames().contains(name)) {
            throw new IllegalStateException("AI-ATLAS: MCP tool '" + name + "' is served by " + source
                    + " through a " + callback.getClass().getName() + ", whose result "
                    + "conversion AI-ATLAS cannot see, so an @AgenticEntity result could reach the MCP client "
                    + "with fields outside its @AgenticField whitelist. Serve the tool's @Tool method through a "
                    + "MethodToolCallbackProvider (or a MethodToolCallback or FunctionToolCallback), or remove '"
                    + name + "' from that bean so AI-ATLAS registers it");
        }
        if (reportedOpaque.add(source + '\u0000' + name)) {
            log.debug("AI-ATLAS: MCP tool '{}' from {} is a {}, whose result conversion AI-ATLAS cannot "
                    + "see; it is served as it is", name, source, callback.getClass().getName());
        }
        return callback;
    }

    /** Whether Spring AI's MCP server serves the application's tool callback beans. */
    private boolean servedByMcp() {
        return AgenticMcpConfiguration.toolCallbackConversionActive(context)
                || context.getBeanNamesForType(StatelessToolCallbackConverterAutoConfiguration.class, false, false)
                .length > 0;
    }

    /**
     * The tool names AI-ATLAS registers: those of the {@code @Tool} methods of {@code @Service} beans,
     * read from the bean types without creating a bean, and those listed in any tool specifications
     * resource.
     */
    private Set<String> atlasToolNames() {
        if (atlasToolNames == null) {
            Set<String> names = new HashSet<>(AgenticMcpConfiguration.readToolSpecifications(context).keySet());
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            for (String bean : beanFactory.getBeanDefinitionNames()) {
                Class<?> type;
                try {
                    type = beanFactory.getType(bean, false);
                } catch (RuntimeException e) {
                    continue;
                }
                if (type == null) {
                    continue;
                }
                Class<?> userType = ClassUtils.getUserClass(type);
                if (!AnnotatedElementUtils.hasAnnotation(userType, Service.class)) {
                    continue;
                }
                for (Method method : ReflectionUtils.getDeclaredMethods(userType)) {
                    if (AnnotationUtils.findAnnotation(method, Tool.class) != null) {
                        names.add(ToolUtils.getToolName(method));
                    }
                }
            }
            atlasToolNames = Set.copyOf(names);
        }
        return atlasToolNames;
    }

    /** A private field of a Spring AI callback, or {@code null} when this Spring AI version lacks it. */
    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        Field field = ReflectionUtils.findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            ReflectionUtils.makeAccessible(field);
            return (T) field.get(target);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }
}
