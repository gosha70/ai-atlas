/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical JSON of an operation's {@link Rest} mapping. Spike ({@code ai.atlas.rest}):
 * {@code status} and {@code parameterIn} are written only when recorded, so the document is
 * byte-identical with the option off; the real phase would make them always present in a new
 * {@code irVersion}.
 */
final class IrRestJson {

    private static final String K_HTTP_METHOD = "httpMethod";
    private static final String K_PATH = "path";
    private static final String K_STATUS = "status";
    private static final String K_PARAMETER_IN = "parameterIn";

    private IrRestJson() {
    }

    /** The mapping as canonical JSON values, or {@code null} for none. */
    static Map<String, Object> write(Rest rest) {
        if (rest == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(K_HTTP_METHOD, rest.httpMethod());
        map.put(K_PATH, rest.path());
        if (rest.status() != null) {
            map.put(K_STATUS, rest.status());
        }
        if (rest.parameterIn() != null) {
            map.put(K_PARAMETER_IN, rest.parameterIn());
        }
        return map;
    }

    /** The mapping a {@code rest} object holds, or {@code null} for a JSON {@code null}. */
    static Rest read(JsonNode node) {
        if (node == null) {
            return null;
        }
        return new Rest(IrJson.string(node, K_HTTP_METHOD), IrJson.string(node, K_PATH),
                node.has(K_STATUS) ? IrJson.integer(node, K_STATUS) : null,
                node.has(K_PARAMETER_IN) ? IrJson.strings(node, K_PARAMETER_IN) : null);
    }
}
