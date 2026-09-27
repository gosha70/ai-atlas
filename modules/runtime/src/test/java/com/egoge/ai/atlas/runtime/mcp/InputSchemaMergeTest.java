/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-018: the merge keeps every keyword of the derived property. A generated keyword the derived
 * property already has never overwrites it; both apply.
 */
class InputSchemaMergeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void generatedAllOfIsAppendedToTheDerivedAllOf() throws IOException {
        String derived = """
                {"type":"object","properties":{
                   "s":{"type":"string","allOf":[{"format":"email"}]}}}""";
        JsonNode generated = JSON.readTree("""
                {"type":"object","properties":{
                   "s":{"type":"string","allOf":[{"pattern":"^(?:[a-z@.]+)$"},{"pattern":"^(?:.*@.*)$"}]}}}""");

        ObjectNode merged = InputSchemaMerge.mergeInputSchema("t", derived, generated);

        assertThat(merged.at("/properties/s")).isEqualTo(JSON.readTree("""
                {"type":"string","allOf":[{"format":"email"},
                   {"pattern":"^(?:[a-z@.]+)$"},{"pattern":"^(?:.*@.*)$"}]}"""));
        assertThat(McpToolSpecificationTest.metaschema().validate(merged)).isEmpty();
    }

    @Test
    void scalarKeywordTheDerivedPropertyHasKeepsTheDerivedValueAndAddsTheGeneratedUnderAllOf() throws IOException {
        String derived = """
                {"type":"object","properties":{
                   "code":{"type":"string","pattern":"^[A-Z]+$","maxLength":10},
                   "limit":{"type":"integer","minimum":0}}}""";
        JsonNode generated = JSON.readTree("""
                {"type":"object","properties":{
                   "code":{"type":"string","pattern":"^(?:[A-Z]{2,})$","maxLength":10},
                   "limit":{"type":"integer","minimum":1.0}}}""");

        ObjectNode merged = InputSchemaMerge.mergeInputSchema("t", derived, generated);

        // An equal value is kept once; a different one keeps the derived value and adds the generated
        assertThat(merged.at("/properties/code")).isEqualTo(JSON.readTree("""
                {"type":"string","pattern":"^[A-Z]+$","maxLength":10,
                 "allOf":[{"pattern":"^(?:[A-Z]{2,})$"}]}"""));
        assertThat(merged.at("/properties/limit")).isEqualTo(JSON.readTree("""
                {"type":"integer","minimum":0,"allOf":[{"minimum":1.0}]}"""));
        Schema schema = McpToolSpecificationTest.SCHEMAS.getSchema(merged);
        assertThat(schema.validate(JSON.readTree("{\"code\":\"AB\",\"limit\":1}"))).isEmpty();
        assertThat(schema.validate(JSON.readTree("{\"code\":\"A\"}"))).as("the generated pattern applies").isNotEmpty();
        assertThat(schema.validate(JSON.readTree("{\"limit\":0}"))).as("the generated minimum applies").isNotEmpty();
    }
}
