/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Hints;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.egoge.ai.atlas.processor.contract.GateFixtures.BASELINE;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.MAJOR;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.M;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.assertPasses;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.compile;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract IR version 4: every operation's return records its effective {@code bound}, and every
 * API operation's {@code rest} mapping its success {@code status} and each parameter's location
 * in {@code parameterIn}. Until Phase 5 declares otherwise these are {@code NONE}/{@code NONE}, 200
 * and every parameter in the query. Version 1 to 3 baselines migrate to exactly those values, so
 * neither the gate nor lock mode sees a difference against the same contract; a version 4
 * document missing one of the slots is malformed.
 */
class IrVersion4Test {

    private static final List<String> API = List.of("API");
    private static final String LOCKED = "-A" + AgenticProcessor.OPT_CONTRACT_LOCKED + "=true";
    /** The bound of every operation today, as the document carries it. */
    private static final String NO_BOUND = """
            "bound": {
                      "style": "NONE",
                      "envelope": "NONE",
                      "limitParameter": null,
                      "cursorParameter": null,
                      "maxResults": null
                    }""";
    /** The mapping of {@code OrderService.label(Long)}, as the document carries it. */
    private static final String LABEL_REST = """
            "rest": {
                    "httpMethod": "POST",
                    "path": "/order-service/label",
                    "status": 200,
                    "parameterIn": [
                      "QUERY"
                    ]
                  }""";

    @TempDir
    Path dir;

    /** A version 4 document as version 3 wrote it: no bound, status or parameter locations. */
    static String asVersion3(String v4) {
        String v3 = v4.replace("\"irVersion\": 4", "\"irVersion\": 3")
                .replaceAll(",\n\\s*\"bound\": \\{[^}]*}", "")
                .replaceAll(",\n\\s*\"status\": \\d+,\n\\s*\"parameterIn\": \\[[^]]*]", "");
        assertThat(v3).contains("\"irVersion\": 3").doesNotContain("\"bound\"", "\"parameterIn\"")
                .doesNotContainPattern("\"status\": \\d");
        return v3;
    }

    @Test
    void theBuilderRecordsTodaysEffectiveValues() throws Exception {
        ContractIr ir = IrJson.parse(irOf(compile(GateFixtures.fixture().sources(), MAJOR + M)), "api.ir.json");

        assertThat(ir.operations()).isNotEmpty().allSatisfy(op -> {
            assertThat(op.returns().bound()).isEqualTo(Bound.NONE);
            assertThat(op.rest()).isNotNull();
            assertThat(op.rest().status()).isEqualTo(200);
            assertThat(op.rest().parameterIn()).isEqualTo(Collections.nCopies(op.parameters().size(), "QUERY"));
        });
    }

    @Test
    void anOperationOffTheApiChannelHasNoMappingButABound() throws Exception {
        String text = GateFixtures.fixture().files().get("shop.OrderService")
                .replace("toolName = \"labelOrder\")", "toolName = \"labelOrder\", channels = AgenticExposed.Channel.AI)");
        GateFixtures.Fixture fixture = GateFixtures.fixture();
        fixture.files().put("shop.OrderService", text);

        ContractIr ir = IrJson.parse(irOf(compile(fixture.sources(), MAJOR + M)), "api.ir.json");

        Operation label = ir.operations().stream().filter(op -> op.method().equals("label")).findFirst().orElseThrow();
        assertThat(label.rest()).isNull();
        assertThat(label.returns().bound()).isEqualTo(Bound.NONE);
    }

