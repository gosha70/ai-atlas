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
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

        assertThat(ir.irVersion()).isEqualTo(2);
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
        assertThat(first).startsWith("{\n  \"irVersion\": 2,\n");
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
    void irVersionAboveTwoIsAnError() {
        String v3 = v1Document().replace("\"irVersion\": 1", "\"irVersion\": 3");

        assertThatThrownBy(() -> IrJson.parse(v3, ".atlas/api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("irVersion 3")
                .hasMessageContaining("supports irVersion 2");
    }

    @Test
    void v2DocumentWithAMissingOrNullSlotIsMalformed() {
        String valid = IrJson.write(document(EffectiveConstraints.NONE, true, Hints.NONE));

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
                false, false, true, "Name", constraints, new FieldLifecycle(1, Integer.MAX_VALUE, 0, ""));
        Operation op = new Operation("shop.Catalog", "find", "find", List.of("AI"), "Find",
                null, List.of(new Parameter("q", "java.lang.String", "", List.of(), required, constraints)),
                new Return("java.lang.String", "NONE", null, null), hints,
                new OperationLifecycle(1, Integer.MAX_VALUE, 0, ""));
        return new ContractIr(ContractIr.IR_VERSION, "/api", 1,
                List.of(new ContractIr.Entity("shop.Product", "ProductDto", "shop.generated", "Product", "",
                        false, List.of(field))),
                List.of(op));
    }

    /** The {@link #document} shape as Phase 2 wrote it: no constraint, requiredness or hint slot. */
    private static String v1Document() {
        return IrJson.write(document(EffectiveConstraints.NONE, true, Hints.NONE))
                .replace("\"irVersion\": 2", "\"irVersion\": 1")
                .replaceAll(",\n\\s*\"required\": true,\n\\s*\"constraints\": \\{}", "")
                .replaceAll("\n\\s*\"constraints\": \\{},", "")
                .replaceAll("\n\\s*\"hints\": \\{},", "");
    }

    private static String compile() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor()).compile(ENTITY, SERVICE);
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
