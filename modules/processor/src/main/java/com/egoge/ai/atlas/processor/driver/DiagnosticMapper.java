/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import javax.tools.JavaFileObject;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Turns the compiler's diagnostics into {@link Diagnostic} values a caller can act on.
 *
 * <p>A run compiles the generated sources from its private staging directory, so javac names that
 * staged copy — a path with a random suffix that {@link AtlasGenerator} deletes before returning.
 * Reporting it would hand callers a path that neither exists nor repeats between runs, so this
 * mapper rewrites those references to how the artifact can actually be named afterwards:
 *
 * <ul>
 *   <li>{@link #republishing} — the run published, so a staged path becomes the absolute published
 *       path under the output directory, which exists once the run returns.</li>
 *   <li>{@link #unpublished} — the run published nothing (it failed, or emitted nothing), so no
 *       file survives to point at. A staged path becomes the artifact's relative path under the
 *       output roots, e.g. {@code sources/com/example/FooDto.java}: it identifies which generated
 *       artifact the message is about without claiming a file the caller can open.</li>
 *   <li>{@link #passThrough} — nothing was staged, so every path is already the one to report.</li>
 * </ul>
 *
 * <p>Paths outside staging — the caller's own sources — are passed through in every mode.
 */
final class DiagnosticMapper {

    private final String stagingPrefix;
    private final String replacement;

    private DiagnosticMapper(String stagingPrefix, String replacement) {
        this.stagingPrefix = stagingPrefix;
        this.replacement = replacement;
    }

    /** A mapper that passes every path through, for runs that stage nothing (in-memory inspect). */
    static DiagnosticMapper passThrough() {
        return new DiagnosticMapper(null, null);
    }

    /**
     * A mapper naming staged artifacts by the path they were published under.
     *
     * @param staging this run's staging directory, or {@code null} if it was never created
     * @param output  the output directory the staged tree was published into
     */
    static DiagnosticMapper republishing(Path staging, Path output) {
        return of(staging, output + File.separator);
    }

    /**
     * A mapper naming staged artifacts by their relative path under the output roots, for a run
     * that published nothing and so left no file to point at.
     *
     * @param staging this run's staging directory, or {@code null} if it was never created
     */
    static DiagnosticMapper unpublished(Path staging) {
        return of(staging, "");
    }

    private static DiagnosticMapper of(Path staging, String replacement) {
        return staging == null
                ? passThrough()
                : new DiagnosticMapper(staging + File.separator, replacement);
    }

    Diagnostic map(javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        Diagnostic.Severity severity = switch (diagnostic.getKind()) {
            case ERROR -> Diagnostic.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> Diagnostic.Severity.WARNING;
            case NOTE -> Diagnostic.Severity.NOTE;
            default -> Diagnostic.Severity.OTHER;
        };
        JavaFileObject source = diagnostic.getSource();
        // The message embeds the same path, e.g. "<file> uses unchecked or unsafe operations".
        return new Diagnostic(severity, rewrite(diagnostic.getMessage(Locale.ROOT)),
                rewrite(source == null ? "" : source.getName()));
    }

    /** Replaces the staging directory prefix with this mode's replacement wherever it occurs. */
    private String rewrite(String text) {
        return stagingPrefix == null ? text : text.replace(stagingPrefix, replacement);
    }
}