    @Test
    void v4IsDeterministicByteStableAndRoundTrips() throws Exception {
        String first = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));
        String second = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));

        assertThat(second.getBytes(StandardCharsets.UTF_8)).isEqualTo(first.getBytes(StandardCharsets.UTF_8));
        assertThat(first).startsWith("{\n  \"irVersion\": 4,\n").contains(LABEL_REST)
                // The bound closes the return, after its reference
                .contains("\"reference\": null,\n        " + NO_BOUND + "\n      },\n      \"hints\"");
        assertThat(IrJson.write(IrJson.parse(first, "api.ir.json"))).isEqualTo(first);
    }

    @Test
    void version1To3DocumentsMigrateExactlyToTodaysValues() throws Exception {
        String v4 = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));
        String v3 = asVersion3(v4);

        ContractIr migrated = IrJson.parse(v3, ".atlas/api.ir.json");

        assertThat(migrated).isEqualTo(IrJson.parse(v4, "api.ir.json"));
        assertThat(IrJson.write(migrated)).isEqualTo(v4);
        for (String document : List.of(IrVersion2Test.v1Document(), IrVersion2Test.v2Document(),
                IrVersion2Test.v3Document())) {
            Operation op = IrJson.parse(document, ".atlas/api.ir.json").operations().get(0);
            assertThat(op.returns().bound()).isEqualTo(Bound.NONE);
        }
    }

    @Test
    void aVersion3BaselinePassesTheGateAndLockModeAgainstTheSameContract() throws IOException {
        String v4 = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));
        Path baseline = Files.writeString(dir.resolve("api.ir.json"), asVersion3(v4), StandardCharsets.UTF_8);

        Compilation gated = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline).compile(GateFixtures.fixture().sources());
        Compilation locked = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline, LOCKED).compile(GateFixtures.fixture().sources());

        assertPasses(gated);
        assertPasses(locked);
        assertThat(irOf(locked)).isEqualTo(v4);
    }

    @Test
    void aVersion4DocumentMissingASlotIsMalformed() {
        String valid = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));
        String status = "\"status\": 200,";
        String in = "\"parameterIn\": [\n          \"QUERY\"\n        ]";
        assertThat(valid).contains(NO_BOUND, status, in);

        assertMalformed(valid.replace(",\n        " + NO_BOUND, ""), "missing 'bound'");
        assertMalformed(valid.replace(NO_BOUND, "\"bound\": null"), "'bound' must be an object");
        assertMalformed(valid.replace(NO_BOUND, NO_BOUND.replace("\"NONE\",", "null,")), "'style' must be a string");
        assertMalformed(valid.replace(NO_BOUND, NO_BOUND.replace(",\n          \"maxResults\": null", "")),
                "missing 'maxResults'");
        assertMalformed(valid.replace(NO_BOUND, NO_BOUND.replace("\"style\": \"NONE\"", "\"style\": \"OFFSET\"")),
                "'style' must be one of [PAGEABLE, LIMIT, DECLARED, NONE], got OFFSET");
        assertMalformed(valid.replace(NO_BOUND, NO_BOUND.replace("\"envelope\": \"NONE\"", "\"envelope\": \"LIST\"")),
                "'envelope' must be one of [PAGE, SLICE, NONE], got LIST");
        assertMalformed(valid.replace(NO_BOUND, NO_BOUND.replace("\"maxResults\": null", "\"maxResults\": 0")),
                "'maxResults' must be at least 1, got 0");
        assertMalformed(valid.replace(status, ""), "missing 'status'");
        assertMalformed(valid.replace(status, "\"status\": \"200\","), "'status' must be an integer");
        assertMalformed(valid.replace(status, "\"status\": 302,"), "must be a 2xx status, got 302");
        assertMalformed(valid.replace(status + "\n        " + in, "\"status\": 200"),
                "missing 'parameterIn'");
        assertMalformed(valid.replace(in, "\"parameterIn\": [\"HEADER\"]"),
                "must be one of [PATH, QUERY, BODY], got [HEADER]");
        assertMalformed(valid.replace(in, "\"parameterIn\": []"), "must locate each of its 1 parameter(s), got []");
    }

    @Test
    void theRpcMappingIsTwoHundredWithEveryParameterInTheQuery() {
        assertThat(Rest.rpc("POST", "/a/b", 2)).isEqualTo(new Rest("POST", "/a/b", 200, List.of("QUERY", "QUERY")));
        assertThat(Rest.rpc("GET", "/a/b", 0).parameterIn()).isEmpty();
        assertThat(new Rest("GET", "/orders/{id}", 200, List.of("PATH")).routeKey()).isEqualTo("GET /orders/{}");
    }

    @Test
    void aBoundContradictingItsStyleIsMalformed() {
        String valid = IrJson.write(document(List.of("API"), Rest.rpc("POST", "/orders/find", 3), Bound.NONE));
        assertThat(valid).contains(NO_BOUND);

        assertMalformed(valid.replace(NO_BOUND, bound("NONE", "\"limit\"", "null", "null")),
                "style NONE names no 'limitParameter' or 'cursorParameter'");
        assertMalformed(valid.replace(NO_BOUND, bound("NONE", "null", "\"after\"", "null")),
                "style NONE names no 'limitParameter' or 'cursorParameter'");
        assertMalformed(valid.replace(NO_BOUND, bound("LIMIT", "null", "null", "50")),
                "style LIMIT must name its 'limitParameter'");
        assertMalformed(valid.replace(NO_BOUND, bound("PAGEABLE", "\"limit\"", "\"after\"", "null")),
                "only style LIMIT names a 'cursorParameter', got style PAGEABLE");
        assertMalformed(valid.replace(NO_BOUND, bound("DECLARED", "null", "null", "null")),
                "style DECLARED must set 'maxResults'");
    }

    @Test
    void anOperationWhoseSlotsContradictEachOtherIsMalformed() {
        Rest byId = new Rest("GET", "/orders/{id}", 200, List.of("PATH", "QUERY", "QUERY"));
        Bound limit = new Bound("LIMIT", "NONE", "limit", "after", 50);

        assertMalformed(document(API, byId, new Bound("LIMIT", "NONE", "size", null, null)),
                "operation 'shop.OrderService#find(java.lang.Long,java.lang.Integer,java.lang.String)': the bound"
                        + " names 'size', which is not one of its parameters [id, limit, after]");
        assertMalformed(document(API, byId, new Bound("LIMIT", "NONE", "limit", "cursor", null)),
                "the bound names 'cursor'");
        assertMalformed(document(API, new Rest("GET", "/orders/{orderId}", 200, List.of("PATH", "QUERY", "QUERY")),
                limit), "PATH parameter 'id' has no {id} variable in the path /orders/{orderId}");
        assertMalformed(document(API, new Rest("GET", "/orders", 200, List.of("PATH", "QUERY", "QUERY")), limit),
                "PATH parameter 'id' has no {id} variable in the path /orders");
        assertMalformed(document(API, new Rest("POST", "/orders", 200, List.of("BODY", "QUERY", "BODY")), Bound.NONE),
                "more than one parameter is the BODY");
        assertMalformed(IrJson.write(document(API, byId, limit)).replace("\"API\"", "\"AI\""),
                "is not on the API channel, so 'rest' must be null");
        assertMalformed(IrJson.write(document(API, Rest.rpc("POST", "/orders/find", 3), Bound.NONE))
                        .replaceAll("\"rest\": \\{[^}]*}", "\"rest\": null"),
                "is on the API channel, so 'rest' must not be null");
    }

    @Test
    void aConsistentMappingAndBoundRoundTrip() throws Exception {
        ContractIr ir = document(API, new Rest("GET", "/orders/{id}", 200, List.of("PATH", "QUERY", "QUERY")),
                new Bound("LIMIT", "NONE", "limit", "after", 50));
        ContractIr body = document(API, new Rest("POST", "/orders", 201, List.of("BODY", "QUERY", "QUERY")),
                new Bound("DECLARED", "NONE", null, null, 10));

        assertThat(IrJson.parse(IrJson.write(ir), "api.ir.json")).isEqualTo(ir);
        assertThat(IrJson.parse(IrJson.write(body), "api.ir.json")).isEqualTo(body);
    }

    @Test
    void version1And2DocumentsWithApiOperationsMigrateToTheRpcMapping() throws Exception {
        Rest rpc = Rest.rpc("POST", "/orders/find", 3);
        String v3 = asVersion3(IrJson.write(document(List.of("AI", "API"), rpc, Bound.NONE)));
        String v2 = v3.replace("\"irVersion\": 3", "\"irVersion\": 2");
        String v1 = v2.replace("\"irVersion\": 2", "\"irVersion\": 1")
                .replaceAll(",\n\\s*\"required\": true,\n\\s*\"constraints\": \\{}", "")
                .replaceAll("\n\\s*\"hints\": \\{},", "");
        assertThat(v1).contains("\"irVersion\": 1").doesNotContain("\"required\"", "\"hints\"");

        for (String document : List.of(v1, v2, v3)) {
            Operation op = IrJson.parse(document, ".atlas/api.ir.json").operations().get(0);
            assertThat(op.rest()).isEqualTo(rpc);
            assertThat(op.returns().bound()).isEqualTo(Bound.NONE);
        }
    }

    /** {@link #NO_BOUND} with the given JSON values. */
    private static String bound(String style, String limitParameter, String cursorParameter, String maxResults) {
        return NO_BOUND.replace("\"style\": \"NONE\"", "\"style\": \"" + style + "\"")
                .replace("\"limitParameter\": null", "\"limitParameter\": " + limitParameter)
                .replace("\"cursorParameter\": null", "\"cursorParameter\": " + cursorParameter)
                .replace("\"maxResults\": null", "\"maxResults\": " + maxResults);
    }

    /** A document of {@code OrderService.find(Long id, Integer limit, String after)}, without entities. */
    private static ContractIr document(List<String> channels, Rest rest, Bound bound) {
        List<Parameter> parameters = new ArrayList<>();
        for (String[] p : new String[][] {{"id", "java.lang.Long"}, {"limit", "java.lang.Integer"},
                {"after", "java.lang.String"}}) {
            parameters.add(new Parameter(p[0], p[1], "", List.of(), true, EffectiveConstraints.NONE));
        }
        Operation op = new Operation("shop.OrderService", "find", "find", channels, "Find", rest, parameters,
                new Return("java.util.List<java.lang.String>", "COLLECTION", null, null, bound), Hints.NONE,
                new OperationLifecycle(1, Integer.MAX_VALUE, 0, ""));
        return new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(op));
    }

    private static void assertMalformed(ContractIr ir, String detail) {
        assertMalformed(IrJson.write(ir), detail);
    }

    private static void assertMalformed(String json, String detail) {
        assertThatThrownBy(() -> IrJson.parse(json, "api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("is not valid Contract IR JSON")
                .hasMessageContaining(detail);
    }
}
