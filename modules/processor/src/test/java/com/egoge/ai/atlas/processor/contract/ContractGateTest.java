/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.egoge.ai.atlas.processor.contract.GateFixtures.Fixture;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.StandardLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.BASELINE;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.M;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertOnElement;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertPasses;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.compile;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.errors;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.fixture;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.generated;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.notes;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.singleError;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compatibility gate (FR-008..FR-013). Every case compiles a variant of one fixture against a
 * baseline produced by compiling the unchanged fixture at M = 2.
 */
class ContractGateTest {

    private static final String ADDED_FIND_SERVICE = """
            package shop;
            import com.egoge.ai.atlas.annotations.AgenticExposed;
            @AgenticExposed(description = "Customers"%s)
            public class CustomerService {
                @AgenticExposed(description = "Find a customer", toolName = "findCustomer")
                public String find() { return null; }
            }
            """;

    @TempDir
    Path dir;
    private Path baseline;

    @BeforeEach
    void writeBaseline() throws IOException {
        baseline = GateFixtures.writeBaseline(dir);
    }

    // ---------------------------------------------------------------- output: fields

    @Test
    void deletingAPublishedFieldFails() {
        Compilation compilation = gate(fixture().with("shop.Order",
                "@AgenticField(description = \"Total\") private Long total;", "private Long total;"));

        String error = singleError(compilation);
        assertThat(error).contains("Breaking contract change to field shop.Order#total: removed java.lang.Long → (none)")
                .contains("(output)").contains("Responses no longer carry the field")
                .contains("breaks clients of major 2")
                .contains("@AgenticField(removedInVersion = 3) on the old field")
                .contains("sinceVersion = 3").contains("atlasAccept");
    }

    @Test
    void declaringRemovedInVersionAboveThePublishedMajorPasses() {
        assertPasses(gate(fixture().with("shop.Order", "@AgenticField(description = \"Total\")",
                "@AgenticField(description = \"Total\", removedInVersion = 3)")));
    }

    @Test
    void deletingAFieldInactiveAtThePublishedMajorPasses() {
        assertPasses(gate(fixture().with("shop.Order",
                "@AgenticField(description = \"Legacy\", removedInVersion = 2) private String legacy;",
                "private String legacy;")));
    }

