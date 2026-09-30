/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.util;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.Map;

/**
 * The version options of a compilation: the REST base path, the configured major and the OpenAPI
 * {@code info.version}.
 *
 * @param apiBasePath        {@code ai.atlas.api.basePath}, without trailing slashes, default {@code /api}
 * @param apiMajor           {@code ai.atlas.api.major}, default 1
 * @param openApiInfoVersion {@code ai.atlas.openapi.infoVersion}, default {@code <apiMajor>.0.0}
 */
public record VersionConfig(String apiBasePath, int apiMajor, String openApiInfoVersion) {

    /** The option naming the REST base path. */
    public static final String OPT_API_BASE_PATH = "ai.atlas.api.basePath";
    /** The option naming the configured major. */
    public static final String OPT_API_MAJOR = "ai.atlas.api.major";
    /** The option naming the OpenAPI document's {@code info.version}. */
    public static final String OPT_OPENAPI_INFO_VERSION = "ai.atlas.openapi.infoVersion";

    /**
     * Reads the version options, reporting an ERROR for the first invalid one.
     *
     * @return the configuration, or {@code null} after reporting an invalid option
     */
    public static VersionConfig resolve(Map<String, String> opts, Messager msg) {
        String apiBasePath = opts.getOrDefault(OPT_API_BASE_PATH, "/api");
        if (!apiBasePath.startsWith("/")) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.basePath must start with '/'. Got: " + apiBasePath);
            return null;
        }
        while (apiBasePath.endsWith("/") && apiBasePath.length() > 1) {
            apiBasePath = apiBasePath.substring(0, apiBasePath.length() - 1);
        }
        if ("/".equals(apiBasePath)) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.basePath must not be '/'. Use a path like '/api'.");
            return null;
        }
        String majorStr = opts.getOrDefault(OPT_API_MAJOR, "1");
        int apiMajor;
        try {
            apiMajor = Integer.parseInt(majorStr);
        } catch (NumberFormatException e) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.major must be an integer. Got: " + majorStr);
            return null;
        }
        if (apiMajor < 1) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.api.major must be a positive integer. Got: " + majorStr);
            return null;
        }
        String infoVersionRaw = opts.get(OPT_OPENAPI_INFO_VERSION);
        String openApiInfoVersion = infoVersionRaw != null ? infoVersionRaw : (apiMajor + ".0.0");
        if (openApiInfoVersion.isBlank()) {
            msg.printMessage(Diagnostic.Kind.ERROR,
                    "[ai-atlas] ai.atlas.openapi.infoVersion must not be empty");
            return null;
        }
        return new VersionConfig(apiBasePath, apiMajor, openApiInfoVersion);
    }
}
