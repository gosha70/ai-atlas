/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import java.nio.file.Path;
import java.util.List;

/**
 * Structured outcome of an {@link AtlasGenerator} run.
 *
 * @param success     {@code true} when the underlying compilation completed without errors
 * @param outputDir   the absolute, normalized directory the run wrote into, or {@code null} when
 *                    the run was an in-memory inspection that wrote nothing to the filesystem
 * @param files       every artifact emitted by <em>this</em> run, ordered by kind then relative path
 * @param openApi     the versioned OpenAPI document, or {@code null} when none was emitted
 * @param diagnostics every compiler / processor message, in the order javac reported them
 */
public record GenerationResult(boolean success, Path outputDir, List<GeneratedFile> files,
                               String openApi, List<Diagnostic> diagnostics) {

    /** Canonical constructor taking defensive, immutable copies of the collections. */
    public GenerationResult {
        files = List.copyOf(files);
        diagnostics = List.copyOf(diagnostics);
    }

    /** Returns the emitted files of the given kind. */
    public List<GeneratedFile> filesOfKind(GeneratedFile.Kind kind) {
        return files.stream().filter(f -> f.kind() == kind).toList();
    }

    /** Returns only the error diagnostics. */
    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    /** Returns {@code true} when at least one error diagnostic was reported. */
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }
}
