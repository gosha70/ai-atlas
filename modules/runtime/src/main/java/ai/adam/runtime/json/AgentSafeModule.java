/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.runtime.json;

import ai.adam.annotations.AgentVisibleClass;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

/**
 * Jackson module that registers the {@link AgentSafeSerializer} for all
 * classes annotated with {@code @AgentVisibleClass}. This module provides
 * Hibernate-safe, PII-safe JSON serialization with optional enriched
 * output for LLM/MCP consumption.
 *
 * <p>Registered automatically via Spring Boot auto-configuration when
 * Jackson is on the classpath.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code ai.adam.json.enriched} — enriched JSON with descriptions/validValues (default: false)</li>
 *   <li>{@code ai.adam.json.include-descriptions} — include field descriptions (default: true)</li>
 *   <li>{@code ai.adam.json.include-valid-values} — include enum valid values (default: true)</li>
 * </ul>
 */
public class AgentSafeModule extends Module {

    private final boolean enriched;
    private final boolean includeDescriptions;
    private final boolean includeValidValues;

    public AgentSafeModule(boolean enriched, boolean includeDescriptions, boolean includeValidValues) {
        this.enriched = enriched;
        this.includeDescriptions = includeDescriptions;
        this.includeValidValues = includeValidValues;
    }

    @Override
    public String getModuleName() {
        return "ai-adam-agent-safe";
    }

    @Override
    public Version version() {
        return new Version(0, 1, 0, "SNAPSHOT", "ai.adam", "runtime");
    }

    @Override
    public void setupModule(SetupContext context) {
        AgentSafeSerializer serializer = new AgentSafeSerializer(enriched, includeDescriptions, includeValidValues);

        context.addBeanSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                                       BeanDescription beanDesc,
                                                       JsonSerializer<?> defaultSerializer) {
                if (beanDesc.getBeanClass().isAnnotationPresent(AgentVisibleClass.class)) {
                    return serializer;
                }
                return defaultSerializer;
            }
        });
    }
}
