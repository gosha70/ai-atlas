/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import java.util.Objects;

/**
 * A compiler or annotation-processor message captured by {@link AtlasGenerator}.
 *
 * @param severity how serious the message is
 * @param message  the human-readable text, rendered with {@link java.util.Locale#ROOT}
 * @param source   the file the message points at, or an empty string when it has no source
 */
public record Diagnostic(Severity severity, String message, String source) {

    /** Severity of a captured {@link Diagnostic}. */
    public enum Severity {
        /** Generation failed for this message. */
        ERROR,
        /** Generation continued, but the message needs attention. */
        WARNING,
        /** Informational note (the processor reports generated artifacts this way). */
        NOTE,
        /** Anything the compiler does not classify as error, warning or note. */
        OTHER
    }

    /** Canonical constructor validating that no component is null. */
    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
    }

    /** Returns {@code true} when this diagnostic is an {@link Severity#ERROR}. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
