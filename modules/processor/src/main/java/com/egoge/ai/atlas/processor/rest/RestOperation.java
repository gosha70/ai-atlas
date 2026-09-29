/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.rest;

import com.palantir.javapoet.ClassName;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The resolved REST mapping of one API operation: the one normalised model the Contract IR records
 * and the controller, the OpenAPI document, the route collision check and the deprecation manifest
 * all read. It is independent of the base path and major; a generated route prefixes
 * {@code <apiBasePath>/v<major>}.
 *
 * @param httpMethod  {@code GET}, {@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}
 * @param resource    the service's resource, one path segment without slashes
 * @param path        the path below the resource, {@code ""} or {@code /}-separated segments, each a
 *                    literal or a {@code {name}} variable
 * @param status      the success status, a 2xx code Spring's {@code HttpStatus} names
 * @param parameterIn each parameter's location, {@link #PATH}, {@link #QUERY} or {@link #BODY}, in
 *                    declaration order
 * @param inputRecord the whitelisted input record the body binds when the body parameter is an
 *                    {@code @AgenticEntity}, or {@code null}
 */
public record RestOperation(String httpMethod, String resource, String path, int status,
                            List<String> parameterIn, ClassName inputRecord) {

    /** A path parameter. */
    public static final String PATH = "PATH";
    /** A query parameter. */
    public static final String QUERY = "QUERY";
    /** The request body. */
    public static final String BODY = "BODY";
    /** {@code GET}. */
    public static final String GET = "GET";
    /** {@code POST}. */
    public static final String POST = "POST";
    /** {@code DELETE}. */
    public static final String DELETE = "DELETE";
    /** The success status of every operation that declares or derives no other. */
    public static final int DEFAULT_STATUS = 200;
    /** {@code 204 No Content}. */
    public static final int NO_CONTENT = 204;

    /** The 2xx statuses Spring's {@code HttpStatus} names, the only ones a generated controller can declare. */
    static final Map<Integer, String> SUCCESS_STATUSES = Map.of(
            200, "OK", 201, "CREATED", 202, "ACCEPTED", 203, "NON_AUTHORITATIVE_INFORMATION", 204, "NO_CONTENT",
            205, "RESET_CONTENT", 206, "PARTIAL_CONTENT", 207, "MULTI_STATUS", 208, "ALREADY_REPORTED",
            226, "IM_USED");

    public RestOperation {
        parameterIn = List.copyOf(parameterIn);
    }

    /** The RPC mapping: GET without parameters and POST with them, at {@code /<resource>/<rpcPath>}, every parameter in the query. */
    static RestOperation rpc(String resource, String methodKebab, int parameterCount) {
        return new RestOperation(parameterCount == 0 ? GET : POST, resource, "/" + methodKebab, DEFAULT_STATUS,
                Collections.nCopies(parameterCount, QUERY), null);
    }

    /** The route below the major, {@code /<resource><path>}, as the Contract IR records it. */
    public String fullPath() {
        return "/" + resource + path;
    }

    /**
     * @param index a parameter's position
     * @return its location, {@link #PATH}, {@link #QUERY} or {@link #BODY}
     */
    public String in(int index) {
        return parameterIn.get(index);
    }

    /** The position of the body parameter, or {@code -1} when the operation has no request body. */
    public int bodyIndex() {
        return parameterIn.indexOf(BODY);
    }

    /** Spring's {@code HttpStatus} constant of {@link #status()}, such as {@code CREATED}. */
    public String statusName() {
        return SUCCESS_STATUSES.get(status);
    }

    /**
     * The route as Spring matches it: the HTTP method and the path, every {@code {name}} variable
     * written {@code {}}, so routes that differ only by variable names have the same key.
     *
     * @param httpMethod the HTTP method
     * @param path       a path, possibly with {@code {name}} variables
     * @return {@code METHOD path}
     */
    public static String routeKey(String httpMethod, String path) {
        return httpMethod + " " + path.replaceAll("\\{[^}/]*}", "{}");
    }
}
