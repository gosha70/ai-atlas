/**
 * Gradle plugin for the AI-ATLAS framework.
 *
 * <p>Apply with {@code plugins { id("com.egoge.ai-atlas") }} to
 * automatically configure the annotation processor, runtime module,
 * and IntelliJ IDEA generated source directories.
 *
 * <p>Configure via the {@code agentic} extension block:
 * <pre>{@code
 * agentic {
 *     version.set("1.1.0")
 *     mcpEnabled.set(true)
 *     restEnabled.set(true)
 *     openApiEnabled.set(true)
 *     piiPatternsFile.set("/path/to/custom-pii.conf")
 * }
 * }</pre>
 *
 * @see com.egoge.ai.atlas.plugin.AgenticPlugin
 * @see com.egoge.ai.atlas.plugin.AgenticExtension
 */
package com.egoge.ai.atlas.plugin;
