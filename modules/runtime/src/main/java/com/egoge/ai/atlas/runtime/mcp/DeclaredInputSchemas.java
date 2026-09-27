/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Serves each merged input schema over the MCP transports exactly as merged, {@code $schema}
 * included (FR-018). The SDK's {@link McpSchema.JsonSchema} record has no {@code $schema}
 * component, so the declaration would be dropped from {@code tools/list}.
 *
 * <p>Each merged schema is registered by identity; the MCP server's {@value #MCP_SERVER_OBJECT_MAPPER}
 * bean, which the SSE and Streamable HTTP transports serialize with, then writes a registered
 * instance as its merged JSON. Every other schema, such as a tool's derived one, is serialized as
 * before.
 */
final class DeclaredInputSchemas implements BeanPostProcessor {

    /** The bean Spring AI's MCP server transports serialize their messages with. */
    static final String MCP_SERVER_OBJECT_MAPPER = "mcpServerObjectMapper";

    private final Map<McpSchema.JsonSchema, JsonNode> declared =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * @param schema the SDK schema a tool is built with
     * @param json   the merged schema to serve in its place
     * @return {@code schema}
     */
    McpSchema.JsonSchema register(McpSchema.JsonSchema schema, JsonNode json) {
        declared.put(schema, json);
        return schema;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (MCP_SERVER_OBJECT_MAPPER.equals(beanName) && bean instanceof ObjectMapper mapper) {
            SimpleModule module = new SimpleModule(DeclaredInputSchemas.class.getName());
            module.setSerializerModifier(new BeanSerializerModifier() {
                @Override
                @SuppressWarnings("unchecked")
                public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription description,
                                                          JsonSerializer<?> serializer) {
                    return description.getBeanClass() == McpSchema.JsonSchema.class
                            ? new Declared((JsonSerializer<Object>) serializer) : serializer;
                }
            });
            mapper.registerModule(module);
        }
        return bean;
    }

    /** Writes a registered schema as its merged JSON, and any other through the default serializer. */
    private final class Declared extends StdSerializer<McpSchema.JsonSchema>
            implements ContextualSerializer, ResolvableSerializer {

        private final JsonSerializer<Object> fallback;

        Declared(JsonSerializer<Object> fallback) {
            super(McpSchema.JsonSchema.class);
            this.fallback = fallback;
        }

        @Override
        public void serialize(McpSchema.JsonSchema value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            JsonNode json = declared.get(value);
            if (json != null) {
                generator.writeTree(json);
            } else {
                fallback.serialize(value, generator, provider);
            }
        }

        @Override
        public void resolve(SerializerProvider provider) throws JsonMappingException {
            if (fallback instanceof ResolvableSerializer resolvable) {
                resolvable.resolve(provider);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property)
                throws JsonMappingException {
            return fallback instanceof ContextualSerializer contextual
                    ? new Declared((JsonSerializer<Object>) contextual.createContextual(provider, property)) : this;
        }
    }
}
