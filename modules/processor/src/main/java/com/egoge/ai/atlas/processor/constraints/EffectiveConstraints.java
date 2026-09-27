/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The effective constraints of an input or field after FR-003's precedence, in ai-atlas's own
 * normalised form (FR-005), which is neither schema dialect. Unset keys are {@code null} (or
 * {@code false} for the booleans, empty for {@code patterns}); components are in the FR-005 key order.
 *
 * @param minimum          lower bound as a canonical decimal string, or {@code null}
 * @param exclusiveMinimum whether {@code minimum} is exclusive
 * @param maximum          upper bound as a canonical decimal string, or {@code null}
 * @param exclusiveMaximum whether {@code maximum} is exclusive
 * @param minLength        minimum string length, or {@code null}
 * @param maxLength        maximum string length, or {@code null}
 * @param minItems         minimum collection or array size, or {@code null}
 * @param maxItems         maximum collection or array size, or {@code null}
 * @param patterns         patterns the whole value must each match, sorted by regex then flags
 * @param notBlank         whether the value must hold a code unit above U+0020 (FR-004a)
 */
public record EffectiveConstraints(String minimum, boolean exclusiveMinimum, String maximum,
                                   boolean exclusiveMaximum, Integer minLength, Integer maxLength,
                                   Integer minItems, Integer maxItems, List<PatternConstraint> patterns,
                                   boolean notBlank) {

    /** No constraint set. */
    public static final EffectiveConstraints NONE =
            new EffectiveConstraints(null, false, null, false, null, null, null, null, List.of(), false);

    /** Orders patterns by regex, then flags (FR-005). */
    public static final Comparator<PatternConstraint> PATTERN_ORDER = Comparator
            .comparing(PatternConstraint::regex)
            .thenComparing(p -> String.join(",", p.flags()));

    public EffectiveConstraints {
        List<PatternConstraint> sorted = new ArrayList<>(patterns);
        sorted.sort(PATTERN_ORDER);
        patterns = List.copyOf(sorted);
    }

    /**
     * A Bean Validation {@code @Pattern}: the whole value must match {@code regex} compiled with
     * {@code flags}.
     *
     * @param regex the Java regular expression
     * @param flags names of {@code jakarta.validation.constraints.Pattern.Flag} constants, sorted
     */
    public record PatternConstraint(String regex, List<String> flags) {

        public PatternConstraint {
            flags = flags.stream().distinct().sorted().toList();
        }
    }

    /** The lower endpoint, or {@code null} when {@code minimum} is not set. */
    public Endpoint lower() {
        return minimum == null ? null : new Endpoint(new BigDecimal(minimum), exclusiveMinimum);
    }

    /** The upper endpoint, or {@code null} when {@code maximum} is not set. */
    public Endpoint upper() {
        return maximum == null ? null : new Endpoint(new BigDecimal(maximum), exclusiveMaximum);
    }

    /** Whether no key is set. */
    public boolean isEmpty() {
        return equals(NONE);
    }

    /**
     * The canonical decimal string of {@code value}: plain notation, no trailing fractional zeros,
     * so {@code 10}, {@code 10.0} and {@code 1E+1} are all {@code "10"}.
     */
    public static String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
