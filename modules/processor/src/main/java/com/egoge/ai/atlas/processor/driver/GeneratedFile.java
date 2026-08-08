/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One artifact emitted by a generation run.
 *
 * @param kind         what the file is
 * @param relativePath slash-separated path relative to the root the file was written under —
 *                     the generated-sources root for {@link Kind#isSource() source} kinds,
 *                     the generated-resources root otherwise
 * @param path         absolute location on disk
 * @param content      the file's textual content, as written
 */
public record GeneratedFile(Kind kind, String relativePath, Path path, String content) {

    /** Classification of a generated artifact. */
    public enum Kind {
        /** A PII-safe DTO record. */
        DTO,
        /** A Spring AI MCP tool wrapper. */
        MCP_TOOL,
        /** A Spring REST controller wrapper. */
        REST_CONTROLLER,
        /** An OpenAPI 3.x specification document. */
        OPENAPI,
        /** The {@code api-version.properties} compile-time version descriptor. */
        API_VERSION_PROPERTIES,
        /** The deprecation manifest describing sunset endpoints. */
        DEPRECATION_MANIFEST,
        /** Any other emitted artifact. */
        OTHER;

        /** Returns {@code true} when this kind is emitted as a Java source file. */
        public boolean isSource() {
            return this == DTO || this == MCP_TOOL || this == REST_CONTROLLER;
        }
    }

    /** Canonical constructor validating that no component is null. */
    public GeneratedFile {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
    }
}
