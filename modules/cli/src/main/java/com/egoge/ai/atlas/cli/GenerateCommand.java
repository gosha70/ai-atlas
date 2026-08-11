/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code atlas generate} — writes the full generated tree (DTOs, MCP tools, REST controllers,
 * OpenAPI spec and the other processor resources) into {@code --out}.
 */
@Command(name = GenerateCommand.NAME,
        description = "Generate DTOs, MCP tools, REST controllers and the OpenAPI spec into a "
                + "directory.",
        mixinStandardHelpOptions = true,
        versionProvider = AtlasCli.VersionProvider.class)
final class GenerateCommand extends AtlasCommand {

    /** The subcommand name, and the {@code command} value in the JSON report. */
    static final String NAME = "generate";

    private static final String NOTHING_TO_EMIT =
            "the sources compiled but produced nothing to generate — are any types annotated with "
                    + "@AgenticExposed or @AgenticEntity? Any previously generated output under "
                    + "--out was left as it was.";

    @Option(names = {"-o", "--out"}, required = true, paramLabel = "<dir>",
            description = "Directory to write generated sources and resources into. Its "
                    + "'sources' and 'resources' subdirectories are replaced by this run.")
    private Path out;

    @Override
    public Integer call() {
        GenerationResult result = generate(out);
        JsonOutput report = JsonOutput.forCommand(NAME)
                .outputDir(result.outputDir())
                .files(result.files())
                .openApi(result.openApi(), openApiPath(result));
        // A clean compile that emitted no files is the documented "nothing to emit" failure, not a
        // success with an empty manifest — the driver published nothing, so neither does this run.
        String failure = result.success() && result.files().isEmpty() ? NOTHING_TO_EMIT : null;
        return report(report, result, failure, () -> printSummary(result));
    }

    /** Where the selected OpenAPI document landed on disk, or {@code null} if none was emitted. */
    private Path openApiPath(GenerationResult result) {
        return result.filesOfKind(GeneratedFile.Kind.OPENAPI).stream()
                .filter(file -> file.content().equals(result.openApi()))
                .map(GeneratedFile::path)
                .findFirst()
                .orElse(null);
    }

    private void printSummary(GenerationResult result) {
        List<GeneratedFile> files = result.files();
        out().println("Generated " + files.size() + " file(s) into " + result.outputDir());
        for (GeneratedFile file : files) {
            out().println("  " + file.kind() + "  " + file.relativePath());
        }
    }
}
