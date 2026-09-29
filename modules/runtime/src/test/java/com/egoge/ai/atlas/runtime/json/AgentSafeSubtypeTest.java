/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.json;

import com.egoge.ai.atlas.annotations.AgenticEntity;
import com.egoge.ai.atlas.annotations.AgenticField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #50: an unannotated subtype of an {@code @AgenticEntity} is serialized through the nearest
 * entity's {@code @AgenticField} getters only, wherever it appears.
 */
class AgentSafeSubtypeTest {

    private static ObjectMapper mapper(boolean enriched) {
        return new ObjectMapper().registerModule(new AgentSafeModule(enriched, true, true));
    }

    static TestVip vip() {
        TestVip vip = new TestVip();
        vip.setId(7L);
        vip.setName("Ada");
        vip.setStatus(TestEntity.Status.ACTIVE);
        vip.setHidden("hidden");
        vip.setTier("gold");
        vip.setSsn("123-45-6789");
        return vip;
    }

    /** A record in the shape of a generated DTO whose component is an unannotated entity subtype. */
    record EnvelopeDto(String label, TestVip person) {
    }

    @Test
    void subtypeKeepsOnlyTheEntityWhitelist() throws Exception {
        JsonNode node = mapper(false).valueToTree(vip());

        assertThat(node.path("id").asLong()).isEqualTo(7L);
        assertThat(node.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(node.path("name").asText()).as("dispatched to the override").isEqualTo("VIP Ada");
        assertThat(node.has("ssn")).isFalse();
        assertThat(node.has("tier")).as("the subtype is not an entity").isFalse();
        assertThat(node.has("hidden")).isFalse();
    }

    @Test
    void subtypeSerializesLikeItsEntity() throws Exception {
        TestEntity plain = new TestEntity();
        plain.setId(7L);
        plain.setName("VIP Ada");
        plain.setStatus(TestEntity.Status.ACTIVE);
        for (boolean enriched : new boolean[] {false, true}) {
            ObjectMapper mapper = mapper(enriched);
            assertThat(mapper.writeValueAsString(vip())).isEqualTo(mapper.writeValueAsString(plain));
        }
    }

    @Test
    void enrichedSubtypeNamesTheEntityInItsTypeInfo() {
        JsonNode node = mapper(true).valueToTree(vip());

        assertThat(node.at("/typeInfo/name").asText()).isEqualTo("widget");
        assertThat(node.has("ssn")).isFalse();
        assertThat(node.has("tier")).isFalse();
    }

    @Test
    void subtypeInsideContainersAndRecordsIsWhitelisted() {
        ObjectMapper mapper = mapper(false);
        List<JsonNode> nodes = List.of(
                mapper.valueToTree(List.of(vip())).get(0),
                mapper.valueToTree(Map.of("k", vip())).get("k"),
                mapper.valueToTree(new TestVip[] {vip()}).get(0),
                mapper.valueToTree(new EnvelopeDto("x", vip())).get("person"));
        for (JsonNode node : nodes) {
            assertThat(node.path("id").asLong()).as(node.toString()).isEqualTo(7L);
            assertThat(node.has("ssn")).as(node.toString()).isFalse();
            assertThat(node.has("tier")).as(node.toString()).isFalse();
        }
    }

    @Test
    void subtypeAsAnEntityFieldValueIsWhitelisted() {
        Holder holder = new Holder();
        holder.owner = vip();
        JsonNode node = mapper(false).valueToTree(holder);

        assertThat(node.at("/owner/id").asLong()).isEqualTo(7L);
        assertThat(node.path("owner").has("ssn")).isFalse();
    }

    @Test
    void implementationOfAnEntityInterfaceIsWhitelisted() {
        JsonNode node = mapper(false).valueToTree(new Implementation());

        assertThat(node.has("ssn")).isFalse();
    }

    @AgenticEntity
    public static class Holder {
        @AgenticField
        private TestEntity owner;

        public TestEntity getOwner() { return owner; }
    }

    @AgenticEntity
    public interface Contract {
    }

    public static class Implementation implements Contract {
        public String getSsn() { return "123-45-6789"; }
    }
}
