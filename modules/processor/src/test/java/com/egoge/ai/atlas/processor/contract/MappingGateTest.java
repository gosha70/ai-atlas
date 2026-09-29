/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Hints;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.OperationLifecycle;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.egoge.ai.atlas.processor.contract.ContractIr.Return;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The gate's rules for the Contract IR version 4 slots, on IRs built directly since nothing emits
 * values other than today's yet: the result bound ({@code returns.bound}) and the REST mapping's
 * status, parameter locations, variable names and parameter names.
 */
class MappingGateTest {

    private static final List<String> API = List.of("API");
    private static final List<String> BOTH = List.of("AI", "API");
    private static final String PATH = "operation shop.OrderService#find(java.lang.Long)";
    private static final Rest QUERY_MAPPING = Rest.rpc("POST", "/order-service/find", 1);
    private static final String REMEDY = "publish it in a new major (ai.atlas.api.major = 2, then atlasAccept)";

    // ------------------------------------------------------------ returns.bound

    @Test
    void aResultBoundAppearingOrFallingIsCompatible() {
        assertThat(boundChanges(Bound.NONE, declared(50)))
                .containsExactly(tuple("returns.bound.maxResults", Direction.OUTPUT, null, "50", Classification.COMPATIBLE),
                        tuple("returns.bound.style", Direction.INPUT, "NONE", "DECLARED", Classification.INFORMATIONAL));
        assertThat(boundChanges(declared(50), declared(20))).containsExactly(
                tuple("returns.bound.maxResults", Direction.OUTPUT, "50", "20", Classification.COMPATIBLE));
    }

    @Test
    void aResultBoundDisappearingOrRisingIsABreakingOutputChange() {
        assertThat(boundChanges(declared(50), Bound.NONE)).contains(
                tuple("returns.bound.maxResults", Direction.OUTPUT, "50", null, Classification.BREAKING));
        assertThat(boundChanges(declared(50), declared(100))).containsExactly(
                tuple("returns.bound.maxResults", Direction.OUTPUT, "50", "100", Classification.BREAKING));

        Difference rise = single(compare(op(API, QUERY_MAPPING, "id", declared(50)),
                op(API, QUERY_MAPPING, "id", declared(100))));
        assertThat(ContractGate.message(rise, 1)).contains(PATH + ": returns.bound.maxResults 50 → 100 (output)",
                "Clients may rely on receiving at most 50 results", REMEDY, "accept the change explicitly with atlasAccept");
    }

    @Test
    void aChangedEnvelopeIsABreakingOutputChange() {
        Bound page = new Bound("PAGEABLE", "PAGE", "pageable", null, null);
        Bound slice = new Bound("PAGEABLE", "SLICE", "pageable", null, null);

        assertThat(boundChanges(page, slice)).containsExactly(
                tuple("returns.bound.envelope", Direction.OUTPUT, "PAGE", "SLICE", Classification.BREAKING));
        Difference envelope = single(compare(op(API, QUERY_MAPPING, "id", page), op(API, QUERY_MAPPING, "id", slice)));
        assertThat(ContractGate.message(envelope, 1)).contains("returns.bound.envelope PAGE → SLICE (output)", REMEDY);
    }

    @Test
    void aPagingRoleDeclaredOrRemovedOnAnExistingParameterIsInformational() {
        Bound limit = new Bound("LIMIT", "NONE", "id", null, null);
        Bound cursor = new Bound("LIMIT", "NONE", "id", "after", null);

        assertThat(boundChanges(Bound.NONE, limit)).containsExactly(
                tuple("returns.bound.limitParameter", Direction.INPUT, null, "id", Classification.INFORMATIONAL),
                tuple("returns.bound.style", Direction.INPUT, "NONE", "LIMIT", Classification.INFORMATIONAL));
        assertThat(boundChanges(cursor, limit)).containsExactly(
                tuple("returns.bound.cursorParameter", Direction.INPUT, "after", null, Classification.INFORMATIONAL));
    }

