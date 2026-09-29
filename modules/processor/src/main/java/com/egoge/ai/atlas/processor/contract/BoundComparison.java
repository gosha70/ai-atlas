/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.constraints.Endpoint;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The gate's rules for an operation's result bound, at the published major M. {@code maxResults}
 * is the page-size ceiling of a paged bound ({@code PAGEABLE}, {@code LIMIT}) and the result bound
 * of any other; both are reported as the change {@code returns.bound.maxResults}, the ceiling as an
 * input and the result bound as an output:
 * <ul>
 *   <li>a result bound appearing or falling is compatible; disappearing or rising is a breaking
 *       output change, as clients may rely on receiving at most that many results;</li>
 *   <li>a page-size ceiling is compared only where it adds to the limit parameter's own Phase 3
 *       maximum, which {@link ConstraintComparison} already gates: a {@code LIMIT} ceiling at or
 *       above that maximum on the same side is no ceiling. A ceiling limits one parameter, the
 *       bound's {@code limitParameter}, identified by position; a ceiling that remains is a breaking
 *       input change when it is below every page size the baseline accepted for that parameter:
 *       the baseline ceiling, only if it limited the same parameter, and that parameter's baseline
 *       maximum. Otherwise it is compatible. A ceiling moved to another parameter is compared even
 *       when its value is unchanged;</li>
 *   <li>a changed envelope is a breaking output change;</li>
 *   <li>a paging role declared or removed on an existing parameter, and so the style, is
 *       informational: adding or removing the parameter already changes the operation's identity.</li>
 * </ul>
 * Used through {@link ContractComparison}.
 */
final class BoundComparison {

    private static final String C_BOUND = "returns.bound.";
    private static final String C_MAX_RESULTS = "maxResults";

    private BoundComparison() {
    }

    /**
     * Compares the bounds of an operation active at M on both sides.
     *
     * @param path        the operation's element path
     * @param old         the baseline operation
     * @param now         the fresh operation, with as many parameters
     * @param major       the published major M
     * @param differences receives each difference
     */
    static void compare(String path, Operation old, Operation now, int major, List<Difference> differences) {
        Bound before = old.returns().bound();
        Bound after = now.returns().bound();
        String remedy = ContractComparison.newMajorRemedy(major, "a result bound has no lifecycle of its own");
        Integer resultsBefore = before.paged() ? null : before.maxResults();
        Integer resultsAfter = after.paged() ? null : after.maxResults();
        add(differences, path, C_MAX_RESULTS, Direction.OUTPUT, resultsBefore, resultsAfter,
                widens(resultsBefore, resultsAfter) ? Classification.BREAKING : Classification.COMPATIBLE,
                "Clients may rely on receiving at most " + resultsBefore + " results", remedy);
        Integer ceilingBefore = before.paged() ? before.maxResults() : null;
        Integer ceilingAfter = after.paged() ? after.maxResults() : null;
        Integer ownBefore = ownCeiling(old, before);
        Integer ownAfter = ownCeiling(now, after);
        int limited = limitedIndex(now, after);
        boolean sameLimited = limitedIndex(old, before) == limited;
        if (!Objects.equals(ownBefore, ownAfter) || !sameLimited && ownAfter != null) {
            // Every page size the baseline accepted for the parameter the ceiling now limits: the
            // baseline ceiling if it limited that parameter, and that parameter's maximum there
            BigDecimal accepted = min(sameLimited && ceilingBefore != null ? BigDecimal.valueOf(ceilingBefore) : null,
                    maximum(old, Bound.LIMIT.equals(after.style()) ? limited : -1));
            boolean narrows = ownAfter != null
                    && (accepted == null || BigDecimal.valueOf(ownAfter).compareTo(accepted) < 0);
            // Two ceilings on different parameters name them, as equal values would otherwise read as no change
            boolean moved = !sameLimited && ceilingBefore != null && ceilingAfter != null;
            add(differences, path, C_MAX_RESULTS, Direction.INPUT,
                    moved ? onParameter(ceilingBefore, before) : ceilingBefore,
                    moved ? onParameter(ceilingAfter, after) : ceilingAfter,
                    narrows ? Classification.BREAKING : Classification.COMPATIBLE,
                    "Requests for pages larger than " + ceilingAfter + " are rejected", remedy);
        }
        add(differences, path, "envelope", Direction.OUTPUT, before.envelope(), after.envelope(),
                Classification.BREAKING, "Clients receive the result in this shape", remedy);
        add(differences, path, "style", Direction.INPUT, before.style(), after.style(), Classification.INFORMATIONAL,
                null, null);
        add(differences, path, "limitParameter", Direction.INPUT, before.limitParameter(), after.limitParameter(),
                Classification.INFORMATIONAL, null, null);
        add(differences, path, "cursorParameter", Direction.INPUT, before.cursorParameter(), after.cursorParameter(),
                Classification.INFORMATIONAL, null, null);
    }

    /**
     * The page-size ceiling the bound adds to its operation, or {@code null} for none: a
     * {@code LIMIT} ceiling at or above the limit parameter's maximum rejects nothing more.
     */
    private static Integer ownCeiling(Operation op, Bound bound) {
        if (!bound.paged() || bound.maxResults() == null) {
            return null;
        }
        BigDecimal maximum = Bound.LIMIT.equals(bound.style()) ? maximum(op, limitedIndex(op, bound)) : null;
        return maximum != null && BigDecimal.valueOf(bound.maxResults()).compareTo(maximum) >= 0
                ? null : bound.maxResults();
    }

    /**
     * The index of the parameter a paged bound's ceiling limits, its {@code limitParameter} (the
     * {@code Pageable} or the limit parameter), or -1 for none. Positions, unlike names, are stable
     * across the two sides of an operation.
     */
    private static int limitedIndex(Operation op, Bound bound) {
        if (!bound.paged() || bound.limitParameter() == null) {
            return -1;
        }
        for (int i = 0; i < op.parameters().size(); i++) {
            if (op.parameters().get(i).name().equals(bound.limitParameter())) {
                return i;
            }
        }
        return -1;
    }

    /** A ceiling with the parameter it limits. */
    private static String onParameter(Integer ceiling, Bound bound) {
        return ceiling + " on '" + bound.limitParameter() + "'";
    }

    /** The largest integer the parameter at {@code index} accepts, or {@code null} for no maximum. */
    private static BigDecimal maximum(Operation op, int index) {
        if (index < 0) {
            return null;
        }
        EffectiveConstraints constraints = op.parameters().get(index).constraints();
        Endpoint upper = constraints != null ? constraints.upper() : null;
        return upper != null ? upper.integralUpper().value() : null;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.min(b);
    }

    /** Whether {@code after} allows more than {@code before}, where {@code null} is no limit. */
    private static boolean widens(Integer before, Integer after) {
        return before != null && (after == null || after > before);
    }

    private static void add(List<Difference> differences, String path, String change, Direction direction,
                            Object before, Object after, Classification classification, String reason,
                            String remedy) {
        if (Objects.equals(before, after)) {
            return;
        }
        boolean breaking = classification == Classification.BREAKING;
        differences.add(new Difference(path, C_BOUND + change, direction, str(before), str(after), classification,
                breaking ? reason : null, breaking ? remedy : null));
    }

    private static String str(Object value) {
        return value != null ? value.toString() : null;
    }
}
