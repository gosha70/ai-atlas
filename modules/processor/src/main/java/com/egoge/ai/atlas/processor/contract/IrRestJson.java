/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.contract;

import com.egoge.ai.atlas.processor.contract.ContractIr.Rest;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The canonical JSON of an operation's {@link Rest} mapping: {@code httpMethod}, {@code path},
 * {@code status} and {@code parameterIn}, every key always present from {@code irVersion} 4.
 */
final class IrRestJson {

    /** The first {@code irVersion} with a mapping's status and parameter locations. */
    static final int MAPPING_VERSION = 4;

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
        map.put(K_STATUS, rest.status());
        map.put(K_PARAMETER_IN, rest.parameterIn());
        return map;
    }

    /**
     * The mapping a {@code rest} object holds, or {@code null} for none. Before
     * {@link #MAPPING_VERSION} every mapping answered 200 with every parameter in the query, so an
     * older one migrates to exactly that.
     *
     * @param node           the {@code rest} object, or {@code null}
     * @param irVersion      the document's version
     * @param parameterCount the operation's number of parameters
     */
    static Rest read(JsonNode node, int irVersion, int parameterCount) {
        if (node == null) {
            return null;
        }
        String httpMethod = IrJson.string(node, K_HTTP_METHOD);
        String path = IrJson.string(node, K_PATH);
        if (irVersion < MAPPING_VERSION) {
            return Rest.rpc(httpMethod, path, parameterCount);
        }
        return new Rest(httpMethod, path, IrJson.integer(node, K_STATUS), IrJson.strings(node, K_PARAMETER_IN));
    }
}
