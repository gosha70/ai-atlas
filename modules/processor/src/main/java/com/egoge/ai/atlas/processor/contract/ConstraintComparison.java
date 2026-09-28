/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;
import com.egoge.ai.atlas.processor.constraints.Endpoint;
import com.egoge.ai.atlas.processor.contract.ContractGate.Classification;
import com.egoge.ai.atlas.processor.contract.ContractGate.Difference;
import com.egoge.ai.atlas.processor.contract.ContractGate.Direction;
import com.egoge.ai.atlas.processor.contract.ContractIr.Field;
import com.egoge.ai.atlas.processor.contract.ContractIr.Operation;
import com.egoge.ai.atlas.processor.contract.ContractIr.Parameter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.egoge.ai.atlas.processor.contract.ContractGate.PARAMETER_PATH;

/**
 * The gate's rules for an input's requiredness and constraints (FR-008): narrowing is breaking,
 * widening compatible, and a slot unknown on either side, as in a migrated version-1 baseline, is
 * skipped. Also masks unknown baseline slots for lock mode (FR-010). Used by {@link ContractComparison}
 * and {@link ContractGate#documentDifferences}.
 */
final class ConstraintComparison {

    private static final String C_REQUIRED = "required";
    private static final String C_MINIMUM = "minimum";
    private static final String C_MAXIMUM = "maximum";
    private static final String C_MIN_LENGTH = "minLength";
    private static final String C_MAX_LENGTH = "maxLength";
    private static final String C_MIN_ITEMS = "minItems";
    private static final String C_MAX_ITEMS = "maxItems";
    private static final String C_PATTERNS = "patterns";
    private static final String C_NOT_BLANK = "notBlank";
    private static final String NARROWS = "It narrows an input";

    private final String path;
    private final String remedy;
    private final List<Difference> differences;

    private ConstraintComparison(String path, String remedy, List<Difference> differences) {
        this.path = path;
        this.remedy = remedy;
        this.differences = differences;
    }

    /**
     * Compares one parameter's requiredness and constraints, adding each difference, on the path
     * {@code parameter <operation id>.<name>} (FR-011), to {@code differences}.
     *
     * @param op          the baseline operation
     * @param old         the baseline parameter
     * @param now         the fresh parameter at the same index
     * @param remedy      the declaration that would legitimise a narrowing
     * @param differences where the differences are added
     */
    static void compare(Operation op, Parameter old, Parameter now, String remedy, List<Difference> differences) {
        new ConstraintComparison(PARAMETER_PATH + op.id() + "." + old.name(), remedy, differences).run(old, now);
    }

    private void run(Parameter old, Parameter now) {
        if (old.required() != null && now.required() != null && !old.required().equals(now.required())) {
            record(C_REQUIRED, old.required().toString(), now.required().toString(), now.required());
        }
        compareConstraints(old, now);
    }

    private void compareConstraints(Parameter old, Parameter now) {
        compareConstraints(old.constraints(), now.constraints(), old.javaType());
    }

    private void compareConstraints(EffectiveConstraints c0, EffectiveConstraints c1, String javaType) {
        if (c0 == null || c1 == null) {
            return;
        }
        boolean integral = Endpoint.integral(javaType);
        compareEndpoint(C_MINIMUM, c0.lower(), c1.lower(), integral, true);
        compareEndpoint(C_MAXIMUM, c0.upper(), c1.upper(), integral, false);
        compareMin(C_MIN_LENGTH, c0.minLength(), c1.minLength());
        compareMax(C_MAX_LENGTH, c0.maxLength(), c1.maxLength());
        compareMin(C_MIN_ITEMS, c0.minItems(), c1.minItems());
        compareMax(C_MAX_ITEMS, c0.maxItems(), c1.maxItems());
        if (!c0.patterns().equals(c1.patterns())) {
            record(C_PATTERNS, patterns(c0.patterns()), patterns(c1.patterns()),
                    !c0.patterns().containsAll(c1.patterns()));
        }
        if (c0.notBlank() != c1.notBlank()) {
            record(C_NOT_BLANK, flag(c0.notBlank()), flag(c1.notBlank()), c1.notBlank());
        }
    }

    /**
     * A lower or upper endpoint, compared as value and exclusivity together; absent is −∞ or +∞,
     * and over an integral type an exclusive bound is first made inclusive.
     */
    private void compareEndpoint(String key, Endpoint old, Endpoint now, boolean integral, boolean lower) {
        Endpoint e0 = normalise(old, integral, lower);
        Endpoint e1 = normalise(now, integral, lower);
        if (e0 == null && e1 == null
                || e0 != null && e1 != null && e0.value().compareTo(e1.value()) == 0 && e0.exclusive() == e1.exclusive()) {
            return;
        }
        boolean tighter = e0 == null
                || e1 != null && (lower ? e1.tighterLowerThan(e0) : e1.tighterUpperThan(e0));
        record(key, bound(old, lower), bound(now, lower), tighter);
    }

