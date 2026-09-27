/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.GateFixtures.Fixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.BASELINE;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.M;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertOnElement;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertPasses;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.compile;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.generated;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.singleError;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate on constraints (FR-008..FR-011): narrowing an input constraint or making an input
 * required breaks clients of M, widening does not, output constraints and hints are informational,
 * and a migrated version-1 baseline's unknown slots never fail, in gate or lock mode. Every
 * baseline is produced by compiling a fixture.
 */
class ConstraintGateTest {

    private static final String LOCKED = "-A" + AgenticProcessor.OPT_CONTRACT_LOCKED + "=true";
    private static final String SERVICE = "shop.OrderService";
    private static final String ENTITY = "shop.Order";
    private static final String OPERATION = "shop.OrderService#search(java.lang.Integer,java.lang.Integer,int,"
            + "java.math.BigDecimal,java.lang.String,java.util.List<java.lang.String>,java.lang.String)";
    private static final String PARAM = "parameter " + OPERATION + ".";

    /** Each parameter carries a marker comment the tests replace with its annotations. */
    private static final Map<String, String> SOURCES = Map.of(
            ENTITY, """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import jakarta.validation.constraints.*;
                    @AgenticEntity(description = "An order")
                    public class Order {
                        @AgenticField(description = "Total") /*total*/ @Max(100) private Long total;
                        public Long getTotal() { return total; }
                    }
                    """,
            SERVICE, """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import jakarta.validation.constraints.*;
                    import java.math.BigDecimal;
                    import java.util.List;
                    @AgenticExposed(description = "Orders")
                    public class OrderService {
                        public String search(
                                /*limit*/ @Max(100) Integer limit,
                                /*floor*/ @Min(10) Integer floor,
                                /*count*/ @DecimalMin(value = "9", inclusive = false) int count,
                                /*price*/ @PositiveOrZero BigDecimal price,
                                /*code*/ String code,
                                /*tags*/ List<String> tags,
                                /*note*/ @AgenticParam(required = Requiredness.OPTIONAL) String note) {
                            return null;
                        }
                    }
                    """);

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- bounds

