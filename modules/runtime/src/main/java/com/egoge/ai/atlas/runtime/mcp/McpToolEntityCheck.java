/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reports, at startup, each method annotated with Spring AI's {@code @McpTool} whose return type is,
 * or contains, an {@code @AgenticEntity} or a subtype of one (issue #50). Spring AI's MCP annotation
 * support serializes those results itself, outside the tool-callback path AI-ATLAS protects, so their
 * fields outside the {@code @AgenticField} whitelist reach the MCP client. Each is logged with a
 * WARNING, or fails startup when {@value AgentSafeToolCallbacks#FAIL_ON_UNPROTECTED} is {@code true}.
 * Nothing is checked while Spring AI's annotation scanner is off.
 */
public class McpToolEntityCheck implements ApplicationContextAware, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(McpToolEntityCheck.class);

    private static final String MCP_TOOL = "org.springaicommunity.mcp.annotation.McpTool";
    private static final String ANNOTATION_SCANNER = "org.springframework.ai.mcp.server.common.autoconfigure."
            + "annotations.McpServerAnnotationScannerAutoConfiguration";

    private ConfigurableApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.context = (ConfigurableApplicationContext) applicationContext;
    }

    /**
     * Reports each {@code @McpTool} method that returns an {@code @AgenticEntity}, which Spring AI's
     * MCP annotation support serves without the whitelist.
     */
    @Override
    public void afterSingletonsInstantiated() {
        ClassLoader classLoader = context.getClassLoader();
        if (!ClassUtils.isPresent(MCP_TOOL, classLoader) || !ClassUtils.isPresent(ANNOTATION_SCANNER, classLoader)
                || context.getBeanNamesForType(ClassUtils.resolveClassName(ANNOTATION_SCANNER, classLoader),
                false, false).length == 0) {
            return;
        }
        @SuppressWarnings("unchecked")
        Class<? extends Annotation> mcpTool =
                (Class<? extends Annotation>) ClassUtils.resolveClassName(MCP_TOOL, classLoader);
        List<String> unprotected = new ArrayList<>();
        Set<Method> seen = new HashSet<>();
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        for (String bean : beanFactory.getBeanDefinitionNames()) {
            Class<?> userType = AgentSafeToolCallbacks.userType(beanFactory, bean);
            if (userType == null || !AnnotationUtils.isCandidateClass(userType, mcpTool)) {
                continue;
            }
            for (Method method : AgentSafeToolCallbacks.declaredMethods(userType, true)) {
                Annotation annotation = AnnotationUtils.findAnnotation(method, mcpTool);
                if (annotation == null || !seen.add(method)) {
                    continue;
                }
                Class<?> entity = entityIn(method.getGenericReturnType(), new HashSet<>());
                if (entity != null) {
                    Object toolName = AnnotationUtils.getValue(annotation, "name");
                    String name = toolName instanceof String text && StringUtils.hasText(text) ? text : method.getName();
                    unprotected.add("MCP tool '" + name + "' (@McpTool method " + userType.getName() + "."
                            + method.getName() + " of bean '" + bean + "') returns the @AgenticEntity "
                            + entity.getName());
                }
            }
        }
        if (unprotected.isEmpty()) {
            return;
        }
        String remedy = "Spring AI's MCP annotation support serializes @McpTool results itself, so their "
                + "fields outside the @AgenticField whitelist reach the MCP client. Return a generated DTO, "
                + "or serialize the result with AgentSafeModule";
        if (AgentSafeToolCallbacks.failOnUnprotected(context.getEnvironment())) {
            throw new IllegalStateException("AI-ATLAS: " + String.join("; ", unprotected) + ". " + remedy
                    + ", or set " + AgentSafeToolCallbacks.FAIL_ON_UNPROTECTED + "=false");
        }
        unprotected.forEach(tool -> log.warn("AI-ATLAS: {}. {}", tool, remedy));
    }

    /** The first {@code @AgenticEntity} type anywhere in {@code type}, its type arguments included. */
    static Class<?> entityIn(Type type, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return null;
        }
        if (type instanceof Class<?> type1) {
            return type1.isArray() ? entityIn(type1.getComponentType(), seen) : entityOf(type1);
        }
        List<Type> parts = new ArrayList<>();
        if (type instanceof ParameterizedType parameterized) {
            parts.add(parameterized.getRawType());
            parts.addAll(List.of(parameterized.getActualTypeArguments()));
        } else if (type instanceof GenericArrayType array) {
            parts.add(array.getGenericComponentType());
        } else if (type instanceof WildcardType wildcard) {
            parts.addAll(List.of(wildcard.getUpperBounds()));
            parts.addAll(List.of(wildcard.getLowerBounds()));
        } else if (type instanceof TypeVariable<?> variable) {
            parts.addAll(List.of(variable.getBounds()));
        }
        for (Type part : parts) {
            Class<?> entity = entityIn(part, seen);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /** The class itself when an {@code @AgenticEntity}, else its nearest annotated supertype. */
    private static Class<?> entityOf(Class<?> type) {
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            Class<?> current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current.isAnnotationPresent(AgenticEntity.class)) {
                return current;
            }
            if (current.getSuperclass() != null) {
                pending.add(current.getSuperclass());
            }
            pending.addAll(List.of(current.getInterfaces()));
        }
        return null;
    }
}
