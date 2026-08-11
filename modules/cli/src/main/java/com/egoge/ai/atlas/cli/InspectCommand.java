/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.cli;

import com.egoge.ai.atlas.processor.driver.GeneratedFile;
import com.egoge.ai.atlas.processor.driver.GenerationResult;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** The kinds of generated files that correspond to exposed services. */
    private static final List<GeneratedFile.Kind> SERVICE_KINDS =
            List.of(GeneratedFile.Kind.MCP_TOOL, GeneratedFile.Kind.REST_CONTROLLER);

    /** Matches a Java package declaration. */
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");

    /** Matches a Java class / interface / record declaration at the top level. */
    private static final Pattern CLASS_PATTERN =
            Pattern.compile("\\b(?:public\\s+)?(?:class|interface|record)\\s+(\\w+)\\s");

    @Override
    public Integer call() {
        GenerationResult result = generateInspect();
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
     * Extracts the qualified service name from a generated wrapper's source content.
     *
     * <p>Parses the package and class declarations from the generated file's content to recover
     * the original service's qualified name. The generated wrapper is placed in
     * {@code <service-package>.generated} and named {@code <Service>McpTool} or
     * {@code <Service>RestController}, so the service name is the package with {@code .generated}
     * stripped plus the wrapper class name with the suffix removed.
     *
     * <p>For inner classes the generated class name uses {@code $} as the separator (e.g.
     * {@code Outer$Inner}), which is converted to {@code .} in the result.
     *
     * <p>Falls back to filename-based extraction when the content cannot be parsed, so the
     * command still works with any future generated source layout.
     */
    static String qualifiedServiceName(GeneratedFile file) {
        // Prefer content-based extraction — it handles inner classes and is independent of the
        // generated file's path layout.
        String content = file.content();
        if (content != null && !content.isEmpty()) {
            String fromContent = qualifiedServiceNameFromContent(content);
            if (fromContent != null) {
                return fromContent;
            }
        }
        // Fallback: reconstruct from the relative path (previous behaviour).
        return qualifiedServiceNameFromPath(file.relativePath());
    }

    /** Extracts the qualified service name from generated Java source content. */
    private static String qualifiedServiceNameFromContent(String content) {
        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
        String pkg = pkgMatcher.find() ? pkgMatcher.group(1) : "";

        Matcher classMatcher = CLASS_PATTERN.matcher(content);
        if (!classMatcher.find()) {
            return null;
        }
        String className = classMatcher.group(1);
        String simpleName = stripWrapperSuffix(className)
                .replace('$', '.'); // inner-class separator → qualified-name separator

        String servicePkg;
        if (pkg.endsWith(".generated")) {
            servicePkg = pkg.substring(0, pkg.length() - ".generated".length());
        } else if (pkg.equals("generated")) {
            servicePkg = "";
        } else {
            servicePkg = pkg;
        }
        return servicePkg.isEmpty() ? simpleName : servicePkg + "." + simpleName;
    }

    /**
     * Extracts the qualified service name from a generated wrapper file's relative path.
     * Used as the fallback when the source content cannot be parsed.
     */
    private static String qualifiedServiceNameFromPath(String relative) {
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
