/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.runtime.autoconfigure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link VersionMismatchDetector}.
 * Uses the package-private {@code check(Api, ClassLoader)} overload to inject
 * different classpath resources in each test scenario.
 */
class VersionMismatchDetectorTest {

    @TempDir
    Path tempDir;

    private Logger detectorLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachLogAppender() {
        detectorLogger = (Logger) LoggerFactory.getLogger(VersionMismatchDetector.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        detectorLogger.addAppender(listAppender);
        detectorLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogAppender() {
        detectorLogger.detachAppender(listAppender);
        listAppender.stop();
    }

    /** Writes api-version.properties to a temp dir and returns a URLClassLoader backed by it. */
    private URLClassLoader classLoaderWith(String propertiesContent) throws Exception {
        Path metaInf = tempDir.resolve("META-INF/ai-atlas");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("api-version.properties"), propertiesContent,
                StandardCharsets.UTF_8);
        return new URLClassLoader(new URL[]{tempDir.toUri().toURL()}, null);
    }

    /** Constructs an Api with the given major and basePath for testing. */
    private AgenticProperties.Api apiWith(int major, String basePath) {
        var api = new AgenticProperties.Api();
        api.setMajor(major);
        api.setBasePath(basePath);
        return api;
    }

    @Test
    void matchingConfig_noWarning() throws Exception {
        try (var cl = classLoaderWith("api.major=1\napi.basePath=/api\n")) {
            VersionMismatchDetector.check(apiWith(1, "/api"), cl);
        }
        boolean hasWarning = listAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(hasWarning).isFalse();
    }

    @Test
    void majorMismatch_emitsWarning() throws Exception {
        try (var cl = classLoaderWith("api.major=2\napi.basePath=/api\n")) {
            VersionMismatchDetector.check(apiWith(1, "/api"), cl);
        }
        boolean hasWarning = listAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Version mismatch"));
        assertThat(hasWarning).isTrue();
    }

    @Test
    void basePathMismatch_emitsWarning() throws Exception {
        try (var cl = classLoaderWith("api.major=1\napi.basePath=/services\n")) {
            VersionMismatchDetector.check(apiWith(1, "/api"), cl);
        }
        boolean hasWarning = listAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Base path mismatch"));
        assertThat(hasWarning).isTrue();
    }

    @Test
    void propertiesFileAbsent_noWarning() throws Exception {
        // URLClassLoader with empty classpath and no parent — resource not found
        try (var cl = new URLClassLoader(new URL[0], null)) {
            VersionMismatchDetector.check(apiWith(1, "/api"), cl);
        }
        boolean hasWarning = listAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN);
        assertThat(hasWarning).isFalse();
    }

    @Test
    void malformedProperties_noException() throws Exception {
        // Properties.load() is lenient — use a content that triggers parseInt failure
        try (var cl = classLoaderWith("api.major=notanumber\napi.basePath=/api\n")) {
            assertThatCode(() -> VersionMismatchDetector.check(apiWith(1, "/api"), cl))
                    .doesNotThrowAnyException();
        }
    }
}
