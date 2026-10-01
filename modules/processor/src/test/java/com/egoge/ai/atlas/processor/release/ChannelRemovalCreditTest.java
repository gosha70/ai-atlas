/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.pending;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.release;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field or operation loses a channel credit only from published releases where it was, at each
 * release's own major, active, deprecated <strong>and visible on that channel</strong>
 * ({@link ChannelReachability}, D4.7, D4.9, AC10, AC11). Reproduces the review finding on
 * {@code bb98010} (finding 3, P2): the path-only evidence let a field earn AI-channel removal
 * credit from a release that deprecated it while it was API-only, so AI clients never received it
 * deprecated.
 */
class ChannelRemovalCreditTest {

    private static final FieldLifecycle ACTIVE = new FieldLifecycle(1, Integer.MAX_VALUE, 0, null);
    private static final OperationLifecycle OP_ACTIVE = new OperationLifecycle(1, Integer.MAX_VALUE, 0, null);

    /**
     * The reviewer's reproduction ({@code /tmp/ReviewChannelCreditTest.java}), added nearly
     * verbatim: only the comparison call becomes {@link ReleaseComparison#compare}, since
     * {@code ContractGate.compareReleases} no longer exists (it was replaced in A2). A field
     * deprecated while API-only, later exposed to AI without deprecation, then loses AI access in
     * major 2: AI clients never received it deprecated, so the removal must not pass.
     */
    @Test
    void invisibleDeprecationMustNotEarnAiRemovalCredit() {
        var old = ReleaseFixtures.parse(ReleaseFixtures.irJson(1,
                ReleaseFixtures.LEGACY.formatted(", deprecatedSinceVersion = 1, channels = AgenticExposed.Channel.API"),
                "", ReleaseFixtures.PROJECTIONS));
        var exposed = ReleaseFixtures.parse(ReleaseFixtures.irJson(1,
                ReleaseFixtures.LEGACY.formatted(""), "", ReleaseFixtures.PROJECTIONS));
        var removed = ReleaseFixtures.parse(ReleaseFixtures.irJson(2,
                ReleaseFixtures.LEGACY.formatted(", channels = AgenticExposed.Channel.API"), "",
                ReleaseFixtures.PROJECTIONS));

        var result = ReleasePolicy.check(List.of(release("1.0.0", old), release("1.1.0", exposed),
                release("2.0.0", removed)), ReleaseComparison.compare(exposed, removed), ReleasePolicy.Policy.DEFAULT);

        assertThat(result.passed()).as("AI clients never received a deprecated field").isFalse();
    }

    /** The same shape, but the field was visible on AI while deprecated: the removal passes. */
    @Test
    void creditFromAVisibleDeprecatedReleasePasses() {
        var deprecated = ReleaseFixtures.parse(ReleaseFixtures.irJson(1,
                ReleaseFixtures.LEGACY.formatted(", deprecatedSinceVersion = 1"), "", ReleaseFixtures.PROJECTIONS));
        var removed = ReleaseFixtures.parse(ReleaseFixtures.irJson(2,
                ReleaseFixtures.LEGACY.formatted(", channels = AgenticExposed.Channel.API"), "",
                ReleaseFixtures.PROJECTIONS));

        var result = ReleasePolicy.check(List.of(release("1.0.0", deprecated), release("2.0.0", removed)),
                ReleaseComparison.compare(deprecated, removed), ReleasePolicy.Policy.DEFAULT);

        assertThat(result.passed()).as(result.violations().toString()).isTrue();
    }

    /**
     * The field itself lists the channel and is deprecated, but its entity is reachable only
     * through an intermediate field that excludes the channel: never visible, so no credit.
     */
    @Test
    void noCreditWhenAnIntermediateFieldExcludesTheChannel() {
        Entity address = entity("test.Address", List.of());
        Entity customer = entity("test.Customer", List.of(
                field("email", null, List.of("AI", "API"), new FieldLifecycle(1, Integer.MAX_VALUE, 1, null))));
        // Order.customer is API-only, so Customer (and its email field) is never reachable on AI.
        Entity order = entity("test.Order", List.of(field("customer", ref(customer), List.of("API"), ACTIVE)));
        Operation find = operation("find", ref(order), List.of("AI", "API"), OP_ACTIVE);
        ContractIr deprecated = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(order, customer, address),
                List.of(find));
        ContractIr removed = new ContractIr(ContractIr.IR_VERSION, "/api", 2,
                List.of(order, withFieldChannels(customer, "email", List.of("API")), address), List.of(find));

        ContractGate.Difference diff = channelLoss("field test.Customer#email", "AI", "[AI, API]", "[API]");
        var result = ReleasePolicy.check(List.of(release("1.0.0", deprecated), release("2.0.0", removed)),
                List.of(diff), ReleasePolicy.Policy.DEFAULT);

