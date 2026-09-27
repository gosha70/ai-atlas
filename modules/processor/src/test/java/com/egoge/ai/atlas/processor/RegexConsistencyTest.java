/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor;

import com.egoge.ai.atlas.processor.generator.McpToolsResourceGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.regex.RegularExpression;
import com.networknt.schema.regex.RegularExpressionFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-017a: a value is accepted or rejected alike by the three places that check it — Bean
 * Validation (Hibernate Validator) on the generated MCP tool class, the {@code mcp-tools.json}
 * input schema (JSON Schema 2020-12) and the OpenAPI parameter schema (OpenAPI 3.0).
 *
 * <p>Schema-side patterns run on GraalJS, an ECMAScript engine, both without and with the
 * {@code u} flag; a Java-backed engine would hide exactly the differences this test exists to
 * catch. For patterns that are not publishable the result is deliberately one-sided: Bean
 * Validation rejects, both schemas accept, and the processor warns.
 */
class RegexConsistencyTest {

    private static final String SERVICE = "t.Rx";
    private static final String TOOL_CLASS = "t.generated.RxMcpTool";
    private static final String OPENAPI = "META-INF/openapi/openapi-v1.json";
    private static final String REST_PATH = "/api/v1/rx/";
    private static final String PARAMETER = "v";
    private static final String CLASS_OUTPUT = "CLASS_OUTPUT/";
    private static final String CLASS_SUFFIX = ".class";
    private static final List<String> ECMASCRIPT_FLAGS = List.of("", "u");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String GRIN = new String(Character.toChars(0x1F600));
    private static final String HIGH = "\uD83D";
    private static final String LOW = "\uDE00";
    private static final String SIZE_MAX_1 = "@Size(max = 1)";

    /** One constrained parameter: its annotations, and whether its constraint is published. */
    private record Case(String annotations, boolean published, List<Input> inputs) {
    }

    /** A value and Java's (Bean Validation's) result for it. */
    private record Input(String value, boolean accepted) {
    }

    private static Input accepts(String value) {
        return new Input(value, true);
    }

    private static Input rejects(String value) {
        return new Input(value, false);
    }

    private static Case pattern(String regex, Input... inputs) {
        return new Case("@Pattern(regexp = \"" + javaLiteral(regex) + "\")", true, List.of(inputs));
    }

    private static Case unpublished(String annotations, Input... inputs) {
        return new Case(annotations, false, List.of(inputs));
    }

    private static final List<Case> CASES = List.of(
            pattern("[A-Z]+", accepts("ABC"), rejects("xABCy")),
            pattern("\\s+", accepts(" \t"), rejects(" "), rejects(" "), rejects("\u0085"),
                    rejects("﻿")),
            pattern("\\S+", accepts(" "), accepts(" ")),
            pattern(".", accepts("a"), accepts(" "), accepts(GRIN), rejects("\u0085"), rejects(" "),
                    rejects("\n"), accepts(HIGH), accepts(LOW), rejects(LOW + HIGH)),
            pattern("[^a]", accepts(GRIN), accepts(HIGH), accepts(LOW)),
            pattern("[^a]{2}", rejects(GRIN), accepts(GRIN + GRIN), accepts("xy")),
            pattern(".{2}", rejects(GRIN), accepts(GRIN + GRIN), accepts("xy"), accepts(LOW + HIGH)),
            pattern("\\S{2}", rejects(GRIN), accepts(GRIN + GRIN), accepts("xy")),
            pattern("[^a][^b]", rejects(GRIN), accepts("x" + GRIN)),
            pattern(".\\S", rejects(GRIN), accepts("x" + GRIN)),
            pattern(".+x", accepts(GRIN + "x"), rejects("x")),
            pattern("[^a]*b", accepts(GRIN + GRIN + "b")),
            pattern("\\d+", accepts("42"), rejects("٣")),
            pattern("\\w+", accepts("a_1"), rejects("é")),
            pattern("[\\d_]+", accepts("4_2"), rejects("٣")),
            pattern("[^\\s,]+", accepts(GRIN), accepts(" "), rejects("a,b")),
            new Case("@NotBlank", true, List.of(accepts("a\nb"), accepts(" a "), accepts(" "), accepts(" "),
                    rejects(""), rejects(" "), rejects("\n\t"), rejects("\u0000"))),
            new Case("@NotBlank @Pattern(regexp = \"[a-z ]+\")", true, List.of(rejects("   "), accepts(" ab "))),
            // Lengths only on BMP inputs, where UTF-16 units and code points agree
            new Case("@Size(min = 2, max = 3)", true, List.of(rejects("a"), accepts("ab"), accepts("é\u2028x"),
                    rejects("abcd"), rejects("\u00A0"))),
            new Case(SIZE_MAX_1, true, List.of(accepts("a"), accepts("é"), accepts("\u2028"), rejects("ab"))),
            unpublished("@Pattern(regexp = \"[a-z]+\", flags = Pattern.Flag.CASE_INSENSITIVE)", rejects("123")),
            unpublished("@Pattern(regexp = \"[a-z]++\")", rejects("1")),
            unpublished("@Pattern(regexp = \"\\\\bab\\\\b\")", rejects("x")),
            unpublished("@Pattern(regexp = \"\\\\p{L}+\")", rejects("1")),
            unpublished("@Pattern(regexp = \"" + javaLiteral(GRIN) + "\")", rejects("x")),
            unpublished("@Pattern(regexp = \"[\\\\S]\")", rejects(" ")),
            unpublished("@Pattern(regexp = \"[\\\\D_]\")", rejects("5")),
            unpublished("@Pattern(regexp = \"[\\\\uD7FF-\\\\uE000]\")", rejects("a")));

