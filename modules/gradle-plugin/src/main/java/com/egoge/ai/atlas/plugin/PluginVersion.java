/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.GradleException;

import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSource;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** This plugin's own version, and the ai-atlas dependency version it selects by default. */
final class PluginVersion {

    private static final Pattern PLUGIN_JAR = Pattern.compile("-(\\d[^/]*)\\.jar$");
    /** The resource, next to this class, holding the plugin's version, written by its build. */
    static final String VERSION_RESOURCE = "ai-atlas-plugin.properties";

    private PluginVersion() {
    }

    /**
     * The ai-atlas version {@code agentic { version }} defaults to: this plugin's own version.
     *
     * @param pluginVersion the plugin's version, or {@code null} when it cannot be determined
     * @return the version
     * @throws GradleException when {@code pluginVersion} is {@code null}, asking for an explicit
     *                         {@code agentic { version }}; it never falls back to the project version
     */
    static String dependencyVersion(String pluginVersion) {
        if (pluginVersion == null) {
            throw new GradleException("The ai-atlas Gradle plugin cannot determine its own version (its classes"
                    + " were loaded without the version resource its build writes and without jar metadata), so it"
                    + " cannot choose the ai-atlas dependency version. Set it explicitly:"
                    + " agentic { version.set(\"<ai-atlas version>\") }. The project version is never used for it.");
        }
        return pluginVersion;
    }

    /**
     * This plugin's version: the {@value #VERSION_RESOURCE} resource its build writes, else the
     * jar's {@code Implementation-Version}, else the version in the jar's file name.
     *
     * @return the version, or {@code null} when none of these is available
     */
    static String ownVersion() {
        try (InputStream in = PluginVersion.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version.trim();
                }
            }
        } catch (IOException e) {
            // fall through to the jar's metadata
        }
        String version = PluginVersion.class.getPackage().getImplementationVersion();
        if (version == null) {
            CodeSource source = PluginVersion.class.getProtectionDomain().getCodeSource();
            Matcher jar = source == null ? null : PLUGIN_JAR.matcher(source.getLocation().getPath());
            version = jar != null && jar.find() ? jar.group(1) : null;
        }
        return version;
    }

    /** This plugin's version for messages: {@link #ownVersion()}, or a placeholder when unknown. */
    static String pluginVersion() {
        String version = ownVersion();
        return version != null ? version : "(development build)";
    }
}
