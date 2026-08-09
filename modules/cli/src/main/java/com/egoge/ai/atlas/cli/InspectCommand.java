/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code atlas inspect} — a dry run that reports the exposed {@code @AgenticExposed} services and
 * the DTOs, MCP tools, REST controllers and OpenAPI spec that <em>would</em> be generated, without
 * writing any file to a caller-visible directory.
 */
@Command(name = InspectCommand.NAME,
        description = "List the @AgenticExposed services found in the sources and the DTOs, MCP "
                + "tools, REST controllers and OpenAPI spec that would be generated — without "
                + "writing files.",
        mixinStandardHelpOptions = true,
        versionProvider = AtlasCli.VersionProvider.class)
final class InspectCommand extends AtlasCommand {

    /** The subcommand name, and the {@code command} value in the JSON report. */
    static final String NAME = "inspect";

    private static final String WORK_DIR_PREFIX = "atlas-inspect";
    private static final String NOTHING_TO_EMIT =
            "the sources compiled but produced nothing to inspect — are any types annotated with "
                    + "@AgenticExposed or @AgenticEntity?";

    @Override
    public Integer call() throws IOException {
        Path workDir = Files.createTempDirectory(WORK_DIR_PREFIX);
        try {
            GenerationResult result = generate(workDir);
            List<String> services = result.filesOfKind(GeneratedFile.Kind.MCP_TOOL).stream()
                    .map(InspectCommand::serviceName)
                    .distinct()
                    .sorted()
                    .toList();
            JsonOutput report = JsonOutput.forCommand(NAME)
                    .inspectFiles(result.files())
                    .openApi(result.openApi(), null)
                    .inspectServices(services);
            String failure = result.success() && result.files().isEmpty() ? NOTHING_TO_EMIT : null;
            return report(report, result, failure, () -> printSummary(result, services));
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Extracts the service simple name from a generated MCP tool file name.
     *
     * <p>The generators name the tool class {@code <Service>McpTool}, so stripping
     * {@code McpTool.java} from the relative path's last segment yields the service name.
     */
    static String serviceName(GeneratedFile toolFile) {
        String fileName = toolFile.relativePath();
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash >= 0) {
            fileName = fileName.substring(lastSlash + 1);
        }
        if (fileName.endsWith(".java")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        if (fileName.endsWith("McpTool")) {
            fileName = fileName.substring(0, fileName.length() - 7);
        }
        return fileName;
    }

    private void printSummary(GenerationResult result, List<String> services) {
        if (!services.isEmpty()) {
            out().println("Exposed services:");
            for (String service : services) {
                out().println("  " + service);
            }
        }
        List<GeneratedFile> files = result.files();
        out().println("Would generate " + files.size() + " file(s):");
        for (GeneratedFile file : files) {
            out().println("  " + file.kind() + "  " + file.relativePath());
        }
    }

    /**
     * Removes a directory tree, deferring any entry that cannot be deleted to JVM exit.
     *
     * <p>Runs in a {@code finally} block — entries are deleted inline in post-order, and per-entry
     * failures are collected rather than masking the real result. The remainder is registered with
     * {@link File#deleteOnExit()} shallowest-first so the JVM deletes children before parents.
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
        deferred.sort(Comparator.naturalOrder());
        deferred.forEach(path -> path.toFile().deleteOnExit());
    }
}
