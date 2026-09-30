/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.release;

import com.egoge.ai.atlas.processor.contract.ContractGate;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractIr;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Entity;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.FieldLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import com.egoge.ai.atlas.processor.contract.ContractIr.TypeRef;
import com.egoge.ai.atlas.processor.contract.EmptyContract;
import com.egoge.ai.atlas.processor.contract.ReleaseComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.LEGACY;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.NOTE;
import static com.egoge.ai.atlas.processor.release.ReleaseFixtures.ir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link ReleaseComparison#compare}: the gate's own comparison, over what each release published
 * at its own major.
 */
class ReleaseComparisonTest {

    private static final String DECLARED_REMOVAL = LEGACY.formatted(", deprecatedSinceVersion = 1,"
            + " removedInVersion = 2, deprecatedMessage = \"Use id\"");

    @Test
    void aDeclaredRemovalAcrossAMajorIsARemoval() {
        ContractIr previous = ir(1, DECLARED_REMOVAL);
        ContractIr current = ir(2, DECLARED_REMOVAL);

        // At the previous major the declared removal is invisible, by design of the gate
        assertThat(ContractGate.compare(previous, current)).isEmpty();
        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(tuple("field test.Order#legacy", "removed", Classification.BREAKING));
        assertThat(differences.get(0).reason()).isEqualTo("Responses no longer carry the field");
    }

    @Test
    void identicalReleasesDoNotDiffer() {
        assertThat(ReleaseComparison.compare(ir(1, NOTE), ir(1, NOTE))).isEmpty();
    }

    @Test
    void aFirstReleaseAddsEveryPublishedElement() {
        ContractIr current = ir(1, NOTE);

        List<Difference> differences = ReleaseComparison.compare(EmptyContract.document("/api", 1), current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(
                        tuple("field test.Order#id", "added", Classification.COMPATIBLE),
                        tuple("field test.Order#note", "added", Classification.COMPATIBLE),
                        tuple("operation test.OrderService#find(java.lang.Long)", "added", Classification.COMPATIBLE));
    }

    @Test
    void aDeprecationIsACompatibleLifecycleChangeOfThePublishedSurface() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(1, DECLARED_REMOVAL);

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(tuple("field test.Order#legacy", "lifecycle", Classification.COMPATIBLE));
    }

    @Test
    void aDeprecationDeclaredForALaterMajorIsNotPublished() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(1, LEGACY.formatted(", deprecatedSinceVersion = 2"));

