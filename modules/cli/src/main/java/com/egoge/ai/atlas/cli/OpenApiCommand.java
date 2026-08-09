/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code atlas openapi} — produces just the OpenAPI document for a source set.
 *
 * <p>The generators only emit a spec as part of a full processing round, so this command runs the
 * same generation as {@code atlas generate} but into a throwaway directory, then keeps the spec
 * alone. Nothing is left in the working tree unless {@code --out} asks for it.
 */
@Command(name = OpenApiCommand.NAME,
        description = "Print or write the OpenAPI specification for the given sources.",
        mixinStandardHelpOptions = true,
        versionProvider = AtlasCli.VersionProvider.class)
final class OpenApiCommand extends AtlasCommand {

    /** The subcommand name, and the {@code command} value in the JSON report. */
    static final String NAME = "openapi";

    private static final String WORK_DIR_PREFIX = "atlas-openapi";
    private static final String NO_SPEC =
            "no OpenAPI document was generated — are any types annotated with @AgenticExposed?";

    @Option(names = {"-o", "--out"}, paramLabel = "<file>",
            description = "File to write the OpenAPI document to. When omitted, the document is "
                    + "printed on stdout (or embedded in the JSON report under --json).")
    private Path out;

    @Override
    public Integer call() throws IOException {
        Path workDir = Files.createTempDirectory(WORK_DIR_PREFIX);
        try {
            GenerationResult result = generate(workDir);
            String document = result.openApi();
            Path written = document == null ? null : write(document);
            String failure = result.success() && document == null ? NO_SPEC : null;
            JsonOutput report = JsonOutput.forCommand(NAME).openApi(document, written);
            // When --out wrote the spec to disk, it is an artifact this run emitted — surface it in
            // the stable manifest so a hook consumer sees the same files / counts contract across
            // both commands.
            if (written != null && document != null) {
                report.files(List.of(
                        new GeneratedFile(GeneratedFile.Kind.OPENAPI,
                                written.getFileName().toString(), written, document)));
            }
            return report(report, result, failure, () -> print(document, written));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** Writes the document to {@code --out}, creating parent directories; no-op without it. */
    private Path write(String document) throws IOException {
        if (out == null) {
            return null;
        }
        Path target = out.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, document, StandardCharsets.UTF_8);
        return target;
    }

    /**
     * Without {@code --out} the document itself is the output, so it goes to stdout unadorned and
     * stays pipeable; with {@code --out} stdout carries the path instead.
     */
    private void print(String document, Path written) {
        if (document == null) {
            return;
        }
        out().println(written == null ? document : written.toString());
    }

    /**
     * Removes the throwaway generation directory. Runs in a {@code finally} block, so a path that
     * cannot be removed is deferred to JVM exit rather than masking the real failure — and the rest
     * of the tree is still deleted now, since one undeletable file must not strand its siblings.
     *
     * <p>Entries are deleted inline in post-order — no buffering of the full tree — so a traversal
     * failure mid-tree still removes every entry that was already visited. Per-entry deletion
     * failures are collected and deferred to {@code deleteOnExit}; only those failures (not the
     * whole tree) are buffered.
     */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> deferred = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!tryDelete(file)) {
                        deferred.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (exc != null) {
                        deferred.add(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    if (!tryDelete(dir)) {
                        deferred.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                private boolean tryDelete(Path path) {
                    try {
                        return Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        return false;
                    }
                }
            });
        } catch (IOException | UncheckedIOException e) {
            deferred.add(root);
        }
        // deleteOnExit deletes in reverse registration order, and a directory only goes away once
        // it is empty — so register shallowest-first to have the JVM delete children first.
        deferred.sort(Comparator.naturalOrder());
        deferred.forEach(path -> path.toFile().deleteOnExit());
    }
}
