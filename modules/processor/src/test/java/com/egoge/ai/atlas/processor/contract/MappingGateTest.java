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
                tuple("returns.bound.maxResults", Direction.INPUT, null, "100", Classification.BREAKING));
        assertThat(boundChanges(paged(100), paged(50))).containsExactly(
                tuple("returns.bound.maxResults", Direction.INPUT, "100", "50", Classification.BREAKING));
        Difference fall = single(compare(op(API, QUERY_MAPPING, "id", paged(100)),
                op(API, QUERY_MAPPING, "id", paged(50))));
        assertThat(ContractGate.message(fall, 1)).contains("returns.bound.maxResults 100 → 50 (input)",
                "Requests for pages larger than 50 are rejected", REMEDY);
    }

    @Test
    void aPageSizeCeilingRisingOrDisappearingIsCompatible() {
        assertThat(boundChanges(paged(50), paged(100))).containsExactly(
                tuple("returns.bound.maxResults", Direction.INPUT, "50", "100", Classification.COMPATIBLE));
        assertThat(boundChanges(paged(50), paged(null))).containsExactly(
                tuple("returns.bound.maxResults", Direction.INPUT, "50", null, Classification.COMPATIBLE));
    }

    @Test
    void aLimitRoleOverAnExistingMaximumAddsNoCeiling() {
        // List<X> find(@Max(100) Long id) gains @AgenticParam(paging = LIMIT): the accepted inputs are unchanged
        List<Difference> differences = ContractGate.compare(op(API, "id", Bound.NONE, "100"),
                op(API, "id", limit(100), "100"));

        assertThat(differences).extracting(Difference::change, Difference::classification).containsExactly(
                tuple("returns.bound.limitParameter", Classification.INFORMATIONAL),
                tuple("returns.bound.style", Classification.INFORMATIONAL));
    }

    @Test
    void aMaximumChangeUnderAMatchingCeilingIsReportedOnceAsAParameterChange() {
        List<Difference> differences = ContractGate.compare(op(API, "id", limit(100), "100"),
                op(API, "id", limit(50), "50"));

        assertThat(differences).extracting(Difference::change, Difference::classification)
                .containsExactly(tuple("maximum", Classification.BREAKING));
    }

    @Test
    void aCeilingBelowWhatTheBaselineAcceptedIsABreakingInputChange() {
        assertThat(ContractGate.compare(op(API, "id", Bound.NONE, "100"), op(API, "id", limit(50), "100")))
                .extracting(Difference::change, Difference::direction, Difference::before, Difference::after,
                        Difference::classification)
                .contains(tuple("returns.bound.maxResults", Direction.INPUT, null, "50", Classification.BREAKING));
        // A ceiling at the baseline maximum becomes a real one when it falls below it
        assertThat(ContractGate.compare(op(API, "id", limit(100), "100"), op(API, "id", limit(50), "100")))
                .extracting(Difference::change, Difference::direction, Difference::before, Difference::after,
                        Difference::classification)
                .containsExactly(tuple("returns.bound.maxResults", Direction.INPUT, "100", "50", Classification.BREAKING));
    }

    @Test
    void aCeilingThatRejectsNothingTheBaselineAcceptedIsCompatible() {
        // @Max 50 → 100 widens the parameter; the new ceiling of 80 still accepts every baseline page size
        List<Difference> differences = ContractGate.compare(op(API, "id", limit(null), "50"),
                op(API, "id", limit(80), "100"));

        assertThat(differences).extracting(Difference::change, Difference::direction, Difference::classification)
                .containsExactlyInAnyOrder(tuple("maximum", Direction.INPUT, Classification.COMPATIBLE),
                        tuple("returns.bound.maxResults", Direction.INPUT, Classification.COMPATIBLE));
    }

    @Test
    void aCeilingMovedToAnotherParameterIsComparedAgainstWhatThatParameterAccepted() {
        // find(Pageable pageable, Long size): the Pageable's ceiling of 50 moves to a previously unlimited size
        Rest query = Rest.rpc("POST", "/order-service/find", 2);
        ContractIr pageable = twoParameters(query, "pageable", "size", paged(50));

        for (int ceiling : List.of(80, 50)) {
            assertThat(ContractGate.compare(pageable, twoParameters(query, "pageable", "size",
                    new Bound("LIMIT", "NONE", "size", null, ceiling))))
                    .extracting(Difference::change, Difference::direction, Difference::before, Difference::after,
                            Difference::classification)
                    .contains(tuple("returns.bound.maxResults", Direction.INPUT, "50 on 'pageable'",
                            ceiling + " on 'size'", Classification.BREAKING));
        }
    }

    @Test
    void aDeclaredBoundBecomingALimitOfTheSameValueIsComparedPerDirection() {
        // Over @Max(50): the result bound disappears as an output; the ceiling adds nothing as an input
        assertThat(ContractGate.compare(op(API, "id", declared(50), "50"), op(API, "id", limit(50), "50")))
                .extracting(Difference::change, Difference::direction, Difference::classification).containsExactlyInAnyOrder(
                        tuple("returns.bound.maxResults", Direction.OUTPUT, Classification.BREAKING),
                        tuple("returns.bound.style", Direction.INPUT, Classification.INFORMATIONAL),
                        tuple("returns.bound.limitParameter", Direction.INPUT, Classification.INFORMATIONAL));
        // Without a maximum, limits above 50 were accepted and are now rejected
        assertThat(ContractGate.compare(op(API, "id", declared(50), null), op(API, "id", limit(50), null)))
                .extracting(Difference::change, Difference::direction, Difference::before, Difference::after,
                        Difference::classification).containsExactlyInAnyOrder(
                        tuple("returns.bound.maxResults", Direction.OUTPUT, "50", null, Classification.BREAKING),
                        tuple("returns.bound.maxResults", Direction.INPUT, null, "50", Classification.BREAKING),
                        tuple("returns.bound.style", Direction.INPUT, "DECLARED", "LIMIT", Classification.INFORMATIONAL),
                        tuple("returns.bound.limitParameter", Direction.INPUT, null, "id",
                                Classification.INFORMATIONAL));
    }

    @Test
    void aPagedBoundBecomingANonPagedOneLiftsTheCeilingAndBoundsTheResult() {
        assertThat(boundChanges(paged(100), declared(50))).containsExactlyInAnyOrder(
                tuple("returns.bound.maxResults", Direction.OUTPUT, null, "50", Classification.COMPATIBLE),
                tuple("returns.bound.maxResults", Direction.INPUT, "100", null, Classification.COMPATIBLE),
                tuple("returns.bound.envelope", Direction.OUTPUT, "PAGE", "NONE", Classification.BREAKING),
                tuple("returns.bound.style", Direction.INPUT, "PAGEABLE", "DECLARED", Classification.INFORMATIONAL),
                tuple("returns.bound.limitParameter", Direction.INPUT, "pageable", null, Classification.INFORMATIONAL));
    }

    // ------------------------------------------------------------ rest

    @Test
    void aChangedStatusIsABreakingOutputChange() {
        Rest created = new Rest("POST", "/order-service/find", 201, List.of("QUERY"));

        Difference status = single(compare(op(API, QUERY_MAPPING, "id", Bound.NONE), op(API, created, "id", Bound.NONE)));

        assertThat(status).extracting(Difference::change, Difference::direction, Difference::before,
                Difference::after, Difference::classification)
                .containsExactlyInAnyOrder("rest.status", Direction.OUTPUT, "200", "201", Classification.BREAKING);
        assertThat(ContractGate.message(status, 1)).contains(PATH + ": rest.status 200 → 201 (output)",
                "REST clients check the success status", REMEDY);
    }

    @Test
    void aChangedParameterLocationIsABreakingInputChange() {
        Rest body = new Rest("POST", "/order-service/find", 200, List.of("BODY"));

        Difference in = single(compare(op(API, QUERY_MAPPING, "id", Bound.NONE), op(API, body, "id", Bound.NONE)));

        assertThat(in).extracting(Difference::change, Difference::direction, Difference::before,
                Difference::after, Difference::classification)
                .containsExactlyInAnyOrder("rest.parameterIn[0]", Direction.INPUT, "QUERY", "BODY", Classification.BREAKING);
        assertThat(ContractGate.message(in, 1)).contains("rest.parameterIn[0] QUERY → BODY (input)",
                "REST clients send the parameter 'id' in the query", REMEDY);
    }

    @Test
    void aPathChangingOnlyInVariableNamesIsCompatible() {
        Rest byId = new Rest("GET", "/orders/{id}", 200, List.of("PATH"));
        Rest byOrderId = new Rest("GET", "/orders/{orderId}", 200, List.of("PATH"));
        Rest items = new Rest("GET", "/orders/{id}/items", 200, List.of("PATH"));

        assertThat(single(compare(op(BOTH, byId, "id", Bound.NONE), op(BOTH, byOrderId, "id", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactlyInAnyOrder("rest.path", Classification.COMPATIBLE);
        assertThat(single(compare(op(BOTH, byId, "id", Bound.NONE), op(BOTH, items, "id", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactlyInAnyOrder("rest.path", Classification.BREAKING);
    }

    @Test
    void swappingTwoPathVariablesIsBreaking() {
        // The route is unchanged, but existing clients' first segment now binds orderId
        Rest customerFirst = new Rest("GET", "/orders/{customerId}/{orderId}", 200, List.of("PATH", "PATH"));
        Rest orderFirst = new Rest("GET", "/orders/{orderId}/{customerId}", 200, List.of("PATH", "PATH"));

        assertThat(ContractGate.compare(twoParameters(customerFirst, "customerId", "orderId", Bound.NONE),
                twoParameters(orderFirst, "customerId", "orderId", Bound.NONE)))
                .extracting(Difference::change, Difference::classification)
                .containsExactly(tuple("rest.path", Classification.BREAKING));
    }

    @Test
    void renamingAPathOrBodyParameterOfAnApiOnlyOperationIsCompatible() {
        Rest byId = new Rest("GET", "/orders/{id}", 200, List.of("PATH"));
        Rest byOrderId = new Rest("GET", "/orders/{orderId}", 200, List.of("PATH"));
        Rest body = new Rest("POST", "/orders", 200, List.of("BODY"));

        assertThat(compare(op(API, byId, "id", Bound.NONE), op(API, byOrderId, "orderId", Bound.NONE)))
                .extracting(Difference::change, Difference::classification).containsExactlyInAnyOrder(
                        tuple("parameter 0.name", Classification.COMPATIBLE),
                        tuple("rest.path", Classification.COMPATIBLE));
        assertThat(single(compare(op(API, body, "order", Bound.NONE), op(API, body, "draft", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactlyInAnyOrder("parameter 0.name", Classification.COMPATIBLE);
    }

    @Test
    void renamingAParameterMcpClientsOrQueryClientsNameStaysBreaking() {
        Rest body = new Rest("POST", "/orders", 200, List.of("BODY"));

        assertThat(single(compare(op(BOTH, body, "order", Bound.NONE), op(BOTH, body, "draft", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactlyInAnyOrder("parameter 0.name", Classification.BREAKING);
        assertThat(single(compare(op(API, QUERY_MAPPING, "id", Bound.NONE),
                op(API, QUERY_MAPPING, "orderId", Bound.NONE))))
                .extracting(Difference::change, Difference::classification)
                .containsExactlyInAnyOrder("parameter 0.name", Classification.BREAKING);
    }

    @Test
    void renamingAPathParameterOfAnOperationApiOnlyOnOneSideOnlyStaysBreaking() {
        Rest byId = new Rest("GET", "/orders/{id}", 200, List.of("PATH"));
        Rest byOrderId = new Rest("GET", "/orders/{orderId}", 200, List.of("PATH"));

        assertThat(compare(op(API, byId, "id", Bound.NONE), op(BOTH, byOrderId, "orderId", Bound.NONE)))
                .extracting(Difference::change, Difference::classification).contains(
                        tuple("parameter 0.name", Classification.BREAKING));
        assertThat(compare(op(BOTH, byId, "id", Bound.NONE), op(API, byOrderId, "orderId", Bound.NONE)))
                .extracting(Difference::change, Difference::classification).contains(
                        tuple("parameter 0.name", Classification.BREAKING));
    }

    @Test
    void renamingAParameterWhileMovingItToTheQueryIsBreakingTwice() {
        Rest body = new Rest("POST", "/orders", 200, List.of("BODY"));
        Rest query = new Rest("POST", "/orders", 200, List.of("QUERY"));

        assertThat(compare(op(API, body, "order", Bound.NONE), op(API, query, "draft", Bound.NONE)))
                .extracting(Difference::change, Difference::classification).containsExactlyInAnyOrder(
                        tuple("rest.parameterIn[0]", Classification.BREAKING),
                        tuple("parameter 0.name", Classification.BREAKING));
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

    private static Bound limit(Integer ceiling) {
        return new Bound("LIMIT", "NONE", "id", null, ceiling);
    }

    /** {@link #op} with the query mapping, and {@code maximum} as the parameter's Phase 3 maximum. */
    private static ContractIr op(List<String> channels, String parameter, Bound bound, String maximum) {
        return op(channels, QUERY_MAPPING, parameter, bound,
                new EffectiveConstraints(null, false, maximum, false, null, null, null, null, List.of(), false));
    }

    /** A document of one operation, {@code OrderService.find(Long)}, active from major 1. */
    private static ContractIr op(List<String> channels, Rest rest, String parameter, Bound bound) {
        return op(channels, rest, parameter, bound, EffectiveConstraints.NONE);
    }

    private static ContractIr op(List<String> channels, Rest rest, String parameter, Bound bound,
                                 EffectiveConstraints constraints) {
        Operation op = new Operation("shop.OrderService", "find", "find", channels, "Find",
                channels.contains("API") ? rest : null,
                List.of(new Parameter(parameter, "java.lang.Long", "", List.of(), true, constraints)),
                new Return("java.util.List<java.lang.String>", "COLLECTION", null, null, bound), Hints.NONE,
                new OperationLifecycle(1, Integer.MAX_VALUE, 0, ""));
        return new ContractIr(ContractIr.IR_VERSION, "/api", 1, List.of(), List.of(op));
    }

    /** A document of one API-only operation, {@code OrderService.find(Long, Long)}, active from major 1. */
    private static ContractIr twoParameters(Rest rest, String first, String second, Bound bound) {
        Operation op = new Operation("shop.OrderService", "find", "find", API, "Find", rest,
                List.of(new Parameter(first, "java.lang.Long", "", List.of(), true, EffectiveConstraints.NONE),
                        new Parameter(second, "java.lang.Long", "", List.of(), true, EffectiveConstraints.NONE)),
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
