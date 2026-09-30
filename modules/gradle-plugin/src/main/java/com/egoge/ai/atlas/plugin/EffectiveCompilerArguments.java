/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reduces a compilation's raw {@code -A} compiler argument tokens, such as {@code compileJava}'s
 * effective {@code getOptions().getAllCompilerArgs()}, to one value per key: javac keeps the last
 * value of a repeated {@code -A} option, so this reduction must too.
 */
final class EffectiveCompilerArguments {

    private static final String OPTION_PREFIX = "-A";

    private EffectiveCompilerArguments() {
    }

    /**
     * @param allCompilerArgs raw compiler argument tokens, in javac's effective order; a token that
     *                        is not a well-formed {@code -Akey=value} pair, including a bare
     *                        {@code -A} with no {@code =}, is ignored
     * @return every {@code -Akey=value} token found, last value winning for a repeated key; a value
     *         containing {@code =} is kept whole, as only the first {@code =} splits key from value
     */
    static Map<String, String> lastWins(List<String> allCompilerArgs) {
        Map<String, String> options = new LinkedHashMap<>();
        if (allCompilerArgs == null) {
            return options;
        }
        for (String arg : allCompilerArgs) {
            if (arg == null || !arg.startsWith(OPTION_PREFIX)) {
                continue;
            }
            String rest = arg.substring(OPTION_PREFIX.length());
            int equals = rest.indexOf('=');
            if (equals < 0 || equals == 0) {
                continue;
            }
            options.put(rest.substring(0, equals), rest.substring(equals + 1));
        }
        return options;
    }
}
