/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.concurrent.Callable;

/**
 * Root of the standalone {@code atlas} command — the process-spawnable surface an AI harness or a
 * build hook uses to drive ai-atlas generation without a running Spring Boot application.
 *
 * <p>Exit codes are part of the contract, so a shell hook can branch on them:
 * <ul>
 *   <li>{@code 0} — the run succeeded.</li>
 *   <li>{@code 1} — the run failed (compile errors, unreadable inputs, nothing to emit). Under
 *       {@code --json} stdout still carries a complete {@link JsonOutput} document with
 *       {@code "status": "error"}.</li>
 *   <li>{@code 2} — the command line itself was wrong (unknown option, missing required option).
 *       Usage help goes to stderr; stdout stays empty, including under {@code --json}, because the
 *       arguments were never parsed far enough to know the mode was requested.</li>
 * </ul>
 */
@Command(name = AtlasCli.NAME,
        header = "Generate PII-safe DTOs, MCP tools, REST controllers and OpenAPI specs from "
                + "@AgenticExposed Java sources.",
        description = "Runs the ai-atlas annotation processor over a source set through the "
                + "platform Java compiler. Requires a JDK (not a JRE). The sources' own compile "
                + "classpath must be supplied with --classpath.",
        mixinStandardHelpOptions = true,
        versionProvider = AtlasCli.VersionProvider.class,
        synopsisSubcommandLabel = "<command>",
        subcommands = {GenerateCommand.class, OpenApiCommand.class})
public final class AtlasCli implements Callable<Integer> {

    /** The executable's command name, as it appears in usage help. */
    public static final String NAME = "atlas";

    private static final String DEV_VERSION = "(development build)";

    @Spec
    private CommandSpec spec;

    private AtlasCli() {
    }

    /**
     * Entry point of {@code atlas.jar}.
     *
     * @param args the command line
     */
    public static void main(String[] args) {
        System.exit(execute(new PrintWriter(System.out, true), new PrintWriter(System.err, true),
                args));
    }

    /**
     * Runs the CLI against caller-supplied streams and returns the exit code instead of exiting.
     *
     * @param out  receives the command's result — the JSON document under {@code --json}
     * @param err  receives diagnostics and usage help
     * @param args the command line
     * @return the process exit code
     */
    static int execute(PrintWriter out, PrintWriter err, String... args) {
        return new CommandLine(new AtlasCli())
                .setOut(out)
                .setErr(err)
                .setExecutionExceptionHandler(new JsonAwareExceptionHandler())
                .execute(args);
    }

    /** Bare {@code atlas} with no subcommand: show what is available and fail as a usage error. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return CommandLine.ExitCode.USAGE;
    }

    /**
     * Turns an exception thrown by a subcommand into the same reporting contract a failed
     * generation gets: a complete JSON document on stdout under {@code --json}, the message on
     * stderr either way, exit code 1. Without this, an unreadable source path would leave a
     * {@code --json} caller parsing a stack trace.
     */
    private static final class JsonAwareExceptionHandler implements IExecutionExceptionHandler {

        @Override
        public int handleExecutionException(Exception ex, CommandLine command, ParseResult parse) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (command.getCommandSpec().userObject() instanceof AtlasCommand atlas
                    && atlas.isJson()) {
                command.getOut().println(JsonOutput.forCommand(command.getCommandSpec().name())
                        .status(false)
                        .error(message)
                        .render());
            }
            command.getErr().println(NAME + ": " + message);
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    /** Reports the version stamped into the jar manifest at build time. */
    static final class VersionProvider implements IVersionProvider {

        @Override
        public String[] getVersion() {
            String version = AtlasCli.class.getPackage().getImplementationVersion();
            return new String[] {NAME + " " + (version == null ? DEV_VERSION : version)};
        }
    }
}
