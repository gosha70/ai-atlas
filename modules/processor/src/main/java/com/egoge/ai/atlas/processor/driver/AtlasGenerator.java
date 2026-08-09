/*
 * Copyright (c) 2026 egoge.com. All rights reserved.
 */
package com.egoge.ai.atlas.processor.driver;

import com.egoge.ai.atlas.processor.AgenticProcessor;
import com.egoge.ai.atlas.processor.generator.ApiVersionPropertiesGenerator;
import com.egoge.ai.atlas.processor.generator.DeprecationManifestGenerator;
import com.egoge.ai.atlas.processor.generator.OpenApiGenerator;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Programmatic driver that runs {@link AgenticProcessor} over a caller-supplied source set,
 * outside of a Gradle {@code compileJava} task and without a Spring context.
 *
 * <p>Generation happens in-process through the platform {@link JavaCompiler}, with the processor
 * registered explicitly — the same mechanism the annotation-processing build uses, so the emitted
 * artifacts are byte-identical to it.
 *
 * <p>This class depends on the JDK compiler only; it declares no Spring dependency. The generated
 * wrappers do reference {@code @Tool}, {@code @Service} and {@code @RestController}, so the
 * {@code classpath} handed in by the caller must carry the Spring AI and Spring Web types for the
 * compilation to succeed — exactly as the target application's own compile classpath does.
 *
 * <p>Output is laid out under the given output directory as {@value #SOURCES_DIR} (generated Java
 * sources, in package directories) and {@value #RESOURCES_DIR} (generated resources, under
 * {@code META-INF}). Those two roots are <em>owned</em> by the driver: each run generates into a
 * private staging directory and then replaces them wholesale, so the returned manifest lists
 * exactly what this run emitted — never an artifact left behind by an earlier or partially failed
 * run. Anything else the caller keeps under the output directory is left untouched, and files under
 * it are excluded from source discovery so an output directory nested inside a source root is safe
 * to reuse.
 */
public final class AtlasGenerator {

    /** Name of the generated-sources root inside the output directory. */
    public static final String SOURCES_DIR = "sources";
    /** Name of the generated-resources root inside the output directory. */
    public static final String RESOURCES_DIR = "resources";

    private static final String CLASSES_DIR = "classes";
    private static final String STAGING_PREFIX = ".atlas-staging";
    private static final String JAVA_SUFFIX = ".java";
    private static final String CLASS_SUFFIX = ".class";
    private static final String OPTION_PREFIX = "-A";
    private static final String ENCODING_OPTION = "-encoding";
    /**
     * Classification anchors. Generated type-level annotations and the type declaration itself are
     * emitted by JavaPoet at column 0, whereas caller-supplied text only ever reaches the output
     * inside an (indented, newline-escaped) string literal — so anchoring to whole lines cannot be
     * spoofed by an annotation description that happens to contain one of these markers.
     */
    private static final String REST_CONTROLLER_ANNOTATION = "@RestController";
    private static final String MCP_TOOL_ANNOTATION = "@Service";
    private static final String DTO_DECLARATION_PREFIX = "public record ";
    private static final String LEGACY_OPENAPI_PATH =
            OpenApiGenerator.RESOURCE_DIR + OpenApiGenerator.LEGACY_RESOURCE_NAME;

    private AtlasGenerator() {
    }

    /**
     * Runs generation with no processor options, i.e. framework defaults.
     *
     * @see #generate(List, List, Path, Map)
     */
    public static GenerationResult generate(List<Path> sources, List<Path> classpath, Path outputDir) {
        return generate(sources, classpath, outputDir, Map.of());
    }

    /**
     * Compiles {@code sources} with {@link AgenticProcessor} registered and collects everything it
     * emitted.
     *
     * @param sources          source roots (directories are scanned recursively) and/or individual
     *                         {@code .java} files
     * @param classpath        the target application's compile classpath; must supply the Spring AI
     *                         and Spring Web types referenced by the generated wrappers
     * @param outputDir        directory to write generated sources and resources into; its
     *                         {@value #SOURCES_DIR} and {@value #RESOURCES_DIR} roots are replaced
     *                         by this run, and it is normalized to an absolute path, so every
     *                         returned {@link GeneratedFile#path()} is absolute
     * @param processorOptions {@code -A} processor options, e.g.
     *                         {@link AgenticProcessor#OPT_API_MAJOR}
     * @return the generated files, the OpenAPI document and every diagnostic reported
     * @throws IllegalArgumentException if a source path does not exist or no sources were found
     * @throws IllegalStateException    if the running JVM is not a JDK (no system compiler)
     * @throws UncheckedIOException     if the output directories cannot be written
     */
    public static GenerationResult generate(List<Path> sources, List<Path> classpath, Path outputDir,
                                            Map<String, String> processorOptions) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(processorOptions, "processorOptions");

