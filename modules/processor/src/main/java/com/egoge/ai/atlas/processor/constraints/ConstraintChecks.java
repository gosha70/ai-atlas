/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.egoge.ai.atlas.processor.constraints.ConstraintReader.K_MAX_ITEMS;
import static com.egoge.ai.atlas.processor.constraints.ConstraintReader.K_MAX_LENGTH;
import static com.egoge.ai.atlas.processor.constraints.ConstraintReader.K_MIN_ITEMS;
import static com.egoge.ai.atlas.processor.constraints.ConstraintReader.K_MIN_LENGTH;

/**
 * FR-004's self-contradiction checks, run on the intersected Bean Validation constraints and on the
 * final effective contract.
 */
final class ConstraintChecks {

    /** Maps {@code jakarta.validation.constraints.Pattern.Flag} names to {@link Pattern} flags. */
    private static final Map<String, Integer> FLAGS = Map.of(
            "UNIX_LINES", Pattern.UNIX_LINES,
            "CASE_INSENSITIVE", Pattern.CASE_INSENSITIVE,
            "COMMENTS", Pattern.COMMENTS,
            "MULTILINE", Pattern.MULTILINE,
            "DOTALL", Pattern.DOTALL,
            "UNICODE_CASE", Pattern.UNICODE_CASE,
            "CANON_EQ", Pattern.CANON_EQ);

    private ConstraintChecks() {
    }

    /**
     * Adds a message to {@code errors} for each contradiction in {@code c}.
     *
     * @param c      the constraints to check
     * @param type   the constrained element's type
     * @param what   names the element in messages
     * @param errors collects the messages
     */
    static void check(EffectiveConstraints c, ConstrainedType type, String what, Set<String> errors) {
        Endpoint lower = c.lower();
        Endpoint upper = c.upper();
        if ((lower != null || upper != null) && !type.numeric()) {
            errors.add("Bounds on " + what + " of non-numeric type " + type.type());
        }
        if (lower != null && upper != null) {
            Endpoint lo = type.integral() ? lower.integralLower() : lower;
            Endpoint hi = type.integral() ? upper.integralUpper() : upper;
            int cmp = lo.value().compareTo(hi.value());
            if (cmp > 0 || cmp == 0 && (lo.exclusive() || hi.exclusive())) {
                errors.add("Contradictory constraints on " + what + ": no value satisfies both "
                        + lower.renderLower() + " and " + upper.renderUpper());
            }
        }
        checkRange(K_MIN_LENGTH, c.minLength(), K_MAX_LENGTH, c.maxLength(), what, errors);
        checkRange(K_MIN_ITEMS, c.minItems(), K_MAX_ITEMS, c.maxItems(), what, errors);
        if ((c.minLength() != null || c.maxLength() != null) && !type.string()) {
            errors.add("Length constraints on " + what + " of non-string type " + type.type());
        }
        if ((c.minItems() != null || c.maxItems() != null) && !type.items()) {
            errors.add("Item constraints on " + what + " of type " + type.type()
                    + ", which is neither a collection nor an array");
        }
        for (PatternConstraint pattern : c.patterns()) {
            int flags = 0;
            for (String flag : pattern.flags()) {
                flags |= FLAGS.getOrDefault(flag, 0);
            }
            try {
                Pattern.compile(pattern.regex(), flags);
            } catch (PatternSyntaxException e) {
                errors.add("The pattern '" + pattern.regex() + "' on " + what + " does not compile as a Java regex: "
                        + e.getDescription());
            }
        }
    }

    private static void checkRange(String minKey, Integer min, String maxKey, Integer max, String what,
                                   Set<String> errors) {
        if (min != null && min < 0) {
            errors.add("Negative " + minKey + " " + min + " on " + what);
        }
        if (max != null && max < 0) {
            errors.add("Negative " + maxKey + " " + max + " on " + what);
        }
        if (min != null && max != null && min > max) {
            errors.add("Contradictory constraints on " + what + ": " + minKey + " " + min + " is above "
                    + maxKey + " " + max);
        }
    }
}
