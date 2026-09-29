/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Bound;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical JSON of an operation's {@code returns.bound}: {@code style}, {@code envelope},
 * {@code limitParameter}, {@code cursorParameter} and {@code maxResults}, every key always present
 * from {@code irVersion} 4.
 */
final class IrBoundJson {

    /** The key of the slot in {@code returns}. */
    static final String K_BOUND = "bound";

    private static final String K_STYLE = "style";
    private static final String K_ENVELOPE = "envelope";
    private static final String K_LIMIT_PARAMETER = "limitParameter";
    private static final String K_CURSOR_PARAMETER = "cursorParameter";
    private static final String K_MAX_RESULTS = "maxResults";

    private IrBoundJson() {
    }

    /** The bound as canonical JSON values. */
    static Map<String, Object> write(Bound bound) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(K_STYLE, bound.style());
        map.put(K_ENVELOPE, bound.envelope());
        map.put(K_LIMIT_PARAMETER, bound.limitParameter());
        map.put(K_CURSOR_PARAMETER, bound.cursorParameter());
        map.put(K_MAX_RESULTS, bound.maxResults());
        return map;
    }

    /**
     * The bound of a {@code returns} object. Nothing before {@link IrRestJson#MAPPING_VERSION} could
     * declare a bound or produce an envelope, so an older return migrates to {@link Bound#NONE}.
     *
     * @param returns   the {@code returns} object
     * @param irVersion the document's version
     */
    static Bound read(JsonNode returns, int irVersion) {
        if (irVersion < IrRestJson.MAPPING_VERSION) {
            return Bound.NONE;
        }
        JsonNode node = IrJson.object(returns, K_BOUND);
        return new Bound(IrJson.string(node, K_STYLE), IrJson.string(node, K_ENVELOPE),
                nullableString(node, K_LIMIT_PARAMETER), nullableString(node, K_CURSOR_PARAMETER),
                nullableInteger(node, K_MAX_RESULTS));
    }

    private static String nullableString(JsonNode node, String key) {
        return present(node, key).isNull() ? null : IrJson.string(node, key);
    }

    private static Integer nullableInteger(JsonNode node, String key) {
        return present(node, key).isNull() ? null : IrJson.integer(node, key);
    }

    private static JsonNode present(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing '" + key + "'");
        }
        return value;
    }
}
