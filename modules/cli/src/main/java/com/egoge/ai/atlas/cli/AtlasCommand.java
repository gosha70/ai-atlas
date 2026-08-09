/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.AtlasGenerator;
import com.egoge.ai.atlas.processor.driver.Diagnostic;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The options and reporting every generating subcommand shares.
 *
 * <p>Subcommands differ only in what they do with the {@link GenerationResult}; the input surface
 * ({@code --sources}, {@code --classpath}, {@code -A}, {@code --json}) and the stdout/stderr split
 * are defined once here so the two commands cannot drift apart.
 */
abstract class AtlasCommand implements Callable<Integer> {

    @Option(names = {"-s", "--sources"}, required = true, split = ",", paramLabel = "<path>",
            description = "Source root directory, or a single .java file, to generate from. "
                    + "Repeatable, or comma-separated.")
    private List<Path> sources = new ArrayList<>();

    @Option(names = {"-c", "--classpath"}, paramLabel = "<cp>",
            description = "Compile classpath for the sources. Repeatable, or one string of "
                    + "path-separator-separated entries. Must carry the Spring AI and Spring Web "
                    + "types the generated wrappers reference, or generation will not compile.")
    private List<String> classpath = new ArrayList<>();

    @Option(names = {"-A", "--option"}, paramLabel = "<key=value>",
            description = "Annotation-processor option, e.g. -Aai.atlas.api.major=2. Repeatable. "
                    + "These must match the target build's options for the output to match it.")
    private Map<String, String> processorOptions = new LinkedHashMap<>();

    @Option(names = "--json",
            description = "Emit a stable JSON report on stdout instead of human-readable text. "
                    + "Diagnostics still go to stderr. See JsonOutput for the schema.")
    private boolean json;

    @Spec
    private CommandSpec spec;

    /** Whether the caller asked for machine-readable output. */
    final boolean isJson() {
        return json;
    }

    final PrintWriter out() {
        return spec.commandLine().getOut();
    }

    final PrintWriter err() {
        return spec.commandLine().getErr();
    }

    /** Runs generation into {@code outputDir} with the caller's sources, classpath and options. */
    final GenerationResult generate(Path outputDir) {
        return AtlasGenerator.generate(List.copyOf(sources), classpathEntries(), outputDir,
                Map.copyOf(processorOptions));
    }

    /**
     * Splits every {@code --classpath} value on the platform path separator, so a single shell
     * {@code "$(cat cp.txt)"} and repeated {@code --classpath a.jar --classpath b.jar} both work.
     * Blank entries are dropped rather than handed to javac as an empty path.
     *
     * <p>Every surviving entry is checked here, at the boundary: javac ignores a classpath entry it
     * cannot read, so a typo in a jar path would otherwise surface much later as a wall of
     * missing-symbol errors that say nothing about the real cause.
     *
     * @throws IllegalArgumentException if an entry does not exist or cannot be read
     */
    private List<Path> classpathEntries() {
        List<Path> entries = new ArrayList<>();
        for (String value : classpath) {
            for (String entry : value.split(File.pathSeparator, -1)) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(validClasspathEntry(Path.of(trimmed)));
                }
            }
        }
        return entries;
    }

    /** Rejects a classpath entry javac would silently skip, naming the offending path. */
    private static Path validClasspathEntry(Path entry) {
        if (!Files.exists(entry)) {
            throw new IllegalArgumentException("classpath entry does not exist: " + entry);
        }
        if (!Files.isReadable(entry)) {
            throw new IllegalArgumentException("classpath entry is not readable: " + entry);
        }
        return entry;
    }

    /**
     * Writes the report and maps the run to an exit code.
     *
     * <p>stdout carries the result — the JSON document, or whatever {@code humanReport} prints.
     * stderr carries the warnings and errors, in both modes, so a hook capturing stdout gets
     * parseable output and a human watching the terminal still sees what went wrong.
     *
     * @param report      the JSON document, already populated by the caller
     * @param result      the run being reported
     * @param humanReport prints the non-JSON summary; not called under {@code --json}
     * @return {@link CommandLine.ExitCode#OK} on success, {@link CommandLine.ExitCode#SOFTWARE}
     *         when the run failed
     */
    final int report(JsonOutput report, GenerationResult result, Runnable humanReport) {
        return report(report, result, null, humanReport);
    }

    /**
     * As {@link #report(JsonOutput, GenerationResult, Runnable)}, but also fails the command when
     * the compilation itself succeeded and the command still could not deliver what it promises.
     *
     * @param failure why the command failed despite a clean compile, or {@code null} if it did not
     */
    final int report(JsonOutput report, GenerationResult result, String failure,
                     Runnable humanReport) {
        boolean ok = result.success() && failure == null;
        if (failure != null) {
            report.error(failure);
        }
        if (json) {
            out().println(report.status(ok).diagnostics(result.diagnostics()).render());
        } else if (ok) {
            humanReport.run();
        }
        printDiagnostics(result.diagnostics());
        if (!result.success()) {
            err().println("atlas: generation failed; no output was written.");
        } else if (failure != null) {
            err().println("atlas: " + failure);
        }
        return ok ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }

    /**
     * Echoes warnings and errors to stderr. Notes are left out — the processor emits one per
     * generated artifact, which would bury the messages that need attention; they stay available
     * in the JSON report's {@code diagnostics}.
     */
    private void printDiagnostics(List<Diagnostic> diagnostics) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() == Diagnostic.Severity.NOTE) {
                continue;
            }
            String source = diagnostic.source().isEmpty() ? "" : diagnostic.source() + ": ";
            err().println(diagnostic.severity() + ": " + source + diagnostic.message());
        }
    }
}
