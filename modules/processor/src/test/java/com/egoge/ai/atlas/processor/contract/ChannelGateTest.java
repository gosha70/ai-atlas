/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.GateFixtures.Fixture;
import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static com.egoge.ai.atlas.processor.contract.GateFixtures.errors;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.irOf;
import static com.egoge.ai.atlas.processor.contract.GateFixtures.singleError;
import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The gate's per-channel rules, for fields active at the published major M: a field losing a
 * channel is a breaking output change when its entity is reachable, through a chain of fields on
 * that channel, from an operation active on it, and compatible otherwise; gaining a channel is
 * compatible; an AI record appearing or disappearing is informational; and lock mode sees every
 * channel change.
 */
class ChannelGateTest {

    private static final String PROJECTIONS = "-A" + AgenticProcessor.OPT_PROJECTIONS + "=true";
    private static final String LOCKED = "-A" + AgenticProcessor.OPT_CONTRACT_LOCKED + "=true";
    private static final String MARGIN = "@AgenticField(description = \"Margin\")";
    private static final String NOTE = "@AgenticField(description = \"Note\")";
    private static final String ACTIONS = "@AgenticField(description = \"Actions\")";
    private static final String NAME = "@AgenticField(description = \"Name\")";

    /** Order and its actions are served on both channels; Customer only on the API channel. */
    private static final Map<String, String> SHOP = Map.of(
            "shop.Order", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                    import java.util.List;
                    @AgenticEntity(description = "An order")
                    public class Order {
                        @AgenticField(description = "Id") private Long id;
                        @AgenticField(description = "Margin") private Long margin;
                        @AgenticField(description = "Actions") private List<OrderAction> actions;
                        public Long getId() { return id; }
                        public Long getMargin() { return margin; }
                        public List<OrderAction> getActions() { return actions; }
                    }
                    """,
            "shop.OrderAction", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                    @AgenticEntity(description = "An action")
                    public class OrderAction {
                        @AgenticField(description = "Id") private Long id;
                        @AgenticField(description = "Note") private String note;
                        public Long getId() { return id; }
                        public String getNote() { return note; }
                    }
                    """,
            "shop.Customer", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                    @AgenticEntity(description = "A customer")
                    public class Customer {
                        @AgenticField(description = "Id") private Long id;
                        @AgenticField(description = "Name") private String name;
                        public Long getId() { return id; }
                        public String getName() { return name; }
                    }
                    """,
            "shop.OrderService", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    public class OrderService {
                        @AgenticExposed(description = "An order", returnType = Order.class)
                        public Order find(Long id) { return null; }
                    }
                    """,
            "shop.CustomerService", """
                    package shop;
                    import com.egoge.ai.atlas.annotations.*;
                    import com.egoge.ai.atlas.annotations.AgenticExposed.Channel;
                    public class CustomerService {
                        @AgenticExposed(description = "A customer", returnType = Customer.class, channels = Channel.API)
                        public Customer find(Long id) { return null; }
                    }
                    """);

    @TempDir
    Path dir;

    @Test
    void aFieldLosingAChannelAnOperationServesIsABreakingOutputChange() throws Exception {
        Path baseline = baseline(shop());

        Compilation compilation = gate(shop().with("shop.Order", MARGIN, api(MARGIN)), baseline);

        assertThat(singleError(compilation)).isEqualTo("[ai-atlas] Breaking contract change to field"
                + " shop.Order#margin: channels.AI [AI, API] → [API] (output). Clients of the AI channel no longer"
                + " receive the field, which breaks clients of major 2. To make it legitimate, publish it in a new"
                + " major (ai.atlas.api.major = 3, then atlasAccept), as a field's channels have no lifecycle of"
                + " their own; or accept the change explicitly with atlasAccept.");
        assertThat(compilation.errors().get(0).getSource().getName()).endsWith("shop/Order.java");
    }

    @Test
    void aFieldReachableThroughAChainOfFieldsOnTheChannelIsBreaking() throws Exception {
        List<Difference> differences = compare(shop(), shop().with("shop.OrderAction", NOTE, api(NOTE)));

        assertThat(channelDifferences(differences)).containsExactly(new Difference("field shop.OrderAction#note",
                "channels.AI", Direction.OUTPUT, "[AI, API]", "[API]", Classification.BREAKING,
                "Clients of the AI channel no longer receive the field",
                "publish it in a new major (ai.atlas.api.major = 3, then atlasAccept), as a field's channels have"
                        + " no lifecycle of their own"));
    }