    private static Compilation compilation;
    private static Object tool;
    private static Map<String, Method> toolMethods;
    private static Validator validator;
    private static ValidatorFactory validatorFactory;
    private static Context js;
    private static JsonNode mcpTools;
    private static JsonNode openApi;

    @BeforeAll
    static void compileFixture() throws Exception {
        compilation = javac().withProcessors(new AgenticProcessor()).withOptions("-Aai.atlas.constraints=true")
                .compile(JavaFileObjects.forSourceString(SERVICE, fixture()));
        assertThat(compilation).succeeded();

        ClassLoader loader = new GeneratedClassLoader(compilation, RegexConsistencyTest.class.getClassLoader());
        Class<?> service = loader.loadClass(SERVICE);
        Class<?> toolClass = loader.loadClass(TOOL_CLASS);
        tool = toolClass.getConstructor(service).newInstance(service.getConstructor().newInstance());
        toolMethods = new HashMap<>();
        for (Method method : toolClass.getDeclaredMethods()) {
            toolMethods.put(method.getName(), method);
        }

        validatorFactory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory();
        validator = validatorFactory.getValidator();
        js = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
        mcpTools = JSON.readTree(ConstraintGenerationTest.resource(compilation,
                McpToolsResourceGenerator.RESOURCE_PATH));
        openApi = JSON.readTree(ConstraintGenerationTest.resource(compilation, OPENAPI));
    }

