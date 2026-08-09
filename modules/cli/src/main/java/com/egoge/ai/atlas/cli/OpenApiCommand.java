/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
     */
    private static void deleteRecursively(Path root) {
        List<Path> deferred = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    deferred.add(path);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            deferred.add(root);
        }
        // deleteOnExit deletes in reverse registration order, and a directory only goes away once
        // it is empty — so register shallowest-first to have the JVM delete children first.
        deferred.sort(Comparator.naturalOrder());
        deferred.forEach(path -> path.toFile().deleteOnExit());
    }
}