    @Test
    void aChainThroughAFieldOffTheChannelDoesNotReachTheEntity() throws Exception {
        Fixture apiActions = shop().with("shop.Order", ACTIONS, api(ACTIONS));

        List<Difference> differences = compare(apiActions,
                shop().with("shop.Order", ACTIONS, api(ACTIONS)).with("shop.OrderAction", NOTE, api(NOTE)));

        assertThat(channelDifferences(differences)).extracting(Difference::path, Difference::classification)
                .containsExactly(tuple("field shop.OrderAction#note", Classification.COMPATIBLE));
    }

    @Test
    void aFieldReachableOnlyThroughAReferenceCycleIsBreaking() throws Exception {
        List<Difference> differences = compare(cyclicShop(), cyclicShop().with("shop.OrderAction", NOTE, api(NOTE)));

        assertThat(channelDifferences(differences)).extracting(Difference::path, Difference::change,
                        Difference::classification)
                .containsExactly(tuple("field shop.OrderAction#note", "channels.AI", Classification.BREAKING));
    }

    @Test
    void aReferenceCycleOffTheChannelDoesNotReachTheEntity() throws Exception {
        Fixture apiActions = cyclicShop().with("shop.Order", ACTIONS, api(ACTIONS));

        List<Difference> differences = compare(apiActions,
                cyclicShop().with("shop.Order", ACTIONS, api(ACTIONS)).with("shop.OrderAction", NOTE, api(NOTE)));

        assertThat(channelDifferences(differences)).extracting(Difference::path, Difference::classification)
                .containsExactly(tuple("field shop.OrderAction#note", Classification.COMPATIBLE));
    }

    @Test
    void aFieldOfAnEntityAnOperationReturnsInACollectionIterableOrArrayIsBreaking() throws Exception {
        for (String returns : List.of("java.util.List<Order>", "Iterable<Order>", "Order[]")) {
            Fixture before = shop().with("shop.OrderService", "public Order find", "public " + returns + " find");

            List<Difference> differences = compare(before,
                    shop().with("shop.OrderService", "public Order find", "public " + returns + " find")
                            .with("shop.Order", MARGIN, api(MARGIN)));

            assertThat(channelDifferences(differences)).as(returns)
                    .extracting(Difference::path, Difference::change, Difference::classification)
                    .containsExactly(tuple("field shop.Order#margin", "channels.AI", Classification.BREAKING));
        }
    }

    @Test
    void aFieldLosingTheApiChannelAnOperationServesIsBreaking() throws Exception {
        List<Difference> differences = compare(shop(), shop().with("shop.Order", MARGIN, ai(MARGIN)));

        assertThat(channelDifferences(differences)).containsExactly(new Difference("field shop.Order#margin",
                "channels.API", Direction.OUTPUT, "[AI, API]", "[AI]", Classification.BREAKING,
                "Clients of the API channel no longer receive the field",
                "publish it in a new major (ai.atlas.api.major = 3, then atlasAccept), as a field's channels have"
                        + " no lifecycle of their own"));
    }

    @Test
    void aFieldLosingTheApiChannelNoOperationServesLosesItCompatibly() throws Exception {
        Fixture agentOrders = shop().with("shop.OrderService", "returnType = Order.class)",
                "returnType = Order.class, channels = AgenticExposed.Channel.AI)");

        List<Difference> differences = compare(agentOrders, shop().with("shop.OrderService",
                "returnType = Order.class)", "returnType = Order.class, channels = AgenticExposed.Channel.AI)")
                .with("shop.Order", MARGIN, ai(MARGIN)));

        assertThat(channelDifferences(differences)).extracting(Difference::path, Difference::change,
                        Difference::classification)
                .containsExactly(tuple("field shop.Order#margin", "channels.API", Classification.COMPATIBLE));
    }

    @Test
    void aFieldOfAnEntityNoOperationOnTheChannelServesLosesItCompatibly() throws Exception {
        Path baseline = baseline(shop());

        List<Difference> differences = ContractGate.compare(IrJson.read(baseline),
                IrJson.parse(irOf(compile(shop().with("shop.Customer", NAME, api(NAME)).sources(), MAJOR + M,
                        PROJECTIONS)), "fresh"));

        assertThat(channelDifferences(differences)).containsExactly(new Difference("field shop.Customer#name",
                "channels.AI", Direction.OUTPUT, "[AI, API]", "[API]", Classification.COMPATIBLE, null, null));
        assertPasses(gate(shop().with("shop.Customer", NAME, api(NAME)), baseline));
    }

    @Test
    void aFieldGainingAChannelIsCompatible() throws Exception {
        Path baseline = baseline(shop().with("shop.Order", MARGIN, api(MARGIN)));

        List<Difference> differences = ContractGate.compare(IrJson.read(baseline),
                IrJson.parse(irOf(compile(shop().sources(), MAJOR + M, PROJECTIONS)), "fresh"));

        assertThat(channelDifferences(differences)).containsExactly(new Difference("field shop.Order#margin",
                "channels.AI", Direction.OUTPUT, "[API]", "[AI, API]", Classification.COMPATIBLE, null, null));
        assertPasses(gate(shop(), baseline));
    }

