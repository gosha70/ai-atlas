/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.atlas.runtime.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSafeSerializerTest {

    private ObjectMapper createMapper(boolean enriched, boolean includeDescriptions, boolean includeValidValues) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new AgentSafeModule(enriched, includeDescriptions, includeValidValues));
        return mapper;
    }

    private TestEntity sampleEntity() {
        TestEntity entity = new TestEntity();
        entity.setId(42L);
        entity.setName("Widget A");
        entity.setStatus(TestEntity.Status.ACTIVE);
        entity.setDisplayName("My Widget");
        entity.setSecret("s3cret");
        entity.setHidden("should not appear");
        return entity;
    }

    // --- Flat mode tests ---

    @Test
    void flatMode_includesOnlyAnnotatedFields() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(false, false, false);
        String json = mapper.writeValueAsString(sampleEntity());
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("id")).isTrue();
        assertThat(node.has("name")).isTrue();
        assertThat(node.has("status")).isTrue();
        assertThat(node.has("hidden")).as("Unannotated field should be excluded").isFalse();
    }

    @Test
    void flatMode_excludesTypeInfo() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(false, false, false);
        String json = mapper.writeValueAsString(sampleEntity());
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("typeInfo")).isFalse();
    }

    @Test
    void flatMode_usesCustomFieldName() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(false, false, false);
        String json = mapper.writeValueAsString(sampleEntity());
        JsonNode node = mapper.readTree(json);

        // @AgentVisible(name = "alias") on displayName field
        assertThat(node.has("alias")).isTrue();
        assertThat(node.get("alias").asText()).isEqualTo("My Widget");
        assertThat(node.has("displayName")).as("Should use custom name, not field name").isFalse();
    }

    @Test
    void flatMode_writesCorrectValues() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(false, false, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        assertThat(node.get("id").asLong()).isEqualTo(42L);
        assertThat(node.get("name").asText()).isEqualTo("Widget A");
        assertThat(node.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void flatMode_nullFieldWritesNull() throws JsonProcessingException {
        TestEntity entity = new TestEntity();
        entity.setId(1L);
        // name, status, displayName, secret are null

        ObjectMapper mapper = createMapper(false, false, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(entity));

        assertThat(node.get("id").asLong()).isEqualTo(1L);
        assertThat(node.get("name").isNull()).isTrue();
    }

    @Test
    void flatMode_nullEntityWritesNull() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(false, false, false);
        // Serialize null directly through the serializer
        String json = mapper.writeValueAsString(null);
        assertThat(json).isEqualTo("null");
    }

    @Test
    void flatMode_deterministicFieldOrder() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(false, false, false);
        // Serialize multiple times — order must be stable
        String json1 = mapper.writeValueAsString(sampleEntity());
        String json2 = mapper.writeValueAsString(sampleEntity());
        assertThat(json1).isEqualTo(json2);
    }

    // --- Enriched mode tests ---

    @Test
    void enrichedMode_includesTypeInfo() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, true, true);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        assertThat(node.has("typeInfo")).isTrue();
        assertThat(node.get("typeInfo").get("name").asText()).isEqualTo("widget");
        assertThat(node.get("typeInfo").get("description").asText()).isEqualTo("A test widget");
    }

    @Test
    void enrichedMode_wrapsValuesInObject() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, true, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        // Each field should be an object with "value" key
        assertThat(node.get("id").has("value")).isTrue();
        assertThat(node.get("id").get("value").asLong()).isEqualTo(42L);
    }

    @Test
    void enrichedMode_includesDescriptions() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, true, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        assertThat(node.get("id").get("description").asText()).isEqualTo("Widget ID");
        assertThat(node.get("name").get("description").asText()).isEqualTo("Widget name");
    }

    @Test
    void enrichedMode_excludesDescriptionsWhenDisabled() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, false, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        assertThat(node.get("id").has("description")).isFalse();
    }

    @Test
    void enrichedMode_includesEnumValidValues() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, true, true);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        JsonNode statusNode = node.get("status");
        assertThat(statusNode.has("validValues")).isTrue();
        JsonNode validValues = statusNode.get("validValues");
        assertThat(validValues.isArray()).isTrue();
        assertThat(validValues.size()).isEqualTo(2);
        assertThat(validValues.get(0).asText()).isEqualTo("ACTIVE");
        assertThat(validValues.get(1).asText()).isEqualTo("INACTIVE");
    }

    @Test
    void enrichedMode_excludesValidValuesWhenDisabled() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, true, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        assertThat(node.get("status").has("validValues")).isFalse();
    }

    @Test
    void enrichedMode_noValidValuesForNonEnumFields() throws JsonProcessingException {
        ObjectMapper mapper = createMapper(true, true, true);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(sampleEntity()));

        // id is Long — should not have validValues
        assertThat(node.get("id").has("validValues")).isFalse();
    }

    // --- Circular reference tests ---

    @Test
    void detectsCircularReference() throws JsonProcessingException {
        TestParent parent = new TestParent();
        parent.setId(1L);
        TestChild child = new TestChild();
        child.setId(2L);
        parent.setChild(child);
        child.setParent(parent); // circular!

        ObjectMapper mapper = createMapper(false, false, false);
        String json = mapper.writeValueAsString(parent);
        JsonNode node = mapper.readTree(json);

        // Parent should serialize fine
        assertThat(node.get("id").asLong()).isEqualTo(1L);
        // Child should serialize
        assertThat(node.get("child").get("id").asLong()).isEqualTo(2L);
        // Child's parent back-reference should be skipped (circular reference detected)
        assertThat(node.get("child").has("parent")).isFalse();
    }

    @Test
    void noCircularReferenceWhenNoCycle() throws JsonProcessingException {
        TestParent parent = new TestParent();
        parent.setId(1L);
        TestChild child = new TestChild();
        child.setId(2L);
        parent.setChild(child);
        // No back-reference — no cycle

        ObjectMapper mapper = createMapper(false, false, false);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(parent));

        assertThat(node.get("id").asLong()).isEqualTo(1L);
        assertThat(node.get("child").get("id").asLong()).isEqualTo(2L);
        // parent field is null (not set), so it should be written as null
        assertThat(node.get("child").get("parent").isNull()).isTrue();
    }
}