    private static Endpoint normalise(Endpoint endpoint, boolean integral, boolean lower) {
        if (endpoint == null || !integral) {
            return endpoint;
        }
        return lower ? endpoint.integralLower() : endpoint.integralUpper();
    }

    /** A minimum length or item count: rising or appearing narrows. */
    private void compareMin(String key, Integer old, Integer now) {
        if (!Objects.equals(old, now)) {
            record(key, count(old), count(now), now != null && (old == null || now > old));
        }
    }

    /** A maximum length or item count: falling or appearing narrows. */
    private void compareMax(String key, Integer old, Integer now) {
        if (!Objects.equals(old, now)) {
            record(key, count(old), count(now), now != null && (old == null || now < old));
        }
    }

    private void record(String key, String before, String after, boolean narrows) {
        differences.add(narrows
                ? new Difference(path, key, Direction.INPUT, before, after, Classification.BREAKING, NARROWS, remedy)
                : new Difference(path, key, Direction.INPUT, before, after, Classification.COMPATIBLE, null, null));
    }

    private static String count(Integer value) {
        return value != null ? value.toString() : null;
    }

    private static String bound(Endpoint endpoint, boolean lower) {
        if (endpoint == null) {
            return null;
        }
        return lower ? endpoint.renderLower() : endpoint.renderUpper();
    }

    private static String patterns(List<PatternConstraint> patterns) {
        if (patterns.isEmpty()) {
            return null;
        }
        return patterns.stream().map(p -> p.flags().isEmpty() ? p.regex() : p.regex() + " " + p.flags())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String flag(boolean value) {
        return value ? Boolean.TRUE.toString() : null;
    }

    /**
     * Whether two constraint sets on a value of {@code javaType} differ under FR-008, so that
     * {@code > 9} and {@code >= 10} on an integral type do not.
     *
     * @param old      the baseline constraints, never {@code null}
     * @param now      the fresh constraints, never {@code null}
     * @param javaType the value's type as the baseline records it
     * @return whether any key differs
     */
    static boolean differ(EffectiveConstraints old, EffectiveConstraints now, String javaType) {
        List<Difference> differences = new ArrayList<>();
        new ConstraintComparison("", null, differences).compareConstraints(old, now, javaType);
        return !differences.isEmpty();
    }

    // ------------------------------------------------------------ unknown slots in lock mode (FR-010)

    /**
     * {@code now} with the constraints unknown when they are unknown in {@code old}, and the
     * baseline's own when FR-008 finds no difference, so they compare equal.
     */
    static Field knownIn(Field old, Field now) {
        if (old == null || now == null || Objects.equals(old.constraints(), now.constraints())) {
            return now;
        }
        EffectiveConstraints constraints = old.constraints() == null ? null
                : now.constraints() != null && !differ(old.constraints(), now.constraints(), old.javaType())
                        ? old.constraints() : now.constraints();
        return new Field(now.name(), now.displayName(), now.javaType(), now.collectionKind(), now.elementType(),
                now.typeHint(), now.reference(), now.enumType(), now.allowedValues(), now.openEnum(),
                now.sensitive(), now.checkCircularReference(), now.description(), constraints, now.channels(),
                now.lifecycle());
    }

    /** {@code now} with each hint, requiredness and constraint slot unknown where it is unknown in {@code old}. */
    static Operation knownIn(Operation old, Operation now) {
        if (old == null || now == null || old.parameters().size() != now.parameters().size()) {
            return now;
        }
        List<Parameter> parameters = new ArrayList<>();
        for (int i = 0; i < now.parameters().size(); i++) {
            Parameter o = old.parameters().get(i);
            Parameter p = now.parameters().get(i);
            parameters.add(new Parameter(p.name(), p.javaType(), p.description(), p.enumConstants(),
                    o.required() != null ? p.required() : null, lockView(o, p)));
        }
        return new Operation(now.service(), now.method(), now.toolName(), now.channels(), now.description(),
                now.rest(), parameters, now.returns(), old.hints() != null ? now.hints() : null, now.lifecycle());
    }

    /**
     * The constraints lock mode compares for {@code now}: unknown when unknown in the baseline, and
     * the baseline's own when FR-008 finds no difference, as between {@code > 9} and {@code >= 10}
     * on an integral type, so equivalent spellings compare equal.
     */
    private static EffectiveConstraints lockView(Parameter old, Parameter now) {
        if (old.constraints() == null || now.constraints() == null) {
            return null;
        }
        return differ(old.constraints(), now.constraints(), old.javaType()) ? now.constraints() : old.constraints();
    }
}
