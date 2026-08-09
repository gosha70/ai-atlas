/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code atlas inspect} — a dry run that reports the exposed {@code @AgenticExposed} services and
 * the DTOs, MCP tools, REST controllers and OpenAPI spec that <em>would</em> be generated, without
 * writing any file to a caller-visible directory.
 *
 * <p>Generation runs in dry-run mode: the driver compiles into a throwaway staging directory and
 * collects the generated artifacts, but never publishes them — no files reach the output roots and
 * no lock file is created.
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

    /** The kinds of generated files that correspond to exposed services. */
    private static final List<GeneratedFile.Kind> SERVICE_KINDS =
            List.of(GeneratedFile.Kind.MCP_TOOL, GeneratedFile.Kind.REST_CONTROLLER);

    @Override
    public Integer call() {
        GenerationResult result = generateDryRun(Path.of(""));  // output dir unused in dry-run mode
        List<String> services = SERVICE_KINDS.stream()
                .flatMap(kind -> result.filesOfKind(kind).stream())
                .map(InspectCommand::qualifiedServiceName)
                .distinct()
                .sorted()
                .toList();
        JsonOutput report = JsonOutput.forCommand(NAME)
                .inspectFiles(result.files())
                .openApi(result.openApi(), null)
                .inspectServices(services);
        String failure = result.success() && result.files().isEmpty() ? NOTHING_TO_EMIT : null;
        return report(report, result, failure, () -> printSummary(result, services));
    }

    /**
     * Extracts the qualified service name from a generated wrapper file's relative path.
     *
     * <p>The generators place wrapper classes in {@code <service-package>.generated}, naming them
     * {@code <Service>McpTool} or {@code <Service>RestController}. The service's qualified name is
     * the package with {@code .generated} stripped, plus the service simple name.
     *
     * <p>For example, {@code test/generated/CustomerServiceMcpTool.java} yields
     * {@code test.CustomerService}. For a service in the default package the generated directory is
     * just {@code generated}, and the result is the simple name alone.
     */
    static String qualifiedServiceName(GeneratedFile file) {
        String relative = file.relativePath();
        int lastSlash = relative.lastIndexOf('/');
        String dir = lastSlash >= 0 ? relative.substring(0, lastSlash) : "";
        String fileName = lastSlash >= 0 ? relative.substring(lastSlash + 1) : relative;

        // Strip ".java"
        if (fileName.endsWith(".java")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        // Strip the wrapper suffix to recover the service simple name
        String simpleName = stripWrapperSuffix(fileName);

        // The service package is the directory path with "/generated" removed.
        // "generated" alone means the service is in the default package.
        String pkg;
        if (dir.equals("generated")) {
            pkg = "";
        } else if (dir.endsWith("/generated")) {
            pkg = dir.substring(0, dir.length() - "/generated".length()).replace('/', '.');
        } else {
            pkg = dir.replace('/', '.');
        }

        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }

    /** Strips {@code McpTool} or {@code RestController} from the end of a generated class name. */
    private static String stripWrapperSuffix(String fileName) {
        if (fileName.endsWith("McpTool")) {
            return fileName.substring(0, fileName.length() - 7);
        }
        if (fileName.endsWith("RestController")) {
            return fileName.substring(0, fileName.length() - 14);
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
}
