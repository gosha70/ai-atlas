/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that AgentSafeModule correctly registers the serializer
 * for @AgentVisibleClass types and leaves other types alone.
 */
class AgentSafeModuleTest {

    @Test
    void moduleName() {
        AgentSafeModule module = new AgentSafeModule(false, true, true);
        assertThat(module.getModuleName()).isEqualTo("ai-atlas-agent-safe");
    }

    @Test
    void moduleVersion() {
        AgentSafeModule module = new AgentSafeModule(false, true, true);
        assertThat(module.version()).isNotNull();
    }

    @Test
    void serializesAnnotatedTypesWithCustomSerializer() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new AgentSafeModule(false, false, false));

        TestEntity entity = new TestEntity();
        entity.setId(1L);
        entity.setName("Test");
        entity.setHidden("should be hidden");

        JsonNode node = mapper.readTree(mapper.writeValueAsString(entity));

        // Custom serializer should exclude unannotated fields
        assertThat(node.has("id")).isTrue();
        assertThat(node.has("name")).isTrue();
        assertThat(node.has("hidden")).isFalse();
    }

    @Test
    void doesNotAffectNonAnnotatedTypes() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new AgentSafeModule(false, false, false));

        // A plain POJO without @AgentVisibleClass should serialize normally
        record PlainPojo(String value, int count) {}
        PlainPojo pojo = new PlainPojo("hello", 5);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(pojo));

        assertThat(node.get("value").asText()).isEqualTo("hello");
        assertThat(node.get("count").asInt()).isEqualTo(5);
    }
}