        assertThat(result.passed()).as("Customer is never reachable on AI").isFalse();
    }

    /** The field lists the channel and is deprecated, but no operation is active on that channel at all. */
    @Test
    void noCreditWhenTheOperationDoesNotListTheChannel() {
        Entity order = entity("test.Order",
                List.of(field("legacy", null, List.of("AI", "API"), new FieldLifecycle(1, Integer.MAX_VALUE, 1, null))));
        Operation find = operation("find", ref(order), List.of("API"), OP_ACTIVE);
        ContractIr deprecated = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(order), List.of(find));
        ContractIr removed = new ContractIr(ContractIr.IR_VERSION, "/api", 2,
                List.of(entity("test.Order", List.of(field("legacy", null, List.of("API"), ACTIVE)))), List.of(find));

        ContractGate.Difference diff = channelLoss("field test.Order#legacy", "AI", "[AI, API]", "[API]");
        var result = ReleasePolicy.check(List.of(release("1.0.0", deprecated), release("2.0.0", removed)),
                List.of(diff), ReleasePolicy.Policy.DEFAULT);

        assertThat(result.passed()).as("find is never active on AI").isFalse();
    }

    /** A pending (untagged) release is part of the history chain, but earns no credit. */
    @Test
    void noCreditFromAPendingRelease() {
        Entity order = entity("test.Order",
                List.of(field("legacy", null, List.of("AI", "API"), new FieldLifecycle(1, Integer.MAX_VALUE, 1, null))));
        Operation find = operation("find", ref(order), List.of("AI", "API"), OP_ACTIVE);
        ContractIr deprecated = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(order), List.of(find));
        ContractIr removed = new ContractIr(ContractIr.IR_VERSION, "/api", 2,
                List.of(entity("test.Order", List.of(field("legacy", null, List.of("API"), ACTIVE)))), List.of(find));

        ContractGate.Difference diff = channelLoss("field test.Order#legacy", "AI", "[AI, API]", "[API]");
        var result = ReleasePolicy.check(List.of(pending("1.0.0", deprecated), release("2.0.0", removed)),
                List.of(diff), ReleasePolicy.Policy.DEFAULT);

        assertThat(result.passed()).as("the only deprecated release is pending").isFalse();
    }

    /** An operation losing two channels passes only when every one of them has credit; the violation names the one that does not. */
    @Test
    void anOperationLosingTwoChannelsNamesTheFailingChannel() {
        // "list" was deprecated and listed on AI in a published release, but never listed on API while deprecated.
        Operation listedOnAiOnly = operation("list", List.of("AI"), new OperationLifecycle(1, Integer.MAX_VALUE, 1, null));
        ContractIr deprecated = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(listedOnAiOnly));
        Operation gone = operation("list", List.of(), new OperationLifecycle(1, 2, 1, null));
        ContractIr removed = new ContractIr(ContractIr.IR_VERSION, "/api", 2, List.of(), List.of(gone));

        ContractGate.Difference diff = new ContractGate.Difference("operation test.OrderService#list()", "channels",
                ContractGate.Direction.INPUT, "[AI, API]", "[]", ContractGate.Classification.BREAKING,
                "Clients of the [AI, API] channel lose the operation", "restore it");
        var result = ReleasePolicy.check(List.of(release("1.0.0", deprecated), release("2.0.0", removed)),
                List.of(diff), ReleasePolicy.Policy.DEFAULT);

        assertThat(result.violations()).singleElement()
                .extracting(ReleasePolicy.Violation::change).isEqualTo("channels.API");
    }

    // ------------------------------------------------------------ fixture helpers

    private static ContractGate.Difference channelLoss(String path, String channel, String before, String after) {
        return new ContractGate.Difference(path, "channels." + channel, ContractGate.Direction.OUTPUT, before, after,
                ContractGate.Classification.BREAKING, "Clients of the " + channel + " channel no longer receive the"
                + " field", "restore it");
    }

    private static Entity entity(String className, List<Field> fields) {
        return new Entity(className, className.substring(className.lastIndexOf('.') + 1) + "Dto", "generated",
                className, "", false, fields);
    }

    private static Entity withFieldChannels(Entity entity, String fieldName, List<String> channels) {
        List<Field> fields = entity.fields().stream()
                .map(f -> f.name().equals(fieldName) ? withChannels(f, channels) : f).toList();
        return new Entity(entity.className(), entity.dtoName(), entity.dtoPackage(), entity.displayName(),
                entity.description(), entity.includeTypeInfo(), fields);
    }

    private static Field withChannels(Field field, List<String> channels) {
        return new Field(field.name(), field.displayName(), field.javaType(), field.collectionKind(),
                field.elementType(), field.typeHint(), field.reference(), field.enumType(), field.allowedValues(),
                field.openEnum(), field.sensitive(), field.checkCircularReference(), field.description(),
                field.constraints(), channels, field.lifecycle());
    }

    private static Field field(String name, TypeRef reference, List<String> channels, FieldLifecycle lifecycle) {
        return new Field(name, name, "java.lang.String", "NONE", null, null, reference, false, List.of(), false,
                false, false, "", null, channels, lifecycle);
    }

    private static TypeRef ref(Entity entity) {
        return new TypeRef(entity.className(), entity.dtoPackage() + "." + entity.dtoName());
    }

    private static Operation operation(String method, TypeRef returns, List<String> channels,
                                       OperationLifecycle lifecycle) {
        Return ret = new Return(returns.entity(), "NONE", returns.dto(), returns, Bound.NONE);
        return new Operation("test.OrderService", method, method, channels, "", null, List.of(), ret, null,
                lifecycle);
    }

    private static Operation operation(String method, List<String> channels, OperationLifecycle lifecycle) {
        Return ret = new Return("void", "NONE", null, null, Bound.NONE);
        return new Operation("test.OrderService", method, method, channels, "", null, List.of(), ret, null,
                lifecycle);
    }
}
