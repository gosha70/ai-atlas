/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints;
import com.egoge.ai.atlas.processor.constraints.EffectiveConstraints.PatternConstraint;
import com.egoge.ai.atlas.processor.contract.ContractIr.Hints;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The canonical JSON of the {@code irVersion} 2 slots (FR-005, FR-006): a {@code constraints}
 * object with only its set keys, in the fixed FR-005 order, and a {@code hints} object with only
 * the declared hints. Used through {@link IrJson}.
 */
final class IrConstraintsJson {

    static final String K_CONSTRAINTS = "constraints";
    static final String K_HINTS = "hints";
    private static final String K_MINIMUM = "minimum";
    private static final String K_EXCLUSIVE_MINIMUM = "exclusiveMinimum";
    private static final String K_MAXIMUM = "maximum";
    private static final String K_EXCLUSIVE_MAXIMUM = "exclusiveMaximum";
    private static final String K_MIN_LENGTH = "minLength";
    private static final String K_MAX_LENGTH = "maxLength";
    private static final String K_MIN_ITEMS = "minItems";
    private static final String K_MAX_ITEMS = "maxItems";
    private static final String K_PATTERNS = "patterns";
    private static final String K_NOT_BLANK = "notBlank";
    private static final String K_REGEX = "regex";
    private static final String K_FLAGS = "flags";
    private static final String K_READ_ONLY = "readOnly";
    private static final String K_DESTRUCTIVE = "destructive";
    private static final String K_IDEMPOTENT = "idempotent";
    private static final String K_OPEN_WORLD = "openWorld";

    private static final Set<String> CONSTRAINT_KEYS = Set.of(K_MINIMUM, K_EXCLUSIVE_MINIMUM, K_MAXIMUM,
            K_EXCLUSIVE_MAXIMUM, K_MIN_LENGTH, K_MAX_LENGTH, K_MIN_ITEMS, K_MAX_ITEMS, K_PATTERNS, K_NOT_BLANK);
    private static final Set<String> HINT_KEYS = Set.of(K_READ_ONLY, K_DESTRUCTIVE, K_IDEMPOTENT, K_OPEN_WORLD);
    private static final Set<String> PATTERN_KEYS = Set.of(K_REGEX, K_FLAGS);

    private IrConstraintsJson() {
    }

    /** The set keys only, in the FR-005 order; {@code null} (unknown) stays {@code null}. */
    static Map<String, Object> write(EffectiveConstraints c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        putIfSet(map, K_MINIMUM, c.minimum());
        putIfSet(map, K_EXCLUSIVE_MINIMUM, c.exclusiveMinimum() ? Boolean.TRUE : null);
        putIfSet(map, K_MAXIMUM, c.maximum());
        putIfSet(map, K_EXCLUSIVE_MAXIMUM, c.exclusiveMaximum() ? Boolean.TRUE : null);
        putIfSet(map, K_MIN_LENGTH, c.minLength());
        putIfSet(map, K_MAX_LENGTH, c.maxLength());
        putIfSet(map, K_MIN_ITEMS, c.minItems());
        putIfSet(map, K_MAX_ITEMS, c.maxItems());
        if (!c.patterns().isEmpty()) {
            List<Map<String, Object>> patterns = new ArrayList<>();
            for (PatternConstraint pattern : c.patterns()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put(K_REGEX, pattern.regex());
                p.put(K_FLAGS, pattern.flags());
                patterns.add(p);
            }
            map.put(K_PATTERNS, patterns);
        }
        putIfSet(map, K_NOT_BLANK, c.notBlank() ? Boolean.TRUE : null);
        return map;
    }

    /** The declared hints only; {@code null} (unknown) stays {@code null}. */
    static Map<String, Object> write(Hints hints) {
        if (hints == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        putIfSet(map, K_READ_ONLY, hints.readOnly());
        putIfSet(map, K_DESTRUCTIVE, hints.destructive());
        putIfSet(map, K_IDEMPOTENT, hints.idempotent());
        putIfSet(map, K_OPEN_WORLD, hints.openWorld());
        return map;
    }

    private static void putIfSet(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** A version-2 {@code constraints} object: present, not {@code null}, with known keys only (FR-006). */
    static EffectiveConstraints readConstraints(JsonNode parent) {
        JsonNode node = IrJson.object(parent, K_CONSTRAINTS);
        onlyKeys(node, K_CONSTRAINTS, CONSTRAINT_KEYS);
        List<PatternConstraint> patterns = !node.has(K_PATTERNS) ? List.of() : IrJson.list(node, K_PATTERNS, p -> {
            onlyKeys(p, K_PATTERNS, PATTERN_KEYS);
            return new PatternConstraint(IrJson.string(p, K_REGEX), IrJson.strings(p, K_FLAGS));
        });
        return new EffectiveConstraints(optionalDecimal(node, K_MINIMUM), optionalFlag(node, K_EXCLUSIVE_MINIMUM),
                optionalDecimal(node, K_MAXIMUM), optionalFlag(node, K_EXCLUSIVE_MAXIMUM),
                optionalInteger(node, K_MIN_LENGTH), optionalInteger(node, K_MAX_LENGTH),
                optionalInteger(node, K_MIN_ITEMS), optionalInteger(node, K_MAX_ITEMS), patterns,
                optionalFlag(node, K_NOT_BLANK));
    }

    /** A version-2 {@code hints} object: present, not {@code null}, with known keys only. */
    static Hints readHints(JsonNode parent) {
        JsonNode node = IrJson.object(parent, K_HINTS);
        onlyKeys(node, K_HINTS, HINT_KEYS);
        return new Hints(optionalBool(node, K_READ_ONLY), optionalBool(node, K_DESTRUCTIVE),
                optionalBool(node, K_IDEMPOTENT), optionalBool(node, K_OPEN_WORLD));
    }

    private static void onlyKeys(JsonNode node, String key, Set<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown key '" + name + "' in '" + key + "'");
            }
        }
    }

    private static String optionalDecimal(JsonNode node, String key) {
        if (!node.has(key)) {
            return null;
        }
        try {
            return EffectiveConstraints.decimal(new BigDecimal(IrJson.string(node, key)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' must be a decimal string");
        }
    }

    private static Integer optionalInteger(JsonNode node, String key) {
        return node.has(key) ? IrJson.integer(node, key) : null;
    }

    private static Boolean optionalBool(JsonNode node, String key) {
        return node.has(key) ? IrJson.bool(node, key) : null;
    }

    private static boolean optionalFlag(JsonNode node, String key) {
        return node.has(key) && IrJson.bool(node, key);
    }
}
