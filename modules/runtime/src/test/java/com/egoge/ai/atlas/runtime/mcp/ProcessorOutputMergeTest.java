/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.mcp;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-018 against the real processor: a small service compiled with {@code ai.atlas.constraints} on
 * yields a generated MCP tool class and {@code mcp-tools.json}; merging each listed schema into the
 * schema Spring AI derives from the generated tool method keeps every listed constraint, with no
 * left-out keyword or missing property. This catches drift between the hand-written fixtures and
 * what the processor actually writes.
 */
@ExtendWith(OutputCaptureExtension.class)
class ProcessorOutputMergeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_CLASS = "t.generated.OrdersMcpTool";

    private static final String ITEM = """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            @AgenticEntity(description = "Item")
            public class Item {
                @AgenticField(description = "Name") private String name;
                public String getName() { return name; }
            }
            """;

    private static final String ORDERS = """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            import java.util.List;
            @AgenticExposed(description = "Orders", readOnly = Hint.TRUE)
            public class Orders {
                @AgenticExposed(description = "Find an item", returnType = Item.class)
                public Item find(@Positive int qty,
                                 @Size(min = 2, max = 10) @Pattern(regexp = "[A-Z]+") String code,
                                 @Size(min = 1) List<String> tags,
                                 @NotNull @DecimalMax(value = "100", inclusive = false) Long id) { return null; }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void generatedToolSpecificationsMergeIntoTheDerivedSchemasWithoutLoss(CapturedOutput output) throws Exception {
        Path classes = compile(Map.of("t/Item.java", ITEM, "t/Orders.java", ORDERS));
        JsonNode listed = JSON.readTree(classes.resolve(AgenticMcpConfiguration.TOOL_SPECIFICATIONS).toFile());
        Map<String, String> derived = derivedSchemas(classes);
        assertThat(listed.path("tools")).isNotEmpty();

        for (JsonNode tool : listed.path("tools")) {
            String name = tool.path("name").asText();
            assertThat(derived).as("a @Tool method for " + name).containsKey(name);
            ObjectNode merged = InputSchemaMerge.mergeInputSchema(name, derived.get(name), tool.path("inputSchema"));
            assertThat(McpToolSpecificationTest.metaschema().validate(merged)).as(name).isEmpty();

            JsonNode properties = merged.path("properties");
            assertThat(properties.at("/qty/type").asText()).isEqualTo("integer");
            assertThat(properties.at("/code/maxLength").asInt()).isEqualTo(10);
            assertThat(properties.at("/code/minLength").asInt()).isEqualTo(2);
            assertThat(properties.at("/tags/type").asText()).isEqualTo("array");
            assertThat(properties.at("/tags/minItems").asInt()).isEqualTo(1);

            Schema schema = McpToolSpecificationTest.SCHEMAS.getSchema(merged);
            assertThat(schema.validate(JSON.readTree("{\"qty\":1,\"code\":\"AB\",\"tags\":[\"x\"],\"id\":99}")))
                    .isEmpty();
            assertThat(schema.validate(JSON.readTree("{\"qty\":0,\"code\":\"AB\",\"tags\":[\"x\"],\"id\":1}")))
                    .as("qty is positive").isNotEmpty();
            assertThat(schema.validate(JSON.readTree("{\"qty\":1,\"code\":\"ab\",\"tags\":[\"x\"],\"id\":1}")))
                    .as("code pattern").isNotEmpty();
            assertThat(schema.validate(JSON.readTree("{\"qty\":1,\"code\":\"AB\",\"tags\":[\"x\"],\"id\":100}")))
                    .as("id is below 100").isNotEmpty();
            assertThat(schema.validate(JSON.readTree("{\"qty\":1,\"code\":\"AB\",\"tags\":[\"x\"]}")))
                    .as("id is required").isNotEmpty();
        }
        assertThat(output).doesNotContain("does not apply to its").doesNotContain("is not in the derived input schema");
    }

    /** Compiles the sources with the processor and the flag on; returns the class output directory. */
    private Path compile(Map<String, String> sources) throws IOException {
        Path src = dir.resolve("src");
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Path generated = Files.createDirectories(dir.resolve("generated"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<Path> paths = sources.keySet().stream().map(src::resolve).toList();
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics,
                    List.of("-Aai.atlas.constraints=true", "-parameters", "-classpath", System.getProperty("java.class.path"),
                            "-d", classes.toString(), "-s", generated.toString()),
                    null, files.getJavaFileObjectsFromPaths(paths));
            task.setProcessors(List.of(new AgenticProcessor()));
            assertThat(task.call()).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
        return classes;
    }

    /** The input schema Spring AI derives for each {@code @Tool} method of the generated tool class, by name. */
    private static Map<String, String> derivedSchemas(Path classes) throws Exception {
        Map<String, String> schemas = new LinkedHashMap<>();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                ProcessorOutputMergeTest.class.getClassLoader())) {
            for (Method method : loader.loadClass(TOOL_CLASS).getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    ToolDefinition definition = ToolDefinitions.from(method);
                    schemas.put(definition.name(), definition.inputSchema());
                }
            }
        }
        return schemas;
    }
}
