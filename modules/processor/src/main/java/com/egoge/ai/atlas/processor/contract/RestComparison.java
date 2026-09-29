/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gate's rules for an operation's REST mapping, on the API channel at the published major M:
 * a changed HTTP method or path is a breaking input change, unless the path differs only in its
 * {@code {name}} variables, which clients never see, and each variable position still binds the
 * same parameter; a changed success status is a breaking output
 * change, and a changed parameter location a breaking input one. Renaming a path or body
 * parameter of an operation served only on the API channel is compatible, as REST clients never
 * send its name; MCP clients do. Used through {@link ContractComparison}.
 */
final class RestComparison {

    private static final String C_HTTP_METHOD = "rest.httpMethod";
    private static final String C_REST_PATH = "rest.path";
    private static final String C_REST_STATUS = "rest.status";
    private static final String C_PARAMETER_IN = "rest.parameterIn";
    private static final List<String> API_ONLY = List.of("API");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]*)}");

    private RestComparison() {
    }

    /**
     * Compares the mappings of an operation present on the API channel on both sides; nothing when
     * either has none, as losing or gaining the channel is a channels change.
     *
     * @param path            the operation's element path
     * @param old             the baseline operation
     * @param now             the fresh operation, with the same identity
     * @param major           the published major M
     * @param operationRemedy the remedy for a change a new operation legitimises
     * @param differences     receives each difference
     */
    static void compare(String path, Operation old, Operation now, int major, String operationRemedy,
                        List<Difference> differences) {
        Rest before = old.rest();
        Rest after = now.rest();
        if (before == null || after == null) {
            return;
        }
        diff(differences, path, C_HTTP_METHOD, Direction.INPUT, before.httpMethod(), after.httpMethod(), true,
                "REST clients call the operation with this HTTP method", operationRemedy);
        diff(differences, path, C_REST_PATH, Direction.INPUT, before.path(), after.path(),
                !before.route().equals(after.route()) || rebound(variableBindings(old), variableBindings(now)),
                "REST clients call the operation at this path, each segment bound to its parameter", operationRemedy);
        String remedy = ContractComparison.newMajorRemedy(major, "a REST mapping has no lifecycle of its own");
        diff(differences, path, C_REST_STATUS, Direction.OUTPUT, String.valueOf(before.status()),
                String.valueOf(after.status()), true, "REST clients check the success status", remedy);
        int shared = Math.min(before.parameterIn().size(), after.parameterIn().size());
        for (int i = 0; i < shared; i++) {
            diff(differences, path, C_PARAMETER_IN + "[" + i + "]", Direction.INPUT, before.in(i), after.in(i),
                    true, "REST clients send the parameter '" + old.parameters().get(i).name() + "' in the "
                            + before.in(i).toLowerCase(Locale.ROOT), remedy);
        }
    }

    /**
     * The parameter each path variable binds, by position in the path: the index of the parameter
     * named by the variable, or -1. Swapping two variables' names swaps the parameters existing
     * clients' values reach, although the route is unchanged.
     */
    private static List<Integer> variableBindings(Operation op) {
        List<Integer> bindings = new ArrayList<>();
        Matcher variable = PATH_VARIABLE.matcher(op.rest().path());
        while (variable.find()) {
            int index = -1;
            for (int i = 0; i < op.parameters().size() && index < 0; i++) {
                if (op.parameters().get(i).name().equals(variable.group(1))) {
                    index = i;
                }
            }
            bindings.add(index);
        }
        return bindings;
    }

    /**
     * Whether a path variable now binds another parameter. A variable that names no parameter, which
     * {@link IrConsistency} rejects in every document read from disk, can occur only in an IR built in
     * memory; it leaves the bindings unknown, and only the route counts.
     */
    private static boolean rebound(List<Integer> before, List<Integer> after) {
        return !before.contains(-1) && !after.contains(-1) && !before.equals(after);
    }

    /**
     * Whether renaming the parameter at {@code index} is invisible to every client: the operation
     * is served only on the API channel on both sides, and the parameter is a path variable or the
     * body on both, so no client sends its name.
     */
    static boolean renameIsCompatible(Operation old, Operation now, int index) {
        return old.rest() != null && now.rest() != null && API_ONLY.equals(old.channels())
                && API_ONLY.equals(now.channels()) && unnamed(old.rest().in(index)) && unnamed(now.rest().in(index));
    }

    private static boolean unnamed(String location) {
        return Rest.PATH.equals(location) || Rest.BODY.equals(location);
    }

    private static void diff(List<Difference> differences, String path, String change, Direction direction,
                             String before, String after, boolean breaking, String reason, String remedy) {
        if (Objects.equals(before, after)) {
            return;
        }
        differences.add(breaking
                ? new Difference(path, change, direction, before, after, Classification.BREAKING, reason, remedy)
                : new Difference(path, change, direction, before, after, Classification.COMPATIBLE, null, null));
    }
}
