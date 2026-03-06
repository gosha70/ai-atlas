/**
 * Gradle plugin for the AI-ATLAS framework.
 *
 * <p>Apply with {@code plugins { id("ai.atlas.gradle-plugin") }} to
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
 * @see ai.atlas.plugin.AgenticPlugin
 * @see ai.atlas.plugin.AgenticExtension
 */
package ai.atlas.plugin;