        assertThat(ReleaseComparison.compare(previous, current)).isEmpty();
    }

    @Test
    void anElementNotYetActiveIsNotPublished() {
        ContractIr previous = ir(1, "");
        ContractIr later = ir(1, LEGACY.formatted(", sinceVersion = 2"));

        assertThat(ReleaseComparison.compare(previous, later)).isEmpty();
        assertThat(ReleaseComparison.compare(later, ir(2, LEGACY.formatted(", sinceVersion = 2"))))
                .extracting(Difference::path, Difference::change)
                .containsExactly(tuple("field test.Order#legacy", "added"));
    }

    @Test
    void theReasonsNameThePreviousReleasesMajor() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(2, "");

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).singleElement().satisfies(d -> assertThat(d.remedy()).contains("removedInVersion = 2"));
    }

    @Test
    void aChannelLossOnAReachableEntityIsBreaking() {
        ContractIr previous = ReleaseFixtures.parse(ReleaseFixtures.irJson(1, NOTE, ""));
        ContractIr current = ReleaseFixtures.parse(ReleaseFixtures.irJson(1,
                NOTE.replace("description = \"A note\"", "description = \"A note\", channels = AgenticExposed.Channel.API"),
                "", ReleaseFixtures.PROJECTIONS));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).filteredOn(Difference::breaking)
                .extracting(Difference::path, Difference::change, Difference::before, Difference::after)
                .containsExactly(tuple("field test.Order#note", "channels.AI", "[AI, API]", "[API]"));
    }

    /** D9.4: a nested chain (Order → Customer → Address) whose deeper reference changes target. */
    @Test
    void aNestedReferenceChangeIsBreaking() {
        Entity order = entity("test.Order", List.of(field("customer", ref("test.Customer", "CustomerDto"))));
        Entity customerToAddress = entity("test.Customer",
                List.of(field("address", ref("test.Address", "AddressDto"))));
        Entity customerToLocation = entity("test.Customer",
                List.of(field("address", ref("test.Location", "LocationDto"))));
        Operation find = operation("find", "find", null, null);
        ContractIr previous = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(order, customerToAddress),
                List.of(find));
        ContractIr current = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(order, customerToLocation),
                List.of(find));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .contains(tuple("field test.Customer#address", "reference", Classification.BREAKING));
    }

    /** D9.4: an entity's {@code dtoName} rename is breaking (its DTO is the identity clients read). */
    @Test
    void aDtoNameRenameIsBreaking() {
        Entity previousOrder = entity("test.Order", "OrderDto", List.of(field("id", null)));
        Entity currentOrder = entity("test.Order", "OrderView", List.of(field("id", null)));
        ContractIr previous = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(previousOrder), List.of());
        ContractIr current = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(currentOrder), List.of());

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(tuple("entity test.Order", "dtoName", Classification.BREAKING));
    }

    /** D9.4: an operation's {@code toolName} rename is breaking (MCP clients call it by that name). */
    @Test
    void aToolNameRenameIsBreaking() {
        Operation previousOp = operation("find", "findOrder", null, null);
        Operation currentOp = operation("find", "fetchOrder", null, null);
        ContractIr previous = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(previousOp));
        ContractIr current = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(currentOp));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .containsExactly(tuple("operation test.OrderService#find()", "toolName", Classification.BREAKING));
    }

    /** D9.4: a REST path rename (the route the RPC fallback derives) is breaking. */
    @Test
    void aRestPathRenameIsBreaking() {
        Operation previousOp = operation("find", "findOrder", Rest.rpc("GET", "/order-service/find", 0), null);
        Operation currentOp = operation("find", "findOrder", Rest.rpc("GET", "/order-service/fetch", 0), null);
        ContractIr previous = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(previousOp));
        ContractIr current = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(currentOp));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .contains(tuple("operation test.OrderService#find()", "rest.path", Classification.BREAKING));
    }

    /**
     * D9.4: an {@code operationId} rename. A second operation named {@code find} appears, so the
     * OpenAPI operationId scheme, which keeps a unique method name as-is, must disambiguate both.
     */
    @Test
    void anOperationIdRenameIsBreaking() {
        Return none = new Return("void", "NONE", null, null, Bound.NONE);
        Parameter id = new Parameter("id", "java.lang.Long", "", List.of(), true, null);
        Parameter name = new Parameter("name", "java.lang.String", "", List.of(), true, null);
        Operation findById = new Operation("test.OrderService", "find", "find", List.of("API"), "",
                Rest.rpc("GET", "/order-service/find", 1), List.of(id), none, null, OP_ACTIVE);
        Operation findByName = new Operation("test.OrderService", "find", "find", List.of("API"), "",
                Rest.rpc("GET", "/order-service/find-by-name", 1), List.of(name), none, null, OP_ACTIVE);
        ContractIr previous = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(findById));
        ContractIr current = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(findById, findByName));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).filteredOn(d -> d.change().equals("operationId")).extracting(Difference::path,
                Difference::before, Difference::after, Difference::classification)
                .containsExactly(tuple("operation test.OrderService#find(java.lang.Long)", "find",
                        "OrderService_find_get", Classification.BREAKING));
    }

    /** D9.4: {@code removedInVersion} and {@code apiUntil} take a field and an operation out at a major. */
    @Test
    void removedInVersionAndApiUntilRemovalsAcrossAMajorAreReported() {
        Entity order = entity("test.Order",
                List.of(field("legacy", null, new FieldLifecycle(1, 2, 0, null))));
        Operation find = operation("find", "find", null, new OperationLifecycle(1, 1, 0, null));
        ContractIr previous = new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(order), List.of(find));
        ContractIr current = new ContractIr(ContractIr.IR_VERSION, "/api", 2, List.of(order), List.of(find));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .contains(tuple("field test.Order#legacy", "removed", Classification.BREAKING),
                        tuple("operation test.OrderService#find()", "removed", Classification.BREAKING));
    }

    /** D9.4: a deprecation whose {@code deprecatedSinceVersion} is exactly the release's own major is published. */
    @Test
    void aDeprecationStartingExactlyAtTheReleasesMajorIsPublished() {
        ContractIr previous = ir(1, LEGACY.formatted(""));
        ContractIr current = ir(2, LEGACY.formatted(", deprecatedSinceVersion = 2"));

        List<Difference> differences = ReleaseComparison.compare(previous, current);

        assertThat(differences).extracting(Difference::path, Difference::change, Difference::classification)
                .contains(tuple("field test.Order#legacy", "lifecycle", Classification.COMPATIBLE));
    }

    /** D9.4: the empty contract as the current release removes every element the previous one published. */
    @Test
    void theEmptyContractAsTheCurrentReleaseRemovesEveryElement() {
        ContractIr previous = ir(1, LEGACY.formatted(""));

        List<Difference> differences = ReleaseComparison.compare(previous, EmptyContract.document("/api", 1));

        assertThat(differences).filteredOn(d -> "removed".equals(d.change())).extracting(Difference::path)
                .containsExactlyInAnyOrder("entity test.Order", "field test.Order#id", "field test.Order#legacy",
                        "operation test.OrderService#find(java.lang.Long)");
    }

    // ------------------------------------------------------------ hand-built fixture helpers

    private static final FieldLifecycle FIELD_ACTIVE = new FieldLifecycle(1, Integer.MAX_VALUE, 0, null);
    private static final OperationLifecycle OP_ACTIVE = new OperationLifecycle(1, Integer.MAX_VALUE, 0, null);

    private static Entity entity(String className, List<Field> fields) {
        return entity(className, className.substring(className.lastIndexOf('.') + 1) + "Dto", fields);
    }

    private static Entity entity(String className, String dtoName, List<Field> fields) {
        return new Entity(className, dtoName, "generated", className, "", false, fields);
    }

    private static Field field(String name, TypeRef reference) {
        return field(name, reference, FIELD_ACTIVE);
    }

    private static Field field(String name, TypeRef reference, FieldLifecycle lifecycle) {
        return new Field(name, name, "java.lang.String", "NONE", null, null, reference, false, List.of(), false,
                false, false, "", null, List.of("AI", "API"), lifecycle);
    }

    private static TypeRef ref(String entityClass, String dtoSimpleName) {
        return new TypeRef(entityClass, "test.generated." + dtoSimpleName);
    }

    /** An operation returning nothing, with an optional REST mapping and lifecycle (default active). */
    private static Operation operation(String method, String toolName, Rest rest, OperationLifecycle lifecycle) {
        Return none = new Return("void", "NONE", null, null, Bound.NONE);
        return new Operation("test.OrderService", method, toolName, List.of("API"), "", rest, List.of(), none, null,
                lifecycle != null ? lifecycle : OP_ACTIVE);
    }
}
