/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;

import java.util.List;
import java.util.Objects;

/**
 * The gate's rules for an operation's result bound, at the published major M. {@code maxResults}
 * is the page-size ceiling of a paged bound ({@code PAGEABLE}, {@code LIMIT}) and the result bound
 * of any other:
 * <ul>
 *   <li>a result bound appearing or falling is compatible; disappearing or rising is a breaking
 *       output change, as clients may rely on receiving at most that many results;</li>
 *   <li>a page-size ceiling appearing or falling is a breaking input change, as larger pages are
 *       rejected; rising or disappearing is compatible;</li>
 *   <li>a changed envelope is a breaking output change;</li>
 *   <li>a paging role declared or removed on an existing parameter, and so the style, is
 *       informational: adding or removing the parameter already changes the operation's identity.</li>
 * </ul>
 * Used through {@link ContractComparison}.
 */
final class BoundComparison {

    private static final String C_BOUND = "returns.bound.";

    private BoundComparison() {
    }

    /**
     * Compares the bounds of an operation active at M on both sides.
     *
     * @param path        the operation's element path
     * @param old         the baseline bound
     * @param now         the fresh bound
     * @param major       the published major M
     * @param differences receives each difference
     */
    static void compare(String path, Bound old, Bound now, int major, List<Difference> differences) {
        String remedy = ContractComparison.newMajorRemedy(major, "a result bound has no lifecycle of its own");
        Integer resultsBefore = old.paged() ? null : old.maxResults();
        Integer resultsAfter = now.paged() ? null : now.maxResults();
        add(differences, path, "maxResults", Direction.OUTPUT, resultsBefore, resultsAfter,
                widens(resultsBefore, resultsAfter) ? Classification.BREAKING : Classification.COMPATIBLE,
                "Clients may rely on receiving at most " + resultsBefore + " results", remedy);
        Integer ceilingBefore = old.paged() ? old.maxResults() : null;
        Integer ceilingAfter = now.paged() ? now.maxResults() : null;
        add(differences, path, "pageSizeCeiling", Direction.INPUT, ceilingBefore, ceilingAfter,
                widens(ceilingAfter, ceilingBefore) ? Classification.BREAKING : Classification.COMPATIBLE,
                "Requests for pages larger than " + ceilingAfter + " are rejected", remedy);
        add(differences, path, "envelope", Direction.OUTPUT, old.envelope(), now.envelope(),
                Classification.BREAKING, "Clients receive the result in this shape", remedy);
        add(differences, path, "style", Direction.INPUT, old.style(), now.style(), Classification.INFORMATIONAL,
                null, null);
        add(differences, path, "limitParameter", Direction.INPUT, old.limitParameter(), now.limitParameter(),
                Classification.INFORMATIONAL, null, null);
        add(differences, path, "cursorParameter", Direction.INPUT, old.cursorParameter(), now.cursorParameter(),
                Classification.INFORMATIONAL, null, null);
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
