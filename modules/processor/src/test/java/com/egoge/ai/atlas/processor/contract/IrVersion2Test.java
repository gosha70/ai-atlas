/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Hints;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract IR version 2 (FR-005, FR-006): constraint, requiredness and hint slots written in a
 * fixed order, and version-1 documents migrated with those slots unknown.
 */
class IrVersion2Test {

    private static final JavaFileObject ENTITY = JavaFileObjects.forSourceString("shop.Product", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            @AgenticEntity(description = "A product")
            public class Product {
                @AgenticField(description = "Name")
                @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Z].*")
                private String name;
                @AgenticField(description = "Stock")
                @PositiveOrZero @AgenticConstraints(maximum = "1000")
                private Integer stock;
                public String getName() { return name; }
                public Integer getStock() { return stock; }
            }
            """);
    private static final JavaFileObject SERVICE = JavaFileObjects.forSourceString("shop.Catalog", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import jakarta.validation.constraints.*;
            import java.util.List;
            @AgenticExposed(description = "Catalog", returnType = Product.class)
            public class Catalog {
                @AgenticExposed(description = "Search products")
                public List<Product> search(
                        @Pattern(regexp = "b+") @Pattern(regexp = "a+") String query,
                        @Positive @DecimalMax(value = "100", inclusive = false) @AgenticParam(
                                description = "Page size", required = Requiredness.OPTIONAL) Integer limit,
                        @Size(min = 1, max = 5) List<String> tags) {
                    return List.of();
                }
            }
            """);

    @Test
    void constraintsRequirednessAndHintsAreRecordedInV2() throws Exception {
        ContractIr ir = IrJson.parse(compile(), "api.ir.json");

        assertThat(ir.irVersion()).isEqualTo(ContractIr.IR_VERSION);
        List<Field> fields = ir.entities().get(0).fields();
        assertThat(fields.get(0).constraints()).isEqualTo(new EffectiveConstraints(null, false, null, false,
                null, 40, null, null, List.of(new PatternConstraint("[A-Z].*", List.of())), true));
        assertThat(fields.get(1).constraints()).isEqualTo(new EffectiveConstraints("0", false, "1000", false,
                null, null, null, null, List.of(), false));

        Operation search = ir.operations().get(0);
        assertThat(search.hints()).isEqualTo(Hints.NONE);
        List<Parameter> params = search.parameters();
        assertThat(params.get(0).required()).isTrue();
        assertThat(params.get(0).constraints().patterns()).extracting(PatternConstraint::regex)
                .containsExactly("a+", "b+");
        assertThat(params.get(1).required()).isFalse();
        assertThat(params.get(1).description()).isEqualTo("Page size");
        assertThat(params.get(1).constraints()).isEqualTo(new EffectiveConstraints("0", true, "100", true,
                null, null, null, null, List.of(), false));
        assertThat(params.get(2).constraints()).isEqualTo(new EffectiveConstraints(null, false, null, false,
                null, null, 1, 5, List.of(), false));
    }

    @Test
    void v2IsWrittenDeterministicallyAndRoundTrips() throws Exception {
        String first = compile();
        String second = compile();

        assertThat(second.getBytes(StandardCharsets.UTF_8)).isEqualTo(first.getBytes(StandardCharsets.UTF_8));
        assertThat(first).startsWith("{\n  \"irVersion\": " + ContractIr.IR_VERSION + ",\n");
        assertThat(IrJson.write(IrJson.parse(first, "api.ir.json"))).isEqualTo(first);
    }

    @Test
    void constraintKeysAreWrittenInTheFixedOrderWithOnlySetKeys() throws Exception {
        EffectiveConstraints all = new EffectiveConstraints("1.5", true, "9", false, 1, 2, 3, 4,
                List.of(new PatternConstraint("b", List.of()), new PatternConstraint("a", List.of("MULTILINE")),
                        new PatternConstraint("a", List.of())), true);
        String json = IrJson.write(document(all, true, new Hints(true, null, false, null)));

        assertThat(flat(json)).contains(flat("""
                      "constraints": {
                        "minimum": "1.5",
                        "exclusiveMinimum": true,
                        "maximum": "9",
                        "minLength": 1,
                        "maxLength": 2,
                        "minItems": 3,
                        "maxItems": 4,
                        "patterns": [
                          {
                            "regex": "a",
                            "flags": []
                          },
                          {
                            "regex": "a",
                            "flags": [
                              "MULTILINE"
                            ]
                          },
                          {
                            "regex": "b",
                            "flags": []
                          }
                        ],
                        "notBlank": true
                      },
                """));
        assertThat(flat(json)).contains(flat("""
                      "hints": {
                        "readOnly": true,
                        "idempotent": false
                      },
                """));
        assertThat(json).doesNotContain("exclusiveMaximum");
        assertThat(IrJson.parse(json, "api.ir.json")).isEqualTo(document(all, true, new Hints(true, null, false, null)));
    }

    @Test
    void emptyConstraintsAndHintsAreEmptyObjects() {
        String json = IrJson.write(document(EffectiveConstraints.NONE, true, Hints.NONE));

        assertThat(json).contains("\"constraints\": {}").contains("\"hints\": {}").contains("\"required\": true");
    }

    @Test
    void v1DocumentMigratesWithUnknownSlots() throws Exception {
        ContractIr migrated = IrJson.parse(v1Document(), ".atlas/api.ir.json");

        assertThat(migrated.irVersion()).isEqualTo(ContractIr.IR_VERSION);
        Field field = migrated.entities().get(0).fields().get(0);
        assertThat(field.constraints()).isNull();
        Operation op = migrated.operations().get(0);
        assertThat(op.hints()).isNull();
        assertThat(op.parameters().get(0).required()).isNull();
        assertThat(op.parameters().get(0).constraints()).isNull();
        assertThat(field.description()).isEqualTo("Name");
    }

    @Test
    void irVersionAboveThreeIsAnError() {
        String v4 = v1Document().replace("\"irVersion\": 1", "\"irVersion\": 4");

        assertThatThrownBy(() -> IrJson.parse(v4, ".atlas/api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("irVersion 4")
                .hasMessageContaining("supports irVersion 3");
    }

    @Test
    void v2DocumentWithAMissingOrNullSlotIsMalformed() {
        String valid = v2Document();

        assertMalformed(valid.replaceFirst("\"constraints\": \\{},\n\\s*\"lifecycle\"", "\"lifecycle\""),
                "missing 'constraints'");
        assertMalformed(valid.replaceFirst("\"constraints\": \\{}", "\"constraints\": null"),
                "'constraints' must be an object");
        assertMalformed(valid.replaceFirst("\"required\": true,", ""), "missing 'required'");
        assertMalformed(valid.replaceFirst("\"required\": true", "\"required\": null"), "'required' must be a boolean");
        assertMalformed(valid.replaceFirst("\"hints\": \\{},", ""), "missing 'hints'");
        assertMalformed(valid.replaceFirst("\"hints\": \\{}", "\"hints\": null"), "'hints' must be an object");
        assertMalformed(valid.replaceFirst("\"constraints\": \\{}", "\"constraints\": {\"minimum\": 5}"),
                "'minimum' must be a string");
        assertMalformed(valid.replaceFirst("\"constraints\": \\{}", "\"constraints\": {\"minimum\": \"five\"}"),
                "'minimum' must be a decimal string");
        assertMalformed(valid.replaceFirst("\"constraints\": \\{}", "\"constraints\": {\"max\": \"5\"}"),
                "unknown key 'max' in 'constraints'");
        assertMalformed(valid.replaceFirst("\"hints\": \\{}", "\"hints\": {\"readOnly\": \"yes\"}"),
                "'readOnly' must be a boolean");
    }

    @Test
    void surrogatesAndOtherNonAsciiTextRoundTripExactly() throws Exception {
        // Lone high, lone low, a reversed pair, a real pair (U+1F600), é and U+2028
        String regex = "[^\uD800-\uDFFF]\uDC00x\uD800\uDC00\uD83D\uD83D\uDE00\u00e9\u2028";
        ContractIr ir = document(new EffectiveConstraints(null, false, null, false, null, null, null, null,
                List.of(new PatternConstraint(regex, List.of())), false), true, Hints.NONE);

        byte[] bytes = IrJson.writeBytes(ir);
        String json = new String(bytes, StandardCharsets.UTF_8);

        assertThat(IrJson.parse(json, "api.ir.json")).isEqualTo(ir);
        assertThat(json).contains("\"[^\\ud800-\\udfff]\\udc00x\\ud800\\udc00\\ud83d\\ud83d\\ude00\u00e9\u2028\"")
                .doesNotContain("?");
    }

    @Test
    void aPatternWithLoneSurrogatesGatesAgainstItsOwnBaselineWithNoDifference(@TempDir Path dir)
            throws Exception {
        JavaFileObject service = JavaFileObjects.forSourceString("shop.Lookup", """
                package shop;
                import com.egoge.ai.atlas.annotations.*;
                import jakarta.validation.constraints.*;
                @AgenticExposed(description = "Lookup")
                public class Lookup {
                    @AgenticExposed(description = "Find by code")
                    public String find(@Pattern(regexp = "[^\\uD800-\\uDFFF]+") String code) { return null; }
                }
                """);
        String first = compile(service);
        assertThat(IrJson.parse(first, "api.ir.json").operations().get(0).parameters().get(0).constraints()
                .patterns()).extracting(PatternConstraint::regex).containsExactly("[^\uD800-\uDFFF]+");
        Path baseline = Files.writeString(dir.resolve("api.ir.json"), first, StandardCharsets.UTF_8);

        Compilation gated = javac().withProcessors(new AgenticProcessor())
                .withOptions("-A" + AgenticProcessor.OPT_CONTRACT_BASELINE + "=" + baseline,
                        "-A" + AgenticProcessor.OPT_CONTRACT_LOCKED + "=true")
                .compile(service);

        assertThat(gated.status()).as(gated.diagnostics().toString()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(new ObjectMapper().readTree(GateFixtures.generated(gated, ContractGate.DIFF_RESOURCE_PATH))
                .get("differences")).isEmpty();
        assertThat(GateFixtures.irOf(gated)).isEqualTo(first);
    }

    // ------------------------------------------------------------ helpers

    /** {@code json} with each line's indentation removed, to compare nested snippets. */
    private static String flat(String json) {
        return String.join("\n", json.lines().map(String::strip).toList());
    }

    private static void assertMalformed(String json, String detail) {
        assertThatThrownBy(() -> IrJson.parse(json, "api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("is not valid Contract IR JSON")
                .hasMessageContaining(detail);
    }

    private static ContractIr document(EffectiveConstraints constraints, Boolean required, Hints hints) {
        Field field = new Field("name", "name", "java.lang.String", "NONE", null, null, null, false, List.of(),
                false, false, true, "Name", constraints, Field.EVERY_CHANNEL,
                new FieldLifecycle(1, Integer.MAX_VALUE, 0, ""));
        Operation op = new Operation("shop.Catalog", "find", "find", List.of("AI"), "Find",
                null, List.of(new Parameter("q", "java.lang.String", "", List.of(), required, constraints)),
                new Return("java.lang.String", "NONE", null, null), hints,
                new OperationLifecycle(1, Integer.MAX_VALUE, 0, ""));
        return new ContractIr(ContractIr.IR_VERSION, "/api", 1,
                List.of(new ContractIr.Entity("shop.Product", "ProductDto", "shop.generated", "Product", "",
                        false, List.of(field))),
                List.of(op));
    }

    /** The {@link #document} shape as version 2 wrote it: no channels slot. */
    static String v2Document() {
        return IrJson.write(document(EffectiveConstraints.NONE, true, Hints.NONE))
                .replace("\"irVersion\": 3", "\"irVersion\": 2")
                .replaceAll("\n\\s*\"channels\": \\[\n\\s*\"AI\",\n\\s*\"API\"\n\\s*],", "");
    }

    /** The {@link #document} shape as Phase 2 wrote it: no constraint, requiredness, hint or channels slot. */
    static String v1Document() {
        return v2Document()
                .replace("\"irVersion\": 2", "\"irVersion\": 1")
                .replaceAll(",\n\\s*\"required\": true,\n\\s*\"constraints\": \\{}", "")
                .replaceAll("\n\\s*\"constraints\": \\{},", "")
                .replaceAll("\n\\s*\"hints\": \\{},", "");
    }

    private static String compile() {
        return compile(ENTITY, SERVICE);
    }

    private static String compile(JavaFileObject... sources) {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(sources);
        assertThat(compilation.status()).as(compilation.diagnostics().toString())
                .isEqualTo(Compilation.Status.SUCCESS);
        JavaFileObject file = compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractIr.RESOURCE_PATH)
                .orElseThrow(() -> new AssertionError("no " + ContractIr.RESOURCE_PATH + " emitted"));
        try (var in = file.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
