/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.util.List;

import static com.egoge.ai.atlas.processor.ConstraintGenerationTest.resource;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code ai.atlas.constraints} on, a constrained parameter's schema has the JSON type its
 * constraint keywords apply to (FR-014, FR-017): integral types are {@code integer}, decimals
 * {@code number}, and every array and {@code java.util.Collection} an {@code array}. Part of
 * {@link ConstraintGenerationTest}'s coverage (task 13).
 */
class ConstraintJsonTypeTest {

    private static final String FLAG_ON = "-Aai.atlas.constraints=true";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JavaFileObject KINDS = JavaFileObjects.forSourceString("t.Kinds", """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            import java.math.BigDecimal;
            import java.util.Deque;
            import java.util.List;
            import java.util.Vector;
            @AgenticExposed(description = "Kinds", readOnly = Hint.TRUE)
            public class Kinds {
                @AgenticExposed(description = "Numbers and lists")
                public String bounds(@Positive BigDecimal price, @Max(10) short level,
                                     @Size(min = 1) List<String> tags) { return null; }
                @AgenticExposed(description = "Other collections")
                public String bags(@Size(min = 1) Vector<String> vector, @Size(min = 1) Deque<Integer> deque,
                                   @Size(min = 1) Tags custom) { return null; }
            }
            """);
    private static final JavaFileObject TAGS = JavaFileObjects.forSourceString("t.Tags", """
            package t;
            public class Tags extends java.util.ArrayList<String> { }
            """);

    @Test
    void everyCollectionTypeIsAnArrayInTheToolSchema() throws IOException {
        JsonNode properties = tool(compile(), "bags").get("inputSchema").get("properties");

        for (String name : List.of("vector", "deque", "custom")) {
            assertThat(properties.get(name).get("type").asText()).as(name).isEqualTo("array");
            assertThat(properties.get(name).get("minItems").asInt()).as(name).isEqualTo(1);
        }
        assertThat(properties.get("vector").get("items").get("type").asText()).isEqualTo("string");
        assertThat(properties.get("deque").get("items").get("type").asText()).isEqualTo("integer");
    }

    private static Compilation compile() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(KINDS, TAGS);
        assertThat(compilation).succeeded();
        return compilation;
    }

    private static JsonNode tool(Compilation compilation, String name) throws IOException {
        for (JsonNode tool : JSON.readTree(resource(compilation, McpToolsResourceGenerator.RESOURCE_PATH))
                .get("tools")) {
            if (tool.get("name").asText().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("no tool " + name);
    }
}