    @Test
    void renamingAFieldsJavaNameFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "private Long total;", "private Long amount;")
                .with("shop.Order", "public Long getTotal() { return total; }",
                        "public Long getAmount() { return amount; }"));

        assertThat(singleError(compilation)).contains("field shop.Order#total: removed");
    }

    @Test
    void renamingAFieldsDisplayNameFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "@AgenticField(description = \"Total\")",
                "@AgenticField(description = \"Total\", name = \"sum\")"));

        String error = singleError(compilation);
        assertThat(error).contains("field shop.Order#total: displayName total → sum");
        assertOnElement(compilation);
    }

    @Test
    void changingAFieldsTypeFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "private Long total;", "private Integer total;")
                .with("shop.Order", "public Long getTotal()", "public Integer getTotal()"));

        assertThat(singleError(compilation))
                .contains("field shop.Order#total: javaType java.lang.Long → java.lang.Integer");
    }

    @Test
    void changingOnlyAFieldsTypeHintBetweenDeclaredEntitiesFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "type = Customer.class", "type = Order.class"));

        assertThat(errors(compilation)).anySatisfy(e ->
                        assertThat(e).contains("field shop.Order#related: typeHint shop.Customer → shop.Order"))
                .anySatisfy(e -> assertThat(e).contains("field shop.Order#related: reference"));
    }

    @Test
    void markingAFieldSensitiveFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "@AgenticField(description = \"Total\")",
                "@AgenticField(description = \"Total\", sensitive = true)"));

        assertThat(singleError(compilation)).contains("field shop.Order#total: sensitive false → true");
    }

    @Test
    void unmarkingAFieldSensitivePassesSilently() throws Exception {
        Files.writeString(baseline, irOf(compile(fixture().with("shop.Order", "@AgenticField(description = \"Total\")",
                "@AgenticField(description = \"Total\", sensitive = true)").sources(), MAJOR + M)), StandardCharsets.UTF_8);

        Compilation compilation = gate(fixture());

        assertPasses(compilation);
        assertThat(compilation.diagnostics()).noneSatisfy(d -> assertThat(d.getMessage(null)).contains("Breaking"));
        assertThat(generated(compilation, ContractGate.DIFF_RESOURCE_PATH))
                .contains("\"change\": \"sensitive\"").contains("\"classification\": \"compatible\"");
    }

    @Test
    void addingAFieldPasses() {
        assertPasses(gate(fixture().with("shop.Order", "public Long getId()",
                "@AgenticField(description = \"Note\") private String note;\n    public String getNote() { return note; }\n    public Long getId()")));
    }

    @Test
    void closedResponseEnumGainingAValueFails() {
        Compilation compilation = gate(fixture().with("shop.Status", "OPEN, CLOSED", "OPEN, CLOSED, PENDING")
                .with("shop.Order", "allowedValues = {\"S\", \"M\"}", "allowedValues = {\"S\", \"M\", \"L\"}"));

        assertThat(errors(compilation)).hasSize(2)
                .anySatisfy(e -> assertThat(e)
                        .contains("field shop.Order#status: allowedValues [OPEN, CLOSED] → [OPEN, CLOSED, PENDING]")
                        .contains("Responses may carry [PENDING]").contains("openEnum = true"))
                .anySatisfy(e -> assertThat(e).contains("field shop.Order#size: allowedValues [S, M] → [S, M, L]"));
    }

    @Test
    void openResponseEnumGainingAValuePasses() {
        assertPasses(gate(fixture().with("shop.Status", "OPEN, CLOSED", "OPEN, CLOSED, PENDING")
                .with("shop.Order", "@AgenticField(description = \"Status\")",
                        "@AgenticField(description = \"Status\", openEnum = true)")));
    }

    @Test
    void removingAnOutputEnumValuePasses() {
        assertPasses(gate(fixture().with("shop.Order", "allowedValues = {\"S\", \"M\"}", "allowedValues = {\"S\"}")));
    }

    @Test
    void descriptionChangesPassSilently() {
        Compilation compilation = gate(fixture()
                .with("shop.Order", "@AgenticField(description = \"Total\")", "@AgenticField(description = \"Sum\")")
                .with("shop.OrderService", "\"Label an order\"", "\"Label one order\""));

        assertPasses(compilation);
        assertThat(compilation.diagnostics()).noneSatisfy(d ->
                assertThat(d.getMessage(null)).contains("contract"));
    }

    // ---------------------------------------------------------------- output: entities and returns

    @Test
    void changingAnEntitysDtoNameFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "@AgenticEntity(description = \"An order\")",
                "@AgenticEntity(description = \"An order\", dtoName = \"OrderView\")"));

        assertThat(errors(compilation))
                .anySatisfy(e -> assertThat(e).contains("entity shop.Order: dtoName OrderDto → OrderView")
                        .contains("restore the previous value"))
                .allSatisfy(e -> assertThat(e).doesNotContain("field shop.Order#"));
        assertOnElement(compilation);
    }

    @Test
    void changingAnEntitysDtoPackageFails() {
        Compilation compilation = gate(fixture().with("shop.Order", "@AgenticEntity(description = \"An order\")",
                "@AgenticEntity(description = \"An order\", packageName = \"shop.api\")"));

        assertThat(errors(compilation))
                .anySatisfy(e -> assertThat(e).contains("entity shop.Order: dtoPackage shop.generated → shop.api")
                        .contains("restore the previous value"));
        assertOnElement(compilation);
    }

    @Test
    void changingAnEntitysIncludeTypeInfoFails() {
        Compilation compilation = gate(fixture().with("shop.Customer", "@AgenticEntity(description = \"A customer\")",
                "@AgenticEntity(description = \"A customer\", includeTypeInfo = false)"));

        assertThat(singleError(compilation)).contains("entity shop.Customer: includeTypeInfo true → false");
        assertOnElement(compilation);
    }

    @Test
    void anUnresolvableTypeIsAnErrorOnTheElementNotACrash() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline).compile(fixture()
                        .with("shop.Order", "public Long getId()",
                                "@AgenticField(description = \"Pending\") private Missing<String> pending;\n"
                                        + "    public Long getId()")
                        .with("shop.OrderService", "public String find()",
                                "public String take(Missing<String> m) { return null; }\n    public String find()")
                        .sources());

        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(compilation.errors()).filteredOn(d -> d.getMessage(null).contains("cannot be resolved"))
                .hasSize(2)
                .anySatisfy(d -> assertThat(d.getMessage(null)).contains("field 'pending'").contains("'<any>'"))
                .anySatisfy(d -> assertThat(d.getMessage(null)).contains("method 'take'"))
                .allSatisfy(d -> assertThat(d.getSource()).isNotNull());
    }

    @Test
    void changingAMethodLevelReturnTypeBetweenDeclaredEntitiesFails() {
        Compilation compilation = gate(fixture().with("shop.OrderService",
                "\"Recent orders\", returnType = Order.class", "\"Recent orders\", returnType = Customer.class"));

        assertThat(errors(compilation)).anySatisfy(e -> assertThat(e)
                .contains("operation shop.OrderService#recent(): returns.returnType shop.Order → shop.Customer")
                .contains("(output)").contains("@AgenticExposed(apiUntil = 2)").contains("apiSince = 3"));
    }

    @Test
    void changingAClassLevelReturnTypeBetweenDeclaredEntitiesFails() {
        Compilation compilation = gate(fixture().with("shop.ReportService",
                "returnType = Order.class", "returnType = Customer.class"));

        assertThat(errors(compilation)).anySatisfy(e -> assertThat(e)
                .contains("operation shop.ReportService#all(): returns.returnType shop.Order → shop.Customer"));
    }

    // ---------------------------------------------------------------- input: operations

    @Test
    void changingTheApiBasePathFails() {
        Compilation compilation = gate(fixture(), "-A" + AgenticProcessor.OPT_API_BASE_PATH + "=/svc");

        assertThat(singleError(compilation)).contains("document: apiBasePath /api → /svc");
    }

    @Test
    void addingASecondApiExposedFindFailsNamingTheRenamedOperationId() {
        Compilation compilation = gate(fixture().add("shop.CustomerService", ADDED_FIND_SERVICE.formatted("")));

        String error = singleError(compilation);
        assertThat(error).contains("operation shop.OrderService#find(): operationId find → OrderService_find_get")
                .contains("whose addition caused it (operation shop.CustomerService#find())")
                .contains("apiSince = 3");
    }

    @Test
    void theOperationIdRemedyNamesOnlyTheCollidingAddition() {
        Compilation compilation = gate(fixture().add("shop.CustomerService", ADDED_FIND_SERVICE.formatted(""))
                .with("shop.OrderService", "public String find()",
                        "public String summary(Long id) { return null; }\n    public String find()"));

        assertThat(singleError(compilation)).contains("(operation shop.CustomerService#find())")
                .doesNotContain("summary");
    }

    @Test
    void theOperationIdRemedyNamesAnExistingOperationThatGainsTheApiChannel() throws Exception {
        String aiOnly = ADDED_FIND_SERVICE.formatted("").replace("toolName = \"findCustomer\"",
                "toolName = \"findCustomer\", channels = { AgenticExposed.Channel.AI }");
        Files.writeString(baseline, irOf(compile(fixture().add("shop.CustomerService", aiOnly).sources(),
                MAJOR + M)), StandardCharsets.UTF_8);

        Compilation compilation = gate(fixture().add("shop.CustomerService", ADDED_FIND_SERVICE.formatted("")));

        assertThat(singleError(compilation))
                .contains("operation shop.OrderService#find(): operationId find → OrderService_find_get")
                .contains("(operation shop.CustomerService#find())");
    }

    @Test
    void addingASecondFindInTheNextMajorPasses() {
        assertPasses(gate(fixture().add("shop.CustomerService", ADDED_FIND_SERVICE.formatted(", apiSince = 3"))));
    }

    @Test
    void removingAnOperationFails() {
        Compilation compilation = gate(fixture().with("shop.OrderService",
                "@AgenticExposed(description = \"Label an order\", toolName = \"labelOrder\")\n    public String label(Long id) { return null; }", ""));

        assertThat(singleError(compilation)).contains("operation shop.OrderService#label(java.lang.Long): removed")
                .contains("(input)").contains("@AgenticExposed(apiUntil = 2) on the old operation");
    }

    @Test
    void addingOrChangingAParameterTypeFails() {
        assertThat(singleError(gate(fixture().with("shop.OrderService", "label(Long id)", "label(Long id, boolean full)"))))
                .contains("operation shop.OrderService#label(java.lang.Long): removed");
        assertThat(singleError(gate(fixture().with("shop.OrderService", "label(Long id)", "label(String id)"))))
                .contains("operation shop.OrderService#label(java.lang.Long): removed");
    }

    @Test
    void renamingAParameterFails() {
        Compilation compilation = gate(fixture().with("shop.OrderService", "label(Long id)", "label(Long orderId)"));

        assertThat(singleError(compilation))
                .contains("operation shop.OrderService#label(java.lang.Long): parameter 0.name id → orderId");
        assertOnElement(compilation);
    }

    @Test
    void renamingAGenericParameterIsReportedOnTheMethod() throws Exception {
        String generic = "label(java.util.List<Long> ids, String[] tags, int limit)";
        Files.writeString(baseline, irOf(compile(fixture().with("shop.OrderService", "label(Long id)", generic)
                .sources(), MAJOR + M)), StandardCharsets.UTF_8);

        Compilation compilation = gate(fixture().with("shop.OrderService", "label(Long id)",
                "label(java.util.List<Long> orderIds, String[] tags, int limit)"));

        assertThat(singleError(compilation)).contains("operation shop.OrderService#label("
                + "java.util.List<java.lang.Long>,java.lang.String[],int): parameter 0.name ids → orderIds");
        assertOnElement(compilation);
    }

    @Test
    void removingAChannelFails() {
        Compilation compilation = gate(fixture().with("shop.OrderService", "toolName = \"labelOrder\")",
                "toolName = \"labelOrder\", channels = { AgenticExposed.Channel.AI })"));

        assertThat(singleError(compilation))
                .contains("operation shop.OrderService#label(java.lang.Long): channels [AI, API] → [AI]")
                .contains("Clients of the [API] channel lose the operation");
    }

    @Test
    void changingAToolNameFails() {
        Compilation compilation = gate(fixture().with("shop.OrderService", "toolName = \"labelOrder\"",
                "toolName = \"tagOrder\""));

        assertThat(singleError(compilation)).contains("toolName labelOrder → tagOrder");
    }

    @Test
    void removingAnInputEnumValueFails() {
        Compilation compilation = gate(fixture().with("shop.Priority", "LOW, HIGH", "LOW"));

        assertThat(singleError(compilation))
                .contains("operation shop.OrderService#count(shop.Priority): parameter 0.enumConstants [LOW, HIGH] → [LOW]")
                .contains("Requests carrying [HIGH] are no longer accepted");
    }

    @Test
    void addingAnInputEnumValuePasses() {
        assertPasses(gate(fixture().with("shop.Priority", "LOW, HIGH", "LOW, MEDIUM, HIGH")));
    }

    @Test
    void addingAnOperationPasses() {
        assertPasses(gate(fixture().with("shop.OrderService", "public String find()",
                "public String summary(Long id) { return null; }\n    public String find()")));
    }

    // ---------------------------------------------------------------- baseline handling

    @Test
    void configuredMajorBelowThePublishedMajorErrors() {
        Compilation compilation = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + 1, BASELINE + baseline).compile(fixture().sources());

        assertThat(singleError(compilation)).contains("ai.atlas.api.major=1 is below the published major 2")
                .contains(baseline.toString());
    }

    @Test
    void noBaselineNotesTheExpectedPathAndAtlasAccept() {
        Path missing = dir.resolve("missing/api.ir.json");
        Compilation compilation = gate(fixture(), BASELINE + missing);

        assertPasses(compilation);
        assertThat(notes(compilation, "contract baseline")).singleElement().asString()
                .contains("No contract baseline at " + missing).contains("atlasAccept");
        assertThat(compilation.generatedFile(StandardLocation.CLASS_OUTPUT, ContractGate.DIFF_RESOURCE_PATH)).isEmpty();

        Compilation unset = compile(fixture().sources(), MAJOR + M);
        assertThat(notes(unset, "contract baseline")).singleElement().asString()
                .contains(AgenticProcessor.OPT_CONTRACT_BASELINE).contains("atlasAccept");
    }

    @Test
    void contractDiffListsEveryDifferenceOrderedByElementPath() throws IOException {
        Compilation compilation = gate(fixture()
                .with("shop.Order", "@AgenticField(description = \"Total\")", "@AgenticField(description = \"Sum\")")
                .with("shop.OrderService", "public String find()",
                        "public String summary(Long id) { return null; }\n    public String find()")
                .with("shop.Order", "allowedValues = {\"S\", \"M\"}", "allowedValues = {\"S\"}"));
        assertPasses(compilation);

        JsonNode diff = new ObjectMapper().readTree(generated(compilation, ContractGate.DIFF_RESOURCE_PATH));
        assertThat(diff.get("publishedMajor").intValue()).isEqualTo(M);
        List<String> rows = new ArrayList<>();
        for (JsonNode d : diff.get("differences")) {
            rows.add(d.get("path").textValue() + " | " + d.get("change").textValue() + " | "
                    + d.get("direction").textValue() + " | " + d.get("before") + " | " + d.get("after") + " | "
                    + d.get("classification").textValue());
        }
        assertThat(rows).containsExactly(
                "field shop.Order#size | allowedValues | output | \"[S, M]\" | \"[S]\" | compatible",
                "field shop.Order#total | description | output | \"Total\" | \"Sum\" | compatible",
                "operation shop.OrderService#summary(java.lang.Long) | added | input | null"
                        + " | \"shop.OrderService#summary(java.lang.Long)\" | compatible");
    }

    @Test
    void contractDiffClassifiesBreakingDifferencesAndIsEmptyWithoutDifferences() throws Exception {
        assertThat(generated(gate(fixture()), ContractGate.DIFF_RESOURCE_PATH)).isEqualTo("""
                {
                  "publishedMajor": 2,
                  "differences": []
                }
                """);

        // A failed compilation withholds its output, so check the fresh IR directly
        ContractIr fresh = IrJson.parse(irOf(compile(fixture().with("shop.OrderService",
                "toolName = \"labelOrder\"", "toolName = \"tagOrder\"").sources(), MAJOR + M)), "api.ir.json");
        ContractGate.Outcome outcome = ContractGate.check(baseline.toString(), fresh);
        assertThat(outcome.failed()).isTrue();
        JsonNode differences = new ObjectMapper().readTree(outcome.diffJson()).get("differences");
        assertThat(differences).hasSize(1);
        JsonNode d = differences.get(0);
        assertThat(List.of(d.get("path").textValue(), d.get("change").textValue(), d.get("direction").textValue(),
                d.get("before").textValue(), d.get("after").textValue(), d.get("classification").textValue()))
                .containsExactly("operation shop.OrderService#label(java.lang.Long)", "toolName", "input",
                        "labelOrder", "tagOrder", "breaking");
    }

    // ---------------------------------------------------------------- openEnum

    @Test
    void openEnumOnAFieldWithoutValuesWarns() throws Exception {
        Compilation compilation = compile(fixture()
                .with("shop.Order", "@AgenticField(description = \"Id\")", "@AgenticField(description = \"Id\", openEnum = true)")
                .with("shop.Order", "@AgenticField(description = \"Status\")",
                        "@AgenticField(description = \"Status\", openEnum = true)")
                .with("shop.Order", "@AgenticField(description = \"Size\",", "@AgenticField(description = \"Size\", openEnum = true,")
                .sources(), MAJOR + M);

        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
        assertThat(compilation.warnings()).filteredOn(d -> d.getMessage(null).contains("openEnum"))
                .singleElement().satisfies(d -> assertThat(d.getMessage(null))
                        .contains("@AgenticField(openEnum = true) on field 'id' has no effect"));
        ContractIr ir = IrJson.parse(irOf(compilation), "api.ir.json");
        // The IR records the declaration as written, so 'id' keeps the flag despite the warning
        assertThat(ir.entities().stream().filter(e -> e.className().equals("shop.Order")).findFirst().orElseThrow()
                .fields()).filteredOn(ContractIr.Field::openEnum).extracting(ContractIr.Field::name)
                .containsExactly("id", "status", "size");
    }

    // ---------------------------------------------------------------- helpers

    /** Compiles {@code fixture} at M against the baseline. */
    private Compilation gate(Fixture fixture, String... options) {
        List<Object> all = new ArrayList<>(List.of(MAJOR + M, BASELINE + baseline));
        all.addAll(List.of(options));
        return javac().withProcessors(new AgenticProcessor()).withOptions(all).compile(fixture.sources());
    }

}
