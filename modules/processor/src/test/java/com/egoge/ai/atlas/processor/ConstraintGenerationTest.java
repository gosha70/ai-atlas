/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code ai.atlas.constraints} on, the generated surfaces carry the effective constraints,
 * requiredness and declared hints (FR-012..FR-017); with it off, nothing new is generated. The
 * golden snapshot of the flag-off output is {@code IrRewireGoldenTest}.
 */
class ConstraintGenerationTest {

    private static final String FLAG_ON = "-Aai.atlas.constraints=true";
    private static final String OPENAPI = "META-INF/openapi/openapi-v1.json";
    private static final String MCP_TOOLS = McpToolsResourceGenerator.RESOURCE_PATH;
    private static final String MCP_TOOL_CLASS = "t.generated.OrdersMcpTool";
    private static final String REST_CONTROLLER = "t.generated.OrdersRestController";
    private static final String FIND_PATH = "/api/v1/orders/find";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JavaFileObject ITEM = JavaFileObjects.forSourceString("t.Item", """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            @AgenticEntity(description = "Item")
            public class Item {
                @AgenticField(description = "Quantity") @Positive private Integer qty;
                @AgenticField(description = "Name") @NotBlank @Size(max = 20) private String name;
                public Integer getQty() { return qty; }
                public String getName() { return name; }
            }
            """);

    private static final JavaFileObject ORDERS = JavaFileObjects.forSourceString("t.Orders", """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            import java.util.List;
            @AgenticExposed(description = "Orders", readOnly = Hint.TRUE)
            public class Orders {
                @AgenticExposed(description = "Find an item", returnType = Item.class)
                public Item find(@Positive int qty,
                                 @AgenticParam(required = Requiredness.OPTIONAL, description = "Item code")
                                 @Size(min = 2, max = 10) @Pattern(regexp = "[A-Z]+") String code,
                                 @NotBlank @Pattern(regexp = "[a-z ]+") String note,
                                 @Size(min = 1) List<String> tags) { return null; }
                @AgenticExposed(description = "Remove an item", readOnly = Hint.FALSE,
                                destructive = Hint.TRUE, idempotent = Hint.TRUE)
                public void remove(@NotNull @DecimalMax(value = "100", inclusive = false) Long id) { }
            }
            """);

    private static final JavaFileObject AUDIT = JavaFileObjects.forSourceString("t.Audit", """
            package t;
            import com.egoge.ai.atlas.annotations.*;
            public class Audit {
                @AgenticExposed(description = "Log a line")
                public String log(String line) { return line; }
            }
            """);

    // ------------------------------------------------------------ option