    @Test
    void aLowerMaximumFails() throws IOException {
        assertBreaking(base(), base().with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)"),
                "limit", "maximum <= 100 → <= 50");
    }

    @Test
    void aHigherMaximumPasses() throws IOException {
        Fixture before = base().with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)");
        assertCompatible(before, base(), "limit", "maximum");
    }

    @Test
    void anInclusiveMinimumToPositiveWidensAndPasses() throws IOException {
        // >= 10 → > 0
        assertCompatible(base(), base().with(SERVICE, "/*floor*/ @Min(10)", "/*floor*/ @Positive"),
                "floor", "minimum");
    }

    @Test
    void positiveToAnInclusiveMinimumNarrowsAndFails() throws IOException {
        // > 0 → >= 10
        Fixture before = base().with(SERVICE, "/*floor*/ @Min(10)", "/*floor*/ @Positive");
        assertBreaking(before, base(), "floor", "minimum > 0 → >= 10");
    }

    @Test
    void anIntegralExclusiveBoundEqualToTheNextInclusiveOneIsNoDifference() throws IOException {
        // on an int, > 9 and >= 10 are the same endpoint, in either direction
        Fixture inclusive = base().with(SERVICE, "/*count*/ @DecimalMin(value = \"9\", inclusive = false)",
                "/*count*/ @Min(10)");
        assertNoDifference(base(), inclusive);
        assertNoDifference(inclusive, base());
    }

    @Test
    void positiveOrZeroToPositiveOnADecimalFails() throws IOException {
        assertBreaking(base(), base().with(SERVICE, "/*price*/ @PositiveOrZero", "/*price*/ @Positive"),
                "price", "minimum >= 0 → > 0");
    }

    @Test
    void positiveToPositiveOrZeroOnADecimalPasses() throws IOException {
        Fixture before = base().with(SERVICE, "/*price*/ @PositiveOrZero", "/*price*/ @Positive");
        assertCompatible(before, base(), "price", "minimum");
    }

    // ---------------------------------------------------------------- lengths, items, patterns, notBlank

    @Test
    void aLowerMaximumLengthFails() throws IOException {
        Fixture before = base().with(SERVICE, "/*code*/", "/*code*/ @Size(max = 10)");
        assertBreaking(before, base().with(SERVICE, "/*code*/", "/*code*/ @Size(max = 5)"), "code",
                "maxLength 10 → 5");
    }

    @Test
    void aListGainingAMinimumSizeFails() throws IOException {
        assertBreaking(base(), base().with(SERVICE, "/*tags*/", "/*tags*/ @Size(min = 1)"), "tags",
                "minItems (none) → 1");
    }

    @Test
    void anAddedNotBlankFails() throws IOException {
        assertBreaking(base(), base().with(SERVICE, "/*code*/", "/*code*/ @NotBlank"), "code",
                "notBlank (none) → true");
    }

    @Test
    void anAddedPatternFailsAndARemovedOnePasses() throws IOException {
        Fixture patterned = base().with(SERVICE, "/*code*/", "/*code*/ @Pattern(regexp = \"[A-Z]+\")");
        assertBreaking(base(), patterned, "code", "patterns (none) → [[A-Z]+]");
        assertCompatible(patterned, base(), "code", "patterns");
    }

    // ---------------------------------------------------------------- requiredness

    @Test
    void aParameterBecomingRequiredFails() throws IOException {
        assertBreaking(base(), base().with(SERVICE,
                        "/*note*/ @AgenticParam(required = Requiredness.OPTIONAL)", "/*note*/"),
                "note", "required false → true");
    }

    @Test
    void aParameterBecomingOptionalPasses() throws IOException {
        assertCompatible(base(), base().with(SERVICE, "/*code*/",
                "/*code*/ @AgenticParam(required = Requiredness.OPTIONAL)"), "code", "required");
    }

    // ---------------------------------------------------------------- overrides, outputs, hints

    @Test
    void anOverrideWideningABeanValidationBoundPassesAndWarns() throws IOException {
        Fixture before = base().with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)");
        Compilation compilation = assertCompatible(before, base().with(SERVICE, "/*limit*/ @Max(100)",
                "/*limit*/ @Max(50) @AgenticConstraints(maximum = \"100\")"), "limit", "maximum");

        assertThat(compilation.warnings()).map(d -> d.getMessage(null))
                .anySatisfy(w -> assertThat(w).contains("looser than its Bean Validation constraint")
                        .contains("maximum <= 50 → <= 100"));
    }

    @Test
    void anOutputFieldConstraintChangeIsInformationalWithNoDiagnostic() throws IOException {
        Compilation compilation = gate(baseline(base()),
                base().with(ENTITY, "/*total*/ @Max(100)", "/*total*/ @Max(10)"));

        assertPasses(compilation);
        assertThat(diagnosticsMentioning(compilation, "Order#total")).isEmpty();
        assertThat(entries(compilation)).containsExactly(List.of("field shop.Order#total", "constraints",
                "output", "{maximum=100}", "{maximum=10}", "informational"));
    }

    @Test
    void aHintChangeIsInformationalWithNoDiagnostic() throws IOException {
        Path baseline = baseline(base());
        Files.writeString(baseline, Files.readString(baseline).replaceFirst("\"hints\": \\{}",
                "\"hints\": {\"readOnly\": true}"));

        Compilation compilation = gate(baseline, base());

        assertPasses(compilation);
        assertThat(diagnosticsMentioning(compilation, "hints")).isEmpty();
        assertThat(entries(compilation)).containsExactly(Arrays.asList("operation " + OPERATION, "hints", "input",
                "{readOnly=true}", null, "informational"));
    }

    // ---------------------------------------------------------------- migrated baselines and lock mode

    @Test
    void aMigratedV1BaselinePassesInGateAndLockMode() throws IOException {
        Path v1 = asVersion1(baseline(base()));
        Fixture narrowed = base().with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)")
                .with(SERVICE, "/*note*/ @AgenticParam(required = Requiredness.OPTIONAL)", "/*note*/");

        Compilation gated = gate(v1, narrowed);
        assertPasses(gated);
        assertThat(entries(gated)).isEmpty();

        assertPasses(gate(v1, narrowed, LOCKED));
    }

    @Test
    void lockModeFailsAV2ConstraintChangeEvenWhenItWidens() throws IOException {
        Fixture before = base().with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)");

        Compilation compilation = gate(baseline(before), base(), LOCKED);

        assertThat(singleError(compilation)).contains("Lock mode").contains("at 1 element(s): operation " + OPERATION);
    }

    // ---------------------------------------------------------------- message and report

    @Test
    void theMessageNamesPathKeyDirectionAndRemedy() throws IOException {
        Compilation compilation = gate(baseline(base()),
                base().with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)"));

        assertThat(singleError(compilation)).isEqualTo("[ai-atlas] Breaking contract change to " + PARAM
                + "limit: maximum <= 100 → <= 50 (input). It narrows an input, which breaks clients of major "
                + M + ". To make it legitimate, declare @AgenticExposed(apiUntil = " + M + ") on the old"
                + " operation, plus a replacement with apiSince = " + (M + 1) + "; or accept the change"
                + " explicitly with atlasAccept.");
        assertOnElement(compilation);
    }

    @Test
    void theReportOrdersEntriesByPathThenChange() throws Exception {
        Fixture before = base().with(SERVICE, "/*code*/", "/*code*/ @Size(max = 10)");
        Fixture after = base()
                .with(ENTITY, "/*total*/ @Max(100)", "/*total*/ @Max(200)")
                .with(SERVICE, "/*limit*/ @Max(100)", "/*limit*/ @Max(50)")
                .with(SERVICE, "/*floor*/ @Min(10)", "/*floor*/ @Min(20)")
                .with(SERVICE, "/*code*/", "/*code*/ @NotBlank @Size(max = 20)");

        // The breaking entries fail the compilation, whose resources are then unavailable: check the IRs directly
        ContractIr fresh = IrJson.parse(irOf(compile(after.sources(), MAJOR + M)), "fresh");
        ContractGate.Outcome outcome = ContractGate.check(baseline(before).toString(), fresh);

        assertThat(outcome.findings()).hasSize(3);
        assertThat(entries(outcome.diffJson())).containsExactly(
                List.of("field shop.Order#total", "constraints", "output", "{maximum=100}", "{maximum=200}",
                        "informational"),
                entry(PARAM + "code", "maxLength", "10", "20", "compatible"),
                entry(PARAM + "code", "notBlank", null, "true", "breaking"),
                entry(PARAM + "floor", "minimum", ">= 10", ">= 20", "breaking"),
                entry(PARAM + "limit", "maximum", "<= 100", "<= 50", "breaking"));
    }

    // ---------------------------------------------------------------- helpers

    private static Fixture base() {
        return new Fixture(new LinkedHashMap<>(SOURCES));
    }

    private Path baseline(Fixture fixture) throws IOException {
        return Files.writeString(Files.createTempFile(dir, "api", ".ir.json"),
                irOf(compile(fixture.sources(), MAJOR + M)), StandardCharsets.UTF_8);
    }

    /** The baseline as Phase 2 wrote it: {@code irVersion} 1, with no constraint, requiredness or hint slot. */
    private static Path asVersion1(Path baseline) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(baseline.toFile());
        ((ObjectNode) root).put("irVersion", 1);
        stripSlots(root);
        return Files.writeString(baseline, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    private static void stripSlots(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(List.of("constraints", "required", "hints"));
        }
        node.forEach(ConstraintGateTest::stripSlots);
    }

    private Compilation gate(Path baseline, Fixture fixture, String... options) {
        List<Object> all = new ArrayList<>(List.of(MAJOR + M, BASELINE + baseline));
        all.addAll(List.of(options));
        return javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(fixture.sources());
    }

    private void assertBreaking(Fixture before, Fixture after, String parameter, String change) throws IOException {
        Compilation compilation = gate(baseline(before), after);

        assertThat(singleError(compilation)).contains(PARAM + parameter + ": " + change + " (input)")
                .contains("It narrows an input, which breaks clients of major " + M)
                .contains("apiSince = " + (M + 1)).contains("atlasAccept");
        assertOnElement(compilation);
    }

    private Compilation assertCompatible(Fixture before, Fixture after, String parameter, String key)
            throws IOException {
        Compilation compilation = gate(baseline(before), after);

        assertPasses(compilation);
        assertThat(entries(compilation)).singleElement()
                .satisfies(e -> assertThat(e).startsWith(PARAM + parameter, key).endsWith("compatible"));
        return compilation;
    }

    private void assertNoDifference(Fixture before, Fixture after) throws IOException {
        Compilation compilation = gate(baseline(before), after);

        assertPasses(compilation);
        assertThat(entries(compilation)).isEmpty();
    }

    private static List<String> entry(String path, String change, String before, String after,
                                      String classification) {
        return Arrays.asList(path, change, "input", before, after, classification);
    }

    /** The {@code contract-diff.json} entries as path, change, direction, before, after, classification. */
    private static List<List<String>> entries(Compilation compilation) throws IOException {
        return entries(generated(compilation, ContractGate.DIFF_RESOURCE_PATH));
    }

    private static List<List<String>> entries(String diffJson) throws IOException {
        JsonNode report = new ObjectMapper().readTree(diffJson);
        List<List<String>> entries = new ArrayList<>();
        for (JsonNode d : report.get("differences")) {
            entries.add(Arrays.asList(d.get("path").asText(), d.get("change").asText(),
                    d.get("direction").asText(), text(d.get("before")), text(d.get("after")),
                    d.get("classification").asText()));
        }
        return entries;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static List<String> diagnosticsMentioning(Compilation compilation, String text) {
        return compilation.diagnostics().stream().map(d -> d.getMessage(null)).filter(m -> m.contains(text))
                .toList();
    }
}