    @Test
    void anAiRecordAppearingOrDisappearingIsInformational() throws Exception {
        Fixture split = shop().with("shop.OrderAction", NOTE, api(NOTE));

        List<Difference> appears = compare(shop(), split);
        List<Difference> disappears = compare(split, shop());

        // OrderAction splits on its own, Order through its actions; Customer never does
        assertThat(aiRecordDifferences(appears)).containsExactly(
                new Difference("entity shop.Order", "aiRecord", Direction.OUTPUT, "shared", "separate",
                        Classification.INFORMATIONAL, null, null),
                new Difference("entity shop.OrderAction", "aiRecord", Direction.OUTPUT, "shared", "separate",
                        Classification.INFORMATIONAL, null, null));
        assertThat(aiRecordDifferences(disappears)).extracting(Difference::before, Difference::after)
                .containsOnly(tuple("separate", "shared"));
        assertThat(aiRecordDifferences(disappears)).hasSize(2);
    }

    @Test
    void theApiRecordNameKeepsTheBreakingDtoNameRule() throws Exception {
        Path baseline = baseline(shop());

        Compilation compilation = gate(shop().with("shop.Order", "@AgenticEntity(description = \"An order\")",
                "@AgenticEntity(description = \"An order\", dtoName = \"OrderView\")"), baseline);

        assertThat(errors(compilation)).anySatisfy(error -> assertThat(error).contains("Breaking contract change"
                + " to entity shop.Order: dtoName OrderDto → OrderView (output)"));
    }

    @Test
    void turningTheOptionOnWithADeclarationIsGatedAgainstABaselineWrittenWithItOff() throws Exception {
        Path baseline = Files.writeString(dir.resolve("api.ir.json"), irOf(compile(shop().sources(), MAJOR + M)),
                StandardCharsets.UTF_8);

        Compilation compilation = gate(shop().with("shop.Order", MARGIN, api(MARGIN)), baseline);

        assertThat(singleError(compilation)).contains("field shop.Order#margin: channels.AI [AI, API] → [API]");
    }

    @Test
    void lockModeFailsOnEveryChannelChangeAndPassesWithoutOne() throws Exception {
        Path baseline = baseline(shop().with("shop.Order", MARGIN, api(MARGIN)));

        Compilation gained = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline, PROJECTIONS, LOCKED).compile(shop().sources());
        Compilation same = javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline, PROJECTIONS, LOCKED)
                .compile(shop().with("shop.Order", MARGIN, api(MARGIN)).sources());

        assertThat(singleError(gained)).contains("Lock mode (ai.atlas.contract.locked=true)")
                .contains("at 1 element(s): field shop.Order#margin");
        assertPasses(same);
    }

    // ------------------------------------------------------------ helpers

    private static String api(String annotation) {
        return annotation.replace(")", ", channels = Channel.API)");
    }

    private static String ai(String annotation) {
        return annotation.replace(")", ", channels = Channel.AI)");
    }

    private static Fixture shop() {
        return new Fixture(new LinkedHashMap<>(SHOP));
    }

    /** The shop with each action referring back to its order: Order and OrderAction form a cycle. */
    private static Fixture cyclicShop() {
        return shop().with("shop.OrderAction", "public Long getId()",
                "@AgenticField(description = \"Parent\") private Order order;\n    public Order getOrder() { return order; }\n    public Long getId()");
    }

    private Path baseline(Fixture fixture) throws IOException {
        return Files.writeString(dir.resolve("api.ir.json"),
                irOf(compile(fixture.sources(), MAJOR + M, PROJECTIONS)), StandardCharsets.UTF_8);
    }

    private static Compilation gate(Fixture fixture, Path baseline) {
        return javac().withProcessors(new AgenticProcessor())
                .withOptions(MAJOR + M, BASELINE + baseline, PROJECTIONS).compile(fixture.sources());
    }

    private static List<Difference> compare(Fixture before, Fixture after) throws IrJson.IrReadException {
        return ContractGate.compare(IrJson.parse(irOf(compile(before.sources(), MAJOR + M, PROJECTIONS)), "a"),
                IrJson.parse(irOf(compile(after.sources(), MAJOR + M, PROJECTIONS)), "b"));
    }

    private static List<Difference> channelDifferences(List<Difference> differences) {
        return differences.stream().filter(d -> d.change().startsWith("channels.")).toList();
    }

    private static List<Difference> aiRecordDifferences(List<Difference> differences) {
        return differences.stream().filter(d -> d.change().equals("aiRecord")).toList();
    }
}
