/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * Contract IR version 3: every field records its effective channels, sorted and always present,
 * and the document never records an AI record's name. Version 1 and 2 baselines migrate exactly,
 * every field to {@code [AI, API]}, so neither the gate nor lock mode sees a difference against
 * the same contract; a version 3 document without a field's channels is malformed.
 */
class IrVersion3Test {

    private static final String PROJECTIONS = "-A" + AgenticProcessor.OPT_PROJECTIONS + "=true";
    private static final String LOCKED = "-A" + AgenticProcessor.OPT_CONTRACT_LOCKED + "=true";
    /** A field's every-channel slot, as a document's entity field carries it. */
    private static final String CHANNELS_OF_ONE_FIELD =
            "\"channels\": [\n            \"AI\",\n            \"API\"\n          ]";

    private static final JavaFileObject ORDER = JavaFileObjects.forSourceString("shop.Order", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
            @AgenticEntity(description = "An order", aiDtoName = "OrderForAgents")
            public class Order {
                @AgenticField(description = "Id") private Long id;
                @AgenticField(description = "Margin", channels = Channel.API) private Long margin;
                @AgenticField(description = "Summary", channels = Channel.AI) private String summary;
                @AgenticField(description = "Both", channels = { Channel.API, Channel.AI }) private String both;
                public Long getId() { return id; }
                public Long getMargin() { return margin; }
                public String getSummary() { return summary; }
                public String getBoth() { return both; }
            }
            """);
    private static final JavaFileObject SERVICE = JavaFileObjects.forSourceString("shop.OrderService", """
            package shop;
            import com.egoge.ai.atlas.annotations.*;
            public class OrderService {
                @AgenticExposed(description = "An order", returnType = Order.class)
                public Order find(Long id) { return null; }
            }
            """);

    @TempDir
    Path dir;

    @Test
    void everyFieldRecordsItsEffectiveChannelsSorted() throws Exception {
        ContractIr ir = IrJson.parse(irOf(compile(List.of(ORDER, SERVICE), PROJECTIONS)), "api.ir.json");

        assertThat(ir.irVersion()).isEqualTo(3);
        assertThat(channels(ir.entities().get(0))).containsExactly(
                Map.entry("id", List.of("AI", "API")),
                Map.entry("margin", List.of("API")),
                Map.entry("summary", List.of("AI")),
                Map.entry("both", List.of("AI", "API")));
    }

    @Test
    void withTheOptionOffEveryFieldRecordsEveryChannel() throws Exception {
        String ir = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));

        for (Entity entity : IrJson.parse(ir, "api.ir.json").entities()) {
            assertThat(entity.fields()).allSatisfy(field -> assertThat(field.channels())
                    .isEqualTo(Field.EVERY_CHANNEL));
        }
        assertThat(ir).contains(CHANNELS_OF_ONE_FIELD);
    }

    @Test
    void theIrRecordsTheDeclarationAndNeverTheAiRecordName() {
        String ir = irOf(compile(List.of(ORDER, SERVICE), PROJECTIONS));

        assertThat(ir).doesNotContain("OrderForAgents").doesNotContain("AiDto")
                .contains("\"dto\": \"shop.generated.OrderDto\"");
    }

    @Test
    void v3IsDeterministicByteStableAndRoundTrips() throws Exception {
        String first = irOf(compile(List.of(ORDER, SERVICE), PROJECTIONS));
        String second = irOf(compile(List.of(ORDER, SERVICE), PROJECTIONS));

        assertThat(second.getBytes(StandardCharsets.UTF_8)).isEqualTo(first.getBytes(StandardCharsets.UTF_8));
        assertThat(first).startsWith("{\n  \"irVersion\": 3,\n");
        assertThat(IrJson.write(IrJson.parse(first, "api.ir.json"))).isEqualTo(first);
        // The slot sits between the constraints and the lifecycle
        assertThat(first).containsPattern("\"constraints\": \\{},\n\\s*\"channels\": \\[\n\\s*\"API\"\n\\s*],\n"
                + "\\s*\"lifecycle\"");
    }

    @Test
    void version1And2DocumentsMigrateEveryFieldToEveryChannel() throws Exception {
        for (String document : List.of(IrVersion2Test.v1Document(), IrVersion2Test.v2Document())) {
            assertThat(document).doesNotContain(CHANNELS_OF_ONE_FIELD);

            ContractIr migrated = IrJson.parse(document, ".atlas/api.ir.json");

            assertThat(migrated.irVersion()).isEqualTo(ContractIr.IR_VERSION);
            assertThat(migrated.entities().get(0).fields().get(0).channels()).containsExactly("AI", "API");
            assertThat(IrJson.write(migrated)).startsWith("{\n  \"irVersion\": 3,\n").contains(CHANNELS_OF_ONE_FIELD);
        }
    }

    @Test
    void aVersion3DocumentWithoutValidChannelsIsMalformed() {
        String valid = IrVersion2Test.v2Document().replace("\"irVersion\": 2", "\"irVersion\": 3")
                .replace("\"constraints\": {},\n          \"lifecycle\"",
                        "\"constraints\": {},\n          " + CHANNELS_OF_ONE_FIELD + ",\n          \"lifecycle\"");
        assertThat(valid).contains(CHANNELS_OF_ONE_FIELD);

        assertMalformed(valid.replace(CHANNELS_OF_ONE_FIELD + ",", ""), "missing 'channels'");
        assertMalformed(valid.replace(CHANNELS_OF_ONE_FIELD, "\"channels\": null"), "'channels' must be an array");
        String invalid = "'channels' of field 'name' must be a non-empty, sorted list of distinct channels among"
                + " [AI, API], got ";
        assertMalformed(valid.replace(CHANNELS_OF_ONE_FIELD, "\"channels\": []"), invalid + "[]");
        assertMalformed(valid.replace(CHANNELS_OF_ONE_FIELD, "\"channels\": [\"API\", \"AI\"]"), invalid + "[API, AI]");
        assertMalformed(valid.replace(CHANNELS_OF_ONE_FIELD, "\"channels\": [\"AI\", \"AI\"]"), invalid + "[AI, AI]");
        assertMalformed(valid.replace(CHANNELS_OF_ONE_FIELD, "\"channels\": [\"WEB\"]"), invalid + "[WEB]");
    }

    @Test
    void aVersion2BaselinePassesTheGateAndLockModeAgainstTheSameContract() throws IOException {
        String v3 = irOf(compile(GateFixtures.fixture().sources(), MAJOR + M));
        String v2 = v3.replace("\"irVersion\": 3", "\"irVersion\": 2")
                .replaceAll("\n\\s*\"channels\": \\[\n\\s*\"AI\",\n\\s*\"API\"\n\\s*],\n(\\s*)\"lifecycle\"",
                        "\n$1\"lifecycle\"");
        assertThat(v2).contains("\"irVersion\": 2").doesNotContainPattern("\"channels\": \\[[^\\]]*],\n\\s*\"lifecycle\"");
        Path baseline = Files.writeString(dir.resolve("api.ir.json"), v2, StandardCharsets.UTF_8);

        Compilation locked = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline, LOCKED).compile(GateFixtures.fixture().sources());

        assertPasses(locked);
        assertThat(irOf(locked)).isEqualTo(v3);
    }

    private static Map<String, List<String>> channels(Entity entity) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        entity.fields().forEach(field -> result.put(field.name(), field.channels()));
        return result;
    }

    private static void assertMalformed(String json, String detail) {
        assertThatThrownBy(() -> IrJson.parse(json, "api.ir.json"))
                .isInstanceOf(IrJson.IrReadException.class)
                .hasMessageContaining("is not valid Contract IR JSON")
                .hasMessageContaining(detail);
    }
}
