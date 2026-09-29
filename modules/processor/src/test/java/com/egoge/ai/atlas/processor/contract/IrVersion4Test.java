/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static void assertMalformed(String json, String detail) {
        assertThatThrownBy(() -> IrJson.parse(json, "api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("is not valid Contract IR JSON")
                .hasMessageContaining(detail);
    }
}