    @Test
    void aPageSizeCeilingAppearingOrFallingIsABreakingInputChange() {
        assertThat(boundChanges(paged(null), paged(100))).containsExactly(
                tuple("returns.bound.pageSizeCeiling", Direction.INPUT, null, "100", Classification.BREAKING));
        assertThat(boundChanges(paged(100), paged(50))).containsExactly(
                tuple("returns.bound.pageSizeCeiling", Direction.INPUT, "100", "50", Classification.BREAKING));
        Difference fall = single(compare(op(API, QUERY_MAPPING, "id", paged(100)),
                op(API, QUERY_MAPPING, "id", paged(50))));
        assertThat(ContractGate.message(fall, 1)).contains("returns.bound.pageSizeCeiling 100 → 50 (input)",
                "Requests for pages larger than 50 are rejected", REMEDY);
    }

    @Test
    void aPageSizeCeilingRisingOrDisappearingIsCompatible() {
        assertThat(boundChanges(paged(50), paged(100))).containsExactly(
                tuple("returns.bound.pageSizeCeiling", Direction.INPUT, "50", "100", Classification.COMPATIBLE));
        assertThat(boundChanges(paged(50), paged(null))).containsExactly(
                tuple("returns.bound.pageSizeCeiling", Direction.INPUT, "50", null, Classification.COMPATIBLE));
    }

    // ------------------------------------------------------------ rest

    @Test
    void aChangedStatusIsABreakingOutputChange() {
        Rest created = new Rest("POST", "/order-service/find", 201, List.of("QUERY"));

        Difference status = single(compare(op(API, QUERY_MAPPING, "id", Bound.NONE), op(API, created, "id", Bound.NONE)));

        assertThat(status).extracting(Difference::change, Difference::direction, Difference::before,
                Difference::after, Difference::classification)
                .containsExactly("rest.status", Direction.OUTPUT, "200", "201", Classification.BREAKING);
        assertThat(ContractGate.message(status, 1)).contains(PATH + ": rest.status 200 → 201 (output)",
                "REST clients check the success status", REMEDY);
    }

    @Test
    void aChangedParameterLocationIsABreakingInputChange() {
        Rest body = new Rest("POST", "/order-service/find", 200, List.of("BODY"));

        Difference in = single(compare(op(API, QUERY_MAPPING, "id", Bound.NONE), op(API, body, "id", Bound.NONE)));

        assertThat(in).extracting(Difference::change, Difference::direction, Difference::before,
                Difference::after, Difference::classification)
                .containsExactly("parameter 0.in", Direction.INPUT, "QUERY", "BODY", Classification.BREAKING);
        assertThat(ContractGate.message(in, 1)).contains("parameter 0.in QUERY → BODY (input)",
                "REST clients send the parameter 'id' in the query", REMEDY);
    }

