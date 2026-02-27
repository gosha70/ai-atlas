/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package ai.adam.plugin;

import org.gradle.api.provider.Property;

/**
 * Extension object for configuring the AI-ADAM Gradle plugin.
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
     * AI-ADAM framework version. Defaults to the project version.
     */
    public abstract Property<String> getVersion();

    /**
     * Maven group for AI-ADAM modules. Defaults to "ai.adam".
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
}
