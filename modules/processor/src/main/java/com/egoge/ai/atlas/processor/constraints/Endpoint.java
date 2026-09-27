/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.constraints;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * A bound's value together with its exclusivity, compared as a whole (FR-003, FR-008).
 *
 * @param value     the bound
 * @param exclusive whether the bound itself is excluded
 */
public record Endpoint(BigDecimal value, boolean exclusive) {

    /** Qualified names of the boxed and big integral types whose bounds are compared over integers. */
    static final Set<String> INTEGRAL_BOXES = Set.of("java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.math.BigInteger");
    private static final Set<String> INTEGRAL_PRIMITIVES = Set.of("byte", "short", "int", "long");

    /**
     * Whether bounds on the type are compared over integers (FR-008).
     *
     * @param javaType a canonical Java type, as the Contract IR records it
     * @return whether it is an integral primitive, its box, or {@code BigInteger}
     */
    public static boolean integral(String javaType) {
        return INTEGRAL_PRIMITIVES.contains(javaType) || INTEGRAL_BOXES.contains(javaType);
    }

    /**
     * Whether this lower endpoint admits fewer values than {@code other}: a higher value, or an
     * equal one that is exclusive where {@code other} is not.
     */
    public boolean tighterLowerThan(Endpoint other) {
        int cmp = value.compareTo(other.value);
        return cmp > 0 || cmp == 0 && exclusive && !other.exclusive;
    }

    /**
     * Whether this upper endpoint admits fewer values than {@code other}: a lower value, or an
     * equal one that is exclusive where {@code other} is not.
     */
    public boolean tighterUpperThan(Endpoint other) {
        int cmp = value.compareTo(other.value);
        return cmp < 0 || cmp == 0 && exclusive && !other.exclusive;
    }

    /**
     * The equivalent inclusive lower endpoint over integers (FR-008): {@code > 9} and
     * {@code >= 9.5} both become {@code >= 10}.
     */
    public Endpoint integralLower() {
        BigDecimal ceiling = value.setScale(0, RoundingMode.CEILING);
        if (exclusive && ceiling.compareTo(value) == 0) {
            ceiling = ceiling.add(BigDecimal.ONE);
        }
        return new Endpoint(ceiling, false);
    }

    /**
     * The equivalent inclusive upper endpoint over integers (FR-008): {@code < 10} and
     * {@code <= 9.5} both become {@code <= 9}.
     */
    public Endpoint integralUpper() {
        BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
        if (exclusive && floor.compareTo(value) == 0) {
            floor = floor.subtract(BigDecimal.ONE);
        }
        return new Endpoint(floor, false);
    }

    /** {@code >= v} or {@code > v}. */
    public String renderLower() {
        return (exclusive ? "> " : ">= ") + EffectiveConstraints.decimal(value);
    }

    /** {@code <= v} or {@code < v}. */
    public String renderUpper() {
        return (exclusive ? "< " : "<= ") + EffectiveConstraints.decimal(value);
    }
}