    @Test
    void aPathChangingOnlyInVariableNamesIsCompatible() {
        Rest byId = new Rest("GET", "/orders/{id}", 200, List.of("PATH"));
        Rest byOrderId = new Rest("GET", "/orders/{orderId}", 200, List.of("PATH"));
        Rest items = new Rest("GET", "/orders/{id}/items", 200, List.of("PATH"));

        assertThat(single(compare(op(BOTH, byId, "id", Bound.NONE), op(BOTH, byOrderId, "id", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactly("rest.path", Classification.COMPATIBLE);
        assertThat(single(compare(op(BOTH, byId, "id", Bound.NONE), op(BOTH, items, "id", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactly("rest.path", Classification.BREAKING);
    }

    @Test
    void renamingAPathOrBodyParameterOfAnApiOnlyOperationIsCompatible() {
        Rest byId = new Rest("GET", "/orders/{id}", 200, List.of("PATH"));
        Rest byOrderId = new Rest("GET", "/orders/{orderId}", 200, List.of("PATH"));
        Rest body = new Rest("POST", "/orders", 200, List.of("BODY"));

        assertThat(compare(op(API, byId, "id", Bound.NONE), op(API, byOrderId, "orderId", Bound.NONE)))
                .extracting(Difference::change, Difference::classification).containsExactly(
                        tuple("parameter 0.name", Classification.COMPATIBLE),
                        tuple("rest.path", Classification.COMPATIBLE));
        assertThat(single(compare(op(API, body, "order", Bound.NONE), op(API, body, "draft", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactly("parameter 0.name", Classification.COMPATIBLE);
    }

    @Test
    void renamingAParameterMcpClientsOrQueryClientsNameStaysBreaking() {
        Rest body = new Rest("POST", "/orders", 200, List.of("BODY"));

        assertThat(single(compare(op(BOTH, body, "order", Bound.NONE), op(BOTH, body, "draft", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactly("parameter 0.name", Classification.BREAKING);
        assertThat(single(compare(op(API, QUERY_MAPPING, "id", Bound.NONE),
                op(API, QUERY_MAPPING, "orderId", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactly("parameter 0.name", Classification.BREAKING);
    }

    @Test
    void todaysValuesOnBothSidesAreNoDifference() {
        ContractIr same = op(BOTH, QUERY_MAPPING, "id", Bound.NONE);

        assertThat(ContractGate.compare(same, op(BOTH, QUERY_MAPPING, "id", Bound.NONE))).isEmpty();
        assertThat(ContractGate.documentDifferences(same, op(BOTH, QUERY_MAPPING, "id", Bound.NONE))).isEmpty();
    }

    @Test
    void lockModeSeesEveryVersion4SlotChange() {
        ContractIr before = op(API, QUERY_MAPPING, "id", Bound.NONE);

        assertThat(ContractGate.documentDifferences(before, op(API, QUERY_MAPPING, "id", paged(null))))
                .containsExactly(PATH);
        assertThat(ContractGate.documentDifferences(before,
                op(API, new Rest("POST", "/order-service/find", 201, List.of("QUERY")), "id", Bound.NONE)))
                .containsExactly(PATH);
    }

    // ------------------------------------------------------------ fixtures

    private static Bound declared(int maxResults) {
        return new Bound("DECLARED", "NONE", null, null, maxResults);
    }

    private static Bound paged(Integer ceiling) {
        return new Bound("PAGEABLE", "PAGE", "pageable", null, ceiling);
    }

    /** A document of one operation, {@code OrderService.find(Long)}, active from major 1. */
    private static ContractIr op(List<String> channels, Rest rest, String parameter, Bound bound) {
        Operation op = new Operation("shop.OrderService", "find", "find", channels, "Find",
                channels.contains("API") ? rest : null,
                List.of(new Parameter(parameter, "java.lang.Long", "", List.of(), true, EffectiveConstraints.NONE)),
                new Return("java.util.List<java.lang.String>", "COLLECTION", null, null, bound), Hints.NONE,
                new OperationLifecycle(1, Integer.MAX_VALUE, 0, ""));
        return new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(op));
    }

    private static List<Difference> compare(ContractIr baseline, ContractIr fresh) {
        List<Difference> differences = ContractGate.compare(baseline, fresh);
        assertThat(differences).allSatisfy(d -> assertThat(d.path()).isEqualTo(PATH));
        return differences;
    }

    private static List<org.assertj.core.groups.Tuple> boundChanges(Bound before, Bound after) {
        return compare(op(API, QUERY_MAPPING, "id", before), op(API, QUERY_MAPPING, "id", after)).stream()
                .map(d -> tuple(d.change(), d.direction(), d.before(), d.after(), d.classification())).toList();
    }

    private static Difference single(List<Difference> differences) {
        assertThat(differences).hasSize(1);
        return differences.get(0);
    }
}
