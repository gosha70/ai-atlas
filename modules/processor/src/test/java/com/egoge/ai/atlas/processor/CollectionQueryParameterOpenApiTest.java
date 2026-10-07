/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.egoge.ai.atlas.processor.RestTestSupport.ORDER;
import static com.egoge.ai.atlas.processor.RestTestSupport.compile;
import static com.egoge.ai.atlas.processor.RestTestSupport.openApi;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A collection or array query parameter is documented as an array of its typed elements, as the
 * generated controller binds it with {@code @RequestParam}, whether or not
 * {@code ai.atlas.constraints} is on (#68); the constrained case is unchanged.
 */
class CollectionQueryParameterOpenApiTest {

    private static final String SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import java.util.*;
            @AgenticExposed(description = "Tags")
            public class TagService {
                @SuppressWarnings("rawtypes")
                public String find(List<String> tags, Set<Long> ids, String[] names, List raw, Long limit) { return null; }
            }
            """;

    @Test
    void anUnconstrainedCollectionIsAnArrayOfItsElementWithEveryOptionOff() throws Exception {
        JsonNode parameters = parameters(compile(List.of(), ORDER, SERVICE));

        assertThat(schema(parameters, "tags")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        assertThat(schema(parameters, "ids"))
                .isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"format\":\"int64\"}}");
        assertThat(schema(parameters, "names")).isEqualTo("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        assertThat(schema(parameters, "raw")).isEqualTo("{\"type\":\"array\",\"items\":{}}");
        // A scalar keeps its mapping
        assertThat(schema(parameters, "limit")).isEqualTo("{\"type\":\"integer\",\"format\":\"int64\"}");
        for (JsonNode parameter : parameters) {
            // Spring binds a repeated name, query's default form style with explode, so none is written
            assertThat(parameter.has("style")).isFalse();
            assertThat(parameter.has("explode")).isFalse();
        }
    }

    @Test
    void aConstrainedCollectionIsUnchanged() throws Exception {
        String service = SERVICE.replace("List<String> tags", "@jakarta.validation.constraints.Size(min = 1) List<String> tags");
        JsonNode parameters = parameters(compile(List.of("-Aai.atlas.constraints=true"), ORDER, service));

        assertThat(schema(parameters, "tags"))
                .isEqualTo("{\"minItems\":1,\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
    }

    private static JsonNode parameters(Compilation compilation) throws Exception {
        RestTestSupport.assertValidOpenApi(compilation);
        return openApi(compilation).path("paths").path("/api/v1/tag-service/find").path("post").path("parameters");
    }

    private static String schema(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) {
                return parameter.path("schema").toString();
            }
        }
        throw new AssertionError("no parameter " + name + " in " + parameters);
    }
}
