/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;
import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The agreement between an operation's slots that {@link IrJson} requires of every document it reads. */
final class IrConsistency {

    private static final String API_CHANNEL = "API";
    /** A {@code {name}} variable of a REST path. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]*)}");

    private IrConsistency() {
    }

    /**
     * The operation, once its slots agree with each other: a hand-edited baseline that contradicts
     * itself could hide a change from the gate. The operation has a REST mapping exactly when it is
     * on the API channel; each {@code PATH} parameter is named by a {@code {name}} variable of the
     * path, and each variable names a {@code PATH} parameter, so the gate can tell which parameter
     * every path position binds; at most one parameter is the {@code BODY}; and the bound's limit
     * and cursor parameters are parameters of the operation.
     *
     * @throws IllegalArgumentException naming the operation and the contradiction
     */
    static Operation check(Operation op) {
        String where = "operation '" + op.id() + "'";
        Rest rest = op.rest();
        boolean api = op.channels().contains(API_CHANNEL);
        if (api && rest == null) {
            throw new IllegalArgumentException(where + " is on the API channel, so 'rest' must not be null");
        }
        if (!api && rest != null) {
            throw new IllegalArgumentException(where + " is not on the API channel, so 'rest' must be null");
        }
        if (rest != null) {
            List<String> variables = new ArrayList<>();
            Matcher matcher = PATH_VARIABLE.matcher(rest.path());
            while (matcher.find()) {
                variables.add(matcher.group(1));
            }
            List<String> pathParameters = new ArrayList<>();
            int bodies = 0;
            for (int i = 0; i < op.parameters().size(); i++) {
                String name = op.parameters().get(i).name();
                if (Rest.PATH.equals(rest.in(i)) && !variables.contains(name)) {
                    throw new IllegalArgumentException(where + ": PATH parameter '" + name + "' has no {" + name
                            + "} variable in the path " + rest.path());
                }
                if (Rest.PATH.equals(rest.in(i))) {
                    pathParameters.add(name);
                }
                if (Rest.BODY.equals(rest.in(i)) && ++bodies > 1) {
                    throw new IllegalArgumentException(where + ": more than one parameter is the BODY");
                }
            }
            for (String variable : variables) {
                if (!pathParameters.contains(variable)) {
                    throw new IllegalArgumentException(where + ": the path variable {" + variable + "} of "
                            + rest.path() + " names no PATH parameter " + pathParameters);
                }
            }
        }
        Bound bound = op.returns().bound();
        List<String> names = op.parameters().stream().map(Parameter::name).toList();
        for (String parameter : new String[] {bound.limitParameter(), bound.cursorParameter()}) {
            if (parameter != null && !names.contains(parameter)) {
                throw new IllegalArgumentException(where + ": the bound names '" + parameter
                        + "', which is not one of its parameters " + names);
            }
        }
        return op;
    }
}
