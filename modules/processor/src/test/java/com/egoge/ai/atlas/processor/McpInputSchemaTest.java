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

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/** The JSON shape of a parameter in a generated MCP tool's {@code inputSchema}. */
class McpInputSchemaTest {

    private static final String FLAG_ON = "-Aai.atlas.constraints=true";

    @Test
    void aRawCollectionIsAnArrayWithUnconstrainedItems() throws IOException {
        JavaFileObject service = JavaFileObjects.forSourceString("t.Raw", """
                package t;
                import com.egoge.ai.atlas.annotations.*;
                import jakarta.validation.constraints.*;
                @AgenticExposed(description = "Raw", readOnly = Hint.TRUE)
                public class Raw {
                    public void accept(@Size(min = 1) java.util.List values) { }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(service);

        assertThat(compilation).succeeded();
        JsonNode tools = new ObjectMapper().readTree(
                ConstraintGenerationTest.resource(compilation, McpToolsResourceGenerator.RESOURCE_PATH));
        JsonNode values = tools.path("tools").path(0).path("inputSchema").path("properties").path("values");
        assertThat(values.path("type").asText()).isEqualTo("array");
        assertThat(values.path("minItems").asInt()).isEqualTo(1);
        assertThat(values.has("items")).isFalse();
    }
}