    @AfterAll
    static void close() {
        if (js != null) {
            js.close();
        }
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    void beanValidationGivesJavasResult() {
        forEachInput((name, c, input) -> assertThat(beanValidationAccepts(name, input.value()))
                .as("Bean Validation, %s %s on %s", name, c.annotations(), describe(input.value()))
                .isEqualTo(input.accepted()));
    }

    @Test
    void mcpInputSchemaAgreesUnderEcmaScript() {
        for (String flags : ECMASCRIPT_FLAGS) {
            SchemaRegistry registry = registry(Dialects.getDraft202012(), flags);
            forEachInput((name, c, input) -> assertThat(mcpAccepts(registry, name, input.value()))
                    .as("mcp-tools.json, flags '%s', %s %s on %s", flags, name, c.annotations(),
                            describe(input.value()))
                    .isEqualTo(c.published() ? input.accepted() : true));
        }
    }

    @Test
    void openApiParameterSchemaAgreesUnderEcmaScript() {
        for (String flags : ECMASCRIPT_FLAGS) {
            SchemaRegistry registry = registry(Dialects.getOpenApi30(), flags);
            forEachInput((name, c, input) -> assertThat(openApiAccepts(registry, name, input.value()))
                    .as("OpenAPI, flags '%s', %s %s on %s", flags, name, c.annotations(), describe(input.value()))
                    .isEqualTo(c.published() ? input.accepted() : true));
        }
    }

    /**
     * The one known disagreement on a published constraint, by the owner's decision: Bean Validation
     * counts a string's length in UTF-16 units, JSON Schema in code points, and {@code minLength}/
     * {@code maxLength} are published as they are. U+1F600 is two units, one code point.
     */
    @Test
    void sizeCountsUtf16UnitsInBeanValidationButCodePointsInTheSchemas() {
        String name = name(CASES.stream().map(Case::annotations).toList().indexOf(SIZE_MAX_1));

        assertThat(beanValidationAccepts(name, GRIN)).as("Bean Validation").isFalse();
        for (String flags : ECMASCRIPT_FLAGS) {
            assertThat(mcpAccepts(registry(Dialects.getDraft202012(), flags), name, GRIN))
                    .as("mcp-tools.json, flags '%s'", flags).isTrue();
            assertThat(openApiAccepts(registry(Dialects.getOpenApi30(), flags), name, GRIN))
                    .as("OpenAPI, flags '%s'", flags).isTrue();
        }
    }

    @Test
    void publishedCasesCarryTheirPatternAndUnpublishedOnesNoneWithAWarning() {
        List<String> warnings = compilation.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING).map(d -> d.getMessage(Locale.ROOT)).toList();
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            JsonNode property = tool(name(i)).get("inputSchema").get("properties").get(PARAMETER);
            boolean hasPattern = property.has("pattern") || property.has("allOf");
            boolean patterned = c.annotations().contains("@Pattern") || c.annotations().contains("@NotBlank");
            assertThat(hasPattern).as("%s %s", name(i), c.annotations()).isEqualTo(c.published() && patterned);
            if (!c.published()) {
                assertThat(warnings).as("%s %s", name(i), c.annotations())
                        .anyMatch(w -> w.contains("parameter '" + PARAMETER + "'") && w.contains("cannot be published"));
            }
        }
        assertThat(warnings.stream().filter(w -> w.contains("cannot be published")))
                .hasSize((int) CASES.stream().filter(c -> !c.published()).count());
    }

    // ------------------------------------------------------------ engines

    private static boolean beanValidationAccepts(String name, String value) {
        return validator.forExecutables().validateParameters(tool, toolMethods.get(name), new Object[]{value})
                .isEmpty();
    }

    private static boolean mcpAccepts(SchemaRegistry registry, String name, String value) {
        Schema schema = registry.getSchema(tool(name).get("inputSchema"));
        ObjectNode arguments = JSON.createObjectNode();
        arguments.set(PARAMETER, TextNode.valueOf(value));
        return schema.validate(arguments).isEmpty();
    }

    private static boolean openApiAccepts(SchemaRegistry registry, String name, String value) {
        JsonNode parameter = openApi.get("paths").get(REST_PATH + name).get("post").get("parameters").get(0);
        return registry.getSchema(parameter.get("schema")).validate(TextNode.valueOf(value)).isEmpty();
    }

    /** A registry whose {@code pattern} keyword runs on GraalJS with {@code flags}, fetching nothing remote. */
    private static SchemaRegistry registry(Dialect dialect, String flags) {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .regularExpressionFactory(new EcmaScriptRegex(js, flags)).build();
        return SchemaRegistry.withDefaultDialect(dialect, builder -> builder.schemaRegistryConfig(config)
                .schemaLoader(loader -> loader.fetchRemoteResources(false)));
    }

    /** ECMAScript {@code RegExp.prototype.test}, the JSON Schema {@code pattern} semantics (unanchored). */
    private record EcmaScriptRegex(Context js, String flags) implements RegularExpressionFactory {

        private static final String COMPILE = "(p, f) => { const r = new RegExp(p, f); return s => r.test(s); }";

        @Override
        public RegularExpression getRegularExpression(String regex) {
            Value test = js.eval("js", COMPILE).execute(regex, flags);
            return value -> test.execute(value).asBoolean();
        }
    }

    // ------------------------------------------------------------ fixture

    @FunctionalInterface
    private interface CaseCheck {
        void check(String name, Case c, Input input);
    }

    private static void forEachInput(CaseCheck check) {
        for (int i = 0; i < CASES.size(); i++) {
            for (Input input : CASES.get(i).inputs()) {
                check.check(name(i), CASES.get(i), input);
            }
        }
    }

    private static String name(int index) {
        return "c" + index;
    }

    private static JsonNode tool(String name) {
        for (JsonNode tool : mcpTools.get("tools")) {
            if (tool.get("name").asText().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError("no tool " + name);
    }

    private static String fixture() {
        StringBuilder methods = new StringBuilder();
        for (int i = 0; i < CASES.size(); i++) {
            methods.append("    public String ").append(name(i)).append('(').append(CASES.get(i).annotations())
                    .append(" String ").append(PARAMETER).append(") { return ").append(PARAMETER).append("; }\n");
        }
        return """
                package t;
                import com.egoge.ai.atlas.annotations.*;
                import jakarta.validation.constraints.*;
                @AgenticExposed(description = "Regex fixture", readOnly = Hint.TRUE)
                public class Rx {
                %s}
                """.formatted(methods);
    }

    /** {@code value} as the body of a Java string literal; non-ASCII as Unicode escapes. */
    private static String javaLiteral(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7E) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String describe(String value) {
        StringBuilder out = new StringBuilder("\"");
        value.chars().forEach(c -> out.append(c >= 0x20 && c <= 0x7E ? String.valueOf((char) c)
                : String.format("\\u%04X", c)));
        return out.append('"').toString();
    }

    /** Loads the classes of a compilation's class output, delegating everything else to the parent. */
    private static final class GeneratedClassLoader extends ClassLoader {

        private final Map<String, JavaFileObject> classes = new HashMap<>();

        GeneratedClassLoader(Compilation compilation, ClassLoader parent) {
            super(parent);
            for (JavaFileObject file : compilation.generatedFiles()) {
                String path = file.toUri().getPath();
                int start = path.indexOf(CLASS_OUTPUT);
                if (file.getKind() == JavaFileObject.Kind.CLASS && start >= 0) {
                    String binaryName = path.substring(start + CLASS_OUTPUT.length(),
                            path.length() - CLASS_SUFFIX.length()).replace('/', '.');
                    classes.put(binaryName, file);
                }
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            JavaFileObject file = classes.get(name);
            if (file == null) {
                throw new ClassNotFoundException(name);
            }
            try (InputStream in = file.openInputStream()) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
