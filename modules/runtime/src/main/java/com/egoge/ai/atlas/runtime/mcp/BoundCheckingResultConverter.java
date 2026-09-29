/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.security.ResultBounds;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Converts a tool result with its delegate, after logging a WARN when the result holds more
 * elements than the tool method's {@code @AgenticBound} declares ({@link ResultBounds}). The result
 * is never changed.
 *
 * @param delegate the converter that writes the result, such as {@link AgentSafeToolCallResultConverter}
 * @param method   the tool method, carrying its bound
 */
record BoundCheckingResultConverter(ToolCallResultConverter delegate, Method method)
        implements ToolCallResultConverter {

    /** {@code delegate} itself when {@code method} declares no bound, or it checking the bound. */
    static ToolCallResultConverter of(ToolCallResultConverter delegate, Method method) {
        return ResultBounds.bound(method) == null ? delegate : new BoundCheckingResultConverter(delegate, method);
    }

    /** The converter that writes the results: the delegate of a bound-checking one, or {@code converter}. */
    static ToolCallResultConverter unwrap(ToolCallResultConverter converter) {
        return converter instanceof BoundCheckingResultConverter checking ? checking.delegate() : converter;
    }

    @Override
    public String convert(Object result, Type returnType) {
        ResultBounds.check(result, method, "MCP tool '" + method.getName() + "'");
        return delegate.convert(result, returnType);
    }
}
