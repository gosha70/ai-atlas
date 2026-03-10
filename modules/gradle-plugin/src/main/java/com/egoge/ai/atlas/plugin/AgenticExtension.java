/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.provider.Property;

/**
 * Extension object for configuring the AI-ATLAS Gradle plugin.
 *
 * <p>Example usage in {@code build.gradle.kts}:
 * <pre>
 * agentic {
 *     version.set("0.1.0")
 *     mcpEnabled.set(true)
 *     restEnabled.set(true)
 * }
 * </pre>
 */
public abstract class AgenticExtension {

    /**
     * AI-ATLAS framework version. Defaults to the project version.
     */
    public abstract Property<String> getVersion();

    /**
     * Maven group for AI-ATLAS modules. Defaults to "ai.atlas".
     */
    public abstract Property<String> getGroup();

    /**
     * Enable MCP tool generation and runtime support. Defaults to true.
     */
    public abstract Property<Boolean> getMcpEnabled();

    /**
     * Enable REST controller generation and runtime support. Defaults to true.
     */
    public abstract Property<Boolean> getRestEnabled();

    /**
     * Enable OpenAPI spec generation. Defaults to true.
     */
    public abstract Property<Boolean> getOpenApiEnabled();

    /**
     * Path to a custom PII patterns file. Each line is a regex fragment;
     * lines starting with {@code #} are comments. When set, the custom file
     * replaces the built-in defaults. The {@code -Aai.atlas.pii.patterns}
     * additive option still works on top.
     */
    public abstract Property<String> getPiiPatternsFile();

    /**
     * Base path prefix for generated REST endpoints. Defaults to "/api".
     * Must start with "/" and must not end with "/".
     * Generated path formula: {basePath}/v{majorVersion}/{service-kebab}/{method-kebab}
     */
    public abstract Property<String> getApiBasePath();

    /**
     * Major API version number for generated REST endpoints and OpenAPI paths.
     * Defaults to 1. Must be a positive integer.
     * Appears in URL as /v{majorVersion}/.
     */
    public abstract Property<Integer> getApiMajorVersion();

    /**
     * Version string for the OpenAPI info.version field.
     * Defaults to "{majorVersion}.0.0". Should follow SemVer.
     * This is the API document version, not the URL path version.
     */
    public abstract Property<String> getOpenApiInfoVersion();
}