        Path output = outputDir.toAbsolutePath().normalize();
        List<Path> sourceFiles = collectSourceFiles(sources, output);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No .java sources found under: " + sources
                    + " (paths inside the output directory " + output + " are excluded)");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available — ai-atlas generation requires a JDK, not a JRE");
        }

        Path sourceOut = output.resolve(SOURCES_DIR);
        Path resourceOut = output.resolve(RESOURCES_DIR);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        Path staging = null;
        boolean success;
        List<GeneratedFile> files = new ArrayList<>();
        try {
            Files.createDirectories(output);
            // Staged inside the output directory so publishing is a same-filesystem move.
            staging = Files.createTempDirectory(output, STAGING_PREFIX);
            Path stagedSources = Files.createDirectories(staging.resolve(SOURCES_DIR));
            Path stagedResources = Files.createDirectories(staging.resolve(RESOURCES_DIR));
            Path stagedClasses = Files.createDirectories(staging.resolve(CLASSES_DIR));

            success = compile(compiler, collector, sourceFiles, classpath,
                    stagedSources, stagedClasses, processorOptions);
            harvestResources(stagedClasses, stagedResources);

            publish(stagedSources, sourceOut);
            publish(stagedResources, resourceOut);

            files.addAll(collectGenerated(sourceOut, true));
            files.addAll(collectGenerated(resourceOut, false));
        } catch (IOException e) {
            throw new UncheckedIOException("ai-atlas generation failed to write to " + output, e);
        } finally {
            deleteRecursively(staging);
        }

        files.sort(Comparator.comparing((GeneratedFile f) -> f.kind().ordinal())
                .thenComparing(GeneratedFile::relativePath));

        return new GenerationResult(success, output, files, findOpenApi(files, processorOptions),
                collector.getDiagnostics().stream().map(AtlasGenerator::toDiagnostic).toList());
    }

    /** Replaces an owned output root with this run's staged tree. */
    private static void publish(Path staged, Path target) throws IOException {
        deleteRecursively(target);
        Files.createDirectories(target.getParent());
        Files.move(staged, target);
    }

    private static boolean compile(JavaCompiler compiler, DiagnosticCollector<JavaFileObject> collector,
                                   List<Path> sourceFiles, List<Path> classpath,
                                   Path sourceOut, Path classOut,
                                   Map<String, String> processorOptions) throws IOException {
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sourceOut));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOut));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);

            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector,
                    compilerOptions(processorOptions), null,
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles));
            task.setProcessors(List.of(new AgenticProcessor()));
            return Boolean.TRUE.equals(task.call());
        }
    }

    private static List<String> compilerOptions(Map<String, String> processorOptions) {
        List<String> options = new ArrayList<>();
        options.add(ENCODING_OPTION);
        options.add(StandardCharsets.UTF_8.name());
        processorOptions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getKey() == null || entry.getKey().isBlank()) {
                        throw new IllegalArgumentException("Processor option keys must not be blank");
                    }
                    options.add(OPTION_PREFIX + entry.getKey() + "=" + entry.getValue());
                });
        return options;
    }

    /**
     * Collects the compilation units, skipping anything inside {@code outputDir}. Without that
     * exclusion a second run over a source root that contains the output directory would feed the
     * previous run's generated sources back to javac, which then clashes with the processor
     * re-creating those same types.
     */
    private static List<Path> collectSourceFiles(List<Path> sources, Path outputDir) {
        Set<Path> files = new LinkedHashSet<>();
        for (Path source : sources) {
            Objects.requireNonNull(source, "source path");
            if (!Files.exists(source)) {
                throw new IllegalArgumentException("Source path does not exist: " + source);
            }
            Path root = source.toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(JAVA_SUFFIX))
                            .map(p -> p.toAbsolutePath().normalize())
                            .filter(p -> !p.startsWith(outputDir))
                            .forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to scan source directory: " + source, e);
                }
            } else if (!root.startsWith(outputDir)) {
                files.add(root);
            }
        }
        return files.stream().sorted().toList();
    }

    /** Copies processor-written resources out of the throwaway class output into {@code resourceOut}. */
    private static void harvestResources(Path classOut, Path resourceOut) throws IOException {
        try (Stream<Path> walk = Files.walk(classOut)) {
            List<Path> resources = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(CLASS_SUFFIX))
                    .toList();
            for (Path resource : resources) {
                Path target = resourceOut.resolve(classOut.relativize(resource).toString());
                Files.createDirectories(target.getParent());
                Files.copy(resource, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<GeneratedFile> collectGenerated(Path root, boolean javaSources) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !javaSources || p.getFileName().toString().endsWith(JAVA_SUFFIX))
                    .map(p -> toGeneratedFile(root, p, javaSources))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan generated output: " + root, e);
        }
    }

    private static GeneratedFile toGeneratedFile(Path root, Path file, boolean javaSource) {
        String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read generated file: " + file, e);
        }
        GeneratedFile.Kind kind = javaSource ? classifySource(content) : classifyResource(relative);
        return new GeneratedFile(kind, relative, file, content);
    }

    private static GeneratedFile.Kind classifySource(String content) {
        List<String> topLevel = content.lines()
                .filter(line -> !line.isEmpty() && !Character.isWhitespace(line.charAt(0)))
                .toList();
        if (topLevel.contains(REST_CONTROLLER_ANNOTATION)) {
            return GeneratedFile.Kind.REST_CONTROLLER;
        }
        if (topLevel.contains(MCP_TOOL_ANNOTATION)) {
            return GeneratedFile.Kind.MCP_TOOL;
        }
        if (topLevel.stream().anyMatch(line -> line.startsWith(DTO_DECLARATION_PREFIX))) {
            return GeneratedFile.Kind.DTO;
        }
        return GeneratedFile.Kind.OTHER;
    }

    private static GeneratedFile.Kind classifyResource(String relativePath) {
        if (relativePath.startsWith(OpenApiGenerator.RESOURCE_DIR)) {
            return GeneratedFile.Kind.OPENAPI;
        }
        if (relativePath.equals(ApiVersionPropertiesGenerator.RESOURCE_PATH)) {
            return GeneratedFile.Kind.API_VERSION_PROPERTIES;
        }
        if (relativePath.equals(DeprecationManifestGenerator.RESOURCE_PATH)) {
            return GeneratedFile.Kind.DEPRECATION_MANIFEST;
        }
        return GeneratedFile.Kind.OTHER;
    }

    /**
     * Selects the spec for the major this run asked for, falling back to this run's versioned spec
     * and then to the unversioned alias (identical content). Selecting by requested major — rather
     * than "the first non-legacy spec" — keeps the answer right when a caller points several runs
     * with different majors at one output tree.
     */
    private static String findOpenApi(List<GeneratedFile> files, Map<String, String> processorOptions) {
        List<GeneratedFile> specs = files.stream()
                .filter(f -> f.kind() == GeneratedFile.Kind.OPENAPI)
                .toList();
        return requestedSpecPath(processorOptions)
                .flatMap(path -> specs.stream().filter(f -> f.relativePath().equals(path)).findFirst())
                .or(() -> specs.stream()
                        .filter(f -> !f.relativePath().equals(LEGACY_OPENAPI_PATH)).findFirst())
                .or(() -> specs.stream().findFirst())
                .map(GeneratedFile::content)
                .orElse(null);
    }

    /** The versioned spec path implied by {@code ai.atlas.api.major}, when the caller set it. */
    private static Optional<String> requestedSpecPath(Map<String, String> processorOptions) {
        String raw = processorOptions.get(AgenticProcessor.OPT_API_MAJOR);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OpenApiGenerator.versionedResourcePath(Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            // The processor reports an unparsable major as a compile error; nothing to select by.
            return Optional.empty();
        }
    }

    private static Diagnostic toDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        Diagnostic.Severity severity = switch (diagnostic.getKind()) {
            case ERROR -> Diagnostic.Severity.ERROR;
            case WARNING, MANDATORY_WARNING -> Diagnostic.Severity.WARNING;
            case NOTE -> Diagnostic.Severity.NOTE;
            default -> Diagnostic.Severity.OTHER;
        };
        JavaFileObject source = diagnostic.getSource();
        return new Diagnostic(severity, diagnostic.getMessage(Locale.ROOT),
                source == null ? "" : source.getName());
    }

    /**
     * Best-effort cleanup of the throwaway class output. Runs in a {@code finally} block, so an
     * unremovable temp directory is left on disk rather than masking the generation failure that
     * would otherwise be reported.
     */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            root.toFile().deleteOnExit();
        }
    }
}
