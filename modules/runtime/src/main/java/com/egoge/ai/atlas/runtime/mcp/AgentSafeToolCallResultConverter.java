/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.runtime.json.AgentSafeModule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.util.json.JsonParser;

import java.awt.image.RenderedImage;
import java.lang.reflect.Type;

/**
 * Converts a tool result to JSON as Spring AI's {@link DefaultToolCallResultConverter} does, but
 * with {@link AgentSafeModule} applied, so an {@code @AgenticEntity} in the result, or an unannotated
 * subtype of one, reaches the MCP client through its {@code @AgenticField} getters only (issue #50).
 *
 * <p>The JSON is written by a copy of Spring AI's own tool-result mapper ({@link JsonParser}) with
 * the module registered, so every other value, generated DTOs included, serializes to the same
 * JSON as before. {@code void}, {@code String}, {@code null} and image results are left to Spring
 * AI's converter.
 *
 * <p>AI-ATLAS applies it to every {@code @Tool} method it registers that declares no result converter
 * of its own. A tool the application registers through its own {@code ToolCallbackProvider} can
 * opt in with {@code @Tool(resultConverter = AgentSafeToolCallResultConverter.class)}, which uses
 * the default (flat) JSON settings.
 */
public final class AgentSafeToolCallResultConverter implements ToolCallResultConverter {

    private static final ToolCallResultConverter DEFAULT = new DefaultToolCallResultConverter();

    private final ObjectMapper mapper;

    /** With the default {@code ai.atlas.json} settings: flat JSON. */
    public AgentSafeToolCallResultConverter() {
        this(new AgentSafeModule(false, true, true));
    }

    /** With the given module, such as the application's {@code AgentSafeModule} bean. */
    public AgentSafeToolCallResultConverter(AgentSafeModule module) {
        this.mapper = JsonParser.getObjectMapper().copy().registerModule(module);
    }

    @Override
    public String convert(Object result, Type returnType) {
        if (returnType == Void.TYPE || result == null || result instanceof String
                || result instanceof RenderedImage) {
            return DEFAULT.convert(result, returnType);
        }
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Conversion from Object to JSON failed", e);
        }
    }
}
