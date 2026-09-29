/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.plugin;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests of the default ai-atlas dependency version: the plugin's own version, and a failure, never
 * the project version, when that cannot be determined.
 */
class DependencyVersionTest {

    @Test
    void thePluginReadsItsOwnVersionFromTheResourceItsBuildWrites() {
        assertThat(AgenticPlugin.ownVersion()).isEqualTo(System.getProperty("ai.atlas.test.version"));
    }

    @Test
    void theDefaultIsThePluginsOwnVersion() {
        assertThat(AgenticPlugin.dependencyVersion("1.4.2")).isEqualTo("1.4.2");
    }

    @Test
    void aDevelopmentBuildRequiresAnExplicitVersion() {
        assertThatThrownBy(() -> AgenticPlugin.dependencyVersion(null))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("cannot determine its own version")
                .hasMessageContaining("agentic { version.set(\"<ai-atlas version>\") }")
                .hasMessageContaining("The project version is never used for it");
    }
}
