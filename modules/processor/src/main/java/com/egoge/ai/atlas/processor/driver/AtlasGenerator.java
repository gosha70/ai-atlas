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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Programmatic driver that runs {@link AgenticProcessor} over a caller-supplied source set,
 * outside of a Gradle {@code compileJava} task and without a Spring context.
 *
 * <p>Generation happens in-process through the platform {@link JavaCompiler}, with the processor
 * registered explicitly — the same mechanism the annotation-processing build uses, so the emitted
 * artifacts are byte-identical to it. This class declares no Spring dependency, but the
 * {@code classpath} handed in by the caller must carry the Spring AI and Spring Web types the
 * generated wrappers reference for the compilation to succeed.
 *
 * <p>Output goes to the {@value #SOURCES_DIR} (Java sources) and {@value #RESOURCES_DIR}
 * (resources) roots inside the given output directory. Each run stages into a private directory
 * and publishes atomically — the previous trees are set aside and restored on failure, so a
 * failed run never leaves partial state. Publication is serialized per output directory by an
 * in-process lock and a cross-process lock file ({@value #LOCK_FILE}). Files under the output
 * directory are excluded from source discovery so nested layouts are safe.
 */
public final class AtlasGenerator {

    /** Name of the generated-sources root inside the output directory. */
    public static final String SOURCES_DIR = "sources";
    /** Name of the generated-resources root inside the output directory. */
    public static final String RESOURCES_DIR = "resources";
    /** Name of the inter-process lock file the driver keeps in the output directory. */
    public static final String LOCK_FILE = ".atlas-lock";
    /**
     * Name prefix of the per-run staging directory the driver creates inside the output directory.
     * It carries a random suffix, lives only for the duration of a run, and never appears in a
     * {@link GenerationResult} — neither in a {@link GeneratedFile} path nor in a {@link Diagnostic}.
     */
    public static final String STAGING_PREFIX = ".atlas-staging";

    private static final String CLASSES_DIR = "classes";
    private static final String BACKUP_DIR = "backup";
    private static final String JAVA_SUFFIX = ".java";
    private static final String CLASS_SUFFIX = ".class";
    private static final String OPTION_PREFIX = "-A";
    private static final String ENCODING_OPTION = "-encoding";
    // Classification: generated type-level annotations and the type declaration are at column 0;
    // caller text only appears inside indented, escaped string literals — whole-line anchors work.
    private static final String REST_CONTROLLER_ANNOTATION = "@RestController";
    private static final String MCP_TOOL_ANNOTATION = "@Service";
    private static final String DTO_DECLARATION_PREFIX = "public record ";
    private static final String LEGACY_OPENAPI_PATH = OpenApiGenerator.RESOURCE_DIR + OpenApiGenerator.LEGACY_RESOURCE_NAME;
    // Serializes threads publishing to the same output directory — FileLock is per-JVM, so an
    // in-process lock is also needed. Keyed by normalized output directory.
    private static final Map<Path, ReentrantLock> OUTPUT_LOCKS = new ConcurrentHashMap<>();

    private AtlasGenerator() {} // package-private on purpose
    /**
     * Runs generation with no processor options, i.e. framework defaults.
     *
     * @see #generate(List, List, Path, Map)
     */
    public static GenerationResult generate(List<Path> sources, List<Path> classpath, Path outputDir) {
        return generate(sources, classpath, outputDir, Map.of());
    }

    /**
     * Compiles {@code sources} with {@link AgenticProcessor}, publishes into {@code outputDir}'s
     * {@value #SOURCES_DIR}/{@value #RESOURCES_DIR} roots, and returns every emitted artifact.
     *
     * @param sources          source roots (scanned recursively) or individual {@code .java} files
     * @param classpath        compile classpath (must carry Spring AI/Spring Web types)
     * @param outputDir        replaced-run output root; normalized absolute. Files under it are
     *                         excluded from discovery, so a nested layout is safe.
     * @param processorOptions {@code -A} options, e.g. {@link AgenticProcessor#OPT_API_MAJOR}
     * @return generated files, OpenAPI document, and diagnostics
     * @throws IllegalArgumentException if no sources found or a path does not exist
     * @throws IllegalStateException    if running on a JRE (no system compiler)
     * @throws UncheckedIOException     if output directories cannot be written
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
        AgenticProcessor processor = new AgenticProcessor();
        Path staging = null;
        boolean success;
        boolean published = false;
        List<GeneratedFile> files = new ArrayList<>();
        try {
            Files.createDirectories(output);
            // Staged inside the output directory so publishing is a same-filesystem move.
            staging = Files.createTempDirectory(output, STAGING_PREFIX);
            Path stagedSources = Files.createDirectories(staging.resolve(SOURCES_DIR));
            Path stagedResources = Files.createDirectories(staging.resolve(RESOURCES_DIR));
            Path stagedClasses = Files.createDirectories(staging.resolve(CLASSES_DIR));
            Path backup = Files.createDirectories(staging.resolve(BACKUP_DIR));

            success = compile(compiler, collector, sourceFiles, classpath,
                    stagedSources, stagedClasses, processorOptions, processor);

            // Only a successful run publishes. A failed compile leaves the staged tree partial (or
            // empty), and publishing it would replace the last good sources/ and resources/ with
            // that wreckage — turning an ordinary compile error into destructive state loss in a
            // shared output directory. On failure the previous trees stay untouched and the caller
            // still gets success=false plus the diagnostics that explain why.
            if (success) {
                harvestResources(stagedClasses, stagedResources);
            }
            if (success && emittedAnnotationOutput(stagedSources, stagedResources)) {
                // Publishing and collecting are one critical section: a concurrent run must not swap
                // the trees out from under this one's manifest, nor publish over a half-published tree.
                ReentrantLock inProcess = OUTPUT_LOCKS.computeIfAbsent(output, dir -> new ReentrantLock());
                inProcess.lock();
                try (FileChannel channel = FileChannel.open(output.resolve(LOCK_FILE),
                             StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock crossProcess = channel.lock()) {
                    OutputPublisher.publish(stagedSources, stagedResources, sourceOut, resourceOut, backup);
                    published = true;
                    files.addAll(collectGenerated(sourceOut, true));
                    files.addAll(collectGenerated(resourceOut, false));
                } finally {
                    inProcess.unlock();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ai-atlas generation failed to write to " + output, e);
        } finally {
            OutputPublisher.deleteRecursively(staging);
        }

        files.sort(Comparator.comparing((GeneratedFile f) -> f.kind().ordinal())
                .thenComparing(GeneratedFile::relativePath));

        // javac names the file it compiled, which for a generated source is the staged copy — a
        // randomly-suffixed path that no longer exists by the time the caller reads the result. Only
        // a run that published can name the surviving copy; a run that published nothing (a failed
        // compile, or nothing to emit) has no file to point at and says so with a relative path.
        DiagnosticMapper diagnostics = published
                ? DiagnosticMapper.republishing(staging, output)
                : DiagnosticMapper.unpublished(staging);
        return new GenerationResult(success, output, files, findOpenApi(files, processorOptions),
                collector.getDiagnostics().stream().map(diagnostics::map).toList(),
                success ? processor.discoveredServices() : List.of());
    }

    /**
     * Compiles and collects generated artifacts entirely in-memory — <em>nothing</em> is written
     * to the filesystem. The returned {@link GeneratedFile#path()} is always {@code null}, and
     * {@link GenerationResult#outputDir()} is always {@code null}.
     *
     * <p>This is the entry point for the {@code atlas inspect} command. Without an output directory
     * there is no source exclusion, so project-local sources are always discovered.
     */
    public static GenerationResult generateInspect(List<Path> sources, List<Path> classpath,
                                                    Map<String, String> processorOptions) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(processorOptions, "processorOptions");

        List<Path> sourceFiles = collectSourceFiles(sources, null);
        if (sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No .java sources found under: " + sources);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available — ai-atlas generation requires a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        AgenticProcessor processor = new AgenticProcessor();
        boolean success;
        List<GeneratedFile> files = new ArrayList<>();
        try (StandardJavaFileManager stdFileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8);
             InMemoryJavaFileManager inMemory = new InMemoryJavaFileManager(stdFileManager)) {

            stdFileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            success = compileInMemory(compiler, collector, sourceFiles, inMemory,
                    processorOptions, processor);

            Map<String, InMemoryJavaFileObject> classOutput =
                    inMemory.capturedAt(StandardLocation.CLASS_OUTPUT);
            Map<String, InMemoryJavaFileObject> resourceFiles = new LinkedHashMap<>();
            if (success) {
                for (var entry : classOutput.entrySet()) {
                    if (entry.getValue().getKind() != JavaFileObject.Kind.CLASS) {
                        resourceFiles.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            if (success) {
                Map<String, InMemoryJavaFileObject> sourceOutput =
                        inMemory.capturedAt(StandardLocation.SOURCE_OUTPUT);
                if (!sourceOutput.isEmpty()
                        || resourceFiles.keySet().stream()
                                .anyMatch(k -> k.startsWith(OpenApiGenerator.RESOURCE_DIR))) {
                    files.addAll(toGeneratedFiles(sourceOutput, true));
                    files.addAll(toGeneratedFiles(resourceFiles, false));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ai-atlas inspection failed", e);
        }

        files.sort(Comparator.comparing((GeneratedFile f) -> f.kind().ordinal())
                .thenComparing(GeneratedFile::relativePath));

        return new GenerationResult(success, null, files, findOpenApi(files, processorOptions),
                // Nothing is staged here — the in-memory run compiles straight from the caller's
                // sources, so every diagnostic path is already the one to report.
                collector.getDiagnostics().stream().map(DiagnosticMapper.passThrough()::map).toList(),
                success ? processor.discoveredServices() : List.of());
    }

    private static boolean compile(JavaCompiler compiler, DiagnosticCollector<JavaFileObject> collector,
                                   List<Path> sourceFiles, List<Path> classpath,
                                   Path sourceOut, Path classOut,
                                   Map<String, String> processorOptions,
                                   AgenticProcessor processor) throws IOException {
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sourceOut));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOut));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);

            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector,
                    compilerOptions(processorOptions), null,
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles));
            task.setProcessors(List.of(processor));
            return Boolean.TRUE.equals(task.call());
        }
    }

    /** Compiles with an in-memory file manager — output is captured, never written to disk. */
    private static boolean compileInMemory(JavaCompiler compiler,
                                           DiagnosticCollector<JavaFileObject> collector,
                                           List<Path> sourceFiles,
                                           InMemoryJavaFileManager fileManager,
                                           Map<String, String> processorOptions,
                                           AgenticProcessor processor) {
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, collector,
                compilerOptions(processorOptions), null,
                fileManager.delegate().getJavaFileObjectsFromPaths(sourceFiles));
        task.setProcessors(List.of(processor));
        return Boolean.TRUE.equals(task.call());
    }

    /** Converts in-memory captured files to {@link GeneratedFile} objects. */
    private static List<GeneratedFile> toGeneratedFiles(Map<String, InMemoryJavaFileObject> captured,
                                                        boolean javaSources) {
        List<GeneratedFile> result = new ArrayList<>();
        for (var entry : captured.entrySet()) {
            String key = entry.getKey();
            try {
                String content = entry.getValue().getContent();
                // Java source keys are class names (dotted); convert to slash-separated paths.
                // Resource keys already use '/' separators as written by the processor's Filer.
                String relative = javaSources
                        ? key.replace('.', '/') + JAVA_SUFFIX
                        : key;
                GeneratedFile.Kind kind = javaSources ? classifySource(content)
                        : classifyResource(relative);
                result.add(new GeneratedFile(kind, relative, null, content));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read in-memory generated file: " + key, e);
            }
        }
        return result;
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
     * Collects the compilation units, skipping anything inside {@code outputDir} when non-null.
     * Without that exclusion a second run over a source root that contains the output directory
     * would feed the previous run's generated sources back to javac, which then clashes with the
     * processor re-creating those same types.
     *
     * <p>When {@code outputDir} is {@code null} (in-memory inspection), no paths are excluded.
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
                            .filter(p -> outputDir == null || !p.startsWith(outputDir))
                            .forEach(files::add);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to scan source directory: " + source, e);
                }
            } else if (outputDir == null || !root.startsWith(outputDir)) {
                files.add(root);
            }
        }
        return files.stream().sorted().toList();
    }

    /**
     * Whether this run produced anything derived from the ai-atlas annotations — a generated source
     * or an OpenAPI document. "The staged tree is non-empty" is a different question: the processor
     * emits {@code api-version.properties} and the deprecation manifest on every round, so a source
     * set carrying no annotation at all still stages those two descriptors.
     */
    private static boolean emittedAnnotationOutput(Path stagedSources, Path stagedResources)
            throws IOException {
        try (Stream<Path> sources = Files.walk(stagedSources);
             Stream<Path> resources = Files.walk(stagedResources)) {
            return sources.filter(Files::isRegularFile)
                           .anyMatch(p -> p.getFileName().toString().endsWith(JAVA_SUFFIX))
                    || resources.filter(Files::isRegularFile)
                           .anyMatch(p -> relativize(stagedResources, p)
                                   .startsWith(OpenApiGenerator.RESOURCE_DIR));
        }
    }

    /** The slash-separated path of {@code file} under {@code root}. */
    private static String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
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
        String relative = relativize(root, file);
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

}
