/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.context.request.NativeWebRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Fails startup when a generated REST endpoint takes a Spring Data {@code Pageable} that the
 * application's paging resolver would not bind as ai-atlas publishes it. The generated controller
 * and the OpenAPI document both use zero-based {@code page}, {@code size} and {@code sort} query
 * parameters without a prefix, and the controller checks the raw {@code page} and {@code size} to
 * reject what the resolver would clamp. A resolver that renames those parameters, adds a prefix or
 * is one-indexed would make those checks misfire, answer 400 to documented requests, or clamp
 * silently.
 *
 * <p>The resolver's settings are not public, and an application may change them through
 * {@code spring.data.web.*} properties or a customizer, so the check resolves two probe requests,
 * {@code page=1&size=1} and {@code page=2&size=2}, each with {@code sort=id,desc}, and compares the
 * result. It runs only when a Contract IR on the class path lists an API operation with a
 * {@code Pageable} parameter, so an application without ai-atlas paging is never affected.
 *
 * <p>A renamed or prefixed {@code sort} parameter only logs a WARNING: the generated controller
 * reads {@code sort} only for an operation declaring sortable fields, and drops it otherwise, and
 * the Contract IR does not record which operations declare them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = PageableBindingCheck.RESOLVER)
public class PageableBindingCheck {

    static final String RESOLVER = "org.springframework.data.web.PageableHandlerMethodArgumentResolver";
    static final String IR_RESOURCE = "META-INF/ai-atlas/api.ir.json";

    private static final Logger log = LoggerFactory.getLogger(PageableBindingCheck.class);
    private static final String PREFIX = "[ai-atlas] ";
    private static final String PAGEABLE = "org.springframework.data.domain.Pageable";
    private static final String API_CHANNEL = "API";
    private static final String SORT_PROPERTY = "id";
    private static final int[] PROBES = {1, 2};
    private static final ObjectMapper JSON = new ObjectMapper();

    @Bean
    SmartInitializingSingleton agenticPageableBindingCheck(
            ObjectProvider<PageableHandlerMethodArgumentResolver> resolvers, ApplicationContext context) {
        return () -> {
            PageableHandlerMethodArgumentResolver resolver = resolvers.getIfUnique();
            if (resolver == null) {
                return;
            }
            Set<String> operations = pageableApiOperations(context.getClassLoader());
            if (operations.isEmpty()) {
                return;
            }
            String sortProblem = sortProblem(resolver);
            if (sortProblem != null) {
                log.warn(PREFIX + "The application's Spring Data paging resolver {}. An operation of {} that"
                        + " declares sortable fields would then ignore the documented sort parameter; keep"
                        + " spring.data.web.sort.sort-parameter=sort for those.", sortProblem, operations);
            }
            Set<String> problems = problems(resolver);
            if (!problems.isEmpty()) {
                throw new IllegalStateException(PREFIX + "The generated REST endpoints of " + operations
                        + " publish zero-based page, size and sort query parameters without a prefix, but the"
                        + " application's Spring Data paging resolver " + String.join("; ", problems)
                        + ". Keep spring.data.web.pageable.page-parameter=page, size-parameter=size, no prefix,"
                        + " one-indexed-parameters=false, and no PageableHandlerMethodArgumentResolverCustomizer"
                        + " changing them");
            }
        };
    }

    /**
     * How the resolver departs from zero-based {@code page} and {@code size} without a prefix, each
     * described once; empty when it binds them as published.
     */
    static Set<String> problems(PageableHandlerMethodArgumentResolver resolver) {
        Set<String> problems = new LinkedHashSet<>();
        for (int n : PROBES) {
            Pageable pageable = probe(resolver, n);
            if (pageable.isUnpaged() || pageable.getPageNumber() != n) {
                problems.add("does not read ?page=N as page N (a renamed or prefixed page parameter, or"
                        + " one-indexed pages)");
            }
            if (pageable.isUnpaged() || pageable.getPageSize() != n) {
                problems.add("does not read ?size=N as size N (a renamed or prefixed size parameter, or a"
                        + " maximum page size below 2)");
            }
        }
        return problems;
    }

    /** How the resolver departs from the published {@code sort} parameter, or {@code null}. */
    static String sortProblem(PageableHandlerMethodArgumentResolver resolver) {
        for (int n : PROBES) {
            Sort.Order order = probe(resolver, n).getSort().getOrderFor(SORT_PROPERTY);
            if (order == null || order.getDirection() != Sort.Direction.DESC) {
                return "does not read ?sort=" + SORT_PROPERTY + ",desc as a descending sort on " + SORT_PROPERTY
                        + " (a renamed or prefixed sort parameter)";
            }
        }
        return null;
    }

    /** Resolves {@code ?page=n&size=n&sort=id,desc} with the resolver. */
    private static Pageable probe(PageableHandlerMethodArgumentResolver resolver, int n) {
        return resolver.resolveArgument(probeParameter(), null, request(Map.of(
                "page", String.valueOf(n), "size", String.valueOf(n), "sort", SORT_PROPERTY + ",desc")), null);
    }

    /** The ids of the API operations taking a {@code Pageable}, over every Contract IR on the class path. */
    static Set<String> pageableApiOperations(ClassLoader loader) {
        Set<String> operations = new TreeSet<>();
        try {
            for (URL url : Collections.list(loader.getResources(IR_RESOURCE))) {
                try (InputStream in = url.openStream()) {
                    for (JsonNode op : JSON.readTree(in).path("operations")) {
                        if (onApi(op) && takesPageable(op)) {
                            operations.add(op.path("id").asText());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + IR_RESOURCE, e);
        }
        return operations;
    }

    private static boolean onApi(JsonNode op) {
        for (JsonNode channel : op.path("channels")) {
            if (API_CHANNEL.equals(channel.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean takesPageable(JsonNode op) {
        for (JsonNode parameter : op.path("parameters")) {
            if (PAGEABLE.equals(parameter.path("javaType").asText())) {
                return true;
            }
        }
        return false;
    }

    /** An unannotated {@code Pageable} parameter, as the generated controllers declare it. */
    private static MethodParameter probeParameter() {
        try {
            return new MethodParameter(PageableBindingCheck.class.getDeclaredMethod("probe", Pageable.class), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused") // the probe's parameter, read reflectively
    private static void probe(Pageable pageable) {
    }

    /** A request carrying only the given query parameters. */
    private static NativeWebRequest request(Map<String, String> parameters) {
        return (NativeWebRequest) Proxy.newProxyInstance(PageableBindingCheck.class.getClassLoader(),
                new Class<?>[] {NativeWebRequest.class}, (proxy, method, args) -> answer(parameters, method, args));
    }

    private static Object answer(Map<String, String> parameters, Method method, Object[] args) {
        return switch (method.getName()) {
            case "getParameter" -> parameters.get((String) args[0]);
            case "getParameterValues" -> parameters.containsKey((String) args[0])
                    ? new String[] {parameters.get((String) args[0])} : null;
            case "getParameterNames" -> new ArrayList<>(parameters.keySet()).iterator();
            case "getParameterMap" -> Map.copyOf(parameters.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> new String[] {e.getValue()})));
            case "hashCode" -> System.identityHashCode(parameters);
            case "equals" -> false;
            case "toString" -> "ai-atlas paging probe " + parameters;
            default -> method.getReturnType() == boolean.class ? false : null;
        };
    }
}
