/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Protects every tool callback Spring AI's tool-callback conversion serves over MCP at the one place
 * it gathers them (issue #50).
 *
 * <p>Spring AI's {@link ToolCallbackConverterAutoConfiguration} and
 * {@link StatelessToolCallbackConverterAutoConfiguration} gather the {@code List<ToolCallback>},
 * {@code ToolCallback}, {@code List<ToolCallbackProvider>} and {@code ToolCallbackProvider} beans
 * into one {@code syncTools} or {@code asyncTools} bean of tool specifications, keeping the first
 * callback of each name. Those beans are not {@code @ConditionalOnMissingBean}, so this post-processor
 * replaces their definitions, under the same names, with factory methods that take the same inputs,
 * pass each callback through {@link AgentSafeToolCallbacks#protect}, and hand them to Spring AI's own
 * configuration, so the precedence and deduplication stay Spring AI's. The application's beans keep
 * their own instances and types.
 *
 * <p>Startup fails when one of those configurations declares a bean this class does not know, as
 * with a Spring AI version it was not built for, since the tools of such a bean would be served
 * unprotected.
 */
public class AgentSafeMcpToolSpecifications implements BeanDefinitionRegistryPostProcessor, Ordered {

    private static final String STATEFUL =
            "org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration";
    private static final String STATELESS =
            "org.springframework.ai.mcp.server.common.autoconfigure.StatelessToolCallbackConverterAutoConfiguration";

    /** Spring AI's factory method, by configuration class, and the one that replaces it. */
    private static final Map<String, Map<String, String>> FACTORIES = Map.of(
            STATEFUL, Map.of("syncTools", "statefulSyncTools", "asyncTools", "statefulAsyncTools"),
            STATELESS, Map.of("syncTools", "statelessSyncTools", "asyncTools", "statelessAsyncTools"));

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Map<String, String> configurations = new LinkedHashMap<>();
        for (String name : registry.getBeanDefinitionNames()) {
            String className = registry.getBeanDefinition(name).getBeanClassName();
            if (className != null && FACTORIES.containsKey(className)) {
                configurations.put(name, className);
            }
        }
        configurations.forEach((configuration, className) -> {
            int replaced = 0;
            for (String name : registry.getBeanDefinitionNames()) {
                BeanDefinition original = registry.getBeanDefinition(name);
                if (!configuration.equals(original.getFactoryBeanName())) {
                    continue;
                }
                String factoryMethod = FACTORIES.get(className).get(original.getFactoryMethodName());
                if (factoryMethod == null) {
                    throw unknown(className, "declares the bean '" + name + "' (method "
                            + original.getFactoryMethodName() + "), which AI-ATLAS does not know");
                }
                registry.removeBeanDefinition(name);
                registry.registerBeanDefinition(name, replacement(original, factoryMethod));
                replaced++;
            }
            if (replaced == 0) {
                throw unknown(className, "declares no syncTools or asyncTools bean");
            }
        });
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }

    private static IllegalStateException unknown(String className, String problem) {
        return new IllegalStateException("AI-ATLAS: Spring AI's " + ClassUtils.getShortName(className) + " "
                + problem + ", so AI-ATLAS cannot keep the results of the MCP tools it serves to the "
                + "@AgenticField whitelist. Use the Spring AI version this AI-ATLAS release is built against, "
                + "or set spring.ai.mcp.server.tool-callback-converter=false");
    }

    private static RootBeanDefinition replacement(BeanDefinition original, String factoryMethod) {
        RootBeanDefinition definition = new RootBeanDefinition(AgentSafeMcpToolSpecifications.class);
        definition.setUniqueFactoryMethodName(factoryMethod);
        definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        Method method = Arrays.stream(AgentSafeMcpToolSpecifications.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(factoryMethod)).findFirst().orElseThrow();
        definition.setTargetType(ResolvableType.forMethodReturnType(method));
        definition.setRole(original.getRole());
        definition.setPrimary(original.isPrimary());
        definition.setLazyInit(original.isLazyInit());
        definition.setDependsOn(original.getDependsOn());
        definition.setDescription("AI-ATLAS: Spring AI's " + original.getFactoryMethodName()
                + ", with each tool callback's results kept to the @AgenticField whitelist");
        return definition;
    }

    public static List<McpServerFeatures.SyncToolSpecification> statefulSyncTools(
            ObjectProvider<List<ToolCallback>> toolCalls, List<ToolCallback> toolCallbackList,
            ObjectProvider<List<ToolCallbackProvider>> tcbProviderList,
            ObjectProvider<ToolCallbackProvider> tcbProviders, McpServerProperties serverProperties,
            AgentSafeToolCallbacks protection, ListableBeanFactory beanFactory) {
        Inputs in = new Inputs(protection, beanFactory, toolCalls, toolCallbackList, tcbProviderList, tcbProviders);
        return new ToolCallbackConverterAutoConfiguration().syncTools(in.toolCalls, in.toolCallbackList,
                in.tcbProviderList, in.tcbProviders, serverProperties);
    }

    public static List<McpServerFeatures.AsyncToolSpecification> statefulAsyncTools(
            ObjectProvider<List<ToolCallback>> toolCalls, List<ToolCallback> toolCallbacksList,
            ObjectProvider<List<ToolCallbackProvider>> tcbProviderList,
            ObjectProvider<ToolCallbackProvider> tcbProviders, McpServerProperties serverProperties,
            AgentSafeToolCallbacks protection, ListableBeanFactory beanFactory) {
        Inputs in = new Inputs(protection, beanFactory, toolCalls, toolCallbacksList, tcbProviderList, tcbProviders);
        return new ToolCallbackConverterAutoConfiguration().asyncTools(in.toolCalls, in.toolCallbackList,
                in.tcbProviderList, in.tcbProviders, serverProperties);
    }

    public static List<McpStatelessServerFeatures.SyncToolSpecification> statelessSyncTools(
            ObjectProvider<List<ToolCallback>> toolCalls, List<ToolCallback> toolCallbackList,
            ObjectProvider<List<ToolCallbackProvider>> tcbProviderList,
            ObjectProvider<ToolCallbackProvider> tcbProviders, McpServerProperties serverProperties,
            AgentSafeToolCallbacks protection, ListableBeanFactory beanFactory) {
        Inputs in = new Inputs(protection, beanFactory, toolCalls, toolCallbackList, tcbProviderList, tcbProviders);
        return new StatelessToolCallbackConverterAutoConfiguration().syncTools(in.toolCalls, in.toolCallbackList,
                in.tcbProviderList, in.tcbProviders, serverProperties);
    }

    public static List<McpStatelessServerFeatures.AsyncToolSpecification> statelessAsyncTools(
            ObjectProvider<List<ToolCallback>> toolCalls, List<ToolCallback> toolCallbacksList,
            ObjectProvider<List<ToolCallbackProvider>> tcbProviderList,
            ObjectProvider<ToolCallbackProvider> tcbProviders, McpServerProperties serverProperties,
            AgentSafeToolCallbacks protection, ListableBeanFactory beanFactory) {
        Inputs in = new Inputs(protection, beanFactory, toolCalls, toolCallbacksList, tcbProviderList, tcbProviders);
        return new StatelessToolCallbackConverterAutoConfiguration().asyncTools(in.toolCalls, in.toolCallbackList,
                in.tcbProviderList, in.tcbProviders, serverProperties);
    }

    /**
     * Spring AI's inputs, each callback protected. The same callback or provider always maps to the
     * same protected instance, so Spring AI's deduplication of repeated beans still applies.
     */
    private static final class Inputs {

        private final AgentSafeToolCallbacks protection;
        private final Map<Object, String> sources;
        private final Map<ToolCallback, ToolCallback> callbacks = new IdentityHashMap<>();
        private final Map<ToolCallbackProvider, ToolCallbackProvider> providers = new IdentityHashMap<>();

        final ObjectProvider<List<ToolCallback>> toolCalls;
        final List<ToolCallback> toolCallbackList;
        final ObjectProvider<List<ToolCallbackProvider>> tcbProviderList;
        final ObjectProvider<ToolCallbackProvider> tcbProviders;

        Inputs(AgentSafeToolCallbacks protection, ListableBeanFactory beanFactory,
               ObjectProvider<List<ToolCallback>> toolCalls, List<ToolCallback> toolCallbackList,
               ObjectProvider<List<ToolCallbackProvider>> tcbProviderList,
               ObjectProvider<ToolCallbackProvider> tcbProviders) {
            this.protection = protection;
            this.sources = ApplicationToolBeans.sources(beanFactory);
            this.toolCalls = new Mapped<>(toolCalls, list -> list == null ? null : list.stream().map(this::callback).toList());
            this.toolCallbackList = toolCallbackList == null ? null
                    : toolCallbackList.stream().map(this::callback).toList();
            this.tcbProviderList = new Mapped<>(tcbProviderList,
                    list -> list == null ? null : list.stream().map(this::provider).toList());
            this.tcbProviders = new Mapped<>(tcbProviders, this::provider);
        }

        private ToolCallback callback(ToolCallback callback) {
            return callback == null ? null : callbacks.computeIfAbsent(callback,
                    key -> protection.protect(key, sources.getOrDefault(key, "a ToolCallback")));
        }

        private ToolCallbackProvider provider(ToolCallbackProvider provider) {
            if (provider == null) {
                return null;
            }
            String source = sources.getOrDefault(provider, "a ToolCallbackProvider");
            return providers.computeIfAbsent(provider, key -> () -> Arrays.stream(key.getToolCallbacks())
                    .map(callback -> callbacks.computeIfAbsent(callback, raw -> protection.protect(raw, source)))
                    .toArray(ToolCallback[]::new));
        }
    }

    /** An {@link ObjectProvider} whose objects are mapped. */
    private record Mapped<T>(ObjectProvider<T> delegate, UnaryOperator<T> map) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return map.apply(delegate.getObject());
        }

        @Override
        public T getObject(Object... args) {
            return map.apply(delegate.getObject(args));
        }

        @Override
        public T getIfAvailable() {
            return map.apply(delegate.getIfAvailable());
        }

        @Override
        public T getIfUnique() {
            return map.apply(delegate.getIfUnique());
        }

        @Override
        public Iterator<T> iterator() {
            return stream().iterator();
        }

        @Override
        public Stream<T> stream() {
            return delegate.stream().map(map);
        }

        @Override
        public Stream<T> orderedStream() {
            return delegate.orderedStream().map(map);
        }
    }
}
