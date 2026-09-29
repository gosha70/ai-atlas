/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.security;

import com.egoge.ai.atlas.annotations.AgenticBound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;

/**
 * Checks a generated wrapper's result against the bound its service method declares with
 * {@code @AgenticExposed(maxResults)}, which the processor carries to the wrapper as
 * {@link AgenticBound}. A result over the bound is logged as a WARN and passed through unchanged:
 * ai-atlas declares bounds, it never truncates a result.
 */
public final class ResultBounds {

    private static final Logger log = LoggerFactory.getLogger(ResultBounds.class);
    /** The component a generated {@code PageResult} or {@code SliceResult} holds its elements in. */
    private static final String CONTENT = "content";

    private ResultBounds() {
    }

    /**
     * The declared bound of a generated wrapper method, or {@code null} when it declares none.
     *
     * @param method the wrapper method, or {@code null}
     */
    public static Integer bound(Method method) {
        AgenticBound bound = method != null ? method.getAnnotation(AgenticBound.class) : null;
        return bound != null ? bound.maxResults() : null;
    }

    /**
     * Logs a WARN when {@code result} holds more elements than {@code method} declares.
     *
     * @param result the wrapper's result
     * @param method the wrapper method
     * @param where  what served the result, for the message: a REST path or an MCP tool
     * @return {@code result}, unchanged
     */
    public static <T> T check(T result, Method method, String where) {
        Integer bound = bound(method);
        if (bound == null) {
            return result;
        }
        long count = count(result);
        if (count > bound) {
            log.warn("[ai-atlas] {} returned {} results, more than the maxResults = {} its service method {}#{}"
                            + " declares. The result is passed through unchanged; page the method, or correct the"
                            + " bound", where, count, bound, method.getDeclaringClass().getSimpleName(),
                    method.getName());
        }
        return result;
    }

    /**
     * The number of elements a result holds: a collection's, map's or array's size, or the size of
     * the {@code content} of a generated page or slice envelope; {@code -1} for anything else,
     * such as a {@code Stream}, which counting would consume.
     */
    static long count(Object result) {
        if (result instanceof Collection<?> collection) {
            return collection.size();
        }
        if (result instanceof Map<?, ?> map) {
            return map.size();
        }
        if (result != null && result.getClass().isArray()) {
            return Array.getLength(result);
        }
        if (result != null && result.getClass().isRecord()) {
            for (RecordComponent component : result.getClass().getRecordComponents()) {
                if (CONTENT.equals(component.getName()) && Collection.class.isAssignableFrom(component.getType())) {
                    try {
                        return count(component.getAccessor().invoke(result));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }
}
