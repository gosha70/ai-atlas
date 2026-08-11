/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * {@code atlas inspect} — a dry run that reports the exposed {@code @AgenticExposed} services and
 * the DTOs, MCP tools, REST controllers and OpenAPI spec that <em>would</em> be generated, without
 * writing any file to disk.
 *
 * <p>Generation runs in-memory: the driver compiles using an in-memory file manager so no output,
 * staging or lock directories are ever created on the filesystem.
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

    private static final String NOTHING_TO_EMIT =
            "the sources compiled but produced nothing to inspect — are any types annotated with "
                    + "@AgenticExposed or @AgenticEntity?";

    @Override
    public Integer call() {
        GenerationResult result = generateInspect();
        List<String> services = result.discoveredServices();
        JsonOutput report = JsonOutput.forCommand(NAME)
                .inspectFiles(result.files())
                .openApi(result.openApi(), null)
                .inspectServices(services);
        // A discovered service with no emitted file (all methods filtered out, or none public) is
        // still something to inspect — only "no files AND no services" means there was nothing.
        String failure = result.success() && result.files().isEmpty() && services.isEmpty()
                ? NOTHING_TO_EMIT : null;
        return report(report, result, failure, () -> printSummary(result, services));
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
}
