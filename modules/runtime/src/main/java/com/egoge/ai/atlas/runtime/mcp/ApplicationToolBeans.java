/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.ResolvableType;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The application's beans that Spring AI's tool-callback conversion serves over MCP: its
 * {@code ToolCallback}, {@code List<ToolCallback>}, {@code ToolCallbackProvider} and
 * {@code List<ToolCallbackProvider>} beans, each named as {@code "ToolCallbackProvider bean 'name'"}.
 */
final class ApplicationToolBeans {

    private ApplicationToolBeans() {
    }

    /**
     * The tool names those beans serve, each with the first bean that serves it. On a SYNC server
     * AI-ATLAS registers no such bean, so every one found belongs to the application.
     */
    static Map<String, String> toolNames(ListableBeanFactory beanFactory) {
        Map<String, String> names = new LinkedHashMap<>();
        forEach(beanFactory, (item, source) -> {
            ToolCallback[] callbacks = item instanceof ToolCallbackProvider provider ? provider.getToolCallbacks()
                    : new ToolCallback[] {(ToolCallback) item};
            for (ToolCallback callback : callbacks) {
                names.putIfAbsent(callback.getToolDefinition().name(), source);
            }
        });
        return names;
    }

    /** The bean each of those callbacks and providers comes from, by identity. */
    static Map<Object, String> sources(ListableBeanFactory beanFactory) {
        Map<Object, String> sources = new IdentityHashMap<>();
        forEach(beanFactory, sources::putIfAbsent);
        return sources;
    }

    /** Each callback and provider, the elements of the list beans included, with its bean. */
    private static void forEach(ListableBeanFactory beanFactory, BiConsumer<Object, String> action) {
        each(beanFactory, ToolCallback.class, false, action);
        each(beanFactory, ToolCallback.class, true, action);
        each(beanFactory, ToolCallbackProvider.class, false, action);
        each(beanFactory, ToolCallbackProvider.class, true, action);
    }

    private static void each(ListableBeanFactory beanFactory, Class<?> element, boolean list,
                             BiConsumer<Object, String> action) {
        ResolvableType type = list ? ResolvableType.forClassWithGenerics(List.class, element)
                : ResolvableType.forClass(element);
        String kind = list ? "List<" + element.getSimpleName() + ">" : element.getSimpleName();
        for (String name : beanFactory.getBeanNamesForType(type, true, true)) {
            Object bean;
            try {
                bean = beanFactory.getBean(name);
            } catch (BeansException e) {
                // Spring AI reports a bean it cannot create itself
                continue;
            }
            String source = kind + " bean '" + name + "'";
            if (bean instanceof List<?> items) {
                items.stream().filter(element::isInstance).forEach(item -> action.accept(item, source));
            } else if (element.isInstance(bean)) {
                action.accept(bean, source);
            }
        }
    }
}
