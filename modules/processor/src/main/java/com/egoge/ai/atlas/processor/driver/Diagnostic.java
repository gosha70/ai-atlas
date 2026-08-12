/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import java.util.Objects;

/**
 * A compiler or annotation-processor message captured by {@link AtlasGenerator}.
 *
 * <p>What {@code source} names depends on what the message is about. A message about a
 * caller-supplied source names that file, by the absolute path it was read from. A message about a
 * generated artifact names the published copy — an absolute path under the output directory that
 * exists once the run returns — but only when the run published; a run that published nothing
 * (a failed compile, or nothing to emit) names the artifact by its relative path under the output
 * roots instead, e.g. {@code sources/com/example/FooDto.java}, since no such file was written.
 * {@link AtlasGenerator#generateInspect} writes nothing at all, so it names generated artifacts by
 * their in-memory path. Many processor messages carry no source and leave it empty. The driver's
 * private staging directory never appears in either {@code source} or {@code message}.
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