    @Test
    void invalidOptionValueIsAnError() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.constraints=yes").compile(ITEM, ORDERS);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("ai.atlas.constraints must be 'true' or 'false'. Got: yes");
    }

    @Test
    void optionIsCaseInsensitive() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions("-Aai.atlas.constraints=TRUE").compile(ITEM, ORDERS, AUDIT);

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedFile(StandardLocation.CLASS_OUTPUT, MCP_TOOLS)).isPresent();
    }

    @Test
    void flagOffGeneratesNoToolSpecificationsAndNoConstraintShapes() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ITEM, ORDERS, AUDIT);

        assertThat(compilation).succeeded();
        assertThat(compilation.generatedFile(StandardLocation.CLASS_OUTPUT, MCP_TOOLS)).isEmpty();
        assertThat(source(compilation, MCP_TOOL_CLASS))
                .doesNotContain("required =").doesNotContain("@Validated").doesNotContain("DecimalMin");
        assertThat(source(compilation, REST_CONTROLLER)).doesNotContain("required = false");
        assertThat(resource(compilation, OPENAPI)).doesNotContain("exclusiveMinimum").doesNotContain("\"required\" : false");
        assertThat(messages(compilation, Diagnostic.Kind.WARNING)).noneMatch(m -> m.contains("behavioural hint"));
    }

    // ------------------------------------------------------------ OpenAPI (FR-014)

    @Test
    void positiveIsBooleanExclusiveMinimumInOpenApi() throws IOException {
        JsonNode find = findParameters(compileOn());

        JsonNode qty = find.get(0).get("schema");
        assertThat(qty.get("minimum").decimalValue()).isEqualByComparingTo("0");
        assertThat(qty.get("exclusiveMinimum").asBoolean()).isTrue();
    }

    @Test
    void openApiParametersCarryRequirednessAndConstraints() throws IOException {
        JsonNode find = findParameters(compileOn());

        assertThat(find.get(0).get("required").asBoolean()).isTrue();
        JsonNode code = find.get(1);
        assertThat(code.get("name").asText()).isEqualTo("code");
        assertThat(code.get("required").asBoolean()).isFalse();
        assertThat(code.get("description").asText()).isEqualTo("Item code");
        assertThat(code.get("schema").get("minLength").asInt()).isEqualTo(2);
        assertThat(code.get("schema").get("maxLength").asInt()).isEqualTo(10);
        assertThat(code.get("schema").get("pattern").asText()).isEqualTo("^(?:[A-Z]+)$");

        JsonNode note = find.get(2).get("schema");
        assertThat(note.get("minLength").asInt()).isEqualTo(1);
        assertThat(note.has("pattern")).isFalse();
        assertThat(note.get("allOf")).hasSize(2);
        assertThat(note.get("allOf").get(0).get("pattern").asText()).isEqualTo("^(?:[a-z ]+)$");
        assertThat(note.get("allOf").get(1).get("pattern").asText()).isEqualTo("[^\\u0000-\\u0020]");

        assertThat(find.get(3).get("schema").get("minItems").asInt()).isEqualTo(1);
    }

    @Test
    void dtoComponentPropertiesCarryFieldConstraints() throws IOException {
        JsonNode properties = JSON.readTree(resource(compileOn(), OPENAPI))
                .get("components").get("schemas").get("ItemDto").get("properties");

        assertThat(properties.get("qty").get("minimum").decimalValue()).isEqualByComparingTo("0");
        assertThat(properties.get("qty").get("exclusiveMinimum").asBoolean()).isTrue();
        assertThat(properties.get("name").get("minLength").asInt()).isEqualTo(1);
        assertThat(properties.get("name").get("maxLength").asInt()).isEqualTo(20);
        assertThat(properties.get("name").get("pattern").asText()).isEqualTo("[^\\u0000-\\u0020]");
    }

    @Test
    void generatedOpenApiDocumentParsesWithoutMessages() {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(resource(compileOn(), OPENAPI), null, options);

        assertThat(result.getOpenAPI()).isNotNull();
        assertThat(result.getMessages()).isEmpty();
    }

    // ------------------------------------------------------------ REST (FR-015)

    @Test
    void restBindsOptionalParametersAsNotRequired() {
        String controller = source(compileOn(), REST_CONTROLLER);

        assertThat(controller).contains("@RequestParam(required = false) String code");
        assertThat(controller).contains("@RequestParam int qty");
        assertThat(controller).contains("@RequestParam String note");
    }

    // ------------------------------------------------------------ MCP tool class (FR-016)

    @Test
    void mcpToolClassCarriesTheEffectiveContractAndIsValidated() {
        String tool = source(compileOn(), MCP_TOOL_CLASS);

        assertThat(tool).contains("@Validated");
        assertThat(tool).contains("@ToolParam(description = \"qty\", required = true) "
                + "@DecimalMin(value = \"0\", inclusive = false) int qty");
        assertThat(tool).contains("@ToolParam(description = \"Item code\", required = false) "
                + "@Size(min = 2, max = 10) @Pattern(regexp = \"[A-Z]+\") String code");
        assertThat(tool).contains("@ToolParam(description = \"note\", required = true) @NotNull "
                + "@Pattern(regexp = \"[a-z ]+\") @NotBlank String note");
        assertThat(tool).contains("@ToolParam(description = \"tags\", required = true) @NotNull "
                + "@Size(min = 1) List<String> tags");
        assertThat(tool).contains("@ToolParam(description = \"id\", required = true) @NotNull "
                + "@DecimalMax(value = \"100\", inclusive = false) Long id");
    }

    @Test
    void mcpToolClassEmitsTheEffectiveContractNotTheSourceAnnotations() {
        JavaFileObject service = JavaFileObjects.forSourceString("t.Orders", """
                package t;
                import com.egoge.ai.atlas.annotations.*;
                import jakarta.validation.constraints.*;
                @AgenticExposed(description = "Orders", readOnly = Hint.TRUE)
                public class Orders {
                    public String find(@Min(10) @Positive @AgenticConstraints(maximum = "50") Integer qty,
                                       @Pattern(regexp = "[a-z]+", flags = Pattern.Flag.CASE_INSENSITIVE)
                                       String name) { return ""; }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(service);

        String tool = source(compilation, MCP_TOOL_CLASS);
        assertThat(tool).contains("@DecimalMin(value = \"10\", inclusive = true)");
        assertThat(tool).contains("@DecimalMax(value = \"50\", inclusive = true)");
        assertThat(tool).contains("@Pattern(regexp = \"[a-z]+\", flags = Pattern.Flag.CASE_INSENSITIVE)");
        assertThat(tool).doesNotContain("@Min").doesNotContain("@Positive");
    }

    @Test
    void withoutTheValidationApiTheToolClassIsAdvisoryWithOneNote() {
        JavaFileObject service = JavaFileObjects.forSourceString("t.Plain", """
                package t;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticExposed(description = "Plain", readOnly = Hint.TRUE)
                public class Plain {
                    public String find(@AgenticConstraints(minimum = "1") Integer qty,
                                       @AgenticParam(required = Requiredness.OPTIONAL) String code) { return ""; }
                    public String other(@AgenticConstraints(maxLength = 3) String code) { return ""; }
                }
                """);
        JavaFileObject second = JavaFileObjects.forSourceString("t.Second", """
                package t;
                import com.egoge.ai.atlas.annotations.*;
                @AgenticExposed(description = "Second", readOnly = Hint.TRUE)
                public class Second {
                    public String more(String code) { return ""; }
                }
                """);
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .withClasspath(classpathWithoutValidationApi()).compile(service, second);

        assertThat(compilation).succeeded();
        String tool = source(compilation, "t.generated.PlainMcpTool");
        assertThat(tool).contains("@ToolParam(description = \"qty\", required = true) Integer qty");
        assertThat(tool).contains("@ToolParam(description = \"code\", required = false) String code");
        assertThat(tool).doesNotContain("@Validated").doesNotContain("jakarta.validation");
        assertThat(messages(compilation, Diagnostic.Kind.NOTE).stream()
                .filter(m -> m.contains("MCP constraints are advisory"))).hasSize(1);
        assertThat(compilation.generatedFile(StandardLocation.CLASS_OUTPUT, MCP_TOOLS)).isPresent();
    }

    // ------------------------------------------------------------ mcp-tools.json (FR-017)

    @Test
    void toolSpecificationsMatchTheFixture() {
        assertThat(resource(compileOn(), MCP_TOOLS)).isEqualTo(EXPECTED_MCP_TOOLS);
    }

    @Test
    void positiveIsNumericExclusiveMinimumInTheToolSchema() throws IOException {
        JsonNode qty = tool(compileOn(), "find").get("inputSchema").get("properties").get("qty");

        assertThat(qty.get("exclusiveMinimum").decimalValue()).isEqualByComparingTo("0");
        assertThat(qty.has("minimum")).isFalse();
    }

    @Test
    void everyInputSchemaValidatesAgainstTheDraft202012Metaschema() throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));
        Schema metaschema = registry.getSchema(SchemaLocation.of(McpToolsResourceGenerator.JSON_SCHEMA_2020_12));

        JsonNode tools = JSON.readTree(resource(compileOn(), MCP_TOOLS)).get("tools");
        assertThat(tools).hasSize(3);
        for (JsonNode tool : tools) {
            List<Error> errors = metaschema.validate(tool.get("inputSchema"));
            assertThat(errors).as(tool.get("name").asText()).isEmpty();
        }
        // The metaschema is not vacuous: a boolean exclusiveMinimum, the OpenAPI 3.0 form, is rejected
        assertThat(metaschema.validate(JSON.readTree("{\"exclusiveMinimum\": true}"))).isNotEmpty();
    }

    @Test
    void toolSpecificationsAreDeterministic() {
        assertThat(resource(compileOn(), MCP_TOOLS)).isEqualTo(resource(compileOn(), MCP_TOOLS));
    }

    // ------------------------------------------------------------ hints (FR-013)

    @Test
    void hintsAppearOnlyWhenDeclaredAndMethodOverridesClass() throws IOException {
        Compilation compilation = compileOn();

        assertThat(tool(compilation, "find").get("annotations").toString()).isEqualTo("{\"readOnlyHint\":true}");
        assertThat(tool(compilation, "remove").get("annotations").toString())
                .isEqualTo("{\"readOnlyHint\":false,\"destructiveHint\":true,\"idempotentHint\":true}");
        assertThat(tool(compilation, "log").get("annotations").toString()).isEqualTo("{}");
    }

    @Test
    void aToolWithNoDeclaredHintWarns() {
        Compilation compilation = compileOn();

        assertThat(compilation).succeeded();
        List<String> warnings = messages(compilation, Diagnostic.Kind.WARNING).stream()
                .filter(m -> m.contains("declares no behavioural hint")).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("t.Audit#log").contains("MCP tool 'log'");
    }

    @Test
    void aToolWithNoDeclaredHintIsAnErrorUnderStrict() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(FLAG_ON, "-Aai.atlas.strict=true").compile(ITEM, ORDERS, AUDIT);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("t.Audit#log (MCP tool 'log') declares no behavioural hint");
    }

    // ------------------------------------------------------------ helpers

    private static final String EXPECTED_MCP_TOOLS = """
            {
              "tools": [
                {
                  "name": "find",
                  "inputSchema": {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {
                      "qty": {
                        "type": "integer",
                        "description": "qty",
                        "exclusiveMinimum": 0
                      },
                      "code": {
                        "type": "string",
                        "description": "Item code",
                        "minLength": 2,
                        "maxLength": 10,
                        "pattern": "^(?:[A-Z]+)$"
                      },
                      "note": {
                        "type": "string",
                        "description": "note",
                        "minLength": 1,
                        "allOf": [
                          {
                            "pattern": "^(?:[a-z ]+)$"
                          },
                          {
                            "pattern": "[^\\\\u0000-\\\\u0020]"
                          }
                        ]
                      },
                      "tags": {
                        "type": "array",
                        "items": {
                          "type": "string"
                        },
                        "description": "tags",
                        "minItems": 1
                      }
                    },
                    "required": [
                      "qty",
                      "note",
                      "tags"
                    ],
                    "additionalProperties": false
                  },
                  "annotations": {
                    "readOnlyHint": true
                  }
                },
                {
                  "name": "log",
                  "inputSchema": {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {
                      "line": {
                        "type": "string",
                        "description": "line"
                      }
                    },
                    "required": [
                      "line"
                    ],
                    "additionalProperties": false
                  },
                  "annotations": {}
                },
                {
                  "name": "remove",
                  "inputSchema": {
                    "$schema": "https://json-schema.org/draft/2020-12/schema",
                    "type": "object",
                    "properties": {
                      "id": {
                        "type": "integer",
                        "description": "id",
                        "exclusiveMaximum": 100
                      }
                    },
                    "required": [
                      "id"
                    ],
                    "additionalProperties": false
                  },
                  "annotations": {
                    "readOnlyHint": false,
                    "destructiveHint": true,
                    "idempotentHint": true
                  }
                }
              ]
            }
            """;

    private static Compilation compileOn() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).withOptions(FLAG_ON)
                .compile(ITEM, ORDERS, AUDIT);
        assertThat(compilation).succeeded();
        return compilation;
    }

    private static JsonNode findParameters(Compilation compilation) throws IOException {
        return JSON.readTree(resource(compilation, OPENAPI)).get("paths").get(FIND_PATH).get("post").get("parameters");
    }

    private static JsonNode tool(Compilation compilation, String name) throws IOException {
        for (JsonNode tool : JSON.readTree(resource(compilation, MCP_TOOLS)).get("tools")) {
            if (tool.get("name").asText().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("no tool " + name);
    }

    static String resource(Compilation compilation, String path) {
        Optional<JavaFileObject> file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, path);
        return read(file.orElseThrow(() -> new AssertionError("no " + path)));
    }

    static String source(Compilation compilation, String qualifiedName) {
        return read(compilation.generatedSourceFile(qualifiedName)
                .orElseThrow(() -> new AssertionError("no " + qualifiedName)));
    }

    private static String read(JavaFileObject file) {
        try {
            return file.getCharContent(true).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> messages(Compilation compilation, Diagnostic.Kind kind) {
        return compilation.diagnostics().stream().filter(d -> d.getKind() == kind)
                .map(d -> d.getMessage(Locale.ROOT)).toList();
    }

    /** The test classpath without the Bean Validation API and its provider. */
    private static List<File> classpathWithoutValidationApi() {
        List<File> classpath = new ArrayList<>();
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("jakarta.validation-api") && !entry.contains("hibernate-validator"))
                .map(File::new).forEach(classpath::add);
        return classpath;
    }
}
